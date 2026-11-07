package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import com.abs.livingvillages.domain.VillageWorkOrderModel;

/**
 * Living Villages 0.15.0 for Minecraft Java 1.21.1 + NeoForge.
 *
 * Autonomous development uses physical resources and bounded vanilla-like recipes: logs become planks,
 * tools need ingredients, workstations consume building materials, and furnaces use real ore and fuel.
 * Workers carry real items to physical storage; forest restoration only uses genuine saplings. Villagers
 * retain vanilla navigation while the mod adds personalities, work roles, production and defense.
 */
@Mod(LivingVillages.MOD_ID)
public final class LivingVillages {
    public static final String MOD_ID = "livingvillages";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int HOUSE_PLANKS_REQUIRED = 96;
    private static final int FARM_SIZE = 5;
    private static final int FARM_CROP_COUNT = FARM_SIZE * FARM_SIZE - 1;
    private static final int FARM_PRESENT_THRESHOLD = 20;

    private long tickCounter = 0L;

    public LivingVillages(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, LivingVillagesConfig.SPEC);
        modEventBus.addListener(VillageElder::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(VillageElder::onVillagerInteract);
        NeoForge.EVENT_BUS.addListener(VillagerSoul::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillagerSoul::onVillagerInteract);
        NeoForge.EVENT_BUS.addListener(VillagerSoul::onVillagerDamaged);
        NeoForge.EVENT_BUS.addListener(VillagerSoul::onMonsterKilledByPlayer);
        NeoForge.EVENT_BUS.addListener(VillagerSoul::onStorageTamperedByPlayer);
        NeoForge.EVENT_BUS.addListener(VillagerAutonomy::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillagerTreeWork::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillagerMining::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageDefense::onVillagerTick);
        // Patrol lighting must observe the guard's current-tick combat/retreat decision.
        NeoForge.EVENT_BUS.addListener(VillagerCaveSupport::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageIndustry::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageCraftingJobs::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageLogistics::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageBuilder::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageFarmBuilder::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillagePopulation::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageMonsterAI::onEntityTick);
        NeoForge.EVENT_BUS.addListener(VillageAnimalAI::onAnimalTick);
        NeoForge.EVENT_BUS.addListener(VillageAnimalAI::onAnimalDamaged);
        NeoForge.EVENT_BUS.addListener(VillageAnimalAI::onBabySpawn);
        NeoForge.EVENT_BUS.addListener(VillageAnimalAI::onAnimalInteract);
    }

    private void onServerStarting(ServerStartingEvent event) {
        tickCounter = 0L;
    }

    private void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        MinecraftServer server = event.getServer();

        // Specialist assignment is lightweight and runs independently from the slower build planner.
        if (tickCounter % 100L == 0L) {
            // Reconcile disabled/stale roles even when autonomous development is paused.
            assignRolesInActiveAreas(server);
        }
        if (LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get() && tickCounter % 200L == 0L) {
            runIndustryInActiveAreas(server);
        }

