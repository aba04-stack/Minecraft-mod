package com.abs.livingvillages.domain;

/**
 * Deterministic, platform-independent demand model for a village's physical economy.
 * Stock is measured from real storage and villager inventories by the Minecraft adapter.
 *
 * <p>Two precision rules keep the model honest:</p>
 * <ul>
 *   <li>Quantities already committed to an active work order are reported as reserved and are
 *       subtracted before a shortage is declared, so one house project cannot make the village
 *       believe its whole wood stock is free.</li>
 *   <li>Fuel, iron and saplings are tracked as their own categories. The coarse ore bucket alone
 *       hides a missing coal/iron deficit behind redstone or lapis stock.</li>
 * </ul>
 */
public final class VillageResourceDemandModel {
    public record Stock(
        int adultPopulation,
        int woodUnits,
        int stoneUnits,
        int oreUnits,
        int foodPoints,
        int matureCrops,
        int plantableFarmland,
        int availableSeeds,
        boolean treesAvailable,
        int availablePickaxes,
        boolean desertBiome,
        int fuelUnits,
        int ironUnits,
        int saplings,
        boolean furnaceAvailable,
        int reservedWoodUnits,
        int reservedStoneUnits,
        int reservedOreUnits,
        int reservedFoodPoints
    ) {
        /** Backwards-compatible constructor for non-desert/domain-only callers. */
        public Stock(int adultPopulation, int woodUnits, int stoneUnits, int oreUnits,
                     int foodPoints, int matureCrops, int plantableFarmland, int availableSeeds,
                     boolean treesAvailable, int availablePickaxes) {
            this(adultPopulation, woodUnits, stoneUnits, oreUnits, foodPoints, matureCrops,
                plantableFarmland, availableSeeds, treesAvailable, availablePickaxes, false);
        }

        /** Backwards-compatible constructor for callers that measure no fuel/iron/reservations. */
        public Stock(int adultPopulation, int woodUnits, int stoneUnits, int oreUnits,
                     int foodPoints, int matureCrops, int plantableFarmland, int availableSeeds,
                     boolean treesAvailable, int availablePickaxes, boolean desertBiome) {
            this(adultPopulation, woodUnits, stoneUnits, oreUnits, foodPoints, matureCrops,
                plantableFarmland, availableSeeds, treesAvailable, availablePickaxes, desertBiome,
                0, 0, 0, false, 0, 0, 0, 0);
        }
    }

    public record Plan(
        int targetWoodUnits,
        int targetStoneUnits,
        int targetOreUnits,
        int targetFoodPoints,
        boolean needsWood,
        boolean needsStone,
        boolean needsOre,
        boolean needsFood,
        int matureCrops,
        int plantableFarmland,
        int availableSeeds,
        boolean treesAvailable,
        int desiredLumberjacks,
        int desiredMiners,
        int desiredFarmers,
        int freeWoodUnits,
        int freeStoneUnits,
        int freeOreUnits,
        int freeFoodPoints,
        int targetFuelUnits,
        int targetIronUnits,
        int targetSaplings,
        boolean needsFuel,
        boolean needsIron,
        boolean needsSaplings
    ) {
        public boolean needsMining() {
            return needsStone || needsOre;
        }

        /** True when a real ore deficit (metal or furnace fuel) asks for a miner, not only bulk stone. */
        public boolean needsOreWork() {
            return needsOre || needsFuel || needsIron;
        }

        /** Total unmet categories, used to keep diagnostics and elder summaries short and ordered. */
        public int shortageCount() {
            int count = 0;
            if (needsWood) count++;
            if (needsStone) count++;
            if (needsOre) count++;
            if (needsFood) count++;
            if (needsFuel) count++;
            if (needsIron) count++;
            if (needsSaplings) count++;
            return count;
        }
    }

    private VillageResourceDemandModel() {}

