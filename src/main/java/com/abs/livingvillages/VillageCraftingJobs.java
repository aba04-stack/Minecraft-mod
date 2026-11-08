package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Physical craftsman work orders. The villager retrieves real ingredients from actual storage,
 * walks to an actual crafting table, spends time there, consumes the inputs, then carries the
 * output back to storage. The work order never creates ingredients or silently deposits results.
 */
public final class VillageCraftingJobs {
    public static final String ACTIVE_KEY = "livingvillages_crafting_job_active";

    private static final String P = "livingvillages_crafting_job_";
    private static final String STAGE = P + "stage";
    private static final String RECIPE = P + "recipe";
    private static final String STARTED = P + "started";
    private static final String NEXT_JOB = P + "next_job";
    private static final String RETRY_AT = P + "retry_at";
    private static final String TABLE_X = P + "table_x", TABLE_Y = P + "table_y", TABLE_Z = P + "table_z";
    private static final String ROUTE_ACTIVE = P + "route_active";
    private static final String ROUTE_KIND = P + "route_kind";
    private static final String ROUTE_X = P + "route_x", ROUTE_Y = P + "route_y", ROUTE_Z = P + "route_z";
    private static final String ROUTE_STORAGE_X = P + "storage_x", ROUTE_STORAGE_Y = P + "storage_y", ROUTE_STORAGE_Z = P + "storage_z";
    private static final String LAST_ROUTE = P + "last_route";
    private static final String CRAFT_STARTED = P + "craft_started";

    private static final int GATHER_INPUTS = 1;
    private static final int GO_TO_TABLE = 2;
    private static final int CRAFTING = 3;
    private static final int DELIVER_OUTPUT = 4;
    private static final int ROUTE_TO_INPUT_STORAGE = 1;
    private static final int ROUTE_TO_TABLE = 2;
    private static final int ROUTE_TO_OUTPUT_STORAGE = 3;

    private static final int TICK_STEP = 10;
    private static final int CRAFT_DURATION_TICKS = 40;
    private static final int JOB_TIMEOUT_TICKS = 48000;
    private static final int ROUTE_REISSUE_TICKS = 40;
    private static final int RETRY_TICKS = 40;
    private static final float WALK_SPEED = 0.90F;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private VillageCraftingJobs() {}