        long intervalTicks = LivingVillagesConfig.SCAN_INTERVAL_SECONDS.get() * 20L;
        boolean queuedElderOrder = tickCounter % 20L == 0L && hasPendingElderOrder(server);
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || (!queuedElderOrder && tickCounter % intervalTicks != 0L)) {
            return;
        }


        List<ActiveArea> alreadyChecked = new ArrayList<>();
        for (CandidateCenter candidate : collectCandidateCenters(server)) {
            ServerLevel level = candidate.level();
            BlockPos center = candidate.center();

            List<Villager> villagers = findVillagers(level, center);
            if (isDuplicateArea(alreadyChecked, level, villagers)) {
                continue;
            }
            rememberActiveArea(alreadyChecked, level, villagers);
            if (villagers.size() < LivingVillagesConfig.MINIMUM_VILLAGERS.get()) {
                VillageRegistry.recordDiagnostic(level, center, "Планировщик",
                    "Недостаточно жителей (" + villagers.size() + "/" + LivingVillagesConfig.MINIMUM_VILLAGERS.get() + ")");
                continue;
            }

            BlockPos villageCenter = averageVillagerPosition(villagers, center);
            long tickStartNanos = System.nanoTime();
            VillageRegistry.recordVisitAndRunCatchUp(level, villageCenter, level.getGameTime());
            VillageElder.ensureElder(level, villageCenter, villagers);
            if (LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()) {
                VillageBuilder.tryResumeOrphanedHouseProject(level, villageCenter);
            }
            if (LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) {
                VillageFarmBuilder.tryResumeOrphanedFarmProject(level, villageCenter);
            }
            VillageTickBudget.recordSubsystemNanos(level, System.nanoTime() - tickStartNanos);

            // Do not let a second project spend ingredients while a worker carries a reserved order.
            if (villagers.stream().anyMatch(v -> v.getPersistentData().getBoolean(VillageCraftingJobs.ACTIVE_KEY)
                || (LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get() && VillageBuilder.isProjectActive(v))
                || (LivingVillagesConfig.ENABLE_FARM_PROJECTS.get() && VillageFarmBuilder.isProjectActive(v)))) {
                VillageRegistry.recordDiagnostic(level, villageCenter, "Планировщик", "Ожидание завершения текущего проекта/ремесла");
                continue;
            }

            int request = VillageElder.pendingRequest(villagers);
            if (request == VillageElder.REQUEST_HOUSE && LivingVillagesConfig.ENABLE_HOUSING_PROJECTS.get()) {
                BuildResult result = tryBuild(level, villageCenter, true);
                if (result.success()) {
                    VillageElder.completeRequest(villagers, request, "Строительство дома началось.");
                    LOGGER.info("Village elder ordered a house at {}.", result.position());
                    continue;
                }
            } else if (request == VillageElder.REQUEST_FARM && LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) {
                BuildResult result = tryBuildFarm(level, villageCenter);
                if (result.success()) {
                    VillageElder.completeRequest(villagers, request, "Строительство фермы началось.");
                    LOGGER.info("Village elder ordered a farm at {}.", result.position());
                    continue;
                }
            }

            // Local elder control allows one village to pause or prioritize differently from others.
            if (!VillageElder.isAutomationEnabled(villagers)) {
                VillageRegistry.recordDiagnostic(level, villageCenter, "Планировщик", "Авторазвитие приостановлено старостой");
                continue;
            }

            int priority = VillageElder.priorityForVillagers(villagers);
            int beds = countBeds(level, villageCenter);
            boolean housingNeeded = villagers.size() >= beds;
            boolean foodProductionNeeded = !hasEnoughFarmland(level, villageCenter, villagers.size());

            if (priority == VillageElder.PRIORITY_FOOD && foodProductionNeeded
                && LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) {
                BuildResult result = tryBuildFarm(level, villageCenter);
                if (result.success()) {
                    LOGGER.info("Living Villages assigned a worker to create a farm at {}.", result.position());
                    continue;
                }
            }

            if (housingNeeded && LivingVillagesConfig.ENABLE_HOUSING_PROJECTS.get()) {
                BuildResult result = tryBuild(level, villageCenter);
                if (result.success()) {
                    LOGGER.info("Living Villages assigned a builder to construct a house at {}.", result.position());
                    continue;
                }
                if (priority == VillageElder.PRIORITY_HOUSING) continue;
            }

            if (foodProductionNeeded && LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) {
                BuildResult result = tryBuildFarm(level, villageCenter);
                if (result.success()) {
                    LOGGER.info("Living Villages created a farm at {}.", result.position());
                    continue;
                }
            }
        }
    }

    private void assignRolesInActiveAreas(MinecraftServer server) {
        List<ActiveArea> alreadyChecked = new ArrayList<>();
        for (CandidateCenter candidate : collectCandidateCenters(server)) {
            ServerLevel level = candidate.level();
            BlockPos center = candidate.center();
            List<Villager> villagers = findVillagers(level, center);
            if (villagers.isEmpty() || isDuplicateArea(alreadyChecked, level, villagers)) continue;
            rememberActiveArea(alreadyChecked, level, villagers);
            BlockPos villageCenter = averageVillagerPosition(villagers, center);
            VillageRegistry.cleanupInvalidAssets(level, villageCenter, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
            VillageElder.ensureElder(level, villageCenter, villagers);
            if (VillageElder.isAutomationEnabled(villagers) || VillageElder.hasPendingRequest(villagers)) {
                VillagerRoles.assignRoles(level, villageCenter);
            } else {
                // Paused villages keep existing assignments but do not create new jobs.
                VillagerRoles.reconcileRoles(level, villageCenter, true);
            }
        }
    }

    private void runIndustryInActiveAreas(MinecraftServer server) {
        List<ActiveArea> alreadyChecked = new ArrayList<>();
        for (CandidateCenter candidate : collectCandidateCenters(server)) {
            ServerLevel level = candidate.level();
            BlockPos playerPos = candidate.center();
            List<Villager> villagers = findVillagers(level, playerPos);
            if (villagers.isEmpty() || isDuplicateArea(alreadyChecked, level, villagers)) continue;
            rememberActiveArea(alreadyChecked, level, villagers);
            BlockPos villageCenter = averageVillagerPosition(villagers, playerPos);
            VillageElder.ensureElder(level, villageCenter, villagers);
            if (!VillageElder.isAutomationEnabled(villagers) && !VillageElder.hasPendingRequest(villagers)) continue;
            VillagePopulation.tickArea(level, villageCenter, villagers);
            // Industry scans only unreserved containers, so it can continue with separate stock.
            VillageIndustry.tickArea(level, villageCenter);
            VillageRegistry.tryTradeWithKnownNeighbors(level, villageCenter, 4, 8);
        }
    }

    private boolean hasPendingElderOrder(MinecraftServer server) {
        List<ActiveArea> checked = new ArrayList<>();
        for (CandidateCenter candidate : collectCandidateCenters(server)) {
            ServerLevel level = candidate.level();
            BlockPos pos = candidate.center();
            List<Villager> villagers = findVillagers(level, pos);
            if (villagers.isEmpty() || isDuplicateArea(checked, level, villagers)) continue;
            rememberActiveArea(checked, level, villagers);
            int request = VillageElder.pendingRequest(villagers);
            if (request == VillageElder.REQUEST_HOUSE && LivingVillagesConfig.ENABLE_HOUSING_PROJECTS.get()
                && LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()) return true;
            if (request == VillageElder.REQUEST_FARM && LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) return true;
        }
        return false;
    }

    private List<CandidateCenter> collectCandidateCenters(MinecraftServer server) {
        List<CandidateCenter> candidates = new ArrayList<>();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return candidates;
        }
        for (ServerPlayer player : players) {
            candidates.add(new CandidateCenter(player.serverLevel(), player.blockPosition()));
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (BlockPos anchor : VillageRegistry.knownLoadedVillageAnchors(level)) {
                candidates.add(new CandidateCenter(level, anchor));
            }
        }
        return candidates;
    }

    /**
     * Evaluates a single loaded settlement area (roles, industry, population, and house/farm planner).
     */
    public void tickSettlementArea(ServerLevel level, BlockPos center) {
        if (level == null || center == null || !level.hasChunkAt(center)) return;
        List<Villager> villagers = findVillagers(level, center);
        if (villagers.isEmpty()) return;
        BlockPos villageCenter = averageVillagerPosition(villagers, center);
        VillageRegistry.cleanupInvalidAssets(level, villageCenter, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        VillageElder.ensureElder(level, villageCenter, villagers);
        if (VillageElder.isAutomationEnabled(villagers) || VillageElder.hasPendingRequest(villagers)) {
            VillagerRoles.assignRoles(level, villageCenter);
            VillagePopulation.tickArea(level, villageCenter, villagers);
            VillageIndustry.tickArea(level, villageCenter);
        }
        if (villagers.size() < LivingVillagesConfig.MINIMUM_VILLAGERS.get()) return;
        if (villagers.stream().anyMatch(v -> v.getPersistentData().getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || (LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get() && VillageBuilder.isProjectActive(v))
            || (LivingVillagesConfig.ENABLE_FARM_PROJECTS.get() && VillageFarmBuilder.isProjectActive(v)))) {
            return;
        }
        int beds = countBeds(level, villageCenter);
        if (villagers.size() >= beds && LivingVillagesConfig.ENABLE_HOUSING_PROJECTS.get()) {
            BuildResult house = tryBuild(level, villageCenter);
            if (house.success()) return;
        }
        if (!hasEnoughFarmland(level, villageCenter, villagers.size())
            && LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) {
            tryBuildFarm(level, villageCenter);
        }
    }

    private BuildResult tryBuild(ServerLevel level, BlockPos center) {
        return tryBuild(level, center, false);
    }

    private BuildResult tryBuild(ServerLevel level, BlockPos center, boolean elderOrdered) {
        List<Villager> villagers = findVillagers(level, center);
        if (villagers.size() < LivingVillagesConfig.MINIMUM_VILLAGERS.get()) {
            String msg = "Недостаточно жителей для запуска плана.";
            VillageRegistry.recordDiagnostic(level, center, "Дом", msg);
            VillageWorkOrders.markPendingProjectOpen(level, center, "house", level.getGameTime());
            return BuildResult.failure(msg);
        }
        int beds = countBeds(level, center);
        if (!elderOrdered && villagers.size() < beds) {
            String msg = "В поселении ещё есть свободная потенциальная кровать.";
            VillageRegistry.recordDiagnostic(level, center, "Дом", msg);
            VillageWorkOrders.markPendingProjectOpen(level, center, "house", level.getGameTime());
            return BuildResult.failure(msg);
        }

        List<Container> containers = findResourceContainers(level, center);
        if (containers.isEmpty()) {
            String msg = "Нет хранилища (сундука/бочки) в радиусе поселения.";
            VillageRegistry.recordDiagnostic(level, center, "Дом", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "house", level.getGameTime());
            return BuildResult.failure(msg);
        }
        Map<Block, Integer> plankCounts = countPlanks(containers);
        VillageWorkOrderModel.Order pendingHouse = findPendingProjectOrder(level, center, "house");
        Item pinnedPlankItem = pendingRequirementItem(pendingHouse,
            item -> VillageBuilder.isSupportedStructuralBlock(Block.byItem(item)));
        Block plankBlock = pinnedPlankItem != Items.AIR
            ? Block.byItem(pinnedPlankItem)
            : plankCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0 && VillageBuilder.isSupportedStructuralBlock(entry.getKey()))
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (plankBlock == null || plankBlock.asItem() == Items.AIR) {
            String msg = "Не найден строительный материал для проекта дома. Нужны доски, песчаник, булыжник или каменный кирпич.";
            VillageRegistry.recordDiagnostic(level, center, "Дом", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "house", level.getGameTime());
            return BuildResult.failure(msg);
        }

        Item bedItem = pendingRequirementItem(pendingHouse, VillageBuilder::isSupportedBedItem);
        if (bedItem == Items.AIR) bedItem = VillageBuilder.selectAvailableBedItem(containers);
        if (bedItem == Items.AIR) bedItem = Items.RED_BED;

        Item doorItem = pendingRequirementItem(pendingHouse, item -> Block.byItem(item) instanceof DoorBlock);
        if (doorItem == Items.AIR) doorItem = VillageBuilder.selectAvailableDoorItem(plankBlock, containers);
        if (doorItem == Items.AIR) doorItem = Items.OAK_DOOR;

        long now = level.getGameTime();
        VillageWorkOrderModel.Order recorded = VillageWorkOrders.recordPendingProjectRequirements(
            level, center, "house", 55, now, List.of(
                new VillageWorkOrderModel.Requirement(BuiltInRegistries.ITEM.getKey(plankBlock.asItem()).toString(), HOUSE_PLANKS_REQUIRED),
                new VillageWorkOrderModel.Requirement(BuiltInRegistries.ITEM.getKey(bedItem).toString(), 1),
                new VillageWorkOrderModel.Requirement(BuiltInRegistries.ITEM.getKey(doorItem).toString(), 1)
            )
        );
        if (recorded == null) {
            String msg = "Не удалось сохранить потребность материалов для дома в очереди поселения.";
            VillageRegistry.recordDiagnostic(level, center, "Дом", msg);
            return BuildResult.failure(msg);
        }

        if (plankCounts.getOrDefault(plankBlock, 0) < HOUSE_PLANKS_REQUIRED) {
            String msg = "Не хватает строительных блоков одного вида (всего нужно 96). Курьеры будут искать недостающие предметы.";
            VillageRegistry.recordDiagnostic(level, center, "Дом", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "house", now);
            return BuildResult.failure(msg);
        }
        if (countItem(containers, bedItem) < 1) {
            String msg = "Не найдена кровать выбранного типа. Заявка сохранена; нужен настоящий предмет кровати.";
            VillageRegistry.recordDiagnostic(level, center, "Дом", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "house", now);
            return BuildResult.failure(msg);
        }
        if (countItem(containers, doorItem) < 1) {
            String msg = "Не найдена подходящая дверь. Заявка сохранена; ремесленник может изготовить дверь из досок.";
            VillageRegistry.recordDiagnostic(level, center, "Дом", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "house", now);
            return BuildResult.failure(msg);
        }

        BlockPos site = findBuildSite(level, center);
        if (site == null) {
            String msg = "Не найдено ровное свободное место в радиусе 28 блоков.";
            VillageRegistry.recordDiagnostic(level, center, "Дом", msg);
            VillageWorkOrders.markPendingProjectOpen(level, center, "house", level.getGameTime());
            return BuildResult.failure(msg);
        }

        // Do not consume anything here. The builder must physically walk to storage,
        // collect the already-existing items, and place each block at the site over time.
        if (!VillageBuilder.startHouseProject(level, center, site, plankBlock, doorItem, bedItem)) {
            String msg = "Строитель занят, не назначен или не может вместить материалы в инвентарь.";
            VillageRegistry.recordDiagnostic(level, center, "Дом", msg);
            VillageWorkOrders.markPendingProjectOpen(level, center, "house", level.getGameTime());
            return BuildResult.failure(msg);
        }
        String ok = "Строитель начал собирать материалы и возводить хижину по блокам.";
        VillageRegistry.recordDiagnostic(level, center, "Дом", ok);
        return BuildResult.projectStarted(site, ok);
    }

    private BuildResult tryBuildFarm(ServerLevel level, BlockPos center) {
        long now = level.getGameTime();
        List<Container> containers = findResourceContainers(level, center);
        VillageWorkOrderModel.Order pendingFarm = findPendingProjectOrder(level, center, "farm");

        Item pinnedSeed = pendingRequirementItem(pendingFarm, this::isSupportedFarmSeed);
        CropPlan cropPlan = pinnedSeed != Items.AIR
            ? cropPlanForSeed(pinnedSeed, containers)
            : chooseCropPlan(containers);
        Item hoeItem = pendingRequirementItem(pendingFarm, VillageFarmBuilder::isHoeItem);
        if (hoeItem == Items.AIR) hoeItem = selectAvailableHoeItem(containers);
        Item pinnedWaterItem = pendingRequirementItem(pendingFarm,
            item -> item == Items.BUCKET || item == Items.WATER_BUCKET);
        Item waterItem = pinnedWaterItem == Items.AIR
            ? VillageFarmBuilder.selectWaterSupplyItem(containers) : pinnedWaterItem;
        if (hoeItem == Items.AIR) hoeItem = Items.WOODEN_HOE;

        VillageWorkOrderModel.Order recorded = VillageWorkOrders.recordPendingProjectRequirements(
            level, center, "farm", 60, now, List.of(
                new VillageWorkOrderModel.Requirement(BuiltInRegistries.ITEM.getKey(cropPlan.seedItem()).toString(), FARM_CROP_COUNT),
                new VillageWorkOrderModel.Requirement(BuiltInRegistries.ITEM.getKey(waterItem).toString(), 1),
                new VillageWorkOrderModel.Requirement(BuiltInRegistries.ITEM.getKey(Items.COMPOSTER).toString(), 1),
                new VillageWorkOrderModel.Requirement(BuiltInRegistries.ITEM.getKey(hoeItem).toString(), 1)
            )
        );
        if (recorded == null) {
            String msg = "Не удалось сохранить потребность материалов для фермы в очереди поселения.";
            VillageRegistry.recordDiagnostic(level, center, "Ферма", msg);
            return BuildResult.failure(msg);
        }

        if (containers.isEmpty()) {
            String msg = "Нет доступного хранилища для ресурсов фермы. Заявка сохранена.";
            VillageRegistry.recordDiagnostic(level, center, "Ферма", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "farm", now);
            return BuildResult.failure(msg);
        }
        if (cropPlan.available() < FARM_CROP_COUNT) {
            String msg = "Для фермы нужно 24 одинаковых семени. Заявка закрепила выбранную культуру; курьеры ищут недостающие предметы.";
            VillageRegistry.recordDiagnostic(level, center, "Ферма", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "farm", now);
            return BuildResult.failure(msg);
        }
        if (waterItem == Items.BUCKET && !VillageFarmBuilder.hasRenewableWaterSource(level, center)) {
            String msg = "Для наполнения пустого ведра не найден безопасный возобновляемый источник воды в загруженной области поселения.";
            VillageRegistry.recordDiagnostic(level, center, "Ферма", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "farm", now);
            return BuildResult.failure(msg);
        }
        if (countItem(containers, waterItem) < 1) {
            String msg = waterItem == Items.BUCKET
                ? "Для фермы нужно настоящее пустое ведро. Ремесленник может изготовить его из трёх железных слитков, если рядом есть источник воды."
                : "Для фермы нужно настоящее ведро воды в ближайшем хранилище.";
            VillageRegistry.recordDiagnostic(level, center, "Ферма", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "farm", now);
            return BuildResult.failure(msg);
        }
        if (countItem(containers, Items.COMPOSTER) < 1) {
            String msg = "Для фермы нужен настоящий компостер в ближайшем хранилище.";
            VillageRegistry.recordDiagnostic(level, center, "Ферма", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "farm", now);
            return BuildResult.failure(msg);
        }
        if (countItem(containers, hoeItem) < 1) {
            String msg = "Для фермы нужна закреплённая в заявке настоящая мотыга.";
            VillageRegistry.recordDiagnostic(level, center, "Ферма", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "farm", now);
            return BuildResult.failure(msg);
        }
        if (!canReturnEmptyBucket(containers)) {
            String msg = "Нет свободного места в хранилищах, чтобы вернуть пустое ведро.";
            VillageRegistry.recordDiagnostic(level, center, "Ферма", msg);
            VillageWorkOrders.markPendingProjectWaiting(level, center, "farm", now);
            return BuildResult.failure(msg);
        }

        BlockPos site = findFarmSite(level, center);
        if (site == null) {
            String msg = "Не найден ровный участок 5×5 в радиусе 28 блоков.";
            VillageRegistry.recordDiagnostic(level, center, "Ферма", msg);
            VillageWorkOrders.markPendingProjectOpen(level, center, "farm", now);
            return BuildResult.failure(msg);
        }
        if (!VillageFarmBuilder.startFarmProject(level, center, site, cropPlan.seedItem(), cropPlan.cropBlock())) {
            String msg = "Не найден свободный работник с местом в инвентаре для материалов фермы.";
            VillageRegistry.recordDiagnostic(level, center, "Ферма", msg);
            VillageWorkOrders.markPendingProjectOpen(level, center, "farm", now);
            return BuildResult.failure(msg);
        }
        String ok = waterItem == Items.BUCKET
            ? "Рабочий собирает реальные материалы, наполнит пустое ведро у безопасного источника воды и построит ферму поэтапно."
            : "Рабочий начал собирать настоящие семена, воду, компостер и мотыгу. Ферма будет строиться поэтапно.";
        VillageRegistry.recordDiagnostic(level, center, "Ферма", ok);
        return BuildResult.projectStarted(site, ok);
    }

    private boolean isSupportedFarmSeed(Item item) {
        return item == Items.WHEAT_SEEDS || item == Items.CARROT
            || item == Items.POTATO || item == Items.BEETROOT_SEEDS;
    }

    private CropPlan cropPlanForSeed(Item seed, List<Container> containers) {
        if (seed == Items.WHEAT_SEEDS) return new CropPlan(seed, Blocks.WHEAT, countItem(containers, seed));
        if (seed == Items.CARROT) return new CropPlan(seed, Blocks.CARROTS, countItem(containers, seed));
        if (seed == Items.POTATO) return new CropPlan(seed, Blocks.POTATOES, countItem(containers, seed));
        if (seed == Items.BEETROOT_SEEDS) return new CropPlan(seed, Blocks.BEETROOTS, countItem(containers, seed));
        return new CropPlan(Items.WHEAT_SEEDS, Blocks.WHEAT, 0);
    }

    private Item selectAvailableHoeItem(List<Container> containers) {
        if (containers == null) return Items.AIR;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && VillageFarmBuilder.isHoeItem(stack.getItem())) return stack.getItem();
            }
        }
        return Items.AIR;
    }

    private CropPlan chooseCropPlan(List<Container> containers) {
        return List.of(
                new CropPlan(Items.WHEAT_SEEDS, Blocks.WHEAT, countItem(containers, Items.WHEAT_SEEDS)),
                new CropPlan(Items.CARROT, Blocks.CARROTS, countItem(containers, Items.CARROT)),
                new CropPlan(Items.POTATO, Blocks.POTATOES, countItem(containers, Items.POTATO)),
                new CropPlan(Items.BEETROOT_SEEDS, Blocks.BEETROOTS, countItem(containers, Items.BEETROOT_SEEDS))
            ).stream()
            .max((first, second) -> Integer.compare(first.available(), second.available()))
            .orElse(new CropPlan(Items.WHEAT_SEEDS, Blocks.WHEAT, 0));
    }

    /**
     * A fixed-size farm is enough for a tiny village, but growing settlements need
     * additional fields. Four tilled blocks per villager is a conservative target;
     * the planner adds a field only when total farmland drops below that target.
     */
    private boolean hasEnoughFarmland(ServerLevel level, BlockPos center, int population) {
        int requiredFarmland = Math.max(FARM_PRESENT_THRESHOLD, population * 4);
        return countFarmland(level, center) >= requiredFarmland;
    }

    private int countFarmland(ServerLevel level, BlockPos center) {
        int count = 0;
        int radius = LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get();
        BlockPos min = center.offset(-radius, -8, -radius);
        BlockPos max = center.offset(radius, 8, radius);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            // Never force-load distant chunks just to count farmland for an active village.
            if (!level.hasChunkAt(pos)) continue;
            if (level.getBlockState(pos).is(Blocks.FARMLAND)) {
                count++;
            }
        }
        return count;
    }

    private BlockPos findFarmSite(ServerLevel level, BlockPos center) {
        for (int radius = 4; radius <= 28; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    // Heightmap queries may access chunk data; skip columns that are not loaded.
                    if (!level.hasChunkAt(new BlockPos(x, center.getY(), z))) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos base = new BlockPos(x, y, z);
                    if (isSuitableFarmSite(level, base)
                        && VillageReservations.isProjectSiteAvailable(level, base, 8, null)) {
                        return base;
                    }
                }
            }
        }
        return null;
    }

    private boolean isSuitableFarmSite(ServerLevel level, BlockPos base) {
        int baseY = base.getY();
        // Keep the 5x5 field flat, and reserve one extra block behind it for the composter.
        for (int dx = 0; dx < FARM_SIZE; dx++) {
            for (int dz = 0; dz < FARM_SIZE; dz++) {
                int x = base.getX() + dx;
                int z = base.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, baseY, z))) return false;
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) != baseY) {
                    return false;
                }
                BlockPos groundPos = new BlockPos(x, baseY - 1, z);
                if (!isFarmableGround(level.getBlockState(groundPos))
                    || !VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(new BlockPos(x, baseY, z)))
                    || !VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(new BlockPos(x, baseY + 1, z)))) {
                    return false;
                }
            }
        }
        int composterX = base.getX() + 2;
        int composterZ = base.getZ() - 1;
        if (!level.hasChunkAt(new BlockPos(composterX, baseY, composterZ))) return false;
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, composterX, composterZ) != baseY) {
            return false;
        }
        BlockPos composterGround = new BlockPos(composterX, baseY - 1, composterZ);
        return isFarmableGround(level.getBlockState(composterGround))
            && VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(new BlockPos(composterX, baseY, composterZ)));
    }

    private boolean isFarmableGround(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
            || state.is(Blocks.DIRT)
            || state.is(Blocks.COARSE_DIRT)
            || state.is(Blocks.ROOTED_DIRT)
            || state.is(Blocks.PODZOL)
            || state.is(Blocks.MYCELIUM)
            || state.is(Blocks.DIRT_PATH)
            || state.is(Blocks.FARMLAND);
    }

    private boolean canReturnEmptyBucket(List<Container> containers) {
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(Items.WATER_BUCKET) && stack.getCount() == 1) {
                    return true; // This slot becomes the empty bucket's slot.
                }
            }
        }
        ItemStack emptyBucket = new ItemStack(Items.BUCKET);
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() && container.canPlaceItem(slot, emptyBucket)) {
                    return true;
                }
                if (stack.is(Items.BUCKET) && stack.getCount() < stack.getMaxStackSize()
                    && container.canPlaceItem(slot, emptyBucket)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Villager> findVillagers(ServerLevel level, BlockPos center) {
        return VillageArea.findVillagers(level, center);
    }

    private BlockPos averageVillagerPosition(List<Villager> villagers, BlockPos fallback) {
        if (villagers.isEmpty()) {
            return fallback;
        }
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (Villager villager : villagers) {
            x += villager.getX();
            y += villager.getY();
            z += villager.getZ();
        }
        double count = villagers.size();
        return BlockPos.containing(x / count, y / count, z / count);
    }

    private int countBeds(ServerLevel level, BlockPos center) {
        int count = 0;
        int radius = LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get();
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minY = center.getY() - 8;
        int maxY = center.getY() + 8;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;

        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.FOOT
                || !VillageRegistry.isBedForVillage(level, center, pos)) continue;
            BlockPos head = pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
            if (!level.hasChunkAt(head) || !level.getBlockState(pos.above()).isAir()
                || !level.getBlockState(pos.above(2)).isAir()
                || !level.getBlockState(head.above()).isAir() || !level.getBlockState(head.above(2)).isAir()) continue;
            count++;
        }
        return count;
    }

    private List<Container> findResourceContainers(ServerLevel level, BlockPos center) {
        List<Container> containers = new ArrayList<>();
        int radius = LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get();
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minY = center.getY() - 8;
        int maxY = center.getY() + 8;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;

        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST) && !state.is(Blocks.BARREL)) {
                continue;
            }
            if (!VillageRegistry.isStorageForVillage(level, center, pos)
                || !VillageReservations.isStorageAvailable(level, pos, null)) continue;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                containers.add(container);
            }
        }
        return containers;
    }

    private Map<Block, Integer> countPlanks(List<Container> containers) {
        Map<Block, Integer> counts = new HashMap<>();
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                Block block = Block.byItem(stack.getItem());
                if (VillageBuilder.isSupportedStructuralBlock(block)) {
                    counts.merge(block, stack.getCount(), Integer::sum);
                }
            }
        }
        return counts;
    }

    private VillageWorkOrderModel.Order findPendingProjectOrder(ServerLevel level, BlockPos center, String kind) {
        return VillageWorkOrders.list(level, center, level.getGameTime()).stream()
            .filter(order -> order.kind().equals(kind) && order.dedupeKey().equals(kind)
                && (order.state() == VillageWorkOrderModel.OPEN
                    || order.state() == VillageWorkOrderModel.WAITING_FOR_RESOURCES))
            .findFirst()
            .orElse(null);
    }

    private Item pendingRequirementItem(VillageWorkOrderModel.Order order, Predicate<Item> predicate) {
        if (order == null || predicate == null) return Items.AIR;
        for (VillageWorkOrderModel.Requirement requirement : order.requirements()) {
            ResourceLocation id = ResourceLocation.tryParse(requirement.itemId());
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item != Items.AIR && predicate.test(item)) return item;
        }
        return Items.AIR;
    }

    private int countItem(List<Container> containers, net.minecraft.world.item.Item item) {
        int count = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(item)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    private BlockPos findBuildSite(ServerLevel level, BlockPos center) {
        for (int radius = 4; radius <= 28; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    // Do not load a new chunk just to probe a possible house location.
                    if (!level.hasChunkAt(new BlockPos(x, center.getY(), z))) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos base = new BlockPos(x, y, z);
                    if (isSuitableSite(level, base)
                        && VillageReservations.isProjectSiteAvailable(level, base, 8, null)) {
                        return base;
                    }
                }
            }
        }
        return null;
    }

    private boolean isSuitableSite(ServerLevel level, BlockPos base) {
        int baseY = base.getY();
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                int x = base.getX() + dx;
                int z = base.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, baseY, z))) return false;
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (surfaceY != baseY) {
                    return false;
                }

                BlockPos groundPos = new BlockPos(x, baseY - 1, z);
                BlockState ground = level.getBlockState(groundPos);
                if ((!ground.isSolidRender(level, groundPos)
                        && !ground.is(Blocks.DIRT_PATH)
                        && !ground.is(Blocks.FARMLAND))
                    || !ground.getFluidState().isEmpty()) {
                    return false;
                }

                for (int dy = 0; dy <= 4; dy++) {
                    if (!VillageBuilder.isAirOrReplaceableVegetation(level.getBlockState(new BlockPos(x, baseY + dy, z)))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isDuplicateArea(List<ActiveArea> checked, ServerLevel level, List<Villager> villagers) {
        if (villagers.isEmpty()) return false;
        return checked.stream().anyMatch(previous -> previous.level() == level && previous.overlaps(villagers));
    }

    private static void rememberActiveArea(List<ActiveArea> checked, ServerLevel level, List<Villager> villagers) {
        if (villagers.isEmpty()) return;
        checked.add(new ActiveArea(level, villagers.stream().map(v -> v.getUUID().toString()).toList()));
    }

    private record ActiveArea(ServerLevel level, List<String> residentIds) {
        private boolean overlaps(List<Villager> villagers) {
            return villagers.stream().anyMatch(v -> residentIds.contains(v.getUUID().toString()));
        }
    }

    private record CandidateCenter(ServerLevel level, BlockPos center) {
    }

    private record CropPlan(net.minecraft.world.item.Item seedItem, Block cropBlock, int available) {
    }

    private record BuildResult(boolean success, String message, BlockPos position) {
        static BuildResult failure(String message) {
            return new BuildResult(false, message, null);
        }

        static BuildResult projectStarted(BlockPos position, String message) {
            return new BuildResult(true, message, position.immutable());
        }

    }
}
