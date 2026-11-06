package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A cautious miner behavior for villagers assigned the persistent "miner" role.
 * Miners equip a real pickaxe from their inventory or local storage, navigate toward
 * blocks, ray-trace the line of sight, and only break blocks when within normal reach.
 * Exposed ores have priority. When no visible ore is available and the real cobblestone stock
 * is low, the miner may quarry an exposed stone block to support the settlement's tool/furnace chain.
 * It never x-rays hidden ores or intentionally digs under its own feet.
 */
public final class VillagerMining {
    private static final String P = "livingvillages_mining_";
    private static final String ACTIVE = P + "active";
    private static final String ORE_X = P + "ore_x", ORE_Y = P + "ore_y", ORE_Z = P + "ore_z";
    private static final String STARTED = P + "started", NEXT_SEARCH = P + "next_search";
    private static final String LAST_ORDER = P + "last_order";
    private static final String HAS_ROUTE = P + "has_route";
    private static final String ROUTE_X = P + "route_x", ROUTE_Y = P + "route_y", ROUTE_Z = P + "route_z";
    private static final String BREAK_X = P + "break_x", BREAK_Y = P + "break_y", BREAK_Z = P + "break_z";
    private static final String BREAK_PROGRESS = P + "break_progress";
    private static final String TARGET_KIND = P + "target_kind";
    private static final String ORE_VEIN_QUEUE = P + "ore_vein_queue";
    private static final int MAX_ORE_VEIN_BLOCKS = 128;
    private static final int TARGET_ORE = 0;
    private static final int TARGET_QUARRY = 1;

    private static final int TICK_STEP = 5;
    private static final int SEARCH_RETRY_TICKS = 80;
    private static final int SUCCESS_RETRY_TICKS = 40;
    private static final int ROUTE_RETRY_TICKS = 60;
    private static final int TASK_TIMEOUT_TICKS = 12000;
    private static final float WALK_SPEED = 0.62F;
    private static final double REACH_SQR = 4.5D * 4.5D;
    private static final Direction[] HORIZONTAL = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private static final List<Block> ORES = List.of(
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE
    );

