package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Server-side GameTests for the persisted physical storage-claim lifecycle.
 * The tests use real Minecraft villagers, block entities, and persistent data.
 */
@GameTestHolder(LivingVillages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageReservationGameTests {
    private VillageReservationGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 20, batch = "reservation_competition")
    public static void oneTaskExcludesCompetingTaskUntilItEnds(GameTestHelper helper) {
        placeFloor(helper);
        BlockPos relativeChest = new BlockPos(1, 1, 1);
        BlockPos chest = helper.absolutePos(relativeChest);
        helper.setBlock(relativeChest, Blocks.CHEST);

        Villager owner = helper.spawn(EntityType.VILLAGER, 0, 1, 1);
        Villager challenger = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        owner.getPersistentData().putBoolean(VillageBuilder.ACTIVE_KEY, true);
        challenger.getPersistentData().putBoolean(VillageBuilder.ACTIVE_KEY, true);
        helper.assertTrue(VillageRegistry.isStorageForVillage(
            helper.getLevel(), owner.blockPosition(), chest),
            "The test chest must first be assigned to the owner's village.");

        helper.assertTrue(VillageReservations.tryReserveStoragePositions(
            helper.getLevel(), owner, List.of(chest), "house"),
            "The first house job must reserve its real chest.");
        helper.assertTrue(!VillageReservations.isStorageAvailable(
            helper.getLevel(), chest, challenger),
            "A second job must not see the first job's reserved chest.");
        helper.assertTrue(!VillageReservations.tryReserveStoragePositions(
            helper.getLevel(), challenger, List.of(chest), "house"),
            "A second task must not reserve the same chest.");

        // Simulate a cancellation/reset. The next lookup must reconcile the stale claim immediately.
        owner.getPersistentData().putBoolean(VillageBuilder.ACTIVE_KEY, false);
        helper.assertTrue(VillageReservations.isStorageAvailable(
            helper.getLevel(), chest, challenger),
            "A claim must be released when its owning task ends.");
        helper.assertTrue(VillageReservations.tryReserveStoragePositions(
            helper.getLevel(), challenger, List.of(chest), "house"),
            "The next active job must be able to claim the released chest.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "stale_storage_cleanup")
    public static void removedChestDoesNotKeepAStaleReservation(GameTestHelper helper) {
        placeFloor(helper);
        BlockPos relativeChest = new BlockPos(1, 1, 1);
        BlockPos chest = helper.absolutePos(relativeChest);
        helper.setBlock(relativeChest, Blocks.CHEST);

        Villager owner = helper.spawn(EntityType.VILLAGER, 0, 1, 1);
        owner.getPersistentData().putBoolean(VillageBuilder.ACTIVE_KEY, true);
        helper.assertTrue(VillageRegistry.isStorageForVillage(
            helper.getLevel(), owner.blockPosition(), chest),
            "The test chest must first be assigned to the owner's village.");
        helper.assertTrue(VillageReservations.tryReserveStoragePositions(
            helper.getLevel(), owner, List.of(chest), "house"),
            "The task must initially reserve the chest.");

        // Replace the container as if a player removed it. When loaded, the invalid position
        // should be pruned so a replacement chest at that coordinate can be reserved.
        helper.setBlock(relativeChest, Blocks.AIR);
        helper.assertTrue(VillageReservations.isStorageAvailable(
            helper.getLevel(), chest, null),
            "A removed container must not retain its reservation.");
        helper.assertTrue(!VillageRegistry.isStorageForVillage(
            helper.getLevel(), owner.blockPosition(), chest),
            "Removing a chest must also clear its persisted village-ownership claim.");
        helper.setBlock(relativeChest, Blocks.CHEST);
        helper.assertTrue(VillageRegistry.isStorageForVillage(
            helper.getLevel(), owner.blockPosition(), chest),
            "A replacement chest may be re-registered to the current village.");
        helper.assertTrue(VillageReservations.tryReserveStoragePositions(
            helper.getLevel(), owner, List.of(chest), "house"),
            "A replacement chest at the old position must be reservable.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "neighbor_village_isolation")
    public static void neighboringVillagesKeepSeparateStorageOwnership(GameTestHelper helper) {
        // The closest HOME anchors across these two groups are 22 blocks apart: enough to
        // exercise the grouping threshold while both settlements remain in one broad scan.
        for (int x = 0; x <= 30; x++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                Blocks.STONE.defaultBlockState(), 3);
        }

        BlockPos homeOneA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeOneB = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos homeTwoA = helper.absolutePos(new BlockPos(25, 1, 1));
        BlockPos homeTwoB = helper.absolutePos(new BlockPos(27, 1, 1));
        BlockPos storeOne = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos storeTwo = helper.absolutePos(new BlockPos(23, 1, 1));
        BlockPos bedOne = helper.absolutePos(new BlockPos(7, 1, 1));
        BlockPos bedTwo = helper.absolutePos(new BlockPos(21, 1, 1));
        BlockPos workshopOne = helper.absolutePos(new BlockPos(9, 1, 1));
        BlockPos workshopTwo = helper.absolutePos(new BlockPos(19, 1, 1));

        helper.getLevel().setBlock(storeOne, Blocks.CHEST.defaultBlockState(), 3);
        helper.getLevel().setBlock(storeTwo, Blocks.CHEST.defaultBlockState(), 3);
        BlockState bedFoot = Blocks.RED_BED.defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST)
            .setValue(BedBlock.PART, BedPart.FOOT);
        helper.getLevel().setBlock(bedOne, bedFoot, 3);
        helper.getLevel().setBlock(bedOne.east(), bedFoot.setValue(BedBlock.PART, BedPart.HEAD), 3);
        helper.getLevel().setBlock(bedTwo, bedFoot, 3);
        helper.getLevel().setBlock(bedTwo.east(), bedFoot.setValue(BedBlock.PART, BedPart.HEAD), 3);
        helper.getLevel().setBlock(workshopOne, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        helper.getLevel().setBlock(workshopTwo, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);

        Villager oneA = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager oneB = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        Villager twoA = helper.spawn(EntityType.VILLAGER, 25, 1, 1);
        Villager twoB = helper.spawn(EntityType.VILLAGER, 27, 1, 1);
        for (Villager villager : List.of(oneA, oneB, twoA, twoB)) villager.setNoAi(true);

        oneA.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeOneA));
        oneB.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeOneB));
        twoA.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeTwoA));
        twoB.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeTwoB));

        BlockPos centerOne = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos centerTwo = helper.absolutePos(new BlockPos(26, 1, 1));
        helper.assertTrue(VillageArea.findVillagers(helper.getLevel(), centerOne, 32, 32).size() == 2,
            "The first resident query must select exactly its own two villagers.");
        helper.assertTrue(VillageArea.findVillagers(helper.getLevel(), centerTwo, 32, 32).size() == 2,
            "The second resident query must select exactly its own two villagers.");

        java.util.UUID villageOne = VillageRegistry.villageId(helper.getLevel(), centerOne);
        java.util.UUID villageTwo = VillageRegistry.villageId(helper.getLevel(), centerTwo);
        helper.assertTrue(villageOne != null && villageTwo != null && !villageOne.equals(villageTwo),
            "Neighboring HOME clusters must retain distinct persistent village identities.");

        helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), centerOne, storeOne),
            "The first physical storage must register to the first village.");
        helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), centerTwo, storeTwo),
            "The second physical storage must register to the second village.");
        helper.assertTrue(!VillageRegistry.isStorageForVillage(helper.getLevel(), centerOne, storeTwo),
            "The first village must not claim the second village's storage.");
        helper.assertTrue(!VillageRegistry.isStorageForVillage(helper.getLevel(), centerTwo, storeOne),
            "The second village must not claim the first village's storage.");

        helper.assertTrue(VillageRegistry.isBedForVillage(helper.getLevel(), centerOne, bedOne),
            "The first bed must register to the first village.");
        helper.assertTrue(VillageRegistry.isBedForVillage(helper.getLevel(), centerTwo, bedTwo),
            "The second bed must register to the second village.");
        helper.assertTrue(!VillageRegistry.isBedForVillage(helper.getLevel(), centerOne, bedTwo),
            "The first village must not count the second village's bed.");
        helper.assertTrue(!VillageRegistry.isBedForVillage(helper.getLevel(), centerTwo, bedOne),
            "The second village must not count the first village's bed.");

        helper.assertTrue(VillageRegistry.isWorkBlockForVillage(helper.getLevel(), centerOne, workshopOne),
            "The first workshop must register to the first village.");
        helper.assertTrue(VillageRegistry.isWorkBlockForVillage(helper.getLevel(), centerTwo, workshopTwo),
            "The second workshop must register to the second village.");
        helper.assertTrue(!VillageRegistry.isWorkBlockForVillage(helper.getLevel(), centerOne, workshopTwo),
            "The first village must not use the second village's workshop.");
        helper.assertTrue(!VillageRegistry.isWorkBlockForVillage(helper.getLevel(), centerTwo, workshopOne),
            "The second village must not use the first village's workshop.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "home_less_resident_assignment")
    public static void multipleResidentsWithoutHomeDoNotBreakGrouping(GameTestHelper helper) {
        placeFloor(helper);
        Villager homeResident = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager firstWithoutHome = helper.spawn(EntityType.VILLAGER, 0, 1, 1);
        Villager secondWithoutHome = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        for (Villager villager : List.of(homeResident, firstWithoutHome, secondWithoutHome)) {
            villager.setNoAi(true);
        }

        BlockPos home = helper.absolutePos(new BlockPos(1, 1, 1));
        homeResident.getBrain().setMemory(
            MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), home));

        List<Villager> selected = VillageArea.findVillagers(helper.getLevel(), home, 8, 8);
        helper.assertTrue(selected.size() == 3,
            "Multiple HOME-less residents must attach safely to one nearby HOME group.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "project_site_collision")
    public static void activeHouseAndFarmProjectsProtectTheirFootprints(GameTestHelper helper) {
        placeFloor(helper);
        Villager houseOwner = helper.spawn(EntityType.VILLAGER, 0, 1, 1);
        Villager farmOwner = helper.spawn(EntityType.VILLAGER, 2, 1, 1);

        BlockPos houseSite = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos farmSite = helper.absolutePos(new BlockPos(50, 1, 1));
        var houseData = houseOwner.getPersistentData();
        houseData.putBoolean(VillageBuilder.ACTIVE_KEY, true);
        houseData.putInt("livingvillages_builder_site_x", houseSite.getX());
        houseData.putInt("livingvillages_builder_site_y", houseSite.getY());
        houseData.putInt("livingvillages_builder_site_z", houseSite.getZ());

        var farmData = farmOwner.getPersistentData();
        farmData.putBoolean(VillageFarmBuilder.ACTIVE_KEY, true);
        farmData.putInt("livingvillages_farm_build_site_x", farmSite.getX());
        farmData.putInt("livingvillages_farm_build_site_y", farmSite.getY());
        farmData.putInt("livingvillages_farm_build_site_z", farmSite.getZ());

        helper.assertTrue(!VillageReservations.isProjectSiteAvailable(
                helper.getLevel(), houseSite.offset(7, 0, 0), 8, null),
            "A new house or farm site inside the safety margin of an active house must be rejected.");
        helper.assertTrue(VillageReservations.isProjectSiteAvailable(
                helper.getLevel(), houseSite.offset(8, 0, 0), 8, null),
            "A site exactly at the configured minimum separation from the active house is allowed.");
        helper.assertTrue(!VillageReservations.isProjectSiteAvailable(
                helper.getLevel(), farmSite.offset(0, 0, 7), 8, null),
            "A new project must also respect the safety margin around an active farm.");
        helper.assertTrue(VillageReservations.isProjectSiteAvailable(
                helper.getLevel(), farmSite.offset(0, 0, 8), 8, null),
            "A site exactly at the minimum separation from the active farm is allowed.");
        helper.succeed();
    }

    private static void placeFloor(GameTestHelper helper) {
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }
}
