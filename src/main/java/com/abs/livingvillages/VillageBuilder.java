package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Worker-led, material-backed house construction. A builder withdraws actual planks and a bed
 * from physical storage, walks to the construction site, and places one block at a time within
 * reach and line of sight. The project is stored on the builder entity so it survives chunk saves.
 */
public final class VillageBuilder {
    public static final String ACTIVE_KEY = "livingvillages_builder_active";
    private static final String P = "livingvillages_builder_";
    private static final String PHASE = P + "phase";
    private static final String PLANK_ITEM = P + "plank_item";
    private static final String DOOR_ITEM = P + "door_item";
    private static final String SITE_X = P + "site_x", SITE_Y = P + "site_y", SITE_Z = P + "site_z";
    private static final String STEP_INDEX = P + "step_index";
    private static final String STARTED = P + "started";
    private static final String NEXT_ACTION = P + "next_action";
    private static final String ROUTE_ACTIVE = P + "route_active";
    private static final String ROUTE_KIND = P + "route_kind";
    private static final String ROUTE_STORAGE_X = P + "storage_x", ROUTE_STORAGE_Y = P + "storage_y", ROUTE_STORAGE_Z = P + "storage_z";
    private static final String ROUTE_STAND_X = P + "stand_x", ROUTE_STAND_Y = P + "stand_y", ROUTE_STAND_Z = P + "stand_z";
    private static final String LAST_ROUTE = P + "last_route";

    private static final int FETCH_PLANKS = 1;
    private static final int FETCH_BED = 2;
    private static final int FETCH_DOOR = 3;
    private static final int BUILDING = 4;
    private static final int ROUTE_TO_STORAGE = 1;
    private static final int ROUTE_TO_BUILD_STEP = 2;
    private static final int PLANKS_REQUIRED = 96;
    private static final long WORK_RETRY_TICKS = 100L;
    private static final long ROUTE_REISSUE_TICKS = 30L;
    private static final long BUILD_STEP_INTERVAL_TICKS = 12L;
    private static final long NIGHT_START = 12000L;
    private static final long DAY_START = 1000L;
    private static final double PLACE_REACH = 4.5D;
    private static final float WALK_SPEED = 0.62F;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private VillageBuilder() {}

    public static boolean isBuilder(Villager villager) {
        return VillagerRoles.ROLE_BUILDER.equals(villager.getPersistentData().getString(VillagerRoles.ROLE_KEY));
    }

    public static boolean isProjectActive(Villager villager) {
        return villager.getPersistentData().getBoolean(ACTIVE_KEY);
    }

    /** Returns the active house blueprint's lower corner, or null when this resident owns no house project. */
    public static BlockPos activeProjectSite(Villager villager) {
        CompoundTag data = villager.getPersistentData();
        if (!data.getBoolean(ACTIVE_KEY)) return null;
        return new BlockPos(data.getInt(SITE_X), data.getInt(SITE_Y), data.getInt(SITE_Z));
    }

