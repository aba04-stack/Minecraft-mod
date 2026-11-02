package com.abs.livingvillages.domain;

/**
 * Platform-independent port interfaces isolating the domain core from Minecraft and NeoForge APIs
 * (Stage 1.3 — {@code VERSION_ADAPTERS.md}).
 *
 * <p>Contains zero {@code net.minecraft.*} or {@code net.neoforged.*} imports.</p>
 */
public final class SettlementPorts {
    private SettlementPorts() {}

    /** Port for querying loaded world state and settlement-owned infrastructure. */
    public interface WorldAccessPort {
        boolean isPositionLoaded(int x, int y, int z);

        boolean isVillageAlarmActive(int x, int y, int z, long gameTime);

        boolean hasOwnedFurnaceNear(int x, int y, int z);
    }

    /** Port for reading and mutating physical item storage without exposing platform containers. */
    public interface StorageAccessPort {
        int countItem(int x, int y, int z, String itemId);

        int availableRoomForItem(int x, int y, int z, String itemId);

        boolean transferItem(
            int fromX, int fromY, int fromZ,
            int toX, int toY, int toZ,
            String itemId, int count
        );
    }

    /** Port for controlling autonomous agents (villagers or tactical mobs) by abstract ID. */
    public interface AgentControlPort {
        boolean isAgentAlive(String agentId);

        boolean navigateTo(String agentId, int x, int y, int z, double speedModifier);

        void clearNavigation(String agentId);
    }

    /** Port for reading and writing versioned settlement persistence records. */
    public interface PersistencePort {
        int schemaVersion();

        int readDiplomaticStanding(int ax, int ay, int az, int bx, int by, int bz);

        void writeDiplomaticStanding(int ax, int ay, int az, int bx, int by, int bz, int standing);
    }

    /**
     * Evaluates honest catch-up smelting strictly through the abstract {@link WorldAccessPort}
     * and {@link StorageAccessPort} contracts.
     */
    public static SettlementSimulationModel.CatchUpResult evaluateCatchUpViaPorts(
        WorldAccessPort world,
        StorageAccessPort storage,
        int x,
        int y,
        int z,
        long elapsedTicks,
        String rawOreItemId,
        String coalItemId,
        String ingotItemId
    ) {
        if (world == null || storage == null) {
            return new SettlementSimulationModel.CatchUpResult(0, 0, 0);
        }
        if (!world.isPositionLoaded(x, y, z) || !world.hasOwnedFurnaceNear(x, y, z)) {
            return new SettlementSimulationModel.CatchUpResult(0, 0, 0);
        }
        int ore = storage.countItem(x, y, z, rawOreItemId);
        int coal = storage.countItem(x, y, z, coalItemId);
        int room = storage.availableRoomForItem(x, y, z, ingotItemId);
        return SettlementSimulationModel.evaluateFurnaceCatchUp(elapsedTicks, ore, coal, room);
    }
}
