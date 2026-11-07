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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
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
 * Material-backed village sheep husbandry.
 *
 * The builder constructs and checkpoints the village's own pen. A herder takes real wheat
 * from village storage, lures reachable sheep through the gate, and only then marks them as
 * village livestock. It later uses real shears and delivers actual wool drops to owned storage.
 * A nearby player-built pen is never treated as a village-owned pen.
 */
public final class VillageLivestock {
    public static final String ACTIVE_KEY = "livingvillages_livestock_active";
    public static final String PENDING_GATE_CLOSE_KEY = "livingvillages_livestock_gate_close_pending";
    public static final String SHEEP_OWNER_KEY = "livingvillages_livestock_owner";

    static final String STATE = "livingvillages_livestock_state";
    static final String TARGET_SHEEP = "livingvillages_livestock_target_sheep";
    private static final String TASK_STARTED = "livingvillages_livestock_task_started";
    private static final String NEXT_ACTION = "livingvillages_livestock_next_action";
    private static final String LAST_ORDER = "livingvillages_livestock_last_order";
    private static final String HERD_DEADLINE = "livingvillages_livestock_herd_deadline";

    private static final String ROUTE_ACTIVE = "livingvillages_livestock_route_active";
    private static final String ROUTE_KIND = "livingvillages_livestock_route_kind";
    private static final String ROUTE_STORAGE_X = "livingvillages_livestock_route_storage_x";
    private static final String ROUTE_STORAGE_Y = "livingvillages_livestock_route_storage_y";
    private static final String ROUTE_STORAGE_Z = "livingvillages_livestock_route_storage_z";
    private static final String ROUTE_STAND_X = "livingvillages_livestock_route_stand_x";
    private static final String ROUTE_STAND_Y = "livingvillages_livestock_route_stand_y";
    private static final String ROUTE_STAND_Z = "livingvillages_livestock_route_stand_z";
    private static final String ROUTE_STARTED = "livingvillages_livestock_route_started";

    private static final String SHEEP_LAST_LURE_TICK = "livingvillages_livestock_last_lure_tick";
    private static final String SHEEP_LURE_OWNER = "livingvillages_livestock_lure_owner";

    private static final int STATE_IDLE = 0;
    static final int STATE_HERD_APPROACH = 1;
    static final int STATE_HERD_TO_GATE = 2;
    static final int STATE_HERD_ENTER_PEN = 3;
    private static final int STATE_SHEAR = 4;

    private static final int ROUTE_FETCH_WHEAT = 1;
    private static final int ROUTE_FETCH_SHEARS = 2;
    private static final int ROUTE_DELIVER_WOOL = 3;

    private static final int TARGET_SHEEP_COUNT = 2;
    private static final int WHEAT_WITHDRAWAL = 8;
    private static final int MAX_SHEEP_SCAN = 32;
    private static final long RETRY_TICKS = 100L;
    private static final long FAILURE_BACKOFF_TICKS = 1200L;
    private static final long ROUTE_TIMEOUT_TICKS = 600L;
    private static final long ROUTE_REISSUE_TICKS = 40L;
    private static final long HERD_TIMEOUT_TICKS = 500L;
    private static final double SHEEP_LURE_RADIUS_SQR = 10.0D * 10.0D;
    private static final double SHEEP_FOLLOW_SPEED = 1.05D;
    private static final float WALK_SPEED = 0.92F;

    private static final List<Item> WOOL_ITEMS = List.of(
        Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL, Items.LIGHT_BLUE_WOOL,
        Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL, Items.GRAY_WOOL,
        Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL,
        Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL, Items.BLACK_WOOL
    );

    private static final List<Item> PLANK_ITEMS = List.of(
        Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
        Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
        Items.BAMBOO_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    );

    private VillageLivestock() {}

    public record PenMaterials(Item fenceItem, Item gateItem, Item plankItem) {}

