package com.abs.livingvillages;

import com.abs.livingvillages.domain.VillageResourceDemandModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
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
 *
 * <p>The scan reports reserved quantities separately from measured stock so the domain model can
 * decide shortages from the material the village is actually free to spend. Every refresh also
 * records its own wall-clock cost into {@link VillageTickBudget} so planner load stays measurable
 * instead of assumed.</p>
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
                0, 0, 0, 0, 0, 0, 0, false, 0));
        }

        long now = level.getGameTime();
        Map<UUID, CachedPlan> byVillage = CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        CachedPlan cached = byVillage.get(villageId);
        if (cached != null && now < cached.expiresAtTick()) return cached.plan();

        long startedNanos = System.nanoTime();
        List<Villager> villagers = residents == null ? VillageArea.findVillagers(level, center) : residents;
        VillageResourceDemandModel.Stock stock = measure(level, center, villagers, now);
        VillageResourceDemandModel.Plan plan = VillageResourceDemandModel.evaluate(stock);
        VillageTickBudget.recordSubsystemNanos(level, System.nanoTime() - startedNanos);
        byVillage.put(villageId, new CachedPlan(plan, now + CACHE_TICKS));

        if (byVillage.size() > 64) {
            byVillage.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtTick());
        }
        VillageRegistry.recordDiagnostic(level, center, "Ресурсы", describe(stock, plan));
        return plan;
    }

    /**
     * Drops the cached plan after a real transfer so quotas react on the next assignment pass
     * instead of up to five seconds later. Cheap: only the affected village is re-measured.
     */
    public static void invalidate(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return;
        Map<UUID, CachedPlan> byVillage = CACHE.get(level);
        if (byVillage == null || byVillage.isEmpty()) return;
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId != null) byVillage.remove(villageId);
    }

    private static VillageResourceDemandModel.Stock measure(
            ServerLevel level, BlockPos center, List<Villager> villagers, long now) {
        Totals totals = new Totals();
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
                        accumulate(totals, container.getItem(slot));
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
                    accumulate(totals, inventory.getItem(slot));
                }
                accumulate(totals, villager.getMainHandItem());
            }
        }

        CropScan crops = countCropFields(level, center);
        boolean treesAvailable = hasHarvestableTree(level, center);
        int pickaxes = VillageIndustry.countAvailablePickaxes(level, center);
        boolean furnaceAvailable = VillageIndustry.hasFurnace(level, center);
        Totals reserved = measureReserved(level, center, now);
        return new VillageResourceDemandModel.Stock(
            adults, totals.wood, totals.stone, totals.ore, totals.food, crops.matureCrops(),
            crops.plantableFarmland(), totals.seeds, treesAvailable, pickaxes,
            level.getBiome(center).is(BiomeTags.HAS_VILLAGE_DESERT),
            totals.fuel, totals.iron, totals.saplings, furnaceAvailable,
            reserved.wood, reserved.stone, reserved.ore, reserved.food);
    }

    /**
     * Sums the item quantities already committed to READY/CLAIMED/IN_PROGRESS/DELIVERING orders.
     * These are accounting numbers only; the physical items stay where the reservation system put them.
     */
    private static Totals measureReserved(ServerLevel level, BlockPos center, long now) {
        Totals reserved = new Totals();
        Map<String, Integer> reservations = VillageWorkOrders.reservedItemCounts(level, center, null, now);
        for (Map.Entry<String, Integer> entry : reservations.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) continue;
            accumulate(reserved, item, Math.max(0, entry.getValue()));
        }
        return reserved;
    }

    /** One mutable pass over a stack so storage, inventories and reservations share one classifier. */
    private static void accumulate(Totals totals, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        accumulate(totals, stack.getItem(), stack.getCount());
    }

    private static void accumulate(Totals totals, Item item, int rawCount) {
        if (item == null || item == Items.AIR || rawCount <= 0) return;
        int count = rawCount;
        Block block = Block.byItem(item);
        if (block != Blocks.AIR) {
            BlockState state = block.defaultBlockState();
            if (state.is(BlockTags.LOGS)) totals.wood += count * 4;
            else if (state.is(BlockTags.PLANKS)) totals.wood += count;
            if (state.is(BlockTags.SAPLINGS)) totals.saplings += count;
        }

        if (item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE
            || item == Items.STONE || item == Items.ANDESITE || item == Items.DIORITE
            || item == Items.GRANITE || item == Items.TUFF || item == Items.SANDSTONE
            || item == Items.RED_SANDSTONE || item == Items.TERRACOTTA
            || item == Items.STONE_BRICKS) {
            totals.stone += count;
        }

        if (item == Items.RAW_IRON || item == Items.RAW_COPPER || item == Items.RAW_GOLD
            || item == Items.REDSTONE || item == Items.LAPIS_LAZULI
            || item == Items.DIAMOND || item == Items.EMERALD
            || item == Items.IRON_INGOT || item == Items.COPPER_INGOT || item == Items.GOLD_INGOT) {
            totals.ore += count;
        }

        // Iron is tracked twice on purpose: the coarse bucket keeps the old targets working while
        // the precise bucket lets a miner hunt the specific metal the village is missing.
        if (item == Items.RAW_IRON || item == Items.IRON_INGOT) {
            totals.iron += count;
        }

        if (item == Items.COAL || item == Items.CHARCOAL) {
            totals.fuel += count;
        }

        if (item == Items.BREAD) totals.food += count * 4;
        else if (item == Items.CARROT || item == Items.POTATO || item == Items.BEETROOT) totals.food += count;
        else if (item == Items.WHEAT) totals.food += count;

        if (item == Items.WHEAT_SEEDS || item == Items.BEETROOT_SEEDS
            || item == Items.CARROT || item == Items.POTATO) {
            totals.seeds += count;
        }
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

    /**
     * Counts ripe crops and empty owned farmland in a single pass over the same volume.
     * Both counters used to walk the whole village box separately, doubling the block reads.
     */
    private static CropScan countCropFields(ServerLevel level, BlockPos center) {
        int mature = 0;
        int plantable = 0;
        int radius = Math.min(16, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)
                        && VillageArea.isBlockInResidentGroup(level, center, pos)) {
                        mature++;
                        continue;
                    }
                    if (!state.isAir() || !level.hasChunkAt(pos.below())) continue;
                    BlockPos soil = pos.below();
                    if (level.getBlockState(soil).is(Blocks.FARMLAND)
                        && level.getMaxLocalRawBrightness(pos) >= 8
                        && VillageArea.isBlockInResidentGroup(level, center, soil)) {
                        plantable++;
                    }
                }
            }
        }
        return new CropScan(mature, plantable);
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static String describe(
            VillageResourceDemandModel.Stock stock, VillageResourceDemandModel.Plan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("запасы: дерево=").append(stock.woodUnits())
            .append(" (свободно ").append(plan.freeWoodUnits())
            .append(", в заказах ").append(Math.max(0, stock.woodUnits() - plan.freeWoodUnits())).append(')')
            .append(", камень=").append(stock.stoneUnits())
            .append(" (свободно ").append(plan.freeStoneUnits()).append(')')
            .append(", руда=").append(stock.oreUnits())
            .append(" (свободно ").append(plan.freeOreUnits()).append(')')
            .append(", еда=").append(stock.foodPoints())
            .append(" (свободно ").append(plan.freeFoodPoints()).append(')')
            .append("; топливо=").append(stock.fuelUnits()).append('/').append(plan.targetFuelUnits())
            .append(", железо=").append(stock.ironUnits()).append('/').append(plan.targetIronUnits())
            .append(", саженцы=").append(stock.saplings()).append('/').append(plan.targetSaplings())
            .append("; нехватка: дерево=").append(plan.needsWood())
            .append(", камень=").append(plan.needsStone())
            .append(", руда=").append(plan.needsOre())
            .append(", еда=").append(plan.needsFood())
            .append(", топливо=").append(plan.needsFuel())
            .append(", железо=").append(plan.needsIron())
            .append(", саженцы=").append(plan.needsSaplings())
            .append("; зрелые поля=").append(stock.matureCrops())
            .append(", пустых грядок=").append(stock.plantableFarmland())
            .append(", семян=").append(stock.availableSeeds())
            .append("; деревья рядом=").append(plan.treesAvailable())
            .append("; рабочие: лесорубы=").append(plan.desiredLumberjacks())
            .append(", шахтёры=").append(plan.desiredMiners())
            .append(", фермеры=").append(plan.desiredFarmers());
        return builder.toString();
    }

    /** Mutable accumulator so one classifier serves storage, inventories and reservations. */
    private static final class Totals {
        private int wood;
        private int stone;
        private int ore;
        private int food;
        private int seeds;
        private int fuel;
        private int iron;
        private int saplings;
    }

    private record CropScan(int matureCrops, int plantableFarmland) {}

    private record CachedPlan(VillageResourceDemandModel.Plan plan, long expiresAtTick) {}
}
