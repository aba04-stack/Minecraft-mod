package com.abs.livingvillages.domain;

/**
 * Platform-independent tactical state machine and perception rules for hostile mobs.
 *
 * <p>This class imports zero Minecraft or NeoForge classes. It enforces honest
 * perception (no wallhack/omniscience), bounded pursuit timeouts, maximum path
 * retry counts, and per-mob tactical decisions without inflating HP or damage.</p>
 */
public final class MonsterTacticalModel {
    public static final int STATE_IDLE = 0;
    public static final int STATE_ALERTED = 1;
    public static final int STATE_ENGAGING = 2;
    public static final int STATE_DISENGAGE = 3;

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

    public static final int MAX_PATH_RETRIES = 3;
    public static final long DEFAULT_STATE_TIMEOUT_TICKS = 200L;
    public static final long DISENGAGE_COOLDOWN_TICKS = 120L;

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
            case KIND_ZOMBIE -> ACTION_GROUP_ADVANCE;
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

    public record TacticalDecision(
            int nextState,
            int tacticalAction,
            int failedPathAttempts,
            boolean resetTarget) {
    }
}
