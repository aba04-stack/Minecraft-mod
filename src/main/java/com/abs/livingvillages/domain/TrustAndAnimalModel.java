package com.abs.livingvillages.domain;

/**
 * Pure domain model for the villager trust system (0..100), animal herd/parental
 * behavior, and animal breeding/offspring maturation.
 *
 * <p>Contains zero Minecraft or NeoForge imports so all rules can be tested
 * and verified independently of the game runtime.</p>
 */
public final class TrustAndAnimalModel {
    // Trust bounds
    public static final int TRUST_MIN = 0;
    public static final int TRUST_MAX = 100;

    // Explicit trust tiers required by specification:
    // 0-19: незнакомец, 20-39: знакомый, 40-59: доверяет, 60-79: друг, 80-100: близкий человек
    public static final int TIER_STRANGER = 0;
    public static final int TIER_ACQUAINTANCE = 1;
    public static final int TIER_TRUSTING = 2;
    public static final int TIER_FRIEND = 3;
    public static final int TIER_CLOSE_PERSON = 4;

    // Trust event types
    public static final int EVENT_HELP_OR_GIFT = 1;
    public static final int EVENT_VILLAGE_DEFENSE = 2;
    public static final int EVENT_REQUEST_FULFILLED = 3;
    public static final int EVENT_ATTACK_OR_HARM = 4;
    public static final int EVENT_THEFT = 5;

    // Villager behavioral responses driven by trust tier
    public static final int BEHAVIOR_CAUTIOUS_DISTANCE = 0;
    public static final int BEHAVIOR_POLITE_GREETING = 1;
    public static final int BEHAVIOR_OFFER_REQUEST = 2;
    public static final int BEHAVIOR_FRIEND_SUPPORT = 3;
    public static final int BEHAVIOR_CLOSE_COMPANION = 4;

    // Animal species archetypes
    public static final int SPECIES_TIMID_GRAZER = 1;
    public static final int SPECIES_POULTRY = 2;
    public static final int SPECIES_HERD_LIVESTOCK = 3;
    public static final int SPECIES_MOUNT_OR_GUARD = 4;

    // Animal tactical / behavioral actions
    public static final int ANIMAL_ACTION_IDLE = 0;
    public static final int ANIMAL_ACTION_FLEE_THREAT = 1;
    public static final int ANIMAL_ACTION_PROTECT_OFFSPRING = 2;
    public static final int ANIMAL_ACTION_FOLLOW_PARENT = 3;
    public static final int ANIMAL_ACTION_SEEK_SHELTER = 4;
    public static final int ANIMAL_ACTION_FORAGE_OR_GRAZE = 5;
    public static final int ANIMAL_ACTION_COURTSHIP = 6;

    // Offspring individual traits
    public static final int OFFSPRING_TRAIT_HARDY = 1;
    public static final int OFFSPRING_TRAIT_SWIFT = 2;
    public static final int OFFSPRING_TRAIT_CALM = 3;
    public static final int OFFSPRING_TRAIT_VIGOROUS = 4;

    private TrustAndAnimalModel() {}

    public static int clampTrust(int trust) {
        return Math.max(TRUST_MIN, Math.min(TRUST_MAX, trust));
    }

    /**
     * Maps a 0..100 trust score to one of the 5 specification tiers:
     * 0-19 -> TIER_STRANGER (0)
     * 20-39 -> TIER_ACQUAINTANCE (1)
     * 40-59 -> TIER_TRUSTING (2)
     * 60-79 -> TIER_FRIEND (3)
     * 80-100 -> TIER_CLOSE_PERSON (4)
     */
    public static int trustTierForScore(int trust) {
        int clamped = clampTrust(trust);
        if (clamped >= 80) {
            return TIER_CLOSE_PERSON;
        }
        if (clamped >= 60) {
            return TIER_FRIEND;
        }
        if (clamped >= 40) {
            return TIER_TRUSTING;
        }
        if (clamped >= 20) {
            return TIER_ACQUAINTANCE;
        }
        return TIER_STRANGER;
    }

    /**
     * Returns the exact Russian display name for the trust level required by the specification.
     */
    public static String trustTierNameRu(int trust) {
        return switch (trustTierForScore(trust)) {
            case TIER_CLOSE_PERSON -> "близкий человек";
            case TIER_FRIEND -> "друг";
            case TIER_TRUSTING -> "доверяет";
            case TIER_ACQUAINTANCE -> "знакомый";
            default -> "незнакомец";
        };
    }

    /**
     * Evaluates a trust update taking into account the event type, the villager's
     * individual personality (0..5), and whether the item/action matches their preference.
     */
    public static int evaluateTrustChange(
            int currentTrust,
            int eventType,
            int baseMagnitude,
            int personality,
            boolean matchesPreference) {
        int safeMagnitude = Math.max(1, baseMagnitude);
        int delta;
        switch (eventType) {
            case EVENT_HELP_OR_GIFT -> {
                delta = safeMagnitude;
                if (matchesPreference) {
                    delta += 4;
                }
                if (personality == 1 || personality == 4) { // Дружелюбный / Весельчак
                    delta += 2;
                } else if (personality == 3) { // Осторожный
                    delta = Math.max(1, delta - 1);
                }
            }
            case EVENT_VILLAGE_DEFENSE -> {
                delta = safeMagnitude + 4;
                if (personality == 3) { // Осторожный особенно ценит защиту
                    delta += 3;
                }
            }
            case EVENT_REQUEST_FULFILLED -> {
                delta = safeMagnitude + 6;
                if (matchesPreference) {
                    delta += 3;
                }
                if (personality == 0) { // Трудолюбивый ценит выполнение дела
                    delta += 2;
                }
            }
            case EVENT_ATTACK_OR_HARM -> {
                int penalty = safeMagnitude;
                if (personality == 3) {
                    penalty += 5;
                }
                delta = -penalty;
            }
            case EVENT_THEFT -> {
                int penalty = safeMagnitude + 4;
                if (personality == 0 || personality == 3) {
                    penalty += 4;
                }
                delta = -penalty;
            }
            default -> delta = 0;
        }
        return clampTrust(currentTrust + delta);
    }

