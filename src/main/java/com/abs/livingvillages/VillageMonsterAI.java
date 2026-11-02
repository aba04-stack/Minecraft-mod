package com.abs.livingvillages;

import com.abs.livingvillages.domain.MonsterTacticalModel;
import com.abs.livingvillages.domain.TickBudgetModel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Comparator;
import java.util.List;

/**
 * Server-side tactical monster AI adapter backed by {@link MonsterTacticalModel}.
 *
 * <p>Enhances vanilla hostile mobs through honest perception (line of sight and
 * bounded activity signals), finite state timeouts, path-retry caps, and tactical
 * positioning without inflating HP/damage or breaking {@code mobGriefing}.</p>
 */
public final class VillageMonsterAI {
    public static final String PREFIX = "livingvillages_monster_ai_";
    public static final String STATE_KEY = PREFIX + "state";
    public static final String STATE_STARTED_KEY = PREFIX + "state_started";
    public static final String FAILED_PATHS_KEY = PREFIX + "failed_paths";
    public static final String TARGET_X_KEY = PREFIX + "target_x";
    public static final String TARGET_Y_KEY = PREFIX + "target_y";
    public static final String TARGET_Z_KEY = PREFIX + "target_z";
    public static final String LAST_SUPPORT_KEY = PREFIX + "last_support";
    public static final String INITIAL_SQUAD_KEY = PREFIX + "initial_squad";

    private static final int TICK_INTERVAL = 20;
    private static final double SIGHT_RANGE_BLOCKS = 20.0D;
    private static final double SOUND_RANGE_BLOCKS = 16.0D;
    private static final long SUPPORT_COOLDOWN_TICKS = 160L;

