package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A farm is a real worker project, not a one-tick block generator. The worker carries actual seeds,
 * a water bucket, a composter and a hoe from storage; tills soil one tile at a time, plants crops,
 * places water and the composter, then returns the empty bucket. The project state is saved on the
 * worker so it can survive chunk saves/reloads.
 */
public final class VillageFarmBuilder {
    public static final String ACTIVE_KEY = "livingvillages_farm_build_active";

    private static final String P = "livingvillages_farm_build_";
    private static final String PHASE = P + "phase";
    private static final String SEED_ITEM = P + "seed_item";
    private static final String CROP_BLOCK = P + "crop_block";
    private static final String SITE_X = P + "site_x", SITE_Y = P + "site_y", SITE_Z = P + "site_z";
    private static final String STEP_INDEX = P + "step_index";
    private static final String NEXT_ACTION = P + "next_action";
    private static final String ROUTE_ACTIVE = P + "route_active";
    private static final String ROUTE_KIND = P + "route_kind";
    private static final String ROUTE_ITEM = P + "route_item";
    private static final String ROUTE_STORAGE_X = P + "storage_x", ROUTE_STORAGE_Y = P + "storage_y", ROUTE_STORAGE_Z = P + "storage_z";
    private static final String ROUTE_STAND_X = P + "stand_x", ROUTE_STAND_Y = P + "stand_y", ROUTE_STAND_Z = P + "stand_z";
    private static final String LAST_ROUTE = P + "last_route";

    private static final int FETCH_SEEDS = 1;
    private static final int FETCH_WATER = 2;
    private static final int FETCH_COMPOSTER = 3;
    private static final int FETCH_HOE = 4;
    private static final int BUILDING = 5;
    private static final int RETURN_BUCKET = 6;
    private static final int ROUTE_TO_STORAGE = 1;
    private static final int ROUTE_TO_STEP = 2;
    private static final int ROUTE_TO_OUTPUT = 3;

    private static final int FARM_SIZE = 5;
    private static final int CROP_COUNT = FARM_SIZE * FARM_SIZE - 1;
    private static final int TICK_STEP = 5;
    private static final long RETRY_TICKS = 100L;
    private static final long ROUTE_REISSUE_TICKS = 30L;
    private static final long STEP_DELAY_TICKS = 8L;
    private static final double INTERACTION_REACH = 4.5D;
    private static final float WALK_SPEED = 0.58F;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Item[] HOES = {Items.NETHERITE_HOE, Items.DIAMOND_HOE, Items.IRON_HOE,
        Items.GOLDEN_HOE, Items.STONE_HOE, Items.WOODEN_HOE};

    private VillageFarmBuilder() {}

    public static boolean isProjectActive(Villager villager) {
        return villager.getPersistentData().getBoolean(ACTIVE_KEY);
    }

