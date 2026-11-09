package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.abs.livingvillages.domain.VillageWorkOrderModel;

/**
 * Physical village logistics. Workers pick up nearby loose raw materials, then walk to an
 * actual chest/barrel and transfer only items they really carry. It deliberately does not
 * create resources, teleport workers, or interrupt their tree/mining/guard tasks.
 */
public final class VillageLogistics {
    private static final String P = "livingvillages_logistics_";
    private static final String ACTIVE = P + "active";
    private static final String CARGO_IN_TRANSIT = P + "cargo_in_transit";
    private static final String CHEST_X = P + "chest_x", CHEST_Y = P + "chest_y", CHEST_Z = P + "chest_z";
    private static final String STAND_X = P + "stand_x", STAND_Y = P + "stand_y", STAND_Z = P + "stand_z";
    private static final String STARTED = P + "started", LAST_ORDER = P + "last_order", NEXT_SCAN = P + "next_scan";
    private static final int TICK_STEP = 20;
    private static final int SEARCH_RETRY_TICKS = 40;
    private static final int SUCCESS_RETRY_TICKS = 40;
    private static final int TRIP_TIMEOUT_TICKS = 2400;
    private static final int PICKUP_RADIUS = 4;
    private static final float WALK_SPEED = 0.90F;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private VillageLogistics() {}

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()
            || !villager.isAlive() || villager.isBaby() || villager.tickCount % TICK_STEP != 0) return;
        if (!(villager.level() instanceof ServerLevel level)) return;

        CompoundTag data = villager.getPersistentData();
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_RESOURCE_LOGISTICS.get()) {
            cancelTrip(villager, data);
            return;
        }

        if (data.getBoolean(ACTIVE)) {
            if (hasHigherPriorityTask(data) || villager.isSleeping() || isNight(level)) {
                cancelTrip(villager, data);
                data.putLong(NEXT_SCAN, level.getGameTime() + SEARCH_RETRY_TICKS);
                return;
            }
            continueTrip(villager, level, data);
            return;
        }

        if (hasHigherPriorityTask(data) || villager.isSleeping() || isNight(level)
            || VillageDailyLife.isOnSocialVisit(villager)
            || (villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                && !VillageDailyLife.isVanillaIdle(villager))
            || VillagerRoles.shouldYieldToVanillaVillagerAi(villager)
            || level.getGameTime() < data.getLong(NEXT_SCAN)) return;

        // Pending construction materials take priority over generic stockpiling.
        Map<String, Integer> materialDeficits = pendingMaterialDeficits(villager, level);
        collectNearbyMaterials(villager, level, materialDeficits);
        if (!hasDeliverableMaterial(villager)) {
            data.putBoolean(CARGO_IN_TRANSIT, false);
            data.putLong(NEXT_SCAN, level.getGameTime() + SEARCH_RETRY_TICKS);
            return;
        }
        // This marker survives a missing/full destination chest and prevents duplicate pickup.
        data.putBoolean(CARGO_IN_TRANSIT, true);

        StorageTarget target = findNearestStorageWithRoom(villager, level);
        if (target == null) {
            if (LivingVillagesConfig.ENABLE_VILLAGE_INDUSTRY.get()) {
                VillageIndustry.tickArea(level, villager.blockPosition());
                target = findNearestStorageWithRoom(villager, level);
            }
            if (target == null) {
                data.putLong(NEXT_SCAN, level.getGameTime() + SEARCH_RETRY_TICKS);
                return;
            }
        }

        if (!VillageReservations.tryReserveStoragePositions(level, villager, List.of(target.storage()), "logistics_delivery")) {
            data.putLong(NEXT_SCAN, level.getGameTime() + SEARCH_RETRY_TICKS);
            return;
        }
        data.putBoolean(ACTIVE, true);
        data.putInt(CHEST_X, target.storage().getX()); data.putInt(CHEST_Y, target.storage().getY()); data.putInt(CHEST_Z, target.storage().getZ());
        data.putInt(STAND_X, target.stand().getX()); data.putInt(STAND_Y, target.stand().getY()); data.putInt(STAND_Z, target.stand().getZ());
        data.putLong(STARTED, level.getGameTime());
        data.putLong(LAST_ORDER, level.getGameTime());
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target.stand(), WALK_SPEED, 1));
    }

    private static void continueTrip(Villager villager, ServerLevel level, CompoundTag data) {
        long now = level.getGameTime();
        if (now - data.getLong(STARTED) > TRIP_TIMEOUT_TICKS) {
            cancelTrip(villager, data);
            data.putLong(NEXT_SCAN, now + SEARCH_RETRY_TICKS);
            return;
        }
        BlockPos storagePos = new BlockPos(data.getInt(CHEST_X), data.getInt(CHEST_Y), data.getInt(CHEST_Z));
        BlockPos standPos = new BlockPos(data.getInt(STAND_X), data.getInt(STAND_Y), data.getInt(STAND_Z));
        BlockPos ownershipCenter = VillagerRoles.getHome(villager);
        if (ownershipCenter == null) ownershipCenter = villager.blockPosition();
        if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
            || !VillageRegistry.isStorageForVillage(level, ownershipCenter, storagePos)
            || !VillageReservations.isStorageAvailable(level, storagePos, villager)
            || !(level.getBlockEntity(storagePos) instanceof Container container)) {
            cancelTrip(villager, data);
            data.putLong(NEXT_SCAN, now + SEARCH_RETRY_TICKS);
            return;
        }

        if (villager.distanceToSqr(Vec3.atCenterOf(standPos)) <= 2.8D * 2.8D) {
            int moved = depositCarriedMaterials(villager, container, now);
            data.putBoolean(CARGO_IN_TRANSIT, hasDeliverableMaterial(villager));
            cancelTrip(villager, data);
            data.putLong(NEXT_SCAN, now + (moved > 0 ? SUCCESS_RETRY_TICKS : SEARCH_RETRY_TICKS));
            return;
        }

        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
            && now - data.getLong(LAST_ORDER) >= 40L) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(standPos, WALK_SPEED, 1));
            data.putLong(LAST_ORDER, now);
        }
    }

    /**
     * Returns null when no materialized pending demand exists (legacy stockpiling mode),
     * or an exact shortage map for OPEN/WAITING orders.
     *
     * <p>The board hands out an immutable snapshot ({@code Map.copyOf}), while the pickup loop
     * decrements whatever it actually collected. Mutating the snapshot itself threw
     * {@code UnsupportedOperationException} from inside the entity tick event, which is a server
     * crash the first time a courier picked up a drop a pending order was really waiting for.</p>
     */
    private static Map<String, Integer> pendingMaterialDeficits(Villager courier, ServerLevel level) {
        BlockPos center = VillagerRoles.getHome(courier);
        if (center == null) center = courier.blockPosition();
        Map<String, Integer> deficits = VillageWorkOrders.pendingMaterialPickupDeficits(level, center);
        return deficits == null ? null : new HashMap<>(deficits);
    }

    /**
     * Collects loose materials this settlement's pending orders are still waiting for, and returns how
     * many items really moved into the courier's inventory.
     *
     * <p>Visible for in-world tests: the courier route is driven by an entity tick event, which a
     * GameTest cannot fire directly.</p>
     */
    public static int collectPendingMaterials(Villager villager, ServerLevel level) {
        if (villager == null || level == null) return 0;
        return collectNearbyMaterials(villager, level, pendingMaterialDeficits(villager, level));
    }

    private static int collectNearbyMaterials(
        Villager villager,
        ServerLevel level,
        Map<String, Integer> materialDeficits
    ) {
        AABB area = villager.getBoundingBox().inflate(PICKUP_RADIUS, 1.5D, PICKUP_RADIUS);
        // The delay check runs after the cheap item-kind test so only relevant drops pay for it.
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, area,
            item -> item.isAlive() && isRawResource(item.getItem())
                && villager.distanceToSqr(item) <= (double) PICKUP_RADIUS * PICKUP_RADIUS
                && pickupDelayHasExpired(item));
        drops.sort(Comparator
            .comparingInt((ItemEntity entity) -> materialDeficits == null ? 0
                : -materialDeficits.getOrDefault(BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).toString(), 0))
            .thenComparingDouble(villager::distanceToSqr));

        int collected = 0;
        for (ItemEntity entity : drops) {
            if (!entity.isAlive()) continue;
            ItemStack entityStack = entity.getItem();
            String itemId = BuiltInRegistries.ITEM.getKey(entityStack.getItem()).toString();
            int requested = materialDeficits == null ? entityStack.getCount()
                : materialDeficits.getOrDefault(itemId, 0);
            if (requested <= 0) continue;

            ItemStack offered = entityStack.copy();
            offered.setCount(Math.min(offered.getCount(), requested));
            int offeredCount = offered.getCount();
            ItemStack remainder = villager.getInventory().addItem(offered);
            int moved = offeredCount - remainder.getCount();
            if (moved <= 0) continue;

            entityStack.shrink(moved);
            if (entityStack.isEmpty()) entity.discard();
            collected += moved;
            if (materialDeficits != null) materialDeficits.put(itemId, Math.max(0, requested - moved));
            villager.getInventory().setChanged();
        }
        return collected;
    }

    /**
     * Respects the vanilla pickup delay: a player gets 40 ticks to take a dropped stack back, and this
     * mod's own food handoff delays its drops by 10 ticks. A courier must wait its turn like everybody
     * else, otherwise loose resources vanish from under the player's cursor.
     *
     * <p>{@code ItemEntity} keeps the delay in a private field and these mappings publish no getter for
     * it (only {@code setPickUpDelay}/{@code setNoPickUpDelay}), so the value is read from the entity's
     * own save data, where vanilla writes it under {@code PickupDelay}. The probe costs one small
     * compound tag per candidate drop and never touches the world.</p>
     */
    private static boolean pickupDelayHasExpired(ItemEntity entity) {
        CompoundTag probe = new CompoundTag();
        entity.saveWithoutId(probe);
        return probe.getShort("PickupDelay") <= 0;
    }

    /**
     * Nearest settlement storage this courier may deliver to, with a safe place to stand next to it.
     *
     * <p>The candidate box is the same one the previous scan covered - {@code radius} horizontally and
     * four blocks vertically around the courier - but the walk goes outward in rings and stops at the
     * first storage that is owned, reserved for this courier, has room and has a safe stand. Collecting
     * every candidate first used to read all 33x33x9 = 9,801 positions even when the chest was two
     * blocks away. Between exactly equidistant storages the chosen one may differ from the old sorted
     * order; a courier treats them as interchangeable.</p>
     */
    private static StorageTarget findNearestStorageWithRoom(Villager villager, ServerLevel level) {
        int radius = Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        BlockPos origin = villager.blockPosition();
        BlockPos home = VillagerRoles.getHome(villager);
        // Captured by the scan predicate below, so it is bound once and never reassigned.
        final BlockPos ownershipCenter = home == null ? origin : home;
        BlockPos[] standOut = new BlockPos[1];
        BlockPos storage = VillageScanRings.nearestInBox(origin, origin, radius, 4, 4, pos -> {
            if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                || !VillageRegistry.isStorageForVillage(level, ownershipCenter, pos)
                || !VillageReservations.isStorageAvailable(level, pos, villager)) return false;
            if (!(level.getBlockEntity(pos) instanceof Container container) || !hasAnySpace(villager, container)) {
                return false;
            }
            for (Direction direction : HORIZONTAL) {
                BlockPos stand = pos.relative(direction);
                if (isSafeStand(level, stand)) {
                    standOut[0] = stand.immutable();
                    return true;
                }
            }
            return false;
        });
        return storage == null || standOut[0] == null ? null : new StorageTarget(storage, standOut[0]);
    }

    /**
     * The storage a courier would deliver to, or {@code null}. Visible for in-world tests: the delivery
     * route itself is driven by an entity tick event, which a GameTest cannot fire directly.
     */
    static BlockPos findCourierStorage(Villager villager, ServerLevel level) {
        if (villager == null || level == null) return null;
        StorageTarget target = findNearestStorageWithRoom(villager, level);
        return target == null ? null : target.storage();
    }

    private static boolean hasAnySpace(Villager villager, Container container) {
        for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
            ItemStack source = villager.getInventory().getItem(i);
            if (source.isEmpty() || !isRawResource(source)) continue;
            if (hasSpaceFor(container, source)) return true;
        }
        return false;
    }

    public static int depositCarriedMaterials(Villager villager, Container destination, long nowTick) {
        if (villager == null || destination == null) return 0;
        int moved = depositMaterials(villager, destination);
        if (moved > 0) {
            if (villager.level() instanceof ServerLevel level) {
                BlockPos center = VillagerRoles.getHome(villager);
                if (center == null) center = villager.blockPosition();
                VillageWorkOrders.invalidateMaterialDemandCache(level, center);
                // A real delivery changes what the village owns, so quotas must see it at once
                // instead of waiting for the next cached planner refresh.
                VillageResourcePlanner.invalidate(level, center);
            }
            VillagerProgression.recordCompletedWork(
                villager,
                com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_COURIER,
                2,
                true,
                nowTick
            );
        }
        return moved;
    }

    private static int depositMaterials(Villager villager, Container destination) {
        int movedTotal = 0;
        var inventory = villager.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack source = inventory.getItem(i);
            if (source.isEmpty() || !isRawResource(source)) continue;
            int moved = insert(destination, source);
            if (moved > 0) {
                movedTotal += moved;
                if (source.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        if (movedTotal > 0) {
            inventory.setChanged();
            destination.setChanged();
        }
        return movedTotal;
    }

    private static int insert(Container destination, ItemStack source) {
        int before = source.getCount();
        for (int pass = 0; pass < 2 && !source.isEmpty(); pass++) {
            for (int i = 0; i < destination.getContainerSize() && !source.isEmpty(); i++) {
                if (!destination.canPlaceItem(i, source)) continue;
                ItemStack existing = destination.getItem(i);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, source)) continue;
                    int room = Math.min(existing.getMaxStackSize(), destination.getMaxStackSize()) - existing.getCount();
                    int amount = Math.min(Math.max(0, room), source.getCount());
                    if (amount > 0) { existing.grow(amount); source.shrink(amount); }
                } else if (existing.isEmpty()) {
                    int amount = Math.min(source.getCount(), Math.min(source.getMaxStackSize(), destination.getMaxStackSize()));
                    if (amount > 0) destination.setItem(i, source.split(amount));
                }
            }
        }
        return before - source.getCount();
    }

    private static boolean hasSpaceFor(Container container, ItemStack source) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!container.canPlaceItem(i, source)) continue;
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(existing, source)
                && existing.getCount() < Math.min(existing.getMaxStackSize(), container.getMaxStackSize())) return true;
        }
        return false;
    }

    private static boolean hasDeliverableMaterial(Villager villager) {
        for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
            if (isRawResource(villager.getInventory().getItem(i))) return true;
        }
        return false;
    }

    private static boolean isRawResource(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Block block = Block.byItem(stack.getItem());
        if (block != Blocks.AIR) {
            BlockState state = block.defaultBlockState();
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.WOOL) || VillageBuilder.isSupportedStructuralBlock(block)
                || block instanceof net.minecraft.world.level.block.FenceBlock
                || block instanceof net.minecraft.world.level.block.FenceGateBlock) return true;
        }
        return (VillageBuilder.isSupportedBedItem(stack.getItem())
                || Block.byItem(stack.getItem()) instanceof DoorBlock)
            || stack.is(Items.STICK) || stack.is(Items.COAL) || stack.is(Items.CHARCOAL)
            || stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)
            || stack.is(Items.SAND) || stack.is(Items.RED_SAND) || stack.is(Items.STONE)
            || stack.is(Items.RAW_IRON) || stack.is(Items.RAW_COPPER) || stack.is(Items.RAW_GOLD)
            || stack.is(Items.IRON_INGOT) || stack.is(Items.COPPER_INGOT) || stack.is(Items.GOLD_INGOT)
            || stack.is(Items.REDSTONE) || stack.is(Items.LAPIS_LAZULI) || stack.is(Items.DIAMOND)
            || stack.is(Items.EMERALD) || stack.is(Items.WHEAT) || stack.is(Items.WHEAT_SEEDS)
            || stack.is(Items.BEETROOT_SEEDS) || stack.is(Items.CARROT) || stack.is(Items.POTATO)
            || stack.is(Items.WATER_BUCKET) || stack.is(Items.COMPOSTER)
            || VillageFarmBuilder.isHoeItem(stack.getItem());
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below())
            && VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(pos))
            && VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(pos.above()))
            && level.getBlockState(pos.below()).blocksMotion();
    }

    private static boolean hasHigherPriorityTask(CompoundTag data) {
        return data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route")
            || data.getBoolean("livingvillages_autonomy_target_active")
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean(VillagerAutonomy.SEED_FETCH_ACTIVE_KEY)
            || data.getBoolean("livingvillages_industry_route_active")
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageLivestock.ACTIVE_KEY);
    }

    private static boolean isNight(ServerLevel level) {
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return time >= 12000L || time < 1000L;
    }

    private static void cancelTrip(Villager villager, CompoundTag data) {
        VillageReservations.release(data, "logistics_delivery");
        if (data.getBoolean(ACTIVE)) {
            BlockPos ownStand = new BlockPos(data.getInt(STAND_X), data.getInt(STAND_Y), data.getInt(STAND_Z));
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(ownStand)) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            });
        }
        data.putBoolean(ACTIVE, false);
        data.remove(CHEST_X); data.remove(CHEST_Y); data.remove(CHEST_Z);
        data.remove(STAND_X); data.remove(STAND_Y); data.remove(STAND_Z);
        data.remove(STARTED); data.remove(LAST_ORDER);
    }

    private record StorageTarget(BlockPos storage, BlockPos stand) {}
}
