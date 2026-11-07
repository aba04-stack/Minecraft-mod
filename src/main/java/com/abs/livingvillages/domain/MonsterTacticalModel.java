package com.abs.livingvillages.domain;

/**
 * Platform-independent tactical state machine and perception rules for hostile mobs
 * and village guard threat prioritization.
 *
 * <p>This class imports zero Minecraft or NeoForge classes. It enforces honest
 * perception (no wallhack/omniscience), bounded pursuit timeouts, maximum path
 * retry counts, last-known-position search decay, patrol routes, and per-mob
 * tactical decisions without inflating HP or damage.</p>
 */
public final class MonsterTacticalModel {
    public static final int STATE_IDLE = 0;
    public static final int STATE_ALERTED = 1;
    public static final int STATE_ENGAGING = 2;
    public static final int STATE_DISENGAGE = 3;
    public static final int STATE_SEARCHING_LAST_KNOWN = 4;
    public static final int STATE_PATROLLING = 5;

    public static final int KIND_ZOMBIE = 1;
    public static final int KIND_SKELETON = 2;
    public static final int KIND_CREEPER = 3;
    public static final int KIND_SPIDER = 4;
    public static final int KIND_WITCH = 5;
    public static final int KIND_RAIDER = 6;

    public static final int ACTION_NONE = 0;
    public static final int ACTION_GROUP_ADVANCE = 1;
    public static final int ACTION_HOLD_RANGE_OR_STRAFE = 2;
    public static final int ACTION_CAUTIOUS_APPROACH = 3;
    public static final int ACTION_VERTICAL_FLANK = 4;
    public static final int ACTION_SUPPORT_ALLIES = 5;
    public static final int ACTION_SCOUT_OR_RETREAT = 6;
    public static final int ACTION_INVESTIGATE_NOISE = 7;
    public static final int ACTION_PATROL_STEP = 8;
    public static final int ACTION_SEEK_COVER_OR_SPREAD = 9;
    public static final int ACTION_AMBUSH_POUNCE = 10;
    public static final int ACTION_HOLD_FOR_ALLY_CLEARANCE = 11;
    public static final int ACTION_BUFF_VANGUARD = 12;
    public static final int ACTION_RELOAD_STRAFE = 13;

    public static final int WITCH_SUPPORT_NONE = 0;
    public static final int WITCH_SUPPORT_CRITICAL_HEAL = 1;
    public static final int WITCH_SUPPORT_MODERATE_MEND = 2;
    public static final int WITCH_SUPPORT_VANGUARD_BUFF = 3;

    public static final int MAX_PATH_RETRIES = 3;
    public static final int DEFAULT_SEARCH_STEPS = 3;
    public static final long DEFAULT_STATE_TIMEOUT_TICKS = 200L;
    public static final long DISENGAGE_COOLDOWN_TICKS = 120L;

    private static final int[][] PATROL_OFFSETS = {
        {5, 0}, {0, 5}, {-5, 0}, {0, -5}
    };

