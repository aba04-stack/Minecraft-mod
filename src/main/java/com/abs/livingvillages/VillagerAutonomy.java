package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;

/**
 * Small, real autonomy layer for vanilla farmer villagers.
 *
 * Farmers keep using vanilla work/harvest AI. When their inventory contains food well
 * above a breeding reserve, they can choose a nearby community container, walk to it
 * through the villager Brain's WALK_TARGET, and transfer the excess food. No items are
 * created; all transferred stacks come out of the villager's actual inventory.
 */
public final class VillagerAutonomy {
    private static final String PREFIX = "livingvillages_autonomy_";
    private static final String TARGET_ACTIVE = PREFIX + "target_active";
    private static final String TARGET_CHEST_X = PREFIX + "chest_x";
    private static final String TARGET_CHEST_Y = PREFIX + "chest_y";
    private static final String TARGET_CHEST_Z = PREFIX + "chest_z";
    private static final String TARGET_STAND_X = PREFIX + "stand_x";
    private static final String TARGET_STAND_Y = PREFIX + "stand_y";
    private static final String TARGET_STAND_Z = PREFIX + "stand_z";
    private static final String TARGET_STARTED = PREFIX + "target_started";
    private static final String LAST_ORDER = PREFIX + "last_order";
    private static final String NEXT_SEARCH = PREFIX + "next_search";

