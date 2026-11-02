package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Lumber work for unemployed adult villagers. Uses the normal villager navigation system,
 * a real ray trace, a reach limit and visible break progress. Leaves that block the view
 * of the selected trunk are cleared first instead of allowing remote block breaking.
 */
public final class VillagerTreeWork {
    private static final String P = "livingvillages_treework_";
    private static final String ACTIVE = P + "active";
    private static final String LOG_X = P + "log_x", LOG_Y = P + "log_y", LOG_Z = P + "log_z";
    private static final String ROUTE_X = P + "route_x", ROUTE_Y = P + "route_y", ROUTE_Z = P + "route_z";
    private static final String HAS_ROUTE = P + "has_route";
    private static final String STARTED_AT = P + "started_at", LAST_ORDER = P + "last_order";
    private static final String NEXT_SEARCH = P + "next_search";
    private static final String PROGRESS_X = P + "progress_x", PROGRESS_Y = P + "progress_y", PROGRESS_Z = P + "progress_z";
    private static final String PROGRESS_KIND = P + "progress_kind", PROGRESS = P + "progress";
    private static final String TREE_BASE_X = P + "tree_base_x", TREE_BASE_Y = P + "tree_base_y", TREE_BASE_Z = P + "tree_base_z";
    private static final String TREE_SAPLING_KEY = P + "tree_sapling_key";

    private static final String REPLANT_ACTIVE = P + "replant_active";
    private static final String REPLANT_SOURCE_X = P + "replant_source_x", REPLANT_SOURCE_Y = P + "replant_source_y", REPLANT_SOURCE_Z = P + "replant_source_z";
    private static final String REPLANT_SAPLING_KEY = P + "replant_sapling_key";
    private static final String REPLANT_STARTED_AT = P + "replant_started_at", REPLANT_NEXT_ATTEMPT = P + "replant_next_attempt";
    private static final String REPLANT_SITE_SET = P + "replant_site_set";
    private static final String REPLANT_SITE_X = P + "replant_site_x", REPLANT_SITE_Y = P + "replant_site_y", REPLANT_SITE_Z = P + "replant_site_z";
    private static final String REPLANT_FETCH_ACTIVE = P + "replant_fetch_active";
    private static final String REPLANT_FETCH_X = P + "replant_fetch_x", REPLANT_FETCH_Y = P + "replant_fetch_y", REPLANT_FETCH_Z = P + "replant_fetch_z";

    private static final int TICK_STEP = 5;
    private static final int LOG_CHOP_STEPS = 12; // roughly 3 seconds
    private static final int LEAF_CLEAR_STEPS = 4; // roughly 1 second
    private static final int SEARCH_RETRY_TICKS = 1200;
    private static final int SUCCESS_RETRY_TICKS = 100;
    private static final int ROUTE_RETRY_TICKS = 60;
    private static final int TASK_TIMEOUT_TICKS = 2400;
    private static final long REPLANT_RETRY_TICKS = 100L;
    private static final double BREAK_REACH_SQR = 4.0D * 4.0D;
    private static final float WALK_SPEED = 0.55F;

