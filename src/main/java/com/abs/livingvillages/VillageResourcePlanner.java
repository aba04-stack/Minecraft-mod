package com.abs.livingvillages;

import com.abs.livingvillages.domain.VillageResourceDemandModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server adapter that measures real village stock and caches a demand plan.
 * It never creates items and only scans already-loaded chunks.
 */
public final class VillageResourcePlanner {
    private static final long CACHE_TICKS = 100L;
    private static final Map<ServerLevel, Map<UUID, CachedPlan>> CACHE = new WeakHashMap<>();

    private VillageResourcePlanner() {}

    public static VillageResourceDemandModel.Plan getPlan(ServerLevel level, BlockPos center) {
        if (level == null || center == null) {
            return VillageResourceDemandModel.evaluate(null);
        }
        return getPlan(level, center, null);
    }

    public static VillageResourceDemandModel.Plan getPlan(
            ServerLevel level, BlockPos center, List<Villager> residents) {
        if (level == null || center == null) {
            return VillageResourceDemandModel.evaluate(null);
        }
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId == null) {
            return VillageResourceDemandModel.evaluate(new VillageResourceDemandModel.Stock(
                residents == null ? 0 : (int) residents.stream().filter(v -> !v.isBaby()).count(),
                0, 0, 0, 0, 0, false, 0));
        }

        long now = level.getGameTime();
        Map<UUID, CachedPlan> byVillage = CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        CachedPlan cached = byVillage.get(villageId);
        if (cached != null && now < cached.expiresAtTick()) return cached.plan();

        List<Villager> villagers = residents == null ? VillageArea.findVillagers(level, center) : residents;
        VillageResourceDemandModel.Stock stock = measure(level, center, villagers);
        VillageResourceDemandModel.Plan plan = VillageResourceDemandModel.evaluate(stock);
        byVillage.put(villageId, new CachedPlan(plan, now + CACHE_TICKS));

