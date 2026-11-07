package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.GameRules;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Material-backed village industry for Minecraft 1.21.1.
 *
 * Every operation has a physical prerequisite and consumes real inputs. Logs become planks,
 * planks/sticks become tools only at a real crafting table with a craftsman nearby, cobblestone
 * is consumed to place a furnace, and vanilla furnace logic performs smelting using real fuel.
 * If inputs, output capacity, a station, or an available worker are missing, the job waits.
 */
public final class VillageIndustry {
    private static final String P = "livingvillages_industry_";
    private static final String ROUTE_ACTIVE = P + "route_active";
    private static final String ROUTE_X = P + "route_x", ROUTE_Y = P + "route_y", ROUTE_Z = P + "route_z";
    private static final String LAST_ROUTE = P + "last_route";
    private static final int BUILD_SCAN_RADIUS = 16;
    private static final int LOCAL_STORAGE_RADIUS = 16;
    private static final float WALK_SPEED = 0.90F;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final List<Item> RAW_METALS = List.of(Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD);
    private static final List<Item> FUELS = List.of(Items.COAL, Items.CHARCOAL, Items.OAK_PLANKS, Items.SPRUCE_PLANKS,
        Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
        Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS, Items.CRIMSON_PLANKS,
        Items.WARPED_PLANKS, Items.STICK, Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
        Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG);

    private VillageIndustry() {}

    /** Called on an interval by the active-area scheduler; only loaded, player-near areas are touched. */
    public static void tickArea(ServerLevel level, BlockPos center) {
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_VILLAGE_INDUSTRY.get()) {
            VillageRegistry.recordDiagnostic(level, center, "Промышленность", "Отключена система промышленности или авторазвития");
            return;
        }

        int radius = LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get();
        List<Villager> villagers = VillageArea.findVillagers(level, center);
        if (villagers.isEmpty()) return;

        List<StorageRef> storages = findStorages(level, center, radius);
        if (tryGrantDesertStarterKit(level, center, villagers, storages)) {
            storages = findStorages(level, center, radius);
        }
        if (storages.isEmpty()) {
            // If physical chests exist but are reserved by active projects, do not mistake them
            // for a brand-new settlement and attempt duplicate storage bootstrap from inventory.
            if (hasStorageBlock(level, center, radius)) {
                VillageRegistry.recordDiagnostic(level, center, "Промышленность", "Все хранилища сейчас зарезервированы активными задачами");
                return;
            }
            // Bootstrap is itself a real recipe: two matching logs -> eight planks -> one chest.
            // All ingredients must be in a villager's own inventory; nothing is created for free.
            bootstrapStorage(level, center, villagers);
            storages = findStorages(level, center, radius);
            if (storages.isEmpty()) {
                VillageRegistry.recordDiagnostic(level, center, "Промышленность", "Нет хранилища: ожидание 2 брёвен (8 досок) у жителей для первого сундука");
                return;
            }
        }

        boolean hasCraftingTable = hasBlock(level, center, Blocks.CRAFTING_TABLE, radius);
        // Build the workshop as soon as four real planks are available. Do not delay this
        // milestone by manufacturing extra planks first.
        if (!hasCraftingTable) {
            if (totalPlanks(storages) < 4) {
                craftPlanksFromStorage(storages, villagers);
            }
            if (buildCraftingTable(level, center, storages, villagers)) {
                hasCraftingTable = true;
                VillageRegistry.recordDiagnostic(level, center, "Промышленность", "Создан верстак поселения");
            } else {
                VillageRegistry.recordDiagnostic(level, center, "Промышленность", "Нет верстака: нужно 4 доски на складе и свободный житель");
                return;
            }
        }

        // Convert logs in storage into planks up to a healthy settlement stock (enough for houses, doors, beds, and tools).
        for (int batch = 0; batch < 4 && totalPlanks(storages) < 160; batch++) {
            if (!craftPlanksFromStorage(storages, villagers)) break;
        }

        // Convert harvested wheat in storage into bread (3 wheat -> 1 bread) for villager nutrition.
        while (totalItem(storages, Items.WHEAT) >= 3 && totalItem(storages, Items.BREAD) < 48) {
            if (!craftRecipe(storages, Items.BREAD, 1, Map.of(Items.WHEAT, 3))) break;
        }

        // In desert/sand-rich settlements without lumber, compress 4 sand -> 1 sandstone (vanilla 2x2 recipe).
        if (totalPlanks(storages) < 96 && totalItem(storages, Items.SANDSTONE) < 96) {
            for (StorageRef ref : storages) {
                if (compressSandToSandstoneIfEligible(ref.container()) > 0) break;
            }
        }

        // Tools are produced by a villager who reaches the actual table, not by a background free-item generator.
        BlockPos tablePos = findNearestBlock(level, center, Blocks.CRAFTING_TABLE, radius);
        List<StorageRef> workshopStorage = tablePos == null ? List.of() : findStorages(level, tablePos, LOCAL_STORAGE_RADIUS);
        if (!LivingVillagesConfig.ENABLE_PHYSICAL_CRAFTING.get()
            && tablePos != null && !workshopStorage.isEmpty() && hasCraftsmanAtTable(villagers, tablePos)) {
            // Backward-compatible simplified mode is optional; realistic mode uses VillageCraftingJobs.
            if (totalItem(workshopStorage, Items.STICK) < 2 && totalPlanks(workshopStorage) >= 2
                && craftSticks(workshopStorage)) return;
            if (craftNeededTools(workshopStorage, villagers, center)) return;
        }

        // A furnace is built only when the village owns cobblestone, an ore to smelt, and real fuel.
        if (!hasBlock(level, center, Blocks.FURNACE, radius)
            && hasRawMetal(storages) && hasAnyFuel(storages) && buildFurnace(level, center, storages, villagers)) {
            VillageRegistry.recordDiagnostic(level, center, "Промышленность", "Построена плавильная печь");
            return;
        }

