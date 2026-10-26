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
import java.util.Comparator;
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
        if (owner == null || !owner.isAlive() || requirements == null || requirements.isEmpty()) return false;
        if (hasLiveReservation(level, owner, owner.getPersistentData())) return false;
        int radius = Math.max(0, Math.min(requestedRadius, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get()));
        Map<Item, Integer> available = new HashMap<>();
        List<StorageStock> storages = new ArrayList<>();

        for (Map.Entry<Item, Integer> requirement : requirements.entrySet()) {
            if (requirement.getValue() <= 0) return false;
        }
        for (BlockPos pos : BlockPos.betweenClosed(
            center.offset(-radius, -6, -radius), center.offset(radius, 6, radius))) {
            if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                || !VillageRegistry.isStorageForVillage(level, center, pos)
                || !isStorageAvailable(level, pos, owner)) continue;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) continue;

            Map<Item, Integer> stock = new HashMap<>();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !requirements.containsKey(stack.getItem())) continue;
                stock.merge(stack.getItem(), stack.getCount(), Integer::sum);
                available.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
            if (!stock.isEmpty()) storages.add(new StorageStock(pos.immutable(), stock));
        }

        for (Map.Entry<Item, Integer> requirement : requirements.entrySet()) {
            if (available.getOrDefault(requirement.getKey(), 0) < requirement.getValue()) return false;
        }

        // Reserve nearest matching containers only until the complete bill is covered.
        storages.sort(Comparator.comparingDouble(stock -> stock.pos().distSqr(center)));
        Map<Item, Integer> remaining = new HashMap<>(requirements);
        List<BlockPos> selected = new ArrayList<>();
        for (StorageStock storage : storages) {
            boolean contributes = false;
            for (Item item : requirements.keySet()) {
                int need = remaining.getOrDefault(item, 0);
                int amount = Math.min(need, storage.items().getOrDefault(item, 0));
                if (amount <= 0) continue;
                remaining.put(item, need - amount);
                contributes = true;
            }
            if (contributes) selected.add(storage.pos());
            if (remaining.values().stream().allMatch(amount -> amount <= 0)) break;
        }
        if (selected.isEmpty() || remaining.values().stream().anyMatch(amount -> amount > 0)) return false;
        return tryReserveStoragePositions(level, owner, selected, kind);
    }

    public static boolean tryReserveStoragePositions(ServerLevel level, Villager owner,
                                                     List<BlockPos> requested, String kind) {
        if (owner == null || !owner.isAlive() || requested == null || requested.isEmpty()) return false;
        CompoundTag data = owner.getPersistentData();
        if (hasLiveReservation(level, owner, data)) return false;

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
        boolean hasLiveClaim = hasLiveReservation(level, owner, data);
        if (hasLiveClaim && !data.getString(KIND).equals(kind)) return false;
        Set<Long> positions = readPositions(data);
        if (hasLiveClaim && positions.contains(storage.asLong())) return true;

        Set<Long> additions = normalizeStoragePositions(level, List.of(storage));
        if (additions.isEmpty()) return false;
        for (long packed : additions) {
            if (!isStorageAvailable(level, BlockPos.of(packed), owner)) return false;
        }
        positions.addAll(additions);
        save(data, positions, kind);
        return true;
    }

    /**
     * Prevents a house or farm from starting within the footprint/safety margin of an active
     * project, even when that project belongs to a different HOME-anchored resident group.
     */
    public static boolean isProjectSiteAvailable(ServerLevel level, BlockPos site,
                                                  int minimumSeparationBlocks, Villager owner) {
        if (site == null) return false;
        int separation = Math.max(1, minimumSeparationBlocks);
        AABB search = new AABB(site).inflate(LOOKUP_RADIUS, 32.0D, LOOKUP_RADIUS);
        for (Villager candidate : level.getEntitiesOfClass(Villager.class, search, Villager::isAlive)) {
            if (candidate == owner) continue;
            BlockPos houseSite = VillageBuilder.activeProjectSite(candidate);
            if (sitesConflict(site, houseSite, separation)) return false;
            BlockPos farmSite = VillageFarmBuilder.activeProjectSite(candidate);
            if (sitesConflict(site, farmSite, separation)) return false;
        }
        return true;
    }

    private static boolean sitesConflict(BlockPos first, BlockPos second, int separation) {
        if (second == null || Math.abs(first.getY() - second.getY()) > 8) return false;
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        long minimumSquared = (long) separation * separation;
        return dx * dx + dz * dz < minimumSquared;
    }

    public static boolean isStorageAvailable(ServerLevel level, BlockPos storage, Villager owner) {
        if (storage == null) return false;
        if (owner != null) {
            BlockPos groupCenter = VillagerRoles.getHome(owner);
            if (groupCenter == null) groupCenter = owner.blockPosition();
            if (!VillageRegistry.isStorageForVillage(level, groupCenter, storage)) return false;
        }
        AABB search = new AABB(storage).inflate(LOOKUP_RADIUS, 24.0D, LOOKUP_RADIUS);
        for (Villager candidate : level.getEntitiesOfClass(Villager.class, search, Villager::isAlive)) {
            if (candidate == owner) continue;
            CompoundTag data = candidate.getPersistentData();
            if (!hasLiveReservation(level, candidate, data)) continue;
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
            if (!hasLiveReservation(level, candidate, data)) continue;
            for (long packed : data.getLongArray(POSITIONS)) {
                BlockPos pos = BlockPos.of(packed);
                // A broken or unloaded chest is not an accessible reservation target and must not
                // stall unrelated background production while a task looks for a replacement.
                if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))) continue;
                if (pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ) return true;
            }
        }
        return false;
    }

    /**
     * Reconciles a persisted claim against its owning task. If a task was reset by another
     * subsystem or a reserved chest was replaced, stale claims are removed automatically.
     * Unloaded chunks are retained because their block state cannot be safely inspected.
     */
    private static boolean hasLiveReservation(ServerLevel level, Villager owner, CompoundTag data) {
        if (!data.getBoolean(ACTIVE)) {
            if (!data.getString(KIND).isBlank() || data.getLongArray(POSITIONS).length > 0) release(data);
            return false;
        }

        boolean taskActive = switch (data.getString(KIND)) {
            case "house" -> data.getBoolean(VillageBuilder.ACTIVE_KEY);
            case "farm" -> data.getBoolean(VillageFarmBuilder.ACTIVE_KEY);
            case "crafting" -> data.getBoolean(VillageCraftingJobs.ACTIVE_KEY);
            case "food_delivery" -> data.getBoolean(VillagePopulation.ACTIVE_KEY);
            case "logistics_delivery" -> data.getBoolean("livingvillages_logistics_active");
            case "food_stockpiling" -> data.getBoolean("livingvillages_autonomy_target_active");
            default -> false;
        };
        if (!taskActive) {
            release(data);
            return false;
        }

        long[] claimed = data.getLongArray(POSITIONS);
        List<Long> valid = new ArrayList<>(claimed.length);
        for (long packed : claimed) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.hasChunkAt(pos) || isStorage(level.getBlockState(pos))) valid.add(packed);
        }
        if (valid.isEmpty()) {
            release(data);
            return false;
        }
        if (valid.size() != claimed.length) {
            data.putLongArray(POSITIONS, valid.stream().mapToLong(Long::longValue).toArray());
        }
        return true;
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

    private record StorageStock(BlockPos pos, Map<Item, Integer> items) {}
}
