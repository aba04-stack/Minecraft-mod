package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
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
        NeoForge.EVENT_BUS.addListener(VillagerAutonomy::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillagerTreeWork::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillagerMining::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageDefense::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageCraftingJobs::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageLogistics::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageBuilder::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillageFarmBuilder::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(VillagePopulation::onVillagerTick);
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
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            BlockPos center = player.blockPosition();

            List<Villager> villagers = findVillagers(level, center);
            if (isDuplicateArea(alreadyChecked, level, villagers)) {
                continue;
            }
            rememberActiveArea(alreadyChecked, level, villagers);
            if (villagers.size() < LivingVillagesConfig.MINIMUM_VILLAGERS.get()) {
                continue;
            }

            BlockPos villageCenter = averageVillagerPosition(villagers, center);
            VillageElder.ensureElder(level, villageCenter, villagers);

            // Do not let a second project spend ingredients while a worker carries a reserved order.
            if (villagers.stream().anyMatch(v -> v.getPersistentData().getBoolean(VillageCraftingJobs.ACTIVE_KEY)
                || (LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get() && VillageBuilder.isProjectActive(v))
                || (LivingVillagesConfig.ENABLE_FARM_PROJECTS.get() && VillageFarmBuilder.isProjectActive(v)))) {
                continue;
            }

            int request = VillageElder.pendingRequest(villagers);
            if (request == VillageElder.REQUEST_HOUSE && LivingVillagesConfig.ENABLE_HOUSING_PROJECTS.get()) {
                BuildResult result = tryBuild(level, villageCenter, true);
                if (result.success()) {
                    VillageElder.completeRequest(villagers, request, "Строительство дома началось.");
                    LOGGER.info("Village elder ordered a house at {}.", result.position());
                    break;
                }
            } else if (request == VillageElder.REQUEST_FARM && LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) {
                BuildResult result = tryBuildFarm(level, villageCenter);
                if (result.success()) {
                    VillageElder.completeRequest(villagers, request, "Строительство фермы началось.");
                    LOGGER.info("Village elder ordered a farm at {}.", result.position());
                    break;
                }
            }

            // Local elder control allows one village to pause or prioritize differently from others.
            if (!VillageElder.isAutomationEnabled(villagers)) {
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
                    break;
                }
            }

            if (housingNeeded && LivingVillagesConfig.ENABLE_HOUSING_PROJECTS.get()) {
                BuildResult result = tryBuild(level, villageCenter);
                if (result.success()) {
                    LOGGER.info("Living Villages assigned a builder to construct a house at {}.", result.position());
                    break;
                }
                // Housing remains the default priority unless the elder explicitly chose food.
                if (priority != VillageElder.PRIORITY_FOOD) continue;
            }

            if (foodProductionNeeded && LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) {
                BuildResult result = tryBuildFarm(level, villageCenter);
                if (result.success()) {
                    LOGGER.info("Living Villages created a farm at {}.", result.position());
                    break;
                }
            }
        }
    }

    private void assignRolesInActiveAreas(MinecraftServer server) {
        List<ActiveArea> alreadyChecked = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            BlockPos center = player.blockPosition();
            List<Villager> villagers = findVillagers(level, center);
            if (villagers.isEmpty() || isDuplicateArea(alreadyChecked, level, villagers)) continue;
            rememberActiveArea(alreadyChecked, level, villagers);
            BlockPos villageCenter = averageVillagerPosition(villagers, center);
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
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            BlockPos playerPos = player.blockPosition();
            List<Villager> villagers = findVillagers(level, playerPos);
            if (villagers.isEmpty() || isDuplicateArea(alreadyChecked, level, villagers)) continue;
            rememberActiveArea(alreadyChecked, level, villagers);
            BlockPos villageCenter = averageVillagerPosition(villagers, playerPos);
            VillageElder.ensureElder(level, villageCenter, villagers);
            if (!VillageElder.isAutomationEnabled(villagers) && !VillageElder.hasPendingRequest(villagers)) continue;
            VillagePopulation.tickArea(level, villageCenter, villagers);
            if (villagers.stream().anyMatch(v -> (LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get() && VillageBuilder.isProjectActive(v))
                || (LivingVillagesConfig.ENABLE_FARM_PROJECTS.get() && VillageFarmBuilder.isProjectActive(v)))) continue;
            VillageIndustry.tickArea(level, villageCenter);
        }
    }

    private boolean hasPendingElderOrder(MinecraftServer server) {
        List<ActiveArea> checked = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            BlockPos pos = player.blockPosition();
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

    private BuildResult tryBuild(ServerLevel level, BlockPos center) {
        return tryBuild(level, center, false);
    }

    private BuildResult tryBuild(ServerLevel level, BlockPos center, boolean elderOrdered) {
        List<Villager> villagers = findVillagers(level, center);
        if (villagers.size() < LivingVillagesConfig.MINIMUM_VILLAGERS.get()) {
            return BuildResult.failure("Недостаточно жителей для запуска плана.");
        }
        int beds = countBeds(level, center);
        if (!elderOrdered && villagers.size() < beds) {
            return BuildResult.failure("В поселении ещё есть свободная потенциальная кровать.");
        }

        List<Container> containers = findResourceContainers(level, center);
        Map<Block, Integer> plankCounts = countPlanks(containers);
        Block plankBlock = plankCounts.entrySet().stream()
            .filter(entry -> entry.getValue() >= HOUSE_PLANKS_REQUIRED)
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        if (plankBlock == null) {
            return BuildResult.failure("Не хватает материалов: нужно 96 досок одного вида в ближайших сундуках.");
        }
        if (countItem(containers, Items.RED_BED) < 1) {
            return BuildResult.failure("Не найдена красная кровать в сундуках поблизости.");
        }
        net.minecraft.world.item.Item doorItem = VillageBuilder.doorItemForPlanks(plankBlock);
        if (doorItem == Items.AIR) {
            return BuildResult.failure("Для выбранных досок не найден подходящий настоящий дверной блок.");
        }
        if (countItem(containers, doorItem) < 1) {
            return BuildResult.failure("Для закрытого дома нужна дверь из того же вида древесины. Ремесленник должен изготовить её из 6 досок.");
        }

        BlockPos site = findBuildSite(level, center);
        if (site == null) {
            return BuildResult.failure("Не найдено ровное свободное место в радиусе 20 блоков.");
        }

        // Do not consume anything here. The builder must physically walk to storage,
        // collect the already-existing items, and place each block at the site over time.
        if (!VillageBuilder.startHouseProject(level, center, site, plankBlock, doorItem)) {
            return BuildResult.failure("Строитель занят, не назначен или не может вместить материалы в инвентарь.");
        }
        return BuildResult.projectStarted(site, "Строитель начал собирать материалы и возводить хижину по блокам.");
    }

    private BuildResult tryBuildFarm(ServerLevel level, BlockPos center) {
        List<Container> containers = findResourceContainers(level, center);
        CropPlan cropPlan = chooseCropPlan(containers);
        if (cropPlan == null) {
            return BuildResult.failure("Для фермы нужны 24 одинаковых семени пшеницы, морковки, картофелины или семени свёклы в настоящих хранилищах.");
        }
        if (countItem(containers, Items.WATER_BUCKET) < 1) {
            return BuildResult.failure("Для фермы нужно настоящее ведро воды в ближайшем хранилище.");
        }
        if (countItem(containers, Items.COMPOSTER) < 1) {
            return BuildResult.failure("Для фермы нужен настоящий компостер в ближайшем хранилище.");
        }
        if (!VillageFarmBuilder.hasHoe(containers)) {
            return BuildResult.failure("Для подготовки поля нужна настоящая мотыга в хранилище.");
        }
        if (!canReturnEmptyBucket(containers)) {
            return BuildResult.failure("Нет свободного места в хранилищах, чтобы вернуть пустое ведро.");
        }

        BlockPos site = findFarmSite(level, center);
        if (site == null) {
            return BuildResult.failure("Не найден ровный участок 5×5 в радиусе 20 блоков.");
        }
        if (!VillageFarmBuilder.startFarmProject(level, center, site, cropPlan.seedItem(), cropPlan.cropBlock())) {
            return BuildResult.failure("Не найден свободный работник с местом в инвентаре для материалов фермы.");
        }
        return BuildResult.projectStarted(site, "Рабочий начал собирать настоящие семена, воду, компостер и мотыгу. Ферма будет строиться поэтапно.");
    }

    private CropPlan chooseCropPlan(List<Container> containers) {
        return List.of(
                new CropPlan(Items.WHEAT_SEEDS, Blocks.WHEAT, countItem(containers, Items.WHEAT_SEEDS)),
                new CropPlan(Items.CARROT, Blocks.CARROTS, countItem(containers, Items.CARROT)),
                new CropPlan(Items.POTATO, Blocks.POTATOES, countItem(containers, Items.POTATO)),
                new CropPlan(Items.BEETROOT_SEEDS, Blocks.BEETROOTS, countItem(containers, Items.BEETROOT_SEEDS))
            ).stream()
            .filter(plan -> plan.available() >= FARM_CROP_COUNT)
            .max((first, second) -> Integer.compare(first.available(), second.available()))
            .orElse(null);
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
        for (int radius = 4; radius <= 20; radius += 2) {
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
                    if (isSuitableFarmSite(level, base)) {
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
                    || !level.getBlockState(new BlockPos(x, baseY, z)).isAir()
                    || !level.getBlockState(new BlockPos(x, baseY + 1, z)).isAir()) {
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
            && level.getBlockState(new BlockPos(composterX, baseY, composterZ)).isAir();
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
            if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.FOOT) continue;
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
            if (!VillageReservations.isStorageAvailable(level, pos, null)) continue;
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
                if (block != Blocks.AIR && block.defaultBlockState().is(net.minecraft.tags.BlockTags.PLANKS)) {
                    counts.merge(block, stack.getCount(), Integer::sum);
                }
            }
        }
        return counts;
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
        for (int radius = 4; radius <= 20; radius += 2) {
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
                    if (isSuitableSite(level, base)) {
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
                if (!ground.isSolidRender(level, groundPos) || !ground.getFluidState().isEmpty()) {
                    return false;
                }

                for (int dy = 0; dy <= 4; dy++) {
                    if (!level.getBlockState(new BlockPos(x, baseY + dy, z)).isAir()) {
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
