package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Physical village logistics. Workers pick up nearby loose raw materials, then walk to an
 * actual chest/barrel and transfer only items they really carry. It deliberately does not
 * create resources, teleport workers, or interrupt their tree/mining/guard tasks.
 */
public final class VillageLogistics {
    private static final String P = "livingvillages_logistics_";
    private static final String ACTIVE = P + "active";
    private static final String CHEST_X = P + "chest_x", CHEST_Y = P + "chest_y", CHEST_Z = P + "chest_z";
    private static final String STAND_X = P + "stand_x", STAND_Y = P + "stand_y", STAND_Z = P + "stand_z";
    private static final String STARTED = P + "started", LAST_ORDER = P + "last_order", NEXT_SCAN = P + "next_scan";
    private static final int TICK_STEP = 20;
    private static final int SEARCH_RETRY_TICKS = 400;
    private static final int SUCCESS_RETRY_TICKS = 1200;
    private static final int TRIP_TIMEOUT_TICKS = 2400;
    private static final int PICKUP_RADIUS = 3;
    private static final float WALK_SPEED = 0.55F;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private VillageLogistics() {}

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()
            || !villager.isAlive() || villager.isBaby() || villager.tickCount % TICK_STEP != 0) return;
        if (!(villager.level() instanceof ServerLevel level)) return;

        CompoundTag data = villager.getPersistentData();
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_RESOURCE_LOGISTICS.get()) {
            cancelTrip(villager, data);
            return;
        }

        if (data.getBoolean(ACTIVE)) {
            if (hasHigherPriorityTask(data) || villager.isSleeping() || isNight(level)) {
                cancelTrip(villager, data);
                data.putLong(NEXT_SCAN, level.getGameTime() + SEARCH_RETRY_TICKS);
                return;
            }
            continueTrip(villager, level, data);
            return;
        }

        if (hasHigherPriorityTask(data) || villager.isSleeping() || isNight(level)
            || villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
            || level.getGameTime() < data.getLong(NEXT_SCAN)) return;

        // Farmers already have a dedicated food-storage routine; do not compete with it.
        if (villager.getVillagerData().getProfession() == VillagerProfession.FARMER) return;

        // Pick up only nearby physical drops, never items from an arbitrary distance.
        collectNearbyMaterials(villager, level);
        if (!hasDeliverableMaterial(villager)) {
            data.putLong(NEXT_SCAN, level.getGameTime() + SEARCH_RETRY_TICKS);
            return;
        }

        StorageTarget target = findNearestStorageWithRoom(villager, level);
        if (target == null) {
            data.putLong(NEXT_SCAN, level.getGameTime() + SEARCH_RETRY_TICKS);
            return;
        }

        data.putBoolean(ACTIVE, true);
        data.putInt(CHEST_X, target.storage().getX()); data.putInt(CHEST_Y, target.storage().getY()); data.putInt(CHEST_Z, target.storage().getZ());
        data.putInt(STAND_X, target.stand().getX()); data.putInt(STAND_Y, target.stand().getY()); data.putInt(STAND_Z, target.stand().getZ());
        data.putLong(STARTED, level.getGameTime());
        data.putLong(LAST_ORDER, level.getGameTime());
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target.stand(), WALK_SPEED, 1));
    }

    private static void continueTrip(Villager villager, ServerLevel level, CompoundTag data) {
        long now = level.getGameTime();
        if (now - data.getLong(STARTED) > TRIP_TIMEOUT_TICKS) {
            cancelTrip(villager, data);
            data.putLong(NEXT_SCAN, now + SEARCH_RETRY_TICKS);
            return;
        }
        BlockPos storagePos = new BlockPos(data.getInt(CHEST_X), data.getInt(CHEST_Y), data.getInt(CHEST_Z));
        BlockPos standPos = new BlockPos(data.getInt(STAND_X), data.getInt(STAND_Y), data.getInt(STAND_Z));
        if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
            || !(level.getBlockEntity(storagePos) instanceof Container container)) {
            cancelTrip(villager, data);
            data.putLong(NEXT_SCAN, now + SEARCH_RETRY_TICKS);
            return;
        }

        if (villager.distanceToSqr(Vec3.atCenterOf(standPos)) <= 2.8D * 2.8D) {
            int moved = depositMaterials(villager, container);
            cancelTrip(villager, data);
            data.putLong(NEXT_SCAN, now + (moved > 0 ? SUCCESS_RETRY_TICKS : SEARCH_RETRY_TICKS));
            return;
        }

        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
            && now - data.getLong(LAST_ORDER) >= 40L) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(standPos, WALK_SPEED, 1));
            data.putLong(LAST_ORDER, now);
        }
    }

    private static void collectNearbyMaterials(Villager villager, ServerLevel level) {
        AABB area = villager.getBoundingBox().inflate(PICKUP_RADIUS, 1.5D, PICKUP_RADIUS);
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, area,
            item -> item.isAlive() && isRawResource(item.getItem()))) {
            if (villager.distanceToSqr(entity) > (double) PICKUP_RADIUS * PICKUP_RADIUS) continue;
            ItemStack offered = entity.getItem().copy();
            ItemStack remainder = villager.getInventory().addItem(offered);
            if (remainder.isEmpty()) {
                entity.discard();
            } else if (remainder.getCount() < entity.getItem().getCount()) {
                entity.getItem().setCount(remainder.getCount());
            }
            villager.getInventory().setChanged();
        }
    }

    private static StorageTarget findNearestStorageWithRoom(Villager villager, ServerLevel level) {
        int radius = Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        BlockPos origin = villager.blockPosition();
        List<BlockPos> storagePositions = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))) continue;
                    if (!(level.getBlockEntity(pos) instanceof Container container) || !hasAnySpace(villager, container)) continue;
                    storagePositions.add(pos.immutable());
                }
            }
        }
        storagePositions.sort(Comparator.comparingDouble(pos -> villager.distanceToSqr(Vec3.atCenterOf(pos))));
        for (BlockPos storage : storagePositions) {
            for (Direction direction : HORIZONTAL) {
                BlockPos stand = storage.relative(direction);
                if (isSafeStand(level, stand)) return new StorageTarget(storage, stand.immutable());
            }
        }
        return null;
    }

    private static boolean hasAnySpace(Villager villager, Container container) {
        for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
            ItemStack source = villager.getInventory().getItem(i);
            if (source.isEmpty() || !isRawResource(source)) continue;
            if (hasSpaceFor(container, source)) return true;
        }
        return false;
    }

    private static int depositMaterials(Villager villager, Container destination) {
        int movedTotal = 0;
        var inventory = villager.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack source = inventory.getItem(i);
            if (source.isEmpty() || !isRawResource(source)) continue;
            int moved = insert(destination, source);
            if (moved > 0) {
                movedTotal += moved;
                if (source.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        if (movedTotal > 0) {
            inventory.setChanged();
            destination.setChanged();
        }
        return movedTotal;
    }

    private static int insert(Container destination, ItemStack source) {
        int before = source.getCount();
        for (int pass = 0; pass < 2 && !source.isEmpty(); pass++) {
            for (int i = 0; i < destination.getContainerSize() && !source.isEmpty(); i++) {
                if (!destination.canPlaceItem(i, source)) continue;
                ItemStack existing = destination.getItem(i);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, source)) continue;
                    int room = Math.min(existing.getMaxStackSize(), destination.getMaxStackSize()) - existing.getCount();
                    int amount = Math.min(Math.max(0, room), source.getCount());
                    if (amount > 0) { existing.grow(amount); source.shrink(amount); }
                } else if (existing.isEmpty()) {
                    int amount = Math.min(source.getCount(), Math.min(source.getMaxStackSize(), destination.getMaxStackSize()));
                    if (amount > 0) destination.setItem(i, source.split(amount));
                }
            }
        }
        return before - source.getCount();
    }

    private static boolean hasSpaceFor(Container container, ItemStack source) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!container.canPlaceItem(i, source)) continue;
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(existing, source)
                && existing.getCount() < Math.min(existing.getMaxStackSize(), container.getMaxStackSize())) return true;
        }
        return false;
    }

    private static boolean hasDeliverableMaterial(Villager villager) {
        for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
            if (isRawResource(villager.getInventory().getItem(i))) return true;
        }
        return false;
    }

    private static boolean isRawResource(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Block block = Block.byItem(stack.getItem());
        if (block != Blocks.AIR) {
            BlockState state = block.defaultBlockState();
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.SAPLINGS)) return true;
        }
        return stack.is(Items.STICK) || stack.is(Items.COAL) || stack.is(Items.CHARCOAL)
            || stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)
            || stack.is(Items.RAW_IRON) || stack.is(Items.RAW_COPPER) || stack.is(Items.RAW_GOLD)
            || stack.is(Items.IRON_INGOT) || stack.is(Items.COPPER_INGOT) || stack.is(Items.GOLD_INGOT)
            || stack.is(Items.REDSTONE) || stack.is(Items.LAPIS_LAZULI) || stack.is(Items.DIAMOND)
            || stack.is(Items.EMERALD) || stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS);
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below())
            && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
            && level.getBlockState(pos.below()).blocksMotion();
    }

    private static boolean hasHigherPriorityTask(CompoundTag data) {
        return data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route")
            || data.getBoolean("livingvillages_autonomy_target_active")
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean("livingvillages_industry_route_active")
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || data.getBoolean(VillageBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY);
    }

    private static boolean isNight(ServerLevel level) {
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return time >= 12000L || time < 1000L;
    }

    private static void cancelTrip(Villager villager, CompoundTag data) {
        if (data.getBoolean(ACTIVE)) {
            BlockPos ownStand = new BlockPos(data.getInt(STAND_X), data.getInt(STAND_Y), data.getInt(STAND_Z));
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(ownStand)) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            });
        }
        data.putBoolean(ACTIVE, false);
        data.remove(CHEST_X); data.remove(CHEST_Y); data.remove(CHEST_Z);
        data.remove(STAND_X); data.remove(STAND_Y); data.remove(STAND_Z);
        data.remove(STARTED); data.remove(LAST_ORDER);
    }

    private record StorageTarget(BlockPos storage, BlockPos stand) {}
}
