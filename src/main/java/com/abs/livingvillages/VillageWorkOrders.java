package com.abs.livingvillages;

import com.abs.livingvillages.domain.VillageWorkOrderModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.WeakHashMap;

/**
 * Persistent per-dimension work-order board, partitioned by stable village identity.
 *
 * <p>The board is the common job ledger for construction and future settlement professions.
 * Physical items remain in real containers/inventories; this class stores requirements and
 * reservations as accounting metadata only. Existing physical storage claims still protect
 * the actual containers while a worker collects project materials.</p>
 */
public final class VillageWorkOrders extends SavedData {
    public static final String ORDER_ID_KEY = "livingvillages_work_order_id";

    private static final String DATA_NAME = "livingvillages_work_orders";
    private static final int MAX_ACTIVE_ORDERS_PER_VILLAGE = 128;
    private static final int MAX_RETAINED_ORDERS_PER_VILLAGE = 256;
    private static final long DEFAULT_ORDER_TTL_TICKS = 30L * 24000L;
    private static final long PROJECT_CLAIM_TICKS = 30L * 24000L;
    private static final Factory<VillageWorkOrders> FACTORY =
        new Factory<>(VillageWorkOrders::new, VillageWorkOrders::load, null);

    private final Map<UUID, List<VillageWorkOrderModel.Order>> ordersByVillage = new HashMap<>();
    private static final long MATERIAL_DEMAND_CACHE_TICKS = 20L;
    private static final Map<ServerLevel, Map<UUID, CachedMaterialDemand>> MATERIAL_DEMAND_CACHE = new WeakHashMap<>();

    private static void invalidateMaterialDemandCache(ServerLevel level, UUID villageId) {
        if (level == null || villageId == null) return;
        Map<UUID, CachedMaterialDemand> cache = MATERIAL_DEMAND_CACHE.get(level);
        if (cache != null) cache.remove(villageId);
    }

    private VillageWorkOrders() {}

    public static VillageWorkOrders get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * Adds an open order, or returns the existing non-terminal order with the same village/kind/key.
     * Repeated planner scans are therefore idempotent and cannot flood the board with duplicates.
     */
    public static VillageWorkOrderModel.Order enqueue(
        ServerLevel level,
        BlockPos center,
        String kind,
        String dedupeKey,
        int priority,
        long nowTick,
        long ttlTicks,
        List<VillageWorkOrderModel.Requirement> requirements
    ) {
        if (level == null || center == null || kind == null || kind.isBlank()
            || dedupeKey == null || dedupeKey.isBlank()) return null;
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId == null) return null;
        List<VillageWorkOrderModel.Requirement> normalized =
            VillageWorkOrderModel.normalizeRequirements(requirements);
        if (normalized == null) return null;

        VillageWorkOrders board = get(level);
        List<VillageWorkOrderModel.Order> orders =
            board.ordersByVillage.computeIfAbsent(villageId, ignored -> new ArrayList<>());
        if (board.reconcileOrders(orders, nowTick)) board.setDirty();
        for (int i = 0; i < orders.size(); i++) {
            VillageWorkOrderModel.Order order = orders.get(i);
            if (!order.isTerminal() && order.kind().equals(kind) && order.dedupeKey().equals(dedupeKey)) {
                if (order.requirements().isEmpty() && !normalized.isEmpty()) {
                    VillageWorkOrderModel.Order enriched =
                        VillageWorkOrderModel.addRequirementsToOpenOrder(order, normalized, priority, nowTick);
                    if (enriched != null) {
                        orders.set(i, enriched);
                        board.setDirty();
                        invalidateMaterialDemandCache(level, villageId);
                        return enriched;
                    }
                }
                return order;
            }
        }

        long active = orders.stream().filter(order -> !order.isTerminal()).count();
        if (active >= MAX_ACTIVE_ORDERS_PER_VILLAGE) return null;
        board.pruneRetainedOrders(orders, nowTick);
        if (orders.size() >= MAX_RETAINED_ORDERS_PER_VILLAGE) return null;