    /** A completed pen is recognized only from the persisted project marker and its real blocks. */
    public static boolean hasCompletePen(ServerLevel level, BlockPos center) {
        var checkpoint = VillageRegistry.getLivestockPenCheckpoint(level, center);
        if (checkpoint == null || !"animal_pen".equals(checkpoint.projectKind())
            || checkpoint.stepIndex() < checkpoint.totalSteps() || checkpoint.totalSteps() != 16) return false;
        BlockPos site = new BlockPos(checkpoint.siteX(), checkpoint.siteY(), checkpoint.siteZ());
        ResourceLocation fenceKey = ResourceLocation.tryParse(checkpoint.primaryMaterialId());
        ResourceLocation gateKey = ResourceLocation.tryParse(checkpoint.secondaryMaterialId());
        if (fenceKey == null || gateKey == null) return false;
        Item fenceItem = BuiltInRegistries.ITEM.get(fenceKey);
        Item gateItem = BuiltInRegistries.ITEM.get(gateKey);
        Block fence = Block.byItem(fenceItem);
        Block gate = Block.byItem(gateItem);
        if (!(fence instanceof FenceBlock) || !(gate instanceof FenceGateBlock)) return false;

        BlockPos gatePos = site.offset(2, 0, 0);
        int fences = 0;
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                if (dx != 0 && dx != 4 && dz != 0 && dz != 4) continue;
                BlockPos pos = site.offset(dx, 0, dz);
                if (!level.hasChunkAt(pos)) return false;
                Block placed = level.getBlockState(pos).getBlock();
                if (pos.equals(gatePos)) {
                    if (placed != gate) return false;
                } else {
                    if (placed != fence) return false;
                    fences++;
                }
            }
        }
        return fences == 15;
    }

    /** The inside pen bounds, not a generic nearby fence enclosure. */
    public static boolean isInsideVillagePen(ServerLevel level, BlockPos center, Sheep sheep) {
        if (sheep == null || !sheep.isAlive() || !hasCompletePen(level, center)) return false;
        var checkpoint = VillageRegistry.getLivestockPenCheckpoint(level, center);
        BlockPos site = new BlockPos(checkpoint.siteX(), checkpoint.siteY(), checkpoint.siteZ());
        BlockPos pos = sheep.blockPosition();
        return pos.getX() > site.getX() && pos.getX() < site.getX() + 4
            && pos.getZ() > site.getZ() && pos.getZ() < site.getZ() + 4
            && Math.abs(pos.getY() - site.getY()) <= 1;
    }

    public static BlockPos penCenter(ServerLevel level, BlockPos center) {
        var checkpoint = VillageRegistry.getLivestockPenCheckpoint(level, center);
        return checkpoint == null ? null : new BlockPos(checkpoint.siteX() + 2, checkpoint.siteY(), checkpoint.siteZ() + 2);
    }

    public static BlockPos penGatePosition(ServerLevel level, BlockPos center) {
        var checkpoint = VillageRegistry.getLivestockPenCheckpoint(level, center);
        return checkpoint == null ? null : new BlockPos(checkpoint.siteX() + 2, checkpoint.siteY(), checkpoint.siteZ());
    }

    /** Selects a fence/gate pair from actual village materials; matching wood species are mandatory. */
    public static PenMaterials selectPenMaterials(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return null;
        Set<String> wanted = new java.util.HashSet<>();
        for (Item planks : PLANK_ITEMS) {
            String path = BuiltInRegistries.ITEM.getKey(planks).getPath();
            if (!path.endsWith("_planks")) continue;
            String base = path.substring(0, path.length() - "_planks".length());
            wanted.add("minecraft:" + path);
            wanted.add("minecraft:" + base + "_log");
            wanted.add("minecraft:" + base + "_wood");
            wanted.add("minecraft:stripped_" + base + "_log");
            wanted.add("minecraft:" + base + "_fence");
            wanted.add("minecraft:" + base + "_fence_gate");
        }
        Map<String, Integer> storage = VillageRegistry.countAvailableOwnedStorageItems(level, center, wanted);
        List<Villager> residents = VillageArea.findVillagers(level, center);
        PenMaterials best = null;
        int bestScore = 0;
        for (Item planks : PLANK_ITEMS) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(planks);
            String base = key.getPath().substring(0, key.getPath().length() - "_planks".length());
            Item fence = itemForPath(key.getNamespace(), base + "_fence");
            Item gate = itemForPath(key.getNamespace(), base + "_fence_gate");
            if (!(Block.byItem(fence) instanceof FenceBlock) || !(Block.byItem(gate) instanceof FenceGateBlock)) continue;

            Item log = VillageIndustry.logItemForPlanks(planks);
            int planksCount = storage.getOrDefault(key.toString(), 0);
            int logCount = storage.getOrDefault(BuiltInRegistries.ITEM.getKey(log).toString(), 0);
            int fenceCount = storage.getOrDefault(BuiltInRegistries.ITEM.getKey(fence).toString(), 0);
            int gateCount = storage.getOrDefault(BuiltInRegistries.ITEM.getKey(gate).toString(), 0);
            for (Villager resident : residents) {
                planksCount += countItem(resident.getInventory(), planks);
                logCount += countItem(resident.getInventory(), log);
                fenceCount += countItem(resident.getInventory(), fence);
                gateCount += countItem(resident.getInventory(), gate);
            }
            int score = planksCount + logCount * 4 + fenceCount * 6 + gateCount * 6;
            if (score > bestScore) {
                bestScore = score;
                best = new PenMaterials(fence, gate, planks);
            }
        }
        return best;
    }

    private static Item itemForPath(String namespace, String path) {
        ResourceLocation id = ResourceLocation.tryParse(namespace + ":" + path);
        return id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
    }

    /** True when housing needs a bed and there are wild adult sheep close enough to lead into a new pen. */
    public static boolean needsPen(ServerLevel level, BlockPos center) {
        if (level == null || center == null || hasCompletePen(level, center)) return false;
        List<Villager> residents = VillageArea.findVillagers(level, center);
        return hasHousingWoolDemand(level, center, residents) && !findHerdableSheep(level, center).isEmpty();
    }

    /** Ensures one idempotent material order exists before the builder/crafter tries to work. */
    public static boolean ensurePenWorkOrder(ServerLevel level, BlockPos center) {
        if (!needsPen(level, center)) return false;
        PenMaterials materials = selectPenMaterials(level, center);
        if (materials == null) {
            VillageRegistry.recordDiagnostic(level, center, "Загон",
                "Нужны брёвна или доски одного вида дерева, чтобы изготовить ограду и калитку.");
            return false;
        }
        long now = level.getGameTime();
        String fenceId = BuiltInRegistries.ITEM.getKey(materials.fenceItem()).toString();
        String gateId = BuiltInRegistries.ITEM.getKey(materials.gateItem()).toString();
        VillageWorkOrders.enqueue(level, center, "animal_pen", "animal_pen", 80, now, 30L * 24000L,
            List.of(
                new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement(fenceId, 15),
                new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement(gateId, 1)
            ));
        return true;
    }

    public static boolean hasPendingPenOrder(ServerLevel level, BlockPos center) {
        return VillageWorkOrders.list(level, center, level.getGameTime()).stream().anyMatch(order ->
            "animal_pen".equals(order.kind())
                && (order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.OPEN
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.WAITING_FOR_RESOURCES
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.READY
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.CLAIMED
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.IN_PROGRESS));
    }

    /** Crafting must stop once the builder has claimed the fully supplied pen order. */
    public static boolean hasCraftablePenOrder(ServerLevel level, BlockPos center) {
        return VillageWorkOrders.list(level, center, level.getGameTime()).stream().anyMatch(order ->
            "animal_pen".equals(order.kind())
                && (order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.OPEN
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.WAITING_FOR_RESOURCES
                    || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.READY));
    }

    public static Item pendingPenFenceItem(ServerLevel level, BlockPos center) {
        return pendingPenRequirement(level, center, true);
    }

    public static Item pendingPenGateItem(ServerLevel level, BlockPos center) {
        return pendingPenRequirement(level, center, false);
    }

    /** Resolves the matching plank type from the material-pinned fence requirement. */
    public static Item pendingPenPlankItem(ServerLevel level, BlockPos center) {
        Item fence = pendingPenFenceItem(level, center);
        if (fence == Items.AIR) return Items.AIR;
        ResourceLocation fenceKey = BuiltInRegistries.ITEM.getKey(fence);
        String path = fenceKey.getPath();
        if (!path.endsWith("_fence")) return Items.AIR;
        ResourceLocation plankKey = ResourceLocation.tryParse(
            fenceKey.getNamespace() + ":" + path.substring(0, path.length() - "_fence".length()) + "_planks");
        if (plankKey == null) return Items.AIR;
        Item planks = BuiltInRegistries.ITEM.get(plankKey);
        return Block.byItem(planks).defaultBlockState().is(net.minecraft.tags.BlockTags.PLANKS) ? planks : Items.AIR;
    }

    private static Item pendingPenRequirement(ServerLevel level, BlockPos center, boolean fence) {
        for (var order : VillageWorkOrders.list(level, center, level.getGameTime())) {
            if (!"animal_pen".equals(order.kind()) || order.isTerminal()) continue;
            String id = order.requirements().stream()
                .filter(req -> req != null && (fence ? req.itemId().endsWith("_fence") && !req.itemId().endsWith("_fence_gate")
                    : req.itemId().endsWith("_fence_gate")))
                .map(com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement::itemId)
                .findFirst().orElse("");
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) {
                Item item = BuiltInRegistries.ITEM.get(key);
                if (item != Items.AIR) return item;
            }
        }
        return Items.AIR;
    }

    /** Starts construction only after all 15 fence pieces and the matching gate physically exist in owned storage. */
    public static boolean tryStartPenProject(ServerLevel level, BlockPos center) {
        if (!ensurePenWorkOrder(level, center) || hasCompletePen(level, center)) return false;
        if (VillageBuilder.hasActiveProjectNear(level, center)) return false;
        Item fence = pendingPenFenceItem(level, center);
        Item gate = pendingPenGateItem(level, center);
        if (fence == Items.AIR || gate == Items.AIR) return false;
        Set<String> required = Set.of(
            BuiltInRegistries.ITEM.getKey(fence).toString(),
            BuiltInRegistries.ITEM.getKey(gate).toString());
        Map<String, Integer> stock = VillageRegistry.countAvailableOwnedStorageItems(level, center, required);
        if (stock.getOrDefault(BuiltInRegistries.ITEM.getKey(fence).toString(), 0) < 15
            || stock.getOrDefault(BuiltInRegistries.ITEM.getKey(gate).toString(), 0) < 1) return false;
        BlockPos site = VillageBuilder.findLivestockPenSite(level, center);
        if (site == null) {
            VillageRegistry.recordDiagnostic(level, center, "Загон", "Нет ровной свободной площадки 5×5 для загона.");
            return false;
        }
        return VillageBuilder.startLivestockPenProject(level, center, site, fence, gate);
    }

    public static boolean needsHerding(ServerLevel level, BlockPos center) {
        if (level == null || center == null || !hasCompletePen(level, center)) return false;
        List<Villager> residents = VillageArea.findVillagers(level, center);
        return hasHousingWoolDemand(level, center, residents)
            && countOwnedSheepInsidePen(level, center) < TARGET_SHEEP_COUNT
            && !findHerdableSheep(level, center).isEmpty();
    }

    /** A herder is useful before shearing too: first it fills the village-built pen with sheep. */
    public static boolean hasWheatAvailable(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return false;
        if (VillageRegistry.countAvailableOwnedStorageItems(level, center, Set.of("minecraft:wheat"))
            .getOrDefault("minecraft:wheat", 0) > 0) return true;
        return VillageArea.findVillagers(level, center).stream()
            .anyMatch(resident -> countItem(resident.getInventory(), Items.WHEAT) > 0);
    }

    public static boolean hasHerderWorkAvailable(ServerLevel level, BlockPos center) {
        return LivingVillagesConfig.ENABLE_VILLAGE_LIVESTOCK.get()
            && ((needsHerding(level, center) && hasWheatAvailable(level, center))
                || (needsWoolForHousing(level, center) && hasShearsAvailable(level, center)));
    }

    public static boolean canAssignHerder(ServerLevel level, BlockPos center, Villager candidate) {
        return candidate != null && candidate.isAlive() && !candidate.isBaby()
            && hasHerderWorkAvailable(level, center);
    }

    public static boolean needsWoolForHousing(ServerLevel level, BlockPos center) {
        if (level == null || center == null || !hasCompletePen(level, center)) return false;
        List<Villager> residents = VillageArea.findVillagers(level, center);
        return hasHousingWoolDemand(level, center, residents) && countOwnedSheepInsidePen(level, center) > 0;
    }

    /** Shared prerequisite for both the builder's pen and the herder's sheep/wool work. */
    private static boolean hasHousingWoolDemand(ServerLevel level, BlockPos center, List<Villager> residents) {
        if (level == null || center == null || residents == null || residents.size() < 2
            || !LivingVillagesConfig.ENABLE_VILLAGE_LIVESTOCK.get()
            || !LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()
            || !LivingVillagesConfig.ENABLE_HOUSING_PROJECTS.get()) return false;
        boolean housingDemand = VillageBuilder.housingNeeded(level, center, residents.size())
            || VillageElder.hasHouseRequest(residents)
            || VillageWorkOrders.list(level, center, level.getGameTime()).stream().anyMatch(order ->
                "house".equals(order.kind())
                    && (order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.OPEN
                        || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.WAITING_FOR_RESOURCES
                        || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.READY
                        || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.CLAIMED
                        || order.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.IN_PROGRESS));
        if (!housingDemand || hasAvailableBed(level, center, residents)) return false;
        return countVillageWool(level, center, residents).values().stream().noneMatch(count -> count >= 3);
    }

    public static boolean hasShearsAvailable(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return false;
        for (Villager resident : VillageArea.findVillagers(level, center)) {
            if (isResidentAvailableForTransfer(resident) && countItem(resident.getInventory(), Items.SHEARS) > 0) return true;
        }
        return findStorages(level, center, null).stream()
            .anyMatch(ref -> countItem(ref.container(), Items.SHEARS) > 0);
    }

    public static boolean hasShearsRecipe(ServerLevel level, BlockPos center) {
        return needsWoolForHousing(level, center) && !hasShearsAvailable(level, center);
    }

    /** Runs the assigned herder. Every item and every animal is real; all routes have timeouts. */
    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager)
            || villager.level().isClientSide() || !villager.isAlive() || villager.isBaby()
            || villager.tickCount % 5 != 0
            || (!VillagerRoles.isHerder(villager) && !villager.getPersistentData().getBoolean(ACTIVE_KEY)
                && !villager.getPersistentData().getBoolean(PENDING_GATE_CLOSE_KEY))
            || !(villager.level() instanceof ServerLevel level)) return;

        CompoundTag data = villager.getPersistentData();
        BlockPos center = ownershipCenter(villager);
        long now = level.getGameTime();
        servicePendingGateClose(level, center, data);
        if (!VillagerRoles.isHerder(villager) && !data.getBoolean(ACTIVE_KEY)) return;
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_VILLAGE_LIVESTOCK.get()
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            || VillagerRoles.shouldYieldToVanillaVillagerAi(villager)
            || isNightOrSleeping(level, villager)
            || VillageRegistry.isVillageAlarmActive(level, center, now)
            || hasHigherPriorityTask(data)) {
            pauseOwnedRoute(villager, data);
            return;
        }

        if (!data.getBoolean(ACTIVE_KEY)) {
            if (now < data.getLong(NEXT_ACTION)) return;
            if (!hasCompletePen(level, center)
                || (!hasWoolInInventory(villager) && !needsHerding(level, center) && !needsWoolForHousing(level, center))) return;
            data.putBoolean(ACTIVE_KEY, true);
            data.putInt(STATE, STATE_IDLE);
            data.putLong(TASK_STARTED, now);
        }
        if (now < data.getLong(NEXT_ACTION)) return;

        if (data.getBoolean(ROUTE_ACTIVE)) {
            continueStorageRoute(villager, level, data, now);
            return;
        }

        claimSheepAlreadyInsidePen(level, center);
        if (hasWoolInInventory(villager)) {
            StorageTarget output = findNearestStorageWithWoolRoom(villager, level);
            if (output == null) {
                data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                VillageRegistry.recordDiagnostic(level, center, "Животновод",
                    "Шерсть есть у животновода, но в складе поселения нет свободного места.");
                return;
            }
            setStorageRoute(villager, data, output, ROUTE_DELIVER_WOOL, now);
            return;
        }

        // Do not let a missing bait supply block a real wool/bed shortage when usable shears exist.
        if (needsWoolForHousing(level, center) && hasShearsAvailable(level, center)) {
            runShearing(villager, level, center, data, now);
            return;
        }

        if (needsHerding(level, center)) {
            runHerding(villager, level, center, data, now);
            return;
        }

        if (needsWoolForHousing(level, center)) {
            runShearing(villager, level, center, data, now);
            return;
        }

        returnWheatToInventory(villager);
        finishTask(villager, data, now + FAILURE_BACKOFF_TICKS);
    }

    private static void runHerding(Villager herder, ServerLevel level, BlockPos center, CompoundTag data, long now) {
        if (!hasWheatInHand(herder)) {
            if (!equipWheatFromInventory(herder)) {
                transferFromResident(level, center, herder, Items.WHEAT);
            }
            if (!equipWheatFromInventory(herder)) {
                StorageTarget source = findNearestStorageWithItem(herder, level, Items.WHEAT);
                if (source == null) {
                    data.putLong(NEXT_ACTION, now + FAILURE_BACKOFF_TICKS);
                    VillageRegistry.recordDiagnostic(level, center, "Животновод",
                        "Для приманивания овец нужна настоящая пшеница в инвентаре или на складе.");
                    return;
                }
                setStorageRoute(herder, data, source, ROUTE_FETCH_WHEAT, now);
                return;
            }
        }

        int state = data.getInt(STATE);
        Sheep target = findTargetSheep(level, herder, data.getString(TARGET_SHEEP));
        if (state == STATE_IDLE || target == null) {
            target = findNearestHerdableSheep(herder, level, center);
            if (target == null) {
                data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                return;
            }
            data.putString(TARGET_SHEEP, target.getUUID().toString());
            data.putInt(STATE, STATE_HERD_APPROACH);
            data.putLong(TASK_STARTED, now);
            data.putLong(HERD_DEADLINE, now + HERD_TIMEOUT_TICKS);
            data.putLong(LAST_ORDER, 0L);
            state = STATE_HERD_APPROACH;
        }

        if (now > data.getLong(HERD_DEADLINE)) {
            requestPenGateClose(level, center, data);
            clearHerdMovement(herder, level, center, data);
            data.remove(TARGET_SHEEP);
            data.putInt(STATE, STATE_IDLE);
            data.putLong(NEXT_ACTION, now + FAILURE_BACKOFF_TICKS);
            return;
        }

        if (state == STATE_HERD_APPROACH) {
            if (herder.distanceToSqr(target) > 8.0D * 8.0D) {
                orderEntityRoute(herder, target, data, now);
                return;
            }
            BlockPos outside = penGatePosition(level, center).north();
            if (!isSafeStand(level, outside) || !canReach(herder, outside)) {
                data.remove(TARGET_SHEEP);
                data.putInt(STATE, STATE_IDLE);
                recordFailure(data, now);
                return;
            }
            data.putInt(STATE, STATE_HERD_TO_GATE);
            data.putLong(LAST_ORDER, 0L);
            state = STATE_HERD_TO_GATE;
        }

        if (state == STATE_HERD_TO_GATE) {
            BlockPos outside = penGatePosition(level, center).north();
            if (herder.distanceToSqr(Vec3.atCenterOf(outside)) > 2.4D * 2.4D) {
                orderBlockRoute(herder, outside, data, now);
                return;
            }
            if (!openPenGate(level, center)) {
                data.remove(TARGET_SHEEP);
                data.putInt(STATE, STATE_IDLE);
                data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                return;
            }
            data.putInt(STATE, STATE_HERD_ENTER_PEN);
            data.putLong(LAST_ORDER, 0L);
            state = STATE_HERD_ENTER_PEN;
        }

        if (state == STATE_HERD_ENTER_PEN) {
            BlockPos inside = penCenter(level, center);
            if (inside == null) {
                requestPenGateClose(level, center, data);
                finishTask(herder, data, now + RETRY_TICKS);
                return;
            }
            if (herder.distanceToSqr(Vec3.atCenterOf(inside)) > 2.0D * 2.0D) {
                orderBlockRoute(herder, inside, data, now);
                return;
            }
            if (isInsideVillagePen(level, center, target)) {
                String owner = target.getPersistentData().getString(SHEEP_OWNER_KEY);
                UUID villageId = VillageRegistry.villageId(level, center);
                if (villageId != null && (owner.isBlank() || owner.equals(villageId.toString()))) {
                    target.getPersistentData().putString(SHEEP_OWNER_KEY, villageId.toString());
                    VillageRegistry.recordDiagnostic(level, center, "Животновод",
                        "Овца приведена в загон на настоящую пшеницу.");
                }
                requestPenGateClose(level, center, data);
                data.remove(TARGET_SHEEP);
                data.putInt(STATE, STATE_IDLE);
                data.putLong(NEXT_ACTION, now + 20L);
                return;
            }
            if (!hasWheatInHand(herder)) {
                requestPenGateClose(level, center, data);
                data.remove(TARGET_SHEEP);
                data.putInt(STATE, STATE_IDLE);
                return;
            }
            orderBlockRoute(herder, inside, data, now);
        }
    }

    private static void runShearing(Villager herder, ServerLevel level, BlockPos center, CompoundTag data, long now) {
        if (countItem(herder.getInventory(), Items.SHEARS) <= 0) {
            transferFromResident(level, center, herder, Items.SHEARS);
        }
        if (countItem(herder.getInventory(), Items.SHEARS) <= 0) {
            StorageTarget source = findNearestStorageWithItem(herder, level, Items.SHEARS);
            if (source == null) {
                data.putLong(NEXT_ACTION, now + FAILURE_BACKOFF_TICKS);
                VillageRegistry.recordDiagnostic(level, center, "Животновод",
                    "Нужны настоящие ножницы в инвентаре или на складе поселения.");
                return;
            }
            setStorageRoute(herder, data, source, ROUTE_FETCH_SHEARS, now);
            return;
        }

        Sheep target = findTargetSheep(level, herder, data.getString(TARGET_SHEEP));
        if (target == null || !isOwnedSheepInsidePen(level, center, target)
            || !target.readyForShearing()) {
            target = findReadySheep(herder, level, center);
            if (target == null) {
                data.remove(TARGET_SHEEP);
                data.putInt(STATE, STATE_IDLE);
                data.putLong(NEXT_ACTION, now + RETRY_TICKS);
                return;
            }
            data.putString(TARGET_SHEEP, target.getUUID().toString());
            data.putInt(STATE, STATE_SHEAR);
            data.putLong(TASK_STARTED, now);
            data.putLong(HERD_DEADLINE, now + ROUTE_TIMEOUT_TICKS);
            data.putLong(LAST_ORDER, 0L);
        }

        if (now > data.getLong(HERD_DEADLINE)) {
            clearOwnedWalkTarget(herder, target.blockPosition());
            data.remove(TARGET_SHEEP);
            data.putInt(STATE, STATE_IDLE);
            recordFailure(data, now);
            return;
        }
        if (herder.distanceToSqr(target) > 4.2D * 4.2D || !canSeeSheep(level, herder, target)) {
            orderEntityRoute(herder, target, data, now);
            return;
        }

        ItemStack shears = firstShears(herder.getInventory());
        UUID villageId = VillageRegistry.villageId(level, center);
        boolean sheared = shears != null && shearManagedSheep(level, target, shears, center);
        if (sheared) {
            collectNearbyWool(herder, level, target);
            VillageRegistry.recordDiagnostic(level, center, "Животновод",
                "Овца острижена настоящими ножницами; шерсть собрана.");
            data.putInt("livingvillages_livestock_failures", 0);
        } else {
            recordFailure(data, now);
        }
        clearOwnedWalkTarget(herder, target.blockPosition());
        data.remove(TARGET_SHEEP);
        data.putInt(STATE, STATE_IDLE);
        data.putLong(NEXT_ACTION, now + 20L);
    }

    /** Sheep are only claimed after the player-controlled settlement's own pen is complete. */
    private static void claimSheepAlreadyInsidePen(ServerLevel level, BlockPos center) {
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId == null || !hasCompletePen(level, center)) return;
        var checkpoint = VillageRegistry.getLivestockPenCheckpoint(level, center);
        BlockPos site = new BlockPos(checkpoint.siteX(), checkpoint.siteY(), checkpoint.siteZ());
        AABB inside = new AABB(site.getX() + 1.0D, site.getY(), site.getZ() + 1.0D,
            site.getX() + 4.0D, site.getY() + 2.0D, site.getZ() + 4.0D);
        for (Sheep sheep : level.getEntitiesOfClass(Sheep.class, inside, Sheep::isAlive)) {
            if (!isInsideVillagePen(level, center, sheep)) continue;
            String owner = sheep.getPersistentData().getString(SHEEP_OWNER_KEY);
            if (owner.isBlank() || owner.equals(villageId.toString())) {
                sheep.getPersistentData().putString(SHEEP_OWNER_KEY, villageId.toString());
            }
        }
    }

    /** Sheep follow only a herder carrying actual wheat; no remote/global attraction is used. */
    public static void onAnimalTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Sheep sheep) || sheep.level().isClientSide()
            || !sheep.isAlive() || sheep.tickCount % 10 != 0
            || !(sheep.level() instanceof ServerLevel level)) return;
        if (!LivingVillagesConfig.ENABLE_VILLAGE_LIVESTOCK.get()) return;

        AABB area = sheep.getBoundingBox().inflate(10.0D, 4.0D, 10.0D);
        List<Villager> herders = level.getEntitiesOfClass(Villager.class, area,
            villager -> isCurrentHerdTarget(villager, sheep));
        herders.sort(Comparator.comparingDouble(sheep::distanceToSqr));
        if (herders.isEmpty()) return;
        Villager herder = herders.get(0);
        if (!isCurrentHerdTarget(herder, sheep)) return;
        BlockPos center = ownershipCenter(herder);
        UUID villageId = VillageRegistry.villageId(level, center);
        String owner = sheep.getPersistentData().getString(SHEEP_OWNER_KEY);
        CompoundTag sheepData = sheep.getPersistentData();
        long now = level.getGameTime();
        String lureOwner = sheepData.getString(SHEEP_LURE_OWNER);
        long lastLure = sheepData.getLong(SHEEP_LAST_LURE_TICK);
        // Herding never overrides the animal controller's danger response, parental protection,
        // or shelter-seeking decision. The alert window also protects the herd between AI checks.
        int animalAction = sheepData.getInt(VillageAnimalAI.LAST_ACTION_KEY);
        if (sheepData.getLong(VillageAnimalAI.HERD_ALERT_UNTIL_KEY) > now
            || animalAction == com.abs.livingvillages.domain.TrustAndAnimalModel.ANIMAL_ACTION_FLEE_THREAT
            || animalAction == com.abs.livingvillages.domain.TrustAndAnimalModel.ANIMAL_ACTION_PROTECT_OFFSPRING
            || animalAction == com.abs.livingvillages.domain.TrustAndAnimalModel.ANIMAL_ACTION_SEEK_SHELTER) return;
        if (villageId == null || sheep.isLeashed() || sheep.hasCustomName()
            || (!owner.isBlank() && !owner.equals(villageId.toString()))
            || (!lureOwner.isBlank() && !lureOwner.equals(villageId.toString()) && now - lastLure < HERD_TIMEOUT_TICKS)
            || isInsideVillagePen(level, center, sheep) || isInsideExistingFencePen(level, sheep)
            || sheep.distanceToSqr(herder) > SHEEP_LURE_RADIUS_SQR
            || !canSeeSheep(level, sheep, herder)) return;

        if (now - lastLure < 20L) return;
        sheep.getNavigation().moveTo(herder, SHEEP_FOLLOW_SPEED);
        sheep.getPersistentData().putLong(SHEEP_LAST_LURE_TICK, now);
        sheep.getPersistentData().putString(SHEEP_LURE_OWNER, villageId.toString());
    }

    /** True only for the one sheep this active specialist is currently leading into the pen. */
    static boolean isCurrentHerdTarget(Villager herder, Sheep sheep) {
        if (herder == null || sheep == null || !herder.isAlive() || !sheep.isAlive()
            || !VillagerRoles.isHerder(herder)
            || !herder.getPersistentData().getBoolean(ACTIVE_KEY)
            || !herder.getMainHandItem().is(Items.WHEAT)) return false;
        int state = herder.getPersistentData().getInt(STATE);
        if (state != STATE_HERD_APPROACH && state != STATE_HERD_TO_GATE && state != STATE_HERD_ENTER_PEN) return false;
        try {
            return sheep.getUUID().equals(UUID.fromString(herder.getPersistentData().getString(TARGET_SHEEP)));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Only sheep parents owned by the same village pass ownership to a baby born inside its own pen. */
    static void inheritSheepOwnership(net.minecraft.world.entity.animal.Animal parentA,
                                     net.minecraft.world.entity.animal.Animal parentB,
                                     net.minecraft.world.entity.AgeableMob child) {
        if (!(parentA instanceof Sheep first) || !(parentB instanceof Sheep second)
            || !(child instanceof Sheep baby) || first.level().isClientSide()
            || !(first.level() instanceof ServerLevel level)) return;
        String firstOwner = first.getPersistentData().getString(SHEEP_OWNER_KEY);
        String secondOwner = second.getPersistentData().getString(SHEEP_OWNER_KEY);
        if (firstOwner.isBlank() || !firstOwner.equals(secondOwner)) return;
        for (BlockPos anchor : VillageRegistry.knownLoadedVillageAnchors(level)) {
            UUID id = VillageRegistry.villageId(level, anchor);
            if (id != null && id.toString().equals(firstOwner)
                && isInsideVillagePen(level, anchor, baby)) {
                baby.getPersistentData().putString(SHEEP_OWNER_KEY, firstOwner);
                return;
            }
        }
    }

    static boolean shearManagedSheep(ServerLevel level, Sheep sheep, ItemStack shears, BlockPos center) {
        UUID villageId = level == null || center == null ? null : VillageRegistry.villageId(level, center);
        if (level == null || sheep == null || shears == null || villageId == null
            || !sheep.isAlive() || !shears.is(Items.SHEARS) || shears.isEmpty()
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            || !villageId.toString().equals(sheep.getPersistentData().getString(SHEEP_OWNER_KEY))
            || !isInsideVillagePen(level, center, sheep) || !sheep.readyForShearing()) return false;
        // 1.21.1 exposes Sheep.shear(SoundSource); it creates the vanilla drops.
        // Apply the real shears' durability cost because the tool is carried in inventory, not equipped.
        sheep.shear(SoundSource.NEUTRAL);
        if (sheep.isSheared()) {
            int nextDamage = shears.getDamageValue() + 1;
            if (nextDamage >= shears.getMaxDamage()) shears.shrink(1);
            else shears.setDamageValue(nextDamage);
        }
        return sheep.isSheared();
    }

    /** Called by the area planner to close a leftover gate after an interrupted/unloaded herding trip. */
    public static void maintainPenGate(ServerLevel level, BlockPos center) {
        if (level == null || center == null || !hasCompletePen(level, center)) return;
        boolean activeAtGate = VillageArea.findVillagers(level, center).stream().anyMatch(resident -> {
            CompoundTag data = resident.getPersistentData();
            int state = data.getInt(STATE);
            return VillagerRoles.isHerder(resident) && data.getBoolean(ACTIVE_KEY)
                && (state == STATE_HERD_TO_GATE || state == STATE_HERD_ENTER_PEN);
        });
        if (!activeAtGate) closePenGateIfClear(level, center);
    }

    /**
     * Do not lure livestock out of an existing 5x5 fenced/walled pen, including a pen whose
     * gate was left open. Vanilla has no universal player-owner field for sheep, so treating
     * any already enclosed animal as unavailable is the conservative no-theft policy.
     */
    static boolean isInsideExistingFencePen(ServerLevel level, Sheep sheep) {
        if (level == null || sheep == null || !sheep.isAlive()
            || !level.hasChunkAt(sheep.blockPosition())
            || !level.getBlockState(sheep.blockPosition().below()).blocksMotion()) return false;
        BlockPos animalPos = sheep.blockPosition();
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                BlockPos penCenter = animalPos.offset(offsetX, 0, offsetZ);
                boolean enclosed = true;
                for (int dx = -2; dx <= 2 && enclosed; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
                        BlockPos boundary = penCenter.offset(dx, 0, dz);
                        if (!level.hasChunkAt(boundary)) {
                            enclosed = false;
                            break;
                        }
                        var block = level.getBlockState(boundary).getBlock();
                        if (!(block instanceof FenceBlock) && !(block instanceof FenceGateBlock)
                            && !(block instanceof net.minecraft.world.level.block.WallBlock)) {
                            enclosed = false;
                            break;
                        }
                    }
                }
                if (enclosed) return true;
            }
        }
        return false;
    }

    private static List<Sheep> findHerdableSheep(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return List.of();
        UUID villageId = VillageRegistry.villageId(level, center);
        int radius = Math.max(12, Math.min(28, LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get()));
        AABB area = new AABB(center).inflate(radius, 8.0D, radius);
        List<Sheep> candidates = level.getEntitiesOfClass(Sheep.class, area,
            sheep -> sheep.isAlive() && !sheep.isBaby()
                && sheep.distanceToSqr(Vec3.atCenterOf(center)) <= (double) radius * radius);
        long now = level.getGameTime();
        candidates.removeIf(sheep -> {
            CompoundTag sheepData = sheep.getPersistentData();
            String owner = sheepData.getString(SHEEP_OWNER_KEY);
            String lureOwner = sheepData.getString(SHEEP_LURE_OWNER);
            long lastLure = sheepData.getLong(SHEEP_LAST_LURE_TICK);
            boolean heldByOtherVillage = !lureOwner.isBlank() && villageId != null
                && !lureOwner.equals(villageId.toString()) && now - lastLure < HERD_TIMEOUT_TICKS;
            return villageId == null || sheep.isLeashed() || sheep.hasCustomName()
                || (!owner.isBlank() && !owner.equals(villageId.toString()))
                || heldByOtherVillage;
        });
        candidates.sort(Comparator.comparingDouble(sheep -> sheep.distanceToSqr(Vec3.atCenterOf(center))));
        if (candidates.size() > MAX_SHEEP_SCAN) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_SHEEP_SCAN));
        }
        candidates.removeIf(sheep -> isInsideVillagePen(level, center, sheep)
            || isInsideExistingFencePen(level, sheep));
        return List.copyOf(candidates);
    }

    private static Sheep findNearestHerdableSheep(Villager herder, ServerLevel level, BlockPos center) {
        return findHerdableSheep(level, center).stream()
            .filter(sheep -> !isInsideVillagePen(level, center, sheep))
            .filter(sheep -> canReach(herder, sheep))
            .min(Comparator.comparingDouble(herder::distanceToSqr))
            .orElse(null);
    }

    private static Sheep findReadySheep(Villager worker, ServerLevel level, BlockPos center) {
        UUID villageId = VillageRegistry.villageId(level, center);
        if (villageId == null) return null;
        List<Villager> residents = VillageArea.findVillagers(level, center);
        Map<Item, Integer> woolCounts = countVillageWool(level, center, residents);
        var checkpoint = VillageRegistry.getLivestockPenCheckpoint(level, center);
        BlockPos site = checkpoint == null ? null : new BlockPos(checkpoint.siteX(), checkpoint.siteY(), checkpoint.siteZ());
        if (site == null) return null;
        AABB inside = new AABB(site.getX() + 1.0D, site.getY(), site.getZ() + 1.0D,
            site.getX() + 4.0D, site.getY() + 2.0D, site.getZ() + 4.0D);
        return level.getEntitiesOfClass(Sheep.class, inside,
                sheep -> sheep.isAlive() && !sheep.isBaby()
                    && villageId.toString().equals(sheep.getPersistentData().getString(SHEEP_OWNER_KEY))
                    && isInsideVillagePen(level, center, sheep))
            .stream()
            .filter(Sheep::readyForShearing)
            .filter(sheep -> woolCounts.getOrDefault(woolItem(sheep), 0) < 3)
            .filter(sheep -> canReach(worker, sheep))
            .min(Comparator.comparingDouble(worker::distanceToSqr))
            .orElse(null);
    }

    private static boolean isOwnedSheepInsidePen(ServerLevel level, BlockPos center, Sheep sheep) {
        UUID owner = VillageRegistry.villageId(level, center);
        return owner != null && owner.toString().equals(sheep.getPersistentData().getString(SHEEP_OWNER_KEY))
            && isInsideVillagePen(level, center, sheep);
    }

    private static int countOwnedSheepInsidePen(ServerLevel level, BlockPos center) {
        UUID owner = VillageRegistry.villageId(level, center);
        var checkpoint = VillageRegistry.getLivestockPenCheckpoint(level, center);
        if (owner == null || checkpoint == null || !hasCompletePen(level, center)) return 0;
        BlockPos site = new BlockPos(checkpoint.siteX(), checkpoint.siteY(), checkpoint.siteZ());
        AABB inside = new AABB(site.getX() + 1.0D, site.getY(), site.getZ() + 1.0D,
            site.getX() + 4.0D, site.getY() + 2.0D, site.getZ() + 4.0D);
        return level.getEntitiesOfClass(Sheep.class, inside,
            sheep -> sheep.isAlive() && !sheep.isBaby()
                && owner.toString().equals(sheep.getPersistentData().getString(SHEEP_OWNER_KEY))
                && isInsideVillagePen(level, center, sheep)).size();
    }

    private static boolean hasAvailableBed(ServerLevel level, BlockPos center, List<Villager> residents) {
        Set<String> wanted = VillageBuilder.ALL_BED_ITEMS.stream()
            .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
            .collect(java.util.stream.Collectors.toSet());
        Map<String, Integer> stored = VillageRegistry.countAvailableOwnedStorageItems(level, center, wanted);
        for (Item bed : VillageBuilder.ALL_BED_ITEMS) {
            if (stored.getOrDefault(BuiltInRegistries.ITEM.getKey(bed).toString(), 0) > 0) return true;
            for (Villager resident : residents) if (countItem(resident.getInventory(), bed) > 0) return true;
        }
        return false;
    }

    private static Map<Item, Integer> countVillageWool(ServerLevel level, BlockPos center, List<Villager> residents) {
        Set<String> wanted = WOOL_ITEMS.stream()
            .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
            .collect(java.util.stream.Collectors.toSet());
        Map<String, Integer> stored = VillageRegistry.countAvailableOwnedStorageItems(level, center, wanted);
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

    private static boolean hasWoolInInventory(Villager villager) {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (!stack.isEmpty() && isWool(stack.getItem())) return true;
        }
        return false;
    }

    private static boolean hasWheatInHand(Villager villager) {
        return villager.getMainHandItem().is(Items.WHEAT);
    }

    private static boolean equipWheatFromInventory(Villager villager) {
        if (hasWheatInHand(villager)) return true;
        if (!villager.getMainHandItem().isEmpty()) return false;
        ItemStack wheat = removeOne(villager.getInventory(), Items.WHEAT);
        if (wheat.isEmpty()) return false;
        villager.setItemInHand(InteractionHand.MAIN_HAND, wheat);
        villager.getInventory().setChanged();
        return true;
    }

    private static void returnWheatToInventory(Villager villager) {
        ItemStack held = villager.getMainHandItem();
        if (!held.is(Items.WHEAT)) return;
        ItemStack copy = held.copy();
        villager.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        ItemStack remaining = villager.getInventory().addItem(copy);
        if (!remaining.isEmpty()) villager.spawnAtLocation(remaining);
        villager.getInventory().setChanged();
    }

    private static void collectNearbyWool(Villager worker, ServerLevel level, Sheep sheep) {
        AABB area = sheep.getBoundingBox().inflate(1.8D, 1.3D, 1.8D);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, area,
            item -> item.isAlive() && !item.getItem().isEmpty() && isWool(item.getItem().getItem()));
        for (ItemEntity drop : drops) {
            ItemStack remainder = worker.getInventory().addItem(drop.getItem().copy());
            if (remainder.isEmpty()) drop.discard();
            else drop.setItem(remainder);
        }
        worker.getInventory().setChanged();
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

    private static Sheep findTargetSheep(ServerLevel level, Villager worker, String uuidText) {
        UUID targetId;
        try { targetId = UUID.fromString(uuidText); }
        catch (RuntimeException ignored) { return null; }
        AABB area = worker.getBoundingBox().inflate(36.0D, 10.0D, 36.0D);
        return level.getEntitiesOfClass(Sheep.class, area,
            candidate -> candidate.isAlive() && candidate.getUUID().equals(targetId)).stream()
            .findFirst().orElse(null);
    }

    private static void orderEntityRoute(Villager villager, Sheep sheep, CompoundTag data, long now) {
        boolean wrongTarget = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
            .map(walk -> !walk.getTarget().currentBlockPosition().equals(sheep.blockPosition())).orElse(true);
        if (wrongTarget || now - data.getLong(LAST_ORDER) >= ROUTE_REISSUE_TICKS) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(sheep, WALK_SPEED, 2));
            data.putLong(LAST_ORDER, now);
        }
    }

    private static void orderBlockRoute(Villager villager, BlockPos target, CompoundTag data, long now) {
        boolean wrongTarget = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
            .map(walk -> !walk.getTarget().currentBlockPosition().equals(target)).orElse(true);
        if (wrongTarget || now - data.getLong(LAST_ORDER) >= ROUTE_REISSUE_TICKS) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, 1));
            data.putLong(LAST_ORDER, now);
        }
    }

    private static boolean canReach(Villager villager, Sheep sheep) {
        var path = villager.getNavigation().createPath(sheep, 2);
        return path != null && path.canReach();
    }

    private static boolean canReach(Villager villager, BlockPos pos) {
        var path = villager.getNavigation().createPath(pos, 0);
        return path != null && path.canReach();
    }

    private static boolean canSeeSheep(ServerLevel level, net.minecraft.world.entity.LivingEntity observer, Sheep sheep) {
        HitResult hit = level.clip(new ClipContext(
            observer.getEyePosition(), sheep.getEyePosition(),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, observer));
        return hit.getType() == HitResult.Type.MISS
            || (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                && blockHit.getBlockPos().distSqr(sheep.blockPosition()) <= 1);
    }

    private static boolean canSeeSheep(ServerLevel level, Sheep sheep, Villager herder) {
        return canSeeSheep(level, herder, sheep);
    }

    private static boolean openPenGate(ServerLevel level, BlockPos center) {
        BlockPos gatePos = penGatePosition(level, center);
        if (gatePos == null || !level.hasChunkAt(gatePos)) return false;
        BlockState gate = level.getBlockState(gatePos);
        if (!(gate.getBlock() instanceof FenceGateBlock)) return false;
        if (!gate.getValue(FenceGateBlock.OPEN)) {
            level.setBlock(gatePos, gate.setValue(FenceGateBlock.OPEN, true), 3);
        }
        return true;
    }

    private static boolean closePenGateIfClear(ServerLevel level, BlockPos center) {
        BlockPos gatePos = penGatePosition(level, center);
        if (gatePos == null || !level.hasChunkAt(gatePos)) return false;
        BlockState gate = level.getBlockState(gatePos);
        if (!(gate.getBlock() instanceof FenceGateBlock)) return false;
        if (!gate.getValue(FenceGateBlock.OPEN)) return true;
        // Never close the gate on the sheep or villager currently crossing it.
        AABB gateVolume = new AABB(gatePos);
        if (!level.getEntitiesOfClass(LivingEntity.class, gateVolume, LivingEntity::isAlive).isEmpty()) return false;
        return level.setBlock(gatePos, gate.setValue(FenceGateBlock.OPEN, false), 3);
    }

    private static void requestPenGateClose(ServerLevel level, BlockPos center, CompoundTag data) {
        if (closePenGateIfClear(level, center)) data.remove(PENDING_GATE_CLOSE_KEY);
        else data.putBoolean(PENDING_GATE_CLOSE_KEY, true);
    }

    private static void servicePendingGateClose(ServerLevel level, BlockPos center, CompoundTag data) {
        if (data.getBoolean(PENDING_GATE_CLOSE_KEY) && closePenGateIfClear(level, center)) {
            data.remove(PENDING_GATE_CLOSE_KEY);
        }
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below())
            && (level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced())
            && (level.getBlockState(pos.above()).isAir() || level.getBlockState(pos.above()).canBeReplaced())
            && level.getBlockState(pos.below()).blocksMotion();
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

    private static boolean isNightOrSleeping(ServerLevel level, Villager villager) {
        long day = Math.floorMod(level.getDayTime(), 24000L);
        return villager.isSleeping() || day < 1000L || day >= 12000L;
    }

    private static BlockPos ownershipCenter(Villager villager) {
        BlockPos home = VillagerRoles.getHome(villager);
        return home == null ? villager.blockPosition() : home;
    }

    private static boolean hasShearsInOwnedStorage(ServerLevel level, BlockPos center) {
        return findStorages(level, center, null).stream()
            .anyMatch(ref -> countItem(ref.container(), Items.SHEARS) > 0);
    }

    private static ItemStack firstShears(Container inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.SHEARS) && !stack.isEmpty()) return stack;
        }
        return null;
    }

    private static void setStorageRoute(Villager worker, CompoundTag data, StorageTarget target, int kind, long now) {
        clearStorageRoute(worker, data);
        if (!VillageReservations.tryAddStorageReservation((ServerLevel) worker.level(), worker, target.storage(), "livestock")) return;
        data.putBoolean(ROUTE_ACTIVE, true);
        data.putInt(ROUTE_KIND, kind);
        data.putInt(ROUTE_STORAGE_X, target.storage().getX());
        data.putInt(ROUTE_STORAGE_Y, target.storage().getY());
        data.putInt(ROUTE_STORAGE_Z, target.storage().getZ());
        data.putInt(ROUTE_STAND_X, target.stand().getX());
        data.putInt(ROUTE_STAND_Y, target.stand().getY());
        data.putInt(ROUTE_STAND_Z, target.stand().getZ());
        data.putLong(ROUTE_STARTED, now);
        data.putLong(LAST_ORDER, now);
        worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target.stand(), WALK_SPEED, 1));
    }

    private static void continueStorageRoute(Villager worker, ServerLevel level, CompoundTag data, long now) {
        BlockPos storagePos = routeStorage(data);
        BlockPos stand = routeStand(data);
        if (now - data.getLong(ROUTE_STARTED) > ROUTE_TIMEOUT_TICKS
            || !level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
            || !VillageRegistry.isStorageForVillage(level, ownershipCenter(worker), storagePos)
            || !VillageReservations.isStorageAvailable(level, storagePos, worker)
            || !(level.getBlockEntity(storagePos) instanceof Container storage)) {
            clearStorageRoute(worker, data);
            VillageReservations.release(data, "livestock");
            recordFailure(data, now);
            return;
        }

        if (worker.distanceToSqr(Vec3.atCenterOf(stand)) > 2.8D * 2.8D) {
            orderBlockRoute(worker, stand, data, now);
            return;
        }

        int kind = data.getInt(ROUTE_KIND);
        if (kind == ROUTE_FETCH_WHEAT) {
            int amount = Math.min(WHEAT_WITHDRAWAL, Math.min(countItem(storage, Items.WHEAT),
                inventoryCapacityFor(worker.getInventory(), Items.WHEAT)));
            transferFromStorage(worker, storage, Items.WHEAT, amount);
        } else if (kind == ROUTE_FETCH_SHEARS) {
            if (countItem(worker.getInventory(), Items.SHEARS) == 0) {
                transferFromStorage(worker, storage, Items.SHEARS, 1);
            }
        } else if (kind == ROUTE_DELIVER_WOOL) {
            depositWool(worker.getInventory(), storage);
        }
        storage.setChanged();
        worker.getInventory().setChanged();
        VillageReservations.release(data, "livestock");
        clearStorageRoute(worker, data);
        data.putLong(NEXT_ACTION, now + 20L);
    }

    private static void clearStorageRoute(Villager worker, CompoundTag data) {
        if (data.getBoolean(ROUTE_ACTIVE)) {
            BlockPos stand = routeStand(data);
            clearOwnedWalkTarget(worker, stand);
        }
        data.putBoolean(ROUTE_ACTIVE, false);
        data.remove(ROUTE_KIND); data.remove(ROUTE_STORAGE_X); data.remove(ROUTE_STORAGE_Y); data.remove(ROUTE_STORAGE_Z);
        data.remove(ROUTE_STAND_X); data.remove(ROUTE_STAND_Y); data.remove(ROUTE_STAND_Z); data.remove(ROUTE_STARTED);
    }

    private static void pauseOwnedRoute(Villager worker, CompoundTag data) {
        if (data.getBoolean(ROUTE_ACTIVE)) clearOwnedWalkTarget(worker, routeStand(data));
        if (worker.level() instanceof ServerLevel level) {
            int state = data.getInt(STATE);
            if (state == STATE_HERD_TO_GATE || state == STATE_HERD_ENTER_PEN) {
                requestPenGateClose(level, ownershipCenter(worker), data);
            }
            clearHerdMovement(worker, level, ownershipCenter(worker), data);
        }
        // A pause does not delete or consume the physical bait/tool/cargo.
    }

    private static void clearHerdMovement(Villager worker, ServerLevel level, BlockPos center, CompoundTag data) {
        int state = data.getInt(STATE);
        if (state == STATE_HERD_APPROACH || state == STATE_SHEAR) {
            Sheep target = findTargetSheep(level, worker, data.getString(TARGET_SHEEP));
            if (target != null) clearOwnedWalkTarget(worker, target.blockPosition());
        }
        BlockPos gate = penGatePosition(level, center);
        if (gate != null) clearOwnedWalkTarget(worker, gate.north());
        clearOwnedWalkTarget(worker, penCenter(level, center));
    }

    private static void clearOwnedWalkTarget(Villager worker, BlockPos target) {
        if (target == null) return;
        worker.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
            if (walk.getTarget().currentBlockPosition().equals(target)) {
                worker.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }
        });
    }

    private static StorageTarget findNearestStorageWithItem(Villager worker, ServerLevel level, Item item) {
        List<StorageRef> refs = findStorages(level, ownershipCenter(worker), worker);
        refs.sort(Comparator.comparingDouble(ref -> worker.distanceToSqr(Vec3.atCenterOf(ref.pos()))));
        for (StorageRef ref : refs) {
            if (countItem(ref.container(), item) <= 0) continue;
            BlockPos stand = findSafeStand(level, worker, ref.pos());
            if (stand != null) return new StorageTarget(ref.pos(), stand);
        }
        return null;
    }

    private static StorageTarget findNearestStorageWithWoolRoom(Villager worker, ServerLevel level) {
        List<StorageRef> refs = findStorages(level, ownershipCenter(worker), worker);
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
            if (stand != null) return new StorageTarget(ref.pos(), stand);
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

    private static void depositWool(Container inventory, Container storage) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !isWool(stack.getItem())) continue;
            ItemStack offered = stack.copy();
            int moved = insert(storage, offered);
            if (moved > 0) stack.shrink(moved);
        }
    }

    private static boolean isResidentAvailableForTransfer(Villager resident) {
        if (resident == null || !resident.isAlive() || resident.isBaby()) return false;
        CompoundTag data = resident.getPersistentData();
        return !data.getBoolean(VillageBuilder.ACTIVE_KEY)
            && !data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
            && !data.getBoolean(VillagePopulation.ACTIVE_KEY)
            && !data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            && !data.getBoolean(ACTIVE_KEY)
            && !data.getBoolean("livingvillages_logistics_active")
            && !data.getBoolean("livingvillages_mining_active")
            && !data.getBoolean("livingvillages_treework_active")
            && !data.getBoolean("livingvillages_treework_replant_active")
            && !data.getBoolean("livingvillages_defense_has_route")
            && !data.getBoolean("livingvillages_industry_route_active")
            && !data.getBoolean(VillagerAutonomy.HARVEST_ACTIVE_KEY)
            && !data.getBoolean(VillagerAutonomy.HARVEST_CYCLE_KEY)
            && !data.getBoolean(VillagerAutonomy.SEED_FETCH_ACTIVE_KEY);
    }

    /** Transfers one real item from an idle resident so the specialist can use it. */
    private static boolean transferFromResident(ServerLevel level, BlockPos center, Villager recipient, Item item) {
        if (level == null || center == null || recipient == null || item == null
            || inventoryCapacityFor(recipient.getInventory(), item) < 1) return false;
        for (Villager resident : VillageArea.findVillagers(level, center)) {
            if (resident == recipient || !resident.isAlive() || resident.isBaby()) continue;
            if (!isResidentAvailableForTransfer(resident)) continue;

            ItemStack removed = removeOne(resident.getInventory(), item);
            if (removed.isEmpty()) continue;
            ItemStack remainder = recipient.getInventory().addItem(removed);
            if (!remainder.isEmpty()) {
                ItemStack stillUnreturned = resident.getInventory().addItem(remainder.copy());
                if (!stillUnreturned.isEmpty()) resident.spawnAtLocation(stillUnreturned);
            }
            resident.getInventory().setChanged();
            recipient.getInventory().setChanged();
            return remainder.isEmpty();
        }
        return false;
    }

    private static void transferFromStorage(Villager worker, Container storage, Item item, int amount) {
        if (worker == null || storage == null || amount <= 0) return;
        int removed = removeItem(storage, item, amount);
        if (removed <= 0) return;
        ItemStack remainder = worker.getInventory().addItem(new ItemStack(item, removed));
        if (!remainder.isEmpty()) {
            ItemStack returned = remainder.copy();
            int inserted = insert(storage, returned);
            if (inserted < remainder.getCount()) {
                worker.spawnAtLocation(new ItemStack(item, remainder.getCount() - inserted));
            }
        }
        storage.setChanged();
        worker.getInventory().setChanged();
    }

    private static void finishTask(Villager villager, CompoundTag data, long retryAt) {
        clearStorageRoute(villager, data);
        VillageReservations.release(data, "livestock");
        if (villager.level() instanceof ServerLevel level) {
            requestPenGateClose(level, ownershipCenter(villager), data);
            clearHerdMovement(villager, level, ownershipCenter(villager), data);
        }
        returnWheatToInventory(villager);
        data.putBoolean(ACTIVE_KEY, false);
        data.putInt(STATE, STATE_IDLE);
        data.remove(TARGET_SHEEP);
        data.remove(TASK_STARTED);
        data.remove(HERD_DEADLINE);
        data.putLong(NEXT_ACTION, retryAt);
    }

    private static void recordFailure(CompoundTag data, long now) {
        int failures = data.getInt("livingvillages_livestock_failures") + 1;
        if (failures >= 3) {
            data.putInt("livingvillages_livestock_failures", 0);
            data.putLong(NEXT_ACTION, now + FAILURE_BACKOFF_TICKS);
        } else {
            data.putInt("livingvillages_livestock_failures", failures);
            data.putLong(NEXT_ACTION, now + RETRY_TICKS);
        }
    }

    private static ItemStack removeOne(Container storage, Item item) {
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            if (storage.getItem(slot).is(item)) return storage.removeItem(slot, 1);
        }
        return ItemStack.EMPTY;
    }

    private static int countItem(Container container, Item item) {
        if (container == null || item == null || item == Items.AIR) return 0;
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int removeItem(Container container, Item item, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            container.removeItem(slot, take);
            remaining -= take;
        }
        if (remaining != amount) container.setChanged();
        return amount - remaining;
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

    private static BlockPos routeStorage(CompoundTag data) {
        return new BlockPos(data.getInt(ROUTE_STORAGE_X), data.getInt(ROUTE_STORAGE_Y), data.getInt(ROUTE_STORAGE_Z));
    }

    private static BlockPos routeStand(CompoundTag data) {
        return new BlockPos(data.getInt(ROUTE_STAND_X), data.getInt(ROUTE_STAND_Y), data.getInt(ROUTE_STAND_Z));
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private record StorageRef(BlockPos pos, Container container) {}
    private record StorageTarget(BlockPos storage, BlockPos stand) {}
}
