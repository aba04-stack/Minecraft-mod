package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Lightweight support for miners exploring natural caves.
 *
 * <p>The villager stores a short, bounded trail in its own persistent data, so the route survives
 * chunk saves and server restarts. It places torches only from real carried/stored items and only
 * on valid, supported positions. At night or when badly hurt it follows the remembered trail
 * backwards and then heads to its HOME anchor. It never loads chunks to inspect or route through.</p>
 */
public final class VillagerCaveSupport {
    private static final String P = "livingvillages_cave_";
    static final String TRAIL = P + "trail";
    private static final String LAST_WAYPOINT = P + "last_waypoint";
    private static final String LAST_TORCH = P + "last_torch";
    private static final String LAST_TORCH_TICK = P + "last_torch_tick";
    private static final String NEXT_TORCH_RESTOCK = P + "next_torch_restock";
    static final String RETURNING = P + "returning";
    static final String RETURN_INDEX = P + "return_index";
    static final String RETURN_TARGET = P + "return_target";
    static final String RETURN_LAST_POS = P + "return_last_pos";
    static final String RETURN_LAST_PROGRESS = P + "return_last_progress";
    static final String RETURN_LAST_ORDER = P + "return_last_order";
    static final String HOME_STALLS = P + "home_stalls";
    static final String HOME_RETRY_AT = P + "home_retry_at";
    static final String HOME_PAUSED = P + "home_paused";
    static final String HOME_PAUSED_AT = P + "home_paused_at";
    static final String HOME_PAUSED_HOME = P + "home_paused_home";
    private static final String NEXT_SCOUT = P + "next_scout";
    private static final String SCOUT_ACTIVE = P + "scout_active";
    private static final String SCOUT_TARGET = P + "scout_target";
    private static final String SCOUT_STARTED = P + "scout_started";
    private static final String SCOUT_LAST_POS = P + "scout_last_pos";
    private static final String SCOUT_LAST_PROGRESS = P + "scout_last_progress";
    private static final String SCOUT_LAST_ORDER = P + "scout_last_order";
    private static final String FAILED_SCOUTS = P + "failed_scouts";
    private static final String FAILED_SCOUT_TICK = P + "failed_scout_tick";
    private static final String SCOUT_VISITED = P + "scout_visited";

    private static final int TICK_STEP = 10;
    static final int MAX_WAYPOINTS = 48;
    private static final int MAX_HOME_RETURN_STALLS = 3;
    private static final int MAX_FAILED_SCOUTS = 12;
    static final int MAX_VISITED_SCOUTS = 64;
    private static final int MAX_SCOUT_PATH_CHECKS = 3;
    private static final int MAX_SCOUT_PATH_NODES = 40;
    private static final int SCOUT_RADIUS_BLOCKS = 6;
    private static final int WAYPOINT_SPACING_SQUARED = 36;
    private static final int TORCH_SPACING_SQUARED = 49;
    private static final long TORCH_COOLDOWN_TICKS = 80L;
    private static final long ROUTE_REISSUE_TICKS = 30L;
    private static final long WAYPOINT_STALL_TICKS = 400L;
    private static final long HOME_RETRY_BASE_TICKS = 200L;
    private static final long SCOUT_INTERVAL_TICKS = 200L;
    private static final long SCOUT_EMPTY_RETRY_TICKS = 400L;
    private static final long SCOUT_STALL_TICKS = 300L;
    private static final long SCOUT_TIMEOUT_TICKS = 2400L;
    private static final long SCOUT_ROUTE_REISSUE_TICKS = 40L;
    private static final long FAILED_SCOUT_TTL_TICKS = 6000L;
    private static final double SCOUT_MIN_DISTANCE_SQUARED = 36.0D;
    private static final double SCOUT_ARRIVAL_DISTANCE_SQUARED = 4.0D;
    private static final double FAILED_SCOUT_DISTANCE_SQUARED = 16.0D;
    private static final double VISITED_SCOUT_DISTANCE_SQUARED = 16.0D;
    private static final double VISITED_TRAIL_DISTANCE_SQUARED = 64.0D;
    private static final double ARRIVAL_DISTANCE_SQUARED = 6.25D;
    private static final float WALK_SPEED = 0.90F;

