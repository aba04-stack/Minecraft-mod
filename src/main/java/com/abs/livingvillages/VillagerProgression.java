package com.abs.livingvillages;

import com.abs.livingvillages.domain.VillagerProgressionModel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent villager profession progression adapter backed by {@link VillagerProgressionModel}.
 *
 * <p>Stores role XP in a versioned NBT compound ({@code livingvillages_progression}),
 * migrates legacy v0 villagers without losing existing data or unknown fields, and
 * provides small, explainable efficiency adjustments without creating free items.</p>
 */
public final class VillagerProgression {
    public static final String PROGRESSION_TAG = "livingvillages_progression";
    public static final String SCHEMA_VERSION_KEY = "schema_version";
    public static final String XP_BY_ROLE_KEY = "xp_by_role";
    public static final String LAST_AWARD_TICK_KEY = "last_award_tick";
    public static final String RAW_BACKUP_TAG_KEY = "_raw_backup_tag";

    private VillagerProgression() {}

    /**
     * Ensure the villager's progression tag is at the current schema version (v1).
     * Unknown fields are preserved; malformed legacy records are backed up safely.
     */
    public static CompoundTag ensureMigratedTag(CompoundTag entityPersistentData) {
        if (entityPersistentData == null) {
            return new CompoundTag();
        }
        if (entityPersistentData.contains(PROGRESSION_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag existing = entityPersistentData.getCompound(PROGRESSION_TAG);
            int version = existing.getInt(SCHEMA_VERSION_KEY);
            if (version < 0 || (existing.contains(XP_BY_ROLE_KEY) && !existing.contains(XP_BY_ROLE_KEY, Tag.TAG_COMPOUND))) {
                CompoundTag recovered = new CompoundTag();
                recovered.put(RAW_BACKUP_TAG_KEY, existing.copy());
                recovered.putInt(SCHEMA_VERSION_KEY, VillagerProgressionModel.SCHEMA_VERSION);
                recovered.put(XP_BY_ROLE_KEY, new CompoundTag());
                entityPersistentData.put(PROGRESSION_TAG, recovered);
                return recovered;
            }
            if (version < VillagerProgressionModel.SCHEMA_VERSION) {
                existing.putInt(SCHEMA_VERSION_KEY, VillagerProgressionModel.SCHEMA_VERSION);
            }
            if (!existing.contains(XP_BY_ROLE_KEY, Tag.TAG_COMPOUND)) {
                existing.put(XP_BY_ROLE_KEY, new CompoundTag());
            }
            return existing;
        }

        // Legacy v0 villager or corrupt non-compound tag
        CompoundTag fresh = new CompoundTag();
        if (entityPersistentData.contains(PROGRESSION_TAG)) {
            Tag unexpected = entityPersistentData.get(PROGRESSION_TAG);
            if (unexpected != null) {
                fresh.put(RAW_BACKUP_TAG_KEY, unexpected.copy());
            }
        }
        fresh.putInt(SCHEMA_VERSION_KEY, VillagerProgressionModel.SCHEMA_VERSION);
        fresh.put(XP_BY_ROLE_KEY, new CompoundTag());
        entityPersistentData.put(PROGRESSION_TAG, fresh);
        return fresh;
    }

    public static int getXp(Villager villager, String role) {
        if (villager == null || !VillagerProgressionModel.isValidRole(role)) return 0;
        CompoundTag prog = ensureMigratedTag(villager.getPersistentData());
        CompoundTag xpTag = prog.getCompound(XP_BY_ROLE_KEY);
        return Math.max(0, xpTag.getInt(role));
    }

    public static int getLevel(Villager villager, String role) {
        if (villager == null || !VillagerProgressionModel.isValidRole(role)) {
            return VillagerProgressionModel.MIN_LEVEL;
        }
        if (!LivingVillagesConfig.ENABLE_VILLAGER_PROGRESSION.get()) {
            return VillagerProgressionModel.MIN_LEVEL;
        }
        int xp = getXp(villager, role);
        int maxLevel = LivingVillagesConfig.MAX_PROFESSION_LEVEL.get();
        return VillagerProgressionModel.levelForXp(xp, maxLevel);
    }

    /**
     * Record a confirmed completed work action and award XP if eligible.
     */
    public static boolean recordCompletedWork(
            Villager villager,
            String role,
            int xpGain,
            boolean taskConfirmedComplete,
            long nowTick) {
        if (villager == null || !LivingVillagesConfig.ENABLE_VILLAGER_PROGRESSION.get()) {
            return false;
        }
        CompoundTag prog = ensureMigratedTag(villager.getPersistentData());
        CompoundTag xpTag = prog.getCompound(XP_BY_ROLE_KEY);
        Map<String, Integer> current = new HashMap<>();
        for (String supported : VillagerProgressionModel.SUPPORTED_ROLES) {
            if (xpTag.contains(supported, Tag.TAG_INT)) {
                current.put(supported, Math.max(0, xpTag.getInt(supported)));
            }
        }

        long lastAward = prog.getLong(LAST_AWARD_TICK_KEY);
        int maxLevel = LivingVillagesConfig.MAX_PROFESSION_LEVEL.get();
        VillagerProgressionModel.AwardResult result = VillagerProgressionModel.evaluateWorkCompletion(
            current,
            role,
            xpGain,
            taskConfirmedComplete,
            nowTick,
            lastAward,
            VillagerProgressionModel.DEFAULT_AWARD_COOLDOWN_TICKS,
            maxLevel
        );
        if (!result.awarded()) {
            return false;
        }

        for (Map.Entry<String, Integer> entry : result.xpByRole().entrySet()) {
            xpTag.putInt(entry.getKey(), entry.getValue());
        }
        prog.putLong(LAST_AWARD_TICK_KEY, result.updatedLastAwardTick());
        return true;
    }

    public static int adjustedStepTicks(Villager villager, String role, int baseTicks, int minTicks) {
        int level = getLevel(villager, role);
        return VillagerProgressionModel.adjustedStepTicks(baseTicks, level, minTicks);
    }

    public static int adjustedDurationTicks(Villager villager, String role, int baseTicks) {
        return adjustedStepTicks(villager, role, baseTicks, 4);
    }

    public static float guardRetreatRatio(Villager villager) {
        int level = getLevel(villager, VillagerProgressionModel.ROLE_GUARD);
        return VillagerProgressionModel.guardRetreatHealthRatio(level);
    }

    public static float retreatHealthThreshold(Villager villager, float fallbackRatio) {
        return villager == null ? fallbackRatio : guardRetreatRatio(villager);
    }

    public static String describePrimarySkill(Villager villager) {
        if (villager == null) return "";
        String specialistRole = villager.getPersistentData().getString(VillagerRoles.ROLE_KEY);
        String targetRole = switch (specialistRole) {
            case VillagerRoles.ROLE_MINER -> VillagerProgressionModel.ROLE_MINER;
            case VillagerRoles.ROLE_GUARD -> VillagerProgressionModel.ROLE_GUARD;
            case VillagerRoles.ROLE_CRAFTSMAN -> VillagerProgressionModel.ROLE_CRAFTSMAN;
            case VillagerRoles.ROLE_BUILDER -> VillagerProgressionModel.ROLE_BUILDER;
            default -> {
                if (villager.getVillagerData().getProfession() == VillagerProfession.FARMER) {
                    yield VillagerProgressionModel.ROLE_FARMER;
                }
                yield bestLearnedRole(villager);
            }
        };
        int level = getLevel(villager, targetRole);
        int xp = getXp(villager, targetRole);
        return roleLabelRu(targetRole) + " ур. " + level + " (" + xp + " XP)";
    }

    public static String describeAllSkillsAndActivityRu(Villager villager) {
        if (villager == null) return "";
        int maxLevel = LivingVillagesConfig.MAX_PROFESSION_LEVEL.get();
        java.util.List<String> learned = new java.util.ArrayList<>();
        for (String role : VillagerProgressionModel.SUPPORTED_ROLES) {
            int xp = getXp(villager, role);
            if (xp > 0) {
                int lvl = getLevel(villager, role);
                learned.add(roleLabelRu(role) + " ур. " + lvl + "/" + maxLevel + " (" + xp + " XP)");
            }
        }
        String skillsPart = learned.isEmpty()
            ? "Опыт ролей: пока 0 XP"
            : "Навыки: " + String.join(", ", learned);
        return skillsPart + " · Задача: " + describeCurrentTaskRu(villager);
    }

    public static String describeCurrentTaskRu(Villager villager) {
        if (villager == null) return "Нет";
        var data = villager.getPersistentData();
        if (data.getBoolean(VillageBuilder.ACTIVE_KEY)) return "Строит дом";
        if (data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)) return "Строит ферму";
        if (data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)) return "Работает на верстаке";
        if (data.getBoolean("livingvillages_treework_active")) return "Рубит дерево";
        if (data.getBoolean("livingvillages_treework_replant_active")) return "Сажает саженец";
        if (data.getBoolean("livingvillages_mining_active")) return "Добывает камень/руду";
        if (data.getBoolean("livingvillages_logistics_active")) return "Несёт ресурсы на склад";
        if (data.getBoolean(VillagePopulation.ACTIVE_KEY)) return "Доставляет еду жителям";
        if (data.getBoolean("livingvillages_autonomy_target_active")) return "Сдаёт урожай на склад";
        if (data.getBoolean("livingvillages_defense_has_route")) return "Охраняет поселение";
        return "Свободен";
    }

    private static String bestLearnedRole(Villager villager) {
        String bestRole = VillagerProgressionModel.ROLE_LUMBERJACK;
        int bestXp = -1;
        for (String role : VillagerProgressionModel.SUPPORTED_ROLES) {
            int xp = getXp(villager, role);
            if (xp > bestXp) {
                bestXp = xp;
                bestRole = role;
            }
        }
        return bestRole;
    }

    private static String roleLabelRu(String role) {
        return switch (role) {
            case VillagerProgressionModel.ROLE_MINER -> "Шахтёр";
            case VillagerProgressionModel.ROLE_BUILDER -> "Строитель";
            case VillagerProgressionModel.ROLE_CRAFTSMAN -> "Ремесленник";
            case VillagerProgressionModel.ROLE_GUARD -> "Стражник";
            case VillagerProgressionModel.ROLE_FARMER -> "Фермер";
            case VillagerProgressionModel.ROLE_COURIER -> "Курьер";
            default -> "Лесоруб";
        };
    }
}