        if (byVillage.size() > 64) {
            byVillage.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtTick());
        }
        VillageRegistry.recordDiagnostic(level, center, "Ресурсы", describe(stock, plan));
        return plan;
    }

    private static VillageResourceDemandModel.Stock measure(
            ServerLevel level, BlockPos center, List<Villager> villagers) {
        int woodUnits = 0;
        int stoneUnits = 0;
        int oreUnits = 0;
        int foodPoints = 0;
        int adults = 0;

        int storageRadius = Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        for (int dx = -storageRadius; dx <= storageRadius; dx++) {
            for (int dz = -storageRadius; dz <= storageRadius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                        || !VillageRegistry.isStorageForVillage(level, center, pos)
                        || !(level.getBlockEntity(pos) instanceof Container container)) continue;
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        int[] totals = addStack(container.getItem(slot), woodUnits, stoneUnits, oreUnits, foodPoints);
                        woodUnits = totals[0];
                        stoneUnits = totals[1];
                        oreUnits = totals[2];
                        foodPoints = totals[3];
                    }
                }
            }
        }

        if (villagers != null) {
            for (Villager villager : villagers) {
                if (villager == null || !villager.isAlive()) continue;
                if (!villager.isBaby()) adults++;
                Container inventory = villager.getInventory();
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    int[] totals = addStack(inventory.getItem(slot), woodUnits, stoneUnits, oreUnits, foodPoints);
                    woodUnits = totals[0];
                    stoneUnits = totals[1];
                    oreUnits = totals[2];
                    foodPoints = totals[3];
                }
                int[] heldTotals = addStack(villager.getMainHandItem(), woodUnits, stoneUnits, oreUnits, foodPoints);
                woodUnits = heldTotals[0];
                stoneUnits = heldTotals[1];
                oreUnits = heldTotals[2];
                foodPoints = heldTotals[3];
            }
        }

        int matureCrops = countMatureCrops(level, center);
        boolean treesAvailable = hasHarvestableTree(level, center);
        int pickaxes = VillageIndustry.countAvailablePickaxes(level, center);
        return new VillageResourceDemandModel.Stock(
            adults, woodUnits, stoneUnits, oreUnits, foodPoints, matureCrops, treesAvailable, pickaxes);
    }

    private static int[] addStack(ItemStack stack, int wood, int stone, int ore, int food) {
        if (stack == null || stack.isEmpty()) return new int[] {wood, stone, ore, food};
        int count = stack.getCount();
        Block block = Block.byItem(stack.getItem());
        if (block != Blocks.AIR) {
            BlockState state = block.defaultBlockState();
            if (state.is(BlockTags.LOGS)) wood += count * 4;
            else if (state.is(BlockTags.PLANKS)) wood += count;
        }

        if (stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)
            || stack.is(Items.STONE) || stack.is(Items.ANDESITE) || stack.is(Items.DIORITE)
            || stack.is(Items.GRANITE) || stack.is(Items.TUFF) || stack.is(Items.SANDSTONE)
            || stack.is(Items.RED_SANDSTONE) || stack.is(Items.TERRACOTTA)
            || stack.is(Items.STONE_BRICKS)) {
            stone += count;
        }

        if (stack.is(Items.RAW_IRON) || stack.is(Items.RAW_COPPER) || stack.is(Items.RAW_GOLD)
            || stack.is(Items.REDSTONE) || stack.is(Items.LAPIS_LAZULI)
            || stack.is(Items.DIAMOND) || stack.is(Items.EMERALD)
            || stack.is(Items.IRON_INGOT) || stack.is(Items.COPPER_INGOT) || stack.is(Items.GOLD_INGOT)) {
            ore += count;
        }

        if (stack.is(Items.BREAD)) food += count * 4;
        else if (stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)) food += count;
        else if (stack.is(Items.WHEAT)) food += count;
        return new int[] {wood, stone, ore, food};
    }

    private static boolean hasHarvestableTree(ServerLevel level, BlockPos center) {
        int radius = Math.min(12, LivingVillagesConfig.TREE_SEARCH_RADIUS_BLOCKS.get());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 16; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(BlockTags.LOGS)) continue;
                    int logs = 0;
                    int leaves = 0;
                    for (int ox = -2; ox <= 2 && logs < 3; ox++) {
                        for (int oz = -2; oz <= 2 && logs < 3; oz++) {
                            for (int oy = -2; oy <= 4 && logs < 3; oy++) {
                                BlockPos check = pos.offset(ox, oy, oz);
                                if (level.hasChunkAt(check) && level.getBlockState(check).is(BlockTags.LOGS)) logs++;
                            }
                        }
                    }
                    if (logs < 3) continue;
                    for (int ox = -4; ox <= 4 && leaves < 3; ox++) {
                        for (int oz = -4; oz <= 4 && leaves < 3; oz++) {
                            for (int oy = -2; oy <= 6 && leaves < 3; oy++) {
                                BlockPos check = pos.offset(ox, oy, oz);
                                if (level.hasChunkAt(check) && level.getBlockState(check).is(BlockTags.LEAVES)) leaves++;
                            }
                        }
                    }
                    if (leaves >= 3) return true;
                }
            }
        }
        return false;
    }

    private static int countMatureCrops(ServerLevel level, BlockPos center) {
        int count = 0;
        int radius = Math.min(16, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)
                        && VillageArea.isBlockInResidentGroup(level, center, pos)) count++;
                }
            }
        }
        return count;
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static String describe(
            VillageResourceDemandModel.Stock stock, VillageResourceDemandModel.Plan plan) {
        return "запасы: дерево=" + stock.woodUnits()
            + ", камень=" + stock.stoneUnits()
            + ", руда/металлы=" + stock.oreUnits()
            + ", еда=" + stock.foodPoints()
            + "; нехватка: дерево=" + plan.needsWood()
            + ", камень=" + plan.needsStone()
            + ", руда=" + plan.needsOre()
            + ", еда=" + plan.needsFood()
            + "; деревья рядом=" + plan.treesAvailable()
            + "; рабочие: лесорубы=" + plan.desiredLumberjacks()
            + ", шахтёры=" + plan.desiredMiners()
            + ", фермеры=" + plan.desiredFarmers();
    }

    private record CachedPlan(VillageResourceDemandModel.Plan plan, long expiresAtTick) {}
}
