package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lightweight, persistent specialist roles for otherwise unemployed adult villagers.
 * These are behavior roles, not new vanilla professions: villagers keep normal trading,
 * sleeping and pathfinding AI while the mod adds mining or defense jobs.
 */
public final class VillagerRoles {
    public static final String ROLE_KEY = "livingvillages_specialist_role";
    public static final String ROLE_MINER = "miner";
    public static final String ROLE_GUARD = "guard";
    public static final String ROLE_CRAFTSMAN = "craftsman";
    public static final String ROLE_BUILDER = "builder";
    public static final String HOME_X = "livingvillages_specialist_home_x";
    public static final String HOME_Y = "livingvillages_specialist_home_y";
    public static final String HOME_Z = "livingvillages_specialist_home_z";

    private static final String TREE_TASK_ACTIVE = "livingvillages_treework_active";
    private static final String REPLANT_TASK_ACTIVE = "livingvillages_treework_replant_active";

    private VillagerRoles() {
    }

    public static boolean isMiner(Villager villager) {
        return villager.getPersistentData().getString(ROLE_KEY).equals(ROLE_MINER);
    }

    public static boolean isGuard(Villager villager) {
        return villager.getPersistentData().getString(ROLE_KEY).equals(ROLE_GUARD);
    }

    public static boolean isCraftsman(Villager villager) {
        return villager.getPersistentData().getString(ROLE_KEY).equals(ROLE_CRAFTSMAN);
    }

    public static boolean isBuilder(Villager villager) {
        return villager.getPersistentData().getString(ROLE_KEY).equals(ROLE_BUILDER);
    }

    public static boolean isUnemployedAdult(Villager villager) {
        if (!villager.isAlive() || villager.isBaby()) {
            return false;
        }
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT;
    }

    /** Assign roles after reconciling disabled/stale assignments first. */
    public static void assignRoles(ServerLevel level, BlockPos center) {
        assignRolesInternal(level, center, false);
    }

    /** Release stale roles while the elder has paused local automation, without assigning new work. */
    public static void reconcileRoles(ServerLevel level, BlockPos center, boolean reconciliationOnly) {
        assignRolesInternal(level, center, reconciliationOnly);
    }

    private static void assignRolesInternal(ServerLevel level, BlockPos center, boolean reconciliationOnly) {
        int radius = LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get();
        AABB area = new AABB(center).inflate(radius, 12.0D, radius);
        List<Villager> all = level.getEntitiesOfClass(Villager.class, area, Villager::isAlive);

        int availablePickaxes = VillageIndustry.countAvailablePickaxes(level, center);
        int availableSwords = VillageIndustry.countAvailableSwords(level, center);
        boolean workshopExists = LivingVillagesConfig.ENABLE_VILLAGE_INDUSTRY.get()
            && VillageIndustry.hasCraftingTable(level, center);
        boolean housingNeeded = LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()
            && (VillageBuilder.housingNeeded(level, center, all.size()) || VillageElder.hasHouseRequest(all));

        // Config toggles must release a role. Active builder projects are retained while paused,
        // because their real materials and construction progress live on that worker.
        for (Villager villager : all) {
            var data = villager.getPersistentData();
            String role = data.getString(ROLE_KEY);
            if (role.isEmpty()) continue;

            boolean activeBuilderProject = VillageBuilder.isProjectActive(villager);
            boolean activeRoleTask = roleTaskActive(villager, role);
            boolean roleDisabled = switch (role) {
                case ROLE_MINER -> !LivingVillagesConfig.ENABLE_MINER_ROLE.get();
                case ROLE_GUARD -> !LivingVillagesConfig.ENABLE_VILLAGE_GUARDS.get();
                case ROLE_CRAFTSMAN -> !LivingVillagesConfig.ENABLE_VILLAGE_INDUSTRY.get();
                case ROLE_BUILDER -> !LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get() && !activeBuilderProject;
                default -> true;
            };

            boolean missingRequiredTool = (ROLE_MINER.equals(role) && availablePickaxes == 0)
                || (ROLE_GUARD.equals(role) && availableSwords == 0);
            boolean professionChanged = !isUnemployedAdult(villager) && !activeRoleTask && !activeBuilderProject;
            boolean builderNoLongerNeeded = ROLE_BUILDER.equals(role) && !activeBuilderProject && !housingNeeded;

            if (roleDisabled || missingRequiredTool || professionChanged || builderNoLongerNeeded) {
                releaseRole(villager);
            }
        }

        // A global pause stops assigning new work, but the role-specific event handlers decide
        // whether an in-flight job is paused or cancelled without deleting carried resources.
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get() || reconciliationOnly
            || (!VillageElder.isAutomationEnabled(all) && !VillageElder.hasPendingRequest(all))) return;
        if (!LivingVillagesConfig.ENABLE_MINER_ROLE.get()
            && !LivingVillagesConfig.ENABLE_VILLAGE_GUARDS.get()
            && !LivingVillagesConfig.ENABLE_VILLAGE_INDUSTRY.get()
            && !LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()) return;