    private static final int[][] FLANK_DIRECTIONS = {
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private MonsterTacticalModel() {}

    /**
     * Honest perception rule: a monster cannot detect a target through solid walls
     * unless it either has direct line of sight within sight range or receives a
     * valid world activity/sound signal within sound range.
     */
    public static boolean canPerceiveTarget(
            boolean hasLineOfSight,
            double distanceSqr,
            double maxSightRangeSqr,
            boolean hasRecentSoundSignal,
            double maxSoundRangeSqr) {
        if (distanceSqr < 0.0D) return false;
        if (hasLineOfSight && distanceSqr <= maxSightRangeSqr) {
            return true;
        }
        return hasRecentSoundSignal && distanceSqr <= maxSoundRangeSqr;
    }

    /**
     * Evaluate the next tactical state and action for a monster tick.
     */
    public static TacticalDecision evaluateTacticalStep(
            int mobKind,
            int currentState,
            long stateElapsedTicks,
            long timeoutTicks,
            int failedPathAttempts,
            boolean targetPerceived,
            boolean hasLineOfSight,
            boolean pathSucceeded,
            double distanceSqr,
            double healthRatio,
            int nearbyAllies,
            int nearbyDefenders,
            boolean mobGriefingEnabled) {
        long safeTimeout = timeoutTicks > 0L ? timeoutTicks : DEFAULT_STATE_TIMEOUT_TICKS;

        if (currentState == STATE_DISENGAGE) {
            if (stateElapsedTicks >= DISENGAGE_COOLDOWN_TICKS) {
                return new TacticalDecision(STATE_IDLE, ACTION_NONE, 0, true);
            }
            return new TacticalDecision(STATE_DISENGAGE, ACTION_NONE, failedPathAttempts, false);
        }

        if (!targetPerceived) {
            if (currentState != STATE_IDLE && stateElapsedTicks >= safeTimeout) {
                return new TacticalDecision(STATE_DISENGAGE, ACTION_NONE, 0, true);
            }
            return new TacticalDecision(
                currentState == STATE_ENGAGING ? STATE_ALERTED : STATE_IDLE,
                ACTION_NONE,
                failedPathAttempts,
                false
            );
        }

        int updatedFailures = pathSucceeded ? 0 : (failedPathAttempts + 1);
        if (updatedFailures >= MAX_PATH_RETRIES || stateElapsedTicks >= safeTimeout) {
            return new TacticalDecision(STATE_DISENGAGE, ACTION_NONE, updatedFailures, true);
        }

        int nextState = hasLineOfSight ? STATE_ENGAGING : STATE_ALERTED;
        int action = selectTacticalAction(
            mobKind,
            hasLineOfSight,
            distanceSqr,
            healthRatio,
            nearbyAllies,
            nearbyDefenders,
            mobGriefingEnabled
        );
        return new TacticalDecision(nextState, action, updatedFailures, false);
    }

    private static int selectTacticalAction(
            int mobKind,
            boolean hasLineOfSight,
            double distanceSqr,
            double healthRatio,
            int nearbyAllies,
            int nearbyDefenders,
            boolean mobGriefingEnabled) {
        return switch (mobKind) {
            case KIND_ZOMBIE -> hasLineOfSight ? ACTION_GROUP_ADVANCE : ACTION_INVESTIGATE_NOISE;
            case KIND_SKELETON -> (distanceSqr < 49.0D || !hasLineOfSight)
                ? ACTION_HOLD_RANGE_OR_STRAFE
                : ACTION_GROUP_ADVANCE;
            case KIND_CREEPER -> {
                if (!mobGriefingEnabled && !hasLineOfSight) {
                    yield ACTION_NONE;
                }
                yield ACTION_CAUTIOUS_APPROACH;
            }
            case KIND_SPIDER -> ACTION_VERTICAL_FLANK;
            case KIND_WITCH -> nearbyAllies > 0 ? ACTION_SUPPORT_ALLIES : ACTION_HOLD_RANGE_OR_STRAFE;
            case KIND_RAIDER -> (healthRatio <= 0.35D || nearbyDefenders > nearbyAllies + 1)
                ? ACTION_SCOUT_OR_RETREAT
                : ACTION_GROUP_ADVANCE;
            default -> ACTION_NONE;
        };
    }

    /**
     * Evaluates gradual search termination when a mob loses direct sight of its target:
     * steps down {@code searchStepsRemaining} toward the last known position, then transitions
     * to patrol (for Pillagers/Raiders) or idle (for Zombies and other mobs).
     */
    public static SearchDecayDecision evaluateSearchDecay(
            boolean hasLastKnownPosition,
            int searchStepsRemaining,
            boolean hasPatrolOrigin) {
        if (hasLastKnownPosition && searchStepsRemaining > 0) {
            return new SearchDecayDecision(
                STATE_SEARCHING_LAST_KNOWN,
                searchStepsRemaining - 1,
                true,
                false
            );
        }
        if (hasPatrolOrigin) {
            return new SearchDecayDecision(STATE_PATROLLING, 0, false, true);
        }
        return new SearchDecayDecision(STATE_IDLE, 0, false, false);
    }

    /**
     * Computes a deterministic small patrol waypoint offset {@code [dx, dz]} around a Pillager's
     * patrol origin.
     */
    public static int[] computePatrolWaypointOffset(int patrolStepIndex, int patrolRadius) {
        int clampedRadius = Math.max(3, Math.min(12, patrolRadius));
        int idx = Math.floorMod(patrolStepIndex, PATROL_OFFSETS.length);
        int baseX = PATROL_OFFSETS[idx][0];
        int baseZ = PATROL_OFFSETS[idx][1];
        int signX = Integer.compare(baseX, 0);
        int signZ = Integer.compare(baseZ, 0);
        return new int[]{signX * clampedRadius, signZ * clampedRadius};
    }

    /**
     * Selects a Pillager's combat action: strafing/repositioning while reloading a crossbow,
     * holding optimal firing range, or advancing with a raider squad.
     */
    public static int selectPillagerCombatAction(
            double distanceSqr,
            boolean reloadingCrossbow,
            boolean hasLineOfSight,
            int nearbyAllies) {
        if (reloadingCrossbow) {
            return ACTION_RELOAD_STRAFE;
        }
        if (distanceSqr < 36.0D) {
            return ACTION_HOLD_RANGE_OR_STRAFE;
        }
        if (!hasLineOfSight || nearbyAllies > 0) {
            return ACTION_GROUP_ADVANCE;
        }
        return ACTION_SCOUT_OR_RETREAT;
    }

    /**
     * Skeleton combat positioning: spread out if clustered with other skeletons, seek cover if
     * recently hit by ranged fire, back up if target closes in, or advance to firing range.
     */
    public static int selectSkeletonCombatAction(
            double distanceSqr,
            boolean hasLineOfSight,
            int clusteredSkeletonsNearby,
            boolean underRecentFire) {
        if (underRecentFire || clusteredSkeletonsNearby > 0) {
            return ACTION_SEEK_COVER_OR_SPREAD;
        }
        if (distanceSqr < 49.0D || !hasLineOfSight) {
            return ACTION_HOLD_RANGE_OR_STRAFE;
        }
        return ACTION_GROUP_ADVANCE;
    }

    /**
     * Spider tactical selection: expressive ambush pounce when a target enters medium range while
     * the spider is waiting in ambush/elevation, otherwise multi-angle vertical flanking.
     */
    public static int selectSpiderTacticalAction(
            double distanceSqr,
            boolean hasLineOfSight,
            boolean onElevatedOrWallSurface,
            boolean ambushReady) {
        if (ambushReady && hasLineOfSight && distanceSqr >= 9.0D && distanceSqr <= 64.0D) {
            return ACTION_AMBUSH_POUNCE;
        }
        if (onElevatedOrWallSurface && distanceSqr <= 49.0D) {
            return ACTION_AMBUSH_POUNCE;
        }
        return ACTION_VERTICAL_FLANK;
    }

    /**
     * Computes a multi-directional flanking offset {@code [dx, dz]} from the target so spiders
     * surround enemies from distinct angles rather than queuing on one line.
     */
    public static int[] computeFlankOffset(int entityHash, int flankDistance) {
        int dist = Math.max(2, Math.min(6, flankDistance));
        int idx = Math.floorMod(entityHash, FLANK_DIRECTIONS.length);
        return new int[]{FLANK_DIRECTIONS[idx][0] * dist, FLANK_DIRECTIONS[idx][1] * dist};
    }

    /**
     * Creeper attack timing and ally-awareness: aborts unreachable approaches, holds/circles when
     * multiple allied mobs are in melee range of the target to avoid friendly blast damage, and
     * advances cautiously when a clean window opens.
     */
    public static int selectCreeperTacticalAction(
            double distanceSqr,
            boolean hasLineOfSight,
            boolean pathSucceeded,
            int alliesAdjacentToTarget,
            boolean mobGriefingEnabled) {
        if (!pathSucceeded) {
            return ACTION_NONE;
        }
        if (!mobGriefingEnabled && !hasLineOfSight) {
            return ACTION_NONE;
        }
        if (alliesAdjacentToTarget >= 2 && distanceSqr <= 36.0D) {
            return ACTION_HOLD_FOR_ALLY_CLEARANCE;
        }
        return ACTION_CAUTIOUS_APPROACH;
    }

    /**
     * Witch ally triage and support selection: prioritizes critical healing when an ally is below
     * 50% health, moderate mending below 80% health, or offensive vanguard buffs when allies are
     * healthy and engaging.
     */
    public static int selectWitchSupportMode(
            int nearbyAllies,
            double lowestAllyHealthRatio,
            boolean cooldownReady) {
        if (!cooldownReady || nearbyAllies <= 0) {
            return WITCH_SUPPORT_NONE;
        }
        if (lowestAllyHealthRatio < 0.50D) {
            return WITCH_SUPPORT_CRITICAL_HEAL;
        }
        if (lowestAllyHealthRatio < 0.80D) {
            return WITCH_SUPPORT_MODERATE_MEND;
        }
        return WITCH_SUPPORT_VANGUARD_BUFF;
    }

    /**
     * Computes a threat priority score for village guards (higher score = higher priority).
     * Strongly prioritizes enemies actively attacking or standing close to civilian villagers.
     */
    public static double scoreGuardThreatPriority(
            double distanceToGuardSqr,
            double distanceToNearestCivilianSqr,
            boolean activelyTargetingVillager,
            boolean recentlyDamagedVillager) {
        double score = 1000.0D - Math.min(900.0D, Math.max(0.0D, distanceToGuardSqr));
        if (activelyTargetingVillager) {
            score += 500.0D;
        }
        if (recentlyDamagedVillager) {
            score += 750.0D;
        }
        if (distanceToNearestCivilianSqr <= 36.0D) {
            score += (36.0D - Math.max(0.0D, distanceToNearestCivilianSqr)) * 12.0D;
        }
        return score;
    }

    /**
     * Determines whether a guard should assist a wounded villager after combat has ended.
     */
    public static boolean shouldGuardAssistWoundedVillager(
            boolean activeThreatPresent,
            double villagerHealthRatio) {
        return !activeThreatPresent && villagerHealthRatio > 0.0D && villagerHealthRatio < 0.85D;
    }

    public record TacticalDecision(
            int nextState,
            int tacticalAction,
            int failedPathAttempts,
            boolean resetTarget) {
    }

    public record SearchDecayDecision(
            int nextState,
            int remainingSearchSteps,
            boolean shouldMoveToLastKnown,
            boolean returnToPatrol) {
    }
}
