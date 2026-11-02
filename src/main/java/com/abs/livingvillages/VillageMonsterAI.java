package com.abs.livingvillages;

import com.abs.livingvillages.domain.MonsterTacticalModel;
import com.abs.livingvillages.domain.TickBudgetModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * bounded activity signals), finite state timeouts, path-retry caps, last-known-position
 * search decay, Pillager patrol routes, Skeleton anti-clustering & cover, Spider
 * multi-angle flanking & ambush, Creeper ally-safe approach timing, and Witch
 * health-triaged ally support without inflating HP/damage or breaking {@code mobGriefing}.</p>
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
    public static final String LAST_KNOWN_X_KEY = PREFIX + "last_known_x";
    public static final String LAST_KNOWN_Y_KEY = PREFIX + "last_known_y";
    public static final String LAST_KNOWN_Z_KEY = PREFIX + "last_known_z";
    public static final String HAS_LAST_KNOWN_KEY = PREFIX + "has_last_known";
    public static final String SEARCH_STEPS_KEY = PREFIX + "search_steps";
    public static final String PATROL_X_KEY = PREFIX + "patrol_x";
    public static final String PATROL_Y_KEY = PREFIX + "patrol_y";
    public static final String PATROL_Z_KEY = PREFIX + "patrol_z";
    public static final String HAS_PATROL_KEY = PREFIX + "has_patrol";
    public static final String PATROL_STEP_KEY = PREFIX + "patrol_step";
    public static final String UNREACHABLE_UNTIL_KEY = PREFIX + "unreachable_until";

    private static final int TICK_INTERVAL = 20;
    private static final double SIGHT_RANGE_BLOCKS = 20.0D;
    private static final double SOUND_RANGE_BLOCKS = 16.0D;
    private static final long SUPPORT_COOLDOWN_TICKS = 160L;
    private static final long UNREACHABLE_BACKOFF_TICKS = 80L;

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
        if (mob instanceof Raider raider) {
            ensureRaiderPatrolOrigin(raider, data);
        }

        if (now < data.getLong(UNREACHABLE_UNTIL_KEY)) {
            return new MonsterTacticalModel.TacticalDecision(
                MonsterTacticalModel.STATE_DISENGAGE,
                MonsterTacticalModel.ACTION_NONE,
                data.getInt(FAILED_PATHS_KEY),
                false
            );
        }

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
        if (perceived && destination != null) {
            recordLastKnownPosition(data, destination);
        }

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

        // When target is lost (!perceived), run gradual search of last known position or return to patrol.
        if (!perceived && currentState != MonsterTacticalModel.STATE_DISENGAGE) {
            MonsterTacticalModel.TacticalDecision searchOrPatrol = stepLastKnownSearchOrPatrol(mob, level, data, now);
            if (searchOrPatrol != null) {
                return searchOrPatrol;
            }
        }

        // Refine per-archetype tactical action for Skeletons, Spiders, Creepers, and Witches.
        if (perceived && !decision.resetTarget()) {
            int refinedAction = refineArchetypeAction(
                mob,
                level,
                mobKind,
                decision.tacticalAction(),
                candidate,
                hasLos,
                pathSucceeded,
                distSqr,
                localBox,
                mobGriefing,
                now,
                data
            );
            decision = new MonsterTacticalModel.TacticalDecision(
                decision.nextState(),
                refinedAction,
                decision.failedPathAttempts(),
                decision.resetTarget()
            );
        }

        if (decision.nextState() != currentState) {
            data.putInt(STATE_KEY, decision.nextState());
            data.putLong(STATE_STARTED_KEY, now);
        }
        data.putInt(FAILED_PATHS_KEY, decision.failedPathAttempts());

        if (mob instanceof Raider raider) {
            int squadState = evaluateRaiderSquadTactics(raider, level, perceived);
            if (squadState == com.abs.livingvillages.domain.SettlementSimulationModel.SQUAD_RETREAT) {
                return new MonsterTacticalModel.TacticalDecision(
                    MonsterTacticalModel.STATE_DISENGAGE,
                    MonsterTacticalModel.ACTION_SCOUT_OR_RETREAT,
                    decision.failedPathAttempts(),
                    true
                );
            }
            if (perceived && destination != null) {
                alertNearbyRaiders(raider, level, candidate, destination);
            }
        }

        if (decision.resetTarget()) {
            mob.getNavigation().stop();
            data.remove(TARGET_X_KEY);
            data.remove(TARGET_Y_KEY);
            data.remove(TARGET_Z_KEY);
            if (decision.failedPathAttempts() >= MonsterTacticalModel.MAX_PATH_RETRIES) {
                data.putLong(UNREACHABLE_UNTIL_KEY, now + UNREACHABLE_BACKOFF_TICKS);
            }
            return decision;
        }

        if (destination != null && level.hasChunkAt(destination)) {
            applyTacticalAction(mob, level, decision.tacticalAction(), candidate, destination, localBox, now, data);
        }
        return decision;
    }

    public static void recordLastKnownPosition(CompoundTag data, BlockPos pos) {
        if (data == null || pos == null) return;
        data.putBoolean(HAS_LAST_KNOWN_KEY, true);
        data.putInt(LAST_KNOWN_X_KEY, pos.getX());
        data.putInt(LAST_KNOWN_Y_KEY, pos.getY());
        data.putInt(LAST_KNOWN_Z_KEY, pos.getZ());
        data.putInt(SEARCH_STEPS_KEY, MonsterTacticalModel.DEFAULT_SEARCH_STEPS);
    }

    public static void ensureRaiderPatrolOrigin(Raider raider, CompoundTag data) {
        if (raider == null || data == null || data.getBoolean(HAS_PATROL_KEY)) return;
        BlockPos origin = raider.blockPosition();
        data.putBoolean(HAS_PATROL_KEY, true);
        data.putInt(PATROL_X_KEY, origin.getX());
        data.putInt(PATROL_Y_KEY, origin.getY());
        data.putInt(PATROL_Z_KEY, origin.getZ());
    }

    /**
     * Broadcasts threat information from a Pillager/Raider to nearby Raider allies within 16 blocks.
     */
    public static int alertNearbyRaiders(
            Raider source,
            ServerLevel level,
            LivingEntity target,
            BlockPos threatPos) {
        if (source == null || level == null || threatPos == null) return 0;
        AABB alertBox = source.getBoundingBox().inflate(16.0D, 6.0D, 16.0D);
        List<Raider> allies = level.getEntitiesOfClass(Raider.class, alertBox,
            other -> other != source && other.isAlive() && !other.isRemoved());
        int alerted = 0;
        for (Raider ally : allies) {
            CompoundTag allyData = ally.getPersistentData();
            recordLastKnownPosition(allyData, threatPos);
            if (allyData.getInt(STATE_KEY) == MonsterTacticalModel.STATE_IDLE
                || allyData.getInt(STATE_KEY) == MonsterTacticalModel.STATE_PATROLLING) {
                allyData.putInt(STATE_KEY, MonsterTacticalModel.STATE_ALERTED);
            }
            if (target != null && ally.getTarget() == null) {
                ally.setTarget(target);
            }
            alerted++;
        }
        return alerted;
    }

    /**
     * Executes gradual search of the last known enemy position or returns a Pillager/Raider
     * to its small patrol route after combat ends.
     */
    public static MonsterTacticalModel.TacticalDecision stepLastKnownSearchOrPatrol(
            Mob mob,
            ServerLevel level,
            CompoundTag data,
            long now) {
        boolean hasLastKnown = data.getBoolean(HAS_LAST_KNOWN_KEY);
        int searchSteps = data.getInt(SEARCH_STEPS_KEY);
        boolean hasPatrol = data.getBoolean(HAS_PATROL_KEY);

        if (!hasLastKnown && !hasPatrol) {
            return null;
        }

        var decay = MonsterTacticalModel.evaluateSearchDecay(hasLastKnown, searchSteps, hasPatrol);
        data.putInt(SEARCH_STEPS_KEY, decay.remainingSearchSteps());
        data.putInt(STATE_KEY, decay.nextState());
        data.putLong(STATE_STARTED_KEY, now);

        if (decay.shouldMoveToLastKnown()) {
            BlockPos lastKnown = new BlockPos(
                data.getInt(LAST_KNOWN_X_KEY),
                data.getInt(LAST_KNOWN_Y_KEY),
                data.getInt(LAST_KNOWN_Z_KEY)
            );
            if (decay.remainingSearchSteps() == 0) {
                data.putBoolean(HAS_LAST_KNOWN_KEY, false);
            }
            if (level.hasChunkAt(lastKnown)) {
                mob.getNavigation().moveTo(lastKnown.getX() + 0.5D, lastKnown.getY(), lastKnown.getZ() + 0.5D, 1.0D);
            }
            return new MonsterTacticalModel.TacticalDecision(
                MonsterTacticalModel.STATE_SEARCHING_LAST_KNOWN,
                MonsterTacticalModel.ACTION_INVESTIGATE_NOISE,
                data.getInt(FAILED_PATHS_KEY),
                false
            );
        }

        data.putBoolean(HAS_LAST_KNOWN_KEY, false);
        if (decay.returnToPatrol()) {
            BlockPos origin = new BlockPos(
                data.getInt(PATROL_X_KEY),
                data.getInt(PATROL_Y_KEY),
                data.getInt(PATROL_Z_KEY)
            );
            int stepIdx = data.getInt(PATROL_STEP_KEY);
            int[] offset = MonsterTacticalModel.computePatrolWaypointOffset(stepIdx, 5);
            data.putInt(PATROL_STEP_KEY, (stepIdx + 1) % 4);
            BlockPos waypoint = origin.offset(offset[0], 0, offset[1]);
            if (level.hasChunkAt(waypoint)) {
                mob.getNavigation().moveTo(waypoint.getX() + 0.5D, waypoint.getY(), waypoint.getZ() + 0.5D, 0.9D);
            }
            return new MonsterTacticalModel.TacticalDecision(
                MonsterTacticalModel.STATE_PATROLLING,
                MonsterTacticalModel.ACTION_PATROL_STEP,
                0,
                false
            );
        }
        return new MonsterTacticalModel.TacticalDecision(
            MonsterTacticalModel.STATE_IDLE,
            MonsterTacticalModel.ACTION_NONE,
            0,
            false
        );
    }

    private static int refineArchetypeAction(
            Mob mob,
            ServerLevel level,
            int mobKind,
            int baseAction,
            LivingEntity target,
            boolean hasLos,
            boolean pathSucceeded,
            double distSqr,
            AABB localBox,
            boolean mobGriefing,
            long now,
            CompoundTag data) {
        return switch (mobKind) {
            case MonsterTacticalModel.KIND_SKELETON -> {
                AABB clusterBox = mob.getBoundingBox().inflate(3.5D, 2.0D, 3.5D);
                int clusteredSkeletons = level.getEntitiesOfClass(AbstractSkeleton.class, clusterBox,
                    other -> other != mob && other.isAlive()).size();
                boolean underFire = mob.getLastHurtByMob() != null
                    && (mob.tickCount - mob.getLastHurtByMobTimestamp()) <= 60;
                yield MonsterTacticalModel.selectSkeletonCombatAction(
                    distSqr,
                    hasLos,
                    clusteredSkeletons,
                    underFire
                );
            }
            case MonsterTacticalModel.KIND_SPIDER -> {
                boolean nearWall = hasClimbableVerticalSurfaceNear(level, mob.blockPosition());
                boolean ambushReady = data.getInt(STATE_KEY) == MonsterTacticalModel.STATE_IDLE
                    || data.getInt(STATE_KEY) == MonsterTacticalModel.STATE_ALERTED;
                yield MonsterTacticalModel.selectSpiderTacticalAction(
                    distSqr,
                    hasLos,
                    nearWall,
                    ambushReady
                );
            }
            case MonsterTacticalModel.KIND_CREEPER -> {
                int alliesNearTarget = 0;
                if (target != null) {
                    AABB targetBlastBox = target.getBoundingBox().inflate(3.5D, 2.5D, 3.5D);
                    alliesNearTarget = level.getEntitiesOfClass(Mob.class, targetBlastBox,
                        other -> other != mob && other.isAlive() && other instanceof Enemy).size();
                }
                yield MonsterTacticalModel.selectCreeperTacticalAction(
                    distSqr,
                    hasLos,
                    pathSucceeded,
                    alliesNearTarget,
                    mobGriefing
                );
            }
            default -> baseAction;
        };
    }

    public static boolean hasClimbableVerticalSurfaceNear(ServerLevel level, BlockPos origin) {
        if (level == null || origin == null || !level.hasChunkAt(origin)) return false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos wall = origin.relative(dir);
            if (!level.hasChunkAt(wall)) continue;
            if (level.getBlockState(wall).blocksMotion()
                && level.getBlockState(wall.above()).blocksMotion()) {
                return true;
            }
        }
        return false;
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

    /**
     * Selects the most critically wounded ally in {@code localBox} for Witch potion support.
     */
    public static Mob selectPriorityAllyForWitchSupport(Mob witch, ServerLevel level, AABB localBox) {
        List<Mob> allies = level.getEntitiesOfClass(Mob.class, localBox,
            ally -> ally != witch && ally.isAlive() && ally instanceof Enemy);
        if (allies.isEmpty()) return null;
        allies.sort(Comparator
            .comparingDouble((Mob ally) -> ally.getMaxHealth() > 0.0F ? (ally.getHealth() / ally.getMaxHealth()) : 1.0D)
            .thenComparing(ally -> ally.getUUID().toString()));
        return allies.get(0);
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
            case MonsterTacticalModel.ACTION_GROUP_ADVANCE, MonsterTacticalModel.ACTION_INVESTIGATE_NOISE -> {
                if (target != null) mob.setTarget(target);
                mob.getNavigation().moveTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D, 1.0D);
                // Alert nearby zombies that have direct line of sight to this zombie.
                List<Zombie> allies = level.getEntitiesOfClass(Zombie.class, localBox,
                    z -> z != mob && z.isAlive() && z.getTarget() == null && hasClearLineOfSight(level, mob, z));
                for (int i = 0; i < Math.min(2, allies.size()); i++) {
                    Zombie ally = allies.get(i);
                    recordLastKnownPosition(ally.getPersistentData(), destination);
                    if (target != null) ally.setTarget(target);
                }
            }
            case MonsterTacticalModel.ACTION_HOLD_RANGE_OR_STRAFE -> {
                if (target != null) {
                    Vec3 away = mob.position().subtract(target.position());
                    if (away.lengthSqr() > 0.01D && away.lengthSqr() < 49.0D) {
                        Vec3 norm = away.normalize().scale(4.0D);
                        BlockPos stepBack = BlockPos.containing(mob.getX() + norm.x, mob.getY(), mob.getZ() + norm.z);
                        if (level.hasChunkAt(stepBack)) {
                            mob.getNavigation().moveTo(stepBack.getX() + 0.5D, stepBack.getY(), stepBack.getZ() + 0.5D, 1.05D);
                        }
                    }
                }
            }
            case MonsterTacticalModel.ACTION_SEEK_COVER_OR_SPREAD -> {
                int[] spread = MonsterTacticalModel.computeFlankOffset(mob.getUUID().hashCode(), 3);
                BlockPos spreadPos = mob.blockPosition().offset(spread[0], 0, spread[1]);
                if (level.hasChunkAt(spreadPos)) {
                    mob.getNavigation().moveTo(spreadPos.getX() + 0.5D, spreadPos.getY(), spreadPos.getZ() + 0.5D, 1.1D);
                }
            }
            case MonsterTacticalModel.ACTION_CAUTIOUS_APPROACH -> {
                if (target != null) mob.setTarget(target);
                mob.getNavigation().moveTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D, 1.0D);
            }
            case MonsterTacticalModel.ACTION_HOLD_FOR_ALLY_CLEARANCE -> {
                mob.getNavigation().stop();
                if (target != null && mob.distanceToSqr(target) < 16.0D) {
                    Vec3 stepOut = mob.position().subtract(target.position());
                    if (stepOut.lengthSqr() > 0.01D) {
                        Vec3 norm = stepOut.normalize().scale(3.0D);
                        BlockPos holdPos = BlockPos.containing(mob.getX() + norm.x, mob.getY(), mob.getZ() + norm.z);
                        if (level.hasChunkAt(holdPos)) {
                            mob.getNavigation().moveTo(holdPos.getX() + 0.5D, holdPos.getY(), holdPos.getZ() + 0.5D, 0.95D);
                        }
                    }
                }
            }
            case MonsterTacticalModel.ACTION_VERTICAL_FLANK -> {
                if (target != null) mob.setTarget(target);
                int[] flank = MonsterTacticalModel.computeFlankOffset(mob.getUUID().hashCode(), 2);
                BlockPos flankPos = destination.offset(flank[0], 0, flank[1]);
                BlockPos moveTarget = level.hasChunkAt(flankPos) ? flankPos : destination;
                mob.getNavigation().moveTo(moveTarget.getX() + 0.5D, moveTarget.getY(), moveTarget.getZ() + 0.5D, 1.1D);
            }
            case MonsterTacticalModel.ACTION_AMBUSH_POUNCE -> {
                if (target != null) mob.setTarget(target);
                mob.getNavigation().moveTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D, 1.25D);
            }
            case MonsterTacticalModel.ACTION_SUPPORT_ALLIES, MonsterTacticalModel.ACTION_BUFF_VANGUARD -> {
                boolean cooldownReady = (now - data.getLong(LAST_SUPPORT_KEY)) >= SUPPORT_COOLDOWN_TICKS;
                Mob bestAlly = selectPriorityAllyForWitchSupport(mob, level, localBox);
                if (bestAlly != null && cooldownReady) {
                    double ratio = bestAlly.getMaxHealth() > 0.0F
                        ? (bestAlly.getHealth() / bestAlly.getMaxHealth())
                        : 1.0D;
                    int mode = MonsterTacticalModel.selectWitchSupportMode(1, ratio, true);
                    if (mode == MonsterTacticalModel.WITCH_SUPPORT_CRITICAL_HEAL) {
                        bestAlly.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
                        bestAlly.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 1));
                        bestAlly.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 0));
                        data.putLong(LAST_SUPPORT_KEY, now);
                    } else if (mode == MonsterTacticalModel.WITCH_SUPPORT_MODERATE_MEND) {
                        bestAlly.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 80, 0));
                        bestAlly.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 80, 0));
                        data.putLong(LAST_SUPPORT_KEY, now);
                    } else if (mode == MonsterTacticalModel.WITCH_SUPPORT_VANGUARD_BUFF) {
                        bestAlly.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 0));
                        bestAlly.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 100, 0));
                        bestAlly.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 80, 0));
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
