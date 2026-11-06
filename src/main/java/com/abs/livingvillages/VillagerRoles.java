package com.abs.livingvillages;

import com.abs.livingvillages.domain.VillageResourceDemandModel;
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
    public static final String ROLE_LUMBERJACK = "lumberjack";
    public static final String ROLE_FARMER = "farmer";
    public static final String HOME_X = "livingvillages_specialist_home_x";
    public static final String HOME_Y = "livingvillages_specialist_home_y";
    public static final String HOME_Z = "livingvillages_specialist_home_z";
    public static final String ROLE_ASSIGNED_TICK_KEY = "livingvillages_role_assigned_tick";
    public static final String TASK_PROGRESS_TICK_KEY = "livingvillages_task_progress_tick";

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

    public static boolean isLumberjack(Villager villager) {
        return villager.getPersistentData().getString(ROLE_KEY).equals(ROLE_LUMBERJACK);
    }

    public static boolean isFarmer(Villager villager) {
        return villager.getPersistentData().getString(ROLE_KEY).equals(ROLE_FARMER);
    }

    public static boolean isUnemployedAdult(Villager villager) {
        if (!villager.isAlive() || villager.isBaby()) {
            return false;
        }
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT;
    }

    /**
     * Any living adult villager can perform autonomous settlement tasks and hold a behavioral
     * specialist role without losing their vanilla VillagerProfession or trading offers.
     */
    public static boolean isEligibleAutonomousAdult(Villager villager) {
        return villager != null && villager.isAlive() && !villager.isBaby();
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
        List<Villager> all = VillageArea.findVillagers(level, center);
        if (all.isEmpty()) return;

        int availablePickaxes = VillageIndustry.countAvailablePickaxes(level, center);
        int availableSwords = VillageIndustry.countAvailableSwords(level, center);
        boolean workshopExists = LivingVillagesConfig.ENABLE_VILLAGE_INDUSTRY.get()
            && VillageIndustry.hasCraftingTable(level, center);
        boolean housingNeeded = LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()
            && (VillageBuilder.housingNeeded(level, center, all.size()) || VillageElder.hasHouseRequest(all));

        VillageResourceDemandModel.Plan resourcePlan = VillageResourcePlanner.getPlan(level, center, all);
        int excessLumberjacks = Math.max(0, (int) all.stream().filter(VillagerRoles::isLumberjack).count()
            - resourcePlan.desiredLumberjacks());
        int excessFarmers = Math.max(0, (int) all.stream().filter(VillagerRoles::isFarmer).count()
            - resourcePlan.desiredFarmers());
        int excessMiners = Math.max(0, (int) all.stream().filter(VillagerRoles::isMiner).count()
            - resourcePlan.desiredMiners());
        long now = level.getGameTime();

        // Disabled or surplus roles are released only when their current task is complete and
        // the minimum tenure elapsed. A worker never abandons a tree, vein, or harvest route mid-task.
        for (Villager villager : all) {
            var data = villager.getPersistentData();
            String role = data.getString(ROLE_KEY);
            if (role.isEmpty()) continue;

            boolean activeBuilderProject = VillageBuilder.isProjectActive(villager);
            boolean roleDisabled = switch (role) {
                case ROLE_MINER -> !LivingVillagesConfig.ENABLE_MINER_ROLE.get();
                case ROLE_GUARD -> !LivingVillagesConfig.ENABLE_VILLAGE_GUARDS.get();
                case ROLE_CRAFTSMAN -> !LivingVillagesConfig.ENABLE_VILLAGE_INDUSTRY.get();
                case ROLE_BUILDER -> !LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get() && !activeBuilderProject;
                case ROLE_LUMBERJACK -> !LivingVillagesConfig.ENABLE_VILLAGER_TREE_WORK.get();
                case ROLE_FARMER -> !LivingVillagesConfig.ENABLE_FARMER_STOCKPILING.get();
                default -> true;
            };

            boolean missingRequiredTool = (ROLE_MINER.equals(role) && availablePickaxes == 0)
                || (ROLE_GUARD.equals(role) && availableSwords == 0);
            boolean builderNoLongerNeeded = ROLE_BUILDER.equals(role) && !activeBuilderProject && !housingNeeded;
            boolean tenureElapsed = now - data.getLong(ROLE_ASSIGNED_TICK_KEY)
                >= com.abs.livingvillages.domain.SettlementSimulationModel.MIN_ROLE_TENURE_TICKS;
            boolean surplusRole = (ROLE_LUMBERJACK.equals(role) && excessLumberjacks > 0)
                || (ROLE_FARMER.equals(role) && excessFarmers > 0)
                || (ROLE_MINER.equals(role) && excessMiners > 0);
            boolean mayRotate = surplusRole && tenureElapsed && !roleTaskActive(villager, role);

            if (roleDisabled || missingRequiredTool || builderNoLongerNeeded || mayRotate) {
                releaseRole(villager);
                if (mayRotate) {
                    if (ROLE_LUMBERJACK.equals(role)) excessLumberjacks--;
                    else if (ROLE_FARMER.equals(role)) excessFarmers--;
                    else if (ROLE_MINER.equals(role)) excessMiners--;
                }
            }
        }

        // A global pause stops assigning new work, but the role-specific event handlers decide
        // whether an in-flight job is paused or cancelled without deleting carried resources.
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()) {
            VillageRegistry.recordDiagnostic(level, center, "Роли", "Отключена система авторазвития");
            return;
        }
        if (reconciliationOnly || (!VillageElder.isAutomationEnabled(all) && !VillageElder.hasPendingRequest(all))) {
            VillageRegistry.recordDiagnostic(level, center, "Роли", "Авторазвитие приостановлено старостой");
            return;
        }
        if (!LivingVillagesConfig.ENABLE_MINER_ROLE.get()
            && !LivingVillagesConfig.ENABLE_VILLAGE_GUARDS.get()
            && !LivingVillagesConfig.ENABLE_VILLAGE_INDUSTRY.get()
            && !LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()
            && !LivingVillagesConfig.ENABLE_VILLAGER_TREE_WORK.get()
            && !LivingVillagesConfig.ENABLE_FARMER_STOCKPILING.get()) {
            VillageRegistry.recordDiagnostic(level, center, "Роли", "Все специализированные роли отключены в настройках");
            return;
        }

        long adultPopulation = all.stream().filter(v -> !v.isBaby()).count();
        if (adultPopulation < 2) {
            VillageRegistry.recordDiagnostic(level, center, "Роли", "Недостаточно взрослых жителей (" + adultPopulation + "/2)");
            return;
        }

        int builders = (int) all.stream().filter(VillagerRoles::isBuilder).count();
        int craftspeople = (int) all.stream().filter(VillagerRoles::isCraftsman).count();
        int lumberjacks = (int) all.stream().filter(VillagerRoles::isLumberjack).count();
        int farmers = (int) all.stream().filter(VillagerRoles::isFarmer).count();
        int miners = (int) all.stream().filter(VillagerRoles::isMiner).count();
        int guards = (int) all.stream().filter(VillagerRoles::isGuard).count();

        int desiredBuilders = housingNeeded ? 1 : 0;
        int desiredCraftspeople = workshopExists ? 1 : 0;
        int desiredLumberjacks = LivingVillagesConfig.ENABLE_VILLAGER_TREE_WORK.get()
            ? resourcePlan.desiredLumberjacks() : 0;
        int desiredFarmers = LivingVillagesConfig.ENABLE_FARMER_STOCKPILING.get()
            ? resourcePlan.desiredFarmers() : 0;
        int desiredMiners = LivingVillagesConfig.ENABLE_MINER_ROLE.get()
            ? resourcePlan.desiredMiners() : 0;
        int guardDivisor = VillageElder.priorityForVillagers(all) == VillageElder.PRIORITY_DEFENSE ? 4 : 6;
        int desiredGuards = LivingVillagesConfig.ENABLE_VILLAGE_GUARDS.get() && adultPopulation >= 3
            ? Math.min(Math.max(1, (int) adultPopulation / guardDivisor), availableSwords) : 0;

        List<Villager> candidates = new ArrayList<>();
        for (Villager villager : all) {
            if (!isEligibleAutonomousAdult(villager) || VillageElder.isElder(villager)) continue;
            var data = villager.getPersistentData();
            if (!data.getString(ROLE_KEY).isEmpty()) continue;
            if (data.getBoolean(TREE_TASK_ACTIVE) || data.getBoolean(REPLANT_TASK_ACTIVE)
                || data.getBoolean("livingvillages_logistics_active")
                || data.getBoolean(VillagePopulation.ACTIVE_KEY)
                || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
                || data.getBoolean(VillageBuilder.ACTIVE_KEY)
                || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
                || data.getBoolean(VillagerAutonomy.HARVEST_ACTIVE_KEY)
                || data.getBoolean(VillagerAutonomy.HARVEST_CYCLE_KEY)
                || data.getBoolean(VillagerAutonomy.SEED_FETCH_ACTIVE_KEY)
                || data.getBoolean("livingvillages_mining_active")
                || data.getBoolean("livingvillages_defense_has_route")
                || data.getBoolean("livingvillages_industry_route_active")) continue;
            candidates.add(villager);
        }
        candidates.sort(Comparator.comparing(v -> v.getUUID().toString()));
        if (candidates.isEmpty()) {
            VillageRegistry.recordDiagnostic(level, center, "Роли",
                "Активны: строит.=" + builders + ", ремесл.=" + craftspeople + ", лесоруб.=" + lumberjacks
                    + ", фермер.=" + farmers + ", шахт.=" + miners + ", охр.=" + guards + " (нет свободных кандидатов)");
            return;
        }

        String role = null;
        // Housing (when materials are ready) and the workshop have priority over extraction and defense roles.
        if (builders < desiredBuilders) role = ROLE_BUILDER;
        else if (craftspeople < desiredCraftspeople) role = ROLE_CRAFTSMAN;
        else if (farmers < desiredFarmers) role = ROLE_FARMER;
        else if (lumberjacks < desiredLumberjacks) role = ROLE_LUMBERJACK;
        else if (miners < desiredMiners) role = ROLE_MINER;
        else if (guards < desiredGuards) role = ROLE_GUARD;
        if (role == null) {
            VillageRegistry.recordDiagnostic(level, center, "Роли",
                "Распределены: строит.=" + builders + ", ремесл.=" + craftspeople + ", лесоруб.=" + lumberjacks
                    + ", фермер.=" + farmers + ", шахт.=" + miners + ", охр.=" + guards);
            return;
        }

        final String targetRole = role;
        candidates.sort(Comparator
            .comparingInt((Villager v) -> VillagerProgression.getXp(v, targetRole))
            .reversed()
            .thenComparing(v -> !isUnemployedAdult(v))
            .thenComparing(v -> v.getUUID().toString()));

        Villager selected = null;
        for (Villager candidate : candidates) {
            boolean canStart = ROLE_CRAFTSMAN.equals(role) || ROLE_BUILDER.equals(role)
                || (ROLE_LUMBERJACK.equals(role) && resourcePlan.needsWood())
                || (ROLE_FARMER.equals(role) && resourcePlan.needsFood()
                    && (resourcePlan.matureCrops() > 0
                        || (resourcePlan.plantableFarmland() > 0 && resourcePlan.availableSeeds() > 0)))
                || (ROLE_MINER.equals(role) && VillageIndustry.hasPickaxeAccessibleTo(candidate))
                || (ROLE_GUARD.equals(role) && VillageIndustry.hasSwordAccessibleTo(candidate));
            if (canStart) { selected = candidate; break; }
        }
        if (selected == null) {
            VillageRegistry.recordDiagnostic(level, center, "Роли", "Нет инструмента в доступе для роли " + role);
            return;
        }

        BlockPos home = averageAdultPosition(all, center);
        var data = selected.getPersistentData();
        data.putString(ROLE_KEY, role);
        data.putLong(ROLE_ASSIGNED_TICK_KEY, level.getGameTime());
        data.putInt(HOME_X, home.getX());
        data.putInt(HOME_Y, home.getY());
        data.putInt(HOME_Z, home.getZ());
        VillageRegistry.recordDiagnostic(level, center, "Роли", "Назначена роль: " + role);
    }

    /**
     * Checks whether autonomous mod work AI must yield to vanilla Villager AI
     * (sleeping, player trading, recent damage panic, or active village evacuation).
     */
    public static boolean shouldYieldToVanillaVillagerAi(Villager villager) {
        if (villager == null || !villager.isAlive()) return true;
        boolean recentlyHurt = villager.getLastHurtByMob() != null
            && (villager.tickCount - villager.getLastHurtByMobTimestamp()) <= 60;
        boolean evacuating = villager.getPersistentData().getLong("livingvillages_evacuating_until")
            > villager.level().getGameTime();
        return com.abs.livingvillages.domain.SettlementSimulationModel.shouldYieldToVanillaVillagerAi(
            villager.isSleeping(),
            villager.isTrading(),
            recentlyHurt,
            evacuating
        );
    }

    /**
     * Recovers a villager whose active route/task has made zero progress for {@code MAX_TASK_STUCK_TICKS}.
     */
    public static boolean recoverIfTaskStuck(Villager villager, long nowTick) {
        if (villager == null) return false;
        var data = villager.getPersistentData();
        long lastProgress = data.getLong(TASK_PROGRESS_TICK_KEY);
        if (!com.abs.livingvillages.domain.SettlementSimulationModel.isTaskStuck(
            lastProgress,
            nowTick,
            com.abs.livingvillages.domain.SettlementSimulationModel.MAX_TASK_STUCK_TICKS
        )) {
            return false;
        }
        villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
        villager.getNavigation().stop();
        data.putBoolean("livingvillages_logistics_active", false);
        data.putBoolean("livingvillages_industry_route_active", false);
        data.putLong(TASK_PROGRESS_TICK_KEY, nowTick);
        return true;
    }

    private static boolean roleTaskActive(Villager villager, String role) {
        var data = villager.getPersistentData();
        return switch (role) {
            case ROLE_MINER -> data.getBoolean("livingvillages_mining_active");
            case ROLE_GUARD -> data.getBoolean("livingvillages_defense_has_route");
            case ROLE_CRAFTSMAN -> data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
                || data.getBoolean("livingvillages_industry_route_active");
            case ROLE_BUILDER -> VillageBuilder.isProjectActive(villager);
            case ROLE_LUMBERJACK -> data.getBoolean(TREE_TASK_ACTIVE) || data.getBoolean(REPLANT_TASK_ACTIVE);
            case ROLE_FARMER -> data.getBoolean(VillagerAutonomy.HARVEST_ACTIVE_KEY)
                || data.getBoolean(VillagerAutonomy.HARVEST_CYCLE_KEY)
                || data.getBoolean(VillagerAutonomy.SEED_FETCH_ACTIVE_KEY);
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
