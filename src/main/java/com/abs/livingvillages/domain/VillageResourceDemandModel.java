package com.abs.livingvillages.domain;

/**
 * Deterministic, platform-independent demand model for a village's physical economy.
 * Stock is measured from real storage and villager inventories by the Minecraft adapter.
 */
public final class VillageResourceDemandModel {
    public record Stock(
        int adultPopulation,
        int woodUnits,
        int stoneUnits,
        int oreUnits,
        int foodPoints,
        int matureCrops,
        int availablePickaxes
    ) {}

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
        int desiredLumberjacks,
        int desiredMiners,
        int desiredFarmers
    ) {
        public boolean needsMining() {
            return needsStone || needsOre;
        }
    }

    private VillageResourceDemandModel() {}

    public static Plan evaluate(Stock stock) {
        if (stock == null) {
            return new Plan(96, 48, 4, 32, true, true, true, true, 0, 0, 0, 0);
        }

        int adults = Math.max(0, stock.adultPopulation());
        int wood = Math.max(0, stock.woodUnits());
        int stone = Math.max(0, stock.stoneUnits());
        int ore = Math.max(0, stock.oreUnits());
        int food = Math.max(0, stock.foodPoints());
        int crops = Math.max(0, stock.matureCrops());
        int pickaxes = Math.max(0, stock.availablePickaxes());

        // Wood units use plank-equivalents: one real log counts as four planks.
        // Targets scale with population, but are capped to prevent runaway stockpiling.
        int targetWood = clamp(48 + adults * 8, 96, 256);
        int targetStone = clamp(24 + adults * 4, 48, 160);
        int targetOre = clamp(adults * 2, 4, 32);
        int targetFood = clamp(adults * 8, 32, 256);

        boolean needsWood = adults > 1 && wood < targetWood;
        boolean needsStone = adults > 1 && stone < targetStone;
        boolean needsOre = adults > 1 && ore < targetOre;
        boolean needsFood = adults > 1 && food < targetFood;

        int populationWorkSlots = Math.max(1, Math.min(3, (adults + 5) / 6));
        int desiredLumberjacks = needsWood ? populationWorkSlots : 0;
        int desiredMiners = needsStone || needsOre
            ? Math.min(pickaxes, Math.max(1, Math.min(3, (adults + 4) / 6))) : 0;
        int desiredFarmers = needsFood && crops > 0
            ? Math.max(1, Math.min(2, (adults + 7) / 8)) : 0;

        return new Plan(
            targetWood, targetStone, targetOre, targetFood,
            needsWood, needsStone, needsOre, needsFood,
            crops, desiredLumberjacks, desiredMiners, desiredFarmers
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