    private static final int SEARCH_STAGGER_TICKS = 400;
    private static final int RETRY_DELAY_TICKS = 600;
    private static final int SUCCESS_DELAY_TICKS = 2400;
    private static final int FAILED_TRIP_TIMEOUT_TICKS = 1200;
    private static final int FOOD_RESERVE_POINTS = 12;
    private static final int MIN_FOOD_POINTS_TO_START = 16;
    private static final float WALK_SPEED = 0.55F;

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private VillagerAutonomy() {
    }

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager)
            || villager.level().isClientSide()
            || !villager.isAlive()
            || villager.isBaby()
            || villager.getVillagerData().getProfession() != VillagerProfession.FARMER
            || villager.tickCount % 20 != 0) {
            return;
        }

        CompoundTag data = villager.getPersistentData();
        if (data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)) return;
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_FARMER_STOCKPILING.get()) {
            if (data.getBoolean(TARGET_ACTIVE)) {
                cancelTrip(villager, data);
            }
            return;
        }

        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }

        long now = level.getGameTime();

        // Do not pull a villager away from rest. Cancel only this mod's own route.
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        if (villager.isSleeping() || dayTime >= 12000L) {
            if (data.getBoolean(TARGET_ACTIVE)) {
                cancelTrip(villager, data);
                data.putLong(NEXT_SEARCH, now + RETRY_DELAY_TICKS);
            }
            return;
        }

        if (data.getBoolean(TARGET_ACTIVE)) {
            handleCurrentTrip(villager, level, data, now);
            return;
        }

        if (dayTime < 1000L) {
            return; // Storage runs are work-hour errands, not a reason to miss sleep.
        }

        if (now < data.getLong(NEXT_SEARCH)) {
            return;
        }

        // Spread scans across time so that many farmers do not search all chests on the same tick.
        long stagger = Math.floorMod(villager.getUUID().hashCode(), SEARCH_STAGGER_TICKS);
        if (Math.floorMod(now, SEARCH_STAGGER_TICKS) != stagger) {
            return;
        }

        if (foodPoints(villager) < MIN_FOOD_POINTS_TO_START) {
            data.putLong(NEXT_SEARCH, now + RETRY_DELAY_TICKS);
            return;
        }

        // Do not commandeer a villager who already has a vanilla movement task.
        if (villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
            data.putLong(NEXT_SEARCH, now + RETRY_DELAY_TICKS);
            return;
        }

        StorageTarget target = findNearestStorage(villager, level);
        if (target == null) {
            data.putLong(NEXT_SEARCH, now + RETRY_DELAY_TICKS);
            return;
        }

        if (!VillageReservations.tryReserveStoragePositions(level, villager, List.of(target.chest()), "food_stockpiling")) {
            data.putLong(NEXT_SEARCH, now + RETRY_DELAY_TICKS);
            return;
        }
        data.putBoolean(TARGET_ACTIVE, true);
        data.putInt(TARGET_CHEST_X, target.chest().getX());
        data.putInt(TARGET_CHEST_Y, target.chest().getY());
        data.putInt(TARGET_CHEST_Z, target.chest().getZ());
        data.putInt(TARGET_STAND_X, target.stand().getX());
        data.putInt(TARGET_STAND_Y, target.stand().getY());
        data.putInt(TARGET_STAND_Z, target.stand().getZ());
        data.putLong(TARGET_STARTED, now);
        data.putLong(LAST_ORDER, now);
        villager.getBrain().setMemory(
            MemoryModuleType.WALK_TARGET,
            new WalkTarget(target.stand(), WALK_SPEED, 1)
        );
    }

    private static void handleCurrentTrip(Villager villager, ServerLevel level, CompoundTag data, long now) {
        BlockPos chestPos = new BlockPos(
            data.getInt(TARGET_CHEST_X), data.getInt(TARGET_CHEST_Y), data.getInt(TARGET_CHEST_Z));
        BlockPos standPos = new BlockPos(
            data.getInt(TARGET_STAND_X), data.getInt(TARGET_STAND_Y), data.getInt(TARGET_STAND_Z));

        if (now - data.getLong(TARGET_STARTED) > FAILED_TRIP_TIMEOUT_TICKS) {
            cancelTrip(villager, data);
            data.putLong(NEXT_SEARCH, now + RETRY_DELAY_TICKS);
            return;
        }

        BlockPos ownershipCenter = VillagerRoles.getHome(villager);
        if (ownershipCenter == null) ownershipCenter = villager.blockPosition();
        if (!level.hasChunkAt(chestPos)
            || !isStorageBlock(level.getBlockState(chestPos))
            || !VillageRegistry.isStorageForVillage(level, ownershipCenter, chestPos)
            || !VillageReservations.isStorageAvailable(level, chestPos, villager)
            || !(level.getBlockEntity(chestPos) instanceof Container container)) {
            cancelTrip(villager, data);
            data.putLong(NEXT_SEARCH, now + RETRY_DELAY_TICKS);
            return;
        }

        double dx = villager.getX() - (standPos.getX() + 0.5D);
        double dy = villager.getY() - standPos.getY();
        double dz = villager.getZ() - (standPos.getZ() + 0.5D);
        if (dx * dx + dy * dy + dz * dz <= 4.0D) {
            int moved = depositSurplus(villager, container);
            cancelTrip(villager, data);
            data.putLong(NEXT_SEARCH, now + (moved > 0 ? SUCCESS_DELAY_TICKS : RETRY_DELAY_TICKS));
            if (moved > 0) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    villager.getX(), villager.getY() + 1.0D, villager.getZ(), 1,
                    0.2D, 0.2D, 0.2D, 0.0D);
            }
            return;
        }

        // Vanilla activities can replace a movement target. Re-issue only occasionally,
        // and only while this particular stockpiling trip remains active.
        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
            && now - data.getLong(LAST_ORDER) >= 40L) {
            villager.getBrain().setMemory(
                MemoryModuleType.WALK_TARGET,
                new WalkTarget(standPos, WALK_SPEED, 1)
            );
            data.putLong(LAST_ORDER, now);
        }
    }

    private static StorageTarget findNearestStorage(Villager villager, ServerLevel level) {
        int radius = Math.min(12, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        BlockPos origin = villager.blockPosition();
        BlockPos ownershipCenter = VillagerRoles.getHome(villager);
        if (ownershipCenter == null) ownershipCenter = origin;
        StorageTarget best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos chestPos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(chestPos) || !isStorageBlock(level.getBlockState(chestPos))
                        || !VillageRegistry.isStorageForVillage(level, ownershipCenter, chestPos)
                        || !VillageReservations.isStorageAvailable(level, chestPos, villager)) {
                        continue;
                    }
                    BlockEntity blockEntity = level.getBlockEntity(chestPos);
                    if (!(blockEntity instanceof Container container)) {
                        continue;
                    }
                    if (!canStoreAnySurplus(villager, container)) {
                        continue;
                    }

                    for (Direction direction : HORIZONTAL_DIRECTIONS) {
                        BlockPos standPos = chestPos.relative(direction);
                        if (!isSafeStandPosition(level, standPos)) {
                            continue;
                        }
                        double distance = villager.distanceToSqr(Vec3.atCenterOf(standPos));
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = new StorageTarget(chestPos.immutable(), standPos.immutable());
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean isStorageBlock(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static boolean isSafeStandPosition(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir()
            || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        return !level.getBlockState(pos.below()).isAir();
    }

    private static boolean canStoreAnySurplus(Villager villager, Container container) {
        int food = foodPoints(villager);
        if (food < MIN_FOOD_POINTS_TO_START) {
            return false;
        }
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack itemStack = villager.getInventory().getItem(slot);
            int value = foodPointValue(itemStack);
            if (value <= 0) {
                continue;
            }
            int movable = Math.min(itemStack.getCount(), Math.max(0, (food - FOOD_RESERVE_POINTS) / value));
            if (movable > 0 && hasSpace(container, itemStack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSpace(Container container, ItemStack source) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (!container.canPlaceItem(slot, source)) {
                continue;
            }
            if (existing.isEmpty() || (existing.getItem() == source.getItem()
                && existing.getCount() < existing.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private static int depositSurplus(Villager villager, Container destination) {
        var inventory = villager.getInventory();
        int remainingFoodPoints = foodPoints(villager);
        int movedTotal = 0;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (remainingFoodPoints <= FOOD_RESERVE_POINTS) {
                break;
            }
            ItemStack source = inventory.getItem(slot);
            int value = foodPointValue(source);
            if (value <= 0 || source.isEmpty()) {
                continue;
            }

            int movable = Math.min(source.getCount(), (remainingFoodPoints - FOOD_RESERVE_POINTS) / value);
            if (movable <= 0) {
                continue;
            }
            int moved = insertInto(destination, source, movable);
            if (moved <= 0) {
                continue;
            }

            source.shrink(moved);
            if (source.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
            remainingFoodPoints -= moved * value;
            movedTotal += moved;
        }

        if (movedTotal > 0) {
            inventory.setChanged();
            destination.setChanged();
        }
        return movedTotal;
    }

    /** Inserts only vanilla food/crops that this feature knows how to reserve safely. */
    private static int insertInto(Container destination, ItemStack source, int limit) {
        int remaining = limit;
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int slot = 0; slot < destination.getContainerSize() && remaining > 0; slot++) {
                if (!destination.canPlaceItem(slot, source)) {
                    continue;
                }
                ItemStack existing = destination.getItem(slot);
                if (pass == 0) {
                    if (existing.isEmpty() || existing.getItem() != source.getItem()
                        || existing.getCount() >= existing.getMaxStackSize()) {
                        continue;
                    }
                    int moved = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                    existing.grow(moved);
                    remaining -= moved;
                } else {
                    if (!existing.isEmpty()) {
                        continue;
                    }
                    int moved = Math.min(remaining, source.getMaxStackSize());
                    destination.setItem(slot, new ItemStack(source.getItem(), moved));
                    remaining -= moved;
                }
            }
        }
        return limit - remaining;
    }

    private static int foodPoints(Villager villager) {
        int points = 0;
        var inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            points += stack.getCount() * foodPointValue(stack);
        }
        return points;
    }

    private static int foodPointValue(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.is(Items.BREAD)) {
            return 4;
        }
        if (stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)) {
            return 1;
        }
        return 0;
    }

    private static void cancelTrip(Villager villager, CompoundTag data) {
        VillageReservations.release(data, "food_stockpiling");
        if (data.getBoolean(TARGET_ACTIVE)) {
            BlockPos ownedStandPos = new BlockPos(
                data.getInt(TARGET_STAND_X), data.getInt(TARGET_STAND_Y), data.getInt(TARGET_STAND_Z));
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walkTarget -> {
                // Only erase a walk target if it still points to our own destination.
                // This avoids cancelling an unrelated vanilla work/home task.
                if (walkTarget.getTarget().currentBlockPosition().equals(ownedStandPos)) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            });
        }
        clearTripData(data);
    }

    private static void clearTripData(CompoundTag data) {
        data.putBoolean(TARGET_ACTIVE, false);
        data.remove(TARGET_CHEST_X);
        data.remove(TARGET_CHEST_Y);
        data.remove(TARGET_CHEST_Z);
        data.remove(TARGET_STAND_X);
        data.remove(TARGET_STAND_Y);
        data.remove(TARGET_STAND_Z);
        data.remove(TARGET_STARTED);
        data.remove(LAST_ORDER);
    }

    private record StorageTarget(BlockPos chest, BlockPos stand) {
    }
}