    private VillageMonsterAI() {}

    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob)
            || mob.level().isClientSide()
            || !mob.isAlive()
            || !(mob instanceof Enemy)
            || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_TACTICAL_MONSTER_AI.get()) {
            return;
        }

        int mobKind = classifyMob(mob);
        if (mobKind == 0) return;

        long now = level.getGameTime();
        if (!TickBudgetModel.isScheduledThisTick(now, mob.getUUID().hashCode(), TICK_INTERVAL)) {
            return;
        }
        if (!level.hasChunkAt(mob.blockPosition())) {
            return;
        }
        if (!VillageTickBudget.tryConsumeAiCheck(level)) {
            return;
        }

        evaluateMobTactics(mob, level, mobKind, now);
    }

    public static int classifyMob(Mob mob) {
        if (mob instanceof Zombie) return MonsterTacticalModel.KIND_ZOMBIE;
        if (mob instanceof AbstractSkeleton) return MonsterTacticalModel.KIND_SKELETON;
        if (mob instanceof Creeper) return MonsterTacticalModel.KIND_CREEPER;
        if (mob instanceof Spider) return MonsterTacticalModel.KIND_SPIDER;
        if (mob instanceof Witch) return MonsterTacticalModel.KIND_WITCH;
        if (mob instanceof Raider) return MonsterTacticalModel.KIND_RAIDER;
        return 0;
    }

    /**
     * Evaluate a single tactical step for a hostile mob in a loaded chunk.
     */
    public static MonsterTacticalModel.TacticalDecision evaluateMobTactics(
            Mob mob,
            ServerLevel level,
            int mobKind,
            long now) {
        CompoundTag data = mob.getPersistentData();
        int currentState = data.getInt(STATE_KEY);
        long stateStarted = data.contains(STATE_STARTED_KEY) ? data.getLong(STATE_STARTED_KEY) : now;
        long elapsed = Math.max(0L, now - stateStarted);
        int failedPaths = data.getInt(FAILED_PATHS_KEY);

        int radius = LivingVillagesConfig.MONSTER_TACTICAL_RADIUS_BLOCKS.get();
        double maxSightSqr = Math.min(SIGHT_RANGE_BLOCKS, radius) * Math.min(SIGHT_RANGE_BLOCKS, radius);
        double maxSoundSqr = Math.min(SOUND_RANGE_BLOCKS, radius) * Math.min(SOUND_RANGE_BLOCKS, radius);

        LivingEntity candidate = findHonestTarget(mob, level, radius, maxSightSqr);
        boolean hasLos = candidate != null && hasClearLineOfSight(level, mob, candidate);
        double distSqr = candidate != null ? mob.distanceToSqr(candidate) : Double.MAX_VALUE;

        BlockPos soundPos = candidate == null
            ? VillageTickBudget.findRecentSignalNear(level, mob.blockPosition(), maxSoundSqr)
            : null;
        boolean hasSoundSignal = soundPos != null && level.hasChunkAt(soundPos);
        if (candidate == null && hasSoundSignal) {
            distSqr = mob.blockPosition().distSqr(soundPos);
        }

        boolean perceived = MonsterTacticalModel.canPerceiveTarget(
            hasLos,
            distSqr,
            maxSightSqr,
            hasSoundSignal,
            maxSoundSqr
        );

        BlockPos destination = candidate != null ? candidate.blockPosition() : soundPos;
        boolean pathSucceeded = false;
        if (perceived && destination != null && level.hasChunkAt(destination)) {
            pathSucceeded = canNavigateToward(mob, destination);
        }

        AABB localBox = mob.getBoundingBox().inflate(12.0D, 6.0D, 12.0D);
        int nearbyAllies = level.getEntitiesOfClass(Mob.class, localBox,
            other -> other != mob && other.isAlive() && other instanceof Enemy).size();
        int nearbyDefenders = level.getEntitiesOfClass(Villager.class, localBox,
            v -> v.isAlive() && VillagerRoles.isGuard(v)).size();

        boolean mobGriefing = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        double healthRatio = mob.getMaxHealth() > 0.0F ? (mob.getHealth() / mob.getMaxHealth()) : 1.0D;

        MonsterTacticalModel.TacticalDecision decision = MonsterTacticalModel.evaluateTacticalStep(
            mobKind,
            currentState,
            elapsed,
            MonsterTacticalModel.DEFAULT_STATE_TIMEOUT_TICKS,
            failedPaths,
            perceived,
            hasLos,
            pathSucceeded,
            distSqr,
            healthRatio,
            nearbyAllies,
            nearbyDefenders,
            mobGriefing
        );

        if (decision.nextState() != currentState) {
            data.putInt(STATE_KEY, decision.nextState());
            data.putLong(STATE_STARTED_KEY, now);
        }
        data.putInt(FAILED_PATHS_KEY, decision.failedPathAttempts());

        if (decision.resetTarget()) {
            mob.getNavigation().stop();
            data.remove(TARGET_X_KEY);
            data.remove(TARGET_Y_KEY);
            data.remove(TARGET_Z_KEY);
            return decision;
        }

        if (destination != null && level.hasChunkAt(destination)) {
            applyTacticalAction(mob, level, decision.tacticalAction(), candidate, destination, localBox, now, data);
        }
        return decision;
    }

    /**
     * Find a visible target within radius without x-raying through solid blocks.
     */
    public static LivingEntity findHonestTarget(Mob mob, ServerLevel level, int radius, double maxSightSqr) {
        LivingEntity currentTarget = mob.getTarget();
        if (currentTarget != null && currentTarget.isAlive()
            && level.hasChunkAt(currentTarget.blockPosition())
            && mob.distanceToSqr(currentTarget) <= maxSightSqr
            && hasClearLineOfSight(level, mob, currentTarget)) {
            return currentTarget;
        }

        AABB searchArea = mob.getBoundingBox().inflate(radius, 8.0D, radius);
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, searchArea,
            v -> v.isAlive() && level.hasChunkAt(v.blockPosition())
                && mob.distanceToSqr(v) <= maxSightSqr
                && hasClearLineOfSight(level, mob, v));
        if (!villagers.isEmpty()) {
            villagers.sort(Comparator.comparingDouble(mob::distanceToSqr));
            return villagers.get(0);
        }

        List<Player> players = level.getEntitiesOfClass(Player.class, searchArea,
            p -> p.isAlive() && !p.isSpectator() && !p.isCreative()
                && level.hasChunkAt(p.blockPosition())
                && mob.distanceToSqr(p) <= maxSightSqr
                && hasClearLineOfSight(level, mob, p));
        if (!players.isEmpty()) {
            players.sort(Comparator.comparingDouble(mob::distanceToSqr));
            return players.get(0);
        }
        return null;
    }

    public static boolean hasClearLineOfSight(ServerLevel level, LivingEntity observer, LivingEntity target) {
        if (level == null || observer == null || target == null) return false;
        if (!level.hasChunkAt(observer.blockPosition()) || !level.hasChunkAt(target.blockPosition())) {
            return false;
        }
        Vec3 from = observer.getEyePosition();
        Vec3 to = target.getEyePosition();
        HitResult hit = level.clip(new ClipContext(
            from,
            to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            observer
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean canNavigateToward(Mob mob, BlockPos targetPos) {
        var path = mob.getNavigation().createPath(targetPos, 1);
        return path != null && path.canReach();
    }

    private static void applyTacticalAction(
            Mob mob,
            ServerLevel level,
            int action,
            LivingEntity target,
            BlockPos destination,
            AABB localBox,
            long now,
            CompoundTag data) {
        data.putInt(TARGET_X_KEY, destination.getX());
        data.putInt(TARGET_Y_KEY, destination.getY());
        data.putInt(TARGET_Z_KEY, destination.getZ());

        switch (action) {
            case MonsterTacticalModel.ACTION_GROUP_ADVANCE -> {
                if (target != null) mob.setTarget(target);
                mob.getNavigation().moveTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D, 1.0D);
                // Alert nearby zombies that have direct line of sight to this zombie.
                List<Zombie> allies = level.getEntitiesOfClass(Zombie.class, localBox,
                    z -> z != mob && z.isAlive() && z.getTarget() == null && hasClearLineOfSight(level, mob, z));
                for (int i = 0; i < Math.min(2, allies.size()); i++) {
                    Zombie ally = allies.get(i);
                    if (target != null) ally.setTarget(target);
                }
            }
            case MonsterTacticalModel.ACTION_HOLD_RANGE_OR_STRAFE -> {
                if (target != null) {
                    Vec3 away = mob.position().subtract(target.position());
                    if (away.lengthSqr() > 0.01D && away.lengthSqr() < 36.0D) {
                        Vec3 norm = away.normalize().scale(4.0D);
                        BlockPos stepBack = BlockPos.containing(mob.getX() + norm.x, mob.getY(), mob.getZ() + norm.z);
                        if (level.hasChunkAt(stepBack)) {
                            mob.getNavigation().moveTo(stepBack.getX() + 0.5D, stepBack.getY(), stepBack.getZ() + 0.5D, 1.05D);
                        }
                    }
                }
            }
            case MonsterTacticalModel.ACTION_CAUTIOUS_APPROACH, MonsterTacticalModel.ACTION_VERTICAL_FLANK -> {
                if (target != null) mob.setTarget(target);
                mob.getNavigation().moveTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D, 1.0D);
            }
            case MonsterTacticalModel.ACTION_SUPPORT_ALLIES -> {
                if (now - data.getLong(LAST_SUPPORT_KEY) >= SUPPORT_COOLDOWN_TICKS) {
                    List<Mob> woundedAllies = level.getEntitiesOfClass(Mob.class, localBox,
                        ally -> ally != mob && ally.isAlive() && ally instanceof Enemy
                            && !(ally instanceof Zombie) && ally.getHealth() < ally.getMaxHealth() * 0.75F);
                    if (!woundedAllies.isEmpty()) {
                        Mob wounded = woundedAllies.get(0);
                        wounded.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 80, 0));
                        data.putLong(LAST_SUPPORT_KEY, now);
                    }
                }
            }
            case MonsterTacticalModel.ACTION_SCOUT_OR_RETREAT -> {
                if (target != null) {
                    Vec3 retreatDir = mob.position().subtract(target.position());
                    if (retreatDir.lengthSqr() > 0.01D) {
                        Vec3 norm = retreatDir.normalize().scale(6.0D);
                        BlockPos fallback = BlockPos.containing(mob.getX() + norm.x, mob.getY(), mob.getZ() + norm.z);
                        if (level.hasChunkAt(fallback)) {
                            mob.getNavigation().moveTo(fallback.getX() + 0.5D, fallback.getY(), fallback.getZ() + 0.5D, 1.1D);
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * Evaluates organized raider squad state (Stage 5.2): records the initial squad size in a
     * 16-block radius around the raider, checks current living squad members, and forces
     * disengagement/retreat if squad losses reach 50%.
     */
    public static int evaluateRaiderSquadTactics(Raider raider, ServerLevel level, boolean villageScouted) {
        if (raider == null || level == null || !raider.isAlive()) {
            return com.abs.livingvillages.domain.SettlementSimulationModel.SQUAD_RETREAT;
        }
        CompoundTag data = raider.getPersistentData();
        AABB squadArea = raider.getBoundingBox().inflate(16.0D, 8.0D, 16.0D);
        int currentLiving = level.getEntitiesOfClass(Raider.class, squadArea,
            other -> other.isAlive() && !other.isRemoved()).size();

        int initialSquad = data.getInt(INITIAL_SQUAD_KEY);
        if (initialSquad <= 0 || currentLiving > initialSquad) {
            initialSquad = Math.max(1, currentLiving);
            data.putInt(INITIAL_SQUAD_KEY, initialSquad);
        }

        int squadDecision = com.abs.livingvillages.domain.SettlementSimulationModel.evaluateRaiderSquad(
            villageScouted,
            initialSquad,
            currentLiving
        );
        if (squadDecision == com.abs.livingvillages.domain.SettlementSimulationModel.SQUAD_RETREAT) {
            raider.setTarget(null);
            raider.getNavigation().stop();
            data.putInt(STATE_KEY, MonsterTacticalModel.STATE_DISENGAGE);
        }
        return squadDecision;
    }
}
