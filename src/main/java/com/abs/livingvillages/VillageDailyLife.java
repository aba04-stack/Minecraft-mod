package com.abs.livingvillages;

import com.abs.livingvillages.domain.DailyLifeActivityModel;
import com.abs.livingvillages.domain.TickBudgetModel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Low-priority daily-life behavior for adult villagers.
 *
 * <p>During the vanilla IDLE activity, a free villager may make a short visit to one
 * nearby idle resident. The controller never replaces a vanilla WALK_TARGET, does
 * not run during work/meet/rest/panic, and yields to every active mod profession task.</p>
 */
public final class VillageDailyLife {
    private static final String P = "livingvillages_daily_life_";
    private static final String ACTIVE = P + "social_active";
    private static final String TARGET_UUID = P + "social_target_uuid";
    private static final String STARTED = P + "social_started";
    private static final String ARRIVED = P + "social_arrived";
    private static final String LAST_ROUTE_ORDER = P + "social_last_route";
    private static final String NEXT_ATTEMPT = P + "next_social_attempt";
    private static final String CLAIMED_BY = P + "social_claimed_by";
    private static final String LAST_ACTION = P + "last_action";
    private static final String LAST_PARTNER = P + "last_partner";

    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int SEARCH_RADIUS = 10;
    private static final int MAX_CANDIDATES = 4;
    private static final int ROUTE_REISSUE_TICKS = 40;
    private static final int CLOSE_ENOUGH_BLOCKS = 3;
    private static final double CLOSE_ENOUGH_SQR = 3.0D * 3.0D;
    private static final double MAX_SOCIAL_DISTANCE_SQR = 12.0D * 12.0D;
    private static final float WALK_SPEED = 0.55F;

