package com.abs.livingvillages.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform-independent rules for persistent settlement work orders and quantity reservations.
 *
 * <p>Higher priority values are more urgent. Orders are immutable snapshots so the
 * SavedData adapter can update them atomically and tests can exercise the lifecycle.</p>
 */
public final class VillageWorkOrderModel {
    public static final int OPEN = 0;
    public static final int WAITING_FOR_RESOURCES = 1;
    public static final int READY = 2;
    public static final int CLAIMED = 3;
    public static final int IN_PROGRESS = 4;
    public static final int DELIVERING = 5;
    public static final int COMPLETED = 6;
    public static final int CANCELLED = 7;
    public static final int FAILED = 8;
    public static final int EXPIRED = 9;

    public static final int MAX_PRIORITY = 100;
    public static final int MAX_REQUIREMENTS_PER_ORDER = 32;
    public static final int MAX_REQUIREMENT_COUNT = 1_000_000;

    public record Requirement(String itemId, int count) {
        public Requirement {
            itemId = itemId == null ? "" : itemId.trim();
        }

        public boolean isValid() {
            return !itemId.isBlank() && itemId.length() <= 128
                && count > 0 && count <= MAX_REQUIREMENT_COUNT;
        }
    }

    public record Order(
        UUID id,
        UUID villageId,
        String kind,
        String dedupeKey,
        int priority,
        long createdTick,
        long updatedTick,
        long expiresAtTick,
        int state,
        UUID workerId,
        long claimUntilTick,
        List<Requirement> requirements
    ) {
        public Order {
            kind = kind == null ? "" : kind.trim();
            dedupeKey = dedupeKey == null ? "" : dedupeKey.trim();
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
        }

        public boolean isTerminal() {
            return isTerminalState(state);
        }
    }

    private VillageWorkOrderModel() {}

    public static boolean isKnownState(int state) {
        return state >= OPEN && state <= EXPIRED;
    }

    public static boolean isTerminalState(int state) {
        return state == COMPLETED || state == CANCELLED || state == FAILED || state == EXPIRED;
    }

    public static boolean isResourceReservedState(int state) {
        return state == READY || state == CLAIMED || state == IN_PROGRESS || state == DELIVERING;
    }

    public static boolean canTransition(int from, int to) {
        if (!isKnownState(from) || !isKnownState(to) || isTerminalState(from) || from == to) return false;
        return switch (from) {
            case OPEN -> to == WAITING_FOR_RESOURCES || to == READY || to == CANCELLED || to == EXPIRED;
            case WAITING_FOR_RESOURCES -> to == OPEN || to == READY || to == CANCELLED || to == EXPIRED;
            case READY -> to == CLAIMED || to == CANCELLED || to == EXPIRED;
            case CLAIMED -> to == IN_PROGRESS || to == READY || to == CANCELLED || to == FAILED || to == EXPIRED;
            case IN_PROGRESS -> to == DELIVERING || to == COMPLETED || to == READY
                || to == CANCELLED || to == FAILED || to == EXPIRED;
            case DELIVERING -> to == COMPLETED || to == READY || to == CANCELLED || to == FAILED || to == EXPIRED;
            default -> false;
        };
    }

    public static boolean isOrderExpired(Order order, long nowTick) {
        return order != null && !order.isTerminal()
            && order.expiresAtTick() > 0L && nowTick >= order.expiresAtTick();
    }

    public static boolean isClaimExpired(Order order, long nowTick) {
        return order != null && order.workerId() != null
            && order.claimUntilTick() > 0L && nowTick >= order.claimUntilTick()
            && (order.state() == CLAIMED || order.state() == IN_PROGRESS || order.state() == DELIVERING);
    }

    public static Order transition(Order order, int nextState, long nowTick) {
        if (order == null || !canTransition(order.state(), nextState)) return null;
        UUID worker = nextState == CLAIMED || nextState == IN_PROGRESS || nextState == DELIVERING
            ? order.workerId() : null;
        long claimUntil = worker == null ? 0L : order.claimUntilTick();
        return copy(order, nextState, worker, claimUntil, nowTick);
    }

    /**
     * Enriches an unclaimed placeholder order once a concrete project has a validated material bill.
     * Existing material requirements are never silently overwritten.
     */
    public static Order addRequirementsToOpenOrder(
        Order order,
        List<Requirement> requirements,
        int priority,
        long nowTick
    ) {
        if (order == null || (order.state() != OPEN && order.state() != WAITING_FOR_RESOURCES)
            || !order.requirements().isEmpty()) return null;
        List<Requirement> normalized = normalizeRequirements(requirements);
        if (normalized == null || normalized.isEmpty()) return null;
        int updatedPriority = Math.max(order.priority(), Math.max(0, Math.min(MAX_PRIORITY, priority)));
        return new Order(order.id(), order.villageId(), order.kind(), order.dedupeKey(),
            updatedPriority, order.createdTick(), nowTick, order.expiresAtTick(),
            order.state(), null, 0L, normalized);
    }

