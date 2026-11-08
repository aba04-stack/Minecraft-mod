package com.abs.livingvillages;

/**
 * Deterministic accounting for world scans.
 *
 * <p>{@code docs/PERFORMANCE_BUDGETS.md} forbids publishing invented MSPT numbers, and a CI GameTest
 * cannot assert on milliseconds without becoming flaky. What can be asserted exactly is how much work
 * a scan performed: how many block positions it read, how many resident-group membership tests it ran,
 * and how many entity area queries it issued. Scanners report their own counts here and a test wraps
 * the call in {@link #measure(Runnable)} — for example "the workshop next door must not cost a full
 * 65x65x17 village box" or "counting pickaxes and swords must not re-scan the same asset".</p>
 *
 * <p>Recording is a comparison plus an addition per scan call, never per block, so production overhead
 * stays in the noise. All calls happen on the server thread (scans are driven from
 * {@code LivingVillages.onServerTick}, from industry/planner passes and from GameTests), so plain
 * statics are enough; a nested measurement is not attributed twice.</p>
 */
public final class VillageScanStats {

    /** Work one measured call performed. All values are counts, not timings. */
    public record ScanReport(long blockPositions, long membershipTests, long entityAreaScans) {
        /** Total counted operations, handy for a single "did no work at all" assertion. */
        public long total() {
            return blockPositions + membershipTests + entityAreaScans;
        }
    }

    private static final ScanReport EMPTY = new ScanReport(0L, 0L, 0L);

    private static int depth;
    private static long positions;
    private static long membershipTests;
    private static long entityAreaScans;

    private VillageScanStats() {
    }

    /**
     * Adds the number of block positions one scan actually read.
     *
     * @param scannedPositions positions read by the scanner; ignored when no measurement is active
     */
    public static void recordScan(int scannedPositions) {
        if (depth > 0 && scannedPositions > 0) {
            positions += scannedPositions;
        }
    }

    /**
     * Records one resident-group membership test. Each test costs two entity area queries in
     * {@code VillageArea.isBlockInResidentGroup}, so repeated tests for the same asset are a bug.
     */
    public static void recordMembershipTest() {
        if (depth > 0) {
            membershipTests++;
        }
    }

    /** Records one entity area query over a village-sized box. */
    public static void recordEntityAreaScan() {
        if (depth > 0) {
            entityAreaScans++;
        }
    }

    /**
     * Runs {@code action} and reports how much scanning it performed.
     *
     * <p>A measurement nested inside another one only runs the action: the outer measurement owns the
     * counters, so a budget stays attributable to a single call.</p>
     *
     * @param action the code under measurement; {@code null} measures nothing
     * @return the counted work, or an empty report for a nested or missing measurement
     */
    public static ScanReport measure(Runnable action) {
        if (action == null) {
            return EMPTY;
        }
        if (depth > 0) {
            action.run();
            return EMPTY;
        }
        long beforePositions = positions;
        long beforeMembership = membershipTests;
        long beforeEntityScans = entityAreaScans;
        depth++;
        ScanReport report;
        try {
            action.run();
            report = new ScanReport(positions - beforePositions,
                membershipTests - beforeMembership, entityAreaScans - beforeEntityScans);
        } finally {
            // Always leave clean counters behind, including when the measured code throws (a failing
            // GameTest assertion must not poison the measurement that follows it).
            depth--;
            positions = 0L;
            membershipTests = 0L;
            entityAreaScans = 0L;
        }
        return report;
    }
}
