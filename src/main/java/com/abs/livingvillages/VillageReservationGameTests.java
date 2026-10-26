package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
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

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void oneTaskExcludesCompetingTaskUntilItEnds(GameTestHelper helper) {
        placeFloor(helper);
        BlockPos relativeChest = new BlockPos(1, 1, 1);
        BlockPos chest = helper.absolutePos(relativeChest);
        helper.setBlock(relativeChest, Blocks.CHEST);

        Villager owner = helper.spawn(EntityType.VILLAGER, 0, 1, 1);
        Villager challenger = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        owner.getPersistentData().putBoolean(VillageBuilder.ACTIVE_KEY, true);
        challenger.getPersistentData().putBoolean(VillageBuilder.ACTIVE_KEY, true);

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

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void removedChestDoesNotKeepAStaleReservation(GameTestHelper helper) {
        placeFloor(helper);
        BlockPos relativeChest = new BlockPos(1, 1, 1);
        BlockPos chest = helper.absolutePos(relativeChest);
        helper.setBlock(relativeChest, Blocks.CHEST);

        Villager owner = helper.spawn(EntityType.VILLAGER, 0, 1, 1);
        owner.getPersistentData().putBoolean(VillageBuilder.ACTIVE_KEY, true);
        helper.assertTrue(VillageReservations.tryReserveStoragePositions(
            helper.getLevel(), owner, List.of(chest), "house"),
            "The task must initially reserve the chest.");

        // Replace the container as if a player removed it. When loaded, the invalid position
        // should be pruned so a replacement chest at that coordinate can be reserved.
        helper.setBlock(relativeChest, Blocks.AIR);
        helper.assertTrue(VillageReservations.isStorageAvailable(
            helper.getLevel(), chest, null),
            "A removed container must not retain its reservation.");
        helper.setBlock(relativeChest, Blocks.CHEST);
        helper.assertTrue(VillageReservations.tryReserveStoragePositions(
            helper.getLevel(), owner, List.of(chest), "house"),
            "A replacement chest at the old position must be reservable.");
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