        // The vanilla furnace performs the actual smelting; this layer only moves real inputs/output.
        processFurnaces(level, center, villagers, radius);
        VillageRegistry.recordDiagnostic(level, center, "Промышленность",
            "Склады: " + storages.size() + ", досок: " + totalPlanks(storages) + ", верстак: есть");
    }

    /** Guides the assigned craftsman to a real workbench using ordinary villager navigation. */
    public static void onVillagerTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()
            || !villager.isAlive() || villager.isBaby() || villager.isNoAi()
            || VillageCraftingJobs.isBusy(villager) || villager.tickCount % 20 != 0) return;
        if (!(villager.level() instanceof ServerLevel level)) return;
        CompoundTag data = villager.getPersistentData();
        if (!VillagerRoles.isCraftsman(villager)) {
            if (data.getBoolean(ROUTE_ACTIVE)) clearOwnedRoute(villager, data);
            return;
        }
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_VILLAGE_INDUSTRY.get()) {
            clearOwnedRoute(villager, data);
            return;
        }
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        if (villager.isSleeping() || dayTime < 1000L || dayTime >= 12000L) {
            clearOwnedRoute(villager, data);
            return;
        }

        int radius = LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get();
        BlockPos table = findNearestBlock(level, villager.blockPosition(), Blocks.CRAFTING_TABLE, radius);
        if (table == null) {
            clearOwnedRoute(villager, data);
            return;
        }

        if (data.getBoolean(ROUTE_ACTIVE)) {
            BlockPos target = ownedTarget(data);
            if (villager.distanceToSqr(Vec3.atCenterOf(target)) <= 2.5D * 2.5D) {
                clearOwnedRoute(villager, data);
                return;
            }
            if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                && level.getGameTime() - data.getLong(LAST_ROUTE) >= 40L) {
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, 1));
                data.putLong(LAST_ROUTE, level.getGameTime());
            }
            return;
        }

        if (villager.distanceToSqr(Vec3.atCenterOf(table)) <= 4.0D * 4.0D
            || villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) return;
        BlockPos stand = findStandNear(level, table);
        if (stand == null) return;
        data.putBoolean(ROUTE_ACTIVE, true);
        data.putInt(ROUTE_X, stand.getX()); data.putInt(ROUTE_Y, stand.getY()); data.putInt(ROUTE_Z, stand.getZ());
        data.putLong(LAST_ROUTE, level.getGameTime());
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
    }

    public static boolean hasCraftingTable(ServerLevel level, BlockPos center) {
        return hasBlock(level, center, Blocks.CRAFTING_TABLE, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get());
    }

    public static int countAvailablePickaxes(ServerLevel level, BlockPos center) {
        return countTools(level, center, VillageIndustry::isPickaxe,
            villager -> isUnassignedAvailableAdult(villager) || VillagerRoles.isMiner(villager));
    }

    public static int countAvailableSwords(ServerLevel level, BlockPos center) {
        return countTools(level, center, VillageIndustry::isSword,
            villager -> isUnassignedAvailableAdult(villager) || VillagerRoles.isGuard(villager));
    }

    /** Do not assign a specialist unless that specific villager can reach a real tool or storage containing one. */
    public static boolean hasPickaxeAccessibleTo(Villager villager) {
        return hasToolAccessibleTo(villager, VillageIndustry::isPickaxe, 12);
    }

    public static boolean hasSwordAccessibleTo(Villager villager) {
        return hasToolAccessibleTo(villager, VillageIndustry::isSword, 10);
    }

    private static boolean hasToolAccessibleTo(Villager villager, Predicate<ItemStack> predicate, int maxRadius) {
        if (predicate.test(villager.getMainHandItem())) return true;
        var inventory = villager.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (predicate.test(inventory.getItem(i))) return true;
        }
        if (!(villager.level() instanceof ServerLevel level)) return false;
        int radius = Math.min(maxRadius, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        for (StorageRef ref : findStorages(level, villager.blockPosition(), radius)) {
            Container container = ref.container();
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (predicate.test(container.getItem(i))) return true;
            }
        }
        return false;
    }

    private static boolean isUnassignedAvailableAdult(Villager villager) {
        if (!VillagerRoles.isEligibleAutonomousAdult(villager)) return false;
        String role = villager.getPersistentData().getString(VillagerRoles.ROLE_KEY);
        if (!role.isEmpty() && !(VillagerRoles.ROLE_BUILDER.equals(role) && !VillageBuilder.isProjectActive(villager))) {
            return false;
        }
        CompoundTag data = villager.getPersistentData();
        return !data.getBoolean("livingvillages_treework_active")
            && !data.getBoolean("livingvillages_treework_replant_active")
            && !data.getBoolean("livingvillages_logistics_active")
            && !data.getBoolean("livingvillages_autonomy_target_active");
    }

    private static int countTools(ServerLevel level, BlockPos center, Predicate<ItemStack> predicate,
                                  Predicate<Villager> workerCanUseTool) {
        int total = 0;
        for (StorageRef ref : findStorages(level, center, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get())) {
            Container container = ref.container();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (predicate.test(stack)) total += stack.getCount();
            }
        }
        for (Villager villager : VillageArea.findVillagers(level, center)) {
            // A tool in an unrelated worker's pockets is not a village-wide tool supply.
            if (!workerCanUseTool.test(villager)) continue;
            if (predicate.test(villager.getMainHandItem())) total += villager.getMainHandItem().getCount();
            for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
                ItemStack stack = villager.getInventory().getItem(i);
                if (predicate.test(stack)) total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean isPickaxe(ItemStack stack) {
        return stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.STONE_PICKAXE) || stack.is(Items.IRON_PICKAXE)
            || stack.is(Items.GOLDEN_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE);
    }

    private static boolean isSword(ItemStack stack) {
        return stack.is(Items.WOODEN_SWORD) || stack.is(Items.STONE_SWORD) || stack.is(Items.IRON_SWORD)
            || stack.is(Items.GOLDEN_SWORD) || stack.is(Items.DIAMOND_SWORD) || stack.is(Items.NETHERITE_SWORD);
    }

    private static boolean isAxe(ItemStack stack) {
        return stack.is(Items.WOODEN_AXE) || stack.is(Items.STONE_AXE) || stack.is(Items.IRON_AXE)
            || stack.is(Items.GOLDEN_AXE) || stack.is(Items.DIAMOND_AXE) || stack.is(Items.NETHERITE_AXE);
    }

    private static void bootstrapStorage(ServerLevel level, BlockPos center, List<Villager> villagers) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return;
        for (Villager villager : villagers) {
            if (!isIdle(villager) || hasHigherPriorityTask(villager)) continue;
            var inventory = villager.getInventory();
            // Carry out up to two vanilla 1 log -> 4 planks recipes in the villager's inventory so 2 logs can form the first chest.
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    ItemStack logs = inventory.getItem(i);
                    Item planks = plankItemForLog(logs);
                    if (planks == null || !inventoryCanFit(inventory, new ItemStack(planks, 4))) continue;
                    logs.shrink(1);
                    if (logs.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
                    inventory.setChanged();
                    insertIntoInventory(inventory, new ItemStack(planks, 4));
                    break;
                }
            }
            // If eight actual planks are carried, spend them on the first chest.
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack planks = inventory.getItem(i);
                if (planks.isEmpty() || !Block.byItem(planks.getItem()).defaultBlockState().is(BlockTags.PLANKS)
                    || planks.getCount() < 8) continue;
                BlockPos site = findBuildSiteNear(level, villager.blockPosition(), 6);
                if (site == null) continue;
                BlockState atSite = level.getBlockState(site);
                if (!atSite.isAir() && VillageBuilder.isAirOrReplaceableVegetation(atSite)) {
                    level.destroyBlock(site, true);
                }
                if (level.setBlock(site, Blocks.CHEST.defaultBlockState(), 3)) {
                    planks.shrink(8);
                    if (planks.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
                    inventory.setChanged();
                    VillageRegistry.isStorageForVillage(level, center, site);
                    return;
                }
            }
        }
    }

    private static boolean buildCraftingTable(ServerLevel level, BlockPos center, List<StorageRef> storages,
                                              List<Villager> villagers) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return false;
        for (StorageRef ref : storages) {
            if (!hasWorkerNear(villagers, ref.pos(), 5.0D)) continue;
            Container container = ref.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || stack.getCount() < 4
                    || !Block.byItem(stack.getItem()).defaultBlockState().is(BlockTags.PLANKS)) continue;
                BlockPos site = findBuildSiteNear(level, ref.pos(), 4);
                if (site == null) continue;
                Item plankItem = stack.getItem();
                stack.shrink(4);
                if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
                container.setChanged();
                BlockState atSite = level.getBlockState(site);
                if (!atSite.isAir() && VillageBuilder.isAirOrReplaceableVegetation(atSite)) {
                    level.destroyBlock(site, true);
                }
                if (!level.setBlock(site, Blocks.CRAFTING_TABLE.defaultBlockState(), 3)) {
                    insertIntoContainer(container, new ItemStack(plankItem, 4));
                    return false;
                }
                VillageRegistry.isWorkBlockForVillage(level, center, site);
                return true;
            }
        }
        return false;
    }

    private static boolean craftPlanksFromStorage(List<StorageRef> storages, List<Villager> villagers) {
        for (StorageRef ref : storages) {
            if (!hasWorkerNear(villagers, ref.pos(), 5.0D)) continue;
            Container container = ref.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack logs = container.getItem(slot);
                Item plankItem = plankItemForLog(logs);
                if (plankItem == null || logs.isEmpty()) continue;
                ItemStack output = new ItemStack(plankItem, 4);
                if (!containerHasSpace(container, output)) continue;
                logs.shrink(1);
                if (logs.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
                container.setChanged();
                insertIntoContainer(container, output);
                return true;
            }
        }
        return false;
    }

    private static boolean craftSticks(List<StorageRef> storages) {
        for (StorageRef ref : storages) {
            Container container = ref.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack planks = container.getItem(slot);
                if (planks.isEmpty() || planks.getCount() < 2
                    || !Block.byItem(planks.getItem()).defaultBlockState().is(BlockTags.PLANKS)) continue;
                ItemStack output = new ItemStack(Items.STICK, 4);
                if (!containerHasSpace(container, output)) continue;
                planks.shrink(2);
                if (planks.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
                container.setChanged();
                insertIntoContainer(container, output);
                return true;
            }
        }
        return false;
    }

    private static boolean craftNeededTools(List<StorageRef> storages, List<Villager> villagers, BlockPos center) {
        int pickaxes = countToolsNearStorageAndVillagers(storages, villagers, VillageIndustry::isPickaxe);
        int stoneOrBetterPickaxes = countToolsNearStorageAndVillagers(storages, villagers,
            stack -> stack.is(Items.STONE_PICKAXE) || stack.is(Items.IRON_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE)
                || stack.is(Items.NETHERITE_PICKAXE));
        int ironOrBetterPickaxes = countToolsNearStorageAndVillagers(storages, villagers,
            stack -> stack.is(Items.IRON_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE));
        int cobble = totalItem(storages, Items.COBBLESTONE);
        int sticks = totalItem(storages, Items.STICK);
        Item plankItem = choosePlanks(storages);
        int planks = plankItem == null ? 0 : totalItem(storages, plankItem);

        // Establish the mining chain before making optional efficiency tools: a pickaxe is the
        // first productive tool because it unlocks real cobblestone and ore inputs.
        if (ironOrBetterPickaxes == 0 && totalItem(storages, Items.IRON_INGOT) >= 3 && sticks >= 2) {
            return craftRecipe(storages, Items.IRON_PICKAXE, 1, Map.of(Items.IRON_INGOT, 3, Items.STICK, 2));
        }
        if (stoneOrBetterPickaxes == 0 && cobble >= 3 && sticks >= 2) {
            return craftRecipe(storages, Items.STONE_PICKAXE, 1, Map.of(Items.COBBLESTONE, 3, Items.STICK, 2));
        }
        if (pickaxes == 0 && plankItem != null && planks >= 3 && sticks >= 2) {
            return craftRecipe(storages, Items.WOODEN_PICKAXE, 1, Map.of(plankItem, 3, Items.STICK, 2));
        }

        // Only make an axe after the settlement has at least one actual pickaxe.
        int axes = countToolsNearStorageAndVillagers(storages, villagers, VillageIndustry::isAxe);
        if (axes == 0 && pickaxes > 0) {
            if (cobble >= 3 && sticks >= 2) {
                return craftRecipe(storages, Items.STONE_AXE, 1, Map.of(Items.COBBLESTONE, 3, Items.STICK, 2));
            }
            if (plankItem != null && planks >= 3 && sticks >= 2) {
                return craftRecipe(storages, Items.WOODEN_AXE, 1, Map.of(plankItem, 3, Items.STICK, 2));
            }
        }

        int population = villagers.size();
        if (population >= 5 && countToolsNearStorageAndVillagers(storages, villagers, VillageIndustry::isSword) == 0) {
            if (totalItem(storages, Items.IRON_INGOT) >= 2 && sticks >= 1) {
                return craftRecipe(storages, Items.IRON_SWORD, 1, Map.of(Items.IRON_INGOT, 2, Items.STICK, 1));
            }
            if (cobble >= 2 && sticks >= 1) {
                return craftRecipe(storages, Items.STONE_SWORD, 1, Map.of(Items.COBBLESTONE, 2, Items.STICK, 1));
            }
            if (plankItem != null && planks >= 2 && sticks >= 1) {
                return craftRecipe(storages, Items.WOODEN_SWORD, 1, Map.of(plankItem, 2, Items.STICK, 1));
            }
        }
        return false;
    }

    private static int countToolsNearStorageAndVillagers(List<StorageRef> storages, List<Villager> villagers,
                                                         Predicate<ItemStack> predicate) {
        int total = 0;
        for (StorageRef ref : storages) {
            for (int i = 0; i < ref.container().getContainerSize(); i++) {
                ItemStack stack = ref.container().getItem(i);
                if (predicate.test(stack)) total += stack.getCount();
            }
        }
        for (Villager villager : villagers) {
            if (!toolInventoryIsRelevant(villager, predicate)) continue;
            if (predicate.test(villager.getMainHandItem())) total += villager.getMainHandItem().getCount();
            for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
                ItemStack stack = villager.getInventory().getItem(i);
                if (predicate.test(stack)) total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean toolInventoryIsRelevant(Villager villager, Predicate<ItemStack> predicate) {
        String role = villager.getPersistentData().getString(VillagerRoles.ROLE_KEY);
        boolean pickaxe = predicate.test(new ItemStack(Items.WOODEN_PICKAXE))
            || predicate.test(new ItemStack(Items.STONE_PICKAXE))
            || predicate.test(new ItemStack(Items.IRON_PICKAXE));
        boolean sword = predicate.test(new ItemStack(Items.WOODEN_SWORD))
            || predicate.test(new ItemStack(Items.STONE_SWORD))
            || predicate.test(new ItemStack(Items.IRON_SWORD));
        if (pickaxe) return role.isEmpty() ? isUnassignedAvailableAdult(villager) : VillagerRoles.isMiner(villager);
        if (sword) return role.isEmpty() ? isUnassignedAvailableAdult(villager) : VillagerRoles.isGuard(villager);
        // For axes, only a free adult or an actual lumberjack task may provide available supply.
        return role.isEmpty() && (isUnassignedAvailableAdult(villager)
            || villager.getPersistentData().getBoolean("livingvillages_treework_active"));
    }

    private static boolean craftRecipe(List<StorageRef> storages, Item outputItem, int outputCount, Map<Item, Integer> ingredients) {
        List<Container> containers = containersOf(storages);
        for (Map.Entry<Item, Integer> entry : ingredients.entrySet()) {
            if (totalItem(storages, entry.getKey()) < entry.getValue()) return false;
        }
        ItemStack output = new ItemStack(outputItem, outputCount);
        if (!canInsert(containers, output)) return false;
        for (Map.Entry<Item, Integer> entry : ingredients.entrySet()) {
            if (!takeItem(storages, entry.getKey(), entry.getValue())) return false;
        }
        return insertIntoContainers(containers, output);
    }

    private static boolean buildFurnace(ServerLevel level, BlockPos center, List<StorageRef> storages,
                                        List<Villager> villagers) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return false;
        for (StorageRef ref : storages) {
            if (!hasWorkerNear(villagers, ref.pos(), 5.0D)) continue;
            Container container = ref.container();
            if (countItem(container, Items.COBBLESTONE) < 8) continue;
            BlockPos site = findBuildSiteNear(level, ref.pos(), 4);
            if (site == null) continue;
            List<StorageRef> local = findStorages(level, site, LOCAL_STORAGE_RADIUS);
            if (!hasRawMetal(local) || !hasAnyFuel(local)) continue;
            if (!takeItem(container, Items.COBBLESTONE, 8)) continue;
            BlockState atSite = level.getBlockState(site);
            if (!atSite.isAir() && VillageBuilder.isAirOrReplaceableVegetation(atSite)) {
                level.destroyBlock(site, true);
            }
            if (level.setBlock(site, Blocks.FURNACE.defaultBlockState(), 3)) {
                VillageRegistry.isWorkBlockForVillage(level, center, site);
                return true;
            }
            insertIntoContainer(container, new ItemStack(Items.COBBLESTONE, 8));
            return false;
        }
        return false;
    }

    private static void processFurnaces(ServerLevel level, BlockPos center, List<Villager> villagers, int radius) {
        for (BlockPos furnacePos : findBlocks(level, center, Blocks.FURNACE, radius)) {
            if (!hasWorkerNear(villagers, furnacePos, 5.0D)) continue;
            if (!(level.getBlockEntity(furnacePos) instanceof Container furnace) || furnace.getContainerSize() < 3) continue;
            List<StorageRef> nearbyStorage = findStorages(level, furnacePos, LOCAL_STORAGE_RADIUS);
            if (nearbyStorage.isEmpty()) continue;

            // Return completed vanilla smelting output to a physically nearby store, if room exists.
            ItemStack result = furnace.getItem(2);
            if (!result.isEmpty()) {
                ItemStack movable = result.copy();
                int before = movable.getCount();
                insertIntoContainers(containersOf(nearbyStorage), movable);
                int amount = before - movable.getCount();
                if (amount > 0) {
                    result.shrink(amount);
                    if (result.isEmpty()) furnace.setItem(2, ItemStack.EMPTY);
                    furnace.setChanged();
                }
            }

            // Only load a new raw metal when real fuel is already in the furnace or storage.
            if (furnace.getItem(0).isEmpty() && !hasRawMetal(nearbyStorage)) continue;
            if (furnace.getItem(1).isEmpty()) {
                Item fuel = findAvailableFuel(nearbyStorage);
                if (fuel == null) continue;
                if (furnace.getItem(0).isEmpty() && !hasRawMetal(nearbyStorage)) continue;
                if (!takeItemFromStorages(nearbyStorage, fuel, 1)) continue;
                furnace.setItem(1, new ItemStack(fuel, 1));
                furnace.setChanged();
            }
            if (furnace.getItem(0).isEmpty()) {
                Item raw = findRawMetal(nearbyStorage);
                if (raw == null) continue;
                int amount = Math.min(16, totalItem(nearbyStorage, raw));
                if (amount <= 0 || !takeItemFromStorages(nearbyStorage, raw, amount)) continue;
                ItemStack input = new ItemStack(raw, amount);
                if (furnace.canPlaceItem(0, input)) {
                    furnace.setItem(0, input);
                    furnace.setChanged();
                } else {
                    insertIntoContainers(containersOf(nearbyStorage), input);
                }
            }
        }
    }

    private static Item findAvailableFuel(List<StorageRef> storages) {
        for (Item fuel : FUELS) if (totalItem(storages, fuel) > 0) return fuel;
        return null;
    }

    private static Item findRawMetal(List<StorageRef> storages) {
        for (Item raw : RAW_METALS) if (totalItem(storages, raw) > 0) return raw;
        return null;
    }

    private static boolean hasRawMetal(List<StorageRef> storages) { return findRawMetal(storages) != null; }
    private static boolean hasAnyFuel(List<StorageRef> storages) { return findAvailableFuel(storages) != null; }

    private static boolean hasCraftsmanAtTable(List<Villager> villagers, BlockPos table) {
        for (Villager villager : villagers) {
            if (VillagerRoles.isCraftsman(villager) && isIdle(villager)
                && villager.distanceToSqr(Vec3.atCenterOf(table)) <= 4.0D * 4.0D) return true;
        }
        return false;
    }

    private static boolean hasWorkerNearStorage(ServerLevel level, BlockPos center, List<StorageRef> storages) {
        List<Villager> villagers = VillageArea.findVillagers(level, center).stream()
            .filter(v -> !v.isBaby()).toList();
        for (StorageRef storage : storages) if (hasWorkerNear(villagers, storage.pos(), 5.0D)) return true;
        return false;
    }

    private static boolean hasWorkerNear(List<Villager> villagers, BlockPos pos, double radius) {
        double effectiveRadius = Math.max(radius, 24.0D);
        double distanceSqr = effectiveRadius * effectiveRadius;
        for (Villager villager : villagers) {
            if (isIdle(villager) && villager.distanceToSqr(Vec3.atCenterOf(pos)) <= distanceSqr) return true;
        }
        return false;
    }

    private static boolean isIdle(Villager villager) {
        if (villager.isBaby() || villager.isSleeping()
            || VillagerRoles.shouldYieldToVanillaVillagerAi(villager)
            || VillageDailyLife.isOnSocialVisit(villager)
            || (villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                && !VillageDailyLife.isVanillaIdle(villager))) return false;
        return !hasHigherPriorityTask(villager);
    }

    private static boolean hasHigherPriorityTask(Villager villager) {
        CompoundTag data = villager.getPersistentData();
        return data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route")
            || data.getBoolean("livingvillages_autonomy_target_active")
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean("livingvillages_logistics_active")
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY);
    }

    private static boolean hasBlock(ServerLevel level, BlockPos center, Block block, int radius) {
        return findNearestBlock(level, center, block, radius) != null;
    }

    private static BlockPos findNearestBlock(ServerLevel level, BlockPos center, Block block, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                    if (!state.is(block)) continue;
                    if (VillageRegistry.isWorkBlockState(state)
                        && !VillageRegistry.isWorkBlockForVillage(level, center, pos)) continue;
                    double distance = pos.distSqr(center);
                    if (distance < bestDistance) { bestDistance = distance; best = pos.immutable(); }
                }
            }
        }
        return best;
    }

    private static List<BlockPos> findBlocks(ServerLevel level, BlockPos center, Block block, int radius) {
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                    if (!state.is(block)) continue;
                    if (VillageRegistry.isWorkBlockState(state)
                        && !VillageRegistry.isWorkBlockForVillage(level, center, pos)) continue;
                    found.add(pos.immutable());
                }
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return found;
    }

    /** One owned chest, trapped chest, or barrel is sufficient as the village's shared starting warehouse. */
    public static boolean hasVillageStorage(ServerLevel level, BlockPos center) {
        return level != null && center != null
            && hasStorageBlock(level, center, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
    }

    /**
     * A treeless desert settlement receives one finite starter cache. This is an explicit,
     * persisted bootstrap exception, not a recurring item generator: after the cache is
     * committed its village UUID is recorded in SavedData and no second cache can be granted.
     */
    static boolean tryGrantDesertStarterKit(
            ServerLevel level, BlockPos center, List<Villager> villagers, List<StorageRef> storages) {
        if (!LivingVillagesConfig.ENABLE_DESERT_STARTER_KIT.get()
            || level == null || center == null || villagers == null
            || !level.getBiome(center).is(BiomeTags.IS_DESERT)
            || villagers.stream().filter(v -> v != null && v.isAlive() && !v.isBaby()).count()
                < LivingVillagesConfig.MINIMUM_VILLAGERS.get()
            || VillageRegistry.hasDesertStarterKit(level, center)) return false;

        VillageResourceDemandModel.Plan demand = VillageResourcePlanner.getPlan(level, center, villagers);
        if (demand.treesAvailable()) return false;

        // If the only owned container is currently reserved, wait instead of creating duplicate depots.
        if ((storages == null || storages.isEmpty())
            && hasStorageBlock(level, center, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get())) {
            VillageRegistry.recordDiagnostic(level, center, "Старт", "Пустынный набор ждёт освобождения общего склада");
            return false;
        }

        List<ItemStack> bundle = createDesertStarterKit();
        StorageRef destination = storages == null ? null : storages.stream()
            .filter(ref -> canFitBundle(ref.container(), bundle))
            .min(Comparator.comparingDouble(ref -> ref.pos().distSqr(center)))
            .orElse(null);

        boolean createdStorage = false;
        BlockPos storagePos;
        Container destinationContainer;
        if (destination != null) {
            storagePos = destination.pos();
            destinationContainer = destination.container();
        } else {
            BlockPos site = findBuildSiteNear(level, center, 6);
            if (site == null) {
                VillageRegistry.recordDiagnostic(level, center, "Старт", "Пустынный набор ждёт безопасного места для первого склада");
                return false;
            }
            BlockState previous = level.getBlockState(site);
            if (!previous.isAir() && VillageBuilder.isAirOrReplaceableVegetation(previous)) {
                level.destroyBlock(site, true);
            }
            if (!level.setBlock(site, Blocks.CHEST.defaultBlockState(), 3)
                || !(level.getBlockEntity(site) instanceof Container placed)) return false;
            storagePos = site.immutable();
            destinationContainer = placed;
            createdStorage = true;
        }

        if (!VillageRegistry.isStorageForVillage(level, center, storagePos)) {
            if (createdStorage) level.removeBlock(storagePos, false);
            VillageRegistry.recordDiagnostic(level, center, "Старт", "Не удалось закрепить пустынный склад за этим поселением");
            return false;
        }

        ItemStack[] before = snapshotContainer(destinationContainer);
        if (!insertBundle(destinationContainer, bundle)
            || !VillageRegistry.recordDesertStarterKitGranted(level, center)) {
            restoreContainer(destinationContainer, before);
            if (createdStorage) level.removeBlock(storagePos, false);
            VillageRegistry.recordDiagnostic(level, center, "Старт", "Не удалось безопасно разместить пустынный стартовый запас");
            return false;
        }
        destinationContainer.setChanged();
        VillageRegistry.recordDiagnostic(level, center, "Старт",
            "Одноразовый пустынный набор готов: песчаник для первого дома, брёвна, кирка, еда и комплект фермы");
        return true;
    }

    /** One real house bill, a basic workbench supply and one real 5x5 farm bill. */
    static List<ItemStack> createDesertStarterKit() {
        return List.of(
            new ItemStack(Items.OAK_LOG, 8),
            new ItemStack(Items.SANDSTONE, 96),
            new ItemStack(Items.STONE_PICKAXE, 1),
            new ItemStack(Items.WOODEN_HOE, 1),
            new ItemStack(Items.WHEAT_SEEDS, 24),
            new ItemStack(Items.WATER_BUCKET, 1),
            new ItemStack(Items.COMPOSTER, 1),
            new ItemStack(Items.BREAD, 4),
            new ItemStack(Items.OAK_DOOR, 1),
            new ItemStack(Items.WHITE_BED, 1)
        );
    }

    private static boolean canFitBundle(Container container, List<ItemStack> bundle) {
        if (container == null || bundle == null) return false;
        ItemStack[] simulated = snapshotContainer(container);
        for (ItemStack required : bundle) {
            if (required == null || required.isEmpty()) continue;
            int remaining = required.getCount();
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int slot = 0; slot < simulated.length && remaining > 0; slot++) {
                    if (!container.canPlaceItem(slot, required)) continue;
                    ItemStack current = simulated[slot];
                    if (pass == 0) {
                        if (current.isEmpty()
                            || !ItemStack.isSameItemSameComponents(current, required)) continue;
                        int capacity = Math.min(current.getMaxStackSize(), container.getMaxStackSize())
                            - current.getCount();
                        int moved = Math.min(Math.max(0, capacity), remaining);
                        if (moved > 0) {
                            current.grow(moved);
                            remaining -= moved;
                        }
                    } else if (current.isEmpty()) {
                        int moved = Math.min(remaining,
                            Math.min(required.getMaxStackSize(), container.getMaxStackSize()));
                        ItemStack placed = required.copy();
                        placed.setCount(moved);
                        simulated[slot] = placed;
                        remaining -= moved;
                    }
                }
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    private static boolean insertBundle(Container container, List<ItemStack> bundle) {
        if (!canFitBundle(container, bundle)) return false;
        for (ItemStack required : bundle) {
            if (required == null || required.isEmpty()) continue;
            if (!insertIntoContainer(container, required.copy())) return false;
        }
        container.setChanged();
        return true;
    }

    private static ItemStack[] snapshotContainer(Container container) {
        ItemStack[] copy = new ItemStack[container.getContainerSize()];
        for (int slot = 0; slot < copy.length; slot++) copy[slot] = container.getItem(slot).copy();
        return copy;
    }

    private static void restoreContainer(Container container, ItemStack[] snapshot) {
        if (container == null || snapshot == null || snapshot.length != container.getContainerSize()) return;
        for (int slot = 0; slot < snapshot.length; slot++) {
            container.setItem(slot, snapshot[slot].copy());
        }
        container.setChanged();
    }

    /** A compact setup hint shown by the elder so a new settlement never fails silently. */
    public static String describeStartupStatus(ServerLevel level, BlockPos center, List<Villager> residents) {
        if (level == null || center == null) return "";
        int adults = residents == null ? 0 : (int) residents.stream()
            .filter(v -> v != null && v.isAlive() && !v.isBaby()).count();
        if (adults < LivingVillagesConfig.MINIMUM_VILLAGERS.get()) {
            return "Старт: нужно взрослых жителей — " + adults + "/"
                + LivingVillagesConfig.MINIMUM_VILLAGERS.get() + ".";
        }
        if (LivingVillagesConfig.ENABLE_DESERT_STARTER_KIT.get()
            && level.getBiome(center).is(BiomeTags.IS_DESERT)
            && !VillageResourcePlanner.getPlan(level, center, residents).treesAvailable()
            && !VillageRegistry.hasDesertStarterKit(level, center)) {
            return "Пустынный старт: готовится одноразовый склад с ресурсами, киркой и комплектом первой фермы.";
        }
        if (VillageRegistry.hasDesertStarterKit(level, center)) {
            return "Стартовый запас пустынного поселения выдан один раз. Дальше жители добывают и производят ресурсы сами.";
        }

        int radius = LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get();
        List<StorageRef> storages = findStorages(level, center, radius);
        if (storages.isEmpty()) {
            if (hasStorageBlock(level, center, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get())) {
                return "Старт: общий склад найден, но сейчас занят другим заданием.";
            }
            var plan = VillageResourcePlanner.getPlan(level, center, residents);
            return plan.treesAvailable()
                ? "Старт: поставь один сундук/бочку или дай жителю 2 брёвна для первого склада."
                : "Старт: нет склада и деревьев. Поставь один сундук/бочку и положи реальные ресурсы.";
        }

        if (!hasBlock(level, center, Blocks.CRAFTING_TABLE, radius)) {
            if (totalPlanks(storages) < 4) {
                return "Старт: склад найден. Для первого верстака нужны 4 доски — положи 1 бревно.";
            }
            return "Старт: склад найден. Житель готовит место для первого верстака.";
        }

        var plan = VillageResourcePlanner.getPlan(level, center, residents);
        if (plan.needsMining() && countAvailablePickaxes(level, center) == 0) {
            return "Старт: склад и верстак готовы. Для шахтёра положи настоящую кирку.";
        }
        if (plan.needsFood() && plan.availableSeeds() == 0 && plan.matureCrops() == 0) {
            return "Старт: для фермы нужны 24 семени одного вида, мотыга, ведро воды и компостер.";
        }
        return "Старт: общий склад и верстак готовы; жители используют только настоящие ресурсы.";
    }

    private static boolean hasStorageBlock(ServerLevel level, BlockPos center, int radius) {
        if (level == null || center == null) return false;
        int scanRadius = Math.min(radius, 24);
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (level.hasChunkAt(pos)
                        && isStorage(level.getBlockState(pos))
                        && VillageRegistry.isStorageForVillage(level, center, pos)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<StorageRef> findStorages(ServerLevel level, BlockPos center, int radius) {
        List<StorageRef> refs = new ArrayList<>();
        int scanRadius = Math.min(radius, 24);
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                        || !VillageRegistry.isStorageForVillage(level, center, pos)
                        || !VillageReservations.isStorageAvailable(level, pos, null)) continue;
                    BlockEntity entity = level.getBlockEntity(pos);
                    if (entity instanceof Container container) refs.add(new StorageRef(pos.immutable(), container));
                }
            }
        }
        refs.sort(Comparator.comparingDouble(ref -> ref.pos().distSqr(center)));
        return refs;
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static BlockPos findBuildSiteNear(ServerLevel level, BlockPos anchor, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = anchor.offset(dx, dy, dz);
                    if (!isBuildable(level, pos)) continue;
                    candidates.add(pos.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(anchor)));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean isBuildable(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(pos.below())) return false;
        if (!VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(pos))
            || !VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(pos.above()))
            || !level.getBlockState(pos.below()).blocksMotion() || !level.getFluidState(pos).isEmpty()) return false;
        return level.getBlockEntity(pos) == null;
    }

    private static BlockPos findStandNear(ServerLevel level, BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos pos = target.relative(direction).offset(0, dy, 0);
                if (!isSafeStand(level, pos)) continue;
                double distance = pos.distSqr(target);
                if (distance < bestDistance) { bestDistance = distance; best = pos.immutable(); }
            }
        }
        return best;
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below())
            && VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(pos))
            && VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(pos.above()))
            && level.getBlockState(pos.below()).blocksMotion();
    }

    private static BlockPos ownedTarget(CompoundTag data) {
        return new BlockPos(data.getInt(ROUTE_X), data.getInt(ROUTE_Y), data.getInt(ROUTE_Z));
    }

    private static void clearOwnedRoute(Villager villager, CompoundTag data) {
        if (data.getBoolean(ROUTE_ACTIVE)) {
            BlockPos target = ownedTarget(data);
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(target)) villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            });
        }
        data.putBoolean(ROUTE_ACTIVE, false);
        data.remove(ROUTE_X); data.remove(ROUTE_Y); data.remove(ROUTE_Z); data.remove(LAST_ROUTE);
    }

    private static final List<Item> PLANK_LOG_ITEMS = List.of(
        Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG,
        Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG,
        Items.CRIMSON_STEM, Items.WARPED_STEM
    );

    /** Returns the real log consumed by the existing 1 log -> 4 matching planks recipe. */
    public static Item logItemForPlanks(Item plankItem) {
        if (plankItem == null || plankItem == Items.AIR) return Items.AIR;
        for (Item logItem : PLANK_LOG_ITEMS) {
            if (plankItemForLog(new ItemStack(logItem)) == plankItem) return logItem;
        }
        return Items.AIR;
    }

    public static Item plankItemForLog(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Block block = Block.byItem(stack.getItem());
        if (!block.defaultBlockState().is(BlockTags.LOGS)) return null;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !id.getNamespace().equals("minecraft")) return null;
        String path = id.getPath();
        if (path.contains("dark_oak")) return Items.DARK_OAK_PLANKS;
        if (path.contains("mangrove")) return Items.MANGROVE_PLANKS;
        if (path.contains("cherry")) return Items.CHERRY_PLANKS;
        if (path.contains("bamboo")) return Items.BAMBOO_PLANKS;
        if (path.contains("crimson")) return Items.CRIMSON_PLANKS;
        if (path.contains("warped")) return Items.WARPED_PLANKS;
        if (path.contains("acacia")) return Items.ACACIA_PLANKS;
        if (path.contains("jungle")) return Items.JUNGLE_PLANKS;
        if (path.contains("birch")) return Items.BIRCH_PLANKS;
        if (path.contains("spruce")) return Items.SPRUCE_PLANKS;
        if (path.contains("oak")) return Items.OAK_PLANKS;
        return null;
    }

    private static Item choosePlanks(List<StorageRef> storages) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (StorageRef ref : storages) {
            Container container = ref.container();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && Block.byItem(stack.getItem()).defaultBlockState().is(BlockTags.PLANKS)) {
                    totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        return totals.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static int totalPlanks(List<StorageRef> storages) {
        int total = 0;
        for (StorageRef ref : storages) {
            Container container = ref.container();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && Block.byItem(stack.getItem()).defaultBlockState().is(BlockTags.PLANKS)) total += stack.getCount();
            }
        }
        return total;
    }

    private static int totalItem(List<StorageRef> storages, Item item) {
        int total = 0;
        for (StorageRef ref : storages) total += countItem(ref.container(), item);
        return total;
    }

    private static int countItem(Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static boolean takeItem(List<StorageRef> storages, Item item, int amount) {
        if (totalItem(storages, item) < amount) return false;
        int left = amount;
        for (StorageRef ref : storages) {
            Container container = ref.container();
            for (int i = 0; i < container.getContainerSize() && left > 0; i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.is(item)) continue;
                int moved = Math.min(left, stack.getCount());
                stack.shrink(moved);
                left -= moved;
                if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                container.setChanged();
            }
            if (left == 0) return true;
        }
        return false;
    }

    private static boolean takeItem(Container container, Item item, int amount) {
        if (countItem(container, item) < amount) return false;
        int left = amount;
        for (int i = 0; i < container.getContainerSize() && left > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.is(item)) continue;
            int moved = Math.min(left, stack.getCount());
            stack.shrink(moved);
            left -= moved;
            if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
        }
        container.setChanged();
        return left == 0;
    }

    private static boolean takeItemFromStorages(List<StorageRef> storages, Item item, int amount) {
        return takeItem(storages, item, amount);
    }

    private static boolean canInsert(List<Container> containers, ItemStack output) {
        int remaining = output.getCount();
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                if (!container.canPlaceItem(i, output)) continue;
                ItemStack existing = container.getItem(i);
                int capacity;
                if (existing.isEmpty()) capacity = Math.min(output.getMaxStackSize(), container.getMaxStackSize());
                else if (ItemStack.isSameItemSameComponents(existing, output)) {
                    capacity = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
                } else continue;
                remaining -= Math.max(0, capacity);
            }
        }
        return remaining <= 0;
    }

    private static boolean containerHasSpace(Container container, ItemStack output) {
        return canInsert(List.of(container), output);
    }

    private static boolean insertIntoContainers(List<Container> containers, ItemStack output) {
        for (int pass = 0; pass < 2 && !output.isEmpty(); pass++) {
            for (Container container : containers) {
                for (int i = 0; i < container.getContainerSize() && !output.isEmpty(); i++) {
                    if (!container.canPlaceItem(i, output)) continue;
                    ItemStack existing = container.getItem(i);
                    if (pass == 0) {
                        if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, output)) continue;
                        int capacity = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
                        int amount = Math.min(Math.max(0, capacity), output.getCount());
                        if (amount > 0) { existing.grow(amount); output.shrink(amount); container.setChanged(); }
                    } else if (existing.isEmpty()) {
                        int amount = Math.min(output.getCount(), Math.min(output.getMaxStackSize(), container.getMaxStackSize()));
                        if (amount > 0) { container.setItem(i, output.split(amount)); container.setChanged(); }
                    }
                }
            }
        }
        return output.isEmpty();
    }

    private static boolean insertIntoContainer(Container container, ItemStack output) {
        return insertIntoContainers(List.of(container), output);
    }

    private static List<Container> containersOf(List<StorageRef> storages) {
        List<Container> containers = new ArrayList<>();
        for (StorageRef ref : storages) containers.add(ref.container());
        return containers;
    }

    private static boolean inventoryCanFit(Container inventory, ItemStack output) {
        int remaining = output.getCount();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()) remaining -= Math.min(output.getMaxStackSize(), inventory.getMaxStackSize());
            else if (ItemStack.isSameItemSameComponents(existing, output)) {
                remaining -= Math.max(0, Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize()) - existing.getCount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private static void insertIntoInventory(Container inventory, ItemStack output) {
        for (int pass = 0; pass < 2 && !output.isEmpty(); pass++) {
            for (int i = 0; i < inventory.getContainerSize() && !output.isEmpty(); i++) {
                ItemStack existing = inventory.getItem(i);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, output)) continue;
                    int capacity = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize()) - existing.getCount();
                    int amount = Math.min(Math.max(0, capacity), output.getCount());
                    if (amount > 0) { existing.grow(amount); output.shrink(amount); }
                } else if (existing.isEmpty()) {
                    int amount = Math.min(output.getCount(), Math.min(output.getMaxStackSize(), inventory.getMaxStackSize()));
                    if (amount > 0) inventory.setItem(i, output.split(amount));
                }
            }
        }
        inventory.setChanged();
    }

    /**
     * Compresses stored Sand into Sandstone using the vanilla 4:1 (2x2) recipe so desert
     * settlements without trees can accumulate structural sandstone blocks.
     */
    public static int compressSandToSandstoneIfEligible(Container container) {
        if (container == null) return 0;
        int sandCount = countItem(container, Items.SAND);
        ItemStack probe = new ItemStack(Items.SANDSTONE, 16);
        int room = inventoryCanFit(container, probe) ? 16 : (inventoryCanFit(container, new ItemStack(Items.SANDSTONE, 1)) ? 1 : 0);
        int produced = com.abs.livingvillages.domain.SettlementSimulationModel.evaluateSandToSandstoneCompression(
            sandCount,
            room
        );
        if (produced <= 0) return 0;
        int neededSand = produced * 4;
        if (!takeItem(container, Items.SAND, neededSand)) {
            return 0;
        }
        insertIntoContainer(container, new ItemStack(Items.SANDSTONE, produced));
        container.setChanged();
        return produced;
    }

    private record StorageRef(BlockPos pos, Container container) {}
}