    public static boolean hasActiveProjectNear(ServerLevel level, BlockPos center) {
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) return false;
        return VillageArea.findVillagers(level, center).stream().anyMatch(VillageFarmBuilder::isProjectActive);
    }

    public static boolean hasHoe(List<Container> containers) {
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (isHoe(container.getItem(slot))) return true;
            }
        }
        return false;
    }

    /**
     * Seed items such as wheat seeds, carrots and potatoes are not all BlockItems.
     * Do not validate them with Block.byItem(seedItem): that can return AIR for a valid seed.
     * Keep the allowed vanilla seed-to-crop pairs explicit so invalid NBT/config data cannot plant
     * an arbitrary block or start a farm with mismatched inputs.
     */
    public static boolean isSupportedCrop(Item seedItem, Block cropBlock) {
        return (seedItem == Items.WHEAT_SEEDS && cropBlock == Blocks.WHEAT)
            || (seedItem == Items.CARROT && cropBlock == Blocks.CARROTS)
            || (seedItem == Items.POTATO && cropBlock == Blocks.POTATOES)
            || (seedItem == Items.BEETROOT_SEEDS && cropBlock == Blocks.BEETROOTS);
    }

    /** Starts a project only if its complete material bill exists in physical storage. */
    public static boolean startFarmProject(ServerLevel level, BlockPos center, BlockPos site,
                                           Item seedItem, Block cropBlock) {
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            || !isSupportedCrop(seedItem, cropBlock)) return false;

        List<Villager> candidates = new ArrayList<>(VillageArea.findVillagers(level, center).stream().filter(v -> v.isAlive()
            && VillagerRoles.isUnemployedAdult(v) && !v.isSleeping()
            && v.getPersistentData().getString(VillagerRoles.ROLE_KEY).isEmpty()
            && !hasOtherTask(v.getPersistentData())
            && !v.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)).toList());
        candidates.sort(Comparator.comparingDouble(v -> v.distanceToSqr(Vec3.atCenterOf(center))));
        List<Container> storages = findStorages(level, center, null);
        Item hoeItem = firstHoeInStorage(storages);
        if (hoeItem == Items.AIR) return false;

        Villager worker = candidates.stream()
            .filter(v -> canFitRequiredItems(v.getInventory(), seedItem, hoeItem))
            .findFirst().orElse(null);
        if (worker == null) return false;
        if (!VillageReservations.reserveNearbyStorages(level, center, worker, "farm")) return false;

        CompoundTag data = worker.getPersistentData();
        data.putBoolean(ACTIVE_KEY, true);
        data.putInt(PHASE, FETCH_SEEDS);
        data.putString(SEED_ITEM, BuiltInRegistries.ITEM.getKey(seedItem).toString());
        data.putString(CROP_BLOCK, BuiltInRegistries.BLOCK.getKey(cropBlock).toString());
        data.putInt(SITE_X, site.getX()); data.putInt(SITE_Y, site.getY()); data.putInt(SITE_Z, site.getZ());
        data.putInt(STEP_INDEX, 0);
        data.putLong(NEXT_ACTION, 0L);
        clearOwnedRoute(worker, data);
        return true;
    }

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager worker) || worker.level().isClientSide()
            || !worker.isAlive() || worker.isBaby() || worker.tickCount % TICK_STEP != 0
            || !(worker.level() instanceof ServerLevel level)) return;

        CompoundTag data = worker.getPersistentData();
        if (!data.getBoolean(ACTIVE_KEY)) return;

        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            || worker.isSleeping() || !isDaytime(level)) {
            pauseRoute(worker, data);
            return;
        }

        BlockPos site = site(data);
        if (!level.hasChunkAt(site)) {
            pauseRoute(worker, data);
            return;
        }
        long now = level.getGameTime();
        switch (data.getInt(PHASE)) {
            case FETCH_SEEDS -> fetchItem(worker, level, data, parseItem(data.getString(SEED_ITEM)), CROP_COUNT, FETCH_WATER, now);
            case FETCH_WATER -> fetchItem(worker, level, data, Items.WATER_BUCKET, 1, FETCH_COMPOSTER, now);
            case FETCH_COMPOSTER -> fetchItem(worker, level, data, Items.COMPOSTER, 1, FETCH_HOE, now);
            case FETCH_HOE -> fetchHoe(worker, level, data, now);
            case BUILDING -> buildNextStep(worker, level, data, site, now);
            case RETURN_BUCKET -> returnEmptyBucket(worker, level, data, now);
            default -> clearProject(worker, data);
        }
    }

    private static void fetchItem(Villager worker, ServerLevel level, CompoundTag data, Item item,
                                  int requiredTotal, int nextPhase, long now) {
        if (item == null || item == Items.AIR) {
            pauseRoute(worker, data);
            data.putLong(NEXT_ACTION, now + RETRY_TICKS);
            return;
        }
        SimpleContainer inventory = worker.getInventory();
        if (countItem(inventory, item) >= requiredTotal) {
            clearOwnedRoute(worker, data);
            data.putInt(PHASE, nextPhase);
            data.putLong(NEXT_ACTION, now + 5L);
            return;
        }
        if (now < data.getLong(NEXT_ACTION)) return;

        if (data.getBoolean(ROUTE_ACTIVE) && data.getInt(ROUTE_KIND) == ROUTE_TO_STORAGE) {
            BlockPos storagePos = routeStorage(data);
            if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
                || !VillageReservations.isStorageAvailable(level, storagePos, worker)
                || !(level.getBlockEntity(storagePos) instanceof Container storage)) {
                clearOwnedRoute(worker, data);
                data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                return;
            }
            if (!atRouteTarget(worker, routeStand(data), data, now)) return;

            int needed = Math.max(0, requiredTotal - countItem(inventory, item));
            int amount = Math.min(needed, Math.min(countItem(storage, item), inventoryCapacityFor(inventory, item)));
            if (amount > 0) {
                int removed = removeItem(storage, item, amount);
                if (removed > 0) {
                    ItemStack remainder = inventory.addItem(new ItemStack(item, removed));
                    if (!remainder.isEmpty()) rollbackOrDrop(worker, level, storage, remainder);
                    inventory.setChanged();
                    storage.setChanged();
                }
            }
            clearOwnedRoute(worker, data);
            data.putLong(NEXT_ACTION, now + 5L);
            return;
        }

        StorageTarget target = findNearestStorageWithItem(worker, level, item);
        if (target == null) {
            data.putLong(NEXT_ACTION, now + RETRY_TICKS);
            return;
        }
        setRoute(worker, data, target.stand(), target.storage(), ROUTE_TO_STORAGE, now, item);
    }

    private static void fetchHoe(Villager worker, ServerLevel level, CompoundTag data, long now) {
        if (hasHoe(worker.getInventory())) {
            clearOwnedRoute(worker, data);
            data.putInt(PHASE, BUILDING);
            data.putLong(NEXT_ACTION, now + 5L);
            return;
        }
        if (now < data.getLong(NEXT_ACTION)) return;

        if (data.getBoolean(ROUTE_ACTIVE) && data.getInt(ROUTE_KIND) == ROUTE_TO_STORAGE) {
            BlockPos storagePos = routeStorage(data);
            Item hoeItem = parseItem(data.getString(ROUTE_ITEM));
            if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
                || !VillageReservations.isStorageAvailable(level, storagePos, worker)
                || !(level.getBlockEntity(storagePos) instanceof Container storage) || !isHoeItem(hoeItem)) {
                clearOwnedRoute(worker, data);
                data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                return;
            }
            if (!atRouteTarget(worker, routeStand(data), data, now)) return;
            if (countItem(storage, hoeItem) > 0 && inventoryCanFit(worker.getInventory(), new ItemStack(hoeItem, 1))) {
                int removed = removeItem(storage, hoeItem, 1);
                if (removed == 1) {
                    ItemStack remainder = worker.getInventory().addItem(new ItemStack(hoeItem));
                    if (!remainder.isEmpty()) rollbackOrDrop(worker, level, storage, remainder);
                    worker.getInventory().setChanged();
                    storage.setChanged();
                }
            }
            clearOwnedRoute(worker, data);
            data.putLong(NEXT_ACTION, now + 5L);
            return;
        }

        List<Container> storages = findStorages(level, VillagerRoles.getHome(worker) == null ? worker.blockPosition() : VillagerRoles.getHome(worker), worker);
        for (Container storage : storages) {
            Item candidate = firstHoeInContainer(storage);
            if (candidate == Items.AIR) continue;
            StorageTarget target = findTargetForContainer(worker, level, storage, candidate);
            if (target == null) continue;
            setRoute(worker, data, target.stand(), target.storage(), ROUTE_TO_STORAGE, now, candidate);
            return;
        }
        data.putLong(NEXT_ACTION, now + RETRY_TICKS);
    }

    private static void buildNextStep(Villager worker, ServerLevel level, CompoundTag data, BlockPos base, long now) {
        ResourceLocation cropKey = ResourceLocation.tryParse(data.getString(CROP_BLOCK));
        Block crop = cropKey == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(cropKey);
        if (crop == Blocks.AIR) {
            pauseRoute(worker, data);
            data.putLong(NEXT_ACTION, now + RETRY_TICKS);
            return;
        }

        List<FarmStep> steps = makePlan(base, crop);
        int index = data.getInt(STEP_INDEX);
        if (index >= steps.size()) {
            data.putInt(PHASE, RETURN_BUCKET);
            clearOwnedRoute(worker, data);
            return;
        }
        if (now < data.getLong(NEXT_ACTION)) return;

        FarmStep step = steps.get(index);
        if (!level.hasChunkAt(step.pos()) || !level.hasChunkAt(step.pos().below())) {
            pauseRoute(worker, data);
            return;
        }
        BlockState current = level.getBlockState(step.pos());
        if (isStepAlreadyDone(step, current, crop)) {
            advance(worker, data, now);
            return;
        }
        if (!isStepSiteStillUsable(level, step, current)) {
            // Someone changed the planned site. Do not destroy or overwrite their block; wait for correction.
            pauseRoute(worker, data);
            data.putLong(NEXT_ACTION, now + RETRY_TICKS);
            return;
        }

        if (!withinReach(worker, step.pos()) || !canSeeTarget(level, worker.getEyePosition(), step.pos(), worker)) {
            if (!data.getBoolean(ROUTE_ACTIVE) || data.getInt(ROUTE_KIND) != ROUTE_TO_STEP) {
                BlockPos stand = findReachableStand(level, worker, step.pos());
                if (stand == null) {
                    data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                    return;
                }
                setRoute(worker, data, stand, step.pos(), ROUTE_TO_STEP, now, Items.AIR);
            } else {
                atRouteTarget(worker, routeStand(data), data, now);
            }
            return;
        }
        clearOwnedRoute(worker, data);
        if (step.kind() == FarmStepKind.TILL) {
            ItemStack hoe = findHoeStack(worker.getInventory());
            if (hoe.isEmpty()) {
                data.putInt(PHASE, FETCH_HOE);
                return;
            }
            if (level.setBlock(step.pos(), Blocks.FARMLAND.defaultBlockState(), 3)) {
                hoe.hurtAndBreak(1, worker, EquipmentSlot.MAINHAND);
                worker.getInventory().setChanged();
                worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                advance(worker, data, now);
            }
            return;
        }
        if (step.kind() == FarmStepKind.WATER) {
            if (countItem(worker.getInventory(), Items.WATER_BUCKET) < 1) {
                data.putInt(PHASE, FETCH_WATER);
                return;
            }
            if (removeItem(worker.getInventory(), Items.WATER_BUCKET, 1) != 1) return;
            if (!level.setBlock(step.pos(), Blocks.WATER.defaultBlockState(), 3)) {
                addOrDrop(worker, level, Items.WATER_BUCKET, step.pos());
                return;
            }
            addOrDrop(worker, level, Items.BUCKET, worker.blockPosition());
            worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            advance(worker, data, now);
            return;
        }
        if (step.kind() == FarmStepKind.PLANT) {
            Item seed = parseItem(data.getString(SEED_ITEM));
            if (countItem(worker.getInventory(), seed) < 1) {
                data.putInt(PHASE, FETCH_SEEDS);
                return;
            }
            if (!level.getBlockState(step.pos().below()).is(Blocks.FARMLAND)
                || removeItem(worker.getInventory(), seed, 1) != 1) return;
            if (!level.setBlock(step.pos(), crop.defaultBlockState(), 3)) {
                addOrDrop(worker, level, seed, worker.blockPosition());
                return;
            }
            worker.getInventory().setChanged();
            worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            advance(worker, data, now);
            return;
        }
        if (step.kind() == FarmStepKind.COMPOSTER) {
            if (countItem(worker.getInventory(), Items.COMPOSTER) < 1) {
                data.putInt(PHASE, FETCH_COMPOSTER);
                return;
            }
            if (removeItem(worker.getInventory(), Items.COMPOSTER, 1) != 1) return;
            if (!level.setBlock(step.pos(), Blocks.COMPOSTER.defaultBlockState(), 3)) {
                addOrDrop(worker, level, Items.COMPOSTER, worker.blockPosition());
                return;
            }
            worker.getInventory().setChanged();
            worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            advance(worker, data, now);
        }
    }

    private static void returnEmptyBucket(Villager worker, ServerLevel level, CompoundTag data, long now) {
        if (countItem(worker.getInventory(), Items.BUCKET) == 0) {
            clearProject(worker, data);
            return;
        }
        if (data.getBoolean(ROUTE_ACTIVE) && data.getInt(ROUTE_KIND) == ROUTE_TO_OUTPUT) {
            BlockPos storagePos = routeStorage(data);
            if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
                || !VillageReservations.isStorageAvailable(level, storagePos, worker)
                || !(level.getBlockEntity(storagePos) instanceof Container storage)) {
                clearOwnedRoute(worker, data);
                data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                return;
            }
            if (!atRouteTarget(worker, routeStand(data), data, now)) return;
            if (!canInsert(storage, new ItemStack(Items.BUCKET, 1))) {
                clearOwnedRoute(worker, data);
                data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                return;
            }
            if (removeItem(worker.getInventory(), Items.BUCKET, 1) == 1) {
                ItemStack offered = new ItemStack(Items.BUCKET, 1);
                if (insert(storage, offered) != 1) addOrDrop(worker, level, Items.BUCKET, worker.blockPosition());
                storage.setChanged();
                worker.getInventory().setChanged();
            }
            clearProject(worker, data);
            return;
        }
        if (now < data.getLong(NEXT_ACTION)) return;
        StorageTarget target = findNearestStorageWithRoom(worker, level, new ItemStack(Items.BUCKET, 1));
        if (target == null) {
            // Do not lose the empty bucket when there is no safe storage destination.
            clearProject(worker, data);
            return;
        }
        setRoute(worker, data, target.stand(), target.storage(), ROUTE_TO_OUTPUT, now, Items.BUCKET);
    }

    private static List<FarmStep> makePlan(BlockPos base, Block cropBlock) {
        List<FarmStep> steps = new ArrayList<>(CROP_COUNT * 2 + 2);
        // First place the actual water source, then prepare each of the 24 crop plots.
        steps.add(new FarmStep(FarmStepKind.WATER, base.offset(2, -1, 2)));
        for (int dx = 0; dx < FARM_SIZE; dx++) {
            for (int dz = 0; dz < FARM_SIZE; dz++) {
                if (dx == 2 && dz == 2) continue;
                steps.add(new FarmStep(FarmStepKind.TILL, base.offset(dx, -1, dz)));
            }
        }
        for (int dx = 0; dx < FARM_SIZE; dx++) {
            for (int dz = 0; dz < FARM_SIZE; dz++) {
                if (dx == 2 && dz == 2) continue;
                steps.add(new FarmStep(FarmStepKind.PLANT, base.offset(dx, 0, dz)));
            }
        }
        steps.add(new FarmStep(FarmStepKind.COMPOSTER, base.offset(2, 0, -1)));
        return steps;
    }

    private static boolean isStepAlreadyDone(FarmStep step, BlockState current, Block cropBlock) {
        return switch (step.kind()) {
            case WATER -> current.is(Blocks.WATER);
            case TILL -> current.is(Blocks.FARMLAND);
            case PLANT -> current.is(cropBlock);
            case COMPOSTER -> current.is(Blocks.COMPOSTER);
        };
    }

    private static boolean isStepSiteStillUsable(ServerLevel level, FarmStep step, BlockState current) {
        return switch (step.kind()) {
            case WATER, TILL -> isFarmableGround(current) && !current.hasBlockEntity();
            case PLANT, COMPOSTER -> current.isAir();
        };
    }

    private static boolean isFarmableGround(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
            || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
            || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND);
    }

    private static void advance(Villager worker, CompoundTag data, long now) {
        clearOwnedRoute(worker, data);
        data.putInt(STEP_INDEX, data.getInt(STEP_INDEX) + 1);
        data.putLong(NEXT_ACTION, now + STEP_DELAY_TICKS);
    }

    private static boolean withinReach(Villager worker, BlockPos target) {
        return worker.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= INTERACTION_REACH * INTERACTION_REACH;
    }

    private static boolean canSeeTarget(ServerLevel level, Vec3 eye, BlockPos target, Villager worker) {
        HitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(target),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, worker));
        return hit.getType() == HitResult.Type.MISS
            || (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target));
    }

    private static BlockPos findReachableStand(ServerLevel level, Villager worker, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (!isSafeStand(level, candidate)) continue;
                    Vec3 eye = new Vec3(candidate.getX() + 0.5D, candidate.getY() + 1.62D, candidate.getZ() + 0.5D);
                    if (eye.distanceToSqr(Vec3.atCenterOf(target)) > INTERACTION_REACH * INTERACTION_REACH) continue;
                    if (!canSeeTarget(level, eye, target, worker)) continue;
                    candidates.add(candidate.immutable());
                }
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(pos -> worker.distanceToSqr(Vec3.atCenterOf(pos)))).orElse(null);
    }

    private static void setRoute(Villager worker, CompoundTag data, BlockPos stand, BlockPos target,
                                 int kind, long now, Item item) {
        clearOwnedRoute(worker, data);
        data.putBoolean(ROUTE_ACTIVE, true);
        data.putInt(ROUTE_KIND, kind);
        data.putInt(ROUTE_STORAGE_X, target.getX()); data.putInt(ROUTE_STORAGE_Y, target.getY()); data.putInt(ROUTE_STORAGE_Z, target.getZ());
        data.putInt(ROUTE_STAND_X, stand.getX()); data.putInt(ROUTE_STAND_Y, stand.getY()); data.putInt(ROUTE_STAND_Z, stand.getZ());
        data.putString(ROUTE_ITEM, item == null || item == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(item).toString());
        data.putLong(LAST_ROUTE, now);
        worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
    }

    private static boolean atRouteTarget(Villager worker, BlockPos stand, CompoundTag data, long now) {
        if (worker.distanceToSqr(Vec3.atCenterOf(stand)) <= 2.8D * 2.8D) {
            clearOwnedRoute(worker, data);
            return true;
        }
        boolean wrongTarget = worker.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
            .map(walk -> !walk.getTarget().currentBlockPosition().equals(stand)).orElse(true);
        if (wrongTarget || now - data.getLong(LAST_ROUTE) >= ROUTE_REISSUE_TICKS) {
            worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
            data.putLong(LAST_ROUTE, now);
        }
        return false;
    }

    private static void pauseRoute(Villager worker, CompoundTag data) {
        if (!data.getBoolean(ROUTE_ACTIVE)) return;
        BlockPos stand = routeStand(data);
        worker.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
            if (walk.getTarget().currentBlockPosition().equals(stand)) worker.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        });
    }

    private static void clearOwnedRoute(Villager worker, CompoundTag data) {
        if (data.getBoolean(ROUTE_ACTIVE)) {
            BlockPos stand = routeStand(data);
            worker.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(stand)) worker.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            });
        }
        data.putBoolean(ROUTE_ACTIVE, false);
        data.remove(ROUTE_KIND); data.remove(ROUTE_ITEM);
        data.remove(ROUTE_STORAGE_X); data.remove(ROUTE_STORAGE_Y); data.remove(ROUTE_STORAGE_Z);
        data.remove(ROUTE_STAND_X); data.remove(ROUTE_STAND_Y); data.remove(ROUTE_STAND_Z); data.remove(LAST_ROUTE);
    }

    private static void clearProject(Villager worker, CompoundTag data) {
        clearOwnedRoute(worker, data);
        VillageReservations.release(data);
        data.putBoolean(ACTIVE_KEY, false);
        data.remove(PHASE); data.remove(SEED_ITEM); data.remove(CROP_BLOCK);
        data.remove(SITE_X); data.remove(SITE_Y); data.remove(SITE_Z);
        data.remove(STEP_INDEX); data.remove(NEXT_ACTION);
    }

    private static boolean hasOtherTask(CompoundTag data) {
        return data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route")
            || data.getBoolean("livingvillages_autonomy_target_active")
            || data.getBoolean("livingvillages_logistics_active")
            || data.getBoolean("livingvillages_industry_route_active")
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || data.getBoolean(VillageBuilder.ACTIVE_KEY)
            || data.getBoolean(ACTIVE_KEY);
    }

    private static boolean isDaytime(ServerLevel level) {
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return time >= 1000L && time < 12000L;
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below())
            && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
            && level.getBlockState(pos.below()).blocksMotion();
    }

    private static List<Container> findStorages(ServerLevel level, BlockPos center, Villager owner) {
        List<Container> found = new ArrayList<>();
        int radius = LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -6; dy <= 6; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                        || !VillageReservations.isStorageAvailable(level, pos, owner)) continue;
                    if (level.getBlockEntity(pos) instanceof Container container) found.add(container);
                }
            }
        }
        return found;
    }

    private static StorageTarget findNearestStorageWithItem(Villager worker, ServerLevel level, Item item) {
        BlockPos origin = VillagerRoles.getHome(worker) == null ? worker.blockPosition() : VillagerRoles.getHome(worker);
        int radius = LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get();
        List<StorageRef> refs = findStorageRefs(level, origin, radius, worker);
        refs.sort(Comparator.comparingDouble(ref -> worker.distanceToSqr(Vec3.atCenterOf(ref.pos()))));
        for (StorageRef ref : refs) {
            if (countItem(ref.container(), item) <= 0) continue;
            StorageTarget target = makeTarget(level, worker, ref.pos());
            if (target != null && VillageReservations.tryAddStorageReservation(level, worker, ref.pos(), "farm")) return target;
        }
        return null;
    }

    private static StorageTarget findNearestStorageWithRoom(Villager worker, ServerLevel level, ItemStack offered) {
        BlockPos origin = VillagerRoles.getHome(worker) == null ? worker.blockPosition() : VillagerRoles.getHome(worker);
        int radius = LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get();
        List<StorageRef> refs = findStorageRefs(level, origin, radius, worker);
        refs.sort(Comparator.comparingDouble(ref -> worker.distanceToSqr(Vec3.atCenterOf(ref.pos()))));
        for (StorageRef ref : refs) {
            if (!canInsert(ref.container(), offered)) continue;
            StorageTarget target = makeTarget(level, worker, ref.pos());
            if (target != null && VillageReservations.tryAddStorageReservation(level, worker, ref.pos(), "farm")) return target;
        }
        return null;
    }

    private static StorageTarget findTargetForContainer(Villager worker, ServerLevel level, Container container, Item item) {
        for (StorageRef ref : findStorageRefs(level, worker.blockPosition(), LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get(), worker)) {
            if (ref.container() != container || countItem(container, item) <= 0) continue;
            StorageTarget target = makeTarget(level, worker, ref.pos());
            if (target != null && VillageReservations.tryAddStorageReservation(level, worker, ref.pos(), "farm")) return target;
        }
        return null;
    }

    private static List<StorageRef> findStorageRefs(ServerLevel level, BlockPos center, int radius, Villager owner) {
        List<StorageRef> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -6; dy <= 6; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                        || !VillageReservations.isStorageAvailable(level, pos, owner)) continue;
                    if (level.getBlockEntity(pos) instanceof Container container) found.add(new StorageRef(pos.immutable(), container));
                }
            }
        }
        found.sort(Comparator.comparingDouble(ref -> workerlessDistance(ref.pos(), center)));
        return found;
    }

    private static double workerlessDistance(BlockPos first, BlockPos second) {
        return first.distSqr(second);
    }

    private static StorageTarget makeTarget(ServerLevel level, Villager worker, BlockPos storage) {
        List<BlockPos> stands = new ArrayList<>();
        for (Direction direction : HORIZONTAL) {
            BlockPos stand = storage.relative(direction);
            if (isSafeStand(level, stand)) stands.add(stand.immutable());
        }
        stands.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(Vec3.atCenterOf(pos))));
        return stands.isEmpty() ? null : new StorageTarget(storage.immutable(), stands.get(0));
    }

    private static Item firstHoeInStorage(List<Container> containers) {
        for (Container container : containers) {
            Item item = firstHoeInContainer(container);
            if (item != Items.AIR) return item;
        }
        return Items.AIR;
    }

    private static Item firstHoeInContainer(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (isHoe(stack)) return stack.getItem();
        }
        return Items.AIR;
    }

    private static boolean isHoe(ItemStack stack) {
        return !stack.isEmpty() && isHoeItem(stack.getItem());
    }

    private static boolean isHoeItem(Item item) {
        for (Item hoe : HOES) if (item == hoe) return true;
        return false;
    }

    private static boolean hasHoe(Container inventory) {
        return !findHoeStack(inventory).isEmpty();
    }

    private static ItemStack findHoeStack(Container inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isHoe(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static boolean canFitRequiredItems(Container inventory, Item seed, Item hoe) {
        List<ItemStack> simulated = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) simulated.add(inventory.getItem(i).copy());
        return simulatedInsert(simulated, seed, Math.max(0, CROP_COUNT - countItem(inventory, seed)))
            && simulatedInsert(simulated, Items.WATER_BUCKET, Math.max(0, 1 - countItem(inventory, Items.WATER_BUCKET)))
            && simulatedInsert(simulated, Items.COMPOSTER, Math.max(0, 1 - countItem(inventory, Items.COMPOSTER)))
            && (hasHoe(inventory) || simulatedInsert(simulated, hoe, 1));
    }

    private static boolean simulatedInsert(List<ItemStack> slots, Item item, int amount) {
        if (amount <= 0) return true;
        int remaining = amount;
        ItemStack sample = new ItemStack(item, 1);
        for (ItemStack existing : slots) {
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, sample)) continue;
            int room = existing.getMaxStackSize() - existing.getCount();
            int moved = Math.min(Math.max(0, room), remaining);
            if (moved > 0) { existing.grow(moved); remaining -= moved; }
            if (remaining == 0) return true;
        }
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            if (!slots.get(i).isEmpty()) continue;
            int moved = Math.min(sample.getMaxStackSize(), remaining);
            slots.set(i, new ItemStack(item, moved));
            remaining -= moved;
        }
        return remaining == 0;
    }

    private static int inventoryCapacityFor(Container inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) total += item.getDefaultInstance().getMaxStackSize();
            else if (stack.is(item)) total += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return total;
    }

    private static int countItem(Container container, Item item) {
        if (item == null || item == Items.AIR) return 0;
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int removeItem(Container container, Item item, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            container.removeItem(slot, take);
            remaining -= take;
        }
        if (remaining != amount) container.setChanged();
        return amount - remaining;
    }

    private static boolean canInsert(Container container, ItemStack offered) {
        int remaining = offered.getCount();
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            if (!container.canPlaceItem(i, offered)) continue;
            ItemStack existing = container.getItem(i);
            int capacity;
            if (existing.isEmpty()) capacity = Math.min(offered.getMaxStackSize(), container.getMaxStackSize());
            else if (ItemStack.isSameItemSameComponents(existing, offered)) capacity = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
            else continue;
            remaining -= Math.max(0, capacity);
        }
        return remaining <= 0;
    }

    private static int insert(Container container, ItemStack offered) {
        int before = offered.getCount();
        for (int pass = 0; pass < 2 && !offered.isEmpty(); pass++) {
            for (int i = 0; i < container.getContainerSize() && !offered.isEmpty(); i++) {
                if (!container.canPlaceItem(i, offered)) continue;
                ItemStack existing = container.getItem(i);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, offered)) continue;
                    int room = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
                    int moved = Math.min(Math.max(0, room), offered.getCount());
                    if (moved > 0) { existing.grow(moved); offered.shrink(moved); }
                } else if (existing.isEmpty()) {
                    int moved = Math.min(offered.getCount(), Math.min(offered.getMaxStackSize(), container.getMaxStackSize()));
                    if (moved > 0) container.setItem(i, offered.split(moved));
                }
            }
        }
        return before - offered.getCount();
    }

    private static void rollbackOrDrop(Villager worker, ServerLevel level, Container storage, ItemStack remainder) {
        ItemStack returned = remainder.copy();
        int inserted = insert(storage, returned);
        if (inserted < remainder.getCount()) {
            ItemStack left = new ItemStack(remainder.getItem(), remainder.getCount() - inserted);
            worker.spawnAtLocation(left);
        }
        storage.setChanged();
    }

    private static void addOrDrop(Villager worker, ServerLevel level, Item item, BlockPos pos) {
        ItemStack remainder = worker.getInventory().addItem(new ItemStack(item, 1));
        if (!remainder.isEmpty()) Block.popResource(level, pos, remainder);
        worker.getInventory().setChanged();
    }

    private static boolean inventoryCanFit(Container inventory, ItemStack offered) {
        List<ItemStack> simulated = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) simulated.add(inventory.getItem(i).copy());
        int remaining = offered.getCount();
        for (int i = 0; i < simulated.size() && remaining > 0; i++) {
            ItemStack existing = simulated.get(i);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, offered)) continue;
            int room = existing.getMaxStackSize() - existing.getCount();
            int moved = Math.min(Math.max(0, room), remaining);
            if (moved > 0) { existing.grow(moved); remaining -= moved; }
        }
        for (int i = 0; i < simulated.size() && remaining > 0; i++) {
            if (!simulated.get(i).isEmpty()) continue;
            int moved = Math.min(offered.getMaxStackSize(), remaining);
            simulated.set(i, new ItemStack(offered.getItem(), moved));
            remaining -= moved;
        }
        return remaining == 0;
    }

    private static Item parseItem(String text) {
        ResourceLocation key = ResourceLocation.tryParse(text);
        return key == null ? Items.AIR : BuiltInRegistries.ITEM.get(key);
    }

    private static BlockPos site(CompoundTag data) {
        return new BlockPos(data.getInt(SITE_X), data.getInt(SITE_Y), data.getInt(SITE_Z));
    }

    private static BlockPos routeStorage(CompoundTag data) {
        return new BlockPos(data.getInt(ROUTE_STORAGE_X), data.getInt(ROUTE_STORAGE_Y), data.getInt(ROUTE_STORAGE_Z));
    }

    private static BlockPos routeStand(CompoundTag data) {
        return new BlockPos(data.getInt(ROUTE_STAND_X), data.getInt(ROUTE_STAND_Y), data.getInt(ROUTE_STAND_Z));
    }

    private static record FarmStep(FarmStepKind kind, BlockPos pos) {}
    private enum FarmStepKind { WATER, TILL, PLANT, COMPOSTER }
    private record StorageRef(BlockPos pos, Container container) {}
    private record StorageTarget(BlockPos storage, BlockPos stand) {}
}
