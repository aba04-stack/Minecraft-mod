package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Persistent village identity and ownership of physical infrastructure.
 *
 * This per-dimension SavedData registry matches identities through resident HOME anchors and stores
 * ownership of physical containers. A container is claimed only when the selected resident group
 * overlaps the group selected around that asset, so a broad radius scan alone cannot annex a
 * neighboring village's stock.
 *
 * Anchor matching is a best-effort incremental registry, not a full census of unloaded villagers.
 * Split/merge behavior still requires validation in a real client world.
 */
public final class VillageRegistry extends SavedData {
    private static final String DATA_NAME = "livingvillages_village_registry";
    private static final Factory<VillageRegistry> FACTORY =
        new Factory<>(VillageRegistry::new, VillageRegistry::load, null);
    private static final int ID_MATCH_RADIUS_BLOCKS = 20;
    private static final int ID_MATCH_RADIUS_SQUARED = ID_MATCH_RADIUS_BLOCKS * ID_MATCH_RADIUS_BLOCKS;
    private static final Map<ServerLevel, IdentityCache> ID_CACHE = new WeakHashMap<>();

    private final Map<UUID, BlockPos> villageAnchors = new HashMap<>();
    private final Map<Long, UUID> storageOwners = new HashMap<>();
    private final Map<Long, UUID> bedOwners = new HashMap<>();
    private final Map<Long, UUID> workBlockOwners = new HashMap<>();

    private VillageRegistry() {}

    public static VillageRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** A query center is evaluated at most once per game tick. */
    public static UUID villageId(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return null;
        long now = level.getGameTime();
        IdentityCache cache = ID_CACHE.get(level);
        if (cache == null || cache.gameTime != now) {
            cache = new IdentityCache(now);
            ID_CACHE.put(level, cache);
        }
        long centerKey = center.asLong();
        CachedId cached = cache.ids.get(centerKey);
        if (cached != null) return cached.id();

        List<Villager> residents = VillageArea.findVillagers(level, center,
            LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get(), 32);
        UUID result = residents.isEmpty() ? null : resolveOrCreateId(level, center, residents);
        cache.ids.put(centerKey, new CachedId(result));
        return result;
    }

    private static UUID resolveOrCreateId(ServerLevel level, BlockPos center, List<Villager> residents) {
        List<BlockPos> homes = new ArrayList<>();
        for (Villager villager : residents) {
            GlobalPos home = villager.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
            if (home != null && home.dimension().equals(level.dimension())) {
                BlockPos pos = home.pos().immutable();
                if (!homes.contains(pos)) homes.add(pos);
            }
        }
        homes.sort(Comparator.<BlockPos>comparingInt(pos -> pos.getX())
            .thenComparingInt(pos -> pos.getZ()).thenComparingInt(pos -> pos.getY()));
        BlockPos candidateAnchor = homes.isEmpty()
            ? residents.stream().min(Comparator.comparing(v -> v.getUUID().toString()))
                .map(v -> v.blockPosition().immutable()).orElse(center.immutable())
            : homes.get(0);

        VillageRegistry registry = get(level);
        UUID bestId = null;
        long bestDistance = Long.MAX_VALUE;
        List<BlockPos> compareAnchors = homes.isEmpty() ? List.of(candidateAnchor) : homes;
        for (Map.Entry<UUID, BlockPos> entry : registry.villageAnchors.entrySet()) {
            long distance = Long.MAX_VALUE;
            for (BlockPos home : compareAnchors) {
                distance = Math.min(distance, horizontalDistanceSquared(home, entry.getValue()));
            }
            if (distance <= ID_MATCH_RADIUS_SQUARED && distance < bestDistance) {
                bestDistance = distance;
                bestId = entry.getKey();
            }
        }
        if (bestId != null) return bestId;

        UUID created = UUID.randomUUID();
        registry.villageAnchors.put(created, candidateAnchor);
        registry.setDirty();
        return created;
    }

    /** Claim a physical storage block for the persistent village selected around groupCenter. */
    public static boolean isStorageForVillage(ServerLevel level, BlockPos groupCenter, BlockPos storage) {
        if (storage == null || !level.hasChunkAt(storage)) return false;
        VillageRegistry registry = get(level);
        if (!isStorage(level.getBlockState(storage))) {
            removeStaleOwner(registry, storage, registry.storageOwners);
            return false;
        }
        return claimAsset(level, groupCenter, storage, registry.storageOwners);
    }

    /** Bed-foot ownership API for housing and population checks. */
    public static boolean isBedForVillage(ServerLevel level, BlockPos groupCenter, BlockPos bedFoot) {
        if (bedFoot == null || !level.hasChunkAt(bedFoot)) return false;
        VillageRegistry registry = get(level);
        if (!(level.getBlockState(bedFoot).getBlock() instanceof BedBlock)
            || level.getBlockState(bedFoot).getValue(BedBlock.PART) != net.minecraft.world.level.block.state.properties.BedPart.FOOT) {
            removeStaleOwner(registry, bedFoot, registry.bedOwners);
            return false;
        }
        return claimAsset(level, groupCenter, bedFoot, registry.bedOwners);
    }

    /** Workstation ownership API for production and crafting scans. */
    public static boolean isWorkBlockForVillage(ServerLevel level, BlockPos groupCenter, BlockPos workBlock) {
        if (workBlock == null || !level.hasChunkAt(workBlock)) return false;
        VillageRegistry registry = get(level);
        if (!isWorkBlock(level.getBlockState(workBlock))) {
            removeStaleOwner(registry, workBlock, registry.workBlockOwners);
            return false;
        }
        return claimAsset(level, groupCenter, workBlock, registry.workBlockOwners);
    }

