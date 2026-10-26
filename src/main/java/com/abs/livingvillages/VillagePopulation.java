package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Population support built around vanilla villager breeding rather than spawning babies directly.
 * A real, unemployed courier may carry food from a physical village container to an adult villager.
 * The target villager keeps the food in its own inventory; vanilla AI decides whether/when breeding
 * occurs, validates the bed POI, creates the child, and grows the child at vanilla speed.
 */
public final class VillagePopulation {
    public static final String ACTIVE_KEY = "livingvillages_population_food_trip_active";

    private static final String P = "livingvillages_population_";
    private static final String PHASE = P + "food_trip_phase";
    private static final String TARGET_UUID = P + "food_target_uuid";
    private static final String CENTER_X = P + "center_x", CENTER_Y = P + "center_y", CENTER_Z = P + "center_z";
    private static final String STORAGE_X = P + "storage_x", STORAGE_Y = P + "storage_y", STORAGE_Z = P + "storage_z";
    private static final String STAND_X = P + "stand_x", STAND_Y = P + "stand_y", STAND_Z = P + "stand_z";
    private static final String STARTED = P + "started", LAST_ORDER = P + "last_order", NEXT_TRIP = P + "next_trip";
    private static final String PKG_BREAD = P + "package_bread";
    private static final String PKG_CARROT = P + "package_carrot";
    private static final String PKG_POTATO = P + "package_potato";
    private static final String PKG_BEETROOT = P + "package_beetroot";

    private static final int PHASE_TO_STORAGE = 1;
    private static final int PHASE_TO_RECIPIENT = 2;
    private static final int PHASE_RETURNING = 3;
    public static final int BREEDING_FOOD_POINTS = 12;
    private static final int ROUTE_REISSUE_TICKS = 40;
    private static final int TARGET_STAND_REACH_SQR = 4;
    private static final long TASK_TIMEOUT_TICKS = 12000L;
    private static final long RETRY_DELAY_TICKS = 1200L;
    private static final float WALK_SPEED = 0.55F;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Item[] ROOT_FOODS = {Items.CARROT, Items.POTATO, Items.BEETROOT};
    private static final Item[] FOOD_ORDER = {Items.CARROT, Items.POTATO, Items.BEETROOT, Items.BREAD};

    private VillagePopulation() {}

    /** Called by the active-area planner roughly every ten seconds. */
    public static void tickArea(ServerLevel level, BlockPos center, List<Villager> villagers) {
        if (VillageFarmBuilder.hasActiveProjectNear(level, center)
            || VillageBuilder.hasActiveProjectNear(level, center)) return;
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_POPULATION_SUPPORT.get()
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            || villagers.isEmpty() || !isDaytime(level)) {
            return;
        }

        List<Villager> adults = villagers.stream().filter(v -> v.isAlive() && !v.isBaby()).toList();
        if (adults.size() < 2 || countPotentialBeds(level, center) <= villagers.size()) return;
        if (villagers.stream().anyMatch(v -> v.getPersistentData().getBoolean(ACTIVE_KEY))) return;

        long foodReadyAdults = adults.stream().filter(v -> foodPoints(v) >= BREEDING_FOOD_POINTS).count();
        if (foodReadyAdults >= 2) return; // Vanilla handles pairing and the actual birth.

        long now = level.getGameTime();
        List<Container> storages = findStorages(level, center, null);
        if (storages.isEmpty() || storedFoodPoints(storages) <= LivingVillagesConfig.POPULATION_FOOD_RESERVE_POINTS.get()) {
            return;
        }

        List<Villager> couriers = adults.stream()
            .filter(v -> isAvailableCourier(v, now))
            .sorted(Comparator.comparing(v -> v.getUUID().toString()))
            .toList();
        if (couriers.isEmpty()) return;

