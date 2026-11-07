package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded sheep-wool supply for pending village housing.
 *
 * <p>Only sheep inside a complete, closed 5x5 fence/wall pen are adopted. Their
 * village owner is stored on the sheep entity itself, so another settlement
 * cannot silently shear the same animal. Workers use real shears, collect the
 * actual wool item entities produced by Minecraft, and deliver them to owned
 * village storage using normal navigation.</p>
 */
public final class VillageLivestock {
    public static final String ACTIVE_KEY = "livingvillages_livestock_active";
    public static final String SHEEP_OWNER_KEY = "livingvillages_livestock_owner";

    private static final String ROUTE_ACTIVE = "livingvillages_livestock_route_active";
    private static final String ROUTE_KIND = "livingvillages_livestock_route_kind";
    private static final String ROUTE_X = "livingvillages_livestock_route_x";
    private static final String ROUTE_Y = "livingvillages_livestock_route_y";
    private static final String ROUTE_Z = "livingvillages_livestock_route_z";
    private static final String STAND_X = "livingvillages_livestock_stand_x";
    private static final String STAND_Y = "livingvillages_livestock_stand_y";
    private static final String STAND_Z = "livingvillages_livestock_stand_z";
    private static final String TARGET_SHEEP = "livingvillages_livestock_target_sheep";
    private static final String ROUTE_STARTED = "livingvillages_livestock_route_started";
    private static final String LAST_ROUTE_ORDER = "livingvillages_livestock_last_route_order";
    private static final String STARTED = "livingvillages_livestock_started";
    private static final String NEXT_ACTION = "livingvillages_livestock_next_action";
    private static final String FAILURES = "livingvillages_livestock_failures";

    private static final int ROUTE_TO_TOOL = 1;
    private static final int ROUTE_TO_SHEEP = 2;
    private static final int ROUTE_TO_WOOL_STORAGE = 3;
    private static final long RETRY_TICKS = 100L;
    private static final long FAILURE_BACKOFF_TICKS = 1200L;
    private static final long ROUTE_TIMEOUT_TICKS = 600L;
    private static final long ROUTE_REISSUE_TICKS = 40L;
    private static final int MAX_ROUTE_FAILURES = 3;
    private static final int MAX_SHEEP_CANDIDATES = 16;
    private static final double INTERACTION_DISTANCE_SQR = 4.5D * 4.5D;
    private static final float WALK_SPEED = 0.9F;

    private static final List<Item> WOOL_ITEMS = List.of(
        Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL, Items.LIGHT_BLUE_WOOL,
        Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL, Items.GRAY_WOOL,
        Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL,
        Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL, Items.BLACK_WOOL
    );

    private VillageLivestock() {}

    public static boolean needsWoolForHousing(ServerLevel level, BlockPos center) {
        if (level == null || center == null
            || !LivingVillagesConfig.ENABLE_VILLAGE_LIVESTOCK.get()
            || !LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()) return false;
        List<Villager> residents = VillageArea.findVillagers(level, center);
        if (residents.size() < 2) return false;

        boolean housingDemand = VillageBuilder.housingNeeded(level, center, residents.size())
            || hasPendingHouseOrder(level, center);
        if (!housingDemand || hasAvailableBed(level, center, residents)) return false;

        Map<Item, Integer> wool = countVillageWool(level, center, residents);
        boolean hasBedWool = wool.values().stream().anyMatch(count -> count >= 3);
        return !hasBedWool && hasManagedSheep(level, center);
    }

    public static boolean hasManagedSheep(ServerLevel level, BlockPos center) {
        return !findManagedSheep(level, center).isEmpty();
    }

    public static boolean hasShearsAvailable(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return false;
        List<Villager> residents = VillageArea.findVillagers(level, center);
        for (Villager resident : residents) {
            if (countItem(resident.getInventory(), Items.SHEARS) > 0) return true;
        }
        for (StorageRef ref : findStorages(level, center, null)) {
            if (countItem(ref.container(), Items.SHEARS) > 0) return true;
        }
        return false;
    }

    public static boolean canAssignHerder(ServerLevel level, BlockPos center, Villager candidate) {
        if (candidate == null || !candidate.isAlive() || !needsWoolForHousing(level, center)) return false;
        return countItem(candidate.getInventory(), Items.SHEARS) > 0
            || hasShearsInOwnedStorage(level, center);
    }

    public static boolean hasShearsRecipe(ServerLevel level, BlockPos center) {
        return needsWoolForHousing(level, center)
            && !hasShearsAvailable(level, center)
            && VillageRegistry.countAvailableOwnedStorageItems(level, center,
                Set.of("minecraft:iron_ingot")).getOrDefault("minecraft:iron_ingot", 0) >= 2;
    }

    static boolean hasValidShearsRecipe() {
        return recipeHasExactInputs();
    }