    private VillageDailyLife() {}

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager)
            || villager.level().isClientSide()
            || !villager.isAlive()
            || villager.isBaby()
            || VillagerRoles.isGuard(villager)
            || !(villager.level() instanceof ServerLevel level)) {
            return;
        }

        long now = level.getGameTime();
        if (!TickBudgetModel.isScheduledThisTick(now, villager.getId(), CHECK_INTERVAL_TICKS)) return;

        CompoundTag data = villager.getPersistentData();
        if (!LivingVillagesConfig.ENABLE_DAILY_LIFE.get()) {
            if (data.getBoolean(ACTIVE)) finishVisit(villager, level, data, false);
            return;
        }

        if (data.getBoolean(ACTIVE)) {
            continueVisit(villager, level, data, now);
            return;
        }
        tryStartSocialVisit(villager, level, now);
    }

    static boolean tryStartSocialVisit(Villager villager, ServerLevel level, long now) {
        if (villager == null || level == null || !villager.isAlive() || villager.isBaby()
            || !LivingVillagesConfig.ENABLE_DAILY_LIFE.get()) return false;
        CompoundTag data = villager.getPersistentData();
        if (data.getBoolean(ACTIVE)
            || now < data.getLong(NEXT_ATTEMPT)
            || !isVanillaIdle(villager)
            || villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
            || VillagerRoles.shouldYieldToVanillaVillagerAi(villager)
            || VillagerRoles.isGuard(villager)
            || hasHigherPriorityTask(data)
            || !data.getString(CLAIMED_BY).isBlank()
            || isVillageAlarmActive(villager, level, now)
            || !VillageTickBudget.tryConsumeAiCheck(level)) {
            return false;
        }

        Villager peer = findAvailablePeer(villager, level);
        boolean canStart = DailyLifeActivityModel.canStartSocialVisit(
            villager.isAlive(),
            !villager.isBaby(),
            isVanillaIdle(villager),
            hasHigherPriorityTask(data) || !data.getString(CLAIMED_BY).isBlank(),
            villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
            isVillageAlarmActive(villager, level, now),
            peer != null,
            now,
            data.getLong(NEXT_ATTEMPT)
        );
        if (!canStart || peer == null) {
            if (peer == null) data.putLong(NEXT_ATTEMPT,
                DailyLifeActivityModel.nextAttemptTick(now, false));
            return false;
        }

        if (!claimPeer(villager, peer, level)) {
            data.putLong(NEXT_ATTEMPT, DailyLifeActivityModel.nextAttemptTick(now, false));
            return false;
        }

        data.putBoolean(ACTIVE, true);
        data.putString(TARGET_UUID, peer.getUUID().toString());
        data.putLong(STARTED, now);
        data.putLong(ARRIVED, 0L);
        data.putLong(LAST_ROUTE_ORDER, now);
        setRoute(villager, peer);
        data.putString(LAST_ACTION, "visiting");
        return true;
    }

    private static void continueVisit(Villager villager, ServerLevel level, CompoundTag data, long now) {
        Villager peer = findVillager(level, villager, data.getString(TARGET_UUID));
        long started = data.getLong(STARTED);
        boolean timedOut = started <= 0L
            || now - started > DailyLifeActivityModel.ROUTE_TIMEOUT_TICKS;
        boolean alarm = isVillageAlarmActive(villager, level, now);
        boolean cancel = DailyLifeActivityModel.shouldCancelSocialVisit(
            villager.isAlive(),
            peer != null && peer.isAlive(),
            isVanillaIdle(villager) && peer != null && isVanillaIdle(peer),
            hasHigherPriorityTask(data) || (peer != null && hasHigherPriorityTask(peer.getPersistentData())),
            alarm,
            timedOut
        );
        if (cancel) {
            finishVisit(villager, level, data, false);
            return;
        }

        if (villager.distanceToSqr(peer) <= CLOSE_ENOUGH_SQR) {
            clearOwnedRoute(villager, peer);
            long arrived = data.getLong(ARRIVED);
            if (arrived <= 0L) {
                data.putLong(ARRIVED, now);
                arrived = now;
            }
            villager.getLookControl().setLookAt(peer, 25.0F, 25.0F);
            if (!peer.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                peer.getLookControl().setLookAt(villager, 20.0F, 20.0F);
            }
            if (now - arrived >= DailyLifeActivityModel.SOCIAL_DURATION_TICKS) {
                finishVisit(villager, level, data, true);
            }
            return;
        }

        if (villager.distanceToSqr(peer) > MAX_SOCIAL_DISTANCE_SQR) {
            finishVisit(villager, level, data, false);
            return;
        }
        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
            && now - data.getLong(LAST_ROUTE_ORDER) >= ROUTE_REISSUE_TICKS) {
            Path path = villager.getNavigation().createPath(peer, CLOSE_ENOUGH_BLOCKS);
            if (path == null || !path.canReach()) {
                finishVisit(villager, level, data, false);
                return;
            }
            setRoute(villager, peer);
            data.putLong(LAST_ROUTE_ORDER, now);
        }
    }

    private static Villager findAvailablePeer(Villager villager, ServerLevel level) {
        AABB search = villager.getBoundingBox().inflate(SEARCH_RADIUS, 4.0D, SEARCH_RADIUS);
        List<Villager> candidates = level.getEntitiesOfClass(
            Villager.class,
            search,
            candidate -> candidate != villager
                && candidate.isAlive()
                && !candidate.isBaby()
                && candidate.distanceToSqr(villager) >= 4.0D
                && candidate.distanceToSqr(villager) <= MAX_SOCIAL_DISTANCE_SQR
                && !candidate.getPersistentData().getBoolean(ACTIVE)
                && !VillagerRoles.isGuard(candidate)
                && isSameVillageIfKnown(villager, candidate, level)
                && isVanillaIdle(candidate)
                && !candidate.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                && !VillagerRoles.shouldYieldToVanillaVillagerAi(candidate)
                && !hasHigherPriorityTask(candidate.getPersistentData())
                && !isVillageAlarmActive(candidate, level, level.getGameTime())
        );
        candidates.sort(Comparator
            .comparingDouble((Villager candidate) -> candidate.distanceToSqr(villager))
            .thenComparing(candidate -> candidate.getUUID().toString()));

        int checked = 0;
        for (Villager candidate : candidates) {
            if (checked++ >= MAX_CANDIDATES) break;
            if (!DailyLifeActivityModel.canAcceptSocialVisitor(activeVisitorCount(level, candidate))) continue;
            if (!VillageTickBudget.tryConsumeAiCheck(level)) break;
            Path path = villager.getNavigation().createPath(candidate, CLOSE_ENOUGH_BLOCKS);
            if (path != null && path.canReach()) return candidate;
        }
        return null;
    }

    private static boolean isSameVillageIfKnown(Villager first, Villager second, ServerLevel level) {
        UUID firstVillage = VillageRegistry.knownVillageIdForResident(level, first.getUUID());
        UUID secondVillage = VillageRegistry.knownVillageIdForResident(level, second.getUUID());
        return firstVillage == null || secondVillage == null || firstVillage.equals(secondVillage);
    }

    private static int activeVisitorCount(ServerLevel level, Villager target) {
        String ownerId = target.getPersistentData().getString(CLAIMED_BY);
        if (ownerId.isBlank()) return 0;
        AABB area = target.getBoundingBox().inflate(16.0D, 8.0D, 16.0D);
        List<Villager> possibleOwner = level.getEntitiesOfClass(
            Villager.class, area, candidate -> candidate.getUUID().toString().equals(ownerId));
        if (possibleOwner.isEmpty()) {
            target.getPersistentData().remove(CLAIMED_BY);
            return 0;
        }
        Villager visitor = possibleOwner.get(0);
        CompoundTag visitorData = visitor.getPersistentData();
        boolean claimStillValid = visitor.isAlive()
            && visitorData.getBoolean(ACTIVE)
            && visitorData.getString(TARGET_UUID).equals(target.getUUID().toString());
        if (!claimStillValid) {
            target.getPersistentData().remove(CLAIMED_BY);
            return 0;
        }
        return 1;
    }

    private static boolean claimPeer(Villager visitor, Villager target, ServerLevel level) {
        if (activeVisitorCount(level, target) >= DailyLifeActivityModel.MAX_VISITORS_PER_RESIDENT) return false;
        target.getPersistentData().putString(CLAIMED_BY, visitor.getUUID().toString());
        return true;
    }

    private static Villager findVillager(ServerLevel level, Villager visitor, String uuidText) {
        if (uuidText == null || uuidText.isBlank()) return null;
        AABB area = visitor.getBoundingBox().inflate(SEARCH_RADIUS + 8.0D, 8.0D, SEARCH_RADIUS + 8.0D);
        for (Villager candidate : level.getEntitiesOfClass(
            Villager.class, area, v -> v.isAlive() && v.getUUID().toString().equals(uuidText))) {
            return candidate;
        }
        return null;
    }

    private static boolean isVanillaIdle(Villager villager) {
        return villager.getBrain().getActiveNonCoreActivity().orElse(null) == Activity.IDLE;
    }

    private static boolean isVillageAlarmActive(Villager villager, ServerLevel level, long now) {
        net.minecraft.core.BlockPos center = VillagerRoles.getHome(villager);
        if (center == null) center = villager.blockPosition();
        return VillageRegistry.isVillageAlarmActive(level, center, now);
    }

    private static boolean hasHigherPriorityTask(CompoundTag data) {
        return data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route")
            || data.getBoolean("livingvillages_logistics_active")
            || data.getBoolean("livingvillages_industry_route_active")
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || data.getBoolean(VillageBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean(VillagerAutonomy.HARVEST_ACTIVE_KEY)
            || data.getBoolean(VillagerAutonomy.HARVEST_CYCLE_KEY)
            || data.getBoolean(VillagerAutonomy.SEED_FETCH_ACTIVE_KEY)
            || data.getBoolean("livingvillages_autonomy_target_active");
    }

    private static void setRoute(Villager villager, Villager target) {
        villager.getBrain().setMemory(
            MemoryModuleType.WALK_TARGET,
            new WalkTarget(target, WALK_SPEED, CLOSE_ENOUGH_BLOCKS));
    }

    private static void clearOwnedRoute(Villager villager, Villager target) {
        villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
            if (walk.getTarget().currentBlockPosition().distSqr(target.blockPosition()) <= 4.0D) {
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }
        });
    }

    static void finishVisit(Villager villager, ServerLevel level, CompoundTag data, boolean completed) {
        String targetId = data.getString(TARGET_UUID);
        Villager target = findVillager(level, villager, targetId);
        if (target != null) {
            clearOwnedRoute(villager, target);
            CompoundTag targetData = target.getPersistentData();
            if (targetData.getString(CLAIMED_BY).equals(villager.getUUID().toString())) {
                targetData.remove(CLAIMED_BY);
            }
            if (completed) {
                data.putString(LAST_PARTNER, target.getUUID().toString());
            }
        }
        data.putBoolean(ACTIVE, false);
        data.putLong(NEXT_ATTEMPT,
            DailyLifeActivityModel.nextAttemptTick(level.getGameTime(), completed));
        data.putString(LAST_ACTION, completed ? "conversation" : "visit_cancelled");
        data.remove(TARGET_UUID);
        data.remove(STARTED);
        data.remove(ARRIVED);
        data.remove(LAST_ROUTE_ORDER);
    }
}
