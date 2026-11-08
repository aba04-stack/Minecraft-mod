package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Ring-ordered block scans shared by the settlement subsystems.
 *
 * <p>Settlement code repeatedly asks two questions about a village-radius box: "is this block anywhere
 * inside it?" and "where is the nearest one?". Walking the box in {@code dx}/{@code dz} order answers
 * both only after reading every position — 65x65x17 = 71,825 reads at the default radius — even when
 * the answer is one block away. Walking square rings outward makes the early exit exact: a match on
 * ring n is the closest possible match, so an existence check stops there, and a nearest-block query
 * stops as soon as the next ring cannot beat the best distance found.</p>
 *
 * <p>The set of visited positions is identical to the plain nested loops; only the order changes. When
 * several matches are exactly equidistant the chosen one may differ from the old scan order, which is
 * why only "any nearest" queries use it — callers treat equidistant stations as interchangeable.</p>
 *
 * <p>Every scan reports how many positions it read to {@link VillageScanStats}, so budgets can be
 * asserted deterministically in GameTests instead of being estimated from wall-clock time.</p>
 */
final class VillageScanRings {

    private VillageScanRings() {}

    /** Receives ring-ordered horizontal offsets and decides whether the scan can stop. */
    interface Visitor {
        /** Called before each ring; returning {@code true} stops the whole scan. */
        default boolean beginRing(int ring) {
            return false;
        }

        /** Called for one column of offsets; returning {@code true} stops the whole scan. */
        boolean visit(int dx, int dz);
    }

    /**
     * Walks square rings of offsets outward from a center: ring 0 is the center column, ring n is the
     * perimeter of the (2n+1)x(2n+1) square — 8n columns, so all rings together cover exactly
     * (2*radius+1)^2 columns. A negative radius walks nothing, matching the nested loops this replaces.
     *
     * @return {@code true} when the visitor asked to stop early
     */
    static boolean walk(int radius, Visitor visitor) {
        for (int ring = 0; ring <= radius; ring++) {
            if (visitor.beginRing(ring)) return true;
            if (ring == 0) {
                if (visitor.visit(0, 0)) return true;
                continue;
            }
            for (int dx = -ring; dx <= ring; dx++) {
                if (visitor.visit(dx, -ring)) return true;
                if (visitor.visit(dx, ring)) return true;
            }
            for (int dz = -ring + 1; dz <= ring - 1; dz++) {
                if (visitor.visit(-ring, dz)) return true;
                if (visitor.visit(ring, dz)) return true;
            }
        }
        return false;
    }

    /**
     * True when {@code block} exists within {@code radius} horizontally and {@code downReach}/{@code
     * upReach} vertically of {@code center}. Blocks that the registry marks as another settlement's
     * workstation are skipped, exactly as the previous full-box scan did.
     */
    static boolean hasOwned(ServerLevel level, BlockPos center, Block block, int radius,
                            int downReach, int upReach) {
        int[] scanned = new int[1];
        boolean found = walk(radius, new Visitor() {
            @Override
            public boolean visit(int dx, int dz) {
                for (int dy = -downReach; dy <= upReach; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    scanned[0]++;
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(block)) continue;
                    if (VillageRegistry.isWorkBlockState(state)
                        && !VillageRegistry.isWorkBlockForVillage(level, center, pos)) continue;
                    // An existence check stops at the first owned match: nothing later can change it.
                    return true;
                }
                return false;
            }
        });
        VillageScanStats.recordScan(scanned[0]);
        return found;
    }

    /**
     * Nearest position to {@code origin} that {@code accept} allows, or {@code null}.
     *
     * <p>Candidates are restricted to the box around {@code boundsCenter} ({@code boundsRadius}
     * horizontally, {@code downReach}/{@code upReach} vertically), so the accepted set is exactly the
     * set the plain nested loops covered — even when {@code origin} sits far outside that box. Rings
     * are walked outward from {@code origin}, therefore the first match is a nearest one in ring order,
     * and columns outside the box cost an integer comparison instead of a chunk and block-state read.</p>
     *
     * <p>{@code accept} performs the world reads and decides the whole question (block kind, ownership,
     * free space, a reachable standing position), which is why the scan can stop at the first match.</p>
     */
    static BlockPos nearestInBox(BlockPos origin, BlockPos boundsCenter, int boundsRadius,
                                 int downReach, int upReach, Predicate<BlockPos> accept) {
        if (origin == null || boundsCenter == null || accept == null || boundsRadius < 0) return null;
        int minY = boundsCenter.getY() - downReach;
        int maxY = boundsCenter.getY() + upReach;
        // Far enough to reach every column of the box even when the worker stands outside it.
        int reach = boundsRadius + Math.max(Math.abs(origin.getX() - boundsCenter.getX()),
            Math.abs(origin.getZ() - boundsCenter.getZ()));
        int[] scanned = new int[1];
        BlockPos[] found = new BlockPos[1];
        walk(reach, (dx, dz) -> {
            int x = origin.getX() + dx;
            int z = origin.getZ() + dz;
            if (Math.max(Math.abs(x - boundsCenter.getX()), Math.abs(z - boundsCenter.getZ())) > boundsRadius) {
                return false;
            }
            for (int y = minY; y <= maxY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                scanned[0]++;
                if (accept.test(pos)) {
                    found[0] = pos.immutable();
                    return true;
                }
            }
            return false;
        });
        VillageScanStats.recordScan(scanned[0]);
        return found[0];
    }

    /**
     * Nearest {@code block} to {@code center} within the same volume, or {@code null}. Ownership rules
     * match {@link #hasOwned}. The scan stops before a ring whose minimum possible squared distance
     * already exceeds the best squared distance found, so the result is a true nearest match.
     */
    static BlockPos nearestOwned(ServerLevel level, BlockPos center, Block block, int radius,
                                 int downReach, int upReach) {
        BlockPos[] best = new BlockPos[1];
        double[] bestDistance = {Double.MAX_VALUE};
        int[] scanned = new int[1];
        walk(radius, new Visitor() {
            @Override
            public boolean beginRing(int ring) {
                // Every position of this ring is at least `ring` blocks away horizontally, so once the
                // squared ring distance exceeds the best squared distance found, the rest cannot win.
                return best[0] != null && (double) ring * ring > bestDistance[0];
            }

            @Override
            public boolean visit(int dx, int dz) {
                for (int dy = -downReach; dy <= upReach; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    scanned[0]++;
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(block)) continue;
                    if (VillageRegistry.isWorkBlockState(state)
                        && !VillageRegistry.isWorkBlockForVillage(level, center, pos)) continue;
                    double distance = pos.distSqr(center);
                    if (distance < bestDistance[0]) { bestDistance[0] = distance; best[0] = pos.immutable(); }
                }
                return false;
            }
        });
        VillageScanStats.recordScan(scanned[0]);
        return best[0];
    }
}