    /**
     * Determines the villager's behavioral stance and unlocked interaction level
     * based on trust score and fear.
     */
    public static int evaluateTrustBehavior(int trust, int fear) {
        if (fear >= 25) {
            return BEHAVIOR_CAUTIOUS_DISTANCE;
        }
        return switch (trustTierForScore(trust)) {
            case TIER_CLOSE_PERSON -> BEHAVIOR_CLOSE_COMPANION;
            case TIER_FRIEND -> BEHAVIOR_FRIEND_SUPPORT;
            case TIER_TRUSTING -> BEHAVIOR_OFFER_REQUEST;
            case TIER_ACQUAINTANCE -> BEHAVIOR_POLITE_GREETING;
            default -> BEHAVIOR_CAUTIOUS_DISTANCE;
        };
    }

    /**
     * Returns the number of unlocked interaction options (1..5) for the given trust score.
     */
    public static int unlockedInteractionCount(int trust) {
        return trustTierForScore(trust) + 1;
    }

    /**
     * Computes a trade price adjustment (negative = discount, positive = markup)
     * driven by the villager's trust toward the player.
     */
    public static int computeTrustPriceOffset(int trust, int fear) {
        if (fear >= 25) {
            return 4;
        }
        return switch (trustTierForScore(trust)) {
            case TIER_CLOSE_PERSON -> -5;
            case TIER_FRIEND -> -3;
            case TIER_TRUSTING -> -1;
            case TIER_ACQUAINTANCE -> 0;
            default -> 1;
        };
    }

    /**
     * Evaluates the priority action for an animal given threats, herd panic,
     * parent-offspring bonds, weather, breeding state, and foraging needs.
     */
    public static int evaluateAnimalBehavior(
            int speciesArchetype,
            boolean isBaby,
            boolean directThreatNearby,
            boolean herdAlerted,
            boolean hasOffspringNearby,
            boolean hasParentNearby,
            boolean isRaining,
            boolean hasShelterOverhead,
            boolean isInLove,
            boolean wantsToForage) {
        if (directThreatNearby || herdAlerted) {
            // Protective adult livestock/mounts stay to shield nearby offspring against direct threats
            if (!isBaby && hasOffspringNearby && directThreatNearby
                && (speciesArchetype == SPECIES_HERD_LIVESTOCK || speciesArchetype == SPECIES_MOUNT_OR_GUARD)) {
                return ANIMAL_ACTION_PROTECT_OFFSPRING;
            }
            return ANIMAL_ACTION_FLEE_THREAT;
        }
        if (isBaby && hasParentNearby) {
            return ANIMAL_ACTION_FOLLOW_PARENT;
        }
        if (isRaining && !hasShelterOverhead) {
            return ANIMAL_ACTION_SEEK_SHELTER;
        }
        if (!isBaby && isInLove) {
            return ANIMAL_ACTION_COURTSHIP;
        }
        if (wantsToForage) {
            return ANIMAL_ACTION_FORAGE_OR_GRAZE;
        }
        return ANIMAL_ACTION_IDLE;
    }

    /**
     * Species- and trait-specific flee speed multiplier when escaping danger.
     */
    public static double fleeSpeedMultiplier(int speciesArchetype, int offspringTrait) {
        double base = switch (speciesArchetype) {
            case SPECIES_TIMID_GRAZER -> 1.45D;
            case SPECIES_POULTRY -> 1.40D;
            case SPECIES_MOUNT_OR_GUARD -> 1.35D;
            default -> 1.25D;
        };
        if (offspringTrait == OFFSPRING_TRAIT_SWIFT) {
            base += 0.12D;
        } else if (offspringTrait == OFFSPRING_TRAIT_CALM) {
            base -= 0.05D;
        }
        return base;
    }

    /**
     * Derives an individual trait (1..4) for newborn offspring from its parents' traits
     * and a deterministic seed.
     */
    public static int deriveOffspringTrait(int parentATrait, int parentBTrait, int deterministicSeed) {
        if (parentATrait >= OFFSPRING_TRAIT_HARDY && parentATrait <= OFFSPRING_TRAIT_VIGOROUS
            && parentATrait == parentBTrait) {
            return parentATrait;
        }
        int mixed = Math.abs(parentATrait * 3 + parentBTrait * 5 + deterministicSeed);
        return (mixed % 4) + 1;
    }

    /**
     * Computes the number of maturation ticks to advance for a young animal
     * during a care step when near its parent and/or well-fed in shelter.
     */
    public static int computeMaturationTicks(
            int currentNegativeAgeTicks,
            boolean nearParent,
            boolean shelteredOrFed,
            int offspringTrait) {
        if (currentNegativeAgeTicks >= 0) {
            return 0;
        }
        int step = 20;
        if (nearParent) {
            step += 25;
        }
        if (shelteredOrFed) {
            step += 15;
        }
        if (offspringTrait == OFFSPRING_TRAIT_VIGOROUS) {
            step += 20;
        }
        return Math.min(Math.abs(currentNegativeAgeTicks), step);
    }
}
