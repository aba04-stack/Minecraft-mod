package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared best-effort boundary for currently loaded village residents.
 *
 * HOME memories provide more stable anchors than a player's position. Residents with nearby
 * HOME anchors form connected groups; residents without HOME are attached to only their nearest
 * group, so a wandering resident cannot merge two otherwise separate home clusters. When no
 * resident has a usable HOME memory, nearby positions provide the fallback grouping.
 *
 * This is deliberately not a global saved village registry. It scopes active, loaded-area work,
 * and must not be presented as proof that every physical storage block belongs to one village.
 */
public final class VillageArea {
    private static final int HOME_CLUSTER_LINK_BLOCKS = 20;
    private static final int FALLBACK_CLUSTER_LINK_BLOCKS = 20;

    private VillageArea() {}

    public static List<Villager> findVillagers(ServerLevel level, BlockPos center) {
        return findVillagers(level, center, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get(), 12);
    }

    public static List<Villager> findVillagers(ServerLevel level, BlockPos center,
                                                int horizontalRadius, int verticalRadius) {
        int radius = Math.max(0, horizontalRadius);
        int height = Math.max(0, verticalRadius);
        AABB area = new AABB(center).inflate(radius, height, radius);
        List<Villager> candidates = level.getEntitiesOfClass(Villager.class, area, Villager::isAlive);
        candidates.sort(Comparator.comparing(v -> v.getUUID().toString()));
        if (candidates.size() <= 1) return List.copyOf(candidates);

        List<Resident> withHome = new ArrayList<>();
        List<Resident> withoutHome = new ArrayList<>();
        for (Villager villager : candidates) {
            GlobalPos home = villager.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
            BlockPos homePosition = home != null && home.dimension().equals(level.dimension())
                ? home.pos().immutable() : null;
            Resident resident = new Resident(villager, homePosition);
            if (homePosition == null) withoutHome.add(resident);
            else withHome.add(resident);
        }

        List<List<Resident>> clusters = connectedComponents(withHome, true, HOME_CLUSTER_LINK_BLOCKS);
        List<Resident> unattached = new ArrayList<>();

        // A resident without a HOME memory may still be an ordinary member of a nearby group.
        // Attach them to one nearest cluster only; never let them bridge two home clusters.
        for (Resident resident : withoutHome) {
            int nearestCluster = -1;
            long nearestDistance = Long.MAX_VALUE;
            BlockPos position = resident.villager().blockPosition();
            for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
                long clusterDistance = Long.MAX_VALUE;
                for (Resident member : clusters.get(clusterIndex)) {
                    clusterDistance = Math.min(clusterDistance,
                        horizontalDistanceSquared(position, member.homePosition()));
                    clusterDistance = Math.min(clusterDistance,
                        horizontalDistanceSquared(position, member.villager().blockPosition()));
                }
                if (clusterDistance < nearestDistance) {
                    nearestDistance = clusterDistance;
                    nearestCluster = clusterIndex;
                }
            }
            long attachLimit = (long) HOME_CLUSTER_LINK_BLOCKS * HOME_CLUSTER_LINK_BLOCKS;
            if (nearestCluster >= 0 && nearestDistance <= attachLimit) {
                clusters.get(nearestCluster).add(resident);
            } else {
                unattached.add(resident);
            }
        }

        clusters.addAll(connectedComponents(unattached, false, FALLBACK_CLUSTER_LINK_BLOCKS));
        if (clusters.isEmpty()) {
            // Defensive fallback for unusual API state: never lose alive candidates.
            clusters.add(candidates.stream().map(v -> new Resident(v, null)).toList());
        }