    private VillagerCaveSupport() {
    }

    public static boolean isReturning(Villager villager) {
        return villager != null && villager.getPersistentData().getBoolean(RETURNING);
    }

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()
            || !villager.isAlive() || villager.isBaby() || villager.tickCount % TICK_STEP != 0
            || !(villager.level() instanceof ServerLevel level)) return;

        CompoundTag data = villager.getPersistentData();
        long now = level.getGameTime();

        // A return trip already started is safety-critical and continues even if the role/config changes.
        if (data.getBoolean(RETURNING)) {
            continueReturnHome(villager, level, data, now);
            return;
        }
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()) {
            cancelCaveScout(villager, data);
            return;
        }

        // Guards light the dark parts of their own patrol route, but yield completely during
        // combat, retreat and post-combat aid. This reuses the same real-item torch logistics.
        if (VillagerRoles.isGuard(villager)
            && LivingVillagesConfig.ENABLE_VILLAGE_GUARDS.get()
            && LivingVillagesConfig.ENABLE_VILLAGE_LIGHTING.get()
            && VillageDefense.canPerformPatrolLighting(villager)) {
            restockTorches(villager, level, data, now);
            placeTorchAtDarkSpot(villager, level, data, now);
        }

        if (!VillagerRoles.isMiner(villager)
            || !LivingVillagesConfig.ENABLE_MINER_ROLE.get()
            || !LivingVillagesConfig.ENABLE_MINER_CAVE_EXPLORATION.get()) {
            cancelCaveScout(villager, data);
            return;
        }

        recordCaveWaypoint(villager, level, data);
        restockTorches(villager, level, data, now);
        if (isUnderground(level, villager.blockPosition())) {
            placeTorchAtDarkSpot(villager, level, data, now);
        }

        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        boolean lowHealth = villager.getHealth() <= villager.getMaxHealth() * 0.35F;
        if ((dayTime >= 11400L || lowHealth) && data.getLongArray(TRAIL).length > 0) {
            cancelCaveScout(villager, data);
            beginReturnHome(villager, data, now);
            continueReturnHome(villager, level, data, now);
            return;
        }

        if (VillagerMining.hasActiveTask(villager)) {
            cancelCaveScout(villager, data);
            return;
        }
        if (dayTime < 1000L || dayTime >= 11400L || lowHealth
            || VillagerRoles.shouldYieldToVanillaVillagerAi(villager)
            || villager.isSleeping() || villager.isInWater()) {
            cancelCaveScout(villager, data);
            data.putLong(NEXT_SCOUT, now + SCOUT_INTERVAL_TICKS);
            return;
        }
        runCaveScout(villager, level, data, now);
    }


    private static void runCaveScout(Villager villager, ServerLevel level, CompoundTag data, long now) {
        if (data.getBoolean(SCOUT_ACTIVE)) {
            if (!data.contains(SCOUT_TARGET)) {
                cancelCaveScout(villager, data);
                data.putLong(NEXT_SCOUT, now + SCOUT_INTERVAL_TICKS);
                return;
            }
            continueCaveScout(villager, level, data, now);
            return;
        }
        if (now < data.getLong(NEXT_SCOUT)) return;

        if (now - data.getLong(FAILED_SCOUT_TICK) > FAILED_SCOUT_TTL_TICKS) {
            data.remove(FAILED_SCOUTS);
            data.remove(FAILED_SCOUT_TICK);
        }
        if (!VillagerMining.hasPickaxeAvailable(villager)) {
            data.putLong(NEXT_SCOUT, now + SCOUT_EMPTY_RETRY_TICKS);
            return;
        }
        if (villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isPresent()) {
            data.putLong(NEXT_SCOUT, now + SCOUT_ROUTE_REISSUE_TICKS);
            return;
        }

        BlockPos home = VillagerRoles.getHome(villager);
        if (home == null) home = villager.blockPosition();
        if (!VillageResourcePlanner.getPlan(level, home).needsOreWork()) {
            data.putLong(NEXT_SCOUT, now + SCOUT_EMPTY_RETRY_TICKS);
            return;
        }

        BlockPos target = findNextCaveTarget(level, villager, data, now);
        if (target == null) {
            data.putLong(NEXT_SCOUT, now + SCOUT_EMPTY_RETRY_TICKS);
            return;
        }

        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, 1));
        data.putBoolean(SCOUT_ACTIVE, true);
        data.putLong(SCOUT_TARGET, target.asLong());
        data.putLong(SCOUT_STARTED, now);
        data.putLong(SCOUT_LAST_POS, villager.blockPosition().asLong());
        data.putLong(SCOUT_LAST_PROGRESS, now);
        data.putLong(SCOUT_LAST_ORDER, now);
    }

    private static void continueCaveScout(Villager villager, ServerLevel level, CompoundTag data, long now) {
        BlockPos target = BlockPos.of(data.getLong(SCOUT_TARGET));
        if (!level.hasChunkAt(target) || !isSafeCaveNode(level, target)) {
            failCaveScout(villager, data, target, now);
            return;
        }
        if (now - data.getLong(SCOUT_STARTED) > SCOUT_TIMEOUT_TICKS) {
            failCaveScout(villager, data, target, now);
            return;
        }
        if (villager.blockPosition().distSqr(target) <= SCOUT_ARRIVAL_DISTANCE_SQUARED) {
            data.putLongArray(SCOUT_VISITED,
                appendVisitedScoutTarget(data.getLongArray(SCOUT_VISITED), target));
            cancelCaveScout(villager, data);
            data.putLong(NEXT_SCOUT, now + SCOUT_INTERVAL_TICKS);
            return;
        }

        long lastPosition = data.getLong(SCOUT_LAST_POS);
        if (villager.blockPosition().distSqr(BlockPos.of(lastPosition)) >= 1.0D) {
            data.putLong(SCOUT_LAST_POS, villager.blockPosition().asLong());
            data.putLong(SCOUT_LAST_PROGRESS, now);
        } else if (now - data.getLong(SCOUT_LAST_PROGRESS) >= SCOUT_STALL_TICKS) {
            failCaveScout(villager, data, target, now);
            return;
        }

        Optional<WalkTarget> current = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        if (current.isPresent()) {
            if (!current.get().getTarget().currentBlockPosition().equals(target)) {
                // Another AI task took ownership of navigation; do not fight it.
                cancelCaveScout(villager, data);
                data.putLong(NEXT_SCOUT, now + SCOUT_INTERVAL_TICKS);
            }
            return;
        }

        if (now - data.getLong(SCOUT_LAST_ORDER) >= SCOUT_ROUTE_REISSUE_TICKS) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, 1));
            data.putLong(SCOUT_LAST_ORDER, now);
        }
    }

    private static BlockPos findNextCaveTarget(ServerLevel level, Villager villager, CompoundTag data, long now) {
        BlockPos origin = villager.blockPosition();
        boolean alreadyUnderground = isUnderground(level, origin);
        int radius = Math.min(SCOUT_RADIUS_BLOCKS, LivingVillagesConfig.MINER_SEARCH_RADIUS_BLOCKS.get());
        int minY = Math.max(level.getMinBuildHeight() + 2, origin.getY() - (alreadyUnderground ? 10 : 12));
        int maxY = Math.min(level.getMaxBuildHeight() - 3, origin.getY() + (alreadyUnderground ? 3 : -3));
        if (minY > maxY) return null;

        List<BlockPos> candidates = new ArrayList<>(256);
        long[] trail = data.getLongArray(TRAIL);
        long[] failed = data.getLongArray(FAILED_SCOUTS);
        long[] visited = data.getLongArray(SCOUT_VISITED);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                BlockPos column = origin.offset(dx, 0, dz);
                if (!level.hasChunkAt(column)) continue;
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    column.getX(), column.getZ());
                for (int y = minY; y <= maxY; y++) {
                    BlockPos candidate = origin.offset(dx, y - origin.getY(), dz);
                    if (origin.distSqr(candidate) < SCOUT_MIN_DISTANCE_SQUARED
                        || !isSafeCaveNode(level, candidate, surfaceY)
                        || isNearRememberedTrail(candidate, trail)
                        || isNearVisitedScoutTarget(visited, candidate)
                        || isFailedScoutTarget(failed, candidate)) continue;
                    candidates.add(candidate.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> origin.distSqr(candidate)));

        int pathChecks = 0;
        for (BlockPos candidate : candidates) {
            if (pathChecks >= MAX_SCOUT_PATH_CHECKS) break;
            if (!VillageTickBudget.tryConsumeAiCheck(level)) break;
            pathChecks++;

            Path path = villager.getNavigation().createPath(candidate, 0);
            if (path == null || !path.canReach() || path.getNodeCount() < 2
                || path.getNodeCount() > MAX_SCOUT_PATH_NODES
                || !isSafeCavePath(level, path)) {
                failed = appendFailedScoutTarget(failed, candidate);
                data.putLongArray(FAILED_SCOUTS, failed);
                data.putLong(FAILED_SCOUT_TICK, now);
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static boolean isSafeCavePath(ServerLevel level, Path path) {
        BlockPos previous = null;
        for (int i = 0; i < path.getNodeCount(); i++) {
            BlockPos node = path.getNodePos(i);
            if (!isSafeWalkNode(level, node)) return false;
            if (previous != null && Math.abs(node.getY() - previous.getY()) > 1) return false;
            previous = node;
        }
        return true;
    }

    private static boolean isNearRememberedTrail(BlockPos candidate, long[] trail) {
        for (long packed : trail) {
            if (candidate.distSqr(BlockPos.of(packed)) < VISITED_TRAIL_DISTANCE_SQUARED) return true;
        }
        return false;
    }

    static boolean isSafeCaveNode(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null || !level.hasChunkAt(feet)) return false;
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            feet.getX(), feet.getZ());
        return isSafeCaveNode(level, feet, surfaceY);
    }

    static boolean isSafeCaveNode(ServerLevel level, BlockPos feet, int surfaceY) {
        // Heightmap depth is deterministic immediately after block updates. canSeeSky may still
        // report stale skylight data in the same tick a cave roof is placed or removed.
        return feet.getY() <= surfaceY - 5
            && isSafeWalkNode(level, feet);
    }

    private static boolean isSafeWalkNode(ServerLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above()) || !level.hasChunkAt(feet.below())) return false;
        BlockState atFeet = level.getBlockState(feet);
        BlockState atHead = level.getBlockState(feet.above());
        BlockState floor = level.getBlockState(feet.below());
        if ((!atFeet.isAir() && !VillageBuilder.isAirOrReplaceableVegetation(atFeet))
            || (!atHead.isAir() && !VillageBuilder.isAirOrReplaceableVegetation(atHead))
            || !floor.blocksMotion()
            || !level.getFluidState(feet).isEmpty()
            || !level.getFluidState(feet.above()).isEmpty()
            || !level.getFluidState(feet.below()).isEmpty()
            || isHazardousFloor(floor)) return false;
        for (Direction direction : Direction.values()) {
            if (!level.hasChunkAt(feet.relative(direction))
                || level.getFluidState(feet.relative(direction)).is(FluidTags.LAVA)) return false;
        }
        return true;
    }

    private static boolean isHazardousFloor(BlockState floor) {
        return floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CAMPFIRE)
            || floor.is(Blocks.SOUL_CAMPFIRE) || floor.is(Blocks.CACTUS)
            || floor.is(Blocks.POWDER_SNOW) || floor.is(Blocks.FIRE)
            || floor.is(Blocks.SOUL_FIRE);
    }

    /** Stores recently reached scouting sites separately from the return trail. */
    static long[] appendVisitedScoutTarget(long[] previous, BlockPos target) {
        if (target == null) return previous == null ? new long[0] : previous;
        long[] old = previous == null ? new long[0] : previous;
        if (isNearVisitedScoutTarget(old, target)) return old;
        if (old.length < MAX_VISITED_SCOUTS) {
            long[] next = new long[old.length + 1];
            System.arraycopy(old, 0, next, 0, old.length);
            next[old.length] = target.asLong();
            return next;
        }
        long[] next = new long[MAX_VISITED_SCOUTS];
        System.arraycopy(old, old.length - (MAX_VISITED_SCOUTS - 1), next, 0, MAX_VISITED_SCOUTS - 1);
        next[MAX_VISITED_SCOUTS - 1] = target.asLong();
        return next;
    }

    private static boolean isNearVisitedScoutTarget(long[] visited, BlockPos candidate) {
        for (long packed : visited) {
            if (candidate.distSqr(BlockPos.of(packed)) <= VISITED_SCOUT_DISTANCE_SQUARED) return true;
        }
        return false;
    }

    static long[] appendFailedScoutTarget(long[] previous, BlockPos target) {
        if (target == null) return previous == null ? new long[0] : previous;
        long[] old = previous == null ? new long[0] : previous;
        if (isFailedScoutTarget(old, target)) return old;
        if (old.length < MAX_FAILED_SCOUTS) {
            long[] next = new long[old.length + 1];
            System.arraycopy(old, 0, next, 0, old.length);
            next[old.length] = target.asLong();
            return next;
        }
        long[] next = new long[MAX_FAILED_SCOUTS];
        System.arraycopy(old, old.length - (MAX_FAILED_SCOUTS - 1), next, 0, MAX_FAILED_SCOUTS - 1);
        next[MAX_FAILED_SCOUTS - 1] = target.asLong();
        return next;
    }

    private static boolean isFailedScoutTarget(long[] failed, BlockPos candidate) {
        for (long packed : failed) {
            if (candidate.distSqr(BlockPos.of(packed)) <= FAILED_SCOUT_DISTANCE_SQUARED) return true;
        }
        return false;
    }

    private static void failCaveScout(Villager villager, CompoundTag data, BlockPos target, long now) {
        data.putLongArray(FAILED_SCOUTS, appendFailedScoutTarget(data.getLongArray(FAILED_SCOUTS), target));
        data.putLong(FAILED_SCOUT_TICK, now);
        cancelCaveScout(villager, data);
        data.putLong(NEXT_SCOUT, now + SCOUT_EMPTY_RETRY_TICKS);
    }

    private static void cancelCaveScout(Villager villager, CompoundTag data) {
        if (data.getBoolean(SCOUT_ACTIVE) && data.contains(SCOUT_TARGET)) {
            BlockPos owned = BlockPos.of(data.getLong(SCOUT_TARGET));
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(owned)) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            });
        }
        data.putBoolean(SCOUT_ACTIVE, false);
        data.remove(SCOUT_TARGET);
        data.remove(SCOUT_STARTED);
        data.remove(SCOUT_LAST_POS);
        data.remove(SCOUT_LAST_PROGRESS);
        data.remove(SCOUT_LAST_ORDER);
    }

    private static void recordCaveWaypoint(Villager villager, ServerLevel level, CompoundTag data) {
        BlockPos feet = villager.blockPosition();
        if (!isUnderground(level, feet)) return;

        long[] old = data.getLongArray(TRAIL);
        long[] next = appendBoundedWaypoint(old, feet);
        if (next == old) return;
        data.putLongArray(TRAIL, next);
        data.putLong(LAST_WAYPOINT, level.getGameTime());
    }

    private static boolean isUnderground(ServerLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet)) return false;
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        return feet.getY() <= surfaceY - 5;
    }

    /** Retains the cave entrance and the newest points without allowing an unbounded entity NBT trail. */
    static long[] appendBoundedWaypoint(long[] old, BlockPos feet) {
        if (feet == null) return old == null ? new long[0] : old;
        long[] route = old == null ? new long[0] : old;
        if (route.length > 0
            && BlockPos.of(route[route.length - 1]).distSqr(feet) < WAYPOINT_SPACING_SQUARED) return route;

        if (route.length < MAX_WAYPOINTS) {
            long[] next = new long[route.length + 1];
            System.arraycopy(route, 0, next, 0, route.length);
            next[route.length] = feet.asLong();
            return next;
        }

        long[] next = new long[MAX_WAYPOINTS];
        next[0] = route[0];
        int tailCount = MAX_WAYPOINTS - 2;
        int sourceStart = Math.max(1, route.length - tailCount);
        int copied = route.length - sourceStart;
        System.arraycopy(route, sourceStart, next, 1, copied);
        next[copied + 1] = feet.asLong();
        return next;
    }

    private static void restockTorches(Villager villager, ServerLevel level, CompoundTag data, long now) {
        if (now < data.getLong(NEXT_TORCH_RESTOCK)) return;
        // The storage scan is deliberately much slower than the miner's work tick.
        data.putLong(NEXT_TORCH_RESTOCK, now + 400L);
        SimpleContainer inventory = villager.getInventory();
        if (countTorches(villager) >= 8) return;

        BlockPos center = VillagerRoles.getHome(villager);
        if (center == null) center = villager.blockPosition();
        int radius = Math.min(12, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos storagePos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
                        || !VillageRegistry.isStorageForVillage(level, center, storagePos)
                        || !VillageReservations.isStorageAvailable(level, storagePos, villager)
                        || !(level.getBlockEntity(storagePos) instanceof Container storage)) continue;

                    for (int slot = 0; slot < storage.getContainerSize(); slot++) {
                        ItemStack stored = storage.getItem(slot);
                        if (!stored.is(Items.TORCH) || stored.getCount() <= 8) continue;
                        int requested = Math.min(16 - countTorches(villager), stored.getCount() - 8);
                        if (requested <= 0) return;
                        ItemStack offered = new ItemStack(Items.TORCH, requested);
                        ItemStack remainder = inventory.addItem(offered);
                        int moved = requested - remainder.getCount();
                        if (moved > 0) {
                            stored.shrink(moved);
                            if (stored.isEmpty()) storage.setItem(slot, ItemStack.EMPTY);
                            storage.setChanged();
                            inventory.setChanged();
                        }
                        if (countTorches(villager) >= 8) return;
                    }
                }
            }
        }
    }

    private static int countTorches(Villager villager) {
        int count = 0;
        Container inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.TORCH)) count += stack.getCount();
        }
        ItemStack offhand = villager.getOffhandItem();
        if (offhand.is(Items.TORCH)) count += offhand.getCount();
        return count;
    }

    private static void placeTorchAtDarkSpot(Villager villager, ServerLevel level, CompoundTag data, long now) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            || now - data.getLong(LAST_TORCH_TICK) < TORCH_COOLDOWN_TICKS) return;
        if (countTorches(villager) <= 0) return;

        BlockPos origin = villager.blockPosition();
        List<BlockPos> candidates = new ArrayList<>(8);
        candidates.add(origin.north());
        candidates.add(origin.east());
        candidates.add(origin.south());
        candidates.add(origin.west());
        candidates.add(origin.north().east());
        candidates.add(origin.south().east());
        candidates.add(origin.south().west());
        candidates.add(origin.north().west());

        BlockPos previousTorch = data.contains(LAST_TORCH)
            ? BlockPos.of(data.getLong(LAST_TORCH)) : null;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : candidates) {
            if (!level.hasChunkAt(candidate) || !level.hasChunkAt(candidate.below())) continue;
            if (level.getMaxLocalRawBrightness(candidate) >= 8) continue;
            if (previousTorch != null && previousTorch.distSqr(candidate) < TORCH_SPACING_SQUARED) continue;
            BlockState existing = level.getBlockState(candidate);
            if (!existing.isAir()) continue;
            if (!level.getFluidState(candidate).isEmpty() || !level.getBlockState(candidate.below()).blocksMotion()) continue;
            if (!Blocks.TORCH.defaultBlockState().canSurvive(level, candidate)) continue;
            double distance = villager.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.immutable();
            }
        }
        if (best == null) return;
        BlockState replaced = level.getBlockState(best);
        if (!level.setBlock(best, Blocks.TORCH.defaultBlockState(), 3)) return;
        if (!consumeTorch(villager)) {
            level.setBlock(best, replaced, 3);
            return;
        }
        data.putLong(LAST_TORCH, best.asLong());
        data.putLong(LAST_TORCH_TICK, now);
        VillageTickBudget.recordActivitySignal(level, best);
    }

    private static boolean consumeTorch(Villager villager) {
        ItemStack offhand = villager.getOffhandItem();
        if (offhand.is(Items.TORCH)) {
            offhand.shrink(1);
            if (offhand.isEmpty()) villager.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            return true;
        }
        Container inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(Items.TORCH)) continue;
            stack.shrink(1);
            if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            inventory.setChanged();
            return true;
        }
        return false;
    }

    static int nearestTrailIndex(BlockPos current, long[] trail) {
        if (current == null || trail == null || trail.length == 0) return -1;
        int nearestIndex = -1;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < trail.length; i++) {
            double distance = current.distSqr(BlockPos.of(trail[i]));
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    private static void beginReturnHome(Villager villager, CompoundTag data, long now) {
        long[] trail = data.getLongArray(TRAIL);
        if (trail.length == 0) return;
        VillagerMining.suspendForCaveReturn(villager);
        data.putBoolean(RETURNING, true);
        data.putInt(RETURN_INDEX, nearestTrailIndex(villager.blockPosition(), trail));
        data.putLong(RETURN_LAST_PROGRESS, now);
        data.putLong(RETURN_LAST_ORDER, 0L);
        data.putLong(RETURN_LAST_POS, villager.blockPosition().asLong());
        data.remove(RETURN_TARGET);
        data.putInt(HOME_STALLS, 0);
        data.remove(HOME_RETRY_AT);
        data.putBoolean(HOME_PAUSED, false);
        data.remove(HOME_PAUSED_AT);
        data.remove(HOME_PAUSED_HOME);
    }

    static void continueReturnHome(Villager villager, ServerLevel level, CompoundTag data, long now) {
        BlockPos home = VillagerRoles.getHome(villager);
        if (home == null) {
            clearReturnTarget(villager, data);
            return;
        }

        if (data.getBoolean(HOME_PAUSED)) {
            boolean moved = villager.blockPosition().asLong() != data.getLong(HOME_PAUSED_AT);
            boolean homeChanged = home.asLong() != data.getLong(HOME_PAUSED_HOME);
            if (!moved && !homeChanged) return;
            data.putBoolean(HOME_PAUSED, false);
            data.putInt(HOME_STALLS, 0);
            data.remove(HOME_PAUSED_AT);
            data.remove(HOME_PAUSED_HOME);
            data.remove(HOME_RETRY_AT);
            data.putLong(RETURN_LAST_PROGRESS, now);
            data.putLong(RETURN_LAST_POS, villager.blockPosition().asLong());
            data.putLong(RETURN_LAST_ORDER, 0L);
            data.remove(RETURN_TARGET);
        }
        if (now < data.getLong(HOME_RETRY_AT)) return;

        long[] trail = data.getLongArray(TRAIL);
        int index = Math.min(data.getInt(RETURN_INDEX), trail.length - 1);
        BlockPos target = null;
        while (index >= 0) {
            BlockPos candidate = BlockPos.of(trail[index]);
            if (level.hasChunkAt(candidate) && villager.blockPosition().distSqr(candidate) > ARRIVAL_DISTANCE_SQUARED) {
                target = candidate.immutable();
                break;
            }
            index--;
        }
        data.putInt(RETURN_INDEX, index);

        boolean headingHome = index < 0;
        if (headingHome) {
            if (!level.hasChunkAt(home)) {
                // Wait for the home chunk to load naturally; keep the route and do not force-load it.
                clearReturnTarget(villager, data);
                return;
            }
            if (villager.blockPosition().distSqr(home) <= ARRIVAL_DISTANCE_SQUARED) {
                finishReturn(villager, data);
                return;
            }
            target = home.immutable();
        }

        long lastPosition = data.getLong(RETURN_LAST_POS);
        if (villager.blockPosition().distSqr(BlockPos.of(lastPosition)) >= 1.0D) {
            data.putLong(RETURN_LAST_POS, villager.blockPosition().asLong());
            data.putLong(RETURN_LAST_PROGRESS, now);
            data.putInt(HOME_STALLS, 0);
            data.remove(HOME_RETRY_AT);
        } else if (now - data.getLong(RETURN_LAST_PROGRESS) >= WAYPOINT_STALL_TICKS) {
            clearReturnTarget(villager, data);
            data.putLong(RETURN_LAST_POS, villager.blockPosition().asLong());
            data.putLong(RETURN_LAST_PROGRESS, now);
            data.putLong(RETURN_LAST_ORDER, 0L);
            if (headingHome) {
                int stalls = Math.min(MAX_HOME_RETURN_STALLS, data.getInt(HOME_STALLS) + 1);
                data.putInt(HOME_STALLS, stalls);
                if (stalls >= MAX_HOME_RETURN_STALLS) {
                    // Stop issuing navigation after a few failed attempts, but keep the NBT trail.
                    // Moving the villager or changing HOME lets a later tick resume the saved route.
                    data.putBoolean(HOME_PAUSED, true);
                    data.putLong(HOME_PAUSED_AT, villager.blockPosition().asLong());
                    data.putLong(HOME_PAUSED_HOME, home.asLong());
                    data.remove(HOME_RETRY_AT);
                } else {
                    data.putLong(HOME_RETRY_AT, now + HOME_RETRY_BASE_TICKS * stalls);
                }
            } else {
                data.putInt(RETURN_INDEX, index - 1);
            }
            return;
        }

        BlockPos routeTarget = target.immutable();
        Optional<WalkTarget> current = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        boolean sameTarget = current.map(walk -> walk.getTarget().currentBlockPosition().equals(routeTarget)).orElse(false);
        boolean retry = now - data.getLong(RETURN_LAST_ORDER) >= ROUTE_REISSUE_TICKS;
        if (!sameTarget && (current.isEmpty() || retry) || sameTarget && retry) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(routeTarget, WALK_SPEED, 1));
            data.putLong(RETURN_TARGET, routeTarget.asLong());
            data.putLong(RETURN_LAST_ORDER, now);
        }
    }

    private static void clearReturnTarget(Villager villager, CompoundTag data) {
        if (data.contains(RETURN_TARGET)) {
            BlockPos owned = BlockPos.of(data.getLong(RETURN_TARGET));
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(owned)) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            });
            data.remove(RETURN_TARGET);
        }
    }

    private static void finishReturn(Villager villager, CompoundTag data) {
        clearReturnTarget(villager, data);
        data.putBoolean(RETURNING, false);
        data.remove(TRAIL);
        data.remove(LAST_WAYPOINT);
        data.remove(RETURN_INDEX);
        data.remove(RETURN_TARGET);
        data.remove(RETURN_LAST_POS);
        data.remove(RETURN_LAST_PROGRESS);
        data.remove(RETURN_LAST_ORDER);
        data.remove(HOME_STALLS);
        data.remove(HOME_RETRY_AT);
        data.remove(HOME_PAUSED);
        data.remove(HOME_PAUSED_AT);
        data.remove(HOME_PAUSED_HOME);
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }
}
