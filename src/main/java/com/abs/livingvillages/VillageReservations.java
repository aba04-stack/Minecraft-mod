package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coarse-grained persistent claims for physical storage blocks.
 *
 * A claim is stored on its worker's persistent data, so it survives world save/reload.
 * A claimed chest is hidden from other material-consuming jobs until the owner finishes/cancels
 * its task. This reserves physical containers, not virtual item counts, and intentionally prefers
 * waiting to competing for the same stock.
 */
public final class VillageReservations {
    private static final String PREFIX = "livingvillages_resource_reservation_";
    private static final String ACTIVE = PREFIX + "active";
    private static final String KIND = PREFIX + "kind";
    private static final String POSITIONS = PREFIX + "positions";
    private static final int LOOKUP_RADIUS = 80;
    private static final Direction[] HORIZONTAL = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private VillageReservations() {}

    public static boolean reserveNearbyStorages(ServerLevel level, BlockPos center, Villager owner, String kind) {
        int radius = LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get();
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
            center.offset(-radius, -8, -radius), center.offset(radius, 8, radius))) {
            if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))) continue;
            if (!isStorageAvailable(level, pos, owner)) continue;
            positions.add(pos.immutable());
        }
        return tryReserveStoragePositions(level, owner, positions, kind);
    }

    /**
     * Claim only physical containers that hold items needed by a task.
     * Item totals are validated before any claim is saved, so partial stock never starts a job.
     */
    public static boolean reserveStoragesForItems(ServerLevel level, BlockPos center, Villager owner,
                                                  String kind, Map<Item, Integer> requirements, int requestedRadius) {
        if (owner == null || !owner.isAlive() || requirements == null || requirements.isEmpty()
            || owner.getPersistentData().getBoolean(ACTIVE)) return false;
        int radius = Math.max(0, Math.min(requestedRadius, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get()));
        Map<Item, Integer> available = new HashMap<>();
        List<BlockPos> relevantStorages = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(
            center.offset(-radius, -8, -radius), center.offset(radius, 8, radius))) {
            if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                || !isStorageAvailable(level, pos, owner)) continue;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) continue;

            boolean relevant = false;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !requirements.containsKey(stack.getItem())) continue;
                available.merge(stack.getItem(), stack.getCount(), Integer::sum);
                relevant = true;
            }
            if (relevant) relevantStorages.add(pos.immutable());
        }

        for (Map.Entry<Item, Integer> requirement : requirements.entrySet()) {
            if (requirement.getValue() <= 0
                || available.getOrDefault(requirement.getKey(), 0) < requirement.getValue()) return false;
        }
        return tryReserveStoragePositions(level, owner, relevantStorages, kind);
    }

    public static boolean tryReserveStoragePositions(ServerLevel level, Villager owner,
                                                     List<BlockPos> requested, String kind) {
        if (owner == null || !owner.isAlive() || requested == null || requested.isEmpty()) return false;
        CompoundTag data = owner.getPersistentData();
        if (data.getBoolean(ACTIVE)) return false;

        Set<Long> positions = normalizeStoragePositions(level, requested);
        if (positions.isEmpty()) return false;
        for (long packed : positions) {
            if (!isStorageAvailable(level, BlockPos.of(packed), owner)) return false;
        }
        save(data, positions, kind);
        return true;
    }

    public static boolean tryAddStorageReservation(ServerLevel level, Villager owner, BlockPos storage, String kind) {
        if (owner == null || !owner.isAlive() || storage == null || !level.hasChunkAt(storage)
            || !isStorage(level.getBlockState(storage))) return false;
        CompoundTag data = owner.getPersistentData();
        if (data.getBoolean(ACTIVE) && !data.getString(KIND).equals(kind)) return false;
        Set<Long> positions = readPositions(data);
        if (data.getBoolean(ACTIVE) && positions.contains(storage.asLong())) return true;

        Set<Long> additions = normalizeStoragePositions(level, List.of(storage));
        if (additions.isEmpty()) return false;
        for (long packed : additions) {
            if (!isStorageAvailable(level, BlockPos.of(packed), owner)) return false;
        }
        positions.addAll(additions);
        save(data, positions, kind);
        return true;
    }

    public static boolean isStorageAvailable(ServerLevel level, BlockPos storage, Villager owner) {
        if (storage == null) return false;
        AABB search = new AABB(storage).inflate(LOOKUP_RADIUS, 24.0D, LOOKUP_RADIUS);
        for (Villager candidate : level.getEntitiesOfClass(Villager.class, search, Villager::isAlive)) {
            if (candidate == owner) continue;
            CompoundTag data = candidate.getPersistentData();
            if (!data.getBoolean(ACTIVE)) continue;
            for (long packed : data.getLongArray(POSITIONS)) {
                if (packed == storage.asLong()) return false;
            }
        }
        return true;
    }

    public static boolean hasReservationInArea(ServerLevel level, BlockPos center, int radius, int verticalRadius) {
        int horizontal = Math.max(0, radius);
        int vertical = Math.max(0, verticalRadius);
        AABB search = new AABB(center).inflate(horizontal + LOOKUP_RADIUS, vertical + 24.0D,
            horizontal + LOOKUP_RADIUS);
        int minX = center.getX() - horizontal, maxX = center.getX() + horizontal;
        int minY = center.getY() - vertical, maxY = center.getY() + vertical;
        int minZ = center.getZ() - horizontal, maxZ = center.getZ() + horizontal;
        for (Villager candidate : level.getEntitiesOfClass(Villager.class, search, Villager::isAlive)) {
            CompoundTag data = candidate.getPersistentData();
            if (!data.getBoolean(ACTIVE)) continue;
            for (long packed : data.getLongArray(POSITIONS)) {
                BlockPos pos = BlockPos.of(packed);
                if (pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ) return true;
            }
        }
        return false;
    }

    public static void release(CompoundTag data) {
        data.putBoolean(ACTIVE, false);
        data.remove(KIND);
        data.remove(POSITIONS);
    }

    /** Release a claim only when the caller still owns it for the expected task type. */
    public static void release(CompoundTag data, String expectedKind) {
        if (data == null || !data.getBoolean(ACTIVE)
            || !data.getString(KIND).equals(expectedKind)) return;
        release(data);
    }

    private static Set<Long> normalizeStoragePositions(ServerLevel level, List<BlockPos> requested) {
        Set<Long> positions = new LinkedHashSet<>();
        for (BlockPos pos : requested) {
            if (pos == null || !level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))) continue;
            positions.add(pos.asLong());
            for (Direction direction : HORIZONTAL) {
                BlockPos adjacent = pos.relative(direction);
                if (level.hasChunkAt(adjacent) && isStorage(level.getBlockState(adjacent))) {
                    positions.add(adjacent.asLong());
                }
            }
        }
        return positions;
    }

    private static Set<Long> readPositions(CompoundTag data) {
        Set<Long> positions = new LinkedHashSet<>();
        for (long packed : data.getLongArray(POSITIONS)) positions.add(packed);
        return positions;
    }

    private static void save(CompoundTag data, Set<Long> positions, String kind) {
        long[] packed = positions.stream().mapToLong(Long::longValue).toArray();
        data.putBoolean(ACTIVE, true);
        data.putString(KIND, kind == null ? "unspecified" : kind);
        data.putLongArray(POSITIONS, packed);
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }
}