    private static boolean claimAsset(ServerLevel level, BlockPos groupCenter, BlockPos location,
                                      Map<Long, UUID> ownership) {
        UUID villageId = villageId(level, groupCenter);
        if (villageId == null) return false;
        VillageRegistry registry = get(level);
        long key = location.asLong();
        UUID existing = ownership.get(key);
        if (existing != null) return existing.equals(villageId);
        if (!VillageArea.isBlockInResidentGroup(level, groupCenter, location)) return false;
        ownership.put(key, villageId);
        registry.setDirty();
        return true;
    }

    private static void removeStaleOwner(VillageRegistry registry, BlockPos location, Map<Long, UUID> ownership) {
        if (ownership.remove(location.asLong()) != null) registry.setDirty();
    }

    /**
     * Lazily clear old asset claims in loaded chunks around an active village.
     * Unloaded assets are left alone because their block state cannot be checked safely.
     */
    public static void cleanupInvalidAssets(ServerLevel level, BlockPos center, int radius) {
        if (level == null || center == null) return;
        VillageRegistry registry = get(level);
        int horizontal = Math.max(0, radius);
        int minX = center.getX() - horizontal, maxX = center.getX() + horizontal;
        int minY = center.getY() - 8, maxY = center.getY() + 8;
        int minZ = center.getZ() - horizontal, maxZ = center.getZ() + horizontal;
        boolean changed = removeInvalidOwners(level, registry.storageOwners, minX, maxX, minY, maxY, minZ, maxZ,
            VillageRegistry::isStorage);
        changed |= removeInvalidOwners(level, registry.bedOwners, minX, maxX, minY, maxY, minZ, maxZ,
            state -> state.getBlock() instanceof BedBlock
                && state.getValue(BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT);
        changed |= removeInvalidOwners(level, registry.workBlockOwners, minX, maxX, minY, maxY, minZ, maxZ,
            VillageRegistry::isWorkBlock);
        if (changed) registry.setDirty();
    }

    private static boolean removeInvalidOwners(ServerLevel level, Map<Long, UUID> ownership,
                                               int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                               java.util.function.Predicate<BlockState> validState) {
        boolean changed = false;
        for (Long key : new ArrayList<>(ownership.keySet())) {
            BlockPos pos = BlockPos.of(key);
            if (pos.getX() < minX || pos.getX() > maxX || pos.getY() < minY || pos.getY() > maxY
                || pos.getZ() < minZ || pos.getZ() > maxZ || !level.hasChunkAt(pos)) continue;
            if (!validState.test(level.getBlockState(pos))) {
                ownership.remove(key);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    public static boolean isWorkBlockState(BlockState state) {
        return state != null && isWorkBlock(state);
    }

    private static boolean isWorkBlock(BlockState state) {
        return state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE)
            || state.is(Blocks.SMOKER) || state.is(Blocks.COMPOSTER) || state.is(Blocks.LOOM)
            || state.is(Blocks.LECTERN) || state.is(Blocks.SMITHING_TABLE) || state.is(Blocks.GRINDSTONE)
            || state.is(Blocks.STONECUTTER) || state.is(Blocks.CARTOGRAPHY_TABLE)
            || state.is(Blocks.FLETCHING_TABLE) || state.is(Blocks.BREWING_STAND);
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static VillageRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageRegistry data = new VillageRegistry();
        ListTag villageTags = tag.getList("villageAnchors", Tag.TAG_COMPOUND);
        for (int i = 0; i < villageTags.size(); i++) {
            CompoundTag entry = villageTags.getCompound(i);
            UUID id = parseUuid(entry.getString("id"));
            if (id != null) data.villageAnchors.put(id, BlockPos.of(entry.getLong("anchor")));
        }
        data.storageOwners.putAll(readOwners(tag.getList("storageOwners", Tag.TAG_COMPOUND)));
        data.bedOwners.putAll(readOwners(tag.getList("bedOwners", Tag.TAG_COMPOUND)));
        data.workBlockOwners.putAll(readOwners(tag.getList("workBlockOwners", Tag.TAG_COMPOUND)));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag villageTags = new ListTag();
        for (Map.Entry<UUID, BlockPos> entry : villageAnchors.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("id", entry.getKey().toString());
            value.putLong("anchor", entry.getValue().asLong());
            villageTags.add(value);
        }
        tag.put("villageAnchors", villageTags);
        tag.put("storageOwners", writeOwners(storageOwners));
        tag.put("bedOwners", writeOwners(bedOwners));
        tag.put("workBlockOwners", writeOwners(workBlockOwners));
        return tag;
    }

    private static ListTag writeOwners(Map<Long, UUID> owners) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, UUID> entry : owners.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putLong("pos", entry.getKey());
            value.putString("village", entry.getValue().toString());
            list.add(value);
        }
        return list;
    }

    private static Map<Long, UUID> readOwners(ListTag list) {
        Map<Long, UUID> owners = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID id = parseUuid(entry.getString("village"));
            if (id != null) owners.put(entry.getLong("pos"), id);
        }
        return owners;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class IdentityCache {
        private final long gameTime;
        private final Map<Long, CachedId> ids = new HashMap<>();
        private IdentityCache(long gameTime) { this.gameTime = gameTime; }
    }

    private record CachedId(UUID id) {}
}