    public static Plan evaluate(Stock stock) {
        if (stock == null) {
            return new Plan(96, 48, 4, 32, true, true, true, true, 0, 0, 0, false, 0, 0, 0,
                0, 0, 0, 0, 4, 2, 4, true, true, true);
        }

        int adults = Math.max(0, stock.adultPopulation());
        int wood = Math.max(0, stock.woodUnits());
        int stone = Math.max(0, stock.stoneUnits());
        int ore = Math.max(0, stock.oreUnits());
        int food = Math.max(0, stock.foodPoints());
        int crops = Math.max(0, stock.matureCrops());
        int plantableFarmland = Math.max(0, stock.plantableFarmland());
        int availableSeeds = Math.max(0, stock.availableSeeds());
        boolean treesAvailable = stock.treesAvailable();
        int pickaxes = Math.max(0, stock.availablePickaxes());
        int fuel = Math.max(0, stock.fuelUnits());
        int iron = Math.max(0, stock.ironUnits());
        int saplings = Math.max(0, stock.saplings());
        boolean furnaceAvailable = stock.furnaceAvailable();

        // Materials already committed to an active order are counted once, as reserved.
        int freeWood = freeOf(wood, stock.reservedWoodUnits());
        int freeStone = freeOf(stone, stock.reservedStoneUnits());
        int freeOre = freeOf(ore, stock.reservedOreUnits());
        int freeFood = freeOf(food, stock.reservedFoodPoints());

        // Wood units use plank-equivalents: one real log counts as four planks.
        // Targets scale with population, but are capped to prevent runaway stockpiling.
        int targetWood = clamp(48 + adults * 8, 96, 256);
        // Treeless settlements must be able to quarry the complete 96-block sandstone bill
        // for their first house before the miner is released.
        int targetStone = stock.desertBiome() || !treesAvailable
            ? clamp(Math.max(96, 24 + adults * 4), 96, 160)
            : clamp(24 + adults * 4, 48, 160);
        int targetOre = clamp(adults * 2, 4, 32);
        int targetFood = clamp(adults * 8, 32, 256);
        // One coal/charcoal unit smelts eight real inputs, so a small stock is enough.
        int targetFuel = clamp(adults, 4, 32);
        // Iron covers pickaxes, swords, shears and buckets for a growing settlement.
        int targetIron = clamp(adults, 2, 24);
        // Saplings keep a harvested forest regenerating instead of dying out with the last tree.
        int targetSaplings = clamp(2 + adults / 2, 4, 16);

        // Keep demand visible after the last mature tree is felled. The source flag controls
        // whether a lumberjack can be assigned now, not whether the village still needs wood.
        boolean needsWood = adults > 1 && freeWood < targetWood;
        boolean needsStone = adults > 1 && freeStone < targetStone;
        boolean needsOre = adults > 1 && freeOre < targetOre;
        boolean needsFood = adults > 1 && freeFood < targetFood;
        // Fuel only matters while something real can be smelted or a furnace already exists.
        boolean needsFuel = adults > 1 && fuel < targetFuel
            && (furnaceAvailable || iron > 0 || freeOre > 0);
        boolean needsIron = adults > 1 && iron < targetIron;
        boolean needsSaplings = adults > 1 && treesAvailable && saplings < targetSaplings;

        int populationWorkSlots = Math.max(1, Math.min(3, (adults + 5) / 6));
        int desiredLumberjacks = needsWood && treesAvailable ? populationWorkSlots : 0;
        int desiredMiners = needsStone || needsOre
            ? Math.min(pickaxes, Math.max(1, Math.min(3, (adults + 4) / 6))) : 0;
        int desiredFarmers = needsFood && (crops > 0 || (plantableFarmland > 0 && availableSeeds > 0))
            ? Math.max(1, Math.min(2, (adults + 7) / 8)) : 0;

        return new Plan(
            targetWood, targetStone, targetOre, targetFood,
            needsWood, needsStone, needsOre, needsFood,
            crops, plantableFarmland, availableSeeds, treesAvailable,
            desiredLumberjacks, desiredMiners, desiredFarmers,
            freeWood, freeStone, freeOre, freeFood,
            targetFuel, targetIron, targetSaplings,
            needsFuel, needsIron, needsSaplings
        );
    }

    private static int freeOf(int measured, int reserved) {
        return Math.max(0, measured - Math.max(0, reserved));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