        for (Villager courier : couriers) {
            for (Villager recipient : adults.stream()
                .filter(v -> v != courier && foodPoints(v) < BREEDING_FOOD_POINTS)
                .sorted(Comparator.comparingInt(VillagePopulation::foodPoints))
                .toList()) {
                StorageTarget target = findFoodStorage(level, center, courier, recipient, storages);
                if (target == null) continue;
                if (!VillageReservations.tryReserveStoragePositions(level, courier, List.of(target.chest()), "food_delivery")) continue;
                startTrip(courier, center, recipient, target, now);
                return; // One physical food trip at a time per active village area.
            }
        }
    }

    /** Advances a food delivery already in progress. */
    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager courier) || courier.level().isClientSide()
            || !courier.isAlive() || courier.tickCount % 5 != 0
            || !(courier.level() instanceof ServerLevel level)) return;

        CompoundTag data = courier.getPersistentData();
        if (!data.getBoolean(ACTIVE_KEY)) return;

        int phase = data.getInt(PHASE);
        boolean featureDisabled = !LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_POPULATION_SUPPORT.get();
        // A trip with no collected food may be cancelled. Once it carries real food, finish or return it.
        if ((featureDisabled || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING))
            && phase == PHASE_TO_STORAGE) {
            finishTrip(courier, data, level.getGameTime());
            return;
        }

        long now = level.getGameTime();
        if (!isDaytime(level) || courier.isSleeping()) {
            clearOwnedRoute(courier, data);
            return; // Preserve the current phase and any carried food overnight.
        }

        if (now - data.getLong(STARTED) > TASK_TIMEOUT_TICKS) {
            if (phase == PHASE_TO_STORAGE) {
                finishTrip(courier, data, now);
            } else {
                dropRemainingPackage(level, courier, data, courier.position());
                if (packageCount(data) == 0) finishTrip(courier, data, now);
                else data.putLong(STARTED, now); // Do not discard a real item if spawning the fallback failed.
            }
            return;
        }

        if (phase == PHASE_TO_STORAGE) {
            handleToStorage(courier, level, data, now);
        } else if (phase == PHASE_TO_RECIPIENT) {
            handleToRecipient(courier, level, data, now);
        } else if (phase == PHASE_RETURNING) {
            handleReturn(courier, level, data, now);
        } else {
            finishTrip(courier, data, now);
        }
    }

    private static boolean isAvailableCourier(Villager villager, long now) {
        if (!villager.isAlive() || villager.isBaby()) return false;
        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT) return false;
        CompoundTag data = villager.getPersistentData();
        if (!data.getString(VillagerRoles.ROLE_KEY).isEmpty() || data.getBoolean(ACTIVE_KEY)
            || now < data.getLong(NEXT_TRIP) || villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) return false;
        return !hasOtherTask(data) && !VillageBuilder.isProjectActive(villager);
    }

    private static boolean hasOtherTask(CompoundTag data) {
        return data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route")
            || data.getBoolean("livingvillages_autonomy_target_active")
            || data.getBoolean("livingvillages_logistics_active")
            || data.getBoolean("livingvillages_industry_route_active")
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || data.getBoolean(VillageBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY);
    }

    private static void startTrip(Villager courier, BlockPos center, Villager recipient, StorageTarget target, long now) {
        CompoundTag data = courier.getPersistentData();
        data.putBoolean(ACTIVE_KEY, true);
        data.putInt(PHASE, PHASE_TO_STORAGE);
        data.putString(TARGET_UUID, recipient.getUUID().toString());
        data.putInt(CENTER_X, center.getX()); data.putInt(CENTER_Y, center.getY()); data.putInt(CENTER_Z, center.getZ());
        data.putInt(STORAGE_X, target.chest().getX()); data.putInt(STORAGE_Y, target.chest().getY()); data.putInt(STORAGE_Z, target.chest().getZ());
        data.putLong(STARTED, now);
        data.putLong(LAST_ORDER, now);
        saveStand(data, target.stand());
        setWalkTarget(courier, target.stand());
    }

    private static void handleToStorage(Villager courier, ServerLevel level, CompoundTag data, long now) {
        BlockPos storagePos = storagePos(data);
        BlockPos stand = routeStand(data);
        if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
            || !VillageReservations.isStorageAvailable(level, storagePos, courier)
            || !(level.getBlockEntity(storagePos) instanceof Container storage)) {
            finishTrip(courier, data, now);
            return;
        }
        ensureRoute(courier, data, stand, now);
        if (!atStand(courier, stand)) return;

        BlockPos center = centerPos(data);
        List<Container> storages = findStorages(level, center, courier);
        Villager recipient = findVillager(level, center, data.getString(TARGET_UUID));
        if (recipient == null || recipient.isBaby() || !recipient.isAlive()
            || foodPoints(recipient) >= BREEDING_FOOD_POINTS) {
            finishTrip(courier, data, now); // No food has been withdrawn yet; retry later with a fresh plan.
            return;
        }

        int needed = BREEDING_FOOD_POINTS - foodPoints(recipient);
        Map<Item, Integer> plan = planFood(storage, needed, storedFoodPoints(storages));
        if (plan == null || !canFit(courier.getInventory(), plan) || !canFit(recipient.getInventory(), plan)) {
            finishTrip(courier, data, now);
            return;
        }
        if (!withdrawPlan(storage, courier, plan)) {
            finishTrip(courier, data, now);
            return;
        }
        savePackage(data, plan);
        data.putInt(PHASE, PHASE_TO_RECIPIENT);
        BlockPos recipientStand = findStandNear(level, recipient.blockPosition(), courier.blockPosition());
        if (recipientStand == null) {
            beginReturn(courier, level, data, now);
            return;
        }
        ensureRoute(courier, data, recipientStand, now);
    }

    private static void handleToRecipient(Villager courier, ServerLevel level, CompoundTag data, long now) {
        BlockPos center = centerPos(data);
        Villager recipient = findVillager(level, center, data.getString(TARGET_UUID));
        if (recipient == null || !recipient.isAlive() || recipient.isBaby()) {
            recipient = findHungryRecipient(level, center, courier, data);
            if (recipient == null) {
                beginReturn(courier, level, data, now);
                return;
            }
            data.putString(TARGET_UUID, recipient.getUUID().toString());
        }
        if (foodPoints(recipient) >= BREEDING_FOOD_POINTS) {
            Villager replacement = findHungryRecipient(level, center, courier, data);
            if (replacement == null) {
                beginReturn(courier, level, data, now);
                return;
            }
            recipient = replacement;
            data.putString(TARGET_UUID, recipient.getUUID().toString());
        }

        BlockPos stand = findStandNear(level, recipient.blockPosition(), courier.blockPosition());
        if (stand == null) {
            beginReturn(courier, level, data, now);
            return;
        }
        ensureRoute(courier, data, stand, now);
        if (courier.distanceToSqr(recipient) > 9.0D || !courier.hasLineOfSight(recipient)) return;

        boolean transferredAny = false;
        while (foodPoints(recipient) < BREEDING_FOOD_POINTS) {
            Item next = firstPackageItemThatFits(courier, recipient, data);
            if (next == null || !transferOneFood(courier, recipient, next)) break;
            decrementPackage(data, next, 1);
            transferredAny = true;
        }

        if (transferredAny) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                recipient.getX(), recipient.getY() + 1.0D, recipient.getZ(), 2, 0.25D, 0.2D, 0.25D, 0.0D);
        }
        if (foodPoints(recipient) >= BREEDING_FOOD_POINTS || firstPackageItemThatFits(courier, recipient, data) == null) {
            if (packageCount(data) > 0) beginReturn(courier, level, data, now);
            else finishTrip(courier, data, now);
        }
    }

    private static void beginReturn(Villager courier, ServerLevel level, CompoundTag data, long now) {
        if (packageCount(data) == 0) {
            finishTrip(courier, data, now);
            return;
        }
        BlockPos center = centerPos(data);
        BlockPos preferred = storagePos(data);
        StorageTarget target = findReturnStorage(level, center, courier.blockPosition(), preferred, courier);
        if (target == null) {
            dropRemainingPackage(level, courier, data, courier.position());
            if (packageCount(data) == 0) finishTrip(courier, data, now);
            else data.putLong(STARTED, now);
            return;
        }
        data.putInt(STORAGE_X, target.chest().getX()); data.putInt(STORAGE_Y, target.chest().getY()); data.putInt(STORAGE_Z, target.chest().getZ());
        data.putInt(PHASE, PHASE_RETURNING);
        ensureRoute(courier, data, target.stand(), now + ROUTE_REISSUE_TICKS);
    }

    private static void handleReturn(Villager courier, ServerLevel level, CompoundTag data, long now) {
        BlockPos storagePos = storagePos(data);
        BlockPos stand = routeStand(data);
        if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
            || !VillageReservations.isStorageAvailable(level, storagePos, courier)
            || !(level.getBlockEntity(storagePos) instanceof Container storage)) {
            beginReturn(courier, level, data, now);
            return;
        }
        ensureRoute(courier, data, stand, now);
        if (!atStand(courier, stand)) return;

        depositPackage(storage, courier, data);
        if (packageCount(data) > 0) {
            // The real food remains as a real item if the chest filled during the trip.
            dropRemainingPackage(level, courier, data, Vec3.atCenterOf(storagePos).add(0.0D, 0.3D, 0.0D));
        }
        if (packageCount(data) == 0) {
            finishTrip(courier, data, now);
        } else {
            data.putLong(STARTED, now);
            BlockPos newStand = findStandNear(level, storagePos, courier.blockPosition());
            if (newStand != null) ensureRoute(courier, data, newStand, now + ROUTE_REISSUE_TICKS);
        }
    }

    private static Villager findHungryRecipient(ServerLevel level, BlockPos center, Villager courier, CompoundTag courierData) {
        return VillageArea.findVillagers(level, center).stream()
            .filter(v -> v != courier && v.isAlive() && !v.isBaby()
                && foodPoints(v) < BREEDING_FOOD_POINTS && firstPackageItemThatFits(courier, v, courierData) != null)
            .min(Comparator.comparingInt((Villager v) -> foodPoints(v))
                .thenComparing(v -> v.getUUID().toString())).orElse(null);
    }

    private static Villager findVillager(ServerLevel level, BlockPos center, String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        for (Villager villager : VillageArea.findVillagers(level, center)) {
            if (villager.isAlive() && villager.getUUID().toString().equals(uuid)) return villager;
        }
        return null;
    }

    private static StorageTarget findFoodStorage(ServerLevel level, BlockPos center, Villager courier,
                                                  Villager recipient, List<Container> storages) {
        int gap = BREEDING_FOOD_POINTS - foodPoints(recipient);
        int globalPoints = storedFoodPoints(storages);
        StorageTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos chest = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(chest) || !isStorage(level.getBlockState(chest))
                        || !VillageReservations.isStorageAvailable(level, chest, courier)) continue;
                    if (!(level.getBlockEntity(chest) instanceof Container container)) continue;
                    Map<Item, Integer> plan = planFood(container, gap, globalPoints);
                    if (plan == null || !canFit(courier.getInventory(), plan) || !canFit(recipient.getInventory(), plan)) continue;
                    BlockPos stand = findStandNear(level, chest, courier.blockPosition());
                    if (stand == null) continue;
                    double distance = courier.distanceToSqr(Vec3.atCenterOf(stand));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new StorageTarget(chest.immutable(), stand.immutable());
                    }
                }
            }
        }
        return best;
    }

    private static StorageTarget findReturnStorage(ServerLevel level, BlockPos center, BlockPos origin,
                                                   BlockPos preferred, Villager courier) {
        if (level.hasChunkAt(preferred) && isStorage(level.getBlockState(preferred))
            && VillageReservations.isStorageAvailable(level, preferred, courier)) {
            BlockPos stand = findStandNear(level, preferred, origin);
            if (stand != null && VillageReservations.tryAddStorageReservation(level, courier, preferred, "food_delivery")) {
                return new StorageTarget(preferred.immutable(), stand.immutable());
            }
        }
        int radius = LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get();
        StorageTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos chest = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(chest) || !isStorage(level.getBlockState(chest))
                        || !VillageReservations.isStorageAvailable(level, chest, courier)
                        || !(level.getBlockEntity(chest) instanceof Container)) continue;
                    BlockPos stand = findStandNear(level, chest, origin);
                    if (stand == null || !VillageReservations.tryAddStorageReservation(level, courier, chest, "food_delivery")) continue;
                    double distance = origin.distSqr(stand);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new StorageTarget(chest.immutable(), stand.immutable());
                    }
                }
            }
        }
        return best;
    }

    private static Map<Item, Integer> planFood(Container source, int targetGap, int globalStoredPoints) {
        if (targetGap <= 0) return null;
        Map<Item, Integer> plan = new LinkedHashMap<>();
        int remaining = targetGap;
        for (Item crop : ROOT_FOODS) {
            int take = Math.min(countItem(source, crop), remaining);
            if (take > 0) {
                plan.put(crop, take);
                remaining -= take;
            }
            if (remaining <= 0) break;
        }
        if (remaining > 0) {
            int bread = Math.min(countItem(source, Items.BREAD), (remaining + 3) / 4);
            if (bread > 0) {
                plan.put(Items.BREAD, bread);
                remaining -= bread * 4;
            }
        }
        if (remaining > 0) return null;
        int cost = nutritionPoints(plan);
        if (globalStoredPoints - cost < LivingVillagesConfig.POPULATION_FOOD_RESERVE_POINTS.get()) return null;
        return plan;
    }

    private static boolean withdrawPlan(Container source, Villager courier, Map<Item, Integer> plan) {
        List<RemovedStack> removed = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : plan.entrySet()) {
            int remaining = entry.getValue();
            for (int slot = 0; slot < source.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = source.getItem(slot);
                if (!stack.is(entry.getKey())) continue;
                int amount = Math.min(remaining, stack.getCount());
                ItemStack taken = source.removeItem(slot, amount);
                if (!taken.isEmpty()) {
                    removed.add(new RemovedStack(slot, taken.copy()));
                    remaining -= taken.getCount();
                }
            }
            if (remaining > 0) {
                restoreRemoved(courier, source, removed);
                return false;
            }
        }
        source.setChanged();

        Map<Item, Integer> added = new LinkedHashMap<>();
        for (RemovedStack removal : removed) {
            ItemStack offered = removal.stack().copy();
            int amount = offered.getCount();
            ItemStack remainder = courier.getInventory().addItem(offered);
            int accepted = amount - remainder.getCount();
            if (accepted > 0) added.merge(removal.stack().getItem(), accepted, Integer::sum);
            if (!remainder.isEmpty()) {
                for (Map.Entry<Item, Integer> entry : added.entrySet()) removeItem(courier.getInventory(), entry.getKey(), entry.getValue());
                restoreRemoved(courier, source, removed);
                courier.getInventory().setChanged();
                return false;
            }
        }
        courier.getInventory().setChanged();
        return true;
    }

    private static void restoreRemoved(Villager courier, Container source, List<RemovedStack> removed) {
        for (RemovedStack removal : removed) {
            ItemStack current = source.getItem(removal.slot());
            ItemStack toRestore = removal.stack().copy();
            if (current.isEmpty()) {
                source.setItem(removal.slot(), toRestore);
            } else if (current.getItem() == toRestore.getItem()
                && current.getCount() + toRestore.getCount() <= current.getMaxStackSize()) {
                current.grow(toRestore.getCount());
            } else {
                ItemStack remainder = insertIntoContainer(source, toRestore);
                // If the original slot changed unexpectedly, drop only the uninserted remainder.
                // This preserves the real items instead of silently deleting them.
                if (!remainder.isEmpty()) courier.spawnAtLocation(remainder);
            }
        }
        source.setChanged();
    }

    private static void depositPackage(Container destination, Villager courier, CompoundTag data) {
        for (Item item : FOOD_ORDER) {
            int remaining = packageCount(data, item);
            while (remaining > 0 && canAcceptOne(destination, item)) {
                ItemStack removed = removeItem(courier.getInventory(), item, 1);
                if (removed.isEmpty()) {
                    data.putInt(packageKey(item), 0);
                    break;
                }
                ItemStack remainder = insertIntoContainer(destination, removed.copy());
                if (!remainder.isEmpty()) {
                    courier.getInventory().addItem(remainder);
                    break;
                }
                decrementPackage(data, item, 1);
                remaining--;
            }
        }
        courier.getInventory().setChanged();
        destination.setChanged();
    }

    private static void dropRemainingPackage(ServerLevel level, Villager courier, CompoundTag data, Vec3 position) {
        for (Item item : FOOD_ORDER) {
            int wanted = Math.min(packageCount(data, item), countItem(courier.getInventory(), item));
            if (wanted <= 0) {
                if (packageCount(data, item) > 0) data.putInt(packageKey(item), 0);
                continue;
            }
            ItemStack removed = removeItem(courier.getInventory(), item, wanted);
            if (removed.isEmpty()) continue;
            ItemEntity entity = new ItemEntity(level, position.x, position.y, position.z, removed.copy());
            entity.setPickUpDelay(10);
            if (level.addFreshEntity(entity)) {
                decrementPackage(data, item, removed.getCount());
            } else {
                ItemStack remainder = courier.getInventory().addItem(removed);
                if (!remainder.isEmpty()) courier.spawnAtLocation(remainder);
            }
        }
        courier.getInventory().setChanged();
    }

    private static Item firstPackageItemThatFits(Villager courier, Villager recipient, CompoundTag data) {
        for (Item item : FOOD_ORDER) {
            if (packageCount(data, item) > 0 && countItem(courier.getInventory(), item) > 0
                && canFit(recipient.getInventory(), Map.of(item, 1))) return item;
        }
        return null;
    }

    private static boolean transferOneFood(Villager courier, Villager recipient, Item item) {
        ItemStack removed = removeItem(courier.getInventory(), item, 1);
        if (removed.isEmpty()) return false;
        ItemStack remainder = recipient.getInventory().addItem(removed.copy());
        if (!remainder.isEmpty()) {
            ItemStack rollback = courier.getInventory().addItem(removed);
            if (!rollback.isEmpty()) courier.spawnAtLocation(rollback);
            return false;
        }
        courier.getInventory().setChanged();
        recipient.getInventory().setChanged();
        return true;
    }

    private static int packageCount(CompoundTag data) {
        int count = 0;
        for (Item item : FOOD_ORDER) count += packageCount(data, item);
        return count;
    }

    private static int packageCount(CompoundTag data, Item item) {
        return Math.max(0, data.getInt(packageKey(item)));
    }

    private static void savePackage(CompoundTag data, Map<Item, Integer> plan) {
        for (Item item : FOOD_ORDER) data.putInt(packageKey(item), plan.getOrDefault(item, 0));
    }

    private static void decrementPackage(CompoundTag data, Item item, int amount) {
        data.putInt(packageKey(item), Math.max(0, packageCount(data, item) - amount));
    }

    private static String packageKey(Item item) {
        if (item == Items.BREAD) return PKG_BREAD;
        if (item == Items.CARROT) return PKG_CARROT;
        if (item == Items.POTATO) return PKG_POTATO;
        if (item == Items.BEETROOT) return PKG_BEETROOT;
        throw new IllegalArgumentException("Unsupported population food: " + item);
    }

    private static void finishTrip(Villager courier, CompoundTag data, long now) {
        clearOwnedRoute(courier, data);
        VillageReservations.release(data, "food_delivery");
        data.putBoolean(ACTIVE_KEY, false);
        data.putLong(NEXT_TRIP, now + RETRY_DELAY_TICKS);
        for (String key : new String[] {PHASE, TARGET_UUID, CENTER_X, CENTER_Y, CENTER_Z,
            STORAGE_X, STORAGE_Y, STORAGE_Z, STAND_X, STAND_Y, STAND_Z, STARTED, LAST_ORDER,
            PKG_BREAD, PKG_CARROT, PKG_POTATO, PKG_BEETROOT}) data.remove(key);
    }

    private static void clearOwnedRoute(Villager villager, CompoundTag data) {
        if (!data.getBoolean(ACTIVE_KEY)) return;
        BlockPos stand = routeStand(data);
        villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
            if (walk.getTarget().currentBlockPosition().equals(stand)) {
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }
        });
    }

    private static void ensureRoute(Villager villager, CompoundTag data, BlockPos desiredStand, long now) {
        BlockPos currentStand = routeStand(data);
        boolean changed = !currentStand.equals(desiredStand);
        boolean noTarget = !villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET);
        if (changed || (noTarget && now - data.getLong(LAST_ORDER) >= ROUTE_REISSUE_TICKS)) {
            saveStand(data, desiredStand);
            setWalkTarget(villager, desiredStand);
            data.putLong(LAST_ORDER, now);
        }
    }

    private static void setWalkTarget(Villager villager, BlockPos stand) {
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
    }

    private static void saveStand(CompoundTag data, BlockPos pos) {
        data.putInt(STAND_X, pos.getX()); data.putInt(STAND_Y, pos.getY()); data.putInt(STAND_Z, pos.getZ());
    }

    private static BlockPos routeStand(CompoundTag data) {
        return new BlockPos(data.getInt(STAND_X), data.getInt(STAND_Y), data.getInt(STAND_Z));
    }

    private static BlockPos storagePos(CompoundTag data) {
        return new BlockPos(data.getInt(STORAGE_X), data.getInt(STORAGE_Y), data.getInt(STORAGE_Z));
    }

    private static BlockPos centerPos(CompoundTag data) {
        return new BlockPos(data.getInt(CENTER_X), data.getInt(CENTER_Y), data.getInt(CENTER_Z));
    }

    private static boolean atStand(Villager villager, BlockPos stand) {
        double dx = villager.getX() - (stand.getX() + 0.5D);
        double dy = villager.getY() - stand.getY();
        double dz = villager.getZ() - (stand.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz <= TARGET_STAND_REACH_SQR;
    }

    private static BlockPos findStandNear(ServerLevel level, BlockPos target, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : HORIZONTAL) {
            BlockPos stand = target.relative(direction);
            if (!isSafeStand(level, stand)) continue;
            double distance = origin.distSqr(stand);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = stand.immutable();
            }
        }
        return best;
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below())
            && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
            && level.getBlockState(pos.below()).blocksMotion();
    }

    private static boolean isDaytime(ServerLevel level) {
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return time >= 1000L && time < 12000L;
    }

    private static int countPotentialBeds(ServerLevel level, BlockPos center) {
        int radius = LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get();
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -8, -radius), center.offset(radius, 8, radius))) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.FOOT) continue;
            BlockPos head = pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
            if (!level.hasChunkAt(head) || !hasBabyClearance(level, pos) || !hasBabyClearance(level, head)) continue;
            count++;
        }
        return count;
    }

    private static boolean hasBabyClearance(ServerLevel level, BlockPos bedPart) {
        return level.getBlockState(bedPart.above()).isAir() && level.getBlockState(bedPart.above(2)).isAir();
    }

    private static List<Container> findStorages(ServerLevel level, BlockPos center, Villager owner) {
        List<Container> containers = new ArrayList<>();
        int radius = LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -8, -radius), center.offset(radius, 8, radius))) {
            if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                || !VillageReservations.isStorageAvailable(level, pos, owner)) continue;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Container container) containers.add(container);
        }
        return containers;
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static int storedFoodPoints(List<Container> storages) {
        int total = 0;
        for (Container storage : storages) total += foodPoints(storage);
        return total;
    }

    private static int foodPoints(Villager villager) {
        return foodPoints(villager.getInventory());
    }

    private static int foodPoints(Container container) {
        int points = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            points += stack.getCount() * foodPointValue(stack.getItem());
        }
        return points;
    }

    private static int nutritionPoints(Map<Item, Integer> plan) {
        int result = 0;
        for (Map.Entry<Item, Integer> entry : plan.entrySet()) result += foodPointValue(entry.getKey()) * entry.getValue();
        return result;
    }

    private static int foodPointValue(Item item) {
        if (item == Items.BREAD) return 4;
        if (item == Items.CARROT || item == Items.POTATO || item == Items.BEETROOT) return 1;
        return 0;
    }

    private static int countItem(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static ItemStack removeItem(Container container, Item item, int amount) {
        int remaining = amount;
        ItemStack result = ItemStack.EMPTY;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            ItemStack part = container.removeItem(slot, take);
            if (!part.isEmpty()) {
                if (result.isEmpty()) result = part;
                else result.grow(part.getCount());
                remaining -= part.getCount();
            }
        }
        if (!result.isEmpty()) container.setChanged();
        return result;
    }

    private static boolean canFit(Container inventory, Map<Item, Integer> plan) {
        List<ItemStack> simulated = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) simulated.add(inventory.getItem(slot).copy());
        for (Map.Entry<Item, Integer> entry : plan.entrySet()) {
            Item item = entry.getKey();
            int remaining = entry.getValue();
            int maxStack = Math.min(inventory.getMaxStackSize(), new ItemStack(item).getMaxStackSize());
            for (ItemStack existing : simulated) {
                if (remaining <= 0) break;
                if (!existing.isEmpty() && existing.is(item) && existing.getCount() < maxStack) {
                    int moved = Math.min(remaining, maxStack - existing.getCount());
                    existing.grow(moved);
                    remaining -= moved;
                }
            }
            while (remaining > 0) {
                int emptySlot = -1;
                for (int i = 0; i < simulated.size(); i++) {
                    if (simulated.get(i).isEmpty()) { emptySlot = i; break; }
                }
                if (emptySlot < 0) return false;
                int moved = Math.min(remaining, maxStack);
                simulated.set(emptySlot, new ItemStack(item, moved));
                remaining -= moved;
            }
        }
        return true;
    }

    private static boolean canAcceptOne(Container destination, Item item) {
        return canFit(destination, Map.of(item, 1));
    }

    private static ItemStack insertIntoContainer(Container destination, ItemStack offered) {
        ItemStack remaining = offered.copy();
        for (int slot = 0; slot < destination.getContainerSize() && !remaining.isEmpty(); slot++) {
            if (!destination.canPlaceItem(slot, remaining)) continue;
            ItemStack existing = destination.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)) {
                int room = Math.min(destination.getMaxStackSize(), existing.getMaxStackSize()) - existing.getCount();
                int moved = Math.min(room, remaining.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    remaining.shrink(moved);
                }
            }
        }
        for (int slot = 0; slot < destination.getContainerSize() && !remaining.isEmpty(); slot++) {
            if (!destination.canPlaceItem(slot, remaining) || !destination.getItem(slot).isEmpty()) continue;
            int moved = Math.min(Math.min(destination.getMaxStackSize(), remaining.getMaxStackSize()), remaining.getCount());
            destination.setItem(slot, new ItemStack(remaining.getItem(), moved));
            remaining.shrink(moved);
        }
        destination.setChanged();
        return remaining;
    }

    private record StorageTarget(BlockPos chest, BlockPos stand) {}
    private record RemovedStack(int slot, ItemStack stack) {}
}
