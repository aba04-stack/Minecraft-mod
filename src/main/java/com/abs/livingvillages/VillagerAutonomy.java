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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
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
    public static final String HARVEST_ACTIVE_KEY = PREFIX + "harvest_active";
    public static final String HARVEST_CYCLE_KEY = PREFIX + "harvest_cycle_active";
    private static final String HARVEST_X = PREFIX + "harvest_x";
    private static final String HARVEST_Y = PREFIX + "harvest_y";
    private static final String HARVEST_Z = PREFIX + "harvest_z";
    private static final String HARVEST_STARTED = PREFIX + "harvest_started";
    private static final String HARVEST_LAST_ORDER = PREFIX + "harvest_last_order";
    private static final String HARVEST_FAILED_X = PREFIX + "harvest_failed_x";
    private static final String HARVEST_FAILED_Y = PREFIX + "harvest_failed_y";
    private static final String HARVEST_FAILED_Z = PREFIX + "harvest_failed_z";
    private static final String HARVEST_FAILED_UNTIL = PREFIX + "harvest_failed_until";
    public static final String SEED_FETCH_ACTIVE_KEY = PREFIX + "seed_fetch_active";
    private static final String SEED_FETCH_CHEST_X = PREFIX + "seed_fetch_chest_x";
    private static final String SEED_FETCH_CHEST_Y = PREFIX + "seed_fetch_chest_y";
    private static final String SEED_FETCH_CHEST_Z = PREFIX + "seed_fetch_chest_z";
    private static final String SEED_FETCH_STAND_X = PREFIX + "seed_fetch_stand_x";
    private static final String SEED_FETCH_STAND_Y = PREFIX + "seed_fetch_stand_y";
    private static final String SEED_FETCH_STAND_Z = PREFIX + "seed_fetch_stand_z";
    private static final String SEED_FETCH_STARTED = PREFIX + "seed_fetch_started";
    private static final String SEED_FETCH_LAST_ORDER = PREFIX + "seed_fetch_last_order";

    private static final int MAX_CROPS_PER_PASS = 4;
    private static final long HARVEST_ROUTE_TIMEOUT_TICKS = 1200L;
    private static final long HARVEST_TARGET_COOLDOWN_TICKS = 12000L;
    private static final long HARVEST_ROUTE_REISSUE_TICKS = 40L;
    private static final long SEED_FETCH_TIMEOUT_TICKS = 1200L;

    private static final int SEARCH_STAGGER_TICKS = 400;
    private static final int RETRY_DELAY_TICKS = 80;
    private static final int SUCCESS_DELAY_TICKS = 120;
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
            || villager.tickCount % 20 != 0) {
            return;
        }

        CompoundTag data = villager.getPersistentData();
        if (data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_logistics_active")) return;
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_FARMER_STOCKPILING.get()) {
            if (data.getBoolean(TARGET_ACTIVE)) {
                cancelTrip(villager, data);
            }
            clearHarvestRoute(villager, data);
            if (data.getBoolean(SEED_FETCH_ACTIVE_KEY)) cancelSeedFetch(villager, data);
            data.putBoolean(HARVEST_CYCLE_KEY, false);
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

        if (data.getBoolean(SEED_FETCH_ACTIVE_KEY)) {
            continueSeedFetch(villager, level, data, now);
            return;
        }

        if (data.getBoolean(TARGET_ACTIVE)) {
            handleCurrentTrip(villager, level, data, now);
            return;
        }

        if (dayTime < 1000L) {
            return; // Storage runs are work-hour errands, not a reason to miss sleep.
        }

        // A farmer stays with the field while food is short. Each pass harvests a small batch,
        // and a distant ripe crop becomes the worker's own resumable walk target.
        boolean farmerWorker = villager.getVillagerData().getProfession() == VillagerProfession.FARMER
            || VillagerRoles.isFarmer(villager);
        BlockPos villageHome = VillagerRoles.getHome(villager);
        if (villageHome == null) villageHome = villager.blockPosition();
        boolean foodShortage = VillageResourcePlanner.getPlan(level, villageHome).needsFood();
        if (farmerWorker && (data.getBoolean(HARVEST_CYCLE_KEY)
            || data.getBoolean(HARVEST_ACTIVE_KEY)
            || (foodShortage && now >= data.getLong(NEXT_SEARCH)))) {
            if (tryHarvestMatureCropAndBakeBread(villager, level, now)) return;
        }

        if (villager.getVillagerData().getProfession() != VillagerProfession.FARMER
            && foodPoints(villager) < MIN_FOOD_POINTS_TO_START) {
            return;
        }

        if (now < data.getLong(NEXT_SEARCH)) {
            return;
        }

        if (foodPoints(villager) < MIN_FOOD_POINTS_TO_START) {
            data.putLong(NEXT_SEARCH, now + RETRY_DELAY_TICKS);
            return;
        }

        if (VillagerRoles.shouldYieldToVanillaVillagerAi(villager)) {
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
            int moved = depositFarmerSurplus(villager, container, now);
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
        if (!level.hasChunkAt(pos) || !VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(pos))
            || !VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(pos.above()))) {
            return false;
        }
        return !level.getBlockState(pos.below()).isAir();
    }

    static boolean tryHarvestMatureCropAndBakeBread(Villager villager, ServerLevel level, long nowTick) {
        CompoundTag data = villager.getPersistentData();
        if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING)) {
            clearHarvestRoute(villager, data);
            data.putBoolean(HARVEST_CYCLE_KEY, false);
            return false;
        }
        data.putBoolean(HARVEST_CYCLE_KEY, true);
        if (data.getBoolean(HARVEST_ACTIVE_KEY)
            && nowTick - data.getLong(HARVEST_STARTED) > HARVEST_ROUTE_TIMEOUT_TICKS) {
            data.putInt(HARVEST_FAILED_X, data.getInt(HARVEST_X));
            data.putInt(HARVEST_FAILED_Y, data.getInt(HARVEST_Y));
            data.putInt(HARVEST_FAILED_Z, data.getInt(HARVEST_Z));
            data.putLong(HARVEST_FAILED_UNTIL, nowTick + HARVEST_TARGET_COOLDOWN_TICKS);
            clearHarvestRoute(villager, data);
        }
        if (data.contains(HARVEST_FAILED_UNTIL) && nowTick >= data.getLong(HARVEST_FAILED_UNTIL)) {
            data.remove(HARVEST_FAILED_X); data.remove(HARVEST_FAILED_Y); data.remove(HARVEST_FAILED_Z);
            data.remove(HARVEST_FAILED_UNTIL);
        }

        BlockPos origin = VillagerRoles.getHome(villager);
        if (origin == null) origin = villager.blockPosition();
        int radius = Math.min(16, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get());

        // One bounded scan discovers both ripe crops and empty, village-owned farmland.
        List<BlockPos> ripeCrops = new ArrayList<>();
        List<BlockPos> emptyFarmland = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    if (data.contains(HARVEST_FAILED_UNTIL) && nowTick < data.getLong(HARVEST_FAILED_UNTIL)
                        && pos.getX() == data.getInt(HARVEST_FAILED_X)
                        && pos.getY() == data.getInt(HARVEST_FAILED_Y)
                        && pos.getZ() == data.getInt(HARVEST_FAILED_Z)) continue;

                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop
                        && crop.isMaxAge(state)
                        && VillageArea.isBlockInResidentGroup(level, origin, pos)) {
                        ripeCrops.add(pos.immutable());
                    } else if (state.isAir() && level.hasChunkAt(pos.below())
                        && level.getBlockState(pos.below()).is(Blocks.FARMLAND)
                        && level.getMaxLocalRawBrightness(pos) >= 8
                        && VillageArea.isBlockInResidentGroup(level, origin, pos.below())) {
                        emptyFarmland.add(pos.immutable());
                    }
                }
            }
        }

        java.util.Comparator<BlockPos> nearestFirst = java.util.Comparator.comparingDouble(
            pos -> villager.distanceToSqr(Vec3.atCenterOf(pos)));
        ripeCrops.sort(nearestFirst);
        emptyFarmland.sort(nearestFirst);

        int workDone = 0;
        for (BlockPos target : ripeCrops) {
            if (workDone >= MAX_CROPS_PER_PASS) break;
            BlockState state = level.getBlockState(target);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop)
                || !crop.isMaxAge(state)) continue;

            if (villager.distanceToSqr(Vec3.atCenterOf(target)) > 9.0D) {
                if (VillagerRoles.shouldYieldToVanillaVillagerAi(villager)) {
                    clearHarvestRoute(villager, data);
                    data.putLong(NEXT_SEARCH, nowTick + RETRY_DELAY_TICKS);
                    return false;
                }
                setHarvestRoute(villager, data, target, nowTick);
                return true;
            }

            net.minecraft.world.item.Item seedItem = seedItemForCrop(crop);
            if (seedItem == Items.AIR) continue;
            List<ItemStack> drops = new ArrayList<>(
                Block.getDrops(state, level, target, null, villager, ItemStack.EMPTY));
            int seedInInventory = countItems(villager.getInventory(), seedItem);
            int seedInDrops = drops.stream().filter(stack -> stack.is(seedItem))
                .mapToInt(ItemStack::getCount).sum();
            if (seedInInventory + seedInDrops <= 0) {
                // A ripe crop is left untouched when it cannot be replanted with a real seed.
                continue;
            }

            clearHarvestRoute(villager, data);
            if (data.contains(HARVEST_FAILED_UNTIL)
                && target.getX() == data.getInt(HARVEST_FAILED_X)
                && target.getY() == data.getInt(HARVEST_FAILED_Y)
                && target.getZ() == data.getInt(HARVEST_FAILED_Z)) {
                data.remove(HARVEST_FAILED_X); data.remove(HARVEST_FAILED_Y); data.remove(HARVEST_FAILED_Z);
                data.remove(HARVEST_FAILED_UNTIL);
            }
            boolean seedFromInventory = seedInInventory > 0;
            if (!seedFromInventory) {
                ItemStack seedDrop = drops.stream()
                    .filter(stack -> stack.is(seedItem) && !stack.isEmpty()).findFirst().orElse(null);
                if (seedDrop == null) continue;
                // Use one seed directly from this crop's real drops; a full villager inventory
                // must not force the farmer to abandon an otherwise safely replantable crop.
                seedDrop.shrink(1);
            } else if (!consumeOneItem(villager.getInventory(), seedItem)) {
                continue;
            }

            // Replant first. Commit drops only after the world accepted the replacement crop.
            if (!level.setBlock(target, crop.getStateForAge(0), 3)) {
                villager.spawnAtLocation(new ItemStack(seedItem));
                continue;
            }
            for (ItemStack drop : drops) {
                if (drop.isEmpty()) continue;
                ItemStack remainder = villager.getInventory().addItem(drop.copy());
                if (!remainder.isEmpty()) Block.popResource(level, target, remainder);
            }
            villager.getInventory().setChanged();
            VillagerProgression.recordCompletedWork(
                villager,
                com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_FARMER,
                2,
                true,
                nowTick
            );
            workDone++;
        }

        // Once ripe crops are handled, fill empty owned farmland using real seeds/planting items.
        if (workDone < MAX_CROPS_PER_PASS && !emptyFarmland.isEmpty()) {
            net.minecraft.world.item.Item plantingItem = firstPlantingItem(villager.getInventory());
            if (plantingItem == Items.AIR) {
                if (startSeedFetch(villager, level, origin, nowTick)) return true;
                data.putBoolean(HARVEST_CYCLE_KEY, false);
                data.putLong(NEXT_SEARCH, nowTick + SUCCESS_DELAY_TICKS);
                VillageRegistry.recordDiagnostic(level, origin, "Фермер",
                    "Ожидаются настоящие семена или посадочные клубни на складе");
                return workDone > 0;
            }

            Block cropBlock = cropForPlantingItem(plantingItem);
            for (BlockPos target : emptyFarmland) {
                if (workDone >= MAX_CROPS_PER_PASS) break;
                if (!level.hasChunkAt(target) || !level.getBlockState(target).isAir()
                    || !level.getBlockState(target.below()).is(Blocks.FARMLAND)
                    || level.getMaxLocalRawBrightness(target) < 8
                    || !VillageArea.isBlockInResidentGroup(level, origin, target.below())) continue;

                if (villager.distanceToSqr(Vec3.atCenterOf(target)) > 9.0D) {
                    if (VillagerRoles.shouldYieldToVanillaVillagerAi(villager)) {
                        data.putBoolean(HARVEST_CYCLE_KEY, false);
                        data.putLong(NEXT_SEARCH, nowTick + RETRY_DELAY_TICKS);
                        return workDone > 0;
                    }
                    setHarvestRoute(villager, data, target, nowTick);
                    return true;
                }

                clearHarvestRoute(villager, data);
                if (data.contains(HARVEST_FAILED_UNTIL)
                    && target.getX() == data.getInt(HARVEST_FAILED_X)
                    && target.getY() == data.getInt(HARVEST_FAILED_Y)
                    && target.getZ() == data.getInt(HARVEST_FAILED_Z)) {
                    data.remove(HARVEST_FAILED_X); data.remove(HARVEST_FAILED_Y);
                    data.remove(HARVEST_FAILED_Z); data.remove(HARVEST_FAILED_UNTIL);
                }
                if (countItems(villager.getInventory(), plantingItem) <= 0) break;
                if (!level.setBlock(target, cropBlock.defaultBlockState(), 3)) continue;
                if (!consumeOneItem(villager.getInventory(), plantingItem)) {
                    level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
                    break;
                }
                villager.getInventory().setChanged();
                VillagerProgression.recordCompletedWork(
                    villager,
                    com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_FARMER,
                    1,
                    true,
                    nowTick
                );
                workDone++;
            }
        }

        // Keep the cycle active only when this bounded batch was full or a route/fetch is pending.
        boolean moreRipe = ripeCrops.stream().anyMatch(pos -> {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) || !crop.isMaxAge(state)) {
                return false;
            }
            net.minecraft.world.item.Item seed = seedItemForCrop(crop);
            if (seed == Items.AIR) return false;
            if (countItems(villager.getInventory(), seed) > 0) return true;
            return Block.getDrops(state, level, pos, null, villager, ItemStack.EMPTY).stream()
                .anyMatch(drop -> drop.is(seed) && drop.getCount() > 0);
        });
        boolean morePlantable = emptyFarmland.stream().anyMatch(pos -> level.getBlockState(pos).isAir()
            && level.getBlockState(pos.below()).is(Blocks.FARMLAND));
        boolean cyclePending = workDone >= MAX_CROPS_PER_PASS
            || moreRipe || (morePlantable && firstPlantingItem(villager.getInventory()) != Items.AIR);
        data.putBoolean(HARVEST_CYCLE_KEY, cyclePending);
        if (!cyclePending) data.putLong(NEXT_SEARCH, nowTick + SUCCESS_DELAY_TICKS);

        // Convert carried wheat into bread after the bounded field work.
        var inv = villager.getInventory();
        int wheatCount = countItems(inv, Items.WHEAT);
        while (wheatCount >= 3) {
            if (!consumeItems(inv, Items.WHEAT, 3)) break;
            ItemStack remainder = inv.addItem(new ItemStack(Items.BREAD, 1));
            if (!remainder.isEmpty()) villager.spawnAtLocation(remainder);
            inv.setChanged();
            wheatCount -= 3;
        }
        return workDone > 0 || cyclePending || data.getBoolean(HARVEST_ACTIVE_KEY);
    }

    private static void setHarvestRoute(Villager villager, CompoundTag data, BlockPos target, long nowTick) {
        boolean sameTarget = data.getBoolean(HARVEST_ACTIVE_KEY)
            && target.equals(new BlockPos(data.getInt(HARVEST_X), data.getInt(HARVEST_Y), data.getInt(HARVEST_Z)));
        if (!sameTarget) {
            data.putBoolean(HARVEST_ACTIVE_KEY, true);
            data.putInt(HARVEST_X, target.getX());
            data.putInt(HARVEST_Y, target.getY());
            data.putInt(HARVEST_Z, target.getZ());
            data.putLong(HARVEST_STARTED, nowTick);
            data.putLong(HARVEST_LAST_ORDER, nowTick);
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, 1));
        } else if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
            && nowTick - data.getLong(HARVEST_LAST_ORDER) >= HARVEST_ROUTE_REISSUE_TICKS) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, 1));
            data.putLong(HARVEST_LAST_ORDER, nowTick);
        }
    }

    private static net.minecraft.world.item.Item firstPlantingItem(Container inventory) {
        for (net.minecraft.world.item.Item item : List.of(
            Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS)) {
            if (countItems(inventory, item) > 0) return item;
        }
        return Items.AIR;
    }

    private static Block cropForPlantingItem(net.minecraft.world.item.Item item) {
        if (item == Items.WHEAT_SEEDS) return Blocks.WHEAT;
        if (item == Items.CARROT) return Blocks.CARROTS;
        if (item == Items.POTATO) return Blocks.POTATOES;
        if (item == Items.BEETROOT_SEEDS) return Blocks.BEETROOTS;
        return Blocks.AIR;
    }

    private static boolean startSeedFetch(Villager villager, ServerLevel level, BlockPos villageCenter, long nowTick) {
        StorageTarget target = findNearestStorageWithSeeds(villager, level, villageCenter);
        if (target == null || !VillageReservations.tryReserveStoragePositions(
            level, villager, List.of(target.chest()), "farmer_seed_fetch")) return false;
        CompoundTag data = villager.getPersistentData();
        data.putBoolean(SEED_FETCH_ACTIVE_KEY, true);
        data.putInt(SEED_FETCH_CHEST_X, target.chest().getX());
        data.putInt(SEED_FETCH_CHEST_Y, target.chest().getY());
        data.putInt(SEED_FETCH_CHEST_Z, target.chest().getZ());
        data.putInt(SEED_FETCH_STAND_X, target.stand().getX());
        data.putInt(SEED_FETCH_STAND_Y, target.stand().getY());
        data.putInt(SEED_FETCH_STAND_Z, target.stand().getZ());
        data.putLong(SEED_FETCH_STARTED, nowTick);
        data.putLong(SEED_FETCH_LAST_ORDER, nowTick);
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
            new WalkTarget(target.stand(), WALK_SPEED, 1));
        return true;
    }

    private static StorageTarget findNearestStorageWithSeeds(
            Villager villager, ServerLevel level, BlockPos villageCenter) {
        int radius = Math.min(12, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        StorageTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos chestPos = villageCenter.offset(dx, dy, dz);
                    if (!level.hasChunkAt(chestPos) || !isStorageBlock(level.getBlockState(chestPos))
                        || !VillageRegistry.isStorageForVillage(level, villageCenter, chestPos)
                        || !VillageReservations.isStorageAvailable(level, chestPos, villager)
                        || !(level.getBlockEntity(chestPos) instanceof Container container)
                        || firstPlantingItem(container) == Items.AIR) continue;
                    for (Direction direction : HORIZONTAL_DIRECTIONS) {
                        BlockPos stand = chestPos.relative(direction);
                        if (!isSafeStandPosition(level, stand)) continue;
                        double distance = villager.distanceToSqr(Vec3.atCenterOf(stand));
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = new StorageTarget(chestPos.immutable(), stand.immutable());
                        }
                    }
                }
            }
        }
        return best;
    }

    private static void continueSeedFetch(Villager villager, ServerLevel level, CompoundTag data, long nowTick) {
        if (nowTick - data.getLong(SEED_FETCH_STARTED) > SEED_FETCH_TIMEOUT_TICKS) {
            cancelSeedFetch(villager, data);
            data.putLong(NEXT_SEARCH, nowTick + RETRY_DELAY_TICKS);
            return;
        }
        BlockPos chest = new BlockPos(data.getInt(SEED_FETCH_CHEST_X),
            data.getInt(SEED_FETCH_CHEST_Y), data.getInt(SEED_FETCH_CHEST_Z));
        BlockPos stand = new BlockPos(data.getInt(SEED_FETCH_STAND_X),
            data.getInt(SEED_FETCH_STAND_Y), data.getInt(SEED_FETCH_STAND_Z));
        BlockPos home = VillagerRoles.getHome(villager);
        if (home == null) home = villager.blockPosition();
        if (!level.hasChunkAt(chest) || !isStorageBlock(level.getBlockState(chest))
            || !VillageRegistry.isStorageForVillage(level, home, chest)
            || !VillageReservations.isStorageAvailable(level, chest, villager)
            || !(level.getBlockEntity(chest) instanceof Container container)) {
            cancelSeedFetch(villager, data);
            data.putLong(NEXT_SEARCH, nowTick + RETRY_DELAY_TICKS);
            return;
        }

        if (villager.distanceToSqr(Vec3.atCenterOf(stand)) <= 2.8D * 2.8D) {
            int moved = withdrawPlantingItems(villager.getInventory(), container, 16);
            cancelSeedFetch(villager, data);
            data.putLong(NEXT_SEARCH, nowTick + (moved > 0 ? 20L : RETRY_DELAY_TICKS));
            if (moved <= 0) data.putBoolean(HARVEST_CYCLE_KEY, false);
            return;
        }
        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
            && nowTick - data.getLong(SEED_FETCH_LAST_ORDER) >= 40L) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(stand, WALK_SPEED, 1));
            data.putLong(SEED_FETCH_LAST_ORDER, nowTick);
        }
    }

    private static int withdrawPlantingItems(Container inventory, Container storage, int limit) {
        int movedTotal = 0;
        for (net.minecraft.world.item.Item item : List.of(
            Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS)) {
            for (int slot = 0; slot < storage.getContainerSize() && movedTotal < limit; slot++) {
                ItemStack stored = storage.getItem(slot);
                if (!stored.is(item)) continue;
                int offeredCount = Math.min(stored.getCount(), limit - movedTotal);
                ItemStack offered = new ItemStack(item, offeredCount);
                ItemStack remainder = inventory.addItem(offered);
                int moved = offeredCount - remainder.getCount();
                if (moved > 0) {
                    stored.shrink(moved);
                    if (stored.isEmpty()) storage.setItem(slot, ItemStack.EMPTY);
                    movedTotal += moved;
                    storage.setChanged();
                    inventory.setChanged();
                }
            }
        }
        return movedTotal;
    }

    private static void cancelSeedFetch(Villager villager, CompoundTag data) {
        BlockPos ownedStand = new BlockPos(data.getInt(SEED_FETCH_STAND_X),
            data.getInt(SEED_FETCH_STAND_Y), data.getInt(SEED_FETCH_STAND_Z));
        villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
            if (walk.getTarget().currentBlockPosition().equals(ownedStand)) {
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }
        });
        VillageReservations.release(data, "farmer_seed_fetch");
        data.putBoolean(SEED_FETCH_ACTIVE_KEY, false);
        data.remove(SEED_FETCH_CHEST_X); data.remove(SEED_FETCH_CHEST_Y); data.remove(SEED_FETCH_CHEST_Z);
        data.remove(SEED_FETCH_STAND_X); data.remove(SEED_FETCH_STAND_Y); data.remove(SEED_FETCH_STAND_Z);
        data.remove(SEED_FETCH_STARTED); data.remove(SEED_FETCH_LAST_ORDER);
    }

    private static int countItems(Container inventory, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static void clearHarvestRoute(Villager villager, CompoundTag data) {
        if (data.getBoolean(HARVEST_ACTIVE_KEY)) {
            BlockPos ownedTarget = new BlockPos(data.getInt(HARVEST_X), data.getInt(HARVEST_Y), data.getInt(HARVEST_Z));
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(ownedTarget)) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            });
        }
        data.putBoolean(HARVEST_ACTIVE_KEY, false);
        data.remove(HARVEST_X); data.remove(HARVEST_Y); data.remove(HARVEST_Z);
        data.remove(HARVEST_STARTED); data.remove(HARVEST_LAST_ORDER);
    }

    private static net.minecraft.world.item.Item seedItemForCrop(Block crop) {
        if (crop == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (crop == Blocks.CARROTS) return Items.CARROT;
        if (crop == Blocks.POTATOES) return Items.POTATO;
        if (crop == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        return Items.AIR;
    }

    private static boolean consumeOneItem(Container inv, net.minecraft.world.item.Item item) {
        return consumeItems(inv, item, 1);
    }

    private static boolean consumeItems(Container inv, net.minecraft.world.item.Item item, int count) {
        int available = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) available += inv.getItem(i).getCount();
        }
        if (available < count) return false;
        int rem = count;
        for (int i = 0; i < inv.getContainerSize() && rem > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(item)) continue;
            int take = Math.min(rem, s.getCount());
            s.shrink(take);
            rem -= take;
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }
        inv.setChanged();
        return rem == 0;
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

    public static int depositFarmerSurplus(Villager villager, Container destination, long nowTick) {
        if (villager == null || destination == null) return 0;
        int moved = depositSurplus(villager, destination);
        if (moved > 0) {
            VillagerProgression.recordCompletedWork(
                villager,
                com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_FARMER,
                2,
                true,
                nowTick
            );
        }
        return moved;
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
