package com.abs.livingvillages.domain;

/**
 * Pure rules for low-priority villager social activities.
 *
 * <p>Daily-life behavior must never compete with survival, vanilla work/sleep,
 * active profession tasks, or village alarms. This model deliberately decides
 * only whether a short social visit is allowed and when the next attempt may run.</p>
 */
public final class DailyLifeActivityModel {
    public static final long SUCCESS_COOLDOWN_TICKS = 2400L;
    public static final long RETRY_COOLDOWN_TICKS = 400L;
    public static final long SOCIAL_DURATION_TICKS = 100L;
    public static final long ROUTE_TIMEOUT_TICKS = 600L;
    public static final int MAX_VISITORS_PER_RESIDENT = 1;
    public static final int MAX_ACTIVE_VISITS_PER_VILLAGE = 1;

    private DailyLifeActivityModel() {}

    public static boolean canStartSocialVisit(
        boolean alive,
        boolean adult,
        boolean vanillaIdle,
        boolean hasHigherPriorityTask,
        boolean hasWalkTarget,
        boolean villageAlarmActive,
        boolean hasAvailablePeer,
        long nowTick,
        long nextAllowedTick
    ) {
        return alive && adult && vanillaIdle
            && !hasHigherPriorityTask
            && !hasWalkTarget
            && !villageAlarmActive
            && hasAvailablePeer
            && nowTick >= nextAllowedTick;
    }

    public static boolean canAcceptSocialVisitor(int activeVisitorCount) {
        return activeVisitorCount < MAX_VISITORS_PER_RESIDENT;
    }

    public static boolean canStartSocialVisitInVillage(int activeVisitCount) {
        return activeVisitCount < MAX_ACTIVE_VISITS_PER_VILLAGE;
    }

    public static boolean shouldCancelSocialVisit(
        boolean visitorAlive,
        boolean targetAlive,
        boolean vanillaStillIdle,
        boolean higherPriorityTaskStarted,
        boolean villageAlarmActive,
        boolean routeTimedOut
    ) {
        return !visitorAlive || !targetAlive || !vanillaStillIdle
            || higherPriorityTaskStarted || villageAlarmActive || routeTimedOut;
    }

    public static long nextAttemptTick(long nowTick, boolean completedSuccessfully) {
        long cooldown = completedSuccessfully ? SUCCESS_COOLDOWN_TICKS : RETRY_COOLDOWN_TICKS;
        return nowTick + cooldown;
    }
}
