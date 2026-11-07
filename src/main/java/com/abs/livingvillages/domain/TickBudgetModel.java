package com.abs.livingvillages.domain;

/**
 * Platform-independent tick scheduling and per-tick quota rules.
 *
 * <p>Used to stagger entity AI evaluations across ticks and enforce hard
 * upper bounds on expensive checks per server tick.</p>
 */
public final class TickBudgetModel {
    public static final int DEFAULT_MAX_AI_CHECKS_PER_TICK = 24;

    private TickBudgetModel() {}

    public static boolean isScheduledThisTick(long gameTime, int stableEntityHash, int intervalTicks) {
        int safeInterval = Math.max(1, intervalTicks);
        long phase = Math.floorMod(stableEntityHash, safeInterval);
        return Math.floorMod(gameTime, safeInterval) == phase;
    }

    public static boolean canConsumeQuota(int usedThisTick, int maxPerTick) {
        int safeMax = Math.max(1, maxPerTick);
        return usedThisTick < safeMax;
    }

    public record TickTelemetrySnapshot(
        long observedTicks,
        long totalAiChecks,
        long totalExecutionNanos,
        long maxTickNanos
    ) {}

    public static double averageMicrosPerTick(TickTelemetrySnapshot snapshot) {
        if (snapshot == null || snapshot.observedTicks() <= 0L) return 0.0D;
        return (snapshot.totalExecutionNanos() / 1000.0D) / (double) snapshot.observedTicks();
    }
}