    private VillagerMining() {
    }

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()
            || !villager.isAlive() || villager.isBaby() || villager.tickCount % TICK_STEP != 0) {
            return;
        }
        CompoundTag data = villager.getPersistentData();
        if (VillagerCaveSupport.isReturning(villager)) return;
        if (!VillagerRoles.isMiner(villager)) {
            if (data.getBoolean(ACTIVE)) cancelTask(villager, data);
            return;
        }
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_MINER_ROLE.get()) {
            if (data.getBoolean(ACTIVE)) cancelTask(villager, data);
            return;
        }
        if (!(villager.level() instanceof ServerLevel level)) return;

        long now = level.getGameTime();
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        if (villager.isSleeping() || dayTime < 1000L || dayTime >= 12000L
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            if (data.getBoolean(ACTIVE)) cancelTask(villager, data);
            data.putLong(NEXT_SEARCH, now + 100L);
            return;
        }

        if (data.getBoolean(ACTIVE)) {
            continueTask(villager, level, data, now);
            return;
        }
        if (now < data.getLong(NEXT_SEARCH) || villager.getHealth() < villager.getMaxHealth() * 0.35F) {
            return;
        }
        if (VillagerRoles.shouldYieldToVanillaVillagerAi(villager)) {
            data.putLong(NEXT_SEARCH, now + 40L);
            return;
        }

        ItemStack pickaxe = ensurePickaxe(villager, level);
        if (pickaxe.isEmpty()) {
            // No magical tools: the village must have a pickaxe in a chest/barrel or the villager's inventory.
            data.putLong(NEXT_SEARCH, now + SEARCH_RETRY_TICKS);
            return;
        }

        BlockPos home = VillagerRoles.getHome(villager);
        if (home == null) home = villager.blockPosition();
        com.abs.livingvillages.domain.VillageResourceDemandModel.Plan demand =
            VillageResourcePlanner.getPlan(level, home);
        if (!demand.needsMining()) return;

        BlockPos target = demand.needsOre() ? findNearestOre(level, villager, pickaxe) : null;
        int targetKind = TARGET_ORE;
        // Use visible stone only when the village stock is below its actual stone-material target.
        if (target == null && demand.needsStone()) {
            target = findNearestQuarryBlock(level, villager, pickaxe);
            targetKind = TARGET_QUARRY;
        }
        if (target == null) {
            data.putLong(NEXT_SEARCH, now + SEARCH_RETRY_TICKS);
            return;
        }
        data.putBoolean(ACTIVE, true);
        data.putInt(TARGET_KIND, targetKind);
        data.putInt(ORE_X, target.getX());
        data.putInt(ORE_Y, target.getY());
        data.putInt(ORE_Z, target.getZ());
        if (targetKind == TARGET_ORE) data.putLongArray(ORE_VEIN_QUEUE, collectOreVein(level, target));
        else data.remove(ORE_VEIN_QUEUE);
        data.putLong(STARTED, now);
        data.putLong(LAST_ORDER, now);
        resetProgress(data);
    }

    private static void continueTask(Villager villager, ServerLevel level, CompoundTag data, long now) {
        if (now - data.getLong(STARTED) > TASK_TIMEOUT_TICKS) {
            cancelTask(villager, data);
            data.putLong(NEXT_SEARCH, now + SEARCH_RETRY_TICKS);
            return;
        }
        BlockPos ore = targetOre(data);
        boolean quarryTask = data.getInt(TARGET_KIND) == TARGET_QUARRY;
        BlockState targetState = level.hasChunkAt(ore) ? level.getBlockState(ore) : Blocks.AIR.defaultBlockState();
        boolean targetStillValid = quarryTask ? isQuarryBlock(targetState) : isOre(targetState);
        if (!level.hasChunkAt(ore) || !targetStillValid
            || !toolCanMine(villager.getItemBySlot(EquipmentSlot.MAINHAND), targetState)) {
            cancelTask(villager, data);
            data.putLong(NEXT_SEARCH, now + SUCCESS_RETRY_TICKS);
            return;
        }

        BlockPos currentBlock = blockBlockingOre(villager, level, ore);
        if (currentBlock == null) {
            cancelTask(villager, data);
            data.putLong(NEXT_SEARCH, now + SEARCH_RETRY_TICKS);
            return;
        }
        BlockState state = level.getBlockState(currentBlock);
        ItemStack tool = villager.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!isSafeMineTarget(level, villager, currentBlock, state)
            || !toolCanMine(tool, state)) {
            cancelTask(villager, data);
            data.putLong(NEXT_SEARCH, now + SEARCH_RETRY_TICKS);
            return;
        }

        if (!withinReach(villager, currentBlock) || !canSeeBlock(villager, level, currentBlock)) {
            clearBreakOverlay(villager, level, data);
            routeToWorkPosition(villager, level, data, currentBlock, now);
            resetProgress(data);
            return;
        }
        clearOwnedRoute(villager, data);
        progressBreaking(villager, level, data, currentBlock, state, tool);

        // Once the selected block is gone, start a new search. Ore remains higher priority;
        // stone quarrying only supplies real cobblestone and never blindly tunnels through the world.
        BlockState remainingTarget = level.getBlockState(ore);
        boolean targetWasExhausted = quarryTask ? !isQuarryBlock(remainingTarget) : !isOre(remainingTarget);
        if (targetWasExhausted && !quarryTask) {
            BlockPos nextOre = findNextOreInVein(level, villager, data);
            if (nextOre != null) {
                data.putInt(ORE_X, nextOre.getX());
                data.putInt(ORE_Y, nextOre.getY());
                data.putInt(ORE_Z, nextOre.getZ());
                clearOwnedRoute(villager, data);
                resetProgress(data);
                data.putLong(STARTED, now);
                return;
            }
        }
        if (targetWasExhausted) {
            cancelTask(villager, data);
            data.putLong(NEXT_SEARCH, now + SUCCESS_RETRY_TICKS);
        }
    }

    private static void progressBreaking(Villager villager, ServerLevel level, CompoundTag data,
                                         BlockPos pos, BlockState state, ItemStack tool) {
        if (!pos.equals(new BlockPos(data.getInt(BREAK_X), data.getInt(BREAK_Y), data.getInt(BREAK_Z)))) {
            data.putInt(BREAK_X, pos.getX());
            data.putInt(BREAK_Y, pos.getY());
            data.putInt(BREAK_Z, pos.getZ());
            data.putInt(BREAK_PROGRESS, 0);
        }
        villager.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        int progress = data.getInt(BREAK_PROGRESS) + 1;
        data.putInt(BREAK_PROGRESS, progress);

        float hardness = state.getDestroySpeed(level, pos);
        float speed = Math.max(0.1F, tool.getDestroySpeed(state));
        int baseRequired = Math.max(4, (int) Math.ceil((30.0D * Math.max(0.1F, hardness) / speed) / TICK_STEP));
        int required = VillagerProgression.adjustedStepTicks(
            villager,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_MINER,
            baseRequired,
            3
        );
        level.destroyBlockProgress(-villager.getId(), pos, Math.min(9, progress * 10 / required));
        if (progress % 2 == 0) {
            level.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D, 1, 0.12D, 0.12D, 0.12D, 0.01D);
        }
        if (progress < required) return;

        // Recheck target and reach on the actual breaking step to avoid stale/distant targets.
        BlockState current = level.getBlockState(pos);
        if (!current.is(state.getBlock()) || !withinReach(villager, pos) || !canSeeBlock(villager, level, pos)
            || !toolCanMine(tool, current)) {
            clearBreakOverlay(villager, level, data);
            resetProgress(data);
            return;
        }

        List<ItemStack> drops = List.of();
        if (level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            drops = Block.getDrops(current, level, pos, level.getBlockEntity(pos), villager, tool);
        }
        if (level.destroyBlock(pos, false, villager)) {
            for (ItemStack drop : drops) storeOrDrop(villager, level, pos, drop.copy());
            tool.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
            level.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D, 6, 0.22D, 0.22D, 0.22D, 0.03D);
            VillageTickBudget.recordActivitySignal(level, pos);
            VillagerProgression.recordCompletedWork(
                villager,
                com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_MINER,
                3,
                true,
                level.getGameTime()
            );
        }
        level.destroyBlockProgress(-villager.getId(), pos, -1);
        resetProgress(data);
    }

    private static BlockPos findNearestOre(ServerLevel level, Villager villager, ItemStack tool) {
        int radius = LivingVillagesConfig.MINER_SEARCH_RADIUS_BLOCKS.get();
        BlockPos origin = villager.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            origin.getX(), origin.getZ());
        // Surface workers keep the cheaper vanilla-like scan. Once a miner is actually underground,
        // it can search farther down through naturally exposed cave faces.
        int verticalDepth = origin.getY() < surfaceY - 5 ? 20 : 8;
        int minY = Math.max(level.getMinBuildHeight() + 1, origin.getY() - verticalDepth);
        int maxY = Math.min(level.getMaxBuildHeight() - 2, origin.getY() + 3);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (isOre(state) && toolCanMine(tool, state) && !isReservedByAnotherMiner(level, villager, pos)
                        && isSafeMineTarget(level, villager, pos, state)
                        && hasExposedFace(level, pos)
                        && findWorkPosition(level, villager, pos) != null) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> villager.distanceToSqr(Vec3.atCenterOf(pos))));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** True while the settlement's stone/sandstone stock is below the amount needed for tools, a furnace, and structural expansion. */
    private static boolean needsCobblestone(ServerLevel level, Villager miner) {
        int stoneStock = 0;
        var inventory = miner.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isCountedQuarryMaterial(stack)) stoneStock += stack.getCount();
        }
        BlockPos origin = VillagerRoles.getHome(miner);
        if (origin == null) origin = miner.blockPosition();
        int radius = Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                        || !VillageRegistry.isStorageForVillage(level, origin, pos)
                        || !VillageReservations.isStorageAvailable(level, pos, miner)) continue;
                    if (!(level.getBlockEntity(pos) instanceof Container container)) continue;
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        ItemStack stack = container.getItem(slot);
                        if (isCountedQuarryMaterial(stack)) stoneStock += stack.getCount();
                    }
                }
            }
        }
        return stoneStock < 112;
    }

    private static boolean isCountedQuarryMaterial(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.is(Items.COBBLESTONE) || stack.is(Items.SANDSTONE)
            || stack.is(Items.RED_SANDSTONE) || stack.is(Items.TERRACOTTA)
            || stack.is(Items.STONE_BRICKS);
    }

    /** Quarry only exposed, natural-looking stone/sandstone blocks; the normal pickaxe, reach and drop rules still apply. */
    private static BlockPos findNearestQuarryBlock(ServerLevel level, Villager villager, ItemStack tool) {
        int radius = Math.min(16, LivingVillagesConfig.MINER_SEARCH_RADIUS_BLOCKS.get());
        BlockPos origin = villager.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int dy = -4; dy <= 3; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (!isQuarryBlock(state) || !toolCanMine(tool, state)
                        || !isSafeMineTarget(level, villager, pos, state)
                        || isReservedByAnotherMiner(level, villager, pos)) continue;
                    if (!hasExposedFace(level, pos)) continue;
                    if (findWorkPosition(level, villager, pos) != null) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> villager.distanceToSqr(Vec3.atCenterOf(pos))));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** Snapshot one connected same-type ore vein; hidden unrelated veins are never added. */
    static long[] collectOreVein(ServerLevel level, BlockPos start) {
        Block targetBlock = level.getBlockState(start).getBlock();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        List<Long> found = new ArrayList<>();
        pending.add(start.immutable());
        while (!pending.isEmpty() && found.size() < MAX_ORE_VEIN_BLOCKS) {
            BlockPos pos = pending.removeFirst();
            if (Math.abs(pos.getX() - start.getX()) > 8 || Math.abs(pos.getZ() - start.getZ()) > 8
                || Math.abs(pos.getY() - start.getY()) > 6 || !level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() != targetBlock || !isOre(state) || !visited.add(pos.asLong())) continue;
            found.add(pos.asLong());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        pending.addLast(pos.offset(dx, dy, dz));
                    }
                }
            }
        }
        return found.stream().mapToLong(Long::longValue).toArray();
    }

    /** Advance to the nearest surviving ore from the initial vein without picking another vein. */
    private static BlockPos findNextOreInVein(ServerLevel level, Villager villager, CompoundTag data) {
        long[] queued = data.getLongArray(ORE_VEIN_QUEUE);
        List<Long> remaining = new ArrayList<>();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (long packed : queued) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            ItemStack pickaxe = villager.getItemBySlot(EquipmentSlot.MAINHAND);
            if (!isOre(state) || !toolCanMine(pickaxe, state)
                || !isSafeMineTarget(level, villager, pos, state)
                || !hasExposedFace(level, pos)
                || findWorkPosition(level, villager, pos) == null) continue;
            remaining.add(packed);
            double distance = villager.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance < bestDistance) { bestDistance = distance; best = pos.immutable(); }
        }
        data.putLongArray(ORE_VEIN_QUEUE, remaining.stream().mapToLong(Long::longValue).toArray());
        return best;
    }

    private static boolean hasExposedFace(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos adj = pos.relative(direction);
            if (level.hasChunkAt(adj) && VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(adj))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isQuarryBlock(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE)
            || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
            || state.is(Blocks.TERRACOTTA);
    }

    private static boolean isReservedByAnotherMiner(ServerLevel level, Villager self, BlockPos candidate) {
        AABB area = new AABB(self.blockPosition()).inflate(
            LivingVillagesConfig.MINER_SEARCH_RADIUS_BLOCKS.get() + 4.0D, 24.0D,
            LivingVillagesConfig.MINER_SEARCH_RADIUS_BLOCKS.get() + 4.0D);
        for (Villager other : level.getEntitiesOfClass(Villager.class, area,
            v -> v != self && v.isAlive() && VillagerRoles.isMiner(v))) {
            CompoundTag data = other.getPersistentData();
            if (!data.getBoolean(ACTIVE) || !data.contains(ORE_X) || !data.contains(ORE_Y) || !data.contains(ORE_Z)) continue;
            BlockPos reserved = targetOre(data);
            if (reserved.distSqr(candidate) <= 36.0D) return true;
        }
        return false;
    }

    private static BlockPos blockBlockingOre(Villager villager, ServerLevel level, BlockPos ore) {
        BlockHitResult hit = level.clip(new ClipContext(villager.getEyePosition(), Vec3.atCenterOf(ore),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, villager));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(ore)) return ore;
        BlockPos obstruction = hit.getBlockPos();
        BlockState state = level.getBlockState(obstruction);
        return isSafeMineTarget(level, villager, obstruction, state) ? obstruction.immutable() : null;
    }

    private static boolean isOre(BlockState state) {
        return ORES.contains(state.getBlock());
    }

    private static boolean isSafeMineTarget(ServerLevel level, Villager villager, BlockPos pos, BlockState state) {
        if (!level.hasChunkAt(pos) || state.isAir() || state.getDestroySpeed(level, pos) < 0.0F
            || state.hasBlockEntity() || !level.getFluidState(pos).isEmpty()) return false;
        if (pos.getY() < villager.blockPosition().getY() - 20
            || pos.getY() > villager.blockPosition().getY() + 3) return false;
        // Never dig the block immediately underneath the worker. This prevents the basic AI
        // from opening a pit under itself while chasing a vein.
        if (pos.getX() == villager.blockPosition().getX() && pos.getZ() == villager.blockPosition().getZ()
            && pos.getY() < villager.blockPosition().getY()) return false;
        if (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.GRANITE)
            || state.is(Blocks.DIORITE) || state.is(Blocks.ANDESITE) || state.is(Blocks.TUFF)
            || state.is(Blocks.CALCITE) || state.is(Blocks.DRIPSTONE_BLOCK)
            || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE)
            || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
            || state.is(Blocks.TERRACOTTA) || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)
            || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)
            || state.is(Blocks.GRAVEL) || isOre(state)) {
            // Don't open a block with lava directly on the other side.
            for (Direction direction : Direction.values()) {
                if (level.getFluidState(pos.relative(direction)).is(FluidTags.LAVA)) return false;
            }
            return true;
        }
        return false;
    }

    private static boolean toolCanMine(ItemStack tool, BlockState state) {
        return isPickaxe(tool) && tool.isCorrectToolForDrops(state);
    }

    private static ItemStack ensurePickaxe(Villager villager, ServerLevel level) {
        ItemStack held = villager.getItemBySlot(EquipmentSlot.MAINHAND);
        int heldTier = pickaxeTier(held);
        var inventory = villager.getInventory();

        int bestInventorySlot = -1;
        int bestTier = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            int tier = pickaxeTier(inventory.getItem(slot));
            if (tier > bestTier) {
                bestTier = tier;
                bestInventorySlot = slot;
            }
        }

        int radius = Math.min(12, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        BlockPos origin = villager.blockPosition();
        BlockPos ownershipCenter = VillagerRoles.getHome(villager);
        if (ownershipCenter == null) ownershipCenter = origin;
        BlockPos bestStoragePos = null;
        int bestStorageSlot = -1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                        || !VillageRegistry.isStorageForVillage(level, ownershipCenter, pos)
                        || !VillageReservations.isStorageAvailable(level, pos, villager)
                        || !(level.getBlockEntity(pos) instanceof Container container)) continue;
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        int tier = pickaxeTier(container.getItem(slot));
                        if (tier > bestTier) {
                            bestTier = tier;
                            bestStoragePos = pos.immutable();
                            bestStorageSlot = slot;
                            bestInventorySlot = -1;
                        }
                    }
                }
            }
        }

        // Keep a tool already in hand unless a strictly better real pickaxe is accessible.
        if (bestTier <= heldTier) return isPickaxe(held) ? held : ItemStack.EMPTY;

        if (bestInventorySlot >= 0) {
            ItemStack chosen = inventory.getItem(bestInventorySlot).copy();
            if (isPickaxe(held)) {
                // A hand-to-inventory swap preserves both tools even when every slot is occupied.
                inventory.setItem(bestInventorySlot, held.copy());
            } else {
                if (!held.isEmpty()) {
                    ItemStack remainder = inventory.addItem(held.copy());
                    if (!remainder.isEmpty()) return ItemStack.EMPTY;
                }
                ItemStack stored = inventory.getItem(bestInventorySlot);
                chosen = stored.split(1);
                if (stored.isEmpty()) inventory.setItem(bestInventorySlot, ItemStack.EMPTY);
            }
            inventory.setChanged();
            villager.setItemSlot(EquipmentSlot.MAINHAND, chosen);
            return villager.getItemBySlot(EquipmentSlot.MAINHAND);
        }

        if (bestStoragePos != null && level.hasChunkAt(bestStoragePos)
            && VillageRegistry.isStorageForVillage(level, ownershipCenter, bestStoragePos)
            && VillageReservations.isStorageAvailable(level, bestStoragePos, villager)
            && level.getBlockEntity(bestStoragePos) instanceof Container container
            && bestStorageSlot >= 0 && bestStorageSlot < container.getContainerSize()) {
            if (!held.isEmpty()) {
                if (isPickaxe(held)) {
                    if (!inventoryCanFit(inventory, held)) return held;
                    insertIntoInventory(inventory, held.copy());
                } else {
                    ItemStack remainder = inventory.addItem(held.copy());
                    if (!remainder.isEmpty()) return ItemStack.EMPTY;
                }
            }
            ItemStack candidate = container.getItem(bestStorageSlot);
            if (pickaxeTier(candidate) <= heldTier || candidate.isEmpty()) return isPickaxe(held) ? held : ItemStack.EMPTY;
            ItemStack chosen = candidate.split(1);
            if (candidate.isEmpty()) container.setItem(bestStorageSlot, ItemStack.EMPTY);
            container.setChanged();
            inventory.setChanged();
            villager.setItemSlot(EquipmentSlot.MAINHAND, chosen);
            return villager.getItemBySlot(EquipmentSlot.MAINHAND);
        }

        return isPickaxe(held) ? held : ItemStack.EMPTY;
    }


    /** Checks that the villager inventory has capacity before moving an existing tool from storage. */
    private static boolean inventoryCanFit(Container inventory, ItemStack output) {
        int remaining = output.getCount();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()) {
                remaining -= Math.min(output.getMaxStackSize(), inventory.getMaxStackSize());
            } else if (ItemStack.isSameItemSameComponents(existing, output)) {
                remaining -= Math.max(0, Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize()) - existing.getCount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    /** Moves an already-existing ItemStack into inventory slots; it never creates item counts. */
    private static void insertIntoInventory(Container inventory, ItemStack output) {
        for (int pass = 0; pass < 2 && !output.isEmpty(); pass++) {
            for (int i = 0; i < inventory.getContainerSize() && !output.isEmpty(); i++) {
                ItemStack existing = inventory.getItem(i);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, output)) continue;
                    int capacity = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize()) - existing.getCount();
                    int amount = Math.min(Math.max(0, capacity), output.getCount());
                    if (amount > 0) { existing.grow(amount); output.shrink(amount); }
                } else if (existing.isEmpty()) {
                    int amount = Math.min(output.getCount(), Math.min(output.getMaxStackSize(), inventory.getMaxStackSize()));
                    if (amount > 0) inventory.setItem(i, output.split(amount));
                }
            }
        }
        inventory.setChanged();
    }

    private static int pickaxeTier(ItemStack stack) {
        if (stack.is(Items.NETHERITE_PICKAXE)) return 6;
        if (stack.is(Items.DIAMOND_PICKAXE)) return 5;
        if (stack.is(Items.IRON_PICKAXE)) return 4;
        if (stack.is(Items.STONE_PICKAXE)) return 3;
        if (stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.GOLDEN_PICKAXE)) return 1;
        return 0;
    }

    private static boolean isPickaxe(ItemStack stack) {
        return stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.STONE_PICKAXE)
            || stack.is(Items.IRON_PICKAXE) || stack.is(Items.GOLDEN_PICKAXE)
            || stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE);
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
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
        boolean routeRetryAllowed = now - data.getLong(LAST_ORDER) >= ROUTE_RETRY_TICKS;
        if (current.isPresent() && !routeRetryAllowed) return;
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
        data.putBoolean(HAS_ROUTE, true);
        data.putInt(ROUTE_X, stand.getX()); data.putInt(ROUTE_Y, stand.getY()); data.putInt(ROUTE_Z, stand.getZ());
        data.putLong(LAST_ORDER, now);
    }

    private static BlockPos findWorkPosition(ServerLevel level, Villager villager, BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos stand = target.offset(dx, dy, dz);
                    if (!isSafeStand(level, stand)) continue;
                    Vec3 eyes = new Vec3(stand.getX() + 0.5D, stand.getY() + villager.getEyeHeight(), stand.getZ() + 0.5D);
                    if (eyes.distanceToSqr(Vec3.atCenterOf(target)) > REACH_SQR) continue;
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
        return level.hasChunkAt(feet) && level.hasChunkAt(feet.above()) && level.hasChunkAt(feet.below())
            && VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(feet))
            && VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(feet.above()))
            && level.getBlockState(feet.below()).blocksMotion();
    }

    private static boolean withinReach(Villager villager, BlockPos pos) {
        return villager.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= REACH_SQR;
    }

    private static boolean canSeeBlock(Villager villager, ServerLevel level, BlockPos target) {
        BlockHitResult hit = level.clip(new ClipContext(villager.getEyePosition(), Vec3.atCenterOf(target),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, villager));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
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
                } else if (existing.isEmpty()) {
                    int moved = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize()));
                    inventory.setItem(slot, stack.split(moved));
                }
            }
        }
        inventory.setChanged();
        if (!stack.isEmpty()) Block.popResource(level, pos, stack);
    }

    private static BlockPos targetOre(CompoundTag data) {
        return new BlockPos(data.getInt(ORE_X), data.getInt(ORE_Y), data.getInt(ORE_Z));
    }

    private static void resetProgress(CompoundTag data) {
        data.putInt(BREAK_PROGRESS, 0);
        data.remove(BREAK_X); data.remove(BREAK_Y); data.remove(BREAK_Z);
    }

    private static void clearBreakOverlay(Villager villager, ServerLevel level, CompoundTag data) {
        if (data.contains(BREAK_X) && data.contains(BREAK_Y) && data.contains(BREAK_Z)) {
            level.destroyBlockProgress(-villager.getId(),
                new BlockPos(data.getInt(BREAK_X), data.getInt(BREAK_Y), data.getInt(BREAK_Z)), -1);
        }
    }

    /** Suspend the active extraction order before cave safety-return takes ownership of navigation. */
    public static void suspendForCaveReturn(Villager villager) {
        if (villager == null) return;
        CompoundTag data = villager.getPersistentData();
        if (!data.getBoolean(ACTIVE)) return;
        cancelTask(villager, data);
        data.putLong(NEXT_SEARCH, villager.level().getGameTime() + 200L);
    }

    private static void cancelTask(Villager villager, CompoundTag data) {
        clearOwnedRoute(villager, data);
        if (villager.level() instanceof ServerLevel level) clearBreakOverlay(villager, level, data);
        data.putBoolean(ACTIVE, false);
        data.remove(ORE_X); data.remove(ORE_Y); data.remove(ORE_Z); data.remove(STARTED); data.remove(LAST_ORDER); data.remove(TARGET_KIND);
        data.remove(ORE_VEIN_QUEUE);
        resetProgress(data);
    }

    private static void clearOwnedRoute(Villager villager, CompoundTag data) {
        if (data.getBoolean(HAS_ROUTE)) {
            BlockPos route = new BlockPos(data.getInt(ROUTE_X), data.getInt(ROUTE_Y), data.getInt(ROUTE_Z));
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(route)) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            });
        }
        data.putBoolean(HAS_ROUTE, false);
        data.remove(ROUTE_X); data.remove(ROUTE_Y); data.remove(ROUTE_Z);
    }
}