    private VillagerTreeWork() {}

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()
            || !villager.isAlive() || villager.isBaby() || villager.tickCount % TICK_STEP != 0) return;

        CompoundTag data = villager.getPersistentData();
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_VILLAGER_TREE_WORK.get()) {
            if (data.getBoolean(ACTIVE)) cancelTask(villager, data);
            if (data.getBoolean(REPLANT_ACTIVE)) cancelReplantTask(villager, data);
            return;
        }
        if (!(villager.level() instanceof ServerLevel level)) return;

        long now = level.getGameTime();
        if (!LivingVillagesConfig.ENABLE_VILLAGER_REPLANTING.get() && data.getBoolean(REPLANT_ACTIVE)) {
            cancelReplantTask(villager, data);
            return;
        }

        // A pending replant job is kept across nights and temporary mobGriefing changes.
        // It does not abandon a felled tree just because the villager has to wait.
        if (data.getBoolean(REPLANT_ACTIVE)) {
            if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
                clearOwnedRoute(villager, data);
                return;
            }
            long replantTime = Math.floorMod(level.getDayTime(), 24000L);
            if (villager.isSleeping() || replantTime < 1000L || replantTime >= 12000L) {
                clearOwnedRoute(villager, data);
                return;
            }
            continueReplantTask(villager, level, data, now);
            return;
        }

        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            if (data.getBoolean(ACTIVE)) cancelTask(villager, data);
            data.putLong(NEXT_SEARCH, now + 200L);
            return;
        }
        long timeOfDay = Math.floorMod(level.getDayTime(), 24000L);
        if (villager.isSleeping() || timeOfDay < 1000L || timeOfDay >= 12000L) {
            if (data.getBoolean(ACTIVE)) {
                cancelTask(villager, data);
                data.putLong(NEXT_SEARCH, now + 200L);
            }
            return;
        }

        if (data.getBoolean(ACTIVE)) {
            continueTask(villager, level, data, now);
            return;
        }
        if (now < data.getLong(NEXT_SEARCH) || !isAvailableWorker(villager)) return;

        // Do not interrupt vanilla Brain tasks or the existing farmer storage trip.
        if (data.getBoolean("livingvillages_autonomy_target_active")
            || villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
            data.putLong(NEXT_SEARCH, now + 100L);
            return;
        }

        BlockPos log = findNearestTreeLog(level, villager);
        if (log == null) {
            data.putLong(NEXT_SEARCH, now + SEARCH_RETRY_TICKS);
            return;
        }
        // Use a real axe if the worker carries one or can withdraw one from nearby storage.
        // Bare-hand logging remains possible as the bootstrap path for a new village.
        ensureAxe(villager, level);
        data.putBoolean(ACTIVE, true);
        data.putInt(LOG_X, log.getX());
        data.putInt(LOG_Y, log.getY());
        data.putInt(LOG_Z, log.getZ());
        BlockPos treeBase = findTreeBase(level, log);
        data.putInt(TREE_BASE_X, treeBase.getX());
        data.putInt(TREE_BASE_Y, treeBase.getY());
        data.putInt(TREE_BASE_Z, treeBase.getZ());
        Block matchingSapling = saplingForLog(level.getBlockState(log).getBlock());
        if (matchingSapling != null) {
            data.putString(TREE_SAPLING_KEY, BuiltInRegistries.BLOCK.getKey(matchingSapling).toString());
        } else {
            data.remove(TREE_SAPLING_KEY);
        }
        data.putLong(STARTED_AT, now);
        data.putLong(LAST_ORDER, now); // give any vanilla route a short window before taking over
        resetProgress(data);
    }

    private static boolean isAvailableWorker(Villager villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        // Avoid interrupting villagers with a working profession in this first lumbering milestone.
        String role = villager.getPersistentData().getString(VillagerRoles.ROLE_KEY);
        CompoundTag data = villager.getPersistentData();
        boolean roleAllowsForestWork = role.isEmpty()
            && !data.getBoolean(VillagePopulation.ACTIVE_KEY)
            && !data.getBoolean(VillageBuilder.ACTIVE_KEY)
            && !data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
            && !data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            && !data.getBoolean("livingvillages_logistics_active")
            && !data.getBoolean("livingvillages_mining_active")
            && !data.getBoolean("livingvillages_defense_has_route");
        boolean canUseForestJob = (profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT)
            && roleAllowsForestWork
            && !(LivingVillagesConfig.ENABLE_MINER_ROLE.get() && VillagerRoles.isMiner(villager))
            && !(LivingVillagesConfig.ENABLE_VILLAGE_GUARDS.get() && VillagerRoles.isGuard(villager))
            && !VillagerRoles.isCraftsman(villager);
        return canUseForestJob;
    }

    private static void continueTask(Villager villager, ServerLevel level, CompoundTag data, long now) {
        if (now - data.getLong(STARTED_AT) > TASK_TIMEOUT_TICKS) {
            cancelTask(villager, data);
            data.putLong(NEXT_SEARCH, now + SEARCH_RETRY_TICKS);
            return;
        }

        BlockPos log = targetLog(data);
        if (!level.hasChunkAt(log) || !level.getBlockState(log).is(BlockTags.LOGS)) {
            cancelTask(villager, data);
            data.putLong(NEXT_SEARCH, now + SUCCESS_RETRY_TICKS);
            return;
        }

        BlockPos obstruction = firstBlockOnSightLine(villager, level, log);
        if (obstruction != null && !obstruction.equals(log)) {
            BlockState obstructingState = level.getBlockState(obstruction);
            if (obstructingState.is(BlockTags.LEAVES)) {
                if (withinReach(villager, obstruction) && canSeeBlock(villager, level, obstruction)) {
                    progressBreaking(villager, level, data, obstruction, true);
                } else {
                    routeToWorkPosition(villager, level, data, obstruction, now);
                }
                return;
            }
            // If another trunk log blocks the ray, chop that block first.
            if (obstructingState.is(BlockTags.LOGS) && obstruction.distSqr(log) <= 36.0D) {
                setTargetLog(data, obstruction);
                log = obstruction;
                resetProgress(data);
            } else {
                routeToWorkPosition(villager, level, data, log, now);
                return;
            }
        }

        // Both physical reach and a clear ray are mandatory before chopping.
        if (!withinReach(villager, log) || !canSeeBlock(villager, level, log)) {
            routeToWorkPosition(villager, level, data, log, now);
            return;
        }
        progressBreaking(villager, level, data, log, false);
    }

    private static BlockPos findNearestTreeLog(ServerLevel level, Villager villager) {
        int radius = LivingVillagesConfig.TREE_SEARCH_RADIUS_BLOCKS.get();
        BlockPos origin = villager.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) > radius) continue;
                for (int dy = -2; dy <= 8; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.is(BlockTags.LOGS) && state.getDestroySpeed(level, pos) >= 0.0F) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> villager.distanceToSqr(Vec3.atCenterOf(pos))));
        List<BlockPos> reservedTargets = getReservedTreeTargets(level, villager, radius);
        for (BlockPos candidate : candidates) {
            // Do not send several villagers to the same trunk or neighboring blocks of one tree.
            boolean reserved = reservedTargets.stream().anyMatch(target -> sameTreeWorkArea(target, candidate));
            if (!reserved && isTreeLog(level, candidate)) return candidate;
        }
        return null;
    }

    private static List<BlockPos> getReservedTreeTargets(ServerLevel level, Villager worker, int radius) {
        List<BlockPos> targets = new ArrayList<>();
        AABB area = new AABB(worker.blockPosition()).inflate(radius + 4.0D, 12.0D, radius + 4.0D);
        for (Villager other : level.getEntitiesOfClass(Villager.class, area,
            candidate -> candidate != worker && candidate.isAlive())) {
            CompoundTag otherData = other.getPersistentData();
            if (!otherData.getBoolean(ACTIVE) || !otherData.contains(LOG_X)
                || !otherData.contains(LOG_Y) || !otherData.contains(LOG_Z)) continue;
            targets.add(targetLog(otherData));
        }
        return targets;
    }

    private static boolean sameTreeWorkArea(BlockPos a, BlockPos b) {
        // Keep close-by workers from repeatedly targeting adjacent blocks of the same trunk.
        int horizontalX = a.getX() - b.getX();
        int horizontalZ = a.getZ() - b.getZ();
        int vertical = Math.abs(a.getY() - b.getY());
        return horizontalX * horizontalX + horizontalZ * horizontalZ <= 16 && vertical <= 8;
    }

    /** Requires a small cluster of logs and nearby leaves so isolated logs/building beams are usually ignored. */
    private static boolean isTreeLog(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(BlockTags.LOGS)) return false;
        int logs = 0;
        for (int dx = -2; dx <= 2 && logs < 3; dx++) {
            for (int dz = -2; dz <= 2 && logs < 3; dz++) {
                for (int dy = -2; dy <= 4 && logs < 3; dy++) {
                    BlockPos check = pos.offset(dx, dy, dz);
                    if (level.hasChunkAt(check) && level.getBlockState(check).is(BlockTags.LOGS)) logs++;
                }
            }
        }
        if (logs < 3) return false;
        int leaves = 0;
        for (int dx = -4; dx <= 4 && leaves < 3; dx++) {
            for (int dz = -4; dz <= 4 && leaves < 3; dz++) {
                for (int dy = -2; dy <= 6 && leaves < 3; dy++) {
                    BlockPos check = pos.offset(dx, dy, dz);
                    if (level.hasChunkAt(check) && level.getBlockState(check).is(BlockTags.LEAVES)) leaves++;
                }
            }
        }
        return leaves >= 3;
    }

    /**
     * After cutting a block, continue with another log close to the cut point. This lets a
     * villager work up a trunk one block at a time, without retargeting a distant tree.
     */
    private static BlockPos findNextLogInSameTree(ServerLevel level, BlockPos cutPosition, Villager villager) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 4; dy++) {
                    BlockPos pos = cutPosition.offset(dx, dy, dz);
                    if (pos.equals(cutPosition) || !level.hasChunkAt(pos)
                        || !level.getBlockState(pos).is(BlockTags.LOGS)) continue;
                    // Continue only through a physically connected log cluster. Nearby leaves alone
                    // must not make the worker switch to another tree in a dense forest.
                    if (hasNearbyLog(level, pos)) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> villager.distanceToSqr(Vec3.atCenterOf(pos))));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean hasNearbyLog(ServerLevel level, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos check = pos.offset(dx, dy, dz);
                    if (level.hasChunkAt(check) && level.getBlockState(check).is(BlockTags.LOGS)) return true;
                }
            }
        }
        return false;
    }


    /** The first colliding block between the villager's eyes and the desired block. */
    private static BlockPos firstBlockOnSightLine(Villager villager, ServerLevel level, BlockPos target) {
        BlockHitResult hit = level.clip(new ClipContext(villager.getEyePosition(), Vec3.atCenterOf(target),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, villager));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target)) return null;
        return hit.getBlockPos().immutable();
    }

    private static boolean canSeeBlock(Villager villager, ServerLevel level, BlockPos target) {
        BlockHitResult hit = level.clip(new ClipContext(villager.getEyePosition(), Vec3.atCenterOf(target),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, villager));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    private static boolean withinReach(Villager villager, BlockPos pos) {
        return villager.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= BREAK_REACH_SQR;
    }

    private static void routeToWorkPosition(Villager villager, ServerLevel level, CompoundTag data,
                                            BlockPos target, long now) {
        BlockPos stand = findWorkPosition(level, villager, target);
        if (stand == null) {
            cancelTask(villager, data);
            data.putLong(NEXT_SEARCH, now + SEARCH_RETRY_TICKS);
            return;
        }
        Optional<WalkTarget> current = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        boolean sameRoute = current.map(walk -> walk.getTarget().currentBlockPosition().equals(stand)).orElse(false);
        if (sameRoute) return;
        boolean noRoute = current.isEmpty();
        boolean routeRetryAllowed = now - data.getLong(LAST_ORDER) >= ROUTE_RETRY_TICKS;
        if (!noRoute && !routeRetryAllowed) return;

        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
        data.putBoolean(HAS_ROUTE, true);
        data.putInt(ROUTE_X, stand.getX());
        data.putInt(ROUTE_Y, stand.getY());
        data.putInt(ROUTE_Z, stand.getZ());
        data.putLong(LAST_ORDER, now);
    }

    /** Picks a safe position where a villager can see and reach the block as a player would. */
    private static BlockPos findWorkPosition(ServerLevel level, Villager villager, BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -3; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos stand = target.offset(dx, dy, dz);
                    if (!isSafeStand(level, stand)) continue;
                    Vec3 eyes = new Vec3(stand.getX() + 0.5D, stand.getY() + villager.getEyeHeight(), stand.getZ() + 0.5D);
                    if (eyes.distanceToSqr(Vec3.atCenterOf(target)) > BREAK_REACH_SQR) continue;
                    BlockHitResult hit = level.clip(new ClipContext(eyes, Vec3.atCenterOf(target),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, villager));
                    if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(target)) continue;
                    double distance = villager.distanceToSqr(Vec3.atCenterOf(stand));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = stand.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above()) || !level.hasChunkAt(feet.below())) return false;
        return level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
            && level.getBlockState(feet.below()).blocksMotion();
    }

    private static ItemStack ensureAxe(Villager villager, ServerLevel level) {
        ItemStack held = villager.getItemBySlot(EquipmentSlot.MAINHAND);
        if (isAxe(held)) return held;

        var inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack found = inventory.getItem(slot);
            if (!isAxe(found)) continue;
            if (!held.isEmpty()) {
                ItemStack remainder = inventory.addItem(held.copy());
                if (!remainder.isEmpty()) return ItemStack.EMPTY;
            }
            ItemStack axe = found.copy();
            found.shrink(1);
            if (found.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            inventory.setChanged();
            villager.setItemSlot(EquipmentSlot.MAINHAND, axe);
            return villager.getItemBySlot(EquipmentSlot.MAINHAND);
        }

        if (!held.isEmpty()) return ItemStack.EMPTY;
        int radius = Math.min(12, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        BlockPos origin = villager.blockPosition();
        BlockPos ownershipCenter = VillagerRoles.getHome(villager);
        if (ownershipCenter == null) ownershipCenter = origin;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorageBlock(level.getBlockState(pos))
                        || !VillageRegistry.isStorageForVillage(level, ownershipCenter, pos)
                        || !VillageReservations.isStorageAvailable(level, pos, villager)
                        || !(level.getBlockEntity(pos) instanceof Container container)) continue;
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        ItemStack stack = container.getItem(slot);
                        if (!isAxe(stack)) continue;
                        ItemStack axe = stack.split(1);
                        if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
                        container.setChanged();
                        villager.setItemSlot(EquipmentSlot.MAINHAND, axe);
                        return villager.getItemBySlot(EquipmentSlot.MAINHAND);
                    }
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isAxe(ItemStack stack) {
        return stack.is(Items.WOODEN_AXE) || stack.is(Items.STONE_AXE) || stack.is(Items.IRON_AXE)
            || stack.is(Items.GOLDEN_AXE) || stack.is(Items.DIAMOND_AXE) || stack.is(Items.NETHERITE_AXE);
    }

    private static boolean isStorageBlock(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static void progressBreaking(Villager villager, ServerLevel level, CompoundTag data,
                                         BlockPos target, boolean leaves) {
        BlockState state = level.getBlockState(target);
        if (leaves ? !state.is(BlockTags.LEAVES) : !state.is(BlockTags.LOGS)) {
            resetProgress(data);
            return;
        }
        if (!withinReach(villager, target) || !canSeeBlock(villager, level, target)) {
            resetProgress(data);
            return;
        }

        int kind = leaves ? 1 : 2;
        boolean sameBlock = data.getInt(PROGRESS_X) == target.getX() && data.getInt(PROGRESS_Y) == target.getY()
            && data.getInt(PROGRESS_Z) == target.getZ() && data.getInt(PROGRESS_KIND) == kind;
        if (!sameBlock) {
            data.putInt(PROGRESS_X, target.getX());
            data.putInt(PROGRESS_Y, target.getY());
            data.putInt(PROGRESS_Z, target.getZ());
            data.putInt(PROGRESS_KIND, kind);
            data.putInt(PROGRESS, 0);
        }
        villager.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
        int progress = data.getInt(PROGRESS) + 1;
        data.putInt(PROGRESS, progress);
        ItemStack tool = villager.getItemBySlot(EquipmentSlot.MAINHAND);
        float hardness = state.getDestroySpeed(level, target);
        float speed = Math.max(1.0F, tool.getDestroySpeed(state));
        int required = Math.max(leaves ? LEAF_CLEAR_STEPS : LOG_CHOP_STEPS,
            Math.max(1, (int) Math.ceil(30.0D * Math.max(0.1F, hardness) / speed / TICK_STEP)));
        level.destroyBlockProgress(-villager.getId(), target, Math.min(9, (progress * 10) / required));
        if (progress % 2 == 0) {
            level.sendParticles(ParticleTypes.CRIT, target.getX() + 0.5D, target.getY() + 0.5D,
                target.getZ() + 0.5D, 2, 0.12D, 0.12D, 0.12D, 0.01D);
        }
        if (progress >= required) {
            removeBlockAndCollectDrops(villager, level, target, state, tool);
            level.destroyBlockProgress(-villager.getId(), target, -1);
            resetProgress(data);
            if (!leaves) {
                BlockPos nextLog = findNextLogInSameTree(level, target, villager);
                if (nextLog != null) {
                    clearOwnedRoute(villager, data);
                    setTargetLog(data, nextLog);
                    data.putLong(STARTED_AT, level.getGameTime());
                } else {
                    BlockPos treeBase = data.contains(TREE_BASE_X)
                        ? new BlockPos(data.getInt(TREE_BASE_X), data.getInt(TREE_BASE_Y), data.getInt(TREE_BASE_Z))
                        : target;
                    String saplingKey = data.getString(TREE_SAPLING_KEY);
                    if (LivingVillagesConfig.ENABLE_VILLAGER_REPLANTING.get()) {
                        beginReplantTask(villager, level, data, treeBase, saplingKey);
                    } else {
                        cancelTask(villager, data);
                        data.putLong(NEXT_SEARCH, level.getGameTime() + SUCCESS_RETRY_TICKS);
                    }
                }
            }
        }
    }

    /** Finds the bottom of the trunk column so replanting happens near ground level. */
    private static BlockPos findTreeBase(ServerLevel level, BlockPos log) {
        BlockPos base = log.immutable();
        // Some trees have branches or 2x2 trunks. Search a small footprint below the selected
        // log so the replant task stays anchored near the ground, not at the last branch cut.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int depth = 0; depth <= 16; depth++) {
                    BlockPos candidate = log.offset(dx, -depth, dz);
                    if (!level.hasChunkAt(candidate)) continue;
                    if (level.getBlockState(candidate).is(BlockTags.LOGS) && candidate.getY() < base.getY()) {
                        base = candidate.immutable();
                    }
                }
            }
        }
        return base;
    }

    /** Map vanilla log types to their matching sapling for the forest-stewardship fallback. */
    private static Block saplingForLog(Block logBlock) {
        String path = BuiltInRegistries.BLOCK.getKey(logBlock).getPath();
        if (path.contains("dark_oak")) return Blocks.DARK_OAK_SAPLING;
        if (path.contains("spruce")) return Blocks.SPRUCE_SAPLING;
        if (path.contains("birch")) return Blocks.BIRCH_SAPLING;
        if (path.contains("jungle")) return Blocks.JUNGLE_SAPLING;
        if (path.contains("acacia")) return Blocks.ACACIA_SAPLING;
        if (path.contains("mangrove")) return Blocks.MANGROVE_PROPAGULE;
        if (path.contains("cherry")) return Blocks.CHERRY_SAPLING;
        if (path.contains("oak")) return Blocks.OAK_SAPLING;
        return null;
    }

    private static void beginReplantTask(Villager villager, ServerLevel level, CompoundTag data,
                                         BlockPos treeBase, String saplingKey) {
        // Preserve the tree-specific information before clearing the chopping task.
        cancelTask(villager, data);
        data.putBoolean(REPLANT_ACTIVE, true);
        data.putInt(REPLANT_SOURCE_X, treeBase.getX());
        data.putInt(REPLANT_SOURCE_Y, treeBase.getY());
        data.putInt(REPLANT_SOURCE_Z, treeBase.getZ());
        data.putString(REPLANT_SAPLING_KEY, saplingKey == null ? "" : saplingKey);
        data.putLong(REPLANT_STARTED_AT, level.getGameTime());
        data.putLong(REPLANT_NEXT_ATTEMPT, 0L);
        data.putBoolean(REPLANT_SITE_SET, false);
        data.putBoolean(REPLANT_FETCH_ACTIVE, false);
    }

    private static void continueReplantTask(Villager villager, ServerLevel level, CompoundTag data, long now) {
        // A tree felled by this worker is not considered a completed job until a real sapling
        // has been planted. If none exists yet, the worker waits instead of abandoning the task
        // and continuing to remove more trees. This makes scarcity visible rather than magical.
        long started = data.getLong(REPLANT_STARTED_AT);
        BlockPos source = new BlockPos(data.getInt(REPLANT_SOURCE_X), data.getInt(REPLANT_SOURCE_Y),
            data.getInt(REPLANT_SOURCE_Z));
        if (!level.hasChunkAt(source)) {
            clearOwnedRoute(villager, data);
            return;
        }

        Block expectedSapling = resolveExpectedSapling(data);
        boolean allowAnySpecies = expectedSapling == null || now - started >= 400L;
        int saplingSlot = findSaplingSlot(villager, expectedSapling, allowAnySpecies);

        // First use a real sapling already carried by the worker, then a real leaf-drop entity.
        // If neither exists, walk to a real local storage block and withdraw one actual sapling.
        if (saplingSlot < 0) {
            ItemEntity droppedSapling = findNearbySaplingDrop(level, source, expectedSapling, allowAnySpecies);
            if (droppedSapling != null) {
                if (villager.distanceToSqr(droppedSapling) <= 4.0D) {
                    if (collectOneSapling(villager, droppedSapling)) {
                        clearOwnedRoute(villager, data);
                        data.putLong(REPLANT_NEXT_ATTEMPT, now + 1L);
                        return;
                    }
                } else {
                    routeToWorkPosition(villager, level, data, droppedSapling.blockPosition(), now);
                    return;
                }
            }
            if (tryFetchSaplingFromStorage(villager, level, data, source, expectedSapling, allowAnySpecies, now)) return;
            clearOwnedRoute(villager, data);
            data.putLong(REPLANT_NEXT_ATTEMPT, now + REPLANT_RETRY_TICKS);
            return;
        }
        if (now < data.getLong(REPLANT_NEXT_ATTEMPT) && !data.getBoolean(REPLANT_SITE_SET)) return;

        Block saplingBlock = Block.byItem(villager.getInventory().getItem(saplingSlot).getItem());
        if (saplingBlock == Blocks.AIR || !saplingBlock.defaultBlockState().is(BlockTags.SAPLINGS)) {
            data.putBoolean(REPLANT_SITE_SET, false);
            data.putLong(REPLANT_NEXT_ATTEMPT, now + REPLANT_RETRY_TICKS);
            return;
        }
        BlockState saplingState = saplingBlock.defaultBlockState();

        BlockPos site = data.getBoolean(REPLANT_SITE_SET)
            ? new BlockPos(data.getInt(REPLANT_SITE_X), data.getInt(REPLANT_SITE_Y), data.getInt(REPLANT_SITE_Z))
            : null;
        if (site == null || !isSuitableReplantSite(level, site, saplingState)) {
            clearOwnedRoute(villager, data);
            site = findReplantSite(level, source, saplingState);
            if (site == null) {
                data.putBoolean(REPLANT_SITE_SET, false);
                data.putLong(REPLANT_NEXT_ATTEMPT, now + REPLANT_RETRY_TICKS);
                return;
            }
            data.putBoolean(REPLANT_SITE_SET, true);
            data.putInt(REPLANT_SITE_X, site.getX());
            data.putInt(REPLANT_SITE_Y, site.getY());
            data.putInt(REPLANT_SITE_Z, site.getZ());
        }

        if (!withinReach(villager, site) || !canSeeBlock(villager, level, site)) {
            routeToWorkPosition(villager, level, data, site, now);
            return;
        }

        // Revalidate on the server immediately before placing the sapling.
        if (!isSuitableReplantSite(level, site, saplingState)) {
            clearOwnedRoute(villager, data);
            data.putBoolean(REPLANT_SITE_SET, false);
            data.putLong(REPLANT_NEXT_ATTEMPT, now + REPLANT_RETRY_TICKS);
            return;
        }
        villager.getLookControl().setLookAt(site.getX() + 0.5D, site.getY() + 0.2D, site.getZ() + 0.5D);
        if (level.setBlock(site, saplingState, 3)) {
            var inventory = villager.getInventory();
            ItemStack seed = inventory.getItem(saplingSlot);
            seed.shrink(1);
            if (seed.isEmpty()) inventory.setItem(saplingSlot, ItemStack.EMPTY);
            inventory.setChanged();
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, site.getX() + 0.5D, site.getY() + 0.6D,
                site.getZ() + 0.5D, 4, 0.2D, 0.2D, 0.2D, 0.01D);
            cancelReplantTask(villager, data);
            data.putLong(NEXT_SEARCH, now + SUCCESS_RETRY_TICKS);
        }
    }

    /** Fetches a real sapling from a physical chest/barrel. The worker walks there first. */
    private static boolean tryFetchSaplingFromStorage(Villager villager, ServerLevel level, CompoundTag data,
                                                        BlockPos source, Block preferred, boolean allowAnySpecies, long now) {
        BlockPos storagePos;
        if (data.getBoolean(REPLANT_FETCH_ACTIVE)) {
            storagePos = new BlockPos(data.getInt(REPLANT_FETCH_X), data.getInt(REPLANT_FETCH_Y), data.getInt(REPLANT_FETCH_Z));
        } else {
            storagePos = findStorageWithSapling(villager, level, source, preferred, allowAnySpecies);
            if (storagePos == null) return false;
            data.putBoolean(REPLANT_FETCH_ACTIVE, true);
            data.putInt(REPLANT_FETCH_X, storagePos.getX());
            data.putInt(REPLANT_FETCH_Y, storagePos.getY());
            data.putInt(REPLANT_FETCH_Z, storagePos.getZ());
        }

        BlockPos ownershipCenter = VillagerRoles.getHome(villager);
        if (ownershipCenter == null) ownershipCenter = villager.blockPosition();
        if (!level.hasChunkAt(storagePos) || !isStorageBlock(level.getBlockState(storagePos))
            || !VillageRegistry.isStorageForVillage(level, ownershipCenter, storagePos)
            || !VillageReservations.isStorageAvailable(level, storagePos, villager)
            || !(level.getBlockEntity(storagePos) instanceof Container container)) {
            clearSaplingFetch(data);
            return false;
        }

        if (!withinReach(villager, storagePos) || !canSeeBlock(villager, level, storagePos)) {
            routeToWorkPosition(villager, level, data, storagePos, now);
            return true;
        }

        int slot = findSaplingSlot(container, preferred, allowAnySpecies);
        if (slot < 0) {
            clearOwnedRoute(villager, data);
            clearSaplingFetch(data);
            return false;
        }
        ItemStack stored = container.getItem(slot);
        ItemStack one = stored.copy();
        one.setCount(1);
        if (!addOneToVillagerInventory(villager, one)) {
            clearOwnedRoute(villager, data);
            data.putLong(REPLANT_NEXT_ATTEMPT, now + REPLANT_RETRY_TICKS);
            return true;
        }
        stored.shrink(1);
        if (stored.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
        container.setChanged();
        clearOwnedRoute(villager, data);
        clearSaplingFetch(data);
        data.putLong(REPLANT_NEXT_ATTEMPT, now + 1L);
        return true;
    }

    private static BlockPos findStorageWithSapling(Villager villager, ServerLevel level, BlockPos source, Block preferred, boolean allowAnySpecies) {
        int radius = Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        BlockPos ownershipCenter = VillagerRoles.getHome(villager);
        if (ownershipCenter == null) ownershipCenter = villager.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = source.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorageBlock(level.getBlockState(pos))
                        || !VillageRegistry.isStorageForVillage(level, ownershipCenter, pos)
                        || !VillageReservations.isStorageAvailable(level, pos, villager)) continue;
                    if (!(level.getBlockEntity(pos) instanceof Container container)
                        || findSaplingSlot(container, preferred, allowAnySpecies) < 0) continue;
                    double distance = pos.distSqr(source);
                    if (distance < bestDistance) { bestDistance = distance; best = pos.immutable(); }
                }
            }
        }
        return best;
    }

    private static int findSaplingSlot(Container container, Block preferred, boolean allowAnySpecies) {
        int any = -1;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!isSaplingStack(stack)) continue;
            Block sapling = Block.byItem(stack.getItem());
            if (preferred == null || sapling == preferred) return i;
            if (any < 0) any = i;
        }
        return allowAnySpecies ? any : -1;
    }


    private static void clearSaplingFetch(CompoundTag data) {
        data.putBoolean(REPLANT_FETCH_ACTIVE, false);
        data.remove(REPLANT_FETCH_X); data.remove(REPLANT_FETCH_Y); data.remove(REPLANT_FETCH_Z);
    }

    private static Block resolveExpectedSapling(CompoundTag data) {
        String key = data.getString(REPLANT_SAPLING_KEY);
        if (key.isEmpty()) return null;
        var resourceLocation = net.minecraft.resources.ResourceLocation.tryParse(key);
        if (resourceLocation == null) return null;
        return BuiltInRegistries.BLOCK.getOptional(resourceLocation).orElse(null);
    }

    private static int findSaplingSlot(Villager villager, Block preferred, boolean allowAnySpecies) {
        var inventory = villager.getInventory();
        int firstAny = -1;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isSaplingStack(stack)) continue;
            Block sapling = Block.byItem(stack.getItem());
            if (preferred != null && sapling == preferred) return slot;
            if (firstAny < 0) firstAny = slot;
        }
        return allowAnySpecies ? firstAny : -1;
    }

    private static boolean isSaplingStack(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Block block = Block.byItem(stack.getItem());
        return block != Blocks.AIR && block.defaultBlockState().is(BlockTags.SAPLINGS);
    }

    private static ItemEntity findNearbySaplingDrop(ServerLevel level, BlockPos source, Block preferred,
                                                     boolean allowAnySpecies) {
        AABB area = new AABB(source).inflate(8.0D, 4.0D, 8.0D);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, area,
            entity -> entity.isAlive() && isSaplingStack(entity.getItem()));
        drops.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(Vec3.atCenterOf(source))));
        ItemEntity any = null;
        for (ItemEntity drop : drops) {
            Block sapling = Block.byItem(drop.getItem().getItem());
            if (preferred == null || sapling == preferred) return drop;
            if (any == null) any = drop;
        }
        return allowAnySpecies ? any : null;
    }

    private static boolean collectOneSapling(Villager villager, ItemEntity droppedSapling) {
        ItemStack dropped = droppedSapling.getItem();
        if (!isSaplingStack(dropped)) return false;
        ItemStack one = dropped.copy();
        one.setCount(1);
        if (!addOneToVillagerInventory(villager, one)) return false;
        dropped.shrink(1);
        if (dropped.isEmpty()) droppedSapling.discard();
        return true;
    }

    private static boolean addOneToVillagerInventory(Villager villager, ItemStack one) {
        var inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, one)) continue;
            int max = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize());
            if (existing.getCount() < max) {
                existing.grow(1);
                inventory.setChanged();
                return true;
            }
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, one.copy());
                inventory.setChanged();
                return true;
            }
        }
        return false;
    }

    private static BlockPos findReplantSite(ServerLevel level, BlockPos source, BlockState saplingState) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 2; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = source.getX() + dx;
                    int z = source.getZ() + dz;
                    if (!level.hasChunkAt(new BlockPos(x, source.getY(), z))) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (Math.abs(candidate.getY() - source.getY()) > 4) continue;
                    if (isSuitableReplantSite(level, candidate, saplingState)) candidates.add(candidate);
                }
            }
            if (!candidates.isEmpty()) break;
        }
        candidates.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(Vec3.atCenterOf(source))));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean isSuitableReplantSite(ServerLevel level, BlockPos pos, BlockState saplingState) {
        if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.below()) || !level.hasChunkAt(pos.above(4))) return false;
        if (!level.getBlockState(pos).isAir() || !saplingState.canSurvive(level, pos)) return false;
        // Leave growing room above the sapling; a single air block is not enough for a viable tree.
        for (int dy = 1; dy <= 4; dy++) {
            if (!level.getBlockState(pos.above(dy)).isAir()) return false;
        }
        if (level.getMaxLocalRawBrightness(pos) < 9) return false;
        // Avoid crowding an existing trunk/sapling, so the new tree has a chance to mature.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 5; dy++) {
                    BlockPos check = pos.offset(dx, dy, dz);
                    if (check.equals(pos) || !level.hasChunkAt(check)) continue;
                    BlockState state = level.getBlockState(check);
                    if (state.is(BlockTags.LOGS) || state.is(BlockTags.SAPLINGS)) return false;
                }
            }
        }
        return true;
    }

    private static void cancelReplantTask(Villager villager, CompoundTag data) {
        clearOwnedRoute(villager, data);
        data.putBoolean(REPLANT_ACTIVE, false);
        data.remove(REPLANT_SOURCE_X); data.remove(REPLANT_SOURCE_Y); data.remove(REPLANT_SOURCE_Z);
        data.remove(REPLANT_SAPLING_KEY); data.remove(REPLANT_STARTED_AT); data.remove(REPLANT_NEXT_ATTEMPT);
        data.remove(REPLANT_SITE_X); data.remove(REPLANT_SITE_Y); data.remove(REPLANT_SITE_Z);
        data.remove(REPLANT_SITE_SET); data.remove(REPLANT_FETCH_ACTIVE);
        data.remove(REPLANT_FETCH_X); data.remove(REPLANT_FETCH_Y); data.remove(REPLANT_FETCH_Z);
    }

    private static void removeBlockAndCollectDrops(Villager villager, ServerLevel level, BlockPos pos,
                                                   BlockState state, ItemStack tool) {
        List<ItemStack> drops = List.of();
        if (level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), villager, tool);
        }
        if (!level.destroyBlock(pos, false, villager)) return;
        for (ItemStack drop : drops) storeOrDrop(villager, level, pos, drop.copy());
        if (state.is(BlockTags.LOGS) && isAxe(tool)) {
            tool.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        }
    }

    private static void storeOrDrop(Villager villager, ServerLevel level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) return;
        var inventory = villager.getInventory();
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            for (int slot = 0; slot < inventory.getContainerSize() && !stack.isEmpty(); slot++) {
                ItemStack existing = inventory.getItem(slot);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) continue;
                    int room = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize()) - existing.getCount();
                    if (room > 0) {
                        int moved = Math.min(room, stack.getCount());
                        existing.grow(moved);
                        stack.shrink(moved);
                    }
                } else {
                    if (!existing.isEmpty()) continue;
                    int moved = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize()));
                    inventory.setItem(slot, stack.split(moved));
                }
            }
        }
        inventory.setChanged();
        if (!stack.isEmpty()) Block.popResource(level, pos, stack);
    }

    private static BlockPos targetLog(CompoundTag data) {
        return new BlockPos(data.getInt(LOG_X), data.getInt(LOG_Y), data.getInt(LOG_Z));
    }

    private static void setTargetLog(CompoundTag data, BlockPos pos) {
        data.putInt(LOG_X, pos.getX());
        data.putInt(LOG_Y, pos.getY());
        data.putInt(LOG_Z, pos.getZ());
    }

    private static void cancelTask(Villager villager, CompoundTag data) {
        clearOwnedRoute(villager, data);
        if (villager.level() instanceof ServerLevel level && data.contains(PROGRESS_X)) {
            level.destroyBlockProgress(-villager.getId(),
                new BlockPos(data.getInt(PROGRESS_X), data.getInt(PROGRESS_Y), data.getInt(PROGRESS_Z)), -1);
        }
        data.putBoolean(ACTIVE, false);
        data.putBoolean(HAS_ROUTE, false);
        data.remove(LOG_X); data.remove(LOG_Y); data.remove(LOG_Z);
        data.remove(ROUTE_X); data.remove(ROUTE_Y); data.remove(ROUTE_Z);
        data.remove(STARTED_AT); data.remove(LAST_ORDER);
        data.remove(TREE_BASE_X); data.remove(TREE_BASE_Y); data.remove(TREE_BASE_Z); data.remove(TREE_SAPLING_KEY);
        resetProgress(data);
    }

    private static void clearOwnedRoute(Villager villager, CompoundTag data) {
        if (data.getBoolean(HAS_ROUTE)) {
            BlockPos ownedRoute = new BlockPos(data.getInt(ROUTE_X), data.getInt(ROUTE_Y), data.getInt(ROUTE_Z));
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(ownedRoute)) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            });
        }
        data.putBoolean(HAS_ROUTE, false);
        data.remove(ROUTE_X); data.remove(ROUTE_Y); data.remove(ROUTE_Z); data.remove(LAST_ORDER);
    }

    private static void resetProgress(CompoundTag data) {
        data.remove(PROGRESS_X); data.remove(PROGRESS_Y); data.remove(PROGRESS_Z); data.remove(PROGRESS_KIND);
        data.putInt(PROGRESS, 0);
    }
}
