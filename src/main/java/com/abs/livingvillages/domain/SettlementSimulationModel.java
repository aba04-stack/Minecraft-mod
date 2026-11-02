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

    public static final int BIOME_FOREST_OR_PLAINS = 0;
    public static final int BIOME_DESERT_OR_BADLANDS = 1;
    public static final int BIOME_SNOWY_OR_TAIGA = 2;
    public static final int BIOME_MOUNTAIN_OR_ROCKY = 3;

    public static final int MATERIAL_NONE = 0;
    public static final int MATERIAL_PLANKS = 1;
    public static final int MATERIAL_SANDSTONE = 2;
    public static final int MATERIAL_STONE_OR_CLAY = 3;

    public static final long MAX_TASK_STUCK_TICKS = 400L;
    public static final long MIN_ROLE_TENURE_TICKS = 600L;

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

    /**
     * Resolves KI-008: while a house or farm is actively building, recipes that consume wood planks
     * are paused so they never drain reserved construction lumber, whereas non-plank stone/iron
     * tool recipes using existing sticks may proceed in parallel.
     */
    public static boolean canCraftInParallelWithConstruction(
        boolean constructionActive,
        boolean recipeConsumesWoodPlanks
    ) {
        if (!constructionActive) return true;
        return !recipeConsumesWoodPlanks;
    }

    public record TradeExchangeResult(
        boolean approved,
        int foodTransferred,
        int planksTransferred,
        int updatedStanding
    ) {}

    /**
     * Evaluates an honest inter-settlement surplus trade exchange between two peaceful villages.
     * Requires non-hostile diplomatic standing ({@code >= -10}), no active raid/defense alarm,
     * and genuine surplus on both sides. Never creates items out of thin air.
     */
    public static TradeExchangeResult evaluateTradeCaravan(
        int currentStanding,
        boolean alarmActive,
        int surplusFoodItems,
        int partnerSurplusPlanks
    ) {
        int clampedStanding = Math.max(-100, Math.min(100, currentStanding));
        if (alarmActive || clampedStanding < -10 || surplusFoodItems < 4 || partnerSurplusPlanks < 8) {
            return new TradeExchangeResult(false, 0, 0, clampedStanding);
        }
        int bundles = Math.min(Math.min(surplusFoodItems / 4, partnerSurplusPlanks / 8), 4);
        int foodMoved = bundles * 4;
        int planksMoved = bundles * 8;
        int nextStanding = Math.min(100, clampedStanding + bundles * 5);
        return new TradeExchangeResult(true, foodMoved, planksMoved, nextStanding);
    }

    /**
     * Selects the structural material category for a 96-block house based on the settlement's
     * biome and actual stored resources, enabling treeless desert and highland progression.
     */
    public static int selectBiomeStructuralCategory(
        int biomeKind,
        int availablePlanks,
        int availableSandstone,
        int availableStoneOrClay,
        int requiredBlocks
    ) {
        int req = Math.max(1, requiredBlocks);
        if (biomeKind == BIOME_DESERT_OR_BADLANDS) {
            if (availableSandstone >= req) return MATERIAL_SANDSTONE;
            if (availableStoneOrClay >= req) return MATERIAL_STONE_OR_CLAY;
            if (availablePlanks >= req) return MATERIAL_PLANKS;
            return MATERIAL_NONE;
        }
        if (biomeKind == BIOME_MOUNTAIN_OR_ROCKY) {
            if (availableStoneOrClay >= req) return MATERIAL_STONE_OR_CLAY;
            if (availablePlanks >= req) return MATERIAL_PLANKS;
            if (availableSandstone >= req) return MATERIAL_SANDSTONE;
            return MATERIAL_NONE;
        }
        if (availablePlanks >= req) return MATERIAL_PLANKS;
        if (availableSandstone >= req) return MATERIAL_SANDSTONE;
        if (availableStoneOrClay >= req) return MATERIAL_STONE_OR_CLAY;
        return MATERIAL_NONE;
    }

    /**
     * Evaluates honest 4:1 compression of stored sand into sandstone (vanilla 2x2 recipe)
     * so desert settlements without trees can produce structural sandstone blocks.
     */
    public static int evaluateSandToSandstoneCompression(int availableSand, int availableOutputSpace) {
        if (availableSand < 4 || availableOutputSpace <= 0) return 0;
        return Math.min(Math.min(availableSand / 4, availableOutputSpace), 16);
    }

    /**
     * Determines whether autonomous villager work AI must yield to vanilla AI
     * (sleeping, player trading, recent damage panic, or active village alarm evacuation).
     */
    public static boolean shouldYieldToVanillaVillagerAi(
        boolean isSleeping,
        boolean isTrading,
        boolean recentlyHurtPanic,
        boolean villageAlarmEvacuating
    ) {
        return isSleeping || isTrading || recentlyHurtPanic || villageAlarmEvacuating;
    }

    /**
     * Detects whether an active worker task has made zero progress for at least {@code maxStuckTicks}.
     */
    public static boolean isTaskStuck(long lastProgressTick, long currentTick, long maxStuckTicks) {
        if (lastProgressTick <= 0L || currentTick <= lastProgressTick) return false;
        return (currentTick - lastProgressTick) >= Math.max(40L, maxStuckTicks);
    }

    /**
     * Prevents unnecessary role thrashing by enforcing a minimum tenure unless the role is disabled
     * or invalid.
     */
    public static boolean canSwitchSpecialistRole(
        long roleAssignedTick,
        long currentTick,
        boolean currentRoleDisabledOrInvalid,
        boolean hasActiveTask
    ) {
        if (hasActiveTask) return false;
        if (currentRoleDisabledOrInvalid) return true;
        if (roleAssignedTick <= 0L || currentTick < roleAssignedTick) return true;
        return (currentTick - roleAssignedTick) >= MIN_ROLE_TENURE_TICKS;
    }
}