        List<Resident> selected = selectNearestCluster(clusters, center);
        return selected.stream().map(Resident::villager).toList();
    }

    /**
     * Best-effort storage ownership heuristic: a physical container belongs to the resident
     * cluster selected around it. The wider query includes villagers currently away from home.
     * This is not a permanent settlement registry, but avoids using a neighboring cluster's stock
     * just because its chest happens to fall inside a broad radius.
     */
    public static boolean isStorageInResidentGroup(ServerLevel level, Villager owner, BlockPos storage) {
        if (owner == null || storage == null || !owner.isAlive()) return false;
        int radius = storageGroupLookupRadius();
        return findVillagers(level, storage, radius, 32).stream()
            .anyMatch(resident -> resident.getUUID().equals(owner.getUUID()));
    }

    /** Checks that a storage block's nearest selected resident cluster overlaps the requested group. */
    public static boolean isStorageInResidentGroup(ServerLevel level, BlockPos groupCenter, BlockPos storage) {
        if (groupCenter == null || storage == null) return false;
        int radius = storageGroupLookupRadius();
        List<Villager> requestedGroup = findVillagers(level, groupCenter, radius, 32);
        if (requestedGroup.isEmpty()) return false;
        List<Villager> storageGroup = findVillagers(level, storage, radius, 32);
        for (Villager resident : storageGroup) {
            for (Villager requested : requestedGroup) {
                if (resident.getUUID().equals(requested.getUUID())) return true;
            }
        }
        return false;
    }

    private static int storageGroupLookupRadius() {
        return Math.min(128, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get()
            + LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get() + 32);
    }

    private static List<List<Resident>> connectedComponents(List<Resident> source,
                                                              boolean useHomeAnchors,
                                                              int linkDistanceBlocks) {
        List<Resident> remaining = new ArrayList<>(source);
        List<List<Resident>> components = new ArrayList<>();
        long linkDistanceSquared = (long) linkDistanceBlocks * linkDistanceBlocks;

        while (!remaining.isEmpty()) {
            List<Resident> component = new ArrayList<>();
            component.add(remaining.remove(0));
            for (int cursor = 0; cursor < component.size(); cursor++) {
                BlockPos basis = anchor(component.get(cursor), useHomeAnchors);
                for (int i = remaining.size() - 1; i >= 0; i--) {
                    Resident candidate = remaining.get(i);
                    if (horizontalDistanceSquared(basis, anchor(candidate, useHomeAnchors)) <= linkDistanceSquared) {
                        component.add(remaining.remove(i));
                    }
                }
            }
            component.sort(Comparator.comparing(r -> r.villager().getUUID().toString()));
            components.add(component);
        }
        return components;
    }

    private static BlockPos anchor(Resident resident, boolean useHomeAnchors) {
        return useHomeAnchors ? resident.homePosition() : resident.villager().blockPosition();
    }

    private static List<Resident> selectNearestCluster(List<List<Resident>> clusters, BlockPos center) {
        List<Resident> selected = null;
        long bestDistance = Long.MAX_VALUE;
        int bestSize = -1;
        String bestId = "";
        for (List<Resident> cluster : clusters) {
            long distance = Long.MAX_VALUE;
            String firstId = null;
            for (Resident resident : cluster) {
                BlockPos reference = resident.homePosition() == null
                    ? resident.villager().blockPosition() : resident.homePosition();
                distance = Math.min(distance, horizontalDistanceSquared(reference, center));
                String id = resident.villager().getUUID().toString();
                if (firstId == null || id.compareTo(firstId) < 0) firstId = id;
            }
            boolean closer = distance < bestDistance;
            boolean sameDistanceLarger = distance == bestDistance && cluster.size() > bestSize;
            boolean stableTieBreak = distance == bestDistance && cluster.size() == bestSize
                && firstId != null && (selected == null || firstId.compareTo(bestId) < 0);
            if (closer || sameDistanceLarger || stableTieBreak) {
                selected = cluster;
                bestDistance = distance;
                bestSize = cluster.size();
                bestId = firstId == null ? "" : firstId;
            }
        }
        return selected == null ? List.of() : selected;
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private record Resident(Villager villager, BlockPos homePosition) {}
}