    public static boolean isBusy(Villager villager) {
        return villager != null && villager.getPersistentData().getBoolean(ACTIVE_KEY);
    }

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()
            || !villager.isAlive() || villager.isBaby() || villager.tickCount % TICK_STEP != 0) return;
        if (!(villager.level() instanceof ServerLevel level)) return;

        CompoundTag data = villager.getPersistentData();
        boolean eligibleCrafter = VillagerRoles.isCraftsman(villager)
            || (VillagerRoles.isBuilder(villager) && !VillageBuilder.isProjectActive(villager)
                && VillageArea.findVillagers(level, villager.blockPosition()).stream().noneMatch(VillagerRoles::isCraftsman));
        if (!eligibleCrafter) {
            if (data.getBoolean(ACTIVE_KEY)) {
                clearOwnedRoute(villager, data);
                clearJob(data);
                data.putLong(NEXT_JOB, level.getGameTime() + RETRY_TICKS);
            }
            return;
        }
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_VILLAGE_INDUSTRY.get()
            || !LivingVillagesConfig.ENABLE_PHYSICAL_CRAFTING.get()) {
            if (data.getBoolean(ACTIVE_KEY)) {
                clearOwnedRoute(villager, data);
                clearJob(data);
                data.putLong(NEXT_JOB, level.getGameTime() + RETRY_TICKS);
            }
            return;
        }

        long now = level.getGameTime();
        // Construction has priority over competing plank consumption (KI-008). A running craft order
        // that consumes wood planks pauses without discarding carried ingredients, whereas non-plank
        // stone/iron recipes using existing sticks may proceed in parallel.
        boolean constructionActive = VillageBuilder.hasActiveProjectNear(level, villager.blockPosition())
            || VillageFarmBuilder.hasActiveProjectNear(level, villager.blockPosition());
        if (constructionActive) {
            Recipe activeRecipe = data.getBoolean(ACTIVE_KEY) ? recipeFromId(data.getString(RECIPE)) : null;
            if (activeRecipe == null
                || !com.abs.livingvillages.domain.SettlementSimulationModel.canCraftInParallelWithConstruction(
                    true, recipeConsumesPlanks(activeRecipe))) {
                pauseOwnedRoute(villager, data);
                return;
            }
        }
        if (!isWorkTime(level, villager)) {
            pauseOwnedRoute(villager, data);
            return;
        }

        if (!data.getBoolean(ACTIVE_KEY)) {
            if (now < data.getLong(NEXT_JOB) || hasOtherTask(data)
                || VillagerRoles.shouldYieldToVanillaVillagerAi(villager)) return;
            if (data.getBoolean("livingvillages_industry_route_active")) {
                data.putBoolean("livingvillages_industry_route_active", false);
            }
            startBestJob(villager, level, data, now);
            return;
        }

        if (now - data.getLong(STARTED) > JOB_TIMEOUT_TICKS) {
            // Any ingredients already carried remain real items; normal logistics can return them.
            clearOwnedRoute(villager, data);
            clearJob(data);
            data.putLong(NEXT_JOB, now + RETRY_TICKS);
            return;
        }

        Recipe recipe = recipeFromId(data.getString(RECIPE));
        if (recipe == null) {
            clearOwnedRoute(villager, data);
            clearJob(data);
            data.putLong(NEXT_JOB, now + RETRY_TICKS);
            return;
        }

        BlockPos table = new BlockPos(data.getInt(TABLE_X), data.getInt(TABLE_Y), data.getInt(TABLE_Z));
        switch (data.getInt(STAGE)) {
            case GATHER_INPUTS -> gatherInputs(villager, level, data, recipe, now);
            case GO_TO_TABLE -> goToTable(villager, level, data, recipe, table, now);
            case CRAFTING -> craftAtTable(villager, level, data, recipe, table, now);
            case DELIVER_OUTPUT -> deliverOutput(villager, level, data, recipe, now);
            default -> {
                clearOwnedRoute(villager, data);
                clearJob(data);
                data.putLong(NEXT_JOB, now + RETRY_TICKS);
            }
        }
    }

    private static void startBestJob(Villager villager, ServerLevel level, CompoundTag data, long now) {
        BlockPos origin = VillagerRoles.getHome(villager);
        if (origin == null) origin = villager.blockPosition();
        int radius = Math.min(16, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get());
        BlockPos table = findNearestBlock(level, villager.blockPosition(), Blocks.CRAFTING_TABLE, radius);
        if (table == null) {
            data.putLong(NEXT_JOB, now + RETRY_TICKS);
            return;
        }

        List<StorageRef> storages = findStorages(level, origin, Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get()), villager);
        if (storages.isEmpty()) {
            data.putLong(NEXT_JOB, now + RETRY_TICKS);
            return;
        }
        List<Villager> villagers = VillageArea.findVillagers(level, origin);
        Recipe recipe = chooseRecipe(level, origin, villager, storages, villagers);
        if (recipe == null || !canFitCraftResult(villager.getInventory(), recipe)) {
            // An idle craftsman should not rescan thousands of blocks every few ticks while stock is low.
            data.putLong(NEXT_JOB, now + RETRY_TICKS);
            return;
        }
        Map<Item, Integer> totalInputs = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.inputs()) {
            totalInputs.merge(ingredient.item(), ingredient.count(), Integer::sum);
        }
        Map<Item, Integer> missingInputs = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> input : totalInputs.entrySet()) {
            int missing = Math.max(0, input.getValue() - inventoryCount(villager.getInventory(), input.getKey()));
            if (missing > 0) missingInputs.put(input.getKey(), missing);
        }
        if (!missingInputs.isEmpty() && !VillageReservations.reserveStoragesForItems(level, origin, villager,
            "crafting", missingInputs, Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get()))) {
            data.putLong(NEXT_JOB, now + RETRY_TICKS);
            return;
        }

        data.remove(RETRY_AT);
        data.putBoolean(ACTIVE_KEY, true);
        data.putInt(STAGE, GATHER_INPUTS);
        data.putString(RECIPE, recipe.id());
        data.putLong(STARTED, now);
        data.putInt(TABLE_X, table.getX());
        data.putInt(TABLE_Y, table.getY());
        data.putInt(TABLE_Z, table.getZ());
    }

    /** Demand-driven priority: mining tool first, then an axe, then housing/farm essentials and defense equipment. */
    private static Recipe chooseRecipe(ServerLevel level, BlockPos center, Villager crafter,
                                       List<StorageRef> storages, List<Villager> villagers) {
        boolean housingNeeded = VillageBuilder.housingNeeded(level, center, villagers.size());

        // A village-built sheep pen is a prerequisite for wool production when housing is blocked.
        // Craft only the missing real fence/gate items, using the exact wood species pinned to its order.
        if (VillageLivestock.hasCraftablePenOrder(level, center)) {
            Item fence = VillageLivestock.pendingPenFenceItem(level, center);
            Item gate = VillageLivestock.pendingPenGateItem(level, center);
            Item penPlanks = VillageLivestock.pendingPenPlankItem(level, center);
            int fencesRequired = VillageLivestock.pendingPenFenceCount(level, center);
            int gatesRequired = VillageLivestock.pendingPenGateCount(level, center);
            if ((fencesRequired == 0 || fence != Items.AIR)
                && (gatesRequired == 0 || gate != Items.AIR) && penPlanks != Items.AIR) {
                int fencesAvailable = fence == Items.AIR ? 0 : availableCount(storages, crafter, fence);
                int gatesAvailable = gate == Items.AIR ? 0 : availableCount(storages, crafter, gate);
                int sticksAvailable = availableCount(storages, crafter, Items.STICK);
                int planksAvailable = availableCount(storages, crafter, penPlanks);
                if (fencesAvailable < fencesRequired) {
                    if (sticksAvailable < 2) {
                        return planksAvailable >= 2 ? sticksRecipe(penPlanks) : null;
                    }
                    return planksAvailable >= 4 ? fenceRecipe(penPlanks) : null;
                }
                if (gatesAvailable < gatesRequired) {
                    if (sticksAvailable < 4) {
                        return planksAvailable >= 2 ? sticksRecipe(penPlanks) : null;
                    }
                    return planksAvailable >= 2 ? fenceGateRecipe(penPlanks) : null;
                }
            }
        }

        if (housingNeeded) {
            Item housePlanks = bestStoredHousePlanks(storages, VillageBuilder.housePlanksRequired() + 6);
            if (housePlanks == null && totalStructuralBlocks(storages) >= VillageBuilder.housePlanksRequired()) {
                housePlanks = bestPlanks(storages, crafter, 6);
            }
            if (housePlanks != null && countAllDoors(storages, crafter) == 0) {
                Item door = VillageBuilder.doorItemForPlanks(Block.byItem(housePlanks));
                if (door != Items.AIR && totalItem(storages, door) == 0) {
                    return new Recipe("door|" + BuiltInRegistries.ITEM.getKey(housePlanks), door, 3,
                        List.of(new Ingredient(housePlanks, 6)));
                }
            }
            if (countAllBeds(storages, crafter) == 0) {
                Item bedPlanks = bestPlanks(storages, crafter, 3);
                Item wool = bestWool(storages, crafter, 3);
                if (bedPlanks != null && wool != null) {
                    Recipe bedRecipe = bedRecipe(bedPlanks, wool);
                    if (bedRecipe != null) return bedRecipe;
                }
            }
        }

        int sticks = availableCount(storages, crafter, Items.STICK);
        int cobble = availableCount(storages, crafter, Items.COBBLESTONE);
        int iron = availableCount(storages, crafter, Items.IRON_INGOT);
        int pickaxes = countTools(storages, villagers, VillageCraftingJobs::isPickaxe, ToolUse.MINER);
        int stoneOrBetter = countTools(storages, villagers, VillageCraftingJobs::isStoneOrBetterPickaxe, ToolUse.MINER);
        int ironOrBetter = countTools(storages, villagers, VillageCraftingJobs::isIronOrBetterPickaxe, ToolUse.MINER);
        Item planks3 = bestPlanks(storages, crafter, 3);

        Recipe pickaxeGoal = null;
        if (ironOrBetter == 0 && iron >= 3) {
            pickaxeGoal = fixed("iron_pickaxe", Items.IRON_PICKAXE, 1,
                List.of(new Ingredient(Items.IRON_INGOT, 3), new Ingredient(Items.STICK, 2)));
        } else if (stoneOrBetter == 0 && cobble >= 3) {
            pickaxeGoal = fixed("stone_pickaxe", Items.STONE_PICKAXE, 1,
                List.of(new Ingredient(Items.COBBLESTONE, 3), new Ingredient(Items.STICK, 2)));
        } else if (pickaxes == 0 && planks3 != null) {
            pickaxeGoal = plankTool("wooden_pickaxe", Items.WOODEN_PICKAXE, planks3, 3, 2);
        }
        if (pickaxeGoal != null) {
            if (sticks < 2) {
                int plankReserve = pickaxeGoal.output() == Items.WOODEN_PICKAXE ? 5 : 2;
                Item plankForSticks = bestPlanks(storages, crafter, plankReserve);
                return plankForSticks == null ? null : sticksRecipe(plankForSticks);
            }
            return pickaxeGoal;
        }

        int axes = countTools(storages, villagers, VillageCraftingJobs::isAxe, ToolUse.LUMBERJACK);
        if (axes == 0 && pickaxes > 0) {
            Recipe axe = null;
            if (cobble >= 3) {
                axe = fixed("stone_axe", Items.STONE_AXE, 1,
                    List.of(new Ingredient(Items.COBBLESTONE, 3), new Ingredient(Items.STICK, 2)));
            } else {
                Item planks = bestPlanks(storages, crafter, 3);
                if (planks != null) axe = plankTool("wooden_axe", Items.WOODEN_AXE, planks, 3, 2);
            }
            if (axe != null) {
                if (sticks < 2) {
                    int plankReserve = axe.output() == Items.WOODEN_AXE ? 5 : 2;
                    Item plankForSticks = bestPlanks(storages, crafter, plankReserve);
                    return plankForSticks == null ? null : sticksRecipe(plankForSticks);
                }
                return axe;
            }
        }

        // Farm tools & workstation: hoe and composter so new farms can be built without waiting on manual items.
        if (countHoes(storages, villagers) == 0 && pickaxes > 0) {
            Recipe hoe = null;
            if (cobble >= 2) {
                hoe = fixed("stone_hoe", Items.STONE_HOE, 1,
                    List.of(new Ingredient(Items.COBBLESTONE, 2), new Ingredient(Items.STICK, 2)));
            } else {
                Item planks = bestPlanks(storages, crafter, 2);
                if (planks != null) hoe = plankTool("wooden_hoe", Items.WOODEN_HOE, planks, 2, 2);
            }
            if (hoe != null) {
                if (sticks < 2) {
                    int plankReserve = hoe.output() == Items.WOODEN_HOE ? 4 : 2;
                    Item plankForSticks = bestPlanks(storages, crafter, plankReserve);
                    return plankForSticks == null ? null : sticksRecipe(plankForSticks);
                }
                return hoe;
            }
        }

        if (VillageLivestock.hasShearsRecipe(level, center) && availableCount(storages, crafter, Items.SHEARS) == 0
            && iron >= 2) {
            return fixed("shears", Items.SHEARS, 1, List.of(new Ingredient(Items.IRON_INGOT, 2)));
        }

        if (availableCount(storages, crafter, Items.COMPOSTER) == 0 && pickaxes > 0) {
            Item composterPlanks = bestPlanks(storages, crafter, 7);
            if (composterPlanks != null) {
                return composterRecipe(composterPlanks);
            }
        }

        // A queued farm may request a real empty bucket so a worker can refill it at a safe
        // renewable water source. Never spend iron on buckets without an actual pending farm need.
        if (hasPendingFarmBucketOrder(level, center)
            && VillageFarmBuilder.hasRenewableWaterSource(level, center)
            && availableCount(storages, crafter, Items.BUCKET) == 0 && iron >= 3) {
            return fixed("bucket", Items.BUCKET, 1,
                List.of(new Ingredient(Items.IRON_INGOT, 3)));
        }

        // Prepare a door in advance once the settlement has surplus planks or structural stone/sandstone.
        if (countAllDoors(storages, crafter) == 0) {
            Item doorPlanks = bestPlanks(storages, crafter, 6);
            if (doorPlanks != null && (totalStructuralBlocks(storages) >= 24 || bestPlanks(storages, crafter, 16) != null)) {
                Recipe d = doorRecipe(doorPlanks);
                if (d != null) return d;
            }
        }

        long adults = villagers.stream().filter(v -> v.isAlive() && !v.isBaby()).count();
        int swords = countTools(storages, villagers, VillageCraftingJobs::isSword, ToolUse.GUARD);
        boolean guardRequestedSword = VillageRegistry.isGuardEquipmentRequested(level, center);
        if ((adults >= 3 || guardRequestedSword) && swords == 0) {
            Recipe sword = null;
            if (iron >= 2) {
                sword = fixed("iron_sword", Items.IRON_SWORD, 1,
                    List.of(new Ingredient(Items.IRON_INGOT, 2), new Ingredient(Items.STICK, 1)));
            } else if (cobble >= 2) {
                sword = fixed("stone_sword", Items.STONE_SWORD, 1,
                    List.of(new Ingredient(Items.COBBLESTONE, 2), new Ingredient(Items.STICK, 1)));
            } else {
                Item planks = bestPlanks(storages, crafter, 2);
                if (planks != null) sword = plankTool("wooden_sword", Items.WOODEN_SWORD, planks, 2, 1);
            }
            if (sword != null) {
                int neededSticks = 1;
                if (sticks < neededSticks) {
                    int plankReserve = sword.output() == Items.WOODEN_SWORD ? 4 : 2;
                    Item plankForSticks = bestPlanks(storages, crafter, plankReserve);
                    return plankForSticks == null ? null : sticksRecipe(plankForSticks);
                }
                return sword;
            }
        }
        return null;
    }

    private static void gatherInputs(Villager villager, ServerLevel level, CompoundTag data, Recipe recipe, long now) {
        Ingredient missing = recipe.inputs().stream()
            .filter(ingredient -> inventoryCount(villager.getInventory(), ingredient.item()) < ingredient.count())
            .findFirst().orElse(null);
        if (missing == null) {
            clearOwnedRoute(villager, data);
            data.putInt(STAGE, GO_TO_TABLE);
            return;
        }

        if (!data.getBoolean(ROUTE_ACTIVE)) {
            if (now < data.getLong(RETRY_AT)) return;
            StorageTarget source = findStorageWithItem(level, villager, missing.item());
            if (source == null) {
                data.putLong(RETRY_AT, now + RETRY_TICKS);
                return; // Wait for logistics/mining to provide the real missing item.
            }
            data.remove(RETRY_AT);
            setRoute(villager, data, source.stand(), source.storage(), ROUTE_TO_INPUT_STORAGE, now);
            return;
        }
        if (data.getInt(ROUTE_KIND) != ROUTE_TO_INPUT_STORAGE) {
            clearOwnedRoute(villager, data);
            return;
        }
        if (!atRouteTarget(villager, level, data, now)) return;

        BlockPos storagePos = routeStorage(data);
        if (!level.hasChunkAt(storagePos) || !VillageReservations.isStorageAvailable(level, storagePos, villager)
            || !(level.getBlockEntity(storagePos) instanceof Container container)) {
            clearOwnedRoute(villager, data);
            return;
        }
        int needed = missing.count() - inventoryCount(villager.getInventory(), missing.item());
        int available = countItem(container, missing.item());
        int amount = Math.min(needed, available);
        if (amount > 0 && inventoryCanFit(villager.getInventory(), new ItemStack(missing.item(), amount))) {
            int removed = removeItem(container, missing.item(), amount);
            if (removed > 0) {
                ItemStack incoming = new ItemStack(missing.item(), removed);
                ItemStack remainder = villager.getInventory().addItem(incoming);
                if (!remainder.isEmpty()) {
                    // Defensive rollback: a race or changed inventory must not delete real resources.
                    if (!insert(container, remainder.copy())) villager.spawnAtLocation(remainder);
                }
                villager.getInventory().setChanged();
                container.setChanged();
            }
        }
        clearOwnedRoute(villager, data);
    }

    private static void goToTable(Villager villager, ServerLevel level, CompoundTag data, Recipe recipe,
                                  BlockPos table, long now) {
        if (!level.hasChunkAt(table) || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            abortJob(villager, data, now);
            return;
        }
        if (firstMissingIngredient(villager, recipe) != null) {
            clearOwnedRoute(villager, data);
            data.putInt(STAGE, GATHER_INPUTS);
            return;
        }
        if (!data.getBoolean(ROUTE_ACTIVE)) {
            BlockPos stand = findStandNear(level, villager, table);
            if (stand == null) return;
            setRoute(villager, data, stand, table, ROUTE_TO_TABLE, now);
            return;
        }
        if (data.getInt(ROUTE_KIND) != ROUTE_TO_TABLE) {
            clearOwnedRoute(villager, data);
            return;
        }
        if (!atRouteTarget(villager, level, data, now)) return;
        clearOwnedRoute(villager, data);
        villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        data.putInt(STAGE, CRAFTING);
        data.putLong(CRAFT_STARTED, now);
    }

    private static void craftAtTable(Villager villager, ServerLevel level, CompoundTag data, Recipe recipe,
                                     BlockPos table, long now) {
        if (!level.hasChunkAt(table) || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            abortJob(villager, data, now);
            return;
        }
        if (villager.distanceToSqr(Vec3.atCenterOf(table)) > 4.5D * 4.5D) {
            data.putInt(STAGE, GO_TO_TABLE);
            return;
        }
        long requiredCraftTicks = Math.min(
            CRAFT_DURATION_TICKS,
            VillagerProgression.adjustedDurationTicks(
                villager,
                com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_CRAFTSMAN,
                (int) CRAFT_DURATION_TICKS
            )
        );
        if (now - data.getLong(CRAFT_STARTED) < requiredCraftTicks) return;
        if (firstMissingIngredient(villager, recipe) != null) {
            data.putInt(STAGE, GATHER_INPUTS);
            return;
        }

        // All ingredients are validated before any input is consumed.
        for (Ingredient ingredient : recipe.inputs()) {
            if (inventoryCount(villager.getInventory(), ingredient.item()) < ingredient.count()) return;
        }
        // New items may have been picked up while the worker was walking; never consume inputs unless
        // the finished item can be held safely in the inventory after the recipe is applied.
        if (!canFitOutputAfterConsuming(villager.getInventory(), recipe)) return;
        for (Ingredient ingredient : recipe.inputs()) {
            int removed = removeItem(villager.getInventory(), ingredient.item(), ingredient.count());
            if (removed != ingredient.count()) {
                // This should only be possible if another game mechanic changed the inventory mid-tick.
                // Do not fabricate a result; the job will wait instead of completing with missing inputs.
                data.putInt(STAGE, GATHER_INPUTS);
                return;
            }
        }
        villager.getInventory().setChanged();
        ItemStack output = new ItemStack(recipe.output(), recipe.outputCount());
        ItemStack remainder = villager.getInventory().addItem(output);
        if (!remainder.isEmpty()) villager.spawnAtLocation(remainder);
        villager.getInventory().setChanged();
        villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        data.putInt(STAGE, DELIVER_OUTPUT);
        clearOwnedRoute(villager, data);
    }

    private static void deliverOutput(Villager villager, ServerLevel level, CompoundTag data, Recipe recipe, long now) {
        if (inventoryCount(villager.getInventory(), recipe.output()) < recipe.outputCount()) {
            // Keep the job active until the output can be collected; never mark missing tools as produced.
            return;
        }
        if (!data.getBoolean(ROUTE_ACTIVE)) {
            if (now < data.getLong(RETRY_AT)) return;
            StorageTarget destination = findStorageWithRoom(level, villager, new ItemStack(recipe.output(), recipe.outputCount()));
            if (destination == null) {
                data.putLong(RETRY_AT, now + RETRY_TICKS);
                return; // A full warehouse blocks completion of the order.
            }
            data.remove(RETRY_AT);
            setRoute(villager, data, destination.stand(), destination.storage(), ROUTE_TO_OUTPUT_STORAGE, now);
            return;
        }
        if (data.getInt(ROUTE_KIND) != ROUTE_TO_OUTPUT_STORAGE) {
            clearOwnedRoute(villager, data);
            return;
        }
        if (!atRouteTarget(villager, level, data, now)) return;

        BlockPos storagePos = routeStorage(data);
        if (!level.hasChunkAt(storagePos) || !VillageReservations.isStorageAvailable(level, storagePos, villager)
            || !(level.getBlockEntity(storagePos) instanceof Container container)
            || !canInsert(container, new ItemStack(recipe.output(), recipe.outputCount()))) {
            clearOwnedRoute(villager, data);
            return;
        }

        int removed = removeItem(villager.getInventory(), recipe.output(), recipe.outputCount());
        if (removed != recipe.outputCount()) return;
        ItemStack actualOutput = new ItemStack(recipe.output(), removed);
        ItemStack remainder = insertStack(container, actualOutput);
        if (!remainder.isEmpty()) {
            ItemStack back = villager.getInventory().addItem(remainder);
            if (!back.isEmpty()) villager.spawnAtLocation(back);
        }
        villager.getInventory().setChanged();
        container.setChanged();
        VillagerProgression.recordCompletedWork(
            villager,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_CRAFTSMAN,
            5,
            true,
            now
        );
        clearOwnedRoute(villager, data);
        clearJob(data);
        data.putLong(NEXT_JOB, now + RETRY_TICKS);
    }

    private static void abortJob(Villager villager, CompoundTag data, long now) {
        clearOwnedRoute(villager, data);
        clearJob(data);
        data.putLong(NEXT_JOB, now + RETRY_TICKS);
    }

    private static Ingredient firstMissingIngredient(Villager villager, Recipe recipe) {
        for (Ingredient ingredient : recipe.inputs()) {
            if (inventoryCount(villager.getInventory(), ingredient.item()) < ingredient.count()) return ingredient;
        }
        return null;
    }

    private static boolean recipeConsumesPlanks(Recipe recipe) {
        if (recipe == null) return false;
        for (Ingredient ingredient : recipe.inputs()) {
            if (Block.byItem(ingredient.item()).defaultBlockState().is(BlockTags.PLANKS)) {
                return true;
            }
        }
        return false;
    }

    private static void setRoute(Villager villager, CompoundTag data, BlockPos stand, BlockPos storage,
                                 int routeKind, long now) {
        clearOwnedRoute(villager, data);
        data.putBoolean(ROUTE_ACTIVE, true);
        data.putInt(ROUTE_KIND, routeKind);
        data.putInt(ROUTE_X, stand.getX()); data.putInt(ROUTE_Y, stand.getY()); data.putInt(ROUTE_Z, stand.getZ());
        data.putInt(ROUTE_STORAGE_X, storage.getX()); data.putInt(ROUTE_STORAGE_Y, storage.getY()); data.putInt(ROUTE_STORAGE_Z, storage.getZ());
        data.putLong(LAST_ROUTE, now);
        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
        }
    }

    private static boolean atRouteTarget(Villager villager, ServerLevel level, CompoundTag data, long now) {
        BlockPos target = routeStand(data);
        if (villager.distanceToSqr(Vec3.atCenterOf(target)) <= 3.0D * 3.0D) return true;
        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
            && now - data.getLong(LAST_ROUTE) >= ROUTE_REISSUE_TICKS) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, 1));
            data.putLong(LAST_ROUTE, now);
        }
        return false;
    }

    private static void pauseOwnedRoute(Villager villager, CompoundTag data) {
        if (!data.getBoolean(ROUTE_ACTIVE)) return;
        BlockPos target = routeStand(data);
        villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
            if (walk.getTarget().currentBlockPosition().equals(target)) villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        });
        // Keep route metadata so the same task resumes after the rest period.
    }

    private static void clearOwnedRoute(Villager villager, CompoundTag data) {
        if (data.getBoolean(ROUTE_ACTIVE)) {
            BlockPos target = routeStand(data);
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(target)) villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            });
        }
        data.putBoolean(ROUTE_ACTIVE, false);
        data.remove(ROUTE_KIND);
        data.remove(ROUTE_X); data.remove(ROUTE_Y); data.remove(ROUTE_Z);
        data.remove(ROUTE_STORAGE_X); data.remove(ROUTE_STORAGE_Y); data.remove(ROUTE_STORAGE_Z);
        data.remove(LAST_ROUTE);
    }

    private static void clearJob(CompoundTag data) {
        VillageReservations.release(data, "crafting");
        data.putBoolean(ACTIVE_KEY, false);
        data.remove(STAGE); data.remove(RECIPE); data.remove(STARTED); data.remove(RETRY_AT);
        data.remove(TABLE_X); data.remove(TABLE_Y); data.remove(TABLE_Z); data.remove(CRAFT_STARTED);
    }

    private static boolean isWorkTime(ServerLevel level, Villager villager) {
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        return !villager.isSleeping() && dayTime >= 1000L && dayTime < 12000L;
    }

    private static boolean hasOtherTask(CompoundTag data) {
        return data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route")
            || data.getBoolean("livingvillages_autonomy_target_active")
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean("livingvillages_logistics_active")
            || data.getBoolean(VillageBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageLivestock.ACTIVE_KEY);
    }

    private static boolean hasPendingFarmBucketOrder(ServerLevel level, BlockPos center) {
        return VillageWorkOrders.list(level, center, level.getGameTime()).stream().anyMatch(order ->
            "farm".equals(order.kind())
                && (order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.OPEN
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.WAITING_FOR_RESOURCES)
                && order.requirements().stream().anyMatch(requirement -> requirement != null
                    && "minecraft:bucket".equals(requirement.itemId())));
    }

    static boolean hasValidEmptyBucketRecipe() {
        Recipe recipe = recipeFromId("bucket");
        return recipe != null && recipe.output() == Items.BUCKET && recipe.outputCount() == 1
            && recipe.inputs().size() == 1
            && recipe.inputs().get(0).item() == Items.IRON_INGOT
            && recipe.inputs().get(0).count() == 3;
    }

    static boolean hasValidLivestockPenRecipes() {
        Recipe fence = fenceRecipe(Items.OAK_PLANKS);
        Recipe gate = fenceGateRecipe(Items.OAK_PLANKS);
        Recipe shears = recipeFromId("shears");
        return fence != null && fence.output() == Items.OAK_FENCE && fence.outputCount() == 3
            && hasInputs(fence, Items.OAK_PLANKS, 4, Items.STICK, 2)
            && gate != null && gate.output() == Items.OAK_FENCE_GATE && gate.outputCount() == 1
            && hasInputs(gate, Items.OAK_PLANKS, 2, Items.STICK, 4)
            && shears != null && shears.output() == Items.SHEARS && shears.outputCount() == 1
            && hasInputs(shears, Items.IRON_INGOT, 2);
    }

    private static boolean hasInputs(Recipe recipe, Item first, int firstCount, Item second, int secondCount) {
        return recipe.inputs().size() == 2
            && recipe.inputs().stream().anyMatch(input -> input.item() == first && input.count() == firstCount)
            && recipe.inputs().stream().anyMatch(input -> input.item() == second && input.count() == secondCount);
    }

    private static boolean hasInputs(Recipe recipe, Item first, int firstCount) {
        return recipe.inputs().size() == 1
            && recipe.inputs().get(0).item() == first && recipe.inputs().get(0).count() == firstCount;
    }

    private static Recipe recipeFromId(String id) {
        if (id == null || id.isBlank()) return null;
        int separator = id.indexOf('|');
        if (separator > 0) {
            String kind = id.substring(0, separator);
            String remainder = id.substring(separator + 1);
            if ("bed".equals(kind)) {
                int secondSep = remainder.indexOf('|');
                if (secondSep <= 0) return null;
                ResourceLocation plankKey = ResourceLocation.tryParse(remainder.substring(0, secondSep));
                ResourceLocation woolKey = ResourceLocation.tryParse(remainder.substring(secondSep + 1));
                if (plankKey == null || woolKey == null) return null;
                Item plankItem = BuiltInRegistries.ITEM.get(plankKey);
                Item woolItem = BuiltInRegistries.ITEM.get(woolKey);
                return bedRecipe(plankItem, woolItem);
            }
            ResourceLocation key = ResourceLocation.tryParse(remainder);
            if (key == null) return null;
            Item material = BuiltInRegistries.ITEM.get(key);
            if (material == Items.AIR || !Block.byItem(material).defaultBlockState().is(BlockTags.PLANKS)) return null;
            return switch (kind) {
                case "sticks" -> sticksRecipe(material);
                case "door" -> doorRecipe(material);
                case "fence" -> fenceRecipe(material);
                case "fence_gate" -> fenceGateRecipe(material);
                case "composter" -> composterRecipe(material);
                case "wooden_pickaxe" -> plankTool(kind, Items.WOODEN_PICKAXE, material, 3, 2);
                case "wooden_axe" -> plankTool(kind, Items.WOODEN_AXE, material, 3, 2);
                case "wooden_hoe" -> plankTool(kind, Items.WOODEN_HOE, material, 2, 2);
                case "wooden_sword" -> plankTool(kind, Items.WOODEN_SWORD, material, 2, 1);
                default -> null;
            };
        }
        return switch (id) {
            case "stone_pickaxe" -> fixed(id, Items.STONE_PICKAXE, 1, List.of(new Ingredient(Items.COBBLESTONE, 3), new Ingredient(Items.STICK, 2)));
            case "iron_pickaxe" -> fixed(id, Items.IRON_PICKAXE, 1, List.of(new Ingredient(Items.IRON_INGOT, 3), new Ingredient(Items.STICK, 2)));
            case "stone_axe" -> fixed(id, Items.STONE_AXE, 1, List.of(new Ingredient(Items.COBBLESTONE, 3), new Ingredient(Items.STICK, 2)));
            case "iron_axe" -> fixed(id, Items.IRON_AXE, 1, List.of(new Ingredient(Items.IRON_INGOT, 3), new Ingredient(Items.STICK, 2)));
            case "stone_hoe" -> fixed(id, Items.STONE_HOE, 1, List.of(new Ingredient(Items.COBBLESTONE, 2), new Ingredient(Items.STICK, 2)));
            case "stone_sword" -> fixed(id, Items.STONE_SWORD, 1, List.of(new Ingredient(Items.COBBLESTONE, 2), new Ingredient(Items.STICK, 1)));
            case "iron_sword" -> fixed(id, Items.IRON_SWORD, 1, List.of(new Ingredient(Items.IRON_INGOT, 2), new Ingredient(Items.STICK, 1)));
            case "bucket" -> fixed(id, Items.BUCKET, 1, List.of(new Ingredient(Items.IRON_INGOT, 3)));
            case "shears" -> fixed(id, Items.SHEARS, 1, List.of(new Ingredient(Items.IRON_INGOT, 2)));
            default -> null;
        };
    }

    private static Recipe fenceRecipe(Item planks) {
        if (planks == null || planks == Items.AIR) return null;
        ResourceLocation plankKey = BuiltInRegistries.ITEM.getKey(planks);
        String path = plankKey.getPath();
        if (!path.endsWith("_planks")) return null;
        ResourceLocation fenceKey = ResourceLocation.tryParse(
            plankKey.getNamespace() + ":" + path.substring(0, path.length() - "_planks".length()) + "_fence");
        if (fenceKey == null) return null;
        Item fence = BuiltInRegistries.ITEM.get(fenceKey);
        if (!(Block.byItem(fence) instanceof net.minecraft.world.level.block.FenceBlock)) return null;
        return new Recipe("fence|" + plankKey, fence, 3,
            List.of(new Ingredient(planks, 4), new Ingredient(Items.STICK, 2)));
    }

    private static Recipe fenceGateRecipe(Item planks) {
        if (planks == null || planks == Items.AIR) return null;
        ResourceLocation plankKey = BuiltInRegistries.ITEM.getKey(planks);
        String path = plankKey.getPath();
        if (!path.endsWith("_planks")) return null;
        ResourceLocation gateKey = ResourceLocation.tryParse(
            plankKey.getNamespace() + ":" + path.substring(0, path.length() - "_planks".length()) + "_fence_gate");
        if (gateKey == null) return null;
        Item gate = BuiltInRegistries.ITEM.get(gateKey);
        if (!(Block.byItem(gate) instanceof net.minecraft.world.level.block.FenceGateBlock)) return null;
        return new Recipe("fence_gate|" + plankKey, gate, 1,
            List.of(new Ingredient(planks, 2), new Ingredient(Items.STICK, 4)));
    }

    private static Recipe doorRecipe(Item planks) {
        Item door = VillageBuilder.doorItemForPlanks(Block.byItem(planks));
        if (door == Items.AIR) return null;
        return new Recipe("door|" + BuiltInRegistries.ITEM.getKey(planks), door, 3,
            List.of(new Ingredient(planks, 6)));
    }

    private static Recipe composterRecipe(Item planks) {
        if (planks == null || planks == Items.AIR) return null;
        return new Recipe("composter|" + BuiltInRegistries.ITEM.getKey(planks), Items.COMPOSTER, 1,
            List.of(new Ingredient(planks, 7)));
    }

    private static Recipe bedRecipe(Item planks, Item wool) {
        if (planks == null || planks == Items.AIR || wool == null || wool == Items.AIR) return null;
        Item bedItem = matchingBedForWool(wool);
        if (bedItem == Items.AIR) return null;
        return new Recipe(
            "bed|" + BuiltInRegistries.ITEM.getKey(planks) + "|" + BuiltInRegistries.ITEM.getKey(wool),
            bedItem,
            1,
            List.of(new Ingredient(planks, 3), new Ingredient(wool, 3))
        );
    }

    private static Item matchingBedForWool(Item wool) {
        if (wool == null || wool == Items.AIR) return Items.AIR;
        ResourceLocation woolId = BuiltInRegistries.ITEM.getKey(wool);
        String path = woolId.getPath();
        if (!path.endsWith("_wool")) return Items.AIR;
        String bedPath = path.substring(0, path.length() - "_wool".length()) + "_bed";
        ResourceLocation bedId = ResourceLocation.tryParse(woolId.getNamespace() + ":" + bedPath);
        if (bedId == null) return Items.AIR;
        Item bed = BuiltInRegistries.ITEM.get(bedId);
        return VillageBuilder.isSupportedBedItem(bed) ? bed : Items.AIR;
    }

    private static Item bestWool(List<StorageRef> storages, Villager crafter, int minimum) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (StorageRef ref : storages) {
            Container container = ref.container();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && Block.byItem(stack.getItem()).defaultBlockState().is(BlockTags.WOOL)) {
                    totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        Container inventory = crafter.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && Block.byItem(stack.getItem()).defaultBlockState().is(BlockTags.WOOL)) {
                totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return totals.entrySet().stream().filter(entry -> entry.getValue() >= minimum)
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static int countAllDoors(List<StorageRef> storages, Villager crafter) {
        int total = 0;
        for (Item door : VillageBuilder.ALL_WOODEN_DOORS) {
            total += availableCount(storages, crafter, door);
        }
        return total;
    }

    private static int countAllBeds(List<StorageRef> storages, Villager crafter) {
        int total = 0;
        for (Item bed : VillageBuilder.ALL_BED_ITEMS) {
            total += availableCount(storages, crafter, bed);
        }
        return total;
    }

    private static int countHoes(List<StorageRef> storages, List<Villager> villagers) {
        Predicate<ItemStack> isHoe = stack -> stack.is(Items.WOODEN_HOE) || stack.is(Items.STONE_HOE)
            || stack.is(Items.IRON_HOE) || stack.is(Items.GOLDEN_HOE)
            || stack.is(Items.DIAMOND_HOE) || stack.is(Items.NETHERITE_HOE);
        int total = 0;
        for (StorageRef ref : storages) {
            Container c = ref.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (isHoe.test(c.getItem(i))) total += c.getItem(i).getCount();
            }
        }
        for (Villager v : villagers) {
            if (!VillagerRoles.isEligibleAutonomousAdult(v)) continue;
            if (isHoe.test(v.getMainHandItem())) total += v.getMainHandItem().getCount();
            for (int i = 0; i < v.getInventory().getContainerSize(); i++) {
                if (isHoe.test(v.getInventory().getItem(i))) total += v.getInventory().getItem(i).getCount();
            }
        }
        return total;
    }

    private static int totalStructuralBlocks(List<StorageRef> storages) {
        int total = 0;
        for (StorageRef ref : storages) {
            Container c = ref.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack stack = c.getItem(i);
                if (!stack.isEmpty() && VillageBuilder.isSupportedStructuralBlock(Block.byItem(stack.getItem()))) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private static Item bestStoredHousePlanks(List<StorageRef> storages, int minimum) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (StorageRef ref : storages) {
            Container container = ref.container();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (isPlanks(stack)) totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return totals.entrySet().stream().filter(entry -> entry.getValue() >= minimum)
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static Recipe sticksRecipe(Item planks) {
        return new Recipe("sticks|" + BuiltInRegistries.ITEM.getKey(planks), Items.STICK, 4,
            List.of(new Ingredient(planks, 2)));
    }

    private static Recipe plankTool(String kind, Item output, Item planks, int planksRequired, int sticksRequired) {
        return new Recipe(kind + "|" + BuiltInRegistries.ITEM.getKey(planks), output, 1,
            List.of(new Ingredient(planks, planksRequired), new Ingredient(Items.STICK, sticksRequired)));
    }

    private static Recipe fixed(String id, Item output, int count, List<Ingredient> inputs) {
        return new Recipe(id, output, count, inputs);
    }

    private static int availableCount(List<StorageRef> storages, Villager crafter, Item item) {
        return totalItem(storages, item) + inventoryCount(crafter.getInventory(), item);
    }

    private static Item bestPlanks(List<StorageRef> storages, Villager crafter, int minimum) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (StorageRef ref : storages) {
            Container container = ref.container();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (isPlanks(stack)) totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        Container inventory = crafter.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isPlanks(stack)) totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return totals.entrySet().stream().filter(entry -> entry.getValue() >= minimum)
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static boolean isPlanks(ItemStack stack) {
        return !stack.isEmpty() && Block.byItem(stack.getItem()).defaultBlockState().is(BlockTags.PLANKS);
    }

    private static int countTools(List<StorageRef> storages, List<Villager> villagers,
                                  Predicate<ItemStack> predicate, ToolUse use) {
        int total = 0;
        for (StorageRef ref : storages) {
            Container container = ref.container();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (predicate.test(stack)) total += stack.getCount();
            }
        }
        for (Villager other : villagers) {
            String role = other.getPersistentData().getString(VillagerRoles.ROLE_KEY);
            boolean relevant = switch (use) {
                case MINER -> VillagerRoles.isMiner(other) || (role.isEmpty() && VillagerRoles.isEligibleAutonomousAdult(other));
                case GUARD -> VillagerRoles.isGuard(other) || (role.isEmpty() && VillagerRoles.isEligibleAutonomousAdult(other));
                case LUMBERJACK -> (role.isEmpty() || VillagerRoles.isBuilder(other)) && (VillagerRoles.isEligibleAutonomousAdult(other)
                    || other.getPersistentData().getBoolean("livingvillages_treework_active")
                    || other.getPersistentData().getBoolean(VillagePopulation.ACTIVE_KEY));
            };
            if (!relevant) continue;
            if (predicate.test(other.getMainHandItem())) total += other.getMainHandItem().getCount();
            Container inventory = other.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (predicate.test(stack)) total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean isPickaxe(ItemStack stack) {
        return stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.STONE_PICKAXE) || stack.is(Items.IRON_PICKAXE)
            || stack.is(Items.GOLDEN_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE);
    }

    private static boolean isStoneOrBetterPickaxe(ItemStack stack) {
        return stack.is(Items.STONE_PICKAXE) || isIronOrBetterPickaxe(stack);
    }

    private static boolean isIronOrBetterPickaxe(ItemStack stack) {
        return stack.is(Items.IRON_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE);
    }

    private static boolean isAxe(ItemStack stack) {
        return stack.is(Items.WOODEN_AXE) || stack.is(Items.STONE_AXE) || stack.is(Items.IRON_AXE)
            || stack.is(Items.GOLDEN_AXE) || stack.is(Items.DIAMOND_AXE) || stack.is(Items.NETHERITE_AXE);
    }

    private static boolean isSword(ItemStack stack) {
        return stack.is(Items.WOODEN_SWORD) || stack.is(Items.STONE_SWORD) || stack.is(Items.IRON_SWORD)
            || stack.is(Items.GOLDEN_SWORD) || stack.is(Items.DIAMOND_SWORD) || stack.is(Items.NETHERITE_SWORD);
    }

    private static boolean canFitMissingInputs(Container inventory, Recipe recipe) {
        List<ItemStack> simulated = copyInventory(inventory);
        for (Ingredient ingredient : recipe.inputs()) {
            int current = countItem(simulated, ingredient.item());
            int missing = Math.max(0, ingredient.count() - current);
            if (missing > 0 && !simulatedInsert(simulated, new ItemStack(ingredient.item(), missing), inventory.getMaxStackSize())) return false;
        }
        return true;
    }

    /** Simulates delivery of all missing ingredients, their consumption, and the output stack. */
    private static boolean canFitCraftResult(Container inventory, Recipe recipe) {
        List<ItemStack> simulated = copyInventory(inventory);
        for (Ingredient ingredient : recipe.inputs()) {
            int missing = Math.max(0, ingredient.count() - countItem(simulated, ingredient.item()));
            if (missing > 0 && !simulatedInsert(simulated, new ItemStack(ingredient.item(), missing), inventory.getMaxStackSize())) return false;
        }
        for (Ingredient ingredient : recipe.inputs()) {
            if (removeFromSimulation(simulated, ingredient.item(), ingredient.count()) != ingredient.count()) return false;
        }
        return simulatedInsert(simulated, new ItemStack(recipe.output(), recipe.outputCount()), inventory.getMaxStackSize());
    }

    /** Checks output capacity against the inventory exactly as it stands at the workbench. */
    private static boolean canFitOutputAfterConsuming(Container inventory, Recipe recipe) {
        List<ItemStack> simulated = copyInventory(inventory);
        for (Ingredient ingredient : recipe.inputs()) {
            if (removeFromSimulation(simulated, ingredient.item(), ingredient.count()) != ingredient.count()) return false;
        }
        return simulatedInsert(simulated, new ItemStack(recipe.output(), recipe.outputCount()), inventory.getMaxStackSize());
    }

    private static int removeFromSimulation(List<ItemStack> slots, Item item, int amount) {
        int remaining = amount;
        for (ItemStack stack : slots) {
            if (remaining <= 0) break;
            if (!stack.is(item)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            remaining -= taken;
        }
        return amount - remaining;
    }

    private static boolean inventoryCanFit(Container inventory, ItemStack offered) {
        return simulatedInsert(copyInventory(inventory), offered.copy(), inventory.getMaxStackSize());
    }

    private static List<ItemStack> copyInventory(Container inventory) {
        List<ItemStack> result = new ArrayList<>(inventory.getContainerSize());
        for (int i = 0; i < inventory.getContainerSize(); i++) result.add(inventory.getItem(i).copy());
        return result;
    }

    private static boolean simulatedInsert(List<ItemStack> slots, ItemStack offered, int containerMax) {
        for (int pass = 0; pass < 2 && !offered.isEmpty(); pass++) {
            for (int i = 0; i < slots.size() && !offered.isEmpty(); i++) {
                ItemStack existing = slots.get(i);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, offered)) continue;
                    int capacity = Math.min(existing.getMaxStackSize(), containerMax) - existing.getCount();
                    int moved = Math.min(Math.max(0, capacity), offered.getCount());
                    if (moved > 0) { existing.grow(moved); offered.shrink(moved); }
                } else if (existing.isEmpty()) {
                    int moved = Math.min(offered.getCount(), Math.min(offered.getMaxStackSize(), containerMax));
                    if (moved > 0) slots.set(i, offered.split(moved));
                }
            }
        }
        return offered.isEmpty();
    }

    private static int inventoryCount(Container inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int countItem(List<ItemStack> stacks, Item item) {
        int total = 0;
        for (ItemStack stack : stacks) if (stack.is(item)) total += stack.getCount();
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

    private static int removeItem(Container container, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.is(item)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            remaining -= taken;
            if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
        }
        container.setChanged();
        return amount - remaining;
    }

    private static boolean insert(Container container, ItemStack offered) {
        return insertStack(container, offered).isEmpty();
    }

    private static ItemStack insertStack(Container container, ItemStack offered) {
        ItemStack remainder = offered.copy();
        for (int pass = 0; pass < 2 && !remainder.isEmpty(); pass++) {
            for (int i = 0; i < container.getContainerSize() && !remainder.isEmpty(); i++) {
                if (!container.canPlaceItem(i, remainder)) continue;
                ItemStack existing = container.getItem(i);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remainder)) continue;
                    int capacity = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
                    int moved = Math.min(Math.max(0, capacity), remainder.getCount());
                    if (moved > 0) { existing.grow(moved); remainder.shrink(moved); container.setChanged(); }
                } else if (existing.isEmpty()) {
                    int moved = Math.min(remainder.getCount(), Math.min(remainder.getMaxStackSize(), container.getMaxStackSize()));
                    if (moved > 0) { container.setItem(i, remainder.split(moved)); container.setChanged(); }
                }
            }
        }
        return remainder;
    }

    private static boolean canInsert(Container container, ItemStack offered) {
        int remaining = offered.getCount();
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            if (!container.canPlaceItem(i, offered)) continue;
            ItemStack existing = container.getItem(i);
            int capacity;
            if (existing.isEmpty()) capacity = Math.min(offered.getMaxStackSize(), container.getMaxStackSize());
            else if (ItemStack.isSameItemSameComponents(existing, offered)) {
                capacity = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
            } else continue;
            remaining -= Math.max(0, capacity);
        }
        return remaining <= 0;
    }

    private static StorageTarget findStorageWithItem(ServerLevel level, Villager villager, Item item) {
        int radius = Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        BlockPos origin = VillagerRoles.getHome(villager);
        if (origin == null) origin = villager.blockPosition();
        List<StorageRef> storages = findStorages(level, origin, radius, villager);
        storages.sort(Comparator.comparingDouble(ref -> villager.distanceToSqr(Vec3.atCenterOf(ref.pos()))));
        for (StorageRef ref : storages) {
            if (countItem(ref.container(), item) <= 0) continue;
            StorageTarget target = makeTarget(level, villager, ref.pos());
            if (target != null && VillageReservations.tryAddStorageReservation(level, villager, ref.pos(), "crafting")) return target;
        }
        return null;
    }

    private static StorageTarget findStorageWithRoom(ServerLevel level, Villager villager, ItemStack output) {
        int radius = Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        BlockPos origin = VillagerRoles.getHome(villager);
        if (origin == null) origin = villager.blockPosition();
        List<StorageRef> storages = findStorages(level, origin, radius, villager);
        storages.sort(Comparator.comparingDouble(ref -> villager.distanceToSqr(Vec3.atCenterOf(ref.pos()))));
        for (StorageRef ref : storages) {
            if (!canInsert(ref.container(), output)) continue;
            StorageTarget target = makeTarget(level, villager, ref.pos());
            if (target != null && VillageReservations.tryAddStorageReservation(level, villager, ref.pos(), "crafting")) return target;
        }
        return null;
    }

    private static StorageTarget makeTarget(ServerLevel level, Villager villager, BlockPos storage) {
        List<BlockPos> stands = new ArrayList<>();
        for (Direction direction : HORIZONTAL) {
            BlockPos stand = storage.relative(direction);
            if (isSafeStand(level, stand)) stands.add(stand.immutable());
        }
        stands.sort(Comparator.comparingDouble(pos -> villager.distanceToSqr(Vec3.atCenterOf(pos))));
        return stands.isEmpty() ? null : new StorageTarget(storage.immutable(), stands.get(0));
    }

    private static List<StorageRef> findStorages(ServerLevel level, BlockPos center, int radius, Villager owner) {
        List<StorageRef> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -6; dy <= 6; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                        || !VillageRegistry.isStorageForVillage(level, center, pos)
                        || !VillageReservations.isStorageAvailable(level, pos, owner)) continue;
                    BlockEntity entity = level.getBlockEntity(pos);
                    if (entity instanceof Container container) found.add(new StorageRef(pos.immutable(), container));
                }
            }
        }
        found.sort(Comparator.comparingDouble(ref -> ref.pos().distSqr(center)));
        return found;
    }

    /**
     * Nearest workstation for one idle craftsman, sharing the ring-ordered scan.
     *
     * <p>An idle craftsman retries every {@code RETRY_TICKS}; the previous full-box walk read the whole
     * 33x33x13 volume on each retry even when the workshop stood two blocks away, and it ran before the
     * cheap "is there anything to craft" checks.</p>
     */
    private static BlockPos findNearestBlock(ServerLevel level, BlockPos center, Block block, int radius) {
        return VillageScanRings.nearestOwned(level, center, block, radius, 6, 6);
    }

    private static BlockPos findStandNear(ServerLevel level, Villager villager, BlockPos target) {
        List<BlockPos> stands = new ArrayList<>();
        for (Direction direction : HORIZONTAL) {
            BlockPos stand = target.relative(direction);
            if (isSafeStand(level, stand)) stands.add(stand.immutable());
        }
        stands.sort(Comparator.comparingDouble(pos -> villager.distanceToSqr(Vec3.atCenterOf(pos))));
        return stands.isEmpty() ? null : stands.get(0);
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below())
            && VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(pos))
            && VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(pos.above()))
            && level.getBlockState(pos.below()).blocksMotion();
    }

    private static boolean isStorage(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static BlockPos routeStand(CompoundTag data) {
        return new BlockPos(data.getInt(ROUTE_X), data.getInt(ROUTE_Y), data.getInt(ROUTE_Z));
    }

    private static BlockPos routeStorage(CompoundTag data) {
        return new BlockPos(data.getInt(ROUTE_STORAGE_X), data.getInt(ROUTE_STORAGE_Y), data.getInt(ROUTE_STORAGE_Z));
    }

    private enum ToolUse { MINER, GUARD, LUMBERJACK }
    private record Ingredient(Item item, int count) {}
    private record Recipe(String id, Item output, int outputCount, List<Ingredient> inputs) {}
    private record StorageRef(BlockPos pos, Container container) {}
    private record StorageTarget(BlockPos storage, BlockPos stand) {}
}
