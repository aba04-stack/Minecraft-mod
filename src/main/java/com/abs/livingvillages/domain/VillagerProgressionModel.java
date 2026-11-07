package com.abs.livingvillages.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-independent domain model for minimal villager profession progression.
 *
 * <p>This class intentionally imports zero Minecraft or NeoForge classes so that
 * progression rules, anti-abuse guards, role switches, and level bonuses can be
 * unit-tested and ported independently of the game runtime.</p>
 */
public final class VillagerProgressionModel {
    public static final int SCHEMA_VERSION = 1;
    public static final int MIN_LEVEL = 1;
    public static final int DEFAULT_MAX_LEVEL = 5;
    public static final long DEFAULT_AWARD_COOLDOWN_TICKS = 20L;

    public static final String ROLE_LUMBERJACK = "lumberjack";
    public static final String ROLE_MINER = "miner";
    public static final String ROLE_BUILDER = "builder";
    public static final String ROLE_CRAFTSMAN = "craftsman";
    public static final String ROLE_GUARD = "guard";
    public static final String ROLE_FARMER = "farmer";
    public static final String ROLE_COURIER = "courier";

    public static final List<String> SUPPORTED_ROLES = List.of(
        ROLE_LUMBERJACK,
        ROLE_MINER,
        ROLE_BUILDER,
        ROLE_CRAFTSMAN,
        ROLE_GUARD,
        ROLE_FARMER,
        ROLE_COURIER
    );

    /** Cumulative XP thresholds for levels 1..5. */
    private static final int[] LEVEL_THRESHOLDS = {0, 10, 30, 65, 115};

    private VillagerProgressionModel() {}

    public static boolean isValidRole(String role) {
        return role != null && SUPPORTED_ROLES.contains(role);
    }

    public static int clampMaxLevel(int configuredMaxLevel) {
        return Math.max(MIN_LEVEL, Math.min(DEFAULT_MAX_LEVEL, configuredMaxLevel));
    }

    public static int levelForXp(int xp, int configuredMaxLevel) {
        int safeXp = Math.max(0, xp);
        int maxLevel = clampMaxLevel(configuredMaxLevel);
        int resolved = MIN_LEVEL;
        for (int lvl = MIN_LEVEL; lvl <= maxLevel; lvl++) {
            int threshold = LEVEL_THRESHOLDS[lvl - 1];
            if (safeXp >= threshold) {
                resolved = lvl;
            } else {
                break;
            }
        }
        return resolved;
    }

    public static int xpThresholdForLevel(int level) {
        int clamped = Math.max(MIN_LEVEL, Math.min(DEFAULT_MAX_LEVEL, level));
        return LEVEL_THRESHOLDS[clamped - 1];
    }

    /**
     * Evaluate a work-completion XP award while preserving all other roles' accumulated XP.
     *
     * <p>Failed, cancelled, empty, or rapid-repeat attempts do not grant XP.</p>
     */
    public static AwardResult evaluateWorkCompletion(
            Map<String, Integer> currentXpByRole,
            String role,
            int xpGain,
            boolean taskConfirmedComplete,
            long nowTick,
            long lastAwardTick,
            long cooldownTicks,
            int configuredMaxLevel) {
        Map<String, Integer> updated = new HashMap<>();
        if (currentXpByRole != null) {
            for (Map.Entry<String, Integer> entry : currentXpByRole.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    updated.put(entry.getKey(), Math.max(0, entry.getValue()));
                }
            }
        }

        if (!isValidRole(role)) {
            return new AwardResult(false, 0, MIN_LEVEL, MIN_LEVEL, false, lastAwardTick, Collections.unmodifiableMap(updated));
        }

        int previousXp = Math.max(0, updated.getOrDefault(role, 0));
        int previousLevel = levelForXp(previousXp, configuredMaxLevel);

        if (!taskConfirmedComplete || xpGain <= 0) {
            return new AwardResult(false, previousXp, previousLevel, previousLevel, false, lastAwardTick, Collections.unmodifiableMap(updated));
        }

        long safeCooldown = Math.max(0L, cooldownTicks);
        if (lastAwardTick > 0L && nowTick >= lastAwardTick && (nowTick - lastAwardTick) < safeCooldown) {
            return new AwardResult(false, previousXp, previousLevel, previousLevel, false, lastAwardTick, Collections.unmodifiableMap(updated));
        }

        int maxXpCap = xpThresholdForLevel(DEFAULT_MAX_LEVEL) * 2;
        int newXp = Math.min(maxXpCap, previousXp + xpGain);
        updated.put(role, newXp);
        int newLevel = levelForXp(newXp, configuredMaxLevel);
        boolean leveledUp = newLevel > previousLevel;

        return new AwardResult(
            true,
            newXp,
            previousLevel,
            newLevel,
            leveledUp,
            nowTick,
            Collections.unmodifiableMap(updated)
        );
    }

    /**
     * Compute a small, explainable duration adjustment for a familiar work operation.
     *
     * <p>Never reduces material costs or creates items; only trims a few ticks off
     * step execution down to {@code minTicks}.</p>
     */
    public static int adjustedStepTicks(int baseTicks, int level, int minTicks) {
        if (baseTicks <= 1) return 1;
        int safeMin = Math.max(1, Math.min(baseTicks, minTicks));
        int clampedLevel = Math.max(MIN_LEVEL, Math.min(DEFAULT_MAX_LEVEL, level));
        int reduction = clampedLevel - MIN_LEVEL;
        return Math.max(safeMin, baseTicks - reduction);
    }

    /**
     * Experienced guards retreat slightly earlier when critically wounded to avoid reckless deaths.
     */
    public static float guardRetreatHealthRatio(int level) {
        int clampedLevel = Math.max(MIN_LEVEL, Math.min(DEFAULT_MAX_LEVEL, level));
        return 0.35F + (clampedLevel - MIN_LEVEL) * 0.02F;
    }

    public record AwardResult(
            boolean awarded,
            int updatedXp,
            int previousLevel,
            int newLevel,
            boolean leveledUp,
            long updatedLastAwardTick,
            Map<String, Integer> xpByRole) {
    }
}
