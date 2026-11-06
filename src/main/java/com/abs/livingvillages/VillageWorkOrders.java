package com.abs.livingvillages;

import com.abs.livingvillages.domain.VillageWorkOrderModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        board.reconcileOrders(orders, nowTick);
        for (VillageWorkOrderModel.Order order : orders) {
            if (!order.isTerminal() && order.kind().equals(kind) && order.dedupeKey().equals(dedupeKey)) {
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
        return created;
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
        String dedupeKey = kind + ":" + site.asLong();
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
        String dedupeKey = kind + ":" + site.asLong();
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
        BlockPos center = VillagerRoles.getHome(worker);
        if (center == null) center = worker.blockPosition();
        VillageWorkOrderModel.Order current = find(level, center, orderId, level.getGameTime());
        if (current == null || !worker.getUUID().equals(current.workerId())) return false;
        VillageWorkOrderModel.Order updated = setState(
            level, center, orderId, terminalState, level.getGameTime());
        if (updated == null) return false;
        data.remove(ORDER_ID_KEY);
        return true;
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
