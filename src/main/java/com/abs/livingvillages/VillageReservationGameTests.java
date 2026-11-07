package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // The seed-fetch reservation must remain live for the full trip, then release on completion.
        challenger.getPersistentData().putBoolean(VillagerAutonomy.SEED_FETCH_ACTIVE_KEY, true);
        VillageReservations.release(challenger.getPersistentData());
        helper.assertTrue(VillageReservations.tryReserveStoragePositions(
            helper.getLevel(), challenger, List.of(chest), "farmer_seed_fetch"),
            "A farmer must reserve its actual seed storage before walking there.");
        helper.assertTrue(!VillageReservations.isStorageAvailable(
            helper.getLevel(), chest, owner),
            "Another resident must not take the chest while the farmer is fetching seeds.");
        challenger.getPersistentData().putBoolean(VillagerAutonomy.SEED_FETCH_ACTIVE_KEY, false);
        helper.assertTrue(VillageReservations.isStorageAvailable(
            helper.getLevel(), chest, owner),
            "The seed storage reservation must be released when seed fetching ends.");
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

    @GameTest(template = "empty", timeoutTicks = 40, batch = "village_identity_recovery")
    public static void villageIdentitySurvivesHomeAnchorLossAndRelocation(GameTestHelper helper) {
        for (int x = 0; x <= 30; x++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                Blocks.STONE.defaultBlockState(), 3);
        }

        BlockPos homeA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeB = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos storage = helper.absolutePos(new BlockPos(5, 1, 1));
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);

        Villager first = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager second = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        first.setNoAi(true);
        second.setNoAi(true);
        first.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeA));
        second.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeB));

        BlockPos originalCenter = helper.absolutePos(new BlockPos(2, 1, 1));
        java.util.UUID originalId = VillageRegistry.villageId(helper.getLevel(), originalCenter);
        helper.assertTrue(originalId != null,
            "The initial resident group must receive a persistent village identity.");
        helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), originalCenter, storage),
            "The original storage must be owned before the residents move.");

        // Wait one tick so the per-tick identity query cache cannot hide a real identity change.
        helper.runAfterDelay(1, () -> {
            first.getBrain().eraseMemory(MemoryModuleType.HOME);
            second.getBrain().eraseMemory(MemoryModuleType.HOME);
            BlockPos movedFirst = helper.absolutePos(new BlockPos(25, 1, 1));
            BlockPos movedSecond = helper.absolutePos(new BlockPos(27, 1, 1));
            first.setPos(movedFirst.getX() + 0.5D, movedFirst.getY(), movedFirst.getZ() + 0.5D);
            second.setPos(movedSecond.getX() + 0.5D, movedSecond.getY(), movedSecond.getZ() + 0.5D);

            BlockPos movedCenter = helper.absolutePos(new BlockPos(26, 1, 1));
            java.util.UUID recoveredId = VillageRegistry.villageId(helper.getLevel(), movedCenter);
            helper.assertTrue(originalId.equals(recoveredId),
                "Resident UUID overlap must preserve the village identity when HOME anchors disappear and residents relocate.");
            helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), movedCenter, storage),
                "A previously registered chest must remain owned by the same village after relocation.");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "registry_save_load_roundtrip")
    public static void villageRegistryPreservesAssetOwnershipAcrossSaveLoadRoundTrip(GameTestHelper helper) {
        for (int x = 0; x <= 12; x++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                Blocks.STONE.defaultBlockState(), 3);
        }
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);

        BlockPos bedFoot = helper.absolutePos(new BlockPos(7, 1, 1));
        BlockState footState = Blocks.RED_BED.defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST)
            .setValue(BedBlock.PART, BedPart.FOOT);
        helper.setBlock(new BlockPos(7, 1, 1), footState);
        helper.setBlock(new BlockPos(8, 1, 1), footState.setValue(BedBlock.PART, BedPart.HEAD));
        helper.setBlock(new BlockPos(9, 1, 1), Blocks.CRAFTING_TABLE);

        Villager first = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager second = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        first.setNoAi(true);
        second.setNoAi(true);
        first.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 1, 1))));
        second.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(3, 1, 1))));

        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos storage = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos workBlock = helper.absolutePos(new BlockPos(9, 1, 1));
        helper.assertTrue(VillageRegistry.villageId(helper.getLevel(), center) != null,
            "A resident group must get a stable identity before it is saved.");
        helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), center, storage),
            "The chest owner must be recorded.");
        helper.assertTrue(VillageRegistry.isBedForVillage(helper.getLevel(), center, bedFoot),
            "The bed owner must be recorded.");
        helper.assertTrue(VillageRegistry.isWorkBlockForVillage(helper.getLevel(), center, workBlock),
            "The work-block owner must be recorded.");

        VillageRegistry live = VillageRegistry.get(helper.getLevel());
        CompoundTag saved = live.save(new CompoundTag(), helper.getLevel().registryAccess());
        VillageRegistry restored = VillageRegistry.load(saved, helper.getLevel().registryAccess());
        CompoundTag roundTripped = restored.save(new CompoundTag(), helper.getLevel().registryAccess());

        for (String key : List.of(
            "villageAnchors", "knownHomes", "knownResidents",
            "storageOwners", "bedOwners", "workBlockOwners")) {
            Set<String> before = canonicalTagRows(saved, key);
            Set<String> after = canonicalTagRows(roundTripped, key);
            helper.assertTrue(!before.isEmpty(),
                "The saved " + key + " registry section must contain test data.");
            helper.assertTrue(before.equals(after),
                "The " + key + " section must preserve all identities and ownership across SavedData load/save."
                    + " before=" + before + ", after=" + after);
        }
        helper.succeed();
    }

    private static Set<String> canonicalTagRows(CompoundTag root, String listName) {
        ListTag entries = root.getList(listName, Tag.TAG_COMPOUND);
        Set<String> rows = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            switch (listName) {
                case "villageAnchors" ->
                    rows.add(entry.getString("id") + "|" + entry.getLong("anchor"));
                case "knownHomes" -> {
                    long[] positions = entry.getLongArray("positions");
                    Arrays.sort(positions);
                    rows.add(entry.getString("id") + "|" + Arrays.toString(positions));
                }
                case "knownResidents" -> {
                    ListTag members = entry.getList("members", Tag.TAG_STRING);
                    List<String> ids = new ArrayList<>();
                    for (int j = 0; j < members.size(); j++) ids.add(members.getString(j));
                    ids.sort(String::compareTo);
                    rows.add(entry.getString("id") + "|" + String.join(",", ids));
                }
                default -> rows.add(entry.getLong("pos") + "|" + entry.getString("village"));
            }
        }
        return rows;
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "legacy_registry_migration")
    public static void legacyRegistryWithoutHomeAliasesMigratesPrimaryAnchor(GameTestHelper helper) {
        // Simulate a pre-alias SavedData payload: only primary village anchors existed.
        CompoundTag legacy = new CompoundTag();
        ListTag villageAnchors = new ListTag();
        String villageId = java.util.UUID.randomUUID().toString();
        BlockPos primaryAnchor = helper.absolutePos(new BlockPos(2, 1, 1));

        CompoundTag entry = new CompoundTag();
        entry.putString("id", villageId);
        entry.putLong("anchor", primaryAnchor.asLong());
        villageAnchors.add(entry);
        legacy.put("villageAnchors", villageAnchors);

        VillageRegistry restored = VillageRegistry.load(legacy, helper.getLevel().registryAccess());
        CompoundTag migrated = restored.save(new CompoundTag(), helper.getLevel().registryAccess());
        ListTag knownHomes = migrated.getList("knownHomes", Tag.TAG_COMPOUND);
        boolean foundMigratedAlias = false;
        for (int i = 0; i < knownHomes.size(); i++) {
            CompoundTag savedEntry = knownHomes.getCompound(i);
            if (!savedEntry.getString("id").equals(villageId)) continue;
            for (long home : savedEntry.getLongArray("positions")) {
                if (home == primaryAnchor.asLong()) foundMigratedAlias = true;
            }
        }
        helper.assertTrue(foundMigratedAlias,
            "A legacy registry without knownHomes records must promote its primary anchor exactly as a migration fallback.");
        helper.succeed();
    }


    @GameTest(template = "empty", timeoutTicks = 20, batch = "foreign_storage_reservation")
    public static void taskCannotReserveAnotherVillagesStorage(GameTestHelper helper) {
        for (int x = 0; x <= 30; x++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                Blocks.STONE.defaultBlockState(), 3);
        }

        BlockPos homeOneA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeOneB = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos homeTwoA = helper.absolutePos(new BlockPos(25, 1, 1));
        BlockPos homeTwoB = helper.absolutePos(new BlockPos(27, 1, 1));
        BlockPos storageOne = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos storageTwo = helper.absolutePos(new BlockPos(23, 1, 1));
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(23, 1, 1), Blocks.CHEST);

        Villager ownerOne = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager partnerOne = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        Villager ownerTwo = helper.spawn(EntityType.VILLAGER, 25, 1, 1);
        Villager partnerTwo = helper.spawn(EntityType.VILLAGER, 27, 1, 1);
        for (Villager villager : List.of(ownerOne, partnerOne, ownerTwo, partnerTwo)) {
            villager.setNoAi(true);
        }
        ownerOne.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeOneA));
        partnerOne.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeOneB));
        ownerTwo.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeTwoA));
        partnerTwo.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeTwoB));

        BlockPos centerOne = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos centerTwo = helper.absolutePos(new BlockPos(26, 1, 1));
        helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), centerOne, storageOne),
            "The first chest must be owned by village one.");
        helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), centerTwo, storageTwo),
            "The second chest must be owned by village two.");

        helper.assertTrue(helper.getLevel().getBlockEntity(storageTwo) instanceof Container,
            "The foreign chest must have a real container block entity.");
        Container foreignStorage = (Container) helper.getLevel().getBlockEntity(storageTwo);
        foreignStorage.setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
        foreignStorage.setChanged();

        helper.assertTrue(!VillageReservations.tryReserveStoragePositions(
            helper.getLevel(), ownerOne, List.of(storageTwo), "crafting"),
            "A worker from village one must not reserve village two's physical chest.");
        helper.assertTrue(!VillageReservations.reserveStoragesForItems(
            helper.getLevel(), centerOne, ownerOne, "crafting", Map.of(Items.OAK_PLANKS, 4), 32),
            "A recipe must not appear affordable by counting stock from a neighboring village.");
        helper.assertTrue(VillageReservations.isStorageAvailable(
            helper.getLevel(), storageTwo, ownerTwo),
            "The failed foreign claim must leave the owning village's chest free.");
        helper.assertTrue(VillageReservations.tryReserveStoragePositions(
            helper.getLevel(), ownerTwo, List.of(storageTwo), "crafting"),
            "The chest's actual village must still be able to reserve its own resources.");
        helper.succeed();
    }


    @GameTest(template = "empty", timeoutTicks = 40, batch = "home_less_neighbor_recovery")
    public static void homeLessRelocatedResidentsDoNotMergeIntoNeighbor(GameTestHelper helper) {
        for (int x = 0; x <= 30; x++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                Blocks.STONE.defaultBlockState(), 3);
        }

        BlockPos homeOneA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeOneB = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos homeTwoA = helper.absolutePos(new BlockPos(25, 1, 1));
        BlockPos homeTwoB = helper.absolutePos(new BlockPos(27, 1, 1));
        BlockPos storageOne = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos storageTwo = helper.absolutePos(new BlockPos(23, 1, 1));
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(23, 1, 1), Blocks.CHEST);

        Villager oneA = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager oneB = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        Villager twoA = helper.spawn(EntityType.VILLAGER, 25, 1, 1);
        Villager twoB = helper.spawn(EntityType.VILLAGER, 27, 1, 1);
        for (Villager villager : List.of(oneA, oneB, twoA, twoB)) villager.setNoAi(true);
        oneA.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeOneA));
        oneB.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeOneB));
        twoA.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeTwoA));
        twoB.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeTwoB));

        BlockPos centerOne = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos centerTwo = helper.absolutePos(new BlockPos(26, 1, 1));
        java.util.UUID villageOne = VillageRegistry.villageId(helper.getLevel(), centerOne);
        java.util.UUID villageTwo = VillageRegistry.villageId(helper.getLevel(), centerTwo);
        helper.assertTrue(villageOne != null && villageTwo != null && !villageOne.equals(villageTwo),
            "The two neighboring groups must start with distinct persistent identities.");
        helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), centerOne, storageOne),
            "The first storage must be registered before its residents move.");
        helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), centerTwo, storageTwo),
            "The second storage must be registered before the relocation.");

        // Give the SavedData registry a tick boundary before HOME memories and positions change.
        helper.runAfterDelay(1, () -> {
            oneA.getBrain().eraseMemory(MemoryModuleType.HOME);
            oneB.getBrain().eraseMemory(MemoryModuleType.HOME);
            BlockPos movedOneA = helper.absolutePos(new BlockPos(20, 1, 1));
            BlockPos movedOneB = helper.absolutePos(new BlockPos(22, 1, 1));
            oneA.setPos(movedOneA.getX() + 0.5D, movedOneA.getY(), movedOneA.getZ() + 0.5D);
            oneB.setPos(movedOneB.getX() + 0.5D, movedOneB.getY(), movedOneB.getZ() + 0.5D);
            BlockPos movedCenterOne = helper.absolutePos(new BlockPos(21, 1, 1));

            List<Villager> recoveredResidents = VillageArea.findVillagers(
                helper.getLevel(), movedCenterOne, 32, 32);
            helper.assertTrue(recoveredResidents.size() == 2,
                "HOME-less residents with a known village identity must not be absorbed by the neighboring HOME group.");
            java.util.UUID recoveredVillageOne = VillageRegistry.villageId(helper.getLevel(), movedCenterOne);
            helper.assertTrue(villageOne.equals(recoveredVillageOne),
                "Resident UUID history must preserve village one after its HOME anchors disappear near village two.");
            helper.assertTrue(villageTwo.equals(VillageRegistry.villageId(helper.getLevel(), centerTwo)),
                "Village two must keep its own identity after village one's relocation.");
            helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), movedCenterOne, storageOne),
                "The moved residents must retain access to their original storage.");
            helper.assertTrue(!VillageRegistry.isStorageForVillage(helper.getLevel(), centerTwo, storageOne),
                "The neighboring village must not annex storage still owned by the relocated village.");
            helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), centerTwo, storageTwo),
                "The neighboring village must retain access to its own storage.");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "villager_progression_lifecycle")
    public static void villagerProgressionPreservesSkillAcrossRoleChangesAndMigratesSchema(GameTestHelper helper) {
        placeFloor(helper);
        Villager worker = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        worker.setNoAi(true);

        // Simulate a legacy v0 tag with an unknown future field that must not be erased.
        CompoundTag legacyProg = new CompoundTag();
        legacyProg.putString("future_custom_field", "preserve_me");
        worker.getPersistentData().put(VillagerProgression.PROGRESSION_TAG, legacyProg);

        CompoundTag migrated = VillagerProgression.ensureMigratedTag(worker.getPersistentData());
        helper.assertTrue(
            migrated.getInt(VillagerProgression.SCHEMA_VERSION_KEY)
                == com.abs.livingvillages.domain.VillagerProgressionModel.SCHEMA_VERSION,
            "Legacy progression records must be promoted to the current schema version.");
        helper.assertTrue(
            "preserve_me".equals(migrated.getString("future_custom_field")),
            "Schema migration must preserve unknown fields inside the progression tag.");

        String minerRole = com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_MINER;
        String builderRole = com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_BUILDER;
        worker.getPersistentData().putString(VillagerRoles.ROLE_KEY, VillagerRoles.ROLE_MINER);

        // Incomplete/failed attempts must not award XP.
        helper.assertTrue(
            !VillagerProgression.recordCompletedWork(worker, minerRole, 10, false, 100L),
            "Failed or cancelled tasks must never award profession XP.");
        helper.assertTrue(
            VillagerProgression.getXp(worker, minerRole) == 0
                && VillagerProgression.getLevel(worker, minerRole) == 1,
            "Villager must remain at level 1 with 0 XP after a failed attempt.");

        // Confirmed completion awards XP and promotes level upon reaching threshold (10 XP -> Level 2).
        helper.assertTrue(
            VillagerProgression.recordCompletedWork(worker, minerRole, 12, true, 200L),
            "Confirmed completed work must award profession XP.");
        helper.assertTrue(
            VillagerProgression.getXp(worker, minerRole) == 12
                && VillagerProgression.getLevel(worker, minerRole) == 2,
            "Reaching the XP threshold must promote the villager's profession level.");

        // Cooldown guard prevents rapid duplicate XP awards on the same tick.
        helper.assertTrue(
            !VillagerProgression.recordCompletedWork(worker, minerRole, 5, true, 205L),
            "Rapid duplicate work events inside the anti-abuse cooldown must be rejected.");

        // Switching specialist role to builder and earning builder XP must preserve miner XP & level.
        worker.getPersistentData().putString(VillagerRoles.ROLE_KEY, VillagerRoles.ROLE_BUILDER);
        helper.assertTrue(
            VillagerProgression.recordCompletedWork(worker, builderRole, 6, true, 300L),
            "A villager must be able to earn XP in a new role after switching.");
        helper.assertTrue(
            VillagerProgression.getXp(worker, minerRole) == 12
                && VillagerProgression.getLevel(worker, minerRole) == 2,
            "Switching profession/role must preserve previously earned XP and level in prior roles.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "monster_tactical_perception")
    public static void monsterTacticalPerceptionRejectsHiddenTargetsAndCapsPathRetries(GameTestHelper helper) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 2; z++) {
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                    Blocks.STONE.defaultBlockState(), 3);
            }
        }
        // Build a solid stone wall at x = 4 blocking line of sight between (1,1,1) and (7,1,1).
        for (int y = 1; y <= 3; y++) {
            for (int z = 0; z <= 2; z++) {
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(4, y, z)),
                    Blocks.STONE.defaultBlockState(), 3);
            }
        }

        net.minecraft.world.entity.monster.Zombie zombie =
            helper.spawn(EntityType.ZOMBIE, 1, 1, 1);
        Villager hiddenVillager = helper.spawn(EntityType.VILLAGER, 7, 1, 1);
        zombie.setNoAi(true);
        hiddenVillager.setNoAi(true);

        helper.assertTrue(
            !VillageMonsterAI.hasClearLineOfSight(helper.getLevel(), zombie, hiddenVillager),
            "A solid stone wall must block direct line of sight between the monster and the villager.");
        helper.assertTrue(
            VillageMonsterAI.findHonestTarget(zombie, helper.getLevel(), 16, 256.0D) == null,
            "Monsters must not detect hidden villagers through solid walls without a valid signal.");

        // Verify that repeated failed path attempts trigger disengagement at MAX_PATH_RETRIES.
        var decision = com.abs.livingvillages.domain.MonsterTacticalModel.evaluateTacticalStep(
            com.abs.livingvillages.domain.MonsterTacticalModel.KIND_ZOMBIE,
            com.abs.livingvillages.domain.MonsterTacticalModel.STATE_ENGAGING,
            40L,
            com.abs.livingvillages.domain.MonsterTacticalModel.DEFAULT_STATE_TIMEOUT_TICKS,
            com.abs.livingvillages.domain.MonsterTacticalModel.MAX_PATH_RETRIES - 1,
            true,
            true,
            false,
            25.0D,
            1.0D,
            0,
            0,
            true
        );
        helper.assertTrue(
            decision.nextState() == com.abs.livingvillages.domain.MonsterTacticalModel.STATE_DISENGAGE
                && decision.resetTarget(),
            "Exceeding MAX_PATH_RETRIES must transition the monster to STATE_DISENGAGE and reset its target.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "orphaned_project_recovery")
    public static void orphanedHouseProjectIsResumedByReplacementBuilderInSameVillage(GameTestHelper helper) {
        placeFloor(helper);
        BlockPos homeA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeB = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos site = helper.absolutePos(new BlockPos(6, 1, 1));

        Villager firstBuilder = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager secondBuilder = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        firstBuilder.setNoAi(true);
        secondBuilder.setNoAi(true);
        firstBuilder.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeA));
        secondBuilder.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeB));
        firstBuilder.getPersistentData().putString(VillagerRoles.ROLE_KEY, VillagerRoles.ROLE_BUILDER);
        secondBuilder.getPersistentData().putString(VillagerRoles.ROLE_KEY, VillagerRoles.ROLE_BUILDER);

        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 1));
        VillageRegistry.recordHouseCheckpoint(
            helper.getLevel(),
            center,
            new com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint(
                "house",
                site.getX(),
                site.getY(),
                site.getZ(),
                "minecraft:oak_planks",
                "minecraft:oak_door",
                14,
                98,
                firstBuilder.getUUID().toString(),
                100L
            )
        );
        firstBuilder.getPersistentData().putBoolean(VillageBuilder.ACTIVE_KEY, true);

        // While the original builder is alive and active in the village, the replacement builder must not steal it.
        helper.assertTrue(
            !VillageBuilder.tryResumeOrphanedHouseProject(helper.getLevel(), center),
            "An active house project owned by a living builder must not be stolen by another worker.");

        // Simulate the original builder dying/disappearing mid-project (KI-006).
        firstBuilder.discard();

        helper.assertTrue(
            VillageBuilder.tryResumeOrphanedHouseProject(helper.getLevel(), center),
            "When the original builder is lost, an available builder in the same village must resume the checkpoint.");
        helper.assertTrue(
            VillageBuilder.isProjectActive(secondBuilder)
                && secondBuilder.getPersistentData().getInt("livingvillages_builder_step_index") == 14,
            "The replacement builder must adopt the exact saved step_index from the village checkpoint.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "alarm_and_simulation_rules")
    public static void villageAlarmEvacuatesCiviliansAndHonestCatchUpConservesMaterials(GameTestHelper helper) {
        placeFloor(helper);
        BlockPos homeA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeB = helper.absolutePos(new BlockPos(8, 1, 1));

        Villager guard = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager civilian = helper.spawn(EntityType.VILLAGER, 5, 1, 1);
        guard.setNoAi(true);
        civilian.setNoAi(true);
        guard.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeA));
        civilian.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeB));
        guard.getPersistentData().putString(VillagerRoles.ROLE_KEY, VillagerRoles.ROLE_GUARD);

        BlockPos center = helper.absolutePos(new BlockPos(3, 1, 1));
        VillageRegistry.raiseVillageAlarm(helper.getLevel(), center, helper.getLevel().getGameTime());
        VillageDefense.evacuateNearbyCivilians(helper.getLevel(), center);

        helper.assertTrue(
            civilian.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
            "A village defense alarm must direct idle non-guard civilians toward their shelter HOME.");
        helper.assertTrue(
            !guard.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
            "Guards must not be routed into civilian evacuation when an alarm sounds.");

        // Verify honest furnace catch-up never creates ingots without real ore and fuel.
        var noFuel = com.abs.livingvillages.domain.SettlementSimulationModel.evaluateFurnaceCatchUp(2000L, 10, 0, 10);
        helper.assertTrue(
            noFuel.smeltedCount() == 0 && noFuel.oreConsumed() == 0,
            "Catch-up simulation must produce 0 ingots when no real fuel is available.");

        var bounded = com.abs.livingvillages.domain.SettlementSimulationModel.evaluateFurnaceCatchUp(2000L, 5, 1, 10);
        helper.assertTrue(
            bounded.smeltedCount() == 5 && bounded.oreConsumed() == 5 && bounded.fuelConsumed() == 1,
            "Catch-up simulation must conserve matter 1:1 with real ore and fuel.");

        // Verify raider squad scouting and 50% loss retreat rules.
        helper.assertTrue(
            com.abs.livingvillages.domain.SettlementSimulationModel.evaluateRaiderSquad(false, 4, 4)
                == com.abs.livingvillages.domain.SettlementSimulationModel.SQUAD_HOLD_SCOUT,
            "Raider squads must hold until a scout perceives the settlement.");
        helper.assertTrue(
            com.abs.livingvillages.domain.SettlementSimulationModel.evaluateRaiderSquad(true, 4, 2)
                == com.abs.livingvillages.domain.SettlementSimulationModel.SQUAD_RETREAT,
            "Raider squads losing 50% of their force must transition to organized retreat.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "farm_recovery_and_parallel_craft")
    public static void orphanedFarmProjectResumesAndParallelCraftingTelemetryAndTradeRulesHold(GameTestHelper helper) {
        placeFloor(helper);
        BlockPos homeA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeB = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos site = helper.absolutePos(new BlockPos(6, 1, 1));

        Villager firstWorker = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager secondWorker = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        firstWorker.setNoAi(true);
        secondWorker.setNoAi(true);
        firstWorker.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeA));
        secondWorker.getBrain().setMemory(MemoryModuleType.HOME,
            GlobalPos.of(helper.getLevel().dimension(), homeB));

        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 1));
        VillageRegistry.recordFarmCheckpoint(
            helper.getLevel(),
            center,
            new com.abs.livingvillages.domain.SettlementSimulationModel.ProjectCheckpoint(
                "farm",
                site.getX(),
                site.getY(),
                site.getZ(),
                "minecraft:wheat_seeds",
                "minecraft:wheat",
                9,
                50,
                firstWorker.getUUID().toString(),
                120L
            )
        );
        firstWorker.getPersistentData().putBoolean(VillageFarmBuilder.ACTIVE_KEY, true);

        helper.assertTrue(
            !VillageFarmBuilder.tryResumeOrphanedFarmProject(helper.getLevel(), center),
            "An active farm project owned by a living worker must not be stolen.");

        firstWorker.discard();

        helper.assertTrue(
            VillageFarmBuilder.tryResumeOrphanedFarmProject(helper.getLevel(), center),
            "When the original farm worker disappears, an available resident in the same village must resume the farm.");
        helper.assertTrue(
            VillageFarmBuilder.isProjectActive(secondWorker)
                && secondWorker.getPersistentData().getInt("livingvillages_farm_build_step_index") == 9,
            "The replacement farm worker must adopt the exact saved step_index from the farm checkpoint.");

        // Verify KI-008 parallel non-plank crafting rule.
        helper.assertTrue(
            !com.abs.livingvillages.domain.SettlementSimulationModel.canCraftInParallelWithConstruction(true, true)
                && com.abs.livingvillages.domain.SettlementSimulationModel.canCraftInParallelWithConstruction(true, false),
            "Plank-consuming recipes must pause during house construction while non-plank recipes may run.");

        // Verify inter-settlement surplus trade caravan rules (Stage 5.3).
        var blockedByAlarm = com.abs.livingvillages.domain.SettlementSimulationModel.evaluateTradeCaravan(0, true, 16, 32);
        helper.assertTrue(!blockedByAlarm.approved(),
            "Trade caravans must not depart while a village defense alarm is active.");

        var approvedTrade = com.abs.livingvillages.domain.SettlementSimulationModel.evaluateTradeCaravan(0, false, 8, 16);
        helper.assertTrue(
            approvedTrade.approved()
                && approvedTrade.foodTransferred() == 8
                && approvedTrade.planksTransferred() == 16
                && approvedTrade.updatedStanding() == 10,
            "Peaceful villages with real food and plank surplus must complete a material-conserving trade exchange.");

        // Verify tick telemetry recording (Stage 1.4).
        VillageTickBudget.recordSubsystemNanos(helper.getLevel(), 25_000L);
        var telemetry = VillageTickBudget.snapshotTelemetry(helper.getLevel());
        helper.assertTrue(
            telemetry.totalExecutionNanos() >= 25_000L
                && com.abs.livingvillages.domain.TickBudgetModel.averageMicrosPerTick(telemetry) > 0.0D,
            "Server tick telemetry must record subsystem execution time and compute average microseconds per tick.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "two_villages_elder_and_trade")
    public static void twoNeighboringVillagesHaveIndependentEldersOrdersAndExecuteHonestSurplusTrade(GameTestHelper helper) {
        for (int x = 0; x <= 30; x++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                Blocks.STONE.defaultBlockState(), 3);
        }

        BlockPos homeOneA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeOneB = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos homeTwoA = helper.absolutePos(new BlockPos(25, 1, 1));
        BlockPos homeTwoB = helper.absolutePos(new BlockPos(27, 1, 1));
        BlockPos storageOne = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos storageTwo = helper.absolutePos(new BlockPos(23, 1, 1));
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(23, 1, 1), Blocks.CHEST);

        Villager oneA = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager oneB = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        Villager oneC = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        Villager oneD = helper.spawn(EntityType.VILLAGER, 4, 1, 1);
        Villager twoA = helper.spawn(EntityType.VILLAGER, 25, 1, 1);
        Villager twoB = helper.spawn(EntityType.VILLAGER, 26, 1, 1);
        Villager twoC = helper.spawn(EntityType.VILLAGER, 27, 1, 1);
        Villager twoD = helper.spawn(EntityType.VILLAGER, 28, 1, 1);

        for (Villager v : List.of(oneA, oneB, oneC, oneD)) {
            v.setNoAi(true);
            v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeOneA));
        }
        oneC.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeOneB));
        oneD.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeOneB));

        for (Villager v : List.of(twoA, twoB, twoC, twoD)) {
            v.setNoAi(true);
            v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeTwoA));
        }
        twoC.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeTwoB));
        twoD.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeTwoB));

        BlockPos centerOne = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos centerTwo = helper.absolutePos(new BlockPos(26, 1, 1));

        List<Villager> groupOne = VillageArea.findVillagers(helper.getLevel(), centerOne);
        List<Villager> groupTwo = VillageArea.findVillagers(helper.getLevel(), centerTwo);
        VillageElder.ensureElder(helper.getLevel(), centerOne, groupOne);
        VillageElder.ensureElder(helper.getLevel(), centerTwo, groupTwo);

        Villager elderOne = groupOne.stream().filter(VillageElder::isElder).findFirst().orElse(null);
        Villager elderTwo = groupTwo.stream().filter(VillageElder::isElder).findFirst().orElse(null);
        helper.assertTrue(elderOne != null && elderTwo != null && !elderOne.getUUID().equals(elderTwo.getUUID()),
            "Two neighboring populated villages must elect independent elders.");

        // Apply distinct server-validated elder orders in each village and verify isolation.
        VillageElder.applyElderAction(elderOne, 2); // ACTION_REQUEST_HOUSE
        VillageElder.applyElderAction(elderTwo, 4); // ACTION_DEFENSE_PRIORITY
        helper.assertTrue(
            VillageElder.pendingRequest(groupOne) == VillageElder.REQUEST_HOUSE
                && VillageElder.priorityForVillagers(groupOne) == VillageElder.PRIORITY_HOUSING,
            "Village one must record the house order and housing priority.");
        helper.assertTrue(
            VillageElder.pendingRequest(groupTwo) == VillageElder.REQUEST_NONE
                && VillageElder.priorityForVillagers(groupTwo) == VillageElder.PRIORITY_DEFENSE,
            "Village two must retain its own defense priority without receiving village one's house request.");

        // Stock both village chests and execute a real 1:1 physical surplus trade caravan.
        Container chestOne = (Container) helper.getLevel().getBlockEntity(storageOne);
        Container chestTwo = (Container) helper.getLevel().getBlockEntity(storageTwo);
        helper.assertTrue(chestOne != null && chestTwo != null, "Both village chests must exist.");
        chestOne.setItem(0, new ItemStack(Items.BREAD, 16));
        chestTwo.setItem(0, new ItemStack(Items.OAK_PLANKS, 32));

        var snapshotOne = VillageElder.createPanelSnapshot(helper.getLevel(), elderOne, null);
        helper.assertTrue(snapshotOne.population() == 4 && snapshotOne.foodPoints() == 64,
            "Elder panel snapshot must report the owning village's population and stored food points.");

        helper.assertTrue(
            VillageRegistry.executeNeighborTradeIfEligible(helper.getLevel(), centerOne, centerTwo, 4, 8),
            "Peaceful neighboring villages with real surplus must execute a physical trade caravan.");

        // 16 bread - 4 reserve = 12 surplus (3 bundles of 4 bread <-> 3 bundles of 8 planks = 24 planks).
        int breadAfterOne = chestOne.getItem(0).is(Items.BREAD) ? chestOne.getItem(0).getCount() : 0;
        int planksAfterOne = chestOne.getItem(1).is(Items.OAK_PLANKS) ? chestOne.getItem(1).getCount() : 0;
        int planksAfterTwo = chestTwo.getItem(0).is(Items.OAK_PLANKS) ? chestTwo.getItem(0).getCount() : 0;
        int breadAfterTwo = chestTwo.getItem(1).is(Items.BREAD) ? chestTwo.getItem(1).getCount() : 0;

        helper.assertTrue(
            breadAfterOne + breadAfterTwo == 16 && planksAfterOne + planksAfterTwo == 32,
            "Inter-settlement trade must strictly conserve total bread and planks (zero free items).");
        helper.assertTrue(
            planksAfterOne == 24 && breadAfterTwo == 12,
            "Each village's chest must physically receive the traded goods from its neighbor.");
        helper.assertTrue(
            VillageRegistry.getDiplomaticStanding(helper.getLevel(), centerOne, centerTwo) == 15,
            "Successful surplus trade must increase persistent diplomatic standing between the two villages.");

        // Also verify biome-specific resource trade: Desert village exchanges surplus Sandstone for Forest Oak Planks.
        chestOne.clearContent();
        chestTwo.clearContent();
        chestOne.setItem(0, new ItemStack(Items.SANDSTONE, 32));
        chestTwo.setItem(0, new ItemStack(Items.OAK_PLANKS, 32));
        boolean biomeTraded = VillageRegistry.executeBiomeResourceTradeIfEligible(
            helper.getLevel(),
            centerOne,
            centerTwo,
            8
        );
        helper.assertTrue(
            biomeTraded
                && countItem(chestOne, Items.OAK_PLANKS) == 24
                && countItem(chestTwo, Items.SANDSTONE) == 12,
            "Treeless desert village must exchange surplus Sandstone for Forest Oak Planks via executeBiomeResourceTradeIfEligible.");
        chestOne.clearContent();
        chestTwo.clearContent();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "catchup_smelting_and_raider_squad")
    public static void catchUpSmeltingRaiderSquadRetreatAndGuardEquipmentRequestWorkInWorld(GameTestHelper helper) {
        for (int x = 0; x <= 20; x++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                Blocks.STONE.defaultBlockState(), 3);
        }
        BlockPos homeA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeB = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos furnacePos = helper.absolutePos(new BlockPos(7, 1, 1));
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(7, 1, 1), Blocks.FURNACE);

        Villager residentA = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager residentB = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        residentA.setNoAi(true);
        residentB.setNoAi(true);
        residentA.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeA));
        residentB.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeB));

        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 1));
        helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), center, chestPos),
            "Village must own its chest.");
        helper.assertTrue(VillageRegistry.isWorkBlockForVillage(helper.getLevel(), center, furnacePos),
            "Village must own its furnace.");

        Container chest = (Container) helper.getLevel().getBlockEntity(chestPos);
        helper.assertTrue(chest != null, "Chest container must exist.");
        chest.setItem(0, new ItemStack(Items.RAW_IRON, 6));
        chest.setItem(1, new ItemStack(Items.COAL, 1));

        // 1200 ticks = 6 smelting operations; 1 coal smelts up to 8 items -> 6 raw iron + 1 coal -> 6 iron ingots.
        int smelted = VillageRegistry.applyCatchUpSmeltingIfEligible(helper.getLevel(), center, 1200L);
        helper.assertTrue(smelted == 6,
            "Honest catch-up smelting must smelt 6 raw iron using 1 coal over 1200 elapsed ticks.");
        helper.assertTrue(
            chest.getItem(0).is(Items.IRON_INGOT) && chest.getItem(0).getCount() == 6
                && chest.getItem(1).isEmpty(),
            "Catch-up smelting must consume raw iron and coal and deposit 6 iron ingots into the village chest.");

        // Guard equipment request flag lifecycle (Stage 4.4).
        VillageRegistry.setGuardEquipmentRequested(helper.getLevel(), center, true);
        helper.assertTrue(VillageRegistry.isGuardEquipmentRequested(helper.getLevel(), center),
            "Unarmed guard demand must be recorded on the village.");
        VillageRegistry.setGuardEquipmentRequested(helper.getLevel(), center, false);
        helper.assertTrue(!VillageRegistry.isGuardEquipmentRequested(helper.getLevel(), center),
            "Guard equipment demand must clear once a sword is equipped.");

        // Organized raider squad scouting and 50% casualty retreat in-world (Stage 5.2).
        net.minecraft.world.entity.monster.Pillager leader = helper.spawn(EntityType.PILLAGER, 14, 1, 1);
        net.minecraft.world.entity.monster.Pillager wingman = helper.spawn(EntityType.PILLAGER, 15, 1, 1);
        net.minecraft.world.entity.monster.Pillager casualtyA = helper.spawn(EntityType.PILLAGER, 16, 1, 1);
        net.minecraft.world.entity.monster.Pillager casualtyB = helper.spawn(EntityType.PILLAGER, 17, 1, 1);
        for (var p : List.of(leader, wingman, casualtyA, casualtyB)) p.setNoAi(true);
        leader.setTarget(residentA);

        int holdDecision = VillageMonsterAI.evaluateRaiderSquadTactics(leader, helper.getLevel(), false);
        helper.assertTrue(
            holdDecision == com.abs.livingvillages.domain.SettlementSimulationModel.SQUAD_HOLD_SCOUT,
            "Raider squad of 4 must hold until a scout perceives the settlement.");

        int advanceDecision = VillageMonsterAI.evaluateRaiderSquadTactics(leader, helper.getLevel(), true);
        helper.assertTrue(
            advanceDecision == com.abs.livingvillages.domain.SettlementSimulationModel.SQUAD_ADVANCE,
            "Scouted settlement with full 4-raider squad must advance.");

        // Inflict 50% squad casualties (2 of 4 eliminated).
        casualtyA.discard();
        casualtyB.discard();
        int retreatDecision = VillageMonsterAI.evaluateRaiderSquadTactics(leader, helper.getLevel(), true);
        helper.assertTrue(
            retreatDecision == com.abs.livingvillages.domain.SettlementSimulationModel.SQUAD_RETREAT
                && leader.getTarget() == null
                && leader.getPersistentData().getInt(VillageMonsterAI.STATE_KEY)
                    == com.abs.livingvillages.domain.MonsterTacticalModel.STATE_DISENGAGE,
            "Losing 50% of the raider squad must trigger organized retreat and clear the attack target.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "monster_archetypes_and_visit_catchup")
    public static void monsterArchetypesWitchAllySupportCreeperMobGriefingAndVisitCatchUpWorkInWorld(GameTestHelper helper) {
        for (int x = 0; x <= 20; x++) {
            for (int z = 0; z <= 2; z++) {
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                    Blocks.STONE.defaultBlockState(), 3);
            }
        }

        BlockPos homeA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeB = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos furnacePos = helper.absolutePos(new BlockPos(7, 1, 1));
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(7, 1, 1), Blocks.FURNACE);

        Villager residentA = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager residentB = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        residentA.setNoAi(true);
        residentB.setNoAi(true);
        residentA.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeA));
        residentB.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeB));

        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 1));
        helper.assertTrue(VillageRegistry.isStorageForVillage(helper.getLevel(), center, chestPos),
            "Village must own its chest.");
        helper.assertTrue(VillageRegistry.isWorkBlockForVillage(helper.getLevel(), center, furnacePos),
            "Village must own its furnace.");

        Container chest = (Container) helper.getLevel().getBlockEntity(chestPos);
        helper.assertTrue(chest != null, "Chest container must exist.");
        chest.setItem(0, new ItemStack(Items.RAW_IRON, 3));
        chest.setItem(1, new ItemStack(Items.COAL, 1));

        // First visit records baseline timestamp (0 smelted); second visit 600 ticks later smelts 3 iron.
        helper.assertTrue(VillageRegistry.recordVisitAndRunCatchUp(helper.getLevel(), center, 100L) == 0,
            "Initial settlement visit must record the baseline timestamp without fabricating ingots.");
        helper.assertTrue(VillageRegistry.recordVisitAndRunCatchUp(helper.getLevel(), center, 700L) == 3,
            "Revisiting the settlement 600 ticks later must smelt 3 raw iron into 3 iron ingots.");

        // Spawn a Witch and a wounded Spider ally in sight of residentB (Stages 4.2 & 4.3).
        net.minecraft.world.entity.monster.Witch witch = helper.spawn(EntityType.WITCH, 3, 1, 2);
        net.minecraft.world.entity.monster.Spider woundedAlly = helper.spawn(EntityType.SPIDER, 4, 1, 2);
        witch.setNoAi(true);
        woundedAlly.setNoAi(true);
        woundedAlly.setHealth(4.0F);
        witch.setTarget(residentB);

        var witchDecision = VillageMonsterAI.evaluateMobTactics(
            witch,
            helper.getLevel(),
            com.abs.livingvillages.domain.MonsterTacticalModel.KIND_WITCH,
            1000L
        );
        helper.assertTrue(
            witchDecision.tacticalAction() == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_SUPPORT_ALLIES
                && (woundedAlly.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)
                    || woundedAlly.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE)),
            "A Witch with nearby allies must select ACTION_SUPPORT_ALLIES and buff a wounded ally.");

        // Verify Skeleton hold-distance, Creeper mobGriefing compliance, and Spider vertical flank decisions.
        var skeletonClose = com.abs.livingvillages.domain.MonsterTacticalModel.evaluateTacticalStep(
            com.abs.livingvillages.domain.MonsterTacticalModel.KIND_SKELETON,
            com.abs.livingvillages.domain.MonsterTacticalModel.STATE_ENGAGING,
            20L, 200L, 0, true, true, true, 16.0D, 0.9D, 0, 1, true
        );
        helper.assertTrue(
            skeletonClose.tacticalAction() == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_HOLD_RANGE_OR_STRAFE,
            "Skeletons within close range must hold distance from defenders.");

        var creeperNoGrief = com.abs.livingvillages.domain.MonsterTacticalModel.evaluateTacticalStep(
            com.abs.livingvillages.domain.MonsterTacticalModel.KIND_CREEPER,
            com.abs.livingvillages.domain.MonsterTacticalModel.STATE_ENGAGING,
            20L, 200L, 0, true, true, true, 9.0D, 1.0D, 0, 1, false
        );
        helper.assertTrue(
            creeperNoGrief.tacticalAction() == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_CAUTIOUS_APPROACH,
            "Creepers must respect mobGriefing=false and use cautious approach without breaching structures.");

        var spiderFlank = com.abs.livingvillages.domain.MonsterTacticalModel.evaluateTacticalStep(
            com.abs.livingvillages.domain.MonsterTacticalModel.KIND_SPIDER,
            com.abs.livingvillages.domain.MonsterTacticalModel.STATE_ENGAGING,
            20L, 200L, 0, true, true, true, 25.0D, 1.0D, 1, 1, true
        );
        helper.assertTrue(
            spiderFlank.tacticalAction() == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_VERTICAL_FLANK,
            "Spiders engaging a defended target must select vertical/flank positioning.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "ports_soul_migration_and_auto_trade")
    public static void portsAdapterSoulMigrationElderSkillSummaryAndAutoNeighborTradeWorkInWorld(GameTestHelper helper) {
        for (int x = 0; x <= 30; x++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                Blocks.STONE.defaultBlockState(), 3);
        }

        BlockPos homeOneA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeOneB = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos homeTwoA = helper.absolutePos(new BlockPos(25, 1, 1));
        BlockPos homeTwoB = helper.absolutePos(new BlockPos(27, 1, 1));
        BlockPos storageOne = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos furnaceOne = helper.absolutePos(new BlockPos(7, 1, 1));
        BlockPos storageTwo = helper.absolutePos(new BlockPos(23, 1, 1));
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(7, 1, 1), Blocks.FURNACE);
        helper.setBlock(new BlockPos(23, 1, 1), Blocks.CHEST);

        Villager oneA = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager oneB = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        Villager oneC = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        Villager oneD = helper.spawn(EntityType.VILLAGER, 4, 1, 1);
        Villager twoA = helper.spawn(EntityType.VILLAGER, 25, 1, 1);
        Villager twoB = helper.spawn(EntityType.VILLAGER, 26, 1, 1);
        Villager twoC = helper.spawn(EntityType.VILLAGER, 27, 1, 1);
        Villager twoD = helper.spawn(EntityType.VILLAGER, 28, 1, 1);

        for (Villager v : List.of(oneA, oneB, oneC, oneD)) {
            v.setNoAi(true);
            v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeOneA));
        }
        oneC.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeOneB));
        oneD.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeOneB));

        for (Villager v : List.of(twoA, twoB, twoC, twoD)) {
            v.setNoAi(true);
            v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeTwoA));
        }
        twoC.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeTwoB));
        twoD.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeTwoB));

        // 1. Verify VillagerSoul v0 -> v1 schema migration and corrupt tag backup (Stage 1.2).
        net.minecraft.nbt.CompoundTag legacyRoot = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.CompoundTag legacySoul = new net.minecraft.nbt.CompoundTag();
        legacySoul.putString("name", "Мира");
        legacySoul.putString("custom_addon_note", "preserved");
        legacyRoot.put(VillagerSoul.SOUL_KEY, legacySoul);
        net.minecraft.nbt.CompoundTag migratedSoul = VillagerSoul.ensureMigratedSoulTag(legacyRoot);
        helper.assertTrue(
            migratedSoul.getInt(VillagerSoul.SCHEMA_VERSION_KEY) == VillagerSoul.SCHEMA_VERSION
                && "preserved".equals(migratedSoul.getString("custom_addon_note")),
            "VillagerSoul schema migration must upgrade to v1 while preserving unknown fields.");

        // 2. Verify domain port adapters against the real ServerLevel (Stage 1.3).
        BlockPos centerOne = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos centerTwo = helper.absolutePos(new BlockPos(26, 1, 1));
        VillageRegistry.villageId(helper.getLevel(), centerOne);
        VillageRegistry.villageId(helper.getLevel(), centerTwo);
        VillageRegistry.isStorageForVillage(helper.getLevel(), centerOne, storageOne);
        VillageRegistry.isWorkBlockForVillage(helper.getLevel(), centerOne, furnaceOne);
        VillageRegistry.isStorageForVillage(helper.getLevel(), centerTwo, storageTwo);

        Container chestOne = (Container) helper.getLevel().getBlockEntity(storageOne);
        Container chestTwo = (Container) helper.getLevel().getBlockEntity(storageTwo);
        helper.assertTrue(chestOne != null && chestTwo != null, "Both village chests must exist.");
        chestOne.setItem(0, new ItemStack(Items.RAW_IRON, 4));
        chestOne.setItem(1, new ItemStack(Items.COAL, 1));

        var worldPort = VillageRegistry.createWorldAccessPort(helper.getLevel());
        var storagePort = VillageRegistry.createStorageAccessPort(helper.getLevel());
        var persistencePort = VillageRegistry.createPersistencePort(helper.getLevel());
        var catchUpViaPorts = com.abs.livingvillages.domain.SettlementPorts.evaluateCatchUpViaPorts(
            worldPort,
            storagePort,
            centerOne.getX(), centerOne.getY(), centerOne.getZ(),
            800L,
            "minecraft:raw_iron",
            "minecraft:coal",
            "minecraft:iron_ingot"
        );
        helper.assertTrue(
            catchUpViaPorts.smeltedCount() == 4
                && persistencePort.schemaVersion() == VillageRegistry.SCHEMA_VERSION,
            "Domain ports adapter must query world and storage state accurately for catch-up smelting.");

        // 3. Verify Elder panel specialist mastery summary (Stage 3.4).
        oneB.getPersistentData().putString(VillagerRoles.ROLE_KEY, VillagerRoles.ROLE_BUILDER);
        VillagerProgression.recordCompletedWork(
            oneB,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_BUILDER,
            12,
            true,
            200L
        );
        List<Villager> groupOne = VillageArea.findVillagers(helper.getLevel(), centerOne);
        VillageElder.ensureElder(helper.getLevel(), centerOne, groupOne);
        Villager elderOne = groupOne.stream().filter(VillageElder::isElder).findFirst().orElse(null);
        helper.assertTrue(elderOne != null, "Village one must elect an elder.");
        var panelSnapshot = VillageElder.createPanelSnapshot(helper.getLevel(), elderOne, null);
        helper.assertTrue(
            panelSnapshot.notice().contains("Мастера:") && panelSnapshot.notice().contains("Строитель ур. 2"),
            "Elder panel snapshot must include the specialist mastery summary when villagers earn XP.");

        // 4. Verify automatic neighbor discovery and trade via tryTradeWithKnownNeighbors (Stage 5.3).
        chestOne.setItem(2, new ItemStack(Items.BREAD, 12));
        chestTwo.setItem(0, new ItemStack(Items.OAK_PLANKS, 24));
        int completedTrades = VillageRegistry.tryTradeWithKnownNeighbors(helper.getLevel(), centerOne, 4, 8);
        helper.assertTrue(
            completedTrades == 1
                && VillageRegistry.getDiplomaticStanding(helper.getLevel(), centerOne, centerTwo) > 0,
            "tryTradeWithKnownNeighbors must automatically discover the loaded neighbor and execute surplus trade.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "specialist_mastery_and_agent_port")
    public static void experiencedSpecialistPreferredForRoleAndAgentControlPortNavigatesInWorld(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                Blocks.STONE.defaultBlockState(), 3);
        }

        BlockPos homeA = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos homeB = helper.absolutePos(new BlockPos(3, 1, 1));
        Villager candA = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager candB = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        candA.setNoAi(true);
        candB.setNoAi(true);
        candA.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeA));
        candB.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeB));

        // Identify the candidate whose UUID sorts later so that without XP priority they would lose tie-break.
        Villager veteran = candA.getUUID().toString().compareTo(candB.getUUID().toString()) > 0 ? candA : candB;
        Villager novice = veteran == candA ? candB : candA;
        VillagerProgression.recordCompletedWork(
            veteran,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_BUILDER,
            15,
            true,
            100L
        );

        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 1));
        VillagerRoles.assignRoles(helper.getLevel(), center);
        helper.assertTrue(
            VillagerRoles.isBuilder(veteran) && !VillagerRoles.isBuilder(novice),
            "VillagerRoles.assignRoles must prefer the candidate with higher progression XP in the target role.");

        // Verify AgentControlPort navigation and cleanup in-world (Stage 1.3).
        var agentPort = VillageRegistry.createAgentControlPort(helper.getLevel());
        String veteranId = veteran.getUUID().toString();
        BlockPos navTarget = helper.absolutePos(new BlockPos(6, 1, 1));
        helper.assertTrue(agentPort.isAgentAlive(veteranId),
            "AgentControlPort must report living villager as alive.");
        helper.assertTrue(
            agentPort.navigateTo(veteranId, navTarget.getX(), navTarget.getY(), navTarget.getZ(), 0.55D)
                && veteran.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
            "AgentControlPort.navigateTo must set WALK_TARGET on the villager.");
        agentPort.clearNavigation(veteranId);
        helper.assertTrue(
            !veteran.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
            "AgentControlPort.clearNavigation must erase WALK_TARGET on the villager.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "logistics_and_farmer_surplus_xp")
    public static void logisticsAndFarmerSurplusDeliveriesAwardXpAndConserveMaterialsInWorld(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                Blocks.STONE.defaultBlockState(), 3);
        }

        BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 1));
        helper.setBlock(new BlockPos(4, 1, 1), Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(chestPos);
        helper.assertTrue(chest != null, "Chest container must exist.");

        Villager courier = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager farmer = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        courier.setNoAi(true);
        farmer.setNoAi(true);

        // 1. Logistics courier deposits 6 oak logs into the chest and earns ROLE_COURIER XP.
        courier.getInventory().addItem(new ItemStack(Items.OAK_LOG, 6));
        int logsMoved = VillageLogistics.depositCarriedMaterials(courier, chest, 300L);
        helper.assertTrue(
            logsMoved == 6
                && chest.getItem(0).is(Items.OAK_LOG)
                && chest.getItem(0).getCount() == 6
                && courier.getInventory().getItem(0).isEmpty()
                && VillagerProgression.getXp(courier, com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_COURIER) == 2,
            "VillageLogistics must transfer 6 oak logs 1:1 into the chest and award ROLE_COURIER XP.");

        // 2. Farmer carrying 8 bread (32 food points) deposits 5 surplus bread (20 points), keeps 3 bread (12 reserve), and earns ROLE_FARMER XP.
        farmer.getInventory().addItem(new ItemStack(Items.BREAD, 8));
        int breadMoved = VillagerAutonomy.depositFarmerSurplus(farmer, chest, 400L);
        helper.assertTrue(
            breadMoved == 5
                && chest.getItem(1).is(Items.BREAD)
                && chest.getItem(1).getCount() == 5
                && farmer.getInventory().getItem(0).is(Items.BREAD)
                && farmer.getInventory().getItem(0).getCount() == 3
                && VillagerProgression.getXp(farmer, com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_FARMER) == 2,
            "VillagerAutonomy must deposit 5 surplus bread into the chest, retain 3 bread (12 food points) in inventory, and award ROLE_FARMER XP.");

        // 3. Verify miner and lumberjack step-tick efficiency bonus with floor guard.
        VillagerProgression.recordCompletedWork(
            courier,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_MINER,
            30,
            true,
            500L
        );
        int adjustedMineSteps = VillagerProgression.adjustedStepTicks(
            courier,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_MINER,
            12,
            3
        );
        helper.assertTrue(
            adjustedMineSteps < 12 && adjustedMineSteps >= 3,
            "Experienced miners must receive a bounded step-tick reduction above the minimum floor.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "enhanced_mob_and_guard_behaviors")
    public static void enhancedMobBehaviorsPillagerAlertSearchPatrolSkeletonSpreadCreeperHoldWitchTriageAndGuardAidWorkInWorld(
            GameTestHelper helper) {
        for (int x = 0; x <= 24; x++) {
            for (int z = 0; z <= 2; z++) {
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                    Blocks.STONE.defaultBlockState(), 3);
            }
        }

        BlockPos homeA = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos homeB = helper.absolutePos(new BlockPos(4, 1, 1));
        Villager guard = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        Villager civilian = helper.spawn(EntityType.VILLAGER, 6, 1, 1);
        guard.setNoAi(true);
        civilian.setNoAi(true);
        guard.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeA));
        civilian.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeB));
        guard.getPersistentData().putString(VillagerRoles.ROLE_KEY, VillagerRoles.ROLE_GUARD);

        // 1. Pillagers: group threat broadcast, 3-step search of last known position, and return to patrol.
        net.minecraft.world.entity.monster.Pillager scout = helper.spawn(EntityType.PILLAGER, 14, 1, 1);
        net.minecraft.world.entity.monster.Pillager allyRaider = helper.spawn(EntityType.PILLAGER, 16, 1, 1);
        scout.setNoAi(true);
        allyRaider.setNoAi(true);
        VillageMonsterAI.ensureRaiderPatrolOrigin(allyRaider, allyRaider.getPersistentData());
        int alertedCount = VillageMonsterAI.alertNearbyRaiders(scout, helper.getLevel(), civilian, civilian.blockPosition());
        helper.assertTrue(
            alertedCount >= 1
                && allyRaider.getTarget() == civilian
                && allyRaider.getPersistentData().getBoolean(VillageMonsterAI.HAS_LAST_KNOWN_KEY)
                && allyRaider.getPersistentData().getInt(VillageMonsterAI.SEARCH_STEPS_KEY)
                    == com.abs.livingvillages.domain.MonsterTacticalModel.DEFAULT_SEARCH_STEPS,
            "Pillager must broadcast threat target and last known position to nearby Raider allies.");

        // Step through the 3 search steps (3 -> 2 -> 1 -> 0) and verify transition to STATE_PATROLLING.
        var step1 = VillageMonsterAI.stepLastKnownSearchOrPatrol(allyRaider, helper.getLevel(), allyRaider.getPersistentData(), 100L);
        var step2 = VillageMonsterAI.stepLastKnownSearchOrPatrol(allyRaider, helper.getLevel(), allyRaider.getPersistentData(), 120L);
        var step3 = VillageMonsterAI.stepLastKnownSearchOrPatrol(allyRaider, helper.getLevel(), allyRaider.getPersistentData(), 140L);
        var step4 = VillageMonsterAI.stepLastKnownSearchOrPatrol(allyRaider, helper.getLevel(), allyRaider.getPersistentData(), 160L);
        helper.assertTrue(
            step1.nextState() == com.abs.livingvillages.domain.MonsterTacticalModel.STATE_SEARCHING_LAST_KNOWN
                && step2.nextState() == com.abs.livingvillages.domain.MonsterTacticalModel.STATE_SEARCHING_LAST_KNOWN
                && step3.nextState() == com.abs.livingvillages.domain.MonsterTacticalModel.STATE_SEARCHING_LAST_KNOWN
                && step4.nextState() == com.abs.livingvillages.domain.MonsterTacticalModel.STATE_PATROLLING
                && step4.tacticalAction() == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_PATROL_STEP,
            "Pillager must search last known position for 3 steps and then return to its patrol route.");

        // 2. Zombies: noise investigation and gradual search termination back to STATE_IDLE.
        var zombieNoise = com.abs.livingvillages.domain.MonsterTacticalModel.evaluateTacticalStep(
            com.abs.livingvillages.domain.MonsterTacticalModel.KIND_ZOMBIE,
            com.abs.livingvillages.domain.MonsterTacticalModel.STATE_IDLE,
            20L, 200L, 0, true, false, true, 64.0D, 1.0D, 0, 0, true
        );
        var zombieIdleAfterSearch = com.abs.livingvillages.domain.MonsterTacticalModel.evaluateSearchDecay(false, 0, false);
        helper.assertTrue(
            zombieNoise.tacticalAction() == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_INVESTIGATE_NOISE
                && zombieIdleAfterSearch.nextState() == com.abs.livingvillages.domain.MonsterTacticalModel.STATE_IDLE,
            "Zombies must investigate suspicious noise and return to idle once search steps expire.");

        // 3. Skeletons: anti-clustering spread and cover seeking when under fire.
        int skelClusterAction = com.abs.livingvillages.domain.MonsterTacticalModel.selectSkeletonCombatAction(
            81.0D, true, 2, false
        );
        int skelCoverAction = com.abs.livingvillages.domain.MonsterTacticalModel.selectSkeletonCombatAction(
            81.0D, true, 0, true
        );
        helper.assertTrue(
            skelClusterAction == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_SEEK_COVER_OR_SPREAD
                && skelCoverAction == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_SEEK_COVER_OR_SPREAD,
            "Skeletons must spread out when clustered and seek cover when under fire.");

        // 4. Spiders: vertical wall detection, ambush pounce, and multi-angle flanking.
        helper.setBlock(new BlockPos(10, 1, 0), Blocks.STONE);
        helper.setBlock(new BlockPos(10, 2, 0), Blocks.STONE);
        helper.assertTrue(
            VillageMonsterAI.hasClimbableVerticalSurfaceNear(helper.getLevel(), helper.absolutePos(new BlockPos(10, 1, 1)))
                && com.abs.livingvillages.domain.MonsterTacticalModel.selectSpiderTacticalAction(25.0D, true, true, true)
                    == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_AMBUSH_POUNCE,
            "Spiders must detect climbable vertical surfaces and execute ambush pounces.");

        // 5. Creepers: hold for ally clearance when allies swarm the target and abort unreachable paths.
        helper.assertTrue(
            com.abs.livingvillages.domain.MonsterTacticalModel.selectCreeperTacticalAction(16.0D, true, true, 2, true)
                == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_HOLD_FOR_ALLY_CLEARANCE
                && com.abs.livingvillages.domain.MonsterTacticalModel.selectCreeperTacticalAction(16.0D, true, false, 0, true)
                    == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_NONE,
            "Creepers must hold when allies are adjacent to the target and abort unreachable paths.");

        // 6. Witches: prioritize the most critically wounded ally and support 3 distinct potion modes.
        net.minecraft.world.entity.monster.Witch witch = helper.spawn(EntityType.WITCH, 18, 1, 1);
        net.minecraft.world.entity.monster.Spider minorWound = helper.spawn(EntityType.SPIDER, 19, 1, 1);
        net.minecraft.world.entity.monster.Spider criticalWound = helper.spawn(EntityType.SPIDER, 20, 1, 1);
        witch.setNoAi(true);
        minorWound.setNoAi(true);
        criticalWound.setNoAi(true);
        minorWound.setHealth(12.0F);   // 75% HP
        criticalWound.setHealth(4.0F); // 25% HP
        var chosenAlly = VillageMonsterAI.selectPriorityAllyForWitchSupport(
            witch,
            helper.getLevel(),
            witch.getBoundingBox().inflate(8.0D, 4.0D, 8.0D)
        );
        helper.assertTrue(
            chosenAlly == criticalWound
                && com.abs.livingvillages.domain.MonsterTacticalModel.selectWitchSupportMode(2, 0.25D, true)
                    == com.abs.livingvillages.domain.MonsterTacticalModel.WITCH_SUPPORT_CRITICAL_HEAL
                && com.abs.livingvillages.domain.MonsterTacticalModel.selectWitchSupportMode(2, 0.95D, true)
                    == com.abs.livingvillages.domain.MonsterTacticalModel.WITCH_SUPPORT_VANGUARD_BUFF,
            "Witch must select the most critically wounded ally first and distinguish critical heal vs vanguard buff.");

        // 7. Village Guards: prioritize direct threat to a civilian villager and assist wounded villagers after combat.
        scout.discard();
        allyRaider.discard();
        witch.discard();
        minorWound.discard();
        criticalWound.discard();

        net.minecraft.world.entity.monster.Zombie passiveNearGuard = helper.spawn(EntityType.ZOMBIE, 3, 1, 1);
        net.minecraft.world.entity.monster.Zombie attackerOnCivilian = helper.spawn(EntityType.ZOMBIE, 6, 1, 2);
        passiveNearGuard.setNoAi(true);
        attackerOnCivilian.setNoAi(true);
        attackerOnCivilian.setTarget(civilian);

        var priorityThreat = VillageDefense.selectPriorityThreat(guard, helper.getLevel(), homeA, 16);
        helper.assertTrue(
            priorityThreat == attackerOnCivilian,
            "Guard must prioritize the enemy actively threatening a civilian villager over a passive closer enemy.");

        passiveNearGuard.discard();
        attackerOnCivilian.discard();

        // Wound the civilian, place guard within 2 blocks carrying bread, and verify post-combat aid.
        civilian.setPos(guard.getX() + 1.5D, guard.getY(), guard.getZ());
        civilian.setHealth(10.0F);
        guard.getInventory().addItem(new ItemStack(Items.BREAD, 2));
        boolean assisted = VillageDefense.assistWoundedVillagerAfterCombat(
            guard,
            helper.getLevel(),
            homeA,
            guard.getPersistentData(),
            500L
        );
        helper.assertTrue(
            assisted
                && civilian.getHealth() > 10.0F
                && civilian.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)
                && civilian.getInventory().getItem(0).is(Items.BREAD)
                && civilian.getInventory().getItem(0).getCount() == 1,
            "After combat, guard must heal a nearby wounded villager, apply regeneration, and share 1 food item.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "villager_trust_animal_herd_and_breeding")
    public static void villagerTrustTiersAnimalHerdParentalBreedingAndPillagerReloadWorkInWorld(GameTestHelper helper) {
        for (int x = 0; x <= 30; x++) {
            for (int z = 0; z <= 2; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
            }
        }

        // 1. Villager Trust System (0-100, 5 tiers, persistence, preferences, defense, theft, and behavior).
        Villager citizen = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        citizen.setNoAi(true);
        java.util.UUID playerUuid = java.util.UUID.nameUUIDFromBytes("trust-test-player".getBytes());

        helper.assertTrue(
            com.abs.livingvillages.domain.TrustAndAnimalModel.trustTierNameRu(10).equals("незнакомец")
                && com.abs.livingvillages.domain.TrustAndAnimalModel.trustTierNameRu(25).equals("знакомый")
                && com.abs.livingvillages.domain.TrustAndAnimalModel.trustTierNameRu(45).equals("доверяет")
                && com.abs.livingvillages.domain.TrustAndAnimalModel.trustTierNameRu(65).equals("друг")
                && com.abs.livingvillages.domain.TrustAndAnimalModel.trustTierNameRu(85).equals("близкий человек"),
            "All 5 trust tiers (0-19, 20-39, 40-59, 60-79, 80-100) must match exact Russian labels.");

        VillagerSoul.setPlayerTrust(citizen, playerUuid, 15);
        int afterPreferredGift = VillagerSoul.recordTrustEvent(
            citizen,
            playerUuid,
            com.abs.livingvillages.domain.TrustAndAnimalModel.EVENT_HELP_OR_GIFT,
            10,
            true
        );
        int afterDefense = VillagerSoul.recordTrustEvent(
            citizen,
            playerUuid,
            com.abs.livingvillages.domain.TrustAndAnimalModel.EVENT_VILLAGE_DEFENSE,
            12,
            false
        );
        int afterRequest = VillagerSoul.recordTrustEvent(
            citizen,
            playerUuid,
            com.abs.livingvillages.domain.TrustAndAnimalModel.EVENT_REQUEST_FULFILLED,
            15,
            true
        );
        helper.assertTrue(
            afterPreferredGift > 20
                && afterDefense > afterPreferredGift
                && afterRequest >= 60
                && VillagerSoul.formatTrustStatus(afterRequest).contains("друг")
                && com.abs.livingvillages.domain.TrustAndAnimalModel.unlockedInteractionCount(85) == 5
                && com.abs.livingvillages.domain.TrustAndAnimalModel.computeTrustPriceOffset(85, 0) < 0,
            "Trust must increase for preferred gifts, village defense, and requests, unlocking interactions and discounts.");

        // Verify trust persistence across NBT save/copy/restore and theft penalty.
        net.minecraft.nbt.CompoundTag savedRoot = citizen.getPersistentData().copy();
        Villager restoredCitizen = helper.spawn(EntityType.VILLAGER, 4, 1, 1);
        restoredCitizen.setNoAi(true);
        restoredCitizen.getPersistentData().put(
            VillagerSoul.SOUL_KEY,
            savedRoot.getCompound(VillagerSoul.SOUL_KEY).copy()
        );
        int persistedTrust = VillagerSoul.getPlayerTrust(restoredCitizen, playerUuid);
        VillagerSoul.recordPlayerTheft(helper.getLevel(), restoredCitizen.blockPosition(), playerUuid);
        int afterTheft = VillagerSoul.getPlayerTrust(restoredCitizen, playerUuid);
        helper.assertTrue(
            persistedTrust == afterRequest && afterTheft < persistedTrust,
            "Villager trust must persist across NBT serialization and decrease after theft.");

        // 2. Animal Herd Reaction & Species-Specific Flight.
        net.minecraft.world.entity.animal.Sheep sheepA = helper.spawn(EntityType.SHEEP, 10, 1, 1);
        net.minecraft.world.entity.animal.Sheep sheepB = helper.spawn(EntityType.SHEEP, 12, 1, 1);
        sheepA.setNoAi(true);
        sheepB.setNoAi(true);
        int herdAlerted = VillageAnimalAI.alertHerdOnDanger(
            sheepA,
            helper.getLevel(),
            helper.absolutePos(new BlockPos(9, 1, 1)),
            200L
        );
        int sheepAction = VillageAnimalAI.evaluateAndApplyAnimalBehavior(sheepB, helper.getLevel(), 210L);
        helper.assertTrue(
            herdAlerted >= 1
                && sheepAction == com.abs.livingvillages.domain.TrustAndAnimalModel.ANIMAL_ACTION_FLEE_THREAT,
            "Attacking one animal must alert nearby herd members and trigger species flight.");

        // 3. Parent-Offspring Interaction (Baby Following & Parental Protection) & Breeding/Maturation.
        net.minecraft.world.entity.animal.Cow parentCowA = helper.spawn(EntityType.COW, 20, 1, 1);
        net.minecraft.world.entity.animal.Cow parentCowB = helper.spawn(EntityType.COW, 21, 1, 1);
        net.minecraft.world.entity.animal.Cow calf = helper.spawn(EntityType.COW, 22, 1, 1);
        parentCowA.setNoAi(true);
        parentCowB.setNoAi(true);
        calf.setNoAi(true);
        calf.setAge(-50); // Young calf close to maturation

        helper.assertTrue(
            VillageAnimalAI.isValidSpeciesBreedingFood(parentCowA, new ItemStack(Items.WHEAT))
                && !VillageAnimalAI.isValidSpeciesBreedingFood(parentCowA, new ItemStack(Items.ROTTEN_FLESH)),
            "Breeding food check must validate species-appropriate feed.");

        parentCowA.setInLove(null);
        parentCowB.setInLove(null);
        boolean courtship = VillageAnimalAI.performPreBreedingCourtship(
            parentCowA,
            helper.getLevel(),
            parentCowA.getBoundingBox().inflate(6.0D, 3.0D, 6.0D)
        );
        int offspringTrait = VillageAnimalAI.registerOffspringFromParents(parentCowA, parentCowB, calf, 300L);
        parentCowA.resetLove();
        parentCowB.resetLove();

        int calfCalmAction = VillageAnimalAI.evaluateAndApplyAnimalBehavior(calf, helper.getLevel(), 310L);
        helper.assertTrue(
            courtship
                && offspringTrait >= 1 && offspringTrait <= 4
                && calfCalmAction == com.abs.livingvillages.domain.TrustAndAnimalModel.ANIMAL_ACTION_FOLLOW_PARENT
                && !calf.isBaby(),
            "Parents must perform pre-breeding courtship, pass an offspring trait, and calves following parents must mature.");

        // Re-set calf to baby and spawn a threat nearby to verify parental protection.
        calf.setAge(-2000);
        net.minecraft.world.entity.monster.Zombie predator = helper.spawn(EntityType.ZOMBIE, 23, 1, 1);
        predator.setNoAi(true);
        int parentProtectAction = VillageAnimalAI.evaluateAndApplyAnimalBehavior(parentCowA, helper.getLevel(), 320L);
        helper.assertTrue(
            parentProtectAction == com.abs.livingvillages.domain.TrustAndAnimalModel.ANIMAL_ACTION_PROTECT_OFFSPRING,
            "Adult herd parent must select ANIMAL_ACTION_PROTECT_OFFSPRING when a threat approaches its calf.");
        predator.discard();

        // 4. Pillager Crossbow Reload Movement & Zombie Group Coordination.
        net.minecraft.world.entity.monster.Pillager pillager = helper.spawn(EntityType.PILLAGER, 26, 1, 1);
        pillager.setNoAi(true);
        pillager.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
        helper.assertTrue(
            VillageMonsterAI.isPillagerReloadingCrossbow(pillager)
                && com.abs.livingvillages.domain.MonsterTacticalModel.selectPillagerCombatAction(49.0D, true, true, 1)
                    == com.abs.livingvillages.domain.MonsterTacticalModel.ACTION_RELOAD_STRAFE,
            "A Pillager with an uncharged crossbow must select ACTION_RELOAD_STRAFE to reposition while reloading.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60, batch = "livestock_care_trust_and_elder_livestock_summary")
    public static void interactiveRequestPreferencesLivestockCareTrustAndElderLivestockSummaryWorkInWorld(GameTestHelper helper) {
        for (int x = 0; x <= 30; x++) {
            for (int z = 0; z <= 2; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
            }
        }

        BlockPos homeA = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos homeB = helper.absolutePos(new BlockPos(4, 1, 1));
        Villager residentA = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        Villager residentB = helper.spawn(EntityType.VILLAGER, 4, 1, 1);
        residentA.setNoAi(true);
        residentB.setNoAi(true);
        // Stage 1: Assign regular vanilla professions (FARMER and FLETCHER) and verify they are eligible for autonomous development.
        residentA.setVillagerData(residentA.getVillagerData().setProfession(net.minecraft.world.entity.npc.VillagerProfession.FARMER));
        residentB.setVillagerData(residentB.getVillagerData().setProfession(net.minecraft.world.entity.npc.VillagerProfession.FLETCHER));
        residentA.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeA));
        residentB.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), homeB));
        helper.assertTrue(
            VillagerRoles.isEligibleAutonomousAdult(residentA) && VillagerRoles.isEligibleAutonomousAdult(residentB),
            "Stage 1: Villagers with vanilla professions (FARMER, FLETCHER) must be eligible for autonomous settlement tasks.");

        // Stage 2, 3 & 4: On an isolated elevated platform at (15, 45, 1) in the same chunk (>32 blocks above y=1),
        // verify: gather logs -> bootstrap first storage chest -> deposit logs -> craft >96 planks -> build crafting table & bake bread.
        BlockPos skyCenter = helper.absolutePos(new BlockPos(15, 45, 1));
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.getLevel().setBlock(skyCenter.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                helper.getLevel().setBlock(skyCenter.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 3);
                helper.getLevel().setBlock(skyCenter.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        // Place short grass around skyCenter to prove replaceable ground cover never blocks chest/table placement.
        helper.getLevel().setBlock(skyCenter.east(), Blocks.SHORT_GRASS.defaultBlockState(), 3);
        Villager skyWorker = EntityType.VILLAGER.create(helper.getLevel());
        Villager skyPartner = EntityType.VILLAGER.create(helper.getLevel());
        helper.assertTrue(skyWorker != null && skyPartner != null, "Sky test villagers must be created.");
        skyWorker.moveTo(skyCenter.getX() + 0.5D, skyCenter.getY(), skyCenter.getZ() + 0.5D, 0.0F, 0.0F);
        skyPartner.moveTo(skyCenter.getX() + 2.5D, skyCenter.getY(), skyCenter.getZ() + 0.5D, 0.0F, 0.0F);
        skyWorker.setNoAi(true);
        skyPartner.setNoAi(true);
        skyWorker.setVillagerData(skyWorker.getVillagerData().setProfession(net.minecraft.world.entity.npc.VillagerProfession.FLETCHER));
        skyPartner.setVillagerData(skyPartner.getVillagerData().setProfession(net.minecraft.world.entity.npc.VillagerProfession.FARMER));
        skyWorker.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), skyCenter));
        skyPartner.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), skyCenter.east(2)));
        helper.getLevel().addFreshEntity(skyWorker);
        helper.getLevel().addFreshEntity(skyPartner);
        skyWorker.getInventory().addItem(new ItemStack(Items.OAK_LOG, 8));

        VillageIndustry.tickArea(helper.getLevel(), skyCenter);

        BlockPos bootstrappedChestPos = null;
        for (BlockPos p : BlockPos.betweenClosed(skyCenter.offset(-5, -1, -2), skyCenter.offset(5, 1, 2))) {
            if (helper.getLevel().getBlockState(p).is(Blocks.CHEST)) {
                bootstrappedChestPos = p.immutable();
                break;
            }
        }
        helper.assertTrue(
            bootstrappedChestPos != null,
            "Stage 4: A villager carrying logs must bootstrap and place the settlement's first Chest even when vanilla-employed and surrounded by grass.");
        Container firstChest = (Container) helper.getLevel().getBlockEntity(bootstrappedChestPos);
        int depositedLogs = VillageLogistics.depositCarriedMaterials(skyWorker, firstChest, 100L);
        helper.assertTrue(
            depositedLogs >= 6 && countItem(firstChest, Items.OAK_LOG) >= 6,
            "Stage 3: Logistics must deposit remaining harvested logs from a vanilla-profession villager into the new chest.");

        // Add more logs and raw wheat to verify industry builds a Crafting Table, converts >96 planks (no 24-plank cap), and bakes Bread.
        firstChest.setItem(1, new ItemStack(Items.OAK_LOG, 32));
        firstChest.setItem(2, new ItemStack(Items.WHEAT, 9));
        for (int i = 0; i < 8; i++) {
            VillageIndustry.tickArea(helper.getLevel(), skyCenter);
        }
        boolean hasWorkshopTable = false;
        for (BlockPos p : BlockPos.betweenClosed(skyCenter.offset(-5, -1, -2), skyCenter.offset(5, 1, 2))) {
            if (helper.getLevel().getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                hasWorkshopTable = true;
                break;
            }
        }
        int craftedPlanksInChest = countItem(firstChest, Items.OAK_PLANKS);
        int bakedBreadInChest = countItem(firstChest, Items.BREAD);
        helper.assertTrue(
            hasWorkshopTable && craftedPlanksInChest >= 96 && bakedBreadInChest >= 3,
            "Stages 5 & 6: Industry must build a Crafting Table, convert logs beyond the old 24-plank cap (got "
                + craftedPlanksInChest + "), and bake Wheat into Bread (got " + bakedBreadInChest + ").");

        // Stages 9 & 10: Place 3 beds on the sky platform for 2 fed villagers and verify natural breeding preparation.
        for (int b = 0; b < 3; b++) {
            BlockPos foot = skyCenter.offset(-5 + b * 2, 0, -1);
            BlockState footState = Blocks.RED_BED.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.FOOT);
            helper.getLevel().setBlock(foot, footState, 3);
            helper.getLevel().setBlock(foot.south(), footState.setValue(BedBlock.PART, BedPart.HEAD), 3);
            VillageRegistry.isBedForVillage(helper.getLevel(), skyCenter, foot);
        }
        skyWorker.getInventory().addItem(new ItemStack(Items.BREAD, 3));
        skyPartner.getInventory().addItem(new ItemStack(Items.BREAD, 3));
        helper.getLevel().setDayTime(2000L);
        VillagePopulation.tickArea(helper.getLevel(), skyCenter, List.of(skyWorker, skyPartner));
        boolean pairedForBreeding = skyWorker.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET)
            && skyPartner.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET);
        skyWorker.discard();
        skyPartner.discard();
        helper.assertTrue(
            pairedForBreeding,
            "Stages 9 & 10: Fed adult villagers with spare beds must pair up with BREED_TARGET for natural breeding.");

        java.util.UUID caretakerUuid = java.util.UUID.nameUUIDFromBytes("livestock-caretaker".getBytes());
        VillagerSoul.setPlayerTrust(residentA, caretakerUuid, 20);
        VillagerSoul.assignVillagerRequest(residentA, "apple");

        helper.assertTrue(
            !VillagerSoul.describePreferredGift(0).isBlank()
                && VillagerSoul.describeRequestItem("apple").equals("Яблоко"),
            "Preferred gift and personal request descriptions must be localized.");

        // Spawn an adult cow and a baby calf near the villagers and verify livestock care trust bonus + status.
        net.minecraft.world.entity.animal.Cow adultCow = helper.spawn(EntityType.COW, 5, 1, 1);
        net.minecraft.world.entity.animal.Cow babyCow = helper.spawn(EntityType.COW, 6, 1, 1);
        adultCow.setNoAi(true);
        babyCow.setNoAi(true);
        babyCow.setAge(-1200);
        VillageAnimalAI.registerOffspringFromParents(adultCow, adultCow, babyCow, 100L);

        int affectedVillagers = VillageAnimalAI.recordLivestockCareTrustBonus(
            helper.getLevel(),
            adultCow.blockPosition(),
            caretakerUuid
        );
        int updatedTrust = VillagerSoul.getPlayerTrust(residentA, caretakerUuid);
        String animalStatus = VillageAnimalAI.describeAnimalStatus(babyCow);
        helper.assertTrue(
            affectedVillagers >= 1
                && updatedTrust > 20
                && animalStatus.contains("Детёныш"),
            "Feeding village livestock must grant a trust bonus with nearby villagers and describe baby animal status.");

        // Elect village elder and verify livestock summary + diagnostics appear in the Elder panel snapshot.
        List<Villager> villagers = List.of(residentA, residentB);
        VillageElder.ensureElder(helper.getLevel(), homeA, villagers);
        VillagerRoles.assignRoles(helper.getLevel(), homeA);
        Villager elder = VillageElder.isElder(residentA) ? residentA : residentB;
        var panel = VillageElder.createPanelSnapshot(helper.getLevel(), elder, "");
        helper.assertTrue(
            panel.notice().contains("Скот:") && panel.notice().contains("Диагностика:"),
            "Elder panel snapshot must include both livestock summary and live development diagnostics.");

        // Stages 7 & 8: Verify desert 4:1 Sand -> Sandstone compression, all 16 bed colors, biome structural blocks, and house project start.
        BlockPos desertChestPos = helper.absolutePos(new BlockPos(8, 1, 1));
        helper.setBlock(new BlockPos(8, 1, 1), Blocks.CHEST);
        VillageRegistry.isStorageForVillage(helper.getLevel(), homeA, desertChestPos);
        Container desertChest = (Container) helper.getLevel().getBlockEntity(desertChestPos);
        helper.assertTrue(desertChest != null, "Desert test chest must exist.");

        desertChest.setItem(0, new ItemStack(Items.SAND, 64));
        int compressedSandstone = VillageIndustry.compressSandToSandstoneIfEligible(desertChest);
        helper.assertTrue(
            compressedSandstone == 16
                && countItem(desertChest, Items.SAND) == 0
                && countItem(desertChest, Items.SANDSTONE) == 16,
            "64 Sand must compress 4:1 into 16 Sandstone without creating or losing materials.");

        desertChest.clearContent();
        desertChest.setItem(0, new ItemStack(Items.SANDSTONE, 64));
        desertChest.setItem(1, new ItemStack(Items.SANDSTONE, 32));
        desertChest.setItem(2, new ItemStack(Items.YELLOW_BED, 1));
        desertChest.setItem(3, new ItemStack(Items.ACACIA_DOOR, 1));

        Item selectedBed = VillageBuilder.selectAvailableBedItem(List.of(desertChest));
        Item selectedDoor = VillageBuilder.selectAvailableDoorItem(Blocks.SANDSTONE, List.of(desertChest));
        helper.assertTrue(
            selectedBed == Items.YELLOW_BED
                && selectedDoor == Items.ACACIA_DOOR
                && VillageBuilder.isSupportedBedItem(Items.BLUE_BED)
                && VillageBuilder.isSupportedStructuralBlock(Blocks.SANDSTONE)
                && VillageBuilder.isSupportedStructuralBlock(Blocks.STONE_BRICKS)
                && VillageBuilder.isSupportedStructuralBlock(Blocks.TERRACOTTA)
                && VillageBuilder.hasMaterialsForHouse(helper.getLevel(), homeA),
            "Builder must support all 16 bed colors, biome structural blocks, and detect house material readiness.");

        BlockPos houseSite = helper.absolutePos(new BlockPos(240, 40, 240));
        boolean startedSandstoneHouse = VillageBuilder.startHouseProject(
            helper.getLevel(),
            homeA,
            houseSite,
            Blocks.SANDSTONE,
            selectedDoor,
            selectedBed
        );
        helper.assertTrue(
            startedSandstoneHouse && (VillageBuilder.isProjectActive(residentA) || VillageBuilder.isProjectActive(residentB)),
            "Stage 7: Settlement with vanilla-employed villagers must auto-promote a builder and start a house project.");
        residentA.getPersistentData().putBoolean(VillageBuilder.ACTIVE_KEY, false);
        residentB.getPersistentData().putBoolean(VillageBuilder.ACTIVE_KEY, false);
        VillageReservations.release(residentA.getPersistentData());
        VillageReservations.release(residentB.getPersistentData());
        desertChest.clearContent();

        // Stages 11 & 12: Verify multi-day XP progression from Level 1 to Level 5 across all 7 roles,
        // efficiency bonuses, profession change, another villager's death, and save/reload persistence.
        String[] allRoles = {
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_LUMBERJACK,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_MINER,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_CRAFTSMAN,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_BUILDER,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_FARMER,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_COURIER,
            com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_GUARD
        };
        long simTick = 1000L;
        int baseStepTicks = VillagerProgression.adjustedStepTicks(
            residentB, com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_BUILDER, 20, 4);
        for (int step = 0; step < 30; step++) {
            simTick += 2400L; // Advance across 5 simulated in-game days (30 * 2400 = 72,000 ticks = 3+ days)
            VillagerProgression.recordCompletedWork(
                residentB,
                com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_BUILDER,
                15,
                true,
                simTick
            );
        }
        for (String role : allRoles) {
            simTick += 200L;
            VillagerProgression.recordCompletedWork(residentB, role, 12, true, simTick);
        }
        int maxLevel = VillagerProgression.getLevel(
            residentB, com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_BUILDER);
        int maxLevelStepTicks = VillagerProgression.adjustedStepTicks(
            residentB, com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_BUILDER, 20, 4);
        // Change vanilla profession (FLETCHER -> MASON) and verify Level 5 and all role skills persist across NBT save/load.
        residentB.setVillagerData(residentB.getVillagerData().setProfession(net.minecraft.world.entity.npc.VillagerProfession.MASON));
        CompoundTag savedVillagerNbt = new CompoundTag();
        residentB.saveWithoutId(savedVillagerNbt);
        Villager reloadedVillager = helper.spawn(EntityType.VILLAGER, 6, 1, 1);
        reloadedVillager.setNoAi(true);
        reloadedVillager.load(savedVillagerNbt);
        int reloadedBuilderLevel = VillagerProgression.getLevel(
            reloadedVillager, com.abs.livingvillages.domain.VillagerProgressionModel.ROLE_BUILDER);
        String inspectableSummary = VillagerProgression.describeAllSkillsAndActivityRu(reloadedVillager);
        reloadedVillager.discard();
        helper.assertTrue(
            maxLevel == 5
                && maxLevelStepTicks < baseStepTicks
                && reloadedBuilderLevel == 5
                && inspectableSummary.contains("Строитель ур. 5/5"),
            "Stages 11 & 12: Villager must reach Level 5 across multi-day work, work faster at Level 5, and preserve XP/levels across profession changes and world save/reload.");

        residentA.getPersistentData().putLong(VillagerRoles.TASK_PROGRESS_TICK_KEY, 100L);
        residentA.getPersistentData().putBoolean("livingvillages_logistics_active", true);
        boolean recoveredStuck = VillagerRoles.recoverIfTaskStuck(residentA, 600L);
        helper.assertTrue(
            recoveredStuck
                && !residentA.getPersistentData().getBoolean("livingvillages_logistics_active")
                && !com.abs.livingvillages.domain.SettlementSimulationModel.canSwitchSpecialistRole(500L, 700L, false, false)
                && com.abs.livingvillages.domain.SettlementSimulationModel.shouldYieldToVanillaVillagerAi(true, false, false, false),
            "Task watchdog must recover stuck workers, enforce role tenure, and yield to vanilla sleep/trade AI.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "resource_demand")
    public static void resourceDemandScalesWorkforceToRealStockAndLocalSources(GameTestHelper helper) {
        var starter = com.abs.livingvillages.domain.VillageResourceDemandModel.evaluate(
            new com.abs.livingvillages.domain.VillageResourceDemandModel.Stock(
                8, 0, 0, 0, 0, 12, 0, 10, true, 2));
        helper.assertTrue(
            starter.needsWood() && starter.needsStone() && starter.needsOre() && starter.needsFood()
                && starter.desiredLumberjacks() == 2
                && starter.desiredMiners() == 2
                && starter.desiredFarmers() == 1,
            "Low physical stock with local trees, ripe crops and tools must request the matching workers.");

        var stocked = com.abs.livingvillages.domain.VillageResourceDemandModel.evaluate(
            new com.abs.livingvillages.domain.VillageResourceDemandModel.Stock(
                8, 256, 160, 32, 256, 0, 0, 0, true, 3));
        helper.assertTrue(
            !stocked.needsWood() && !stocked.needsStone() && !stocked.needsOre() && !stocked.needsFood()
                && stocked.desiredLumberjacks() == 0
                && stocked.desiredMiners() == 0
                && stocked.desiredFarmers() == 0,
            "Workers must not keep extracting resources after stock reaches its targets.");

        var treeless = com.abs.livingvillages.domain.VillageResourceDemandModel.evaluate(
            new com.abs.livingvillages.domain.VillageResourceDemandModel.Stock(
                8, 0, 0, 0, 0, 0, 0, 0, false, 2));
        helper.assertTrue(
            !treeless.needsWood() && treeless.desiredLumberjacks() == 0,
            "A treeless settlement must not assign lumberjacks to a resource source that is not present.");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "farmer_field_cycle")
    public static void farmerPlantsEmptyOwnedFarmlandFromRealSeedsInBoundedBatches(GameTestHelper helper) {
        BlockPos[] fields = {
            new BlockPos(2, 1, 2), new BlockPos(3, 1, 2), new BlockPos(4, 1, 2),
            new BlockPos(2, 1, 3), new BlockPos(3, 1, 3)
        };
        for (BlockPos field : fields) {
            helper.setBlock(field.below(), Blocks.FARMLAND);
            helper.setBlock(field, Blocks.AIR);
        }

        Villager farmer = helper.spawn(EntityType.VILLAGER, 4, 1, 4);
        farmer.setNoAi(true);
        farmer.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 5));

        boolean firstPass = VillagerAutonomy.tryHarvestMatureCropAndBakeBread(
            farmer, helper.getLevel(), 100L);
        int plantedAfterFirst = 0;
        for (BlockPos field : fields) {
            if (helper.getLevel().getBlockState(helper.absolutePos(field)).is(Blocks.WHEAT)) plantedAfterFirst++;
        }
        helper.assertTrue(firstPass && plantedAfterFirst == 4,
            "A farmer must plant up to four empty owned farmland blocks in one pass using real seeds.");

        boolean secondPass = VillagerAutonomy.tryHarvestMatureCropAndBakeBread(
            farmer, helper.getLevel(), 200L);
        int plantedAfterSecond = 0;
        for (BlockPos field : fields) {
            if (helper.getLevel().getBlockState(helper.absolutePos(field)).is(Blocks.WHEAT)) plantedAfterSecond++;
        }
        helper.assertTrue(secondPass && plantedAfterSecond == 5
            && countItem(farmer.getInventory(), Items.WHEAT_SEEDS) == 0,
            "The next pass must finish the field and consume exactly the real seeds used for planting.");

        BlockPos protectedField = new BlockPos(7, 1, 7);
        helper.setBlock(protectedField.below(), Blocks.FARMLAND);
        helper.setBlock(protectedField, Blocks.AIR);
        Villager farmerWithoutSeeds = helper.spawn(EntityType.VILLAGER, 7, 1, 6);
        farmerWithoutSeeds.setNoAi(true);
        VillagerAutonomy.tryHarvestMatureCropAndBakeBread(farmerWithoutSeeds, helper.getLevel(), 300L);
        helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(protectedField)).isAir(),
            "A farmer without real seeds or a seed source must leave empty farmland untouched.");
        var fieldNeedsPlanting = com.abs.livingvillages.domain.VillageResourceDemandModel.evaluate(
            new com.abs.livingvillages.domain.VillageResourceDemandModel.Stock(
                8, 0, 0, 0, 0, 0, 16, 24, false, 2));
        helper.assertTrue(fieldNeedsPlanting.needsFood()
                && fieldNeedsPlanting.plantableFarmland() == 16
                && fieldNeedsPlanting.availableSeeds() == 24
                && fieldNeedsPlanting.desiredFarmers() == 1,
            "A village with empty farmland and real stored seeds must still assign a farmer.");

        var fieldWithoutSeeds = com.abs.livingvillages.domain.VillageResourceDemandModel.evaluate(
            new com.abs.livingvillages.domain.VillageResourceDemandModel.Stock(
                8, 0, 0, 0, 0, 0, 16, 0, false, 2));
        helper.assertTrue(fieldWithoutSeeds.desiredFarmers() == 0,
            "An empty field with no actual seeds must not create an idle farmer job.");
        farmer.discard();
        farmerWithoutSeeds.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "multi_block_work")
    public static void workersProcessConnectedTreesOreVeinsAndWholeRipeCropBatches(GameTestHelper helper) {
        // A connected five-log tree and one nearby but disconnected building beam.
        BlockPos[] treeLogs = {
            new BlockPos(1, 1, 1), new BlockPos(1, 2, 1), new BlockPos(1, 3, 1),
            new BlockPos(2, 3, 1), new BlockPos(2, 4, 1)
        };
        for (BlockPos pos : treeLogs) helper.setBlock(pos, Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(6, 1, 1), Blocks.OAK_LOG);
        long[] treeQueue = VillagerTreeWork.collectConnectedTreeLogs(
            helper.getLevel(), helper.absolutePos(treeLogs[0]));
        helper.assertTrue(treeQueue.length == 5,
            "A lumberjack job must snapshot every log in the connected tree, without taking a nearby separate log.");

        // Three connected iron ore blocks next to a different ore type.
        BlockPos[] ironVein = {new BlockPos(8, 1, 1), new BlockPos(9, 1, 1), new BlockPos(10, 1, 1)};
        for (BlockPos pos : ironVein) helper.setBlock(pos, Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(11, 1, 1), Blocks.COAL_ORE);
        long[] oreQueue = VillagerMining.collectOreVein(
            helper.getLevel(), helper.absolutePos(ironVein[0]));
        helper.assertTrue(oreQueue.length == 3,
            "A miner must queue the connected iron vein, not stop after one ore or switch to an adjacent coal vein.");

        // Five mature wheat crops: the first bounded pass harvests four, the next finishes the field.
        BlockPos[] crops = {
            new BlockPos(5, 1, 4), new BlockPos(6, 1, 4), new BlockPos(7, 1, 4),
            new BlockPos(5, 1, 5), new BlockPos(6, 1, 5)
        };
        var ripeWheat = Blocks.WHEAT.defaultBlockState().setValue(
            net.minecraft.world.level.block.CropBlock.AGE,
            net.minecraft.world.level.block.CropBlock.MAX_AGE);
        for (BlockPos crop : crops) {
            helper.setBlock(crop.below(), Blocks.FARMLAND);
            helper.setBlock(crop, ripeWheat);
        }
        Villager farmer = helper.spawn(EntityType.VILLAGER, 7, 1, 5);
        farmer.setNoAi(true);
        farmer.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 10));

        boolean firstPass = VillagerAutonomy.tryHarvestMatureCropAndBakeBread(farmer, helper.getLevel(), 100L);
        int remainingAfterFirst = 0;
        for (BlockPos crop : crops) {
            var state = helper.getLevel().getBlockState(helper.absolutePos(crop));
            if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock block && block.isMaxAge(state)) {
                remainingAfterFirst++;
            }
        }
        helper.assertTrue(firstPass && remainingAfterFirst == 1,
            "One harvest pass must process a batch of ripe crops instead of leaving after the first plant.");

        boolean secondPass = VillagerAutonomy.tryHarvestMatureCropAndBakeBread(farmer, helper.getLevel(), 200L);
        int remainingAfterSecond = 0;
        for (BlockPos crop : crops) {
            var state = helper.getLevel().getBlockState(helper.absolutePos(crop));
            if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock block && block.isMaxAge(state)) {
                remainingAfterSecond++;
            }
        }
        helper.assertTrue(secondPass && remainingAfterSecond == 0,
            "The farmer's harvest cycle must continue until every ripe crop in the field has been processed.");
        farmer.discard();
        helper.succeed();
    }


    @GameTest(template = "empty", timeoutTicks = 40, batch = "work_order_board")
    public static void workOrdersAreDeduplicatedClaimedReservedAndPersisted(GameTestHelper helper) {
        placeFloor(helper);
        Villager owner = helper.spawn(EntityType.VILLAGER, 0, 1, 1);
        Villager challenger = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        BlockPos center = owner.blockPosition();
        long now = helper.getLevel().getGameTime();

        var requirement = new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement(
            "minecraft:oak_planks", 32);
        boolean firstQueued = VillageWorkOrders.enqueuePendingProject(
            helper.getLevel(), center, "house", 55);
        boolean markedWaiting = VillageWorkOrders.markPendingProjectWaiting(
            helper.getLevel(), center, "house", now);
        var first = VillageWorkOrders.list(helper.getLevel(), center, now).stream()
            .filter(order -> order.kind().equals("house") && order.dedupeKey().equals("house"))
            .findFirst().orElse(null);
        boolean reopened = VillageWorkOrders.markPendingProjectOpen(
            helper.getLevel(), center, "house", now + 1L);
        boolean returnedToWaiting = VillageWorkOrders.markPendingProjectWaiting(
            helper.getLevel(), center, "house", now + 2L);
        boolean duplicateQueued = VillageWorkOrders.enqueuePendingProject(
            helper.getLevel(), center, "house", 55);
        var duplicate = VillageWorkOrders.list(helper.getLevel(), center, now + 3L).stream()
            .filter(order -> order.kind().equals("house") && order.dedupeKey().equals("house"))
            .findFirst().orElse(null);
        var adopted = VillageWorkOrders.enqueue(
            helper.getLevel(), center, "house", "house", 55, now + 4L, 5000L, List.of(requirement));
        helper.assertTrue(firstQueued && markedWaiting && reopened && returnedToWaiting && duplicateQueued
                && first != null && duplicate != null && first.id().equals(duplicate.id())
                && adopted != null && first.id().equals(adopted.id())
                && adopted.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.WAITING_FOR_RESOURCES
                && adopted.requirements().size() == 1
                && adopted.requirements().get(0).count() == 32,
            "An elder's pending order must stay unique, report resource waits, reopen when materials are present but space/worker is missing, and then adopt its material bill.");

        var ready = VillageWorkOrders.setState(
            helper.getLevel(), center, first.id(),
            com.abs.livingvillages.domain.VillageWorkOrderModel.READY, now + 2L);
        var claimed = VillageWorkOrders.claim(
            helper.getLevel(), center, first.id(), owner.getUUID(), now + 3L, 40L);
        var competingClaim = VillageWorkOrders.claim(
            helper.getLevel(), center, first.id(), challenger.getUUID(), now + 3L, 40L);
        helper.assertTrue(ready != null && claimed != null && competingClaim == null,
            "Only one worker may claim a ready settlement order.");

        var started = VillageWorkOrders.beginWork(
            helper.getLevel(), center, first.id(), owner.getUUID(), now + 4L, 40L);
        Map<String, Integer> reserved = VillageWorkOrders.reservedItemCounts(
            helper.getLevel(), center, null, now + 4L);
        helper.assertTrue(started != null && reserved.getOrDefault("minecraft:oak_planks", 0) == 32,
            "An in-progress order must reserve its real material quantities in the ledger.");

        boolean insufficient = com.abs.livingvillages.domain.VillageWorkOrderModel.canReserve(
            List.of(new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement(
                "minecraft:oak_planks", 33)),
            Map.of("minecraft:oak_planks", 64), reserved);
        boolean sufficient = com.abs.livingvillages.domain.VillageWorkOrderModel.canReserve(
            List.of(new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement(
                "minecraft:oak_planks", 32)),
            Map.of("minecraft:oak_planks", 64), reserved);
        helper.assertTrue(!insufficient && sufficient,
            "The quantity ledger must prevent over-reserving physical stock while allowing a fully covered bill.");

        CompoundTag saved = VillageWorkOrders.get(helper.getLevel()).save(
            new CompoundTag(), helper.getLevel().registryAccess());
        VillageWorkOrders restored = VillageWorkOrders.load(saved, helper.getLevel().registryAccess());
        CompoundTag roundTrip = restored.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(roundTrip.getList("orders", Tag.TAG_COMPOUND).size() == 1,
            "The village work-order board must survive an NBT save/load round trip.");

        var released = VillageWorkOrders.releaseExpiredClaim(
            helper.getLevel(), center, first.id(), now + 44L);
        helper.assertTrue(released != null
                && released.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.READY
                && released.workerId() == null,
            "An expired worker lease must return an abandoned order to READY and clear its owner.");

        var reclaimed = VillageWorkOrders.claim(
            helper.getLevel(), center, first.id(), challenger.getUUID(), now + 45L, 40L);
        var restarted = VillageWorkOrders.beginWork(
            helper.getLevel(), center, first.id(), challenger.getUUID(), now + 46L, 40L);
        var completed = VillageWorkOrders.setState(
            helper.getLevel(), center, first.id(),
            com.abs.livingvillages.domain.VillageWorkOrderModel.COMPLETED, now + 47L);
        helper.assertTrue(reclaimed != null && restarted != null && completed != null
                && completed.state() == com.abs.livingvillages.domain.VillageWorkOrderModel.COMPLETED
                && !com.abs.livingvillages.domain.VillageWorkOrderModel.canTransition(
                    completed.state(), com.abs.livingvillages.domain.VillageWorkOrderModel.READY),
            "A replacement worker may finish a released order, and terminal orders cannot be reopened.");
        owner.discard();
        challenger.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "work_order_material_demand")
    public static void pendingWorkOrderDemandSubtractsStorageAndCourierCargo(GameTestHelper helper) {
        var house = new com.abs.livingvillages.domain.VillageWorkOrderModel.Order(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "house", "house",
            55, 0L, 0L, 10000L,
            com.abs.livingvillages.domain.VillageWorkOrderModel.WAITING_FOR_RESOURCES,
            null, 0L,
            List.of(
                new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement("minecraft:oak_planks", 96),
                new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement("minecraft:red_bed", 1)
            )
        );
        var farm = new com.abs.livingvillages.domain.VillageWorkOrderModel.Order(
            java.util.UUID.randomUUID(), house.villageId(), "farm", "farm",
            60, 0L, 0L, 10000L,
            com.abs.livingvillages.domain.VillageWorkOrderModel.OPEN,
            null, 0L,
            List.of(
                new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement("minecraft:wheat_seeds", 24),
                new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement("minecraft:water_bucket", 1),
                new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement("minecraft:composter", 1),
                new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement("minecraft:wooden_hoe", 1)
            )
        );
        var alreadyStarted = new com.abs.livingvillages.domain.VillageWorkOrderModel.Order(
            java.util.UUID.randomUUID(), house.villageId(), "house", "other-house",
            50, 0L, 0L, 10000L,
            com.abs.livingvillages.domain.VillageWorkOrderModel.IN_PROGRESS,
            java.util.UUID.randomUUID(), 9000L,
            List.of(new com.abs.livingvillages.domain.VillageWorkOrderModel.Requirement("minecraft:oak_planks", 48))
        );

        Map<String, Integer> missing = com.abs.livingvillages.domain.VillageWorkOrderModel.unmetRequirements(
            List.of(house, farm, alreadyStarted),
            Map.of("minecraft:oak_planks", 60, "minecraft:red_bed", 1, "minecraft:wheat_seeds", 8),
            Map.of("minecraft:oak_planks", 12, "minecraft:wheat_seeds", 4)
        );
        helper.assertTrue(
            missing.size() == 5
                && missing.getOrDefault("minecraft:oak_planks", 0) == 24
                && missing.getOrDefault("minecraft:wheat_seeds", 0) == 12
                && missing.getOrDefault("minecraft:water_bucket", 0) == 1
                && missing.getOrDefault("minecraft:composter", 0) == 1
                && missing.getOrDefault("minecraft:wooden_hoe", 0) == 1
                && !missing.containsKey("minecraft:red_bed"),
            "Courier demand must subtract accessible chest stock and in-transit cargo, ignore started orders, and include farm inputs."
        );

        helper.assertTrue(
            com.abs.livingvillages.domain.VillageWorkOrderModel.missingInputCount(96, 20, 15, 4) == 4
                && com.abs.livingvillages.domain.VillageWorkOrderModel.missingInputCount(96, 20, 19, 4) == 0
                && VillageIndustry.logItemForPlanks(Items.OAK_PLANKS) == Items.OAK_LOG
                && VillageIndustry.logItemForPlanks(Items.BIRCH_PLANKS) == Items.BIRCH_LOG,
            "Queued plank demand must translate to the correct log species and account for four planks per log."
        );
        helper.assertTrue(
            VillagerMining.quarryBlockProduces(Blocks.STONE.defaultBlockState(), Items.COBBLESTONE)
                && VillagerMining.quarryBlockProduces(Blocks.SANDSTONE.defaultBlockState(), Items.SANDSTONE)
                && !VillagerMining.quarryBlockProduces(Blocks.RED_SANDSTONE.defaultBlockState(), Items.SANDSTONE),
            "A miner must select quarry blocks that produce the exact item requested by the work order."
        );

        Map<String, Integer> fulfilled = com.abs.livingvillages.domain.VillageWorkOrderModel.unmetRequirements(
            List.of(house, farm),
            Map.of(
                "minecraft:oak_planks", 96,
                "minecraft:red_bed", 1,
                "minecraft:wheat_seeds", 24,
                "minecraft:water_bucket", 1,
                "minecraft:composter", 1,
                "minecraft:wooden_hoe", 1
            ),
            Map.of()
        );
        helper.assertTrue(fulfilled.isEmpty(),
            "A queued project with its full bill in accessible storage must not request extra deliveries.");
        helper.succeed();
    }

    private static int countItem(Container container, Item item) {
        if (container == null || item == null) return 0;
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void placeFloor(GameTestHelper helper) {
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }
}