        long adultPopulation = all.stream().filter(v -> !v.isBaby()).count();
        if (adultPopulation < 2) return;

        int builders = (int) all.stream().filter(VillagerRoles::isBuilder).count();
        int craftspeople = (int) all.stream().filter(VillagerRoles::isCraftsman).count();
        int miners = (int) all.stream().filter(VillagerRoles::isMiner).count();
        int guards = (int) all.stream().filter(VillagerRoles::isGuard).count();

        int desiredBuilders = housingNeeded ? 1 : 0;
        // A tiny village may need a craftsman to make the door/tool needed for the next house.
        int desiredCraftspeople = workshopExists && (adultPopulation >= 4 || housingNeeded) ? 1 : 0;
        int desiredMiners = LivingVillagesConfig.ENABLE_MINER_ROLE.get() && adultPopulation >= 4
            ? Math.min(Math.max(1, (int) adultPopulation / 6), availablePickaxes) : 0;
        int guardDivisor = VillageElder.priorityForVillagers(all) == VillageElder.PRIORITY_DEFENSE ? 5 : 8;
        int desiredGuards = LivingVillagesConfig.ENABLE_VILLAGE_GUARDS.get() && adultPopulation >= 5
            ? Math.min(Math.max(1, (int) adultPopulation / guardDivisor), availableSwords) : 0;

        List<Villager> candidates = new ArrayList<>();
        for (Villager villager : all) {
            if (!isUnemployedAdult(villager) || VillageElder.isElder(villager)) continue;
            var data = villager.getPersistentData();
            if (!data.getString(ROLE_KEY).isEmpty()) continue;
            if (data.getBoolean(TREE_TASK_ACTIVE) || data.getBoolean(REPLANT_TASK_ACTIVE)
                || data.getBoolean("livingvillages_logistics_active")
                || data.getBoolean(VillagePopulation.ACTIVE_KEY)
                || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
                || data.getBoolean(VillageBuilder.ACTIVE_KEY)
                || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
                || data.getBoolean("livingvillages_mining_active")
                || data.getBoolean("livingvillages_defense_has_route")
                || data.getBoolean("livingvillages_industry_route_active")) continue;
            candidates.add(villager);
        }
        candidates.sort(Comparator.comparing(v -> v.getUUID().toString()));
        if (candidates.isEmpty()) return;

        String role = null;
        // Housing and the basic workshop have priority over optional extraction and defense roles.
        if (builders < desiredBuilders) role = ROLE_BUILDER;
        else if (craftspeople < desiredCraftspeople) role = ROLE_CRAFTSMAN;
        else if (miners < desiredMiners) role = ROLE_MINER;
        else if (guards < desiredGuards) role = ROLE_GUARD;
        if (role == null) return;

        Villager selected = null;
        for (Villager candidate : candidates) {
            boolean canStart = ROLE_CRAFTSMAN.equals(role) || ROLE_BUILDER.equals(role)
                || (ROLE_MINER.equals(role) && VillageIndustry.hasPickaxeAccessibleTo(candidate))
                || (ROLE_GUARD.equals(role) && VillageIndustry.hasSwordAccessibleTo(candidate));
            if (canStart) { selected = candidate; break; }
        }
        if (selected == null) return;

        BlockPos home = averageAdultPosition(all, center);
        var data = selected.getPersistentData();
        data.putString(ROLE_KEY, role);
        data.putInt(HOME_X, home.getX());
        data.putInt(HOME_Y, home.getY());
        data.putInt(HOME_Z, home.getZ());
    }

    private static boolean roleTaskActive(Villager villager, String role) {
        var data = villager.getPersistentData();
        return switch (role) {
            case ROLE_MINER -> data.getBoolean("livingvillages_mining_active");
            case ROLE_GUARD -> data.getBoolean("livingvillages_defense_has_route");
            case ROLE_CRAFTSMAN -> data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
                || data.getBoolean("livingvillages_industry_route_active");
            case ROLE_BUILDER -> VillageBuilder.isProjectActive(villager);
            default -> false;
        };
    }

    private static void releaseRole(Villager villager) {
        var data = villager.getPersistentData();
        data.remove(ROLE_KEY);
        data.remove(HOME_X);
        data.remove(HOME_Y);
        data.remove(HOME_Z);
    }

    public static BlockPos getHome(Villager villager) {
        var data = villager.getPersistentData();
        if (!data.contains(HOME_X) || !data.contains(HOME_Y) || !data.contains(HOME_Z)) return null;
        return new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z));
    }

    private static BlockPos averageAdultPosition(List<Villager> villagers, BlockPos fallback) {
        double x = 0.0D, y = 0.0D, z = 0.0D;
        int count = 0;
        for (Villager villager : villagers) {
            if (villager.isBaby()) continue;
            x += villager.getX(); y += villager.getY(); z += villager.getZ();
            count++;
        }
        return count == 0 ? fallback : BlockPos.containing(x / count, y / count, z / count);
    }
}
