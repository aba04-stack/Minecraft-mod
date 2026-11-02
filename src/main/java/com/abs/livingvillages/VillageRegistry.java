package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public static final int SCHEMA_VERSION = 1;
    private static final String SCHEMA_VERSION_KEY = "schema_version";
    private static final String DATA_NAME = "livingvillages_village_registry";
    private static final Factory<VillageRegistry> FACTORY =
        new Factory<>(VillageRegistry::new, VillageRegistry::load, null);
    private static final int ID_MATCH_RADIUS_BLOCKS = 20;
    private static final int ID_MATCH_RADIUS_SQUARED = ID_MATCH_RADIUS_BLOCKS * ID_MATCH_RADIUS_BLOCKS;
    private static final Map<ServerLevel, IdentityCache> ID_CACHE = new WeakHashMap<>();

    private final Map<UUID, BlockPos> villageAnchors = new HashMap<>();
    /** All observed HOME anchors are aliases; keep the primary anchor for old-save compatibility. */
    private final Map<UUID, Set<Long>> knownHomes = new HashMap<>();
    /** Resident UUIDs provide a fallback when all known HOME anchors move or disappear. */
    private final Map<UUID, Set<UUID>> knownResidents = new HashMap<>();
    private final Map<Long, UUID> storageOwners = new HashMap<>();
    private final Map<Long, UUID> bedOwners = new HashMap<>();
    private final Map<Long, UUID> workBlockOwners = new HashMap<>();
    private final Map<UUID, com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint> houseCheckpoints = new HashMap<>();
    private final Map<UUID, com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint> farmCheckpoints = new HashMap<>();
    private final Map<UUID, Long> villageAlarms = new HashMap<>();
    private final Map<String, Integer> diplomaticStandings = new HashMap<>();
    private final Map<UUID, Boolean> guardEquipmentRequests = new HashMap<>();
    private final Map<UUID, Long> villageLastVisitedTick = new HashMap<>();
    private final Map<UUID, Map<String, String>> villageDiagnostics = new HashMap<>();

    private VillageRegistry() {}

    public static VillageRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Return a resident's prior village only when the SavedData history is unambiguous. */
    static UUID knownVillageIdForResident(ServerLevel level, UUID residentId) {
        if (level == null || residentId == null) return null;
        VillageRegistry registry = get(level);
        UUID found = null;
        for (Map.Entry<UUID, Set<UUID>> entry : registry.knownResidents.entrySet()) {
            if (!entry.getValue().contains(residentId)) continue;
            if (found != null && !found.equals(entry.getKey())) return null;
            found = entry.getKey();
        }
        return found;
    }

    /** Resolve a current HOME position from persisted aliases without recursively selecting a group. */
    static UUID knownVillageIdForHomeAnchor(ServerLevel level, BlockPos home) {
        if (level == null || home == null) return null;
        VillageRegistry registry = get(level);
        UUID found = null;
        long key = home.asLong();
        for (Map.Entry<UUID, Set<Long>> entry : registry.knownHomes.entrySet()) {
            if (!entry.getValue().contains(key)) continue;
            if (found != null && !found.equals(entry.getKey())) return null;
            found = entry.getKey();
        }
        for (Map.Entry<UUID, BlockPos> entry : registry.villageAnchors.entrySet()) {
            if (!entry.getValue().equals(home)) continue;
            if (found != null && !found.equals(entry.getKey())) return null;
            found = entry.getKey();
        }
        return found;
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
        Set<UUID> currentResidentIds = new HashSet<>();
        for (Villager villager : residents) {
            currentResidentIds.add(villager.getUUID());
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

        // HOME anchors remain the strongest identity signal when the current resident group
        // actually has HOME memories. Never substitute a current position for a missing HOME:
        // after relocation, that position may be nearer to a different village's saved anchor.
        if (!homes.isEmpty()) {
            for (Map.Entry<UUID, BlockPos> entry : registry.villageAnchors.entrySet()) {
                long distance = Long.MAX_VALUE;
                for (BlockPos home : homes) {
                    distance = Math.min(distance, horizontalDistanceSquared(home, entry.getValue()));
                    for (long alias : registry.knownHomes.getOrDefault(entry.getKey(), Set.of())) {
                        distance = Math.min(distance, horizontalDistanceSquared(home, BlockPos.of(alias)));
                    }
                }
                if (distance <= ID_MATCH_RADIUS_SQUARED
                    && (distance < bestDistance || (distance == bestDistance && bestId != null
                        && entry.getKey().toString().compareTo(bestId.toString()) < 0))) {
                    bestDistance = distance;
                    bestId = entry.getKey();
                }
            }
        }

        // When HOME is absent, prior resident UUID membership comes first. Otherwise proximity
        // to a neighbor's old home could overwrite a valid identity during relocation.
        if (bestId == null) {
            // Avoid adopting a different village's identity because just one wandering resident
            // happens to be inside the query area. HOME anchors already match first; the UUID
            // fallback therefore requires a strict majority of the selected residents.
            int minimumOverlap = currentResidentIds.isEmpty()
                ? Integer.MAX_VALUE : currentResidentIds.size() / 2 + 1;
            int bestOverlap = 0;
            for (Map.Entry<UUID, Set<UUID>> entry : registry.knownResidents.entrySet()) {
                int overlap = 0;
                for (UUID residentId : currentResidentIds) {
                    if (entry.getValue().contains(residentId)) overlap++;
                }
                if (overlap < minimumOverlap) continue;
                if (overlap > bestOverlap || (overlap == bestOverlap && overlap > 0 && bestId != null
                    && entry.getKey().toString().compareTo(bestId.toString()) < 0)) {
                    bestOverlap = overlap;
                    bestId = entry.getKey();
                }
            }
        }

        // Very old registries may not yet have any resident UUID history. Keep a spatial
        // migration fallback only for that legacy shape; otherwise a new HOME-less group must not
        // silently adopt a known neighboring village's identity.
        if (bestId == null && homes.isEmpty() && registry.knownResidents.isEmpty()) {
            for (Map.Entry<UUID, BlockPos> entry : registry.villageAnchors.entrySet()) {
                long distance = horizontalDistanceSquared(candidateAnchor, entry.getValue());
                for (long alias : registry.knownHomes.getOrDefault(entry.getKey(), Set.of())) {
                    distance = Math.min(distance, horizontalDistanceSquared(candidateAnchor, BlockPos.of(alias)));
                }
                if (distance <= ID_MATCH_RADIUS_SQUARED
                    && (distance < bestDistance || (distance == bestDistance && bestId != null
                        && entry.getKey().toString().compareTo(bestId.toString()) < 0))) {
                    bestDistance = distance;
                    bestId = entry.getKey();
                }
            }
        }

        boolean changed = false;
        if (bestId == null) {
            bestId = UUID.randomUUID();
            registry.villageAnchors.put(bestId, candidateAnchor);
            changed = true;
        } else if (!registry.villageAnchors.containsKey(bestId)) {
            registry.villageAnchors.put(bestId, candidateAnchor);
            changed = true;
        }

        Set<Long> savedHomes = registry.knownHomes.computeIfAbsent(bestId, ignored -> new HashSet<>());
        for (BlockPos home : homes) {
            if (savedHomes.add(home.asLong())) changed = true;
        }
        Set<UUID> savedResidents = registry.knownResidents.computeIfAbsent(bestId, ignored -> new HashSet<>());
        for (UUID residentId : currentResidentIds) {
            if (savedResidents.add(residentId)) changed = true;
        }
        if (changed) registry.setDirty();
        return bestId;
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

    static VillageRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageRegistry data = new VillageRegistry();
        ListTag villageTags = tag.getList("villageAnchors", Tag.TAG_COMPOUND);
        for (int i = 0; i < villageTags.size(); i++) {
            CompoundTag entry = villageTags.getCompound(i);
            UUID id = parseUuid(entry.getString("id"));
            if (id != null) data.villageAnchors.put(id, BlockPos.of(entry.getLong("anchor")));
        }
        ListTag knownHomeTags = tag.getList("knownHomes", Tag.TAG_COMPOUND);
        for (int i = 0; i < knownHomeTags.size(); i++) {
            CompoundTag entry = knownHomeTags.getCompound(i);
            UUID id = parseUuid(entry.getString("id"));
            if (id == null) continue;
            Set<Long> positions = data.knownHomes.computeIfAbsent(id, ignored -> new HashSet<>());
            for (long position : entry.getLongArray("positions")) positions.add(position);
        }
        // Older saves have no alias record for a village, so promote the legacy primary anchor.
        // A current save may legitimately contain an empty alias set when that group had no HOME memory.
        for (Map.Entry<UUID, BlockPos> entry : data.villageAnchors.entrySet()) {
            if (!data.knownHomes.containsKey(entry.getKey())) {
                data.knownHomes.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>())
                    .add(entry.getValue().asLong());
            }
        }
        ListTag knownResidentTags = tag.getList("knownResidents", Tag.TAG_COMPOUND);
        for (int i = 0; i < knownResidentTags.size(); i++) {
            CompoundTag entry = knownResidentTags.getCompound(i);
            UUID id = parseUuid(entry.getString("id"));
            if (id == null) continue;
            Set<UUID> members = data.knownResidents.computeIfAbsent(id, ignored -> new HashSet<>());
            ListTag memberTags = entry.getList("members", Tag.TAG_STRING);
            for (int j = 0; j < memberTags.size(); j++) {
                UUID resident = parseUuid(memberTags.getString(j));
                if (resident != null) members.add(resident);
            }
        }
        data.storageOwners.putAll(readOwners(tag.getList("storageOwners", Tag.TAG_COMPOUND)));
        data.bedOwners.putAll(readOwners(tag.getList("bedOwners", Tag.TAG_COMPOUND)));
        data.workBlockOwners.putAll(readOwners(tag.getList("workBlockOwners", Tag.TAG_COMPOUND)));
        ListTag checkpointTags = tag.getList("houseCheckpoints", Tag.TAG_COMPOUND);
        for (int i = 0; i < checkpointTags.size(); i++) {
            CompoundTag entry = checkpointTags.getCompound(i);
            UUID id = parseUuid(entry.getString("village"));
            if (id == null) continue;
            data.houseCheckpoints.put(id, new com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint(
                entry.getString("kind"),
                entry.getInt("siteX"),
                entry.getInt("siteY"),
                entry.getInt("siteZ"),
                entry.getString("primaryMaterial"),
                entry.getString("secondaryMaterial"),
                entry.getInt("stepIndex"),
                entry.getInt("totalSteps"),
                entry.getString("ownerWorker"),
                entry.getLong("updatedTick")
            ));
        }
        ListTag farmCheckpointTags = tag.getList("farmCheckpoints", Tag.TAG_COMPOUND);
        for (int i = 0; i < farmCheckpointTags.size(); i++) {
            CompoundTag entry = farmCheckpointTags.getCompound(i);
            UUID id = parseUuid(entry.getString("village"));
            if (id == null) continue;
            data.farmCheckpoints.put(id, new com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint(
                entry.getString("kind"),
                entry.getInt("siteX"),
                entry.getInt("siteY"),
                entry.getInt("siteZ"),
                entry.getString("primaryMaterial"),
                entry.getString("secondaryMaterial"),
                entry.getInt("stepIndex"),
                entry.getInt("totalSteps"),
                entry.getString("ownerWorker"),
                entry.getLong("updatedTick")
            ));
        }
        ListTag standingTags = tag.getList("diplomaticStandings", Tag.TAG_COMPOUND);
        for (int i = 0; i < standingTags.size(); i++) {
            CompoundTag entry = standingTags.getCompound(i);
            String pair = entry.getString("pair");
            if (!pair.isBlank()) {
                data.diplomaticStandings.put(pair, entry.getInt("standing"));
            }
        }
        ListTag visitedTags = tag.getList("villageLastVisited", Tag.TAG_COMPOUND);
        for (int i = 0; i < visitedTags.size(); i++) {
            CompoundTag entry = visitedTags.getCompound(i);
            UUID id = parseUuid(entry.getString("village"));
            if (id != null) {
                data.villageLastVisitedTick.put(id, entry.getLong("tick"));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION);
        ListTag villageTags = new ListTag();
        for (Map.Entry<UUID, BlockPos> entry : villageAnchors.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("id", entry.getKey().toString());
            value.putLong("anchor", entry.getValue().asLong());
            villageTags.add(value);
        }
        tag.put("villageAnchors", villageTags);

        ListTag knownHomeTags = new ListTag();
        for (Map.Entry<UUID, Set<Long>> entry : knownHomes.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("id", entry.getKey().toString());
            value.putLongArray("positions", entry.getValue().stream().mapToLong(Long::longValue).sorted().toArray());
            knownHomeTags.add(value);
        }
        tag.put("knownHomes", knownHomeTags);

        ListTag knownResidentTags = new ListTag();
        for (Map.Entry<UUID, Set<UUID>> entry : knownResidents.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("id", entry.getKey().toString());
            ListTag memberTags = new ListTag();
            entry.getValue().stream().sorted(Comparator.comparing(UUID::toString))
                .forEach(resident -> memberTags.add(StringTag.valueOf(resident.toString())));
            value.put("members", memberTags);
            knownResidentTags.add(value);
        }
        tag.put("knownResidents", knownResidentTags);
        tag.put("storageOwners", writeOwners(storageOwners));
        tag.put("bedOwners", writeOwners(bedOwners));
        tag.put("workBlockOwners", writeOwners(workBlockOwners));
        ListTag checkpointTags = new ListTag();
        for (Map.Entry<UUID, com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint> entry : houseCheckpoints.entrySet()) {
            var cp = entry.getValue();
            if (cp == null) continue;
            CompoundTag value = new CompoundTag();
            value.putString("village", entry.getKey().toString());
            value.putString("kind", cp.projectKind());
            value.putInt("siteX", cp.siteX());
            value.putInt("siteY", cp.siteY());
            value.putInt("siteZ", cp.siteZ());
            value.putString("primaryMaterial", cp.primaryMaterialId());
            value.putString("secondaryMaterial", cp.secondaryMaterialId());
            value.putInt("stepIndex", cp.stepIndex());
            value.putInt("totalSteps", cp.totalSteps());
            value.putString("ownerWorker", cp.ownerWorkerId());
            value.putLong("updatedTick", cp.updatedTick());
            checkpointTags.add(value);
        }
        tag.put("houseCheckpoints", checkpointTags);
        ListTag farmCheckpointTags = new ListTag();
        for (Map.Entry<UUID, com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint> entry : farmCheckpoints.entrySet()) {
            var cp = entry.getValue();
            if (cp == null) continue;
            CompoundTag value = new CompoundTag();
            value.putString("village", entry.getKey().toString());
            value.putString("kind", cp.projectKind());
            value.putInt("siteX", cp.siteX());
            value.putInt("siteY", cp.siteY());
            value.putInt("siteZ", cp.siteZ());
            value.putString("primaryMaterial", cp.primaryMaterialId());
            value.putString("secondaryMaterial", cp.secondaryMaterialId());
            value.putInt("stepIndex", cp.stepIndex());
            value.putInt("totalSteps", cp.totalSteps());
            value.putString("ownerWorker", cp.ownerWorkerId());
            value.putLong("updatedTick", cp.updatedTick());
            farmCheckpointTags.add(value);
        }
        tag.put("farmCheckpoints", farmCheckpointTags);
        ListTag standingTags = new ListTag();
        for (Map.Entry<String, Integer> entry : diplomaticStandings.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("pair", entry.getKey());
            value.putInt("standing", entry.getValue());
            standingTags.add(value);
        }
        tag.put("diplomaticStandings", standingTags);
        ListTag visitedTags = new ListTag();
        for (Map.Entry<UUID, Long> entry : villageLastVisitedTick.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("village", entry.getKey().toString());
            value.putLong("tick", entry.getValue());
            visitedTags.add(value);
        }
        tag.put("villageLastVisited", visitedTags);
        return tag;
    }

    public static void recordHouseCheckpoint(
        ServerLevel level,
        BlockPos center,
        com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint checkpoint
    ) {
        if (level == null || center == null || checkpoint == null) return;
        UUID id = villageId(level, center);
        if (id == null) return;
        VillageRegistry registry = get(level);
        registry.houseCheckpoints.put(id, checkpoint);
        registry.setDirty();
    }

    public static void clearHouseCheckpoint(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return;
        UUID id = villageId(level, center);
        if (id == null) return;
        VillageRegistry registry = get(level);
        if (registry.houseCheckpoints.remove(id) != null) {
            registry.setDirty();
        }
    }

    public static com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint getHouseCheckpoint(
        ServerLevel level,
        BlockPos center
    ) {
        if (level == null || center == null) return null;
        UUID id = villageId(level, center);
        if (id == null) return null;
        return get(level).houseCheckpoints.get(id);
    }

    public static void recordFarmCheckpoint(
        ServerLevel level,
        BlockPos center,
        com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint checkpoint
    ) {
        if (level == null || center == null || checkpoint == null) return;
        UUID id = villageId(level, center);
        if (id == null) return;
        VillageRegistry registry = get(level);
        registry.farmCheckpoints.put(id, checkpoint);
        registry.setDirty();
    }

    public static void clearFarmCheckpoint(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return;
        UUID id = villageId(level, center);
        if (id == null) return;
        VillageRegistry registry = get(level);
        if (registry.farmCheckpoints.remove(id) != null) {
            registry.setDirty();
        }
    }

    public static com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint getFarmCheckpoint(
        ServerLevel level,
        BlockPos center
    ) {
        if (level == null || center == null) return null;
        UUID id = villageId(level, center);
        if (id == null) return null;
        return get(level).farmCheckpoints.get(id);
    }

    public static void raiseVillageAlarm(ServerLevel level, BlockPos center, long gameTime) {
        if (level == null || center == null) return;
        UUID id = villageId(level, center);
        if (id == null) return;
        get(level).villageAlarms.put(id, gameTime);
    }

    public static boolean isVillageAlarmActive(ServerLevel level, BlockPos center, long gameTime) {
        if (level == null || center == null) return false;
        UUID id = villageId(level, center);
        if (id == null) return false;
        Long raised = get(level).villageAlarms.get(id);
        return raised != null && com.abs.livingvillages.domain.SettlementSimulationModel.isAlarmActive(raised, gameTime);
    }

    private static String pairKey(UUID a, UUID b) {
        String sa = a.toString();
        String sb = b.toString();
        return sa.compareTo(sb) <= 0 ? sa + "|" + sb : sb + "|" + sa;
    }

    public static int getDiplomaticStanding(ServerLevel level, BlockPos centerA, BlockPos centerB) {
        if (level == null || centerA == null || centerB == null) return 0;
        UUID idA = villageId(level, centerA);
        UUID idB = villageId(level, centerB);
        if (idA == null || idB == null || idA.equals(idB)) return 0;
        return get(level).diplomaticStandings.getOrDefault(pairKey(idA, idB), 0);
    }

    /**
     * Executes an honest, 1:1 material-conserving surplus trade between two distinct peaceful
     * settlements using real items in unreserved village-owned chests/barrels (Stage 5.3).
     */
    public static boolean executeNeighborTradeIfEligible(
        ServerLevel level,
        BlockPos centerA,
        BlockPos centerB,
        int breadReserveCount,
        int plankReserveCount
    ) {
        if (level == null || centerA == null || centerB == null) return false;
        UUID idA = villageId(level, centerA);
        UUID idB = villageId(level, centerB);
        if (idA == null || idB == null || idA.equals(idB)) return false;

        long now = level.getGameTime();
        boolean alarm = isVillageAlarmActive(level, centerA, now) || isVillageAlarmActive(level, centerB, now);
        Container chestA = findNearestOwnedStorageWithItem(level, centerA, Items.BREAD, breadReserveCount + 1);
        Container chestB = findNearestOwnedStorageWithItem(level, centerB, Items.OAK_PLANKS, plankReserveCount + 1);
        if (chestA == null || chestB == null) return false;

        int breadInA = countItemInContainer(chestA, Items.BREAD);
        int planksInB = countItemInContainer(chestB, Items.OAK_PLANKS);
        int surplusBread = Math.max(0, breadInA - Math.max(0, breadReserveCount));
        int surplusPlanks = Math.max(0, planksInB - Math.max(0, plankReserveCount));

        VillageRegistry registry = get(level);
        String key = pairKey(idA, idB);
        int currentStanding = registry.diplomaticStandings.getOrDefault(key, 0);
        var decision = com.abs.livingvillages.domain.SettlementSimulationModel.evaluateTradeCaravan(
            currentStanding,
            alarm,
            surplusBread,
            surplusPlanks
        );
        if (!decision.approved()) return false;

        if (!canFitItemInContainer(chestB, Items.BREAD, decision.foodTransferred())
            || !canFitItemInContainer(chestA, Items.OAK_PLANKS, decision.planksTransferred())) {
            return false;
        }

        int removedBread = removeItemFromContainer(chestA, Items.BREAD, decision.foodTransferred());
        if (removedBread != decision.foodTransferred()) return false;
        int removedPlanks = removeItemFromContainer(chestB, Items.OAK_PLANKS, decision.planksTransferred());
        if (removedPlanks != decision.planksTransferred()) {
            insertItemIntoContainer(chestA, new ItemStack(Items.BREAD, removedBread));
            return false;
        }

        insertItemIntoContainer(chestB, new ItemStack(Items.BREAD, removedBread));
        insertItemIntoContainer(chestA, new ItemStack(Items.OAK_PLANKS, removedPlanks));
        chestA.setChanged();
        chestB.setChanged();

        registry.diplomaticStandings.put(key, decision.updatedStanding());
        registry.setDirty();
        return true;
    }

    /**
     * Automatically checks known loaded neighboring settlements in this dimension and executes
     * honest 1:1 surplus trade caravans when eligible (Stage 5.3 live scheduler hook).
     */
    public static int tryTradeWithKnownNeighbors(
        ServerLevel level,
        BlockPos center,
        int breadReserveCount,
        int plankReserveCount
    ) {
        if (level == null || center == null) return 0;
        UUID selfId = villageId(level, center);
        if (selfId == null) return 0;
        VillageRegistry registry = get(level);
        List<Map.Entry<UUID, BlockPos>> entries = new ArrayList<>(registry.villageAnchors.entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().toString()));
        int completed = 0;
        for (Map.Entry<UUID, BlockPos> entry : entries) {
            if (selfId.equals(entry.getKey())) continue;
            BlockPos neighborAnchor = entry.getValue();
            if (neighborAnchor == null || !level.hasChunkAt(neighborAnchor)) continue;
            if (executeNeighborTradeIfEligible(level, center, neighborAnchor, breadReserveCount, plankReserveCount)
                || executeNeighborTradeIfEligible(level, neighborAnchor, center, breadReserveCount, plankReserveCount)
                || executeBiomeResourceTradeIfEligible(level, center, neighborAnchor, plankReserveCount)
                || executeBiomeResourceTradeIfEligible(level, neighborAnchor, center, plankReserveCount)) {
                completed++;
            }
        }
        return completed;
    }

    /**
     * Exchanges surplus biome-specific construction materials (e.g., Desert Sandstone or Highland
     * Cobblestone for Forest Oak Planks) between two peaceful settlements so treeless villages
     * can acquire wood for doors, beds, and tools.
     */
    public static boolean executeBiomeResourceTradeIfEligible(
        ServerLevel level,
        BlockPos centerStoneOrSand,
        BlockPos centerForest,
        int reserveCount
    ) {
        if (level == null || centerStoneOrSand == null || centerForest == null) return false;
        UUID idA = villageId(level, centerStoneOrSand);
        UUID idB = villageId(level, centerForest);
        if (idA == null || idB == null || idA.equals(idB)) return false;

        long now = level.getGameTime();
        boolean alarm = isVillageAlarmActive(level, centerStoneOrSand, now)
            || isVillageAlarmActive(level, centerForest, now);
        Item stoneOrSandItem = Items.SANDSTONE;
        Container chestA = findNearestOwnedStorageWithItem(level, centerStoneOrSand, Items.SANDSTONE, reserveCount + 4);
        if (chestA == null) {
            stoneOrSandItem = Items.COBBLESTONE;
            chestA = findNearestOwnedStorageWithItem(level, centerStoneOrSand, Items.COBBLESTONE, reserveCount + 4);
        }
        Container chestB = findNearestOwnedStorageWithItem(level, centerForest, Items.OAK_PLANKS, reserveCount + 8);
        if (chestA == null || chestB == null) return false;

        int surplusStone = Math.max(0, countItemInContainer(chestA, stoneOrSandItem) - Math.max(0, reserveCount));
        int surplusPlanks = Math.max(0, countItemInContainer(chestB, Items.OAK_PLANKS) - Math.max(0, reserveCount));

        VillageRegistry registry = get(level);
        String key = pairKey(idA, idB);
        int currentStanding = registry.diplomaticStandings.getOrDefault(key, 0);
        var decision = com.abs.livingvillages.domain.SettlementSimulationModel.evaluateTradeCaravan(
            currentStanding,
            alarm,
            surplusStone,
            surplusPlanks
        );
        if (!decision.approved()) return false;

        if (!canFitItemInContainer(chestB, stoneOrSandItem, decision.foodTransferred())
            || !canFitItemInContainer(chestA, Items.OAK_PLANKS, decision.planksTransferred())) {
            return false;
        }

        int removedStone = removeItemFromContainer(chestA, stoneOrSandItem, decision.foodTransferred());
        if (removedStone != decision.foodTransferred()) return false;
        int removedPlanks = removeItemFromContainer(chestB, Items.OAK_PLANKS, decision.planksTransferred());
        if (removedPlanks != decision.planksTransferred()) {
            insertItemIntoContainer(chestA, new ItemStack(stoneOrSandItem, removedStone));
            return false;
        }

        insertItemIntoContainer(chestB, new ItemStack(stoneOrSandItem, removedStone));
        insertItemIntoContainer(chestA, new ItemStack(Items.OAK_PLANKS, removedPlanks));
        chestA.setChanged();
        chestB.setChanged();

        registry.diplomaticStandings.put(key, decision.updatedStanding());
        registry.setDirty();
        return true;
    }

    public static com.abs.livingvillages.domain.SettlementPorts.WorldAccessPort createWorldAccessPort(ServerLevel level) {
        return new com.abs.livingvillages.domain.SettlementPorts.WorldAccessPort() {
            @Override
            public boolean isPositionLoaded(int x, int y, int z) {
                return level != null && level.hasChunkAt(new BlockPos(x, y, z));
            }

            @Override
            public boolean isVillageAlarmActive(int x, int y, int z, long gameTime) {
                return level != null && VillageRegistry.isVillageAlarmActive(level, new BlockPos(x, y, z), gameTime);
            }

            @Override
            public boolean hasOwnedFurnaceNear(int x, int y, int z) {
                return level != null && VillageRegistry.hasOwnedFurnaceNear(level, new BlockPos(x, y, z));
            }
        };
    }

    public static com.abs.livingvillages.domain.SettlementPorts.StorageAccessPort createStorageAccessPort(ServerLevel level) {
        return new com.abs.livingvillages.domain.SettlementPorts.StorageAccessPort() {
            @Override
            public int countItem(int x, int y, int z, String itemId) {
                if (level == null) return 0;
                Item item = resolvePortItem(itemId);
                if (item == Items.AIR) return 0;
                int total = 0;
                for (Container c : findAvailableOwnedStorages(level, new BlockPos(x, y, z))) {
                    total += countItemInContainer(c, item);
                }
                return total;
            }

            @Override
            public int availableRoomForItem(int x, int y, int z, String itemId) {
                if (level == null) return 0;
                Item item = resolvePortItem(itemId);
                if (item == Items.AIR) return 0;
                int total = 0;
                for (Container c : findAvailableOwnedStorages(level, new BlockPos(x, y, z))) {
                    total += VillageRegistry.availableRoomForItem(c, item);
                }
                return total;
            }

            @Override
            public boolean transferItem(int fromX, int fromY, int fromZ, int toX, int toY, int toZ, String itemId, int count) {
                if (level == null || count <= 0) return false;
                Item item = resolvePortItem(itemId);
                if (item == Items.AIR) return false;
                Container from = findNearestOwnedStorageWithItem(level, new BlockPos(fromX, fromY, fromZ), item, count);
                List<Container> dests = findAvailableOwnedStorages(level, new BlockPos(toX, toY, toZ));
                if (from == null || dests.isEmpty() || !canFitItemInContainer(dests.get(0), item, count)) return false;
                int removed = removeItemFromContainer(from, item, count);
                if (removed != count) return false;
                insertItemIntoContainer(dests.get(0), new ItemStack(item, removed));
                from.setChanged();
                dests.get(0).setChanged();
                return true;
            }
        };
    }

    public static com.abs.livingvillages.domain.SettlementPorts.PersistencePort createPersistencePort(ServerLevel level) {
        return new com.abs.livingvillages.domain.SettlementPorts.PersistencePort() {
            @Override
            public int schemaVersion() {
                return SCHEMA_VERSION;
            }

            @Override
            public int readDiplomaticStanding(int ax, int ay, int az, int bx, int by, int bz) {
                return getDiplomaticStanding(level, new BlockPos(ax, ay, az), new BlockPos(bx, by, bz));
            }

            @Override
            public void writeDiplomaticStanding(int ax, int ay, int az, int bx, int by, int bz, int standing) {
                if (level == null) return;
                UUID idA = villageId(level, new BlockPos(ax, ay, az));
                UUID idB = villageId(level, new BlockPos(bx, by, bz));
                if (idA == null || idB == null || idA.equals(idB)) return;
                VillageRegistry registry = get(level);
                registry.diplomaticStandings.put(pairKey(idA, idB), standing);
                registry.setDirty();
            }
        };
    }

    public static com.abs.livingvillages.domain.SettlementPorts.AgentControlPort createAgentControlPort(ServerLevel level) {
        return new com.abs.livingvillages.domain.SettlementPorts.AgentControlPort() {
            @Override
            public boolean isAgentAlive(String agentId) {
                if (level == null || agentId == null) return false;
                UUID id = parseUuid(agentId);
                if (id == null) return false;
                net.minecraft.world.entity.Entity entity = level.getEntity(id);
                return entity != null && entity.isAlive();
            }

            @Override
            public boolean navigateTo(String agentId, int x, int y, int z, double speedModifier) {
                if (level == null || agentId == null) return false;
                UUID id = parseUuid(agentId);
                if (id == null) return false;
                net.minecraft.world.entity.Entity entity = level.getEntity(id);
                if (!(entity instanceof net.minecraft.world.entity.Mob mob) || !mob.isAlive()) return false;
                BlockPos target = new BlockPos(x, y, z);
                float speed = (float) Math.max(0.2D, Math.min(1.5D, speedModifier));
                if (mob instanceof Villager villager) {
                    villager.getBrain().setMemory(
                        MemoryModuleType.WALK_TARGET,
                        new net.minecraft.world.entity.ai.memory.WalkTarget(target, speed, 1)
                    );
                    return true;
                }
                return mob.getNavigation().moveTo(x + 0.5D, y, z + 0.5D, speed);
            }

            @Override
            public void clearNavigation(String agentId) {
                if (level == null || agentId == null) return;
                UUID id = parseUuid(agentId);
                if (id == null) return;
                net.minecraft.world.entity.Entity entity = level.getEntity(id);
                if (entity instanceof Villager villager) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                } else if (entity instanceof net.minecraft.world.entity.Mob mob) {
                    mob.getNavigation().stop();
                }
            }
        };
    }

    private static Item resolvePortItem(String itemId) {
        if (itemId == null) return Items.AIR;
        return switch (itemId) {
            case "minecraft:raw_iron" -> Items.RAW_IRON;
            case "minecraft:coal" -> Items.COAL;
            case "minecraft:iron_ingot" -> Items.IRON_INGOT;
            case "minecraft:bread" -> Items.BREAD;
            case "minecraft:oak_planks" -> Items.OAK_PLANKS;
            default -> Items.AIR;
        };
    }

    public static void setGuardEquipmentRequested(ServerLevel level, BlockPos center, boolean requested) {
        if (level == null || center == null) return;
        UUID id = villageId(level, center);
        if (id == null) return;
        get(level).guardEquipmentRequests.put(id, requested);
    }

    public static boolean isGuardEquipmentRequested(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return false;
        UUID id = villageId(level, center);
        if (id == null) return false;
        return Boolean.TRUE.equals(get(level).guardEquipmentRequests.get(id));
    }

    /**
     * Records the current visit timestamp for a settlement and, if sufficient time has elapsed
     * since the previous visit, runs honest catch-up smelting using real stored ore and coal.
     */
    public static int recordVisitAndRunCatchUp(ServerLevel level, BlockPos center, long currentTick) {
        if (level == null || center == null) return 0;
        UUID id = villageId(level, center);
        if (id == null) return 0;
        VillageRegistry registry = get(level);
        Long previous = registry.villageLastVisitedTick.put(id, currentTick);
        registry.setDirty();
        if (previous == null || currentTick <= previous) return 0;
        long elapsed = currentTick - previous;
        if (elapsed < com.abs.livingvillages.domain.SettlementSimulationModel.SMELT_DURATION_TICKS) {
            return 0;
        }
        return applyCatchUpSmeltingIfEligible(level, center, elapsed);
    }

    /**
     * Executes honest, material-conserving catch-up smelting in a village that owns both a real
     * furnace and an unreserved chest containing raw iron and coal (Stage 5.1).
     */
    public static int applyCatchUpSmeltingIfEligible(ServerLevel level, BlockPos center, long elapsedTicks) {
        if (level == null || center == null) return 0;
        if (!hasOwnedFurnaceNear(level, center)) return 0;
        Container chest = findNearestOwnedStorageForSmelting(level, center);
        if (chest == null) return 0;

        int rawIron = countItemInContainer(chest, Items.RAW_IRON);
        int coal = countItemInContainer(chest, Items.COAL);
        int ingotRoom = availableRoomForItem(chest, Items.IRON_INGOT);
        var result = com.abs.livingvillages.domain.SettlementSimulationModel.evaluateFurnaceCatchUp(
            elapsedTicks,
            rawIron,
            coal,
            ingotRoom
        );
        if (result.smeltedCount() <= 0) return 0;

        int removedOre = removeItemFromContainer(chest, Items.RAW_IRON, result.oreConsumed());
        if (removedOre != result.oreConsumed()) return 0;
        int removedCoal = removeItemFromContainer(chest, Items.COAL, result.fuelConsumed());
        if (removedCoal != result.fuelConsumed()) {
            insertItemIntoContainer(chest, new ItemStack(Items.RAW_IRON, removedOre));
            return 0;
        }
        insertItemIntoContainer(chest, new ItemStack(Items.IRON_INGOT, result.smeltedCount()));
        chest.setChanged();
        return result.smeltedCount();
    }

    private static boolean hasOwnedFurnaceNear(ServerLevel level, BlockPos center) {
        int radius = Math.min(16, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get());
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -4, -radius), center.offset(radius, 4, radius))) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.FURNACE) && !state.is(Blocks.BLAST_FURNACE)) continue;
            if (isWorkBlockForVillage(level, center, pos)) return true;
        }
        return false;
    }

    private static int availableRoomForItem(Container container, Item item) {
        int room = 0;
        int maxStack = item.getDefaultInstance().getMaxStackSize();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) room += maxStack;
            else if (stack.is(item)) room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return room;
    }

    private static List<Container> findAvailableOwnedStorages(ServerLevel level, BlockPos center) {
        int radius = Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -4, -radius), center.offset(radius, 4, radius))) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST) && !state.is(Blocks.BARREL)) continue;
            if (!isStorageForVillage(level, center, pos) || !VillageReservations.isStorageAvailable(level, pos, null)) continue;
            positions.add(pos.immutable());
        }
        positions.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        List<Container> containers = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container container) {
                containers.add(container);
            }
        }
        return containers;
    }

    private static Container findNearestOwnedStorageWithItem(ServerLevel level, BlockPos center, Item item, int minCount) {
        for (Container container : findAvailableOwnedStorages(level, center)) {
            if (countItemInContainer(container, item) >= minCount) {
                return container;
            }
        }
        return null;
    }

    private static Container findNearestOwnedStorageForSmelting(ServerLevel level, BlockPos center) {
        for (Container container : findAvailableOwnedStorages(level, center)) {
            if (countItemInContainer(container, Items.RAW_IRON) > 0
                && countItemInContainer(container, Items.COAL) > 0) {
                return container;
            }
        }
        return null;
    }

    private static int countItemInContainer(Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static boolean canFitItemInContainer(Container container, Item item, int amount) {
        int room = 0;
        int maxStack = item.getDefaultInstance().getMaxStackSize();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) room += maxStack;
            else if (stack.is(item)) room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            if (room >= amount) return true;
        }
        return room >= amount;
    }

    private static int removeItemFromContainer(Container container, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            container.removeItem(i, take);
            remaining -= take;
        }
        return amount - remaining;
    }

    private static void insertItemIntoContainer(Container container, ItemStack stack) {
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
                ItemStack existing = container.getItem(i);
                if (pass == 0 && !existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                    int move = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
                    if (move > 0) {
                        existing.grow(move);
                        stack.shrink(move);
                    }
                } else if (pass == 1 && existing.isEmpty()) {
                    container.setItem(i, stack.copy());
                    stack.setCount(0);
                }
            }
        }
    }

    public static List<BlockPos> knownLoadedVillageAnchors(ServerLevel level) {
        if (level == null) return List.of();
        VillageRegistry registry = get(level);
        List<BlockPos> loaded = new ArrayList<>();
        for (BlockPos anchor : registry.villageAnchors.values()) {
            if (anchor != null && level.hasChunkAt(anchor)) {
                loaded.add(anchor.immutable());
            }
        }
        return loaded;
    }

    public static void recordDiagnostic(ServerLevel level, BlockPos center, String subsystem, String message) {
        if (level == null || center == null || subsystem == null || subsystem.isBlank() || message == null) return;
        UUID id = villageId(level, center);
        if (id == null) return;
        VillageRegistry registry = get(level);
        Map<String, String> map = registry.villageDiagnostics.computeIfAbsent(id, ignored -> new java.util.LinkedHashMap<>());
        map.put(subsystem, message);
    }

    public static String getDiagnostic(ServerLevel level, BlockPos center, String subsystem) {
        if (level == null || center == null || subsystem == null) return "";
        UUID id = villageId(level, center);
        if (id == null) return "";
        VillageRegistry registry = get(level);
        Map<String, String> map = registry.villageDiagnostics.get(id);
        return map == null ? "" : map.getOrDefault(subsystem, "");
    }

    public static String getDiagnosticsSummary(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return "";
        UUID id = villageId(level, center);
        if (id == null) return "";
        VillageRegistry registry = get(level);
        Map<String, String> map = registry.villageDiagnostics.get(id);
        if (map == null || map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return sb.toString();
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