    public static boolean hasActiveProjectNear(ServerLevel level, BlockPos center) {
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()) return false;
        return VillageArea.findVillagers(level, center).stream().anyMatch(VillageBuilder::isProjectActive);
    }

    /** Used by role assignment to keep a suitable spare bed available for natural population growth. */
    public static boolean housingNeeded(ServerLevel level, BlockPos center, int population) {
        return population >= countBeds(level, center);
    }

    /** Number of planks used by the current fixed house blueprint. */
    public static int housePlanksRequired() { return PLANKS_REQUIRED; }

    /** Derives a matching real door item from the plank block namespace/path. */
    public static Item doorItemForPlanks(Block plankBlock) {
        if (plankBlock == null || plankBlock == Blocks.AIR
            || !plankBlock.defaultBlockState().is(BlockTags.PLANKS)) return Items.AIR;
        ResourceLocation plankId = BuiltInRegistries.BLOCK.getKey(plankBlock);
        String path = plankId.getPath();
        if (!path.endsWith("_planks")) return Items.AIR;
        String doorPath = path.substring(0, path.length() - "_planks".length()) + "_door";
        ResourceLocation doorId = ResourceLocation.tryParse(plankId.getNamespace() + ":" + doorPath);
        if (doorId == null) return Items.AIR;
        Item item = BuiltInRegistries.ITEM.get(doorId);
        return Block.byItem(item) instanceof DoorBlock ? item : Items.AIR;
    }

    /** Called by the autonomous planner only after it verifies real planks, bed, door and site. */
    public static boolean startHouseProject(ServerLevel level, BlockPos center, BlockPos site,
                                            Block plankBlock, Item doorItem) {
        if (!LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            || plankBlock == null || !plankBlock.defaultBlockState().is(BlockTags.PLANKS)
            || doorItem == null || !(Block.byItem(doorItem) instanceof DoorBlock)
            || !VillageReservations.isProjectSiteAvailable(level, site, 8, null)) return false;
        Villager builder = findAvailableBuilder(level, center);
        if (builder == null) return false;

        Item plankItem = plankBlock.asItem();
        if (!canFitProjectMaterials(builder.getInventory(), plankItem, PLANKS_REQUIRED, Items.RED_BED, doorItem)) return false;

        java.util.Map<Item, Integer> materialBill = java.util.Map.of(
            plankItem, PLANKS_REQUIRED, Items.RED_BED, 1, doorItem, 1);
        if (!VillageReservations.reserveStoragesForItems(level, builder.blockPosition(), builder, "house",
            materialBill, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get())) return false;
        CompoundTag data = builder.getPersistentData();
        data.putBoolean(ACTIVE_KEY, true);
        data.putInt(PHASE, FETCH_PLANKS);
        data.putString(PLANK_ITEM, BuiltInRegistries.ITEM.getKey(plankItem).toString());
        data.putString(DOOR_ITEM, BuiltInRegistries.ITEM.getKey(doorItem).toString());
        data.putInt(SITE_X, site.getX()); data.putInt(SITE_Y, site.getY()); data.putInt(SITE_Z, site.getZ());
        data.putInt(STEP_INDEX, 0);
        data.putLong(STARTED, level.getGameTime());
        data.putLong(NEXT_ACTION, 0L);
        clearOwnedRoute(builder, data);
        return true;
    }

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()
            || !villager.isAlive() || villager.isBaby() || villager.tickCount % 5 != 0
            || !(villager.level() instanceof ServerLevel level)) return;

        CompoundTag data = villager.getPersistentData();
        if (!data.getBoolean(ACTIVE_KEY)) return;
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            pauseRoute(villager, data);
            return;
        }

        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        if (villager.isSleeping() || dayTime < DAY_START || dayTime >= NIGHT_START) {
            pauseRoute(villager, data);
            return;
        }

        BlockPos site = new BlockPos(data.getInt(SITE_X), data.getInt(SITE_Y), data.getInt(SITE_Z));
        if (!level.hasChunkAt(site)) {
            pauseRoute(villager, data);
            return;
        }

        switch (data.getInt(PHASE)) {
            case FETCH_PLANKS -> fetchRequiredItem(villager, level, data,
                getPlankItem(data), Math.max(0, PLANKS_REQUIRED - Math.min(data.getInt(STEP_INDEX), PLANKS_REQUIRED)), FETCH_BED);
            case FETCH_BED -> fetchRequiredItem(villager, level, data, Items.RED_BED, 1, FETCH_DOOR);
            case FETCH_DOOR -> fetchRequiredItem(villager, level, data, getDoorItem(data), 1, BUILDING);
            case BUILDING -> buildNextStep(villager, level, data, site);
            default -> clearProject(villager, data);
        }
    }

    private static Villager findAvailableBuilder(ServerLevel level, BlockPos center) {
        return VillageArea.findVillagers(level, center).stream()
            .filter(v -> v.isAlive() && !v.isBaby() && isBuilder(v) && !isProjectActive(v)
                && !hasOtherActiveTask(v.getPersistentData()))
            .min(Comparator.comparingDouble(v -> v.distanceToSqr(Vec3.atCenterOf(center))))
            .orElse(null);
    }

    private static void fetchRequiredItem(Villager villager, ServerLevel level, CompoundTag data,
                                          Item item, int requiredTotal, int nextPhase) {
        if (item == Items.AIR) {
            clearProject(villager, data);
            return;
        }
        SimpleContainer inventory = villager.getInventory();
        if (countItem(inventory, item) >= requiredTotal) {
            clearOwnedRoute(villager, data);
            data.putInt(PHASE, nextPhase);
            data.putLong(NEXT_ACTION, level.getGameTime() + 5L);
            return;
        }

        long now = level.getGameTime();
        if (data.getBoolean(ROUTE_ACTIVE) && data.getInt(ROUTE_KIND) == ROUTE_TO_STORAGE) {
            BlockPos storagePos = routeStorage(data);
            BlockPos standPos = routeStand(data);
            if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
                || !VillageReservations.isStorageAvailable(level, storagePos, villager)
                || !(level.getBlockEntity(storagePos) instanceof Container storage)) {
                clearOwnedRoute(villager, data);
                data.putLong(NEXT_ACTION, now + WORK_RETRY_TICKS);
                return;
            }
            if (!atRouteTarget(villager, standPos, data, now)) return;

            int needed = requiredTotal - countItem(inventory, item);
            int available = countItem(storage, item);
            int room = inventoryCapacityFor(inventory, item);
            int amount = Math.min(needed, Math.min(available, room));
            if (amount > 0) {
                int removed = removeItem(storage, item, amount);
                if (removed > 0) {
                    ItemStack remainder = inventory.addItem(new ItemStack(item, removed));
                    if (!remainder.isEmpty()) {
                        int returned = insertStack(storage, remainder.copy());
                        if (returned < remainder.getCount()) villager.spawnAtLocation(new ItemStack(item, remainder.getCount() - returned));
                    }
                    inventory.setChanged();
                    storage.setChanged();
                }
            }
            clearOwnedRoute(villager, data);
            data.putLong(NEXT_ACTION, now + 10L);
            return;
        }

        if (now < data.getLong(NEXT_ACTION)) return;
        StorageTarget target = findNearestStorageWithItem(villager, level, item);
        if (target == null) {
            data.putLong(NEXT_ACTION, now + WORK_RETRY_TICKS);
            return; // Wait for a real item to be delivered; never substitute a virtual resource.
        }
        setRoute(villager, data, target.stand(), target.storage(), ROUTE_TO_STORAGE, now);
    }

    private static void buildNextStep(Villager villager, ServerLevel level, CompoundTag data, BlockPos site) {
        Block plankBlock = getPlankBlock(data);
        if (plankBlock == Blocks.AIR || !plankBlock.defaultBlockState().is(BlockTags.PLANKS)) {
            pauseRoute(villager, data);
            return;
        }
        Item doorItem = getDoorItem(data);
        Block doorBlock = Block.byItem(doorItem);
        if (!(doorBlock instanceof DoorBlock)) {
            pauseRoute(villager, data);
            return;
        }
        List<BuildStep> steps = makeHousePlan(site, plankBlock, doorBlock);
        int index = data.getInt(STEP_INDEX);
        if (index >= steps.size()) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.0D,
                villager.getZ(), 4, 0.25D, 0.25D, 0.25D, 0.0D);
            clearProject(villager, data);
            return;
        }
        long now = level.getGameTime();
        if (now < data.getLong(NEXT_ACTION)) return;

        BuildStep step = steps.get(index);
        if (!level.hasChunkAt(step.pos()) || (step.secondPos() != null && !level.hasChunkAt(step.secondPos()))) {
            pauseRoute(villager, data);
            return;
        }
        BlockState current = level.getBlockState(step.pos());
        if (current.equals(step.state()) && (step.secondPos() == null
            || level.getBlockState(step.secondPos()).equals(step.secondState()))) {
            data.putInt(STEP_INDEX, index + 1); // Supports safe continuation after a save/reload.
            clearOwnedRoute(villager, data);
            return;
        }
        // Recover a double-block placement if the world saved after its lower half but before its upper half.
        if (step.secondPos() != null && current.equals(step.state())
            && level.getBlockState(step.secondPos()).isAir()) {
            BlockPos upper = step.secondPos();
            if (withinPlacementReach(villager, upper) && hasClearLine(villager, level, upper)) {
                if (level.setBlock(upper, step.secondState(), 2)) {
                    level.sendBlockUpdated(upper, Blocks.AIR.defaultBlockState(), step.secondState(), 3);
                    data.putInt(STEP_INDEX, index + 1);
                    clearOwnedRoute(villager, data);
                    data.putLong(NEXT_ACTION, level.getGameTime() + BUILD_STEP_INTERVAL_TICKS);
                }
            } else if (!data.getBoolean(ROUTE_ACTIVE) || data.getInt(ROUTE_KIND) != ROUTE_TO_BUILD_STEP) {
                BlockPos stand = findReachableStand(level, villager, upper);
                if (stand != null) setRoute(villager, data, stand, upper, ROUTE_TO_BUILD_STEP, level.getGameTime());
            }
            return;
        }
        if (!current.isAir() || (step.secondPos() != null && !level.getBlockState(step.secondPos()).isAir())) {
            // Never overwrite a player block or remove an obstruction. The owner can clear it later.
            clearOwnedRoute(villager, data);
            data.putLong(NEXT_ACTION, now + WORK_RETRY_TICKS);
            return;
        }

        if (!withinPlacementReach(villager, step.pos()) || !hasClearLine(villager, level, step.pos())) {
            if (!data.getBoolean(ROUTE_ACTIVE) || data.getInt(ROUTE_KIND) != ROUTE_TO_BUILD_STEP) {
                BlockPos stand = findReachableStand(level, villager, step.pos());
                if (stand == null) {
                    data.putLong(NEXT_ACTION, now + WORK_RETRY_TICKS);
                    return;
                }
                setRoute(villager, data, stand, step.pos(), ROUTE_TO_BUILD_STEP, now);
            } else {
                atRouteTarget(villager, routeStand(data), data, now);
            }
            return;
        }

        clearOwnedRoute(villager, data);
        if (step.secondPos() != null) {
            if (countItem(villager.getInventory(), step.item()) < 1) {
                data.putInt(PHASE, step.item() == Items.RED_BED ? FETCH_BED : FETCH_DOOR);
                return;
            }
            if (removeItem(villager.getInventory(), step.item(), 1) != 1) return;
            boolean first = level.setBlock(step.pos(), step.state(), 2);
            boolean second = first && level.setBlock(step.secondPos(), step.secondState(), 2);
            if (!second) {
                if (first) level.setBlock(step.pos(), Blocks.AIR.defaultBlockState(), 3);
                ItemStack remainder = villager.getInventory().addItem(new ItemStack(step.item()));
                if (!remainder.isEmpty()) villager.spawnAtLocation(remainder);
                villager.getInventory().setChanged();
                return;
            }
            level.sendBlockUpdated(step.pos(), Blocks.AIR.defaultBlockState(), step.state(), 3);
            level.sendBlockUpdated(step.secondPos(), Blocks.AIR.defaultBlockState(), step.secondState(), 3);
        } else {
            if (countItem(villager.getInventory(), step.item()) < 1) {
                data.putInt(PHASE, FETCH_PLANKS);
                return;
            }
            if (removeItem(villager.getInventory(), step.item(), 1) != 1) return;
            if (!level.setBlock(step.pos(), step.state(), 3)) {
                ItemStack remainder = villager.getInventory().addItem(new ItemStack(step.item()));
                if (!remainder.isEmpty()) villager.spawnAtLocation(remainder);
                return;
            }
        }
        villager.getInventory().setChanged();
        villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        data.putInt(STEP_INDEX, index + 1);
        data.putLong(NEXT_ACTION, now + BUILD_STEP_INTERVAL_TICKS);
    }

    private static List<BuildStep> makeHousePlan(BlockPos base, Block plankBlock, Block doorBlock) {
        List<BuildStep> steps = new ArrayList<>(98);
        BlockState planks = plankBlock.defaultBlockState();
        Item plankItem = plankBlock.asItem();
        // Floor: 25 real planks.
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) steps.add(new BuildStep(base.offset(dx, 0, dz), planks, plankItem, null, null));
        }
        // Three wall courses. The doorway is left open only in the lower two courses.
        for (int y = 1; y <= 3; y++) {
            for (int dx = 0; dx < 5; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    boolean perimeter = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    boolean doorway = y <= 2 && dx == 2 && dz == 4;
                    if (perimeter && !doorway) steps.add(new BuildStep(base.offset(dx, y, dz), planks, plankItem, null, null));
                }
            }
        }
        // Flat roof at y=4. The bed keeps two full air blocks above it (y=2 and y=3).
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) steps.add(new BuildStep(base.offset(dx, 4, dz), planks, plankItem, null, null));
        }
        BlockPos footPos = base.offset(2, 1, 3);
        BlockPos headPos = base.offset(2, 1, 2);
        BlockState foot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT);
        BlockState head = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD);
        steps.add(new BuildStep(footPos, foot, Items.RED_BED, headPos, head));

        BlockPos doorLowerPos = base.offset(2, 1, 4);
        BlockPos doorUpperPos = base.offset(2, 2, 4);
        BlockState doorLower = doorBlock.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.SOUTH)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState doorUpper = doorLower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        steps.add(new BuildStep(doorLowerPos, doorLower, doorBlock.asItem(), doorUpperPos, doorUpper));
        return steps;
    }

    private static StorageTarget findNearestStorageWithItem(Villager villager, ServerLevel level, Item item) {
        int radius = LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get();
        BlockPos origin = villager.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -6, -radius), origin.offset(radius, 6, radius))) {
            if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                || !VillageReservations.isStorageAvailable(level, pos, villager)) continue;
            if (!(level.getBlockEntity(pos) instanceof Container container) || countItem(container, item) <= 0) continue;
            candidates.add(pos.immutable());
        }
        candidates.sort(Comparator.comparingDouble(pos -> villager.distanceToSqr(Vec3.atCenterOf(pos))));
        for (BlockPos storage : candidates) {
            for (Direction direction : HORIZONTAL) {
                BlockPos stand = storage.relative(direction);
                if (!isSafeStand(level, stand)) continue;
                if (!VillageReservations.tryAddStorageReservation(level, villager, storage, "house")) break;
                return new StorageTarget(storage, stand.immutable());
            }
        }
        return null;
    }

    private static BlockPos findReachableStand(ServerLevel level, Villager villager, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -3; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (!isSafeStand(level, candidate)) continue;
                    Vec3 eye = new Vec3(candidate.getX() + 0.5D, candidate.getY() + 1.62D, candidate.getZ() + 0.5D);
                    if (eye.distanceToSqr(Vec3.atCenterOf(target)) > PLACE_REACH * PLACE_REACH) continue;
                    if (!hasClearLine(level, eye, Vec3.atCenterOf(target), villager)) continue;
                    candidates.add(candidate.immutable());
                }
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(pos -> villager.distanceToSqr(Vec3.atCenterOf(pos)))).orElse(null);
    }

    private static boolean withinPlacementReach(Villager villager, BlockPos target) {
        return villager.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= PLACE_REACH * PLACE_REACH;
    }

    private static boolean hasClearLine(Villager villager, ServerLevel level, BlockPos target) {
        return hasClearLine(level, villager.getEyePosition(), Vec3.atCenterOf(target), villager);
    }

    private static boolean hasClearLine(ServerLevel level, Vec3 from, Vec3 to, Villager villager) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, villager)).getType() == HitResult.Type.MISS;
    }

    private static void setRoute(Villager villager, CompoundTag data, BlockPos stand, BlockPos storageOrTarget,
                                 int kind, long now) {
        clearOwnedRoute(villager, data);
        data.putBoolean(ROUTE_ACTIVE, true);
        data.putInt(ROUTE_KIND, kind);
        data.putInt(ROUTE_STORAGE_X, storageOrTarget.getX()); data.putInt(ROUTE_STORAGE_Y, storageOrTarget.getY()); data.putInt(ROUTE_STORAGE_Z, storageOrTarget.getZ());
        data.putInt(ROUTE_STAND_X, stand.getX()); data.putInt(ROUTE_STAND_Y, stand.getY()); data.putInt(ROUTE_STAND_Z, stand.getZ());
        data.putLong(LAST_ROUTE, now);
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
    }

    private static boolean atRouteTarget(Villager villager, BlockPos stand, CompoundTag data, long now) {
        if (villager.distanceToSqr(Vec3.atCenterOf(stand)) <= 2.8D * 2.8D) {
            clearOwnedRoute(villager, data);
            return true;
        }
        boolean wrongTarget = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
            .map(walk -> !walk.getTarget().currentBlockPosition().equals(stand)).orElse(true);
        if (wrongTarget || now - data.getLong(LAST_ROUTE) >= ROUTE_REISSUE_TICKS) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
            data.putLong(LAST_ROUTE, now);
        }
        return false;
    }

    private static void pauseRoute(Villager villager, CompoundTag data) {
        if (!data.getBoolean(ROUTE_ACTIVE)) return;
        BlockPos stand = routeStand(data);
        villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
            if (walk.getTarget().currentBlockPosition().equals(stand)) villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        });
    }

    private static void clearOwnedRoute(Villager villager, CompoundTag data) {
        if (data.getBoolean(ROUTE_ACTIVE)) {
            BlockPos stand = routeStand(data);
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(stand)) villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            });
        }
        data.putBoolean(ROUTE_ACTIVE, false);
        data.remove(ROUTE_KIND); data.remove(ROUTE_STORAGE_X); data.remove(ROUTE_STORAGE_Y); data.remove(ROUTE_STORAGE_Z);
        data.remove(ROUTE_STAND_X); data.remove(ROUTE_STAND_Y); data.remove(ROUTE_STAND_Z); data.remove(LAST_ROUTE);
    }

    private static void clearProject(Villager villager, CompoundTag data) {
        clearOwnedRoute(villager, data);
        VillageReservations.release(data, "house");
        data.putBoolean(ACTIVE_KEY, false);
        data.remove(PHASE); data.remove(PLANK_ITEM); data.remove(DOOR_ITEM); data.remove(SITE_X); data.remove(SITE_Y); data.remove(SITE_Z);
        data.remove(STEP_INDEX); data.remove(STARTED); data.remove(NEXT_ACTION);
    }

    private static boolean hasOtherActiveTask(CompoundTag data) {
        return data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route")
            || data.getBoolean("livingvillages_autonomy_target_active")
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean("livingvillages_logistics_active")
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY);
    }

    private static Item getPlankItem(CompoundTag data) {
        ResourceLocation key = ResourceLocation.tryParse(data.getString(PLANK_ITEM));
        return key == null ? Items.AIR : BuiltInRegistries.ITEM.get(key);
    }

    private static Item getDoorItem(CompoundTag data) {
        ResourceLocation key = ResourceLocation.tryParse(data.getString(DOOR_ITEM));
        return key == null ? Items.AIR : BuiltInRegistries.ITEM.get(key);
    }

    private static Block getPlankBlock(CompoundTag data) {
        Item item = getPlankItem(data);
        Block block = Block.byItem(item);
        return block == Blocks.AIR ? Blocks.AIR : block;
    }

    private static BlockPos routeStorage(CompoundTag data) {
        return new BlockPos(data.getInt(ROUTE_STORAGE_X), data.getInt(ROUTE_STORAGE_Y), data.getInt(ROUTE_STORAGE_Z));
    }

    private static BlockPos routeStand(CompoundTag data) {
        return new BlockPos(data.getInt(ROUTE_STAND_X), data.getInt(ROUTE_STAND_Y), data.getInt(ROUTE_STAND_Z));
    }

    private static int countBeds(ServerLevel level, BlockPos center) {
        int radius = LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get();
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -8, -radius), center.offset(radius, 8, radius))) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.FOOT) continue;
            BlockPos head = pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
            if (!level.hasChunkAt(head) || !level.getBlockState(pos.above()).isAir()
                || !level.getBlockState(pos.above(2)).isAir()
                || !level.getBlockState(head.above()).isAir() || !level.getBlockState(head.above(2)).isAir()) continue;
            count++;
        }
        return count;
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below())
            && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
            && level.getBlockState(pos.below()).blocksMotion();
    }

    private static int countItem(Container container, Item item) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int removeItem(Container container, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            container.removeItem(i, take);
            remaining -= take;
        }
        if (remaining != amount) container.setChanged();
        return amount - remaining;
    }

    private static int inventoryCapacityFor(Container inventory, Item item) {
        int remaining = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) remaining += item.getDefaultInstance().getMaxStackSize();
            else if (stack.is(item)) remaining += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return remaining;
    }

    private static boolean canFitProjectMaterials(Container inventory, Item plankItem, int planks,
                                                  Item bedItem, Item doorItem) {
        List<ItemStack> simulation = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) simulation.add(inventory.getItem(i).copy());
        int missingPlanks = Math.max(0, planks - countItem(inventory, plankItem));
        int missingBeds = Math.max(0, 1 - countItem(inventory, bedItem));
        int missingDoors = Math.max(0, 1 - countItem(inventory, doorItem));
        return simulateInsert(simulation, missingPlanks == 0 ? ItemStack.EMPTY : new ItemStack(plankItem, missingPlanks))
            && simulateInsert(simulation, missingBeds == 0 ? ItemStack.EMPTY : new ItemStack(bedItem, missingBeds))
            && simulateInsert(simulation, missingDoors == 0 ? ItemStack.EMPTY : new ItemStack(doorItem, missingDoors));
    }

    private static boolean simulateInsert(List<ItemStack> slots, ItemStack offered) {
        if (offered.isEmpty()) return true;
        int remaining = offered.getCount();
        for (ItemStack slot : slots) {
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, offered)) continue;
            int room = slot.getMaxStackSize() - slot.getCount();
            int amount = Math.min(room, remaining);
            if (amount > 0) { slot.grow(amount); remaining -= amount; }
            if (remaining == 0) return true;
        }
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            if (!slots.get(i).isEmpty()) continue;
            int amount = Math.min(offered.getMaxStackSize(), remaining);
            slots.set(i, new ItemStack(offered.getItem(), amount));
            remaining -= amount;
        }
        return remaining == 0;
    }

    private static int insertStack(Container container, ItemStack offered) {
        int initial = offered.getCount();
        for (int pass = 0; pass < 2 && !offered.isEmpty(); pass++) {
            for (int slot = 0; slot < container.getContainerSize() && !offered.isEmpty(); slot++) {
                if (!container.canPlaceItem(slot, offered)) continue;
                ItemStack existing = container.getItem(slot);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, offered)) continue;
                    int room = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
                    int move = Math.min(Math.max(0, room), offered.getCount());
                    if (move > 0) { existing.grow(move); offered.shrink(move); }
                } else if (existing.isEmpty()) {
                    int move = Math.min(offered.getCount(), Math.min(offered.getMaxStackSize(), container.getMaxStackSize()));
                    if (move > 0) container.setItem(slot, offered.split(move));
                }
            }
        }
        return initial - offered.getCount();
    }

    private record StorageTarget(BlockPos storage, BlockPos stand) {}
    private record BuildStep(BlockPos pos, BlockState state, Item item, BlockPos secondPos, BlockState secondState) {}
}
