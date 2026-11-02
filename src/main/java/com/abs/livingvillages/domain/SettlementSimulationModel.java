package com.abs.livingvillages.domain;

/**
 * Platform-independent domain rules for settlement project checkpoint recovery,
 * coordinated village alarm/evacuation, honest remote furnace catch-up simulation,
 * and organized raider scouting/retreat.
 *
 * Contains zero dependencies on {@code net.minecraft.*} or {@code net.neoforged.*}.
 */
public final class SettlementSimulationModel {
    public static final long ALARM_DURATION_TICKS = 160L;
    public static final long SMELT_DURATION_TICKS = 200L;
    public static final int FUEL_BURN_SMELTS = 8;
    public static final long MAX_CATCH_UP_TICKS = 24000L;

    public static final int SQUAD_HOLD_SCOUT = 0;
    public static final int SQUAD_ADVANCE = 1;
    public static final int SQUAD_RETREAT = 2;

    private SettlementSimulationModel() {}

    /**
     * Immutable checkpoint of an in-progress settlement construction project stored at the
     * settlement level so another worker can resume the project if the original builder dies.
     */
    public record ProjectCheckpoint(
        String projectKind,
        int siteX,
        int siteY,
        int siteZ,
        String primaryMaterialId,
        String secondaryMaterialId,
        int stepIndex,
        int totalSteps,
        String ownerWorkerId,
        long updatedTick
    ) {}

    /**
     * Immutable result of an honest, material-conserving furnace catch-up calculation.
     */
    public record CatchUpResult(
        int smeltedCount,
        int oreConsumed,
        int fuelConsumed,
        long ticksConsumed
    ) {}

    public static boolean canResumeOrphanedProject(
        ProjectCheckpoint checkpoint,
        boolean originalOwnerAliveInVillage,
        boolean hasAvailableBuilder
    ) {
        if (checkpoint == null) return false;
        if (checkpoint.stepIndex() < 0 || checkpoint.stepIndex() >= checkpoint.totalSteps()) return false;
        if (originalOwnerAliveInVillage) return false;
        return hasAvailableBuilder;
    }

    public static int remainingPlanksForStep(int totalPlanksRequired, int stepIndex) {
        int clampedStep = Math.max(0, Math.min(stepIndex, totalPlanksRequired));
        return Math.max(0, totalPlanksRequired - clampedStep);
    }

    public static boolean isAlarmActive(long alarmRaisedTick, long currentTick) {
        if (alarmRaisedTick <= 0L || currentTick < alarmRaisedTick) return false;
        return (currentTick - alarmRaisedTick) <= ALARM_DURATION_TICKS;
    }

    public static boolean shouldEvacuateCivilian(
        boolean alarmActive,
        boolean isGuard,
        boolean isSleeping
    ) {
        return alarmActive && !isGuard && !isSleeping;
    }

    /**
     * Evaluates how many smelting operations could honestly complete during {@code elapsedTicks}
     * using strictly existing raw ore, fuel, and output container space.
     * Never creates items without consuming 1:1 input ore and required fuel.
     */
    public static CatchUpResult evaluateFurnaceCatchUp(
        long elapsedTicks,
        int rawOreCount,
        int coalFuelCount,
        int availableOutputSpace
    ) {
        if (elapsedTicks < SMELT_DURATION_TICKS
            || rawOreCount <= 0
            || coalFuelCount <= 0
            || availableOutputSpace <= 0) {
            return new CatchUpResult(0, 0, 0, 0L);
        }
        long cappedTicks = Math.min(elapsedTicks, MAX_CATCH_UP_TICKS);
        int maxByTime = (int) (cappedTicks / SMELT_DURATION_TICKS);
        int maxByFuel = coalFuelCount * FUEL_BURN_SMELTS;
        int smelted = Math.min(Math.min(rawOreCount, availableOutputSpace), Math.min(maxByTime, maxByFuel));
        if (smelted <= 0) {
            return new CatchUpResult(0, 0, 0, 0L);
        }
        int fuelUsed = (smelted + FUEL_BURN_SMELTS - 1) / FUEL_BURN_SMELTS;
        long ticksUsed = (long) smelted * SMELT_DURATION_TICKS;
        return new CatchUpResult(smelted, smelted, fuelUsed, ticksUsed);
    }

    /**
     * Evaluates organized raider squad state: raiders hold until a scout has perceived the
     * settlement through honest line-of-sight or activity signals, and retreat if losses reach 50%.
     */
    public static int evaluateRaiderSquad(
        boolean villageScoutedByPerception,
        int initialSquadSize,
        int currentLivingRaiders
    ) {
        if (currentLivingRaiders <= 0) return SQUAD_RETREAT;
        if (initialSquadSize >= 2 && currentLivingRaiders * 2 <= initialSquadSize) {
            return SQUAD_RETREAT;
        }
        if (!villageScoutedByPerception) {
            return SQUAD_HOLD_SCOUT;
        }
        return SQUAD_ADVANCE;
    }
}