        long ttl = Math.max(200L, Math.min(DEFAULT_ORDER_TTL_TICKS, ttlTicks));
        VillageWorkOrderModel.Order created = new VillageWorkOrderModel.Order(
            UUID.randomUUID(),
            villageId,
            kind,
            dedupeKey,
            Math.max(0, Math.min(VillageWorkOrderModel.MAX_PRIORITY, priority)),
            nowTick,
            nowTick,
            nowTick + ttl,
            VillageWorkOrderModel.OPEN,
            null,
            0L,
            normalized
        );
        orders.add(created);
        board.setDirty();
        invalidateMaterialDemandCache(level, villageId);
        return created;
    }

    /** Creates the persistent placeholder entry when the elder queues a house/farm request. */
    public static boolean enqueuePendingProject(
        ServerLevel level,
        BlockPos center,
        String kind,
        int priority
    ) {
        if (!("house".equals(kind) || "farm".equals(kind))) return false;
        long now = level == null ? 0L : level.getGameTime();
        VillageWorkOrderModel.Order order = enqueue(
            level, center, kind, kind, priority, now, DEFAULT_ORDER_TTL_TICKS, List.of());
        return order != null && !order.isTerminal();
    }

    /**
     * Records the concrete material bill for an OPEN/WAITING house or farm request.
     * Repeated planner scans reuse the same key and cannot create duplicates.
     */
    public static VillageWorkOrderModel.Order recordPendingProjectRequirements(
        ServerLevel level,
        BlockPos center,
        String kind,
        int priority,
        long nowTick,
        List<VillageWorkOrderModel.Requirement> requirements
    ) {
        if (level == null || center == null || !("house".equals(kind) || "farm".equals(kind))
            || requirements == null || requirements.isEmpty()) return null;
        return enqueue(level, center, kind, kind, priority, nowTick, DEFAULT_ORDER_TTL_TICKS, requirements);
    }

    /** Marks a placeholder house/farm request as waiting after a planner attempt cannot start it. */
    public static boolean markPendingProjectWaiting(
        ServerLevel level,
        BlockPos center,
        String kind,
        long nowTick
    ) {
        if (level == null || center == null || !("house".equals(kind) || "farm".equals(kind))) return false;
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId == null) return false;
        VillageWorkOrders board = get(level);
        List<VillageWorkOrderModel.Order> orders = board.ordersByVillage.get(villageId);
        if (orders == null) return false;
        for (int i = 0; i < orders.size(); i++) {
            VillageWorkOrderModel.Order current = orders.get(i);
            if (!current.kind().equals(kind) || !current.dedupeKey().equals(kind)
                || current.isTerminal()) continue;
            if (current.state() == VillageWorkOrderModel.WAITING_FOR_RESOURCES) return true;
            if (current.state() != VillageWorkOrderModel.OPEN) return false;
            VillageWorkOrderModel.Order waiting = VillageWorkOrderModel.transition(
                current, VillageWorkOrderModel.WAITING_FOR_RESOURCES, nowTick);
            if (waiting == null) return false;
            orders.set(i, waiting);
            board.setDirty();
            invalidateMaterialDemandCache(level, villageId);
            return true;
        }
        return false;
    }

    /** Returns an order to OPEN when materials are available but another start condition is missing. */
    public static boolean markPendingProjectOpen(
        ServerLevel level,
        BlockPos center,
        String kind,
        long nowTick
    ) {
        if (level == null || center == null || !("house".equals(kind) || "farm".equals(kind))) return false;
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId == null) return false;
        VillageWorkOrders board = get(level);
        List<VillageWorkOrderModel.Order> orders = board.ordersByVillage.get(villageId);
        if (orders == null) return false;
        for (int i = 0; i < orders.size(); i++) {
            VillageWorkOrderModel.Order current = orders.get(i);
            if (!current.kind().equals(kind) || !current.dedupeKey().equals(kind) || current.isTerminal()) continue;
            if (current.state() == VillageWorkOrderModel.OPEN) return true;
            if (current.state() != VillageWorkOrderModel.WAITING_FOR_RESOURCES) return false;
            VillageWorkOrderModel.Order open = VillageWorkOrderModel.transition(
                current, VillageWorkOrderModel.OPEN, nowTick);
            if (open == null) return false;
            orders.set(i, open);
            board.setDirty();
            invalidateMaterialDemandCache(level, villageId);
            return true;
        }
        return false;
    }

    public static List<VillageWorkOrderModel.Order> list(
        ServerLevel level,
        BlockPos center,
        long nowTick
    ) {
        UUID villageId = level == null || center == null ? null : VillageRegistry.villageId(level, center);
        if (villageId == null) return List.of();
        VillageWorkOrders board = get(level);
        List<VillageWorkOrderModel.Order> orders = board.ordersByVillage.get(villageId);
        if (orders == null) return List.of();
        if (board.reconcileOrders(orders, nowTick)) board.setDirty();
        return orders.stream()
            .sorted(Comparator.comparingInt(VillageWorkOrderModel.Order::priority).reversed()
                .thenComparingLong(VillageWorkOrderModel.Order::createdTick)
                .thenComparing(order -> order.id().toString()))
            .toList();
    }

    public static VillageWorkOrderModel.Order find(
        ServerLevel level,
        BlockPos center,
        UUID orderId,
        long nowTick
    ) {
        if (level == null || center == null || orderId == null) return null;
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId == null) return null;
        VillageWorkOrders board = get(level);
        List<VillageWorkOrderModel.Order> orders = board.ordersByVillage.get(villageId);
        if (orders == null) return null;
        if (board.reconcileOrders(orders, nowTick)) board.setDirty();
        return board.findIn(orders, orderId);
    }

    public static VillageWorkOrderModel.Order setState(
        ServerLevel level,
        BlockPos center,
        UUID orderId,
        int nextState,
        long nowTick
    ) {
        if (level == null || center == null || orderId == null) return null;
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId == null) return null;
        VillageWorkOrders board = get(level);
        List<VillageWorkOrderModel.Order> orders = board.ordersByVillage.get(villageId);
        if (orders == null) return null;
        for (int i = 0; i < orders.size(); i++) {
            VillageWorkOrderModel.Order current = orders.get(i);
            if (!current.id().equals(orderId)) continue;
            VillageWorkOrderModel.Order updated =
                VillageWorkOrderModel.transition(current, nextState, nowTick);
            if (updated == null) return null;
            orders.set(i, updated);
            board.setDirty();
            invalidateMaterialDemandCache(level, villageId);
            return updated;
        }
        return null;
    }

    public static VillageWorkOrderModel.Order claim(
        ServerLevel level,
        BlockPos center,
        UUID orderId,
        UUID workerId,
        long nowTick,
        long leaseTicks
    ) {
        VillageWorkOrderModel.Order current = find(level, center, orderId, nowTick);
        if (current == null) return null;
        VillageWorkOrderModel.Order updated =
            VillageWorkOrderModel.claim(current, workerId, nowTick, leaseTicks);
        return updated == null ? null : replace(level, current.villageId(), updated);
    }

    public static VillageWorkOrderModel.Order beginWork(
        ServerLevel level,
        BlockPos center,
        UUID orderId,
        UUID workerId,
        long nowTick,
        long leaseTicks
    ) {
        VillageWorkOrderModel.Order current = find(level, center, orderId, nowTick);
        if (current == null) return null;
        VillageWorkOrderModel.Order updated =
            VillageWorkOrderModel.beginWork(current, workerId, nowTick, leaseTicks);
        return updated == null ? null : replace(level, current.villageId(), updated);
    }

    public static VillageWorkOrderModel.Order releaseExpiredClaim(
        ServerLevel level,
        BlockPos center,
        UUID orderId,
        long nowTick
    ) {
        VillageWorkOrderModel.Order current = find(level, center, orderId, nowTick);
        if (current == null) return null;
        VillageWorkOrderModel.Order updated =
            VillageWorkOrderModel.releaseExpiredClaim(current, nowTick);
        return updated == current ? current : replace(level, current.villageId(), updated);
    }

    /**
     * Computes real pickup inputs for pending work orders. Plank requirements are translated
     * to matching logs because the existing industry consumes one log to make four planks.
     * Storage, all resident inventories, and loose drops inside this village are counted once.
     * Returns null when there is no materialized pending demand (legacy stockpiling fallback).
     */
    public static Map<String, Integer> pendingMaterialPickupDeficits(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return null;
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId == null) return null;
        long now = level.getGameTime();

        Map<UUID, CachedMaterialDemand> byVillage =
            MATERIAL_DEMAND_CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        CachedMaterialDemand cached = byVillage.get(villageId);
        if (cached == null || now >= cached.expiresAtTick()) {
            List<VillageWorkOrderModel.Order> pending = list(level, center, now);
            Map<String, Integer> directDemand = new HashMap<>();
            Map<String, Integer> plankDemand = new HashMap<>();
            Set<String> wantedIds = new HashSet<>();

            for (VillageWorkOrderModel.Order order : pending) {
                if (order.state() != VillageWorkOrderModel.OPEN
                    && order.state() != VillageWorkOrderModel.WAITING_FOR_RESOURCES) continue;
                for (VillageWorkOrderModel.Requirement requirement : order.requirements()) {
                    if (requirement == null || !requirement.isValid()) continue;
                    ResourceLocation id = ResourceLocation.tryParse(requirement.itemId());
                    if (id == null) continue;
                    Item item = BuiltInRegistries.ITEM.get(id);
                    Item logItem = VillageIndustry.logItemForPlanks(item);
                    if (logItem != Items.AIR) {
                        plankDemand.merge(requirement.itemId(), requirement.count(), Integer::sum);
                        wantedIds.add(requirement.itemId());
                        wantedIds.add(BuiltInRegistries.ITEM.getKey(logItem).toString());
                    } else {
                        directDemand.merge(requirement.itemId(), requirement.count(), Integer::sum);
                        wantedIds.add(requirement.itemId());
                    }
                }
            }

            Map<String, Integer> accessibleStock = wantedIds.isEmpty()
                ? Map.of()
                : VillageRegistry.countAvailableOwnedStorageItems(level, center, wantedIds);
            cached = new CachedMaterialDemand(
                Map.copyOf(directDemand), Map.copyOf(plankDemand),
                Set.copyOf(wantedIds), Map.copyOf(accessibleStock), now + MATERIAL_DEMAND_CACHE_TICKS
            );
            byVillage.put(villageId, cached);
            if (byVillage.size() > 64) {
                byVillage.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtTick());
            }
        }

        if (cached.wantedIds().isEmpty()) return null;

        // Recount carried items and loose drops every query. This changes immediately as one
        // courier picks up a drop or another courier takes over a delivery route.
        Map<String, Integer> carriedOrDropped = new HashMap<>();
        for (Villager resident : VillageArea.findVillagers(level, center)) {
            CompoundTag data = resident.getPersistentData();
            if (data.getBoolean(VillageBuilder.ACTIVE_KEY)
                || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
                || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)) continue;
            for (int slot = 0; slot < resident.getInventory().getContainerSize(); slot++) {
                ItemStack stack = resident.getInventory().getItem(slot);
                if (stack.isEmpty()) continue;
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (cached.wantedIds().contains(itemId)) carriedOrDropped.merge(itemId, stack.getCount(), Integer::sum);
            }
            ItemStack hand = resident.getMainHandItem();
            if (!hand.isEmpty()) {
                String itemId = BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
                if (cached.wantedIds().contains(itemId)) carriedOrDropped.merge(itemId, hand.getCount(), Integer::sum);
            }
        }

        int radius = Math.max(8, Math.min(32, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get()));
        AABB itemArea = new AABB(center).inflate(radius, 16.0D, radius);
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, itemArea, ItemEntity::isAlive)) {
            if (!VillageArea.isBlockInResidentGroup(level, center, entity.blockPosition())) continue;
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (cached.wantedIds().contains(itemId)) carriedOrDropped.merge(itemId, stack.getCount(), Integer::sum);
        }

        Map<String, Integer> deficits = new HashMap<>();
        for (Map.Entry<String, Integer> entry : cached.directDemand().entrySet()) {
            long available = (long) cached.accessibleStock().getOrDefault(entry.getKey(), 0)
                + carriedOrDropped.getOrDefault(entry.getKey(), 0);
            long missing = Math.max(0L, (long) entry.getValue() - available);
            if (missing > 0L) deficits.put(entry.getKey(), (int) Math.min(Integer.MAX_VALUE, missing));
        }

        for (Map.Entry<String, Integer> entry : cached.plankDemand().entrySet()) {
            ResourceLocation plankId = ResourceLocation.tryParse(entry.getKey());
            if (plankId == null) continue;
            Item plankItem = BuiltInRegistries.ITEM.get(plankId);
            Item logItem = VillageIndustry.logItemForPlanks(plankItem);
            if (logItem == Items.AIR) continue;
            String logId = BuiltInRegistries.ITEM.getKey(logItem).toString();
            int missingLogs = VillageWorkOrderModel.missingInputCount(
                entry.getValue(),
                cached.accessibleStock().getOrDefault(entry.getKey(), 0)
                    + carriedOrDropped.getOrDefault(entry.getKey(), 0),
                cached.accessibleStock().getOrDefault(logId, 0)
                    + carriedOrDropped.getOrDefault(logId, 0),
                4
            );
            if (missingLogs > 0) deficits.merge(logId, missingLogs, Integer::sum);
        }

        return Map.copyOf(deficits);
    }

    /** Explicitly refresh demand after a real storage transfer or an external order mutation. */
    public static void invalidateMaterialDemandCache(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return;
        invalidateMaterialDemandCache(level, VillageRegistry.villageId(level, center));
    }

    /** Returns the matching log type required by the highest-priority pending plank order. */
    public static Item pendingWoodLogItem(ServerLevel level, BlockPos center) {
        Map<String, Integer> deficits = pendingMaterialPickupDeficits(level, center);
        if (deficits == null) return Items.AIR;
        for (VillageWorkOrderModel.Order order : list(level, center, level.getGameTime())) {
            if (order.state() != VillageWorkOrderModel.OPEN
                && order.state() != VillageWorkOrderModel.WAITING_FOR_RESOURCES) continue;
            for (VillageWorkOrderModel.Requirement requirement : order.requirements()) {
                ResourceLocation id = ResourceLocation.tryParse(requirement.itemId());
                if (id == null) continue;
                Item plank = BuiltInRegistries.ITEM.get(id);
                Item log = VillageIndustry.logItemForPlanks(plank);
                if (log != Items.AIR
                    && deficits.getOrDefault(BuiltInRegistries.ITEM.getKey(log).toString(), 0) > 0) return log;
            }
        }
        return Items.AIR;
    }

    /** A miner may quarry only the specific natural material currently missing from a pending bill. */
    public static Item pendingQuarryOutputItem(ServerLevel level, BlockPos center) {
        Map<String, Integer> deficits = pendingMaterialPickupDeficits(level, center);
        if (deficits == null) return Items.AIR;
        for (VillageWorkOrderModel.Order order : list(level, center, level.getGameTime())) {
            if (order.state() != VillageWorkOrderModel.OPEN
                && order.state() != VillageWorkOrderModel.WAITING_FOR_RESOURCES) continue;
            for (VillageWorkOrderModel.Requirement requirement : order.requirements()) {
                ResourceLocation id = ResourceLocation.tryParse(requirement.itemId());
                if (id == null) continue;
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item != Items.COBBLESTONE && item != Items.SANDSTONE
                    && item != Items.RED_SANDSTONE && item != Items.TERRACOTTA) continue;
                if (deficits.getOrDefault(requirement.itemId(), 0) > 0) return item;
            }
        }
        return Items.AIR;
    }

    private record CachedMaterialDemand(
        Map<String, Integer> directDemand,
        Map<String, Integer> plankDemand,
        Set<String> wantedIds,
        Map<String, Integer> accessibleStock,
        long expiresAtTick
    ) {}

    /** Count quantities reserved by all active orders except the provided order. */
    public static Map<String, Integer> reservedItemCounts(
        ServerLevel level,
        BlockPos center,
        UUID excludingOrderId,
        long nowTick
    ) {
        List<VillageWorkOrderModel.Order> orders = list(level, center, nowTick);
        return VillageWorkOrderModel.reservedCounts(orders, excludingOrderId);
    }

    /**
     * Starts or resumes a project after its real material bill and physical storage claim succeeded.
     * The existing VillageReservations system continues to lock the actual chests; this board
     * records the matching quantities, owner, project site and lifecycle in one persistent order.
     */
    public static boolean startProject(
        ServerLevel level,
        BlockPos center,
        String kind,
        BlockPos site,
        Villager worker,
        Map<Item, Integer> materialBill
    ) {
        if (level == null || center == null || kind == null || site == null
            || worker == null || !worker.isAlive() || materialBill == null || materialBill.isEmpty()) return false;
        List<VillageWorkOrderModel.Requirement> requirements = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : materialBill.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) return false;
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(entry.getKey());
            if (key == null) return false;
            requirements.add(new VillageWorkOrderModel.Requirement(key.toString(), entry.getValue()));
        }
        String dedupeKey = kind;
        long now = level.getGameTime();
        int priority = kind.equals("farm") ? 60 : 55;
        VillageWorkOrderModel.Order order = enqueue(
            level, center, kind, dedupeKey, priority, now, DEFAULT_ORDER_TTL_TICKS, requirements);
        if (order == null) return false;

        UUID workerId = worker.getUUID();
        if (order.state() == VillageWorkOrderModel.IN_PROGRESS
            && workerId.equals(order.workerId())) {
            worker.getPersistentData().putString(ORDER_ID_KEY, order.id().toString());
            return true;
        }

        if (order.state() == VillageWorkOrderModel.OPEN
            || order.state() == VillageWorkOrderModel.WAITING_FOR_RESOURCES) {
            order = setState(level, center, order.id(), VillageWorkOrderModel.READY, now);
        }
        if (order == null) return false;

        if (order.state() == VillageWorkOrderModel.READY) {
            order = claim(level, center, order.id(), workerId, now, PROJECT_CLAIM_TICKS);
            if (order == null) return false;
            order = beginWork(level, center, order.id(), workerId, now, PROJECT_CLAIM_TICKS);
            if (order == null) return false;
        } else if (order.state() == VillageWorkOrderModel.CLAIMED
            && workerId.equals(order.workerId())) {
            order = beginWork(level, center, order.id(), workerId, now, PROJECT_CLAIM_TICKS);
            if (order == null) return false;
        } else {
            return false;
        }
        worker.getPersistentData().putString(ORDER_ID_KEY, order.id().toString());
        return true;
    }

    /** Transfer a checkpointed project to its replacement worker after the caller verified orphaning. */
    public static boolean rebindOrphanedProject(
        ServerLevel level,
        BlockPos center,
        String kind,
        BlockPos site,
        Villager replacement,
        boolean previousWorkerConfirmedAbsent
    ) {
        if (level == null || center == null || kind == null || site == null
            || replacement == null || !replacement.isAlive() || !previousWorkerConfirmedAbsent) return false;
        long now = level.getGameTime();
        String dedupeKey = kind;
        VillageWorkOrderModel.Order order = enqueue(
            level, center, kind, dedupeKey, kind.equals("farm") ? 60 : 55,
            now, DEFAULT_ORDER_TTL_TICKS, List.of());
        if (order == null) return false;
        UUID replacementId = replacement.getUUID();
        VillageWorkOrders board = get(level);
        List<VillageWorkOrderModel.Order> orders = board.ordersByVillage.get(order.villageId());
        if (orders == null) return false;
        for (int i = 0; i < orders.size(); i++) {
            VillageWorkOrderModel.Order current = orders.get(i);
            if (!current.id().equals(order.id())) continue;
            VillageWorkOrderModel.Order updated;
            if (current.state() == VillageWorkOrderModel.OPEN
                || current.state() == VillageWorkOrderModel.WAITING_FOR_RESOURCES
                || current.state() == VillageWorkOrderModel.READY) {
                if (current.state() != VillageWorkOrderModel.READY) {
                    current = VillageWorkOrderModel.transition(current, VillageWorkOrderModel.READY, now);
                }
                VillageWorkOrderModel.Order claimed =
                    VillageWorkOrderModel.claim(current, replacementId, now, PROJECT_CLAIM_TICKS);
                updated = claimed == null ? null
                    : VillageWorkOrderModel.beginWork(claimed, replacementId, now, PROJECT_CLAIM_TICKS);
            } else {
                updated = VillageWorkOrderModel.reassignOrphanedOrder(
                    current, replacementId, now, PROJECT_CLAIM_TICKS, true);
            }
            if (updated == null) return false;
            orders.set(i, updated);
            board.setDirty();
            replacement.getPersistentData().putString(ORDER_ID_KEY, updated.id().toString());
            return true;
        }
        return false;
    }

    public static boolean completeProject(ServerLevel level, Villager worker) {
        return finishProject(level, worker, VillageWorkOrderModel.COMPLETED);
    }

    public static boolean cancelProject(ServerLevel level, Villager worker) {
        return finishProject(level, worker, VillageWorkOrderModel.CANCELLED);
    }

    private static boolean finishProject(ServerLevel level, Villager worker, int terminalState) {
        if (level == null || worker == null) return false;
        var data = worker.getPersistentData();
        String value = data.getString(ORDER_ID_KEY);
        UUID orderId;
        try {
            orderId = UUID.fromString(value);
        } catch (RuntimeException ignored) {
            return false;
        }
        VillageWorkOrders board = get(level);
        for (List<VillageWorkOrderModel.Order> orders : board.ordersByVillage.values()) {
            for (int i = 0; i < orders.size(); i++) {
                VillageWorkOrderModel.Order current = orders.get(i);
                if (!current.id().equals(orderId) || !worker.getUUID().equals(current.workerId())) continue;
                VillageWorkOrderModel.Order updated =
                    VillageWorkOrderModel.transition(current, terminalState, level.getGameTime());
                if (updated == null) return false;
                orders.set(i, updated);
                board.setDirty();
                data.remove(ORDER_ID_KEY);
                return true;
            }
        }
        return false;
    }

    private static VillageWorkOrderModel.Order replace(
        ServerLevel level,
        UUID villageId,
        VillageWorkOrderModel.Order updated
    ) {
        if (level == null || villageId == null || updated == null) return null;
        VillageWorkOrders board = get(level);
        List<VillageWorkOrderModel.Order> orders = board.ordersByVillage.get(villageId);
        if (orders == null) return null;
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).id().equals(updated.id())) {
                orders.set(i, updated);
                board.setDirty();
                invalidateMaterialDemandCache(level, villageId);
                return updated;
            }
        }
        return null;
    }

    private boolean reconcileOrders(List<VillageWorkOrderModel.Order> orders, long nowTick) {
        boolean changed = false;
        for (int i = 0; i < orders.size(); i++) {
            VillageWorkOrderModel.Order order = orders.get(i);
            if (VillageWorkOrderModel.isOrderExpired(order, nowTick)) {
                VillageWorkOrderModel.Order expired =
                    VillageWorkOrderModel.transition(order, VillageWorkOrderModel.EXPIRED, nowTick);
                if (expired != null) {
                    orders.set(i, expired);
                    changed = true;
                }
            } else if (VillageWorkOrderModel.isClaimExpired(order, nowTick)) {
                orders.set(i, VillageWorkOrderModel.releaseExpiredClaim(order, nowTick));
                changed = true;
            }
        }
        return changed;
    }

    private void pruneRetainedOrders(List<VillageWorkOrderModel.Order> orders, long nowTick) {
        if (orders.size() < MAX_RETAINED_ORDERS_PER_VILLAGE) return;
        orders.removeIf(order -> order.isTerminal()
            && nowTick - order.updatedTick() > DEFAULT_ORDER_TTL_TICKS);
    }

    private VillageWorkOrderModel.Order findIn(
        List<VillageWorkOrderModel.Order> orders,
        UUID orderId
    ) {
        for (VillageWorkOrderModel.Order order : orders) {
            if (order.id().equals(orderId)) return order;
        }
        return null;
    }

    static VillageWorkOrders load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageWorkOrders board = new VillageWorkOrders();
        ListTag orderTags = tag.getList("orders", Tag.TAG_COMPOUND);
        for (int i = 0; i < orderTags.size(); i++) {
            CompoundTag entry = orderTags.getCompound(i);
            UUID villageId = parseUuid(entry.getString("village"));
            UUID orderId = parseUuid(entry.getString("id"));
            if (villageId == null || orderId == null) continue;
            int state = entry.getInt("state");
            if (!VillageWorkOrderModel.isKnownState(state)) continue;
            List<VillageWorkOrderModel.Requirement> requirements = new ArrayList<>();
            ListTag requirementTags = entry.getList("requirements", Tag.TAG_COMPOUND);
            for (int j = 0; j < Math.min(requirementTags.size(),
                VillageWorkOrderModel.MAX_REQUIREMENTS_PER_ORDER); j++) {
                CompoundTag requirement = requirementTags.getCompound(j);
                var value = new VillageWorkOrderModel.Requirement(
                    requirement.getString("item"), requirement.getInt("count"));
                ResourceLocation key = ResourceLocation.tryParse(value.itemId());
                if (key != null && value.isValid()) requirements.add(value);
            }
            List<VillageWorkOrderModel.Requirement> normalized =
                VillageWorkOrderModel.normalizeRequirements(requirements);
            if (normalized == null) continue;
            UUID workerId = parseUuid(entry.getString("worker"));
            String kind = entry.getString("kind");
            String dedupe = entry.getString("dedupe");
            if (kind.isBlank() || dedupe.isBlank()) continue;
            VillageWorkOrderModel.Order order = new VillageWorkOrderModel.Order(
                orderId, villageId, kind, dedupe,
                Math.max(0, Math.min(VillageWorkOrderModel.MAX_PRIORITY, entry.getInt("priority"))),
                entry.getLong("created"), entry.getLong("updated"), entry.getLong("expires"),
                state, workerId, entry.getLong("claimUntil"), normalized);
            List<VillageWorkOrderModel.Order> villageOrders =
                board.ordersByVillage.computeIfAbsent(villageId, ignored -> new ArrayList<>());
            if (villageOrders.size() < MAX_RETAINED_ORDERS_PER_VILLAGE) villageOrders.add(order);
        }
        return board;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag orderTags = new ListTag();
        for (Map.Entry<UUID, List<VillageWorkOrderModel.Order>> village : ordersByVillage.entrySet()) {
            for (VillageWorkOrderModel.Order order : village.getValue()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("village", village.getKey().toString());
                entry.putString("id", order.id().toString());
                entry.putString("kind", order.kind());
                entry.putString("dedupe", order.dedupeKey());
                entry.putInt("priority", order.priority());
                entry.putLong("created", order.createdTick());
                entry.putLong("updated", order.updatedTick());
                entry.putLong("expires", order.expiresAtTick());
                entry.putInt("state", order.state());
                if (order.workerId() != null) entry.putString("worker", order.workerId().toString());
                entry.putLong("claimUntil", order.claimUntilTick());
                ListTag requirementTags = new ListTag();
                for (VillageWorkOrderModel.Requirement requirement : order.requirements()) {
                    CompoundTag value = new CompoundTag();
                    value.putString("item", requirement.itemId());
                    value.putInt("count", requirement.count());
                    requirementTags.add(value);
                }
                entry.put("requirements", requirementTags);
                orderTags.add(entry);
            }
        }
        tag.put("orders", orderTags);
        return tag;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