    public static Order claim(Order order, UUID workerId, long nowTick, long leaseTicks) {
        if (order == null || workerId == null || workerId.equals(new UUID(0L, 0L))
            || order.state() != READY || isOrderExpired(order, nowTick)) return null;
        return copy(order, CLAIMED, workerId, nowTick + Math.max(1L, leaseTicks), nowTick);
    }

    public static Order beginWork(Order order, UUID workerId, long nowTick, long leaseTicks) {
        if (order == null || workerId == null || order.state() != CLAIMED
            || !workerId.equals(order.workerId()) || isClaimExpired(order, nowTick)) return null;
        return copy(order, IN_PROGRESS, workerId, nowTick + Math.max(1L, leaseTicks), nowTick);
    }

    public static Order renewClaim(Order order, UUID workerId, long nowTick, long leaseTicks) {
        if (order == null || workerId == null || !workerId.equals(order.workerId())
            || (order.state() != CLAIMED && order.state() != IN_PROGRESS && order.state() != DELIVERING)
            || isClaimExpired(order, nowTick)) return null;
        return copy(order, order.state(), workerId, nowTick + Math.max(1L, leaseTicks), nowTick);
    }

    public static Order releaseExpiredClaim(Order order, long nowTick) {
        if (!isClaimExpired(order, nowTick)) return order;
        return copy(order, READY, null, 0L, nowTick);
    }

    public static Order reassignOrphanedOrder(Order order, UUID replacementWorker, long nowTick,
                                               long leaseTicks, boolean previousWorkerConfirmedAbsent) {
        if (order == null || replacementWorker == null || !previousWorkerConfirmedAbsent
            || order.isTerminal()
            || (order.state() != CLAIMED && order.state() != IN_PROGRESS && order.state() != DELIVERING)) return null;
        return copy(order, IN_PROGRESS, replacementWorker, nowTick + Math.max(1L, leaseTicks), nowTick);
    }

    public static int effectivePriority(int basePriority, long createdTick, long nowTick,
                                        long agingIntervalTicks) {
        int base = Math.max(0, Math.min(MAX_PRIORITY, basePriority));
        if (agingIntervalTicks <= 0L || nowTick <= createdTick) return base;
        long aging = (nowTick - createdTick) / agingIntervalTicks;
        return (int) Math.min(MAX_PRIORITY, base + aging);
    }

    /**
     * Validates a new material reservation against physical stock minus existing active reservations.
     * Maps are keyed by canonical registry item IDs (for example minecraft:oak_planks).
     */
    public static boolean canReserve(
        List<Requirement> requested,
        Map<String, Integer> physicalStock,
        Map<String, Integer> alreadyReserved
    ) {
        if (requested == null || requested.isEmpty() || requested.size() > MAX_REQUIREMENTS_PER_ORDER
            || physicalStock == null || alreadyReserved == null) return false;
        Map<String, Integer> combined = new HashMap<>();
        for (Requirement requirement : requested) {
            if (requirement == null || !requirement.isValid()) return false;
            combined.merge(requirement.itemId(), requirement.count(), Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : combined.entrySet()) {
            int stock = Math.max(0, physicalStock.getOrDefault(entry.getKey(), 0));
            int reserved = Math.max(0, alreadyReserved.getOrDefault(entry.getKey(), 0));
            if (stock - reserved < entry.getValue()) return false;
        }
        return true;
    }

    public static Map<String, Integer> reservedCounts(List<Order> orders, UUID excludingOrderId) {
        Map<String, Integer> totals = new HashMap<>();
        if (orders == null) return totals;
        for (Order order : orders) {
            if (order == null || (excludingOrderId != null && excludingOrderId.equals(order.id()))
                || !isResourceReservedState(order.state())) continue;
            for (Requirement requirement : order.requirements()) {
                if (requirement != null && requirement.isValid()) {
                    totals.merge(requirement.itemId(), requirement.count(), Integer::sum);
                }
            }
        }
        return totals;
    }

    public static List<Requirement> normalizeRequirements(List<Requirement> requirements) {
        if (requirements == null || requirements.isEmpty()) return List.of();
        if (requirements.size() > MAX_REQUIREMENTS_PER_ORDER) return null;
        Map<String, Integer> totals = new HashMap<>();
        for (Requirement requirement : requirements) {
            if (requirement == null || !requirement.isValid()) return null;
            totals.merge(requirement.itemId(), requirement.count(), Integer::sum);
        }
        if (totals.size() > MAX_REQUIREMENTS_PER_ORDER
            || totals.values().stream().anyMatch(count -> count > MAX_REQUIREMENT_COUNT)) return null;
        List<Requirement> normalized = new ArrayList<>(totals.size());
        totals.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> normalized.add(new Requirement(entry.getKey(), entry.getValue())));
        return List.copyOf(normalized);
    }

    private static Order copy(Order order, int state, UUID workerId, long claimUntil, long nowTick) {
        return new Order(order.id(), order.villageId(), order.kind(), order.dedupeKey(),
            order.priority(), order.createdTick(), nowTick, order.expiresAtTick(),
            state, workerId, claimUntil, order.requirements());
    }
}
