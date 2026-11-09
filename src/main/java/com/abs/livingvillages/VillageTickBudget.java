package com.abs.livingvillages;

import com.abs.livingvillages.domain.TickBudgetModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Server-thread tick quota tracker and bounded world-activity signal buffer.
 *
 * <p>Enforces upper bounds on expensive AI perception/path checks per tick and
 * records short-lived sound/activity signals (e.g., chopping, mining, combat)
 * with a fixed maximum capacity and TTL.</p>
 */
public final class VillageTickBudget {
    public static final int MAX_SIGNALS_PER_LEVEL = 32;
    public static final long SIGNAL_TTL_TICKS = 160L;

    private static final Map<ServerLevel, LevelTickState> STATES = new WeakHashMap<>();

    private VillageTickBudget() {}

    public static boolean tryConsumeAiCheck(ServerLevel level) {
        if (level == null) return false;
        long now = level.getGameTime();
        LevelTickState state = stateFor(level, now);
        int maxPerTick = LivingVillagesConfig.MAX_AI_CHECKS_PER_TICK.get();
        if (!TickBudgetModel.canConsumeQuota(state.checksUsedThisTick, maxPerTick)) {
            return false;
        }
        state.checksUsedThisTick++;
        state.totalAiChecks++;
        return true;
    }

    /**
     * Consumes one settlement scan slot for this tick.
     *
     * <p>Every settlement's plan cache expires on the same interval, so without a slot limit all of them
     * would re-measure the world in one tick. Callers that do not get a slot keep serving their previous
     * (bounded-age) result and ask again next tick; the slot is never borrowed from the AI check quota,
     * which protects a different kind of work.</p>
     *
     * @param maxPerTick caller-defined limit; the shared quota rule raises anything below one to one
     * @return {@code true} when the caller may run its full re-measure now
     */
    public static boolean tryConsumeSettlementScan(ServerLevel level, int maxPerTick) {
        if (level == null) return false;
        LevelTickState state = stateFor(level, level.getGameTime());
        if (!TickBudgetModel.canConsumeQuota(state.settlementScansUsedThisTick, maxPerTick)) {
            return false;
        }
        state.settlementScansUsedThisTick++;
        state.totalSettlementScans++;
        return true;
    }

    /** Total full settlement re-measures performed since the level was loaded. */
    public static long totalSettlementScans(ServerLevel level) {
        if (level == null) return 0L;
        return stateFor(level, level.getGameTime()).totalSettlementScans;
    }

    public static void recordSubsystemNanos(ServerLevel level, long elapsedNanos) {
        if (level == null || elapsedNanos <= 0L) return;
        long now = level.getGameTime();
        LevelTickState state = stateFor(level, now);
        state.nanosThisTick += elapsedNanos;
        state.totalExecutionNanos += elapsedNanos;
        if (state.nanosThisTick > state.maxTickNanos) {
            state.maxTickNanos = state.nanosThisTick;
        }
    }

    public static TickBudgetModel.TickTelemetrySnapshot snapshotTelemetry(ServerLevel level) {
        if (level == null) {
            return new TickBudgetModel.TickTelemetrySnapshot(0L, 0L, 0L, 0L);
        }
        LevelTickState state = stateFor(level, level.getGameTime());
        return new TickBudgetModel.TickTelemetrySnapshot(
            Math.max(1L, state.observedTicks),
            state.totalAiChecks,
            state.totalExecutionNanos,
            state.maxTickNanos
        );
    }

    public static void recordActivitySignal(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return;
        long now = level.getGameTime();
        LevelTickState state = stateFor(level, now);
        pruneExpiredSignals(state, now);
        while (state.signals.size() >= MAX_SIGNALS_PER_LEVEL) {
            state.signals.pollFirst();
        }
        state.signals.addLast(new ActivitySignal(pos.immutable(), now));
    }

    public static BlockPos findRecentSignalNear(ServerLevel level, BlockPos origin, double maxRangeSqr) {
        if (level == null || origin == null || maxRangeSqr <= 0.0D) return null;
        long now = level.getGameTime();
        LevelTickState state = stateFor(level, now);
        pruneExpiredSignals(state, now);
        BlockPos best = null;
        double bestDist = maxRangeSqr;
        for (ActivitySignal signal : state.signals) {
            double dist = signal.pos().distSqr(origin);
            if (dist <= bestDist) {
                bestDist = dist;
                best = signal.pos();
            }
        }
        return best;
    }

    private static LevelTickState stateFor(ServerLevel level, long now) {
        LevelTickState state = STATES.computeIfAbsent(level, ignored -> new LevelTickState());
        if (state.gameTime != now) {
            state.gameTime = now;
            state.checksUsedThisTick = 0;
            state.settlementScansUsedThisTick = 0;
            state.nanosThisTick = 0L;
            state.observedTicks++;
        }
        return state;
    }

    private static void pruneExpiredSignals(LevelTickState state, long now) {
        Iterator<ActivitySignal> it = state.signals.iterator();
        while (it.hasNext()) {
            ActivitySignal signal = it.next();
            if (now - signal.createdTick() > SIGNAL_TTL_TICKS) {
                it.remove();
            }
        }
    }

    private static final class LevelTickState {
        private long gameTime = -1L;
        private int checksUsedThisTick = 0;
        private int settlementScansUsedThisTick = 0;
        private long totalSettlementScans = 0L;
        private long observedTicks = 0L;
        private long totalAiChecks = 0L;
        private long nanosThisTick = 0L;
        private long totalExecutionNanos = 0L;
        private long maxTickNanos = 0L;
        private final Deque<ActivitySignal> signals = new ArrayDeque<>(MAX_SIGNALS_PER_LEVEL);
    }

    private record ActivitySignal(BlockPos pos, long createdTick) {
    }
}