    private static boolean recipeHasExactInputs() {
        return Items.SHEARS.getDefaultInstance().getMaxStackSize() == 1
            && Items.IRON_INGOT.getDefaultInstance().getMaxStackSize() == 64;
    }

    public static boolean onVillagerTickEligible(Villager villager) {
        return villager != null && villager.isAlive() && !villager.isBaby()
            && (VillagerRoles.isHerder(villager) || villager.getPersistentData().getBoolean(ACTIVE_KEY));
    }

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager)
            || villager.level().isClientSide() || !onVillagerTickEligible(villager)
            || villager.tickCount % 5 != 0
            || !(villager.level() instanceof ServerLevel level)) return;

        CompoundTag data = villager.getPersistentData();
        if (!data.getBoolean(ACTIVE_KEY)) {
            BlockPos center = ownershipCenter(villager);
            if (!needsWoolForHousing(level, center)) return;
            data.putBoolean(ACTIVE_KEY, true);
            data.putLong(STARTED, level.getGameTime());
            data.putLong(NEXT_ACTION, 0L);
            data.putLong(LAST_ROUTE_ORDER, 0L);
        }

        long now = level.getGameTime();
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_VILLAGE_LIVESTOCK.get()
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            || VillagerRoles.shouldYieldToVanillaVillagerAi(villager)
            || isNightOrSleeping(level, villager)
            || VillageRegistry.isVillageAlarmActive(level, ownershipCenter(villager), now)
            || hasHigherPriorityTask(data)) {
            pauseOwnedRoute(villager, data);
            return;
        }

        if (now < data.getLong(NEXT_ACTION)) return;
        if (now - data.getLong(STARTED) > 24000L && !hasWoolInInventory(villager)) {
            finishTask(villager, data, now + FAILURE_BACKOFF_TICKS);
            return;
        }

        if (data.getBoolean(ROUTE_ACTIVE)) {
            continueRoute(villager, level, data, now);
            return;
        }

        if (hasWoolInInventory(villager)) {
            StorageTarget target = findNearestStorageWithWoolRoom(villager, level);
            if (target == null) {
                data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                VillageRegistry.recordDiagnostic(level, ownershipCenter(villager), "Животновод",
                    "Шерсть собрана, но в принадлежащих деревне хранилищах нет свободного места.");
                return;
            }
            setRoute(villager, data, target.stand(), target.storage(), ROUTE_TO_WOOL_STORAGE, now, null);
            return;
        }

        if (countItem(villager.getInventory(), Items.SHEARS) > 0) {
            Sheep target = findReadySheep(villager, level);
            if (target == null) {
                data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                return;
            }
            PathTarget route = findSheepRoute(villager, target);
            if (route == null) {
                recordRouteFailure(villager, data, now);
                return;
            }
            data.putString(TARGET_SHEEP, target.getUUID().toString());
            setRoute(villager, data, route.stand(), target.blockPosition(), ROUTE_TO_SHEEP, now, null);
            return;
        }

        StorageTarget tool = findNearestStorageWithItem(villager, level, Items.SHEARS);
        if (tool == null) {
            data.putLong(NEXT_ACTION, now + FAILURE_BACKOFF_TICKS);
            VillageRegistry.recordDiagnostic(level, ownershipCenter(villager), "Животновод",
                "Для сбора шерсти нужны настоящие ножницы в инвентаре или принадлежащем деревне складе.");
            return;
        }
        setRoute(villager, data, tool.stand(), tool.storage(), ROUTE_TO_TOOL, now, Items.SHEARS);
    }

    private static void continueRoute(Villager villager, ServerLevel level, CompoundTag data, long now) {
        int kind = data.getInt(ROUTE_KIND);
        BlockPos storagePos = routeTarget(data);
        BlockPos stand = routeStand(data);
        if (kind == ROUTE_TO_SHEEP) {
            Sheep sheep = findTargetSheep(level, villager, data.getString(TARGET_SHEEP));
            if (sheep == null || !isManagedByVillage(level, ownershipCenter(villager), sheep)
                || !isInsideClosedPen(level, sheep.blockPosition())) {
                clearRoute(villager, data);
                data.remove(TARGET_SHEEP);
                recordRouteFailure(villager, data, now);
                return;
            }
            if (now - data.getLong(ROUTE_STARTED) > ROUTE_TIMEOUT_TICKS) {
                clearRoute(villager, data);
                data.remove(TARGET_SHEEP);
                recordRouteFailure(villager, data, now);
                return;
            }
            if (villager.distanceToSqr(sheep) <= INTERACTION_DISTANCE_SQR
                && canSeeTarget(level, villager, sheep)) {
                ItemStack shears = firstShears(villager.getInventory());
                boolean sheared = shears != null && shearManagedSheep(level, sheep, shears,
                    VillageRegistry.villageId(level, ownershipCenter(villager)));
                if (sheared) {
                    collectNearbyWool(villager, level, sheep);
                    data.putInt(FAILURES, 0);
                    VillageRegistry.recordDiagnostic(level, ownershipCenter(villager), "Животновод",
                        "Собрана настоящая шерсть с овцы в загоне.");
                } else {
                    recordRouteFailure(villager, data, now);
                }
                clearRoute(villager, data);
                data.remove(TARGET_SHEEP);
                data.putLong(NEXT_ACTION, now + 20L);
                return;
            }
            reissueRoute(villager, data, stand, now);
            return;
        }

        if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
            || !VillageRegistry.isStorageForVillage(level, ownershipCenter(villager), storagePos)
            || !VillageReservations.isStorageAvailable(level, storagePos, villager)
            || !(level.getBlockEntity(storagePos) instanceof Container storage)) {
            clearRoute(villager, data);
            VillageReservations.release(data, "livestock");
            data.putLong(NEXT_ACTION, now + RETRY_TICKS);
            return;
        }
        if (now - data.getLong(ROUTE_STARTED) > ROUTE_TIMEOUT_TICKS) {
            clearRoute(villager, data);
            VillageReservations.release(data, "livestock");
            recordRouteFailure(villager, data, now);
            return;
        }
        if (!atRouteTarget(villager, stand, data, now)) return;

        if (kind == ROUTE_TO_TOOL) {
            fetchShears(villager, level, storage);
        } else if (kind == ROUTE_TO_WOOL_STORAGE) {
            depositWool(villager, storage);
        }
        storage.setChanged();
        villager.getInventory().setChanged();
        VillageReservations.release(data, "livestock");
        clearRoute(villager, data);
        data.putLong(NEXT_ACTION, now + 20L);
    }

    static boolean isInsideClosedPen(ServerLevel level, BlockPos animalPos) {
        if (level == null || animalPos == null || !level.hasChunkAt(animalPos)) return false;
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                BlockPos penCenter = animalPos.offset(offsetX, 0, offsetZ);
                int gates = 0;
                boolean closed = true;
                for (int dx = -2; dx <= 2 && closed; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
                        BlockPos boundary = penCenter.offset(dx, 0, dz);
                        if (!level.hasChunkAt(boundary)) { closed = false; break; }
                        BlockState state = level.getBlockState(boundary);
                        if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof WallBlock) continue;
                        if (state.getBlock() instanceof FenceGateBlock
                            && !state.getValue(FenceGateBlock.OPEN)) {
                            gates++;
                            continue;
                        }
                        closed = false;
                        break;
                    }
                }
                if (closed && gates >= 1 && level.getBlockState(animalPos.below()).blocksMotion()) return true;
            }
        }
        return false;
    }

    static boolean tryClaimSheepForVillage(ServerLevel level, Sheep sheep, UUID villageId) {
        if (level == null || sheep == null || villageId == null || !sheep.isAlive()
            || !isInsideClosedPen(level, sheep.blockPosition())) return false;
        CompoundTag data = sheep.getPersistentData();
        String current = data.getString(SHEEP_OWNER_KEY);
        if (current.isBlank()) {
            data.putString(SHEEP_OWNER_KEY, villageId.toString());
            return true;
        }
        return current.equals(villageId.toString());
    }

    static boolean shearManagedSheep(ServerLevel level, Sheep sheep, ItemStack shears, UUID villageId) {
        if (level == null || sheep == null || shears == null || villageId == null
            || !sheep.isAlive() || !shears.is(Items.SHEARS) || shears.isEmpty()
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            || !tryClaimSheepForVillage(level, sheep, villageId)
            || !sheep.getPersistentData().getString(SHEEP_OWNER_KEY).equals(villageId.toString())
            || !sheep.readyForShearing()) return false;
        sheep.shear(level, SoundSource.NEUTRAL, shears);
        return sheep.isSheared();
    }

    private static List<Sheep> findManagedSheep(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return List.of();
        UUID owner = VillageRegistry.villageId(level, center);
        if (owner == null) return List.of();
        int radius = Math.max(8, Math.min(24, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get()));
        AABB area = new AABB(center).inflate(radius, 6.0D, radius);
        List<Sheep> sheep = level.getEntitiesOfClass(Sheep.class, area,
            candidate -> candidate.isAlive() && !candidate.isBaby()
                && candidate.distanceToSqr(Vec3.atCenterOf(center)) <= (double) radius * radius);
        sheep.sort(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(Vec3.atCenterOf(center))));
        List<Sheep> managed = new ArrayList<>();
        for (Sheep candidate : sheep) {
            if (managed.size() >= MAX_SHEEP_CANDIDATES) break;
            if (!isInsideClosedPen(level, candidate.blockPosition())) continue;
            if (tryClaimSheepForVillage(level, candidate, owner)) managed.add(candidate);
        }
        return managed;
    }

    private static Sheep findReadySheep(Villager worker, ServerLevel level) {
        BlockPos center = ownershipCenter(worker);
        List<Villager> residents = VillageArea.findVillagers(level, center);
        Map<Item, Integer> woolCounts = countVillageWool(level, center, residents);
        List<Sheep> sheep = findManagedSheep(level, center);
        return sheep.stream()
            .filter(Sheep::readyForShearing)
            .filter(candidate -> woolCounts.getOrDefault(woolItem(candidate), 0) < 3)
            .filter(candidate -> worker.getNavigation().createPath(candidate, 2) != null)
            .min(Comparator.comparingDouble(candidate -> worker.distanceToSqr(candidate)))
            .orElse(null);
    }

    private static boolean isManagedByVillage(ServerLevel level, BlockPos center, Sheep sheep) {
        UUID owner = VillageRegistry.villageId(level, center);
        return owner != null && tryClaimSheepForVillage(level, sheep, owner)
            && sheep.getPersistentData().getString(SHEEP_OWNER_KEY).equals(owner.toString());
    }

    private static boolean hasAvailableBed(ServerLevel level, BlockPos center, List<Villager> residents) {
        Set<String> wanted = VillageBuilder.ALL_BED_ITEMS.stream()
            .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
            .collect(java.util.stream.Collectors.toSet());
        Map<String, Integer> storage = VillageRegistry.countAvailableOwnedStorageItems(level, center, wanted);
        for (Item bed : VillageBuilder.ALL_BED_ITEMS) {
            String key = BuiltInRegistries.ITEM.getKey(bed).toString();
            if (storage.getOrDefault(key, 0) > 0) return true;
            for (Villager resident : residents) {
                if (countItem(resident.getInventory(), bed) > 0) return true;
            }
        }
        return false;
    }

    private static Map<Item, Integer> countVillageWool(ServerLevel level, BlockPos center, List<Villager> residents) {
        Set<String> wanted = WOOL_ITEMS.stream()
            .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
            .collect(java.util.stream.Collectors.toSet());
        Map<String, Integer> storage = VillageRegistry.countAvailableOwnedStorageItems(level, center, wanted);
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (Item wool : WOOL_ITEMS) totals.put(wool, storage.getOrDefault(BuiltInRegistries.ITEM.getKey(wool).toString(), 0));
        for (Villager resident : residents) {
            Container inventory = resident.getInventory();
            for (Item wool : WOOL_ITEMS) totals.merge(wool, countItem(inventory, wool), Integer::sum);
        }
        return totals;
    }

    private static boolean hasPendingHouseOrder(ServerLevel level, BlockPos center) {
        return VillageWorkOrders.list(level, center, level.getGameTime()).stream().anyMatch(order ->
            "house".equals(order.kind())
                && (order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.OPEN
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.WAITING_FOR_RESOURCES
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.READY));
    }

    private static boolean hasShearsInOwnedStorage(ServerLevel level, BlockPos center) {
        for (StorageRef ref : findStorages(level, center, null)) {
            if (countItem(ref.container(), Items.SHEARS) > 0) return true;
        }
        return false;
    }

    private static Sheep findTargetSheep(ServerLevel level, Villager worker, String uuidText) {
        UUID targetId;
        try { targetId = UUID.fromString(uuidText); }
        catch (RuntimeException ignored) { return null; }
        AABB area = worker.getBoundingBox().inflate(24.0D, 8.0D, 24.0D);
        return level.getEntitiesOfClass(Sheep.class, area,
            candidate -> candidate.isAlive() && candidate.getUUID().equals(targetId)).stream().findFirst().orElse(null);
    }

    private static PathTarget findSheepRoute(Villager worker, Sheep sheep) {
        var path = worker.getNavigation().createPath(sheep, 2);
        if (path == null || !path.canReach()) return null;
        return new PathTarget(sheep.blockPosition().immutable());
    }

    private static void collectNearbyWool(Villager worker, ServerLevel level, Sheep sheep) {
        AABB area = sheep.getBoundingBox().inflate(1.5D, 1.0D, 1.5D);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, area,
            item -> item.isAlive() && isWool(item.getItem().getItem()));
        for (ItemEntity drop : drops) {
            ItemStack offered = drop.getItem().copy();
            ItemStack remainder = worker.getInventory().addItem(offered);
            if (remainder.isEmpty()) drop.discard();
            else drop.setItem(remainder);
        }
        worker.getInventory().setChanged();
    }

    private static void fetchShears(Villager worker, ServerLevel level, Container storage) {
        if (countItem(worker.getInventory(), Items.SHEARS) > 0) return;
        if (countItem(storage, Items.SHEARS) < 1 || inventoryCapacityFor(worker.getInventory(), Items.SHEARS) < 1) return;
        ItemStack removed = removeOne(storage, Items.SHEARS);
        if (removed.isEmpty()) return;
        ItemStack remainder = worker.getInventory().addItem(removed.copy());
        if (!remainder.isEmpty()) insert(storage, remainder);
        worker.getInventory().setChanged();
    }

    private static void depositWool(Villager worker, Container storage) {
        Container inventory = worker.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !isWool(stack.getItem())) continue;
            ItemStack offered = stack.copy();
            int moved = insert(storage, offered);
            if (moved > 0) stack.shrink(moved);
        }
        inventory.setChanged();
    }

    private static StorageTarget findNearestStorageWithItem(Villager worker, ServerLevel level, Item item) {
        BlockPos center = ownershipCenter(worker);
        List<StorageRef> refs = findStorages(level, center, worker);
        refs.sort(Comparator.comparingDouble(ref -> worker.distanceToSqr(Vec3.atCenterOf(ref.pos()))));
        for (StorageRef ref : refs) {
            if (countItem(ref.container(), item) <= 0) continue;
            BlockPos stand = findSafeStand(level, worker, ref.pos());
            if (stand != null && VillageReservations.tryAddStorageReservation(level, worker, ref.pos(), "livestock")) {
                return new StorageTarget(ref.pos(), stand);
            }
        }
        return null;
    }

    private static StorageTarget findNearestStorageWithWoolRoom(Villager worker, ServerLevel level) {
        BlockPos center = ownershipCenter(worker);
        List<StorageRef> refs = findStorages(level, center, worker);
        refs.sort(Comparator.comparingDouble(ref -> worker.distanceToSqr(Vec3.atCenterOf(ref.pos()))));
        for (StorageRef ref : refs) {
            boolean room = false;
            for (int slot = 0; slot < worker.getInventory().getContainerSize(); slot++) {
                ItemStack stack = worker.getInventory().getItem(slot);
                if (!stack.isEmpty() && isWool(stack.getItem()) && canInsert(ref.container(), stack)) {
                    room = true;
                    break;
                }
            }
            if (!room) continue;
            BlockPos stand = findSafeStand(level, worker, ref.pos());
            if (stand != null && VillageReservations.tryAddStorageReservation(level, worker, ref.pos(), "livestock")) {
                return new StorageTarget(ref.pos(), stand);
            }
        }
        return null;
    }

    private static List<StorageRef> findStorages(ServerLevel level, BlockPos center, Villager owner) {
        int radius = Math.min(16, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        List<StorageRef> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -6; dy <= 6; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))
                        || !VillageRegistry.isStorageForVillage(level, center, pos)
                        || !VillageReservations.isStorageAvailable(level, pos, owner)) continue;
                    if (level.getBlockEntity(pos) instanceof Container container) {
                        found.add(new StorageRef(pos.immutable(), container));
                    }
                }
            }
        }
        return found;
    }

    private static BlockPos findSafeStand(ServerLevel level, Villager worker, BlockPos target) {
        List<BlockPos> stands = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stand = target.relative(direction);
            if (!isSafeStand(level, stand)) continue;
            var path = worker.getNavigation().createPath(stand, 0);
            if (path != null && path.canReach()) stands.add(stand.immutable());
        }
        stands.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(Vec3.atCenterOf(pos))));
        return stands.isEmpty() ? null : stands.get(0);
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below())
            && (level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced())
            && (level.getBlockState(pos.above()).isAir() || level.getBlockState(pos.above()).canBeReplaced())
            && level.getBlockState(pos.below()).blocksMotion();
    }

    private static void setRoute(Villager worker, CompoundTag data, BlockPos stand, BlockPos target,
                                 int kind, long now, Item item) {
        clearRoute(worker, data);
        data.putBoolean(ROUTE_ACTIVE, true);
        data.putInt(ROUTE_KIND, kind);
        data.putInt(ROUTE_X, target.getX()); data.putInt(ROUTE_Y, target.getY()); data.putInt(ROUTE_Z, target.getZ());
        data.putInt(STAND_X, stand.getX()); data.putInt(STAND_Y, stand.getY()); data.putInt(STAND_Z, stand.getZ());
        data.putLong(ROUTE_STARTED, now);
        data.putLong(LAST_ROUTE_ORDER, now);
        worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
    }

    private static boolean atRouteTarget(Villager worker, BlockPos stand, CompoundTag data, long now) {
        if (worker.distanceToSqr(Vec3.atCenterOf(stand)) <= 2.8D * 2.8D) {
            clearOwnedWalkTarget(worker, stand);
            return true;
        }
        reissueRoute(worker, data, stand, now);
        return false;
    }

    private static void reissueRoute(Villager worker, CompoundTag data, BlockPos stand, long now) {
        boolean wrongTarget = worker.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
            .map(walk -> !walk.getTarget().currentBlockPosition().equals(stand)).orElse(true);
        if (wrongTarget || now - data.getLong(LAST_ROUTE_ORDER) >= ROUTE_REISSUE_TICKS) {
            worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
            data.putLong(LAST_ROUTE_ORDER, now);
        }
    }

    private static boolean canSeeTarget(ServerLevel level, Villager worker, Sheep sheep) {
        net.minecraft.world.phys.HitResult hit = level.clip(new net.minecraft.world.level.ClipContext(
            worker.getEyePosition(), sheep.getEyePosition(),
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, worker));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
            || (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                && blockHit.getBlockPos().distSqr(sheep.blockPosition()) <= 1);
    }

    private static void recordRouteFailure(Villager villager, CompoundTag data, long now) {
        int failures = data.getInt(FAILURES) + 1;
        data.putInt(FAILURES, failures);
        data.putLong(NEXT_ACTION, now + (failures >= MAX_ROUTE_FAILURES ? FAILURE_BACKOFF_TICKS : RETRY_TICKS));
        if (failures >= MAX_ROUTE_FAILURES) data.putInt(FAILURES, 0);
    }

    private static void pauseOwnedRoute(Villager villager, CompoundTag data) {
        if (!data.getBoolean(ROUTE_ACTIVE)) return;
        clearOwnedWalkTarget(villager, routeStand(data));
    }

    private static void clearRoute(Villager villager, CompoundTag data) {
        if (data.getBoolean(ROUTE_ACTIVE)) clearOwnedWalkTarget(villager, routeStand(data));
        data.putBoolean(ROUTE_ACTIVE, false);
        data.remove(ROUTE_KIND); data.remove(ROUTE_X); data.remove(ROUTE_Y); data.remove(ROUTE_Z);
        data.remove(STAND_X); data.remove(STAND_Y); data.remove(STAND_Z);
        data.remove(ROUTE_STARTED); data.remove(LAST_ROUTE_ORDER);
    }

    private static void clearOwnedWalkTarget(Villager villager, BlockPos stand) {
        villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
            if (walk.getTarget().currentBlockPosition().equals(stand)) {
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }
        });
    }

    private static void finishTask(Villager villager, CompoundTag data, long retryAt) {
        clearRoute(villager, data);
        VillageReservations.release(data, "livestock");
        data.putBoolean(ACTIVE_KEY, false);
        data.remove(TARGET_SHEEP);
        data.remove(STARTED);
        data.remove(FAILURES);
        data.putLong(NEXT_ACTION, retryAt);
    }

    private static boolean hasWoolInInventory(Villager villager) {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (!stack.isEmpty() && isWool(stack.getItem())) return true;
        }
        return false;
    }

    private static boolean hasHigherPriorityTask(CompoundTag data) {
        return data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route")
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || data.getBoolean("livingvillages_logistics_active")
            || data.getBoolean(VillageIndustry.ACTIVE_ROUTE_KEY)
            || data.getBoolean(VillageBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean("livingvillages_autonomy_target_active");
    }

    private static boolean isNightOrSleeping(ServerLevel level, Villager villager) {
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return villager.isSleeping() || time < 1000L || time >= 12000L;
    }

    private static BlockPos ownershipCenter(Villager villager) {
        BlockPos home = VillagerRoles.getHome(villager);
        return home == null ? villager.blockPosition() : home;
    }

    private static Sheep findReadySheep(Villager worker, ServerLevel level) {
        BlockPos center = ownershipCenter(worker);
        List<Villager> residents = VillageArea.findVillagers(level, center);
        Map<Item, Integer> woolCounts = countVillageWool(level, center, residents);
        return findManagedSheep(level, center).stream()
            .filter(Sheep::readyForShearing)
            .filter(sheep -> woolCounts.getOrDefault(woolItem(sheep), 0) < 3)
            .filter(sheep -> {
                var path = worker.getNavigation().createPath(sheep, 2);
                return path != null && path.canReach();
            })
            .min(Comparator.comparingDouble(worker::distanceToSqr))
            .orElse(null);
    }

    private static Item woolItem(Sheep sheep) {
        ResourceLocation id = ResourceLocation.tryParse("minecraft:" + sheep.getColor().getName() + "_wool");
        if (id == null) return Items.WHITE_WOOL;
        Item item = BuiltInRegistries.ITEM.get(id);
        return isWool(item) ? item : Items.WHITE_WOOL;
    }

    private static boolean isWool(Item item) {
        return item != null && item != Items.AIR
            && Block.byItem(item).defaultBlockState().is(net.minecraft.tags.BlockTags.WOOL);
    }

    private static boolean isManagedByVillage(ServerLevel level, BlockPos center, Sheep sheep) {
        UUID villageId = VillageRegistry.villageId(level, center);
        return villageId != null && tryClaimSheepForVillage(level, sheep, villageId)
            && sheep.getPersistentData().getString(SHEEP_OWNER_KEY).equals(villageId.toString());
    }

    private static List<Sheep> findManagedSheep(ServerLevel level, BlockPos center) {
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId == null) return List.of();
        int radius = Math.max(8, Math.min(24, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get()));
        AABB area = new AABB(center).inflate(radius, 6.0D, radius);
        List<Sheep> candidates = level.getEntitiesOfClass(Sheep.class, area,
            sheep -> sheep.isAlive() && !sheep.isBaby()
                && sheep.distanceToSqr(Vec3.atCenterOf(center)) <= (double) radius * radius);
        candidates.sort(Comparator.comparingDouble(sheep -> sheep.distanceToSqr(Vec3.atCenterOf(center))));
        List<Sheep> managed = new ArrayList<>();
        for (Sheep sheep : candidates) {
            if (managed.size() >= MAX_SHEEP_CANDIDATES) break;
            if (!isInsideClosedPen(level, sheep.blockPosition())) continue;
            if (tryClaimSheepForVillage(level, sheep, villageId)) managed.add(sheep);
        }
        return managed;
    }

    private static boolean tryClaimSheepForVillage(ServerLevel level, Sheep sheep, UUID villageId) {
        if (level == null || sheep == null || villageId == null || !sheep.isAlive()
            || !isInsideClosedPen(level, sheep.blockPosition())) return false;
        CompoundTag data = sheep.getPersistentData();
        String owner = data.getString(SHEEP_OWNER_KEY);
        if (owner.isBlank()) {
            data.putString(SHEEP_OWNER_KEY, villageId.toString());
            return true;
        }
        return owner.equals(villageId.toString());
    }

    private static boolean hasClosedPenSheep(ServerLevel level, BlockPos center) {
        return !findManagedSheep(level, center).isEmpty();
    }

    private static boolean hasAvailableBed(ServerLevel level, BlockPos center, List<Villager> residents) {
        Set<String> bedIds = VillageBuilder.ALL_BED_ITEMS.stream()
            .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
            .collect(java.util.stream.Collectors.toSet());
        Map<String, Integer> stored = VillageRegistry.countAvailableOwnedStorageItems(level, center, bedIds);
        for (Item bed : VillageBuilder.ALL_BED_ITEMS) {
            if (stored.getOrDefault(BuiltInRegistries.ITEM.getKey(bed).toString(), 0) > 0) return true;
            for (Villager resident : residents) if (countItem(resident.getInventory(), bed) > 0) return true;
        }
        return false;
    }

    private static Map<Item, Integer> countVillageWool(ServerLevel level, BlockPos center, List<Villager> residents) {
        Set<String> woolIds = WOOL_ITEMS.stream()
            .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
            .collect(java.util.stream.Collectors.toSet());
        Map<String, Integer> stored = VillageRegistry.countAvailableOwnedStorageItems(level, center, woolIds);
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (Item wool : WOOL_ITEMS) totals.put(wool, stored.getOrDefault(BuiltInRegistries.ITEM.getKey(wool).toString(), 0));
        for (Villager resident : residents) {
            for (Item wool : WOOL_ITEMS) totals.merge(wool, countItem(resident.getInventory(), wool), Integer::sum);
        }
        return totals;
    }

    private static boolean hasPendingHouseOrder(ServerLevel level, BlockPos center) {
        return VillageWorkOrders.list(level, center, level.getGameTime()).stream().anyMatch(order ->
            "house".equals(order.kind())
                && (order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.OPEN
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.WAITING_FOR_RESOURCES
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.READY));
    }

    private static boolean hasShearsInOwnedStorage(ServerLevel level, BlockPos center) {
        return findStorages(level, center, null).stream().anyMatch(ref -> countItem(ref.container(), Items.SHEARS) > 0);
    }

    private static ItemStack firstShears(Container inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.SHEARS) && !stack.isEmpty()) return stack;
        }
        return null;
    }

    private static ItemStack removeOne(Container storage, Item item) {
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            ItemStack stack = storage.getItem(slot);
            if (stack.is(item)) return storage.removeItem(slot, 1);
        }
        return ItemStack.EMPTY;
    }

    private static int countItem(Container inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int inventoryCapacityFor(Container inventory, Item item) {
        int capacity = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) capacity += item.getDefaultInstance().getMaxStackSize();
            else if (stack.is(item)) capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return capacity;
    }

    private static boolean canInsert(Container container, ItemStack offered) {
        int remaining = offered.getCount();
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            if (!container.canPlaceItem(slot, offered)) continue;
            ItemStack existing = container.getItem(slot);
            int room = existing.isEmpty()
                ? Math.min(offered.getMaxStackSize(), container.getMaxStackSize())
                : ItemStack.isSameItemSameComponents(existing, offered)
                    ? Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount() : 0;
            remaining -= Math.max(0, room);
        }
        return remaining <= 0;
    }

    private static int insert(Container container, ItemStack offered) {
        int before = offered.getCount();
        for (int pass = 0; pass < 2 && !offered.isEmpty(); pass++) {
            for (int slot = 0; slot < container.getContainerSize() && !offered.isEmpty(); slot++) {
                if (!container.canPlaceItem(slot, offered)) continue;
                ItemStack existing = container.getItem(slot);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, offered)) continue;
                    int room = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
                    int moved = Math.min(Math.max(0, room), offered.getCount());
                    if (moved > 0) { existing.grow(moved); offered.shrink(moved); }
                } else if (existing.isEmpty()) {
                    int moved = Math.min(offered.getCount(), Math.min(offered.getMaxStackSize(), container.getMaxStackSize()));
                    if (moved > 0) container.setItem(slot, offered.split(moved));
                }
            }
        }
        if (before != offered.getCount()) container.setChanged();
        return before - offered.getCount();
    }

    private static boolean hasWoolInventory(Villager villager) {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (!stack.isEmpty() && isWool(stack.getItem())) return true;
        }
        return false;
    }

    private static boolean hasHigherPriorityTask(CompoundTag data) {
        return data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_treework_replant_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route")
            || data.getBoolean("livingvillages_logistics_active")
            || data.getBoolean("livingvillages_industry_route_active")
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || data.getBoolean(VillageBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean("livingvillages_autonomy_target_active");
    }

    private static boolean isNight(ServerLevel level, Villager villager) {
        long day = Math.floorMod(level.getDayTime(), 24000L);
        return villager.isSleeping() || day < 1000L || day >= 12000L;
    }

    private static BlockPos ownershipCenter(Villager villager) {
        BlockPos home = VillagerRoles.getHome(villager);
        return home == null ? villager.blockPosition() : home;
    }

    private static void recordRouteFailure(Villager villager, CompoundTag data, long now) {
        int failures = data.getInt(FAILURES) + 1;
        if (failures >= MAX_ROUTE_FAILURES) {
            data.putInt(FAILURES, 0);
            data.putLong(NEXT_ACTION, now + FAILURE_BACKOFF_TICKS);
        } else {
            data.putInt(FAILURES, failures);
            data.putLong(NEXT_ACTION, now + RETRY_TICKS);
        }
    }

    private static void pauseRoute(Villager villager, CompoundTag data) {
        if (data.getBoolean(ROUTE_ACTIVE)) clearOwnedWalkTarget(villager, routeStand(data));
    }

    private static void clearRoute(Villager villager, CompoundTag data) {
        if (data.getBoolean(ROUTE_ACTIVE)) clearOwnedWalkTarget(villager, routeStand(data));
        data.putBoolean(ROUTE_ACTIVE, false);
        data.remove(ROUTE_KIND); data.remove(ROUTE_X); data.remove(ROUTE_Y); data.remove(ROUTE_Z);
        data.remove(STAND_X); data.remove(STAND_Y); data.remove(STAND_Z);
        data.remove(ROUTE_STARTED); data.remove(LAST_ROUTE_ORDER);
    }

    private static void clearOwnedWalkTarget(Villager villager, BlockPos stand) {
        villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
            if (walk.getTarget().currentBlockPosition().equals(stand)) {
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }
        });
    }

    private static void setRoute(Villager worker, CompoundTag data, BlockPos stand, BlockPos target, int kind,
                                 long now, Item item) {
        clearRoute(worker, data);
        data.putBoolean(ROUTE_ACTIVE, true);
        data.putInt(ROUTE_KIND, kind);
        data.putInt(ROUTE_X, target.getX()); data.putInt(ROUTE_Y, target.getY()); data.putInt(ROUTE_Z, target.getZ());
        data.putInt(STAND_X, stand.getX()); data.putInt(STAND_Y, stand.getY()); data.putInt(STAND_Z, stand.getZ());
        data.putLong(ROUTE_STARTED, now);
        data.putLong(LAST_ROUTE_ORDER, now);
        worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, WALK_SPEED, 1));
    }

    private static BlockPos routeTarget(CompoundTag data) {
        return new BlockPos(data.getInt(ROUTE_X), data.getInt(ROUTE_Y), data.getInt(ROUTE_Z));
    }

    private static BlockPos routeStand(CompoundTag data) {
        return new BlockPos(data.getInt(STAND_X), data.getInt(STAND_Y), data.getInt(STAND_Z));
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private record StorageRef(BlockPos pos, Container container) {}
    private record StorageTarget(BlockPos storage, BlockPos stand) {}
    private record PathTarget(BlockPos stand) {}
}
