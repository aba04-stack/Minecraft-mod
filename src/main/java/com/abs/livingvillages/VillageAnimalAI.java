package com.abs.livingvillages;

import com.abs.livingvillages.domain.TickBudgetModel;
import com.abs.livingvillages.domain.TrustAndAnimalModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Server-side animal behavior, herd danger reaction, parental protection,
 * shelter/foraging routines, and enhanced vanilla-compatible breeding/offspring maturation.
 */
public final class VillageAnimalAI {
    public static final String LAST_ACTION_KEY = "livingvillages_animal_action";
    public static final String HERD_ALERT_UNTIL_KEY = "livingvillages_herd_alert_until";
    public static final String THREAT_X_KEY = "livingvillages_animal_threat_x";
    public static final String THREAT_Z_KEY = "livingvillages_animal_threat_z";
    public static final String PARENT_UUID_KEY = "livingvillages_parent_uuid";
    public static final String OFFSPRING_TRAIT_KEY = "livingvillages_offspring_trait";
    public static final String PROTECTING_CHILD_UNTIL_KEY = "livingvillages_protect_child_until";
    public static final String LAST_FORAGE_TICK_KEY = "livingvillages_last_forage_tick";

    private static final int ANIMAL_CHECK_INTERVAL = 20;
    private static final long HERD_ALERT_DURATION_TICKS = 160L;
    private static final long PARENTAL_BOND_DURATION_TICKS = 1200L;

    private VillageAnimalAI() {}

    public static void onAnimalTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Animal animal) || animal.level().isClientSide()) {
            return;
        }
        if (!LivingVillagesConfig.ENABLE_ANIMAL_BEHAVIOR.get()) {
            return;
        }
        if (!(animal.level() instanceof ServerLevel serverLevel) || !animal.isAlive()) {
            return;
        }
        if (!serverLevel.hasChunkAt(animal.blockPosition())) {
            return;
        }
        long now = serverLevel.getGameTime();
        if (!TickBudgetModel.isScheduledThisTick(now, animal.getId(), ANIMAL_CHECK_INTERVAL)) {
            return;
        }
        evaluateAndApplyAnimalBehavior(animal, serverLevel, now);
    }

    public static void onAnimalDamaged(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Animal victim) || victim.level().isClientSide()) {
            return;
        }
        if (!(victim.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity source = event.getSource().getEntity();
        LivingEntity threat = source instanceof LivingEntity living ? living : victim.getLastHurtByMob();
        BlockPos threatPos = threat != null ? threat.blockPosition() : victim.blockPosition();
        alertHerdOnDanger(victim, serverLevel, threatPos, serverLevel.getGameTime());
    }

    public static void onBabySpawn(BabyEntitySpawnEvent event) {
        if (!(event.getParentA() instanceof Animal parentA)
            || !(event.getParentB() instanceof Animal parentB)) {
            return;
        }
        AgeableMob child = event.getChild();
        if (child == null || parentA.level().isClientSide()) {
            return;
        }
        registerOffspringFromParents(parentA, parentB, child, parentA.level().getGameTime());
    }

    public static int classifySpeciesArchetype(Animal animal) {
        if (animal instanceof Sheep || animal instanceof Rabbit) {
            return TrustAndAnimalModel.SPECIES_TIMID_GRAZER;
        }
        if (animal instanceof Chicken) {
            return TrustAndAnimalModel.SPECIES_POULTRY;
        }
        if (animal instanceof AbstractHorse || animal instanceof Goat) {
            return TrustAndAnimalModel.SPECIES_MOUNT_OR_GUARD;
        }
        if (animal instanceof Cow || animal instanceof Pig) {
            return TrustAndAnimalModel.SPECIES_HERD_LIVESTOCK;
        }
        return TrustAndAnimalModel.SPECIES_HERD_LIVESTOCK;
    }

    /**
     * Alerts nearby animals in the herd (within 14 blocks) when one animal is attacked or threatened.
     */
    public static int alertHerdOnDanger(
            Animal victim,
            ServerLevel level,
            BlockPos threatPos,
            long nowTick) {
        if (victim == null || level == null || threatPos == null) return 0;
        recordHerdAlert(victim.getPersistentData(), threatPos, nowTick);

        AABB herdBox = victim.getBoundingBox().inflate(14.0D, 5.0D, 14.0D);
        List<Animal> herd = level.getEntitiesOfClass(Animal.class, herdBox,
            other -> other != victim && other.isAlive() && !other.isRemoved());
        int alerted = 0;
        for (Animal member : herd) {
            recordHerdAlert(member.getPersistentData(), threatPos, nowTick);
            alerted++;
        }
        return alerted;
    }

    private static void recordHerdAlert(CompoundTag data, BlockPos threatPos, long nowTick) {
        data.putLong(HERD_ALERT_UNTIL_KEY, nowTick + HERD_ALERT_DURATION_TICKS);
        data.putInt(THREAT_X_KEY, threatPos.getX());
        data.putInt(THREAT_Z_KEY, threatPos.getZ());
    }

    /**
     * Evaluates and applies the animal's highest-priority behavioral action in the world.
     */
    public static int evaluateAndApplyAnimalBehavior(Animal animal, ServerLevel level, long nowTick) {
        if (animal == null || level == null || !animal.isAlive()) {
            return TrustAndAnimalModel.ANIMAL_ACTION_IDLE;
        }
        CompoundTag data = animal.getPersistentData();
        int species = classifySpeciesArchetype(animal);
        boolean isBaby = animal.isBaby();

        AABB localArea = animal.getBoundingBox().inflate(12.0D, 4.0D, 12.0D);
        LivingEntity predatorOrThreat = findNearestPredatorOrThreat(animal, level, localArea);
        boolean directThreat = predatorOrThreat != null;
        if (shouldYieldToVanillaAnimalAi(animal, level, directThreat)) {
            return TrustAndAnimalModel.ANIMAL_ACTION_IDLE;
        }
        if (directThreat) {
            recordHerdAlert(data, predatorOrThreat.blockPosition(), nowTick);
        }
        boolean herdAlerted = data.getLong(HERD_ALERT_UNTIL_KEY) > nowTick;

        Animal nearbyOffspring = isBaby ? null : findNearbyOffspring(animal, level, localArea);
        Animal nearbyParent = isBaby ? findNearbyParent(animal, level, localArea) : null;

        boolean raining = level.isRainingAt(animal.blockPosition());
        boolean sheltered = hasOverheadShelter(level, animal.blockPosition());
        boolean inLove = animal.isInLove();
        boolean wantsToForage = (nowTick - data.getLong(LAST_FORAGE_TICK_KEY)) >= 120L;

        int action = TrustAndAnimalModel.evaluateAnimalBehavior(
            species,
            isBaby,
            directThreat,
            herdAlerted,
            nearbyOffspring != null,
            nearbyParent != null,
            raining,
            sheltered,
            inLove,
            wantsToForage
        );

        data.putInt(LAST_ACTION_KEY, action);

        switch (action) {
            case TrustAndAnimalModel.ANIMAL_ACTION_PROTECT_OFFSPRING -> {
                if (nearbyOffspring != null) {
                    BlockPos threatPos = predatorOrThreat != null
                        ? predatorOrThreat.blockPosition()
                        : new BlockPos(data.getInt(THREAT_X_KEY), animal.getBlockY(), data.getInt(THREAT_Z_KEY));
                    Vec3 between = nearbyOffspring.position().add(
                        Vec3.atCenterOf(threatPos).subtract(nearbyOffspring.position()).normalize().scale(1.5D)
                    );
                    animal.getNavigation().moveTo(between.x, nearbyOffspring.getY(), between.z, 1.25D);
                    // Urge the baby to flee behind the parent
                    Vec3 babyEscape = nearbyOffspring.position().subtract(Vec3.atCenterOf(threatPos));
                    if (babyEscape.lengthSqr() > 0.01D) {
                        Vec3 norm = babyEscape.normalize().scale(4.0D);
                        nearbyOffspring.getNavigation().moveTo(
                            nearbyOffspring.getX() + norm.x,
                            nearbyOffspring.getY(),
                            nearbyOffspring.getZ() + norm.z,
                            1.35D
                        );
                    }
                }
            }
            case TrustAndAnimalModel.ANIMAL_ACTION_FLEE_THREAT -> {
                double threatX = predatorOrThreat != null ? predatorOrThreat.getX() : data.getInt(THREAT_X_KEY);
                double threatZ = predatorOrThreat != null ? predatorOrThreat.getZ() : data.getInt(THREAT_Z_KEY);
                Vec3 away = new Vec3(animal.getX() - threatX, 0.0D, animal.getZ() - threatZ);
                if (away.lengthSqr() < 0.01D) {
                    away = new Vec3(1.0D, 0.0D, 0.0D);
                }
                double speed = TrustAndAnimalModel.fleeSpeedMultiplier(species, data.getInt(OFFSPRING_TRAIT_KEY));
                Vec3 escape = away.normalize().scale(7.0D);
                BlockPos dest = BlockPos.containing(animal.getX() + escape.x, animal.getY(), animal.getZ() + escape.z);
                if (level.hasChunkAt(dest)) {
                    animal.getNavigation().moveTo(dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D, speed);
                }
            }
            case TrustAndAnimalModel.ANIMAL_ACTION_FOLLOW_PARENT -> {
                if (nearbyParent != null) {
                    animal.getNavigation().moveTo(nearbyParent, 1.15D);
                    stepOffspringGrowth(animal, level, nowTick);
                }
            }
            case TrustAndAnimalModel.ANIMAL_ACTION_SEEK_SHELTER -> {
                BlockPos shelterPos = findNearestShelterBlock(level, animal.blockPosition(), 10);
                if (shelterPos != null) {
                    animal.getNavigation().moveTo(shelterPos.getX() + 0.5D, shelterPos.getY(), shelterPos.getZ() + 0.5D, 1.15D);
                }
            }
            case TrustAndAnimalModel.ANIMAL_ACTION_COURTSHIP -> {
                performPreBreedingCourtship(animal, level, localArea);
            }
            case TrustAndAnimalModel.ANIMAL_ACTION_FORAGE_OR_GRAZE -> {
                BlockPos foragePos = findNearbyForageBlock(level, animal.blockPosition(), 8);
                if (foragePos != null) {
                    animal.getNavigation().moveTo(foragePos.getX() + 0.5D, foragePos.getY() + 1.0D, foragePos.getZ() + 0.5D, 1.0D);
                    data.putLong(LAST_FORAGE_TICK_KEY, nowTick);
                }
                if (isBaby) {
                    stepOffspringGrowth(animal, level, nowTick);
                }
            }
            default -> {
            }
        }
        return action;
    }

    public static boolean shouldYieldToVanillaAnimalAi(Animal animal, ServerLevel level, boolean directThreat) {
        if (animal == null) return true;
        if (animal.isLeashed() || animal.isVehicle() || animal.isPassenger()) {
            return true;
        }
        if (!directThreat && level != null) {
            AABB playerArea = animal.getBoundingBox().inflate(10.0D, 4.0D, 10.0D);
            List<net.minecraft.world.entity.player.Player> players = level.getEntitiesOfClass(
                net.minecraft.world.entity.player.Player.class, playerArea, net.minecraft.world.entity.player.Player::isAlive);
            for (var player : players) {
                if (isValidSpeciesBreedingFood(animal, player.getMainHandItem())
                    || isValidSpeciesBreedingFood(animal, player.getOffhandItem())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static LivingEntity findNearestPredatorOrThreat(Animal animal, ServerLevel level, AABB area) {
        List<MobThreatCandidate> candidates = level.getEntitiesOfClass(LivingEntity.class, area,
            entity -> {
                if (entity == animal || !entity.isAlive()) return false;
                if (entity instanceof Enemy) return true;
                if (animal.getLastHurtByMob() == entity) return true;
                if ((entity instanceof Wolf || entity instanceof Fox || entity instanceof Ocelot)
                    && (animal instanceof Sheep || animal instanceof Chicken || animal instanceof Rabbit)) {
                    return true;
                }
                return false;
            }).stream()
            .map(e -> new MobThreatCandidate(e, animal.distanceToSqr(e)))
            .sorted(Comparator.comparingDouble(MobThreatCandidate::distSqr))
            .toList();
        return candidates.isEmpty() ? null : candidates.get(0).entity();
    }

    private record MobThreatCandidate(LivingEntity entity, double distSqr) {}

    public static Animal findNearbyOffspring(Animal adult, ServerLevel level, AABB area) {
        List<Animal> babies = level.getEntitiesOfClass(Animal.class, area,
            other -> other != adult && other.isAlive() && other.isBaby()
                && other.getType() == adult.getType());
        if (babies.isEmpty()) return null;
        String adultUuid = adult.getUUID().toString();
        for (Animal baby : babies) {
            if (adultUuid.equals(baby.getPersistentData().getString(PARENT_UUID_KEY))) {
                return baby;
            }
        }
        babies.sort(Comparator.comparingDouble(adult::distanceToSqr));
        return babies.get(0);
    }

    public static Animal findNearbyParent(Animal baby, ServerLevel level, AABB area) {
        List<Animal> adults = level.getEntitiesOfClass(Animal.class, area,
            other -> other != baby && other.isAlive() && !other.isBaby()
                && other.getType() == baby.getType());
        if (adults.isEmpty()) return null;
        String parentId = baby.getPersistentData().getString(PARENT_UUID_KEY);
        if (!parentId.isEmpty()) {
            for (Animal adult : adults) {
                if (parentId.equals(adult.getUUID().toString())) {
                    return adult;
                }
            }
        }
        adults.sort(Comparator.comparingDouble(baby::distanceToSqr));
        return adults.get(0);
    }

    public static boolean hasOverheadShelter(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return false;
        int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        return topY > pos.getY() + 1;
    }

    public static BlockPos findNearestShelterBlock(ServerLevel level, BlockPos origin, int radius) {
        if (level == null || origin == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos candidate = origin.offset(dx, 0, dz);
                if (!level.hasChunkAt(candidate)) continue;
                if (hasOverheadShelter(level, candidate)
                    && level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.below()).blocksMotion()) {
                    double d = origin.distSqr(candidate);
                    if (d < bestDist) {
                        bestDist = d;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return best;
    }

    public static BlockPos findNearbyForageBlock(ServerLevel level, BlockPos origin, int radius) {
        if (level == null || origin == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.GRASS_BLOCK)
                        || state.is(Blocks.HAY_BLOCK)
                        || state.is(Blocks.SHORT_GRASS)
                        || state.is(Blocks.WHEAT)
                        || state.is(Blocks.CARROTS)
                        || state.is(Blocks.POTATOES)) {
                        double d = origin.distSqr(pos);
                        if (d < bestDist) {
                            bestDist = d;
                            best = pos.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Checks species-appropriate breeding feed while preserving vanilla {@code Animal.isFood}.
     */
    public static boolean isValidSpeciesBreedingFood(Animal animal, ItemStack stack) {
        if (animal == null || stack == null || stack.isEmpty()) return false;
        if (animal.isFood(stack)) {
            return true;
        }
        if (animal instanceof Cow || animal instanceof Sheep || animal instanceof Goat) {
            return stack.is(Items.WHEAT);
        }
        if (animal instanceof Pig) {
            return stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.BEETROOT);
        }
        if (animal instanceof Chicken) {
            return stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS)
                || stack.is(Items.MELON_SEEDS) || stack.is(Items.PUMPKIN_SEEDS);
        }
        if (animal instanceof Rabbit) {
            return stack.is(Items.CARROT) || stack.is(Items.GOLDEN_CARROT) || stack.is(Items.DANDELION);
        }
        return false;
    }

    /**
     * Natural pre-breeding behavior: animals in love mode face their mate and move close together.
     */
    public static boolean performPreBreedingCourtship(Animal animal, ServerLevel level, AABB area) {
        if (animal == null || level == null || !animal.isInLove()) return false;
        List<Animal> mates = level.getEntitiesOfClass(Animal.class, area,
            other -> other != animal && other.isAlive() && !other.isBaby()
                && other.getType() == animal.getType() && other.isInLove());
        if (mates.isEmpty()) return false;
        mates.sort(Comparator.comparingDouble(animal::distanceToSqr));
        Animal mate = mates.get(0);
        animal.getLookControl().setLookAt(mate, 30.0F, 30.0F);
        mate.getLookControl().setLookAt(animal, 30.0F, 30.0F);
        animal.getNavigation().moveTo(mate, 1.05D);
        level.sendParticles(ParticleTypes.HEART,
            animal.getX(), animal.getY() + 0.8D, animal.getZ(), 1,
            0.2D, 0.2D, 0.2D, 0.0D);
        return true;
    }

    /**
     * Registers parent-child bonds and derives an individual offspring trait when a baby spawns.
     */
    public static int registerOffspringFromParents(
            Animal parentA,
            Animal parentB,
            AgeableMob child,
            long nowTick) {
        if (parentA == null || child == null) return 0;
        CompoundTag childData = child.getPersistentData();
        childData.putString(PARENT_UUID_KEY, parentA.getUUID().toString());

        int traitA = parentA.getPersistentData().getInt(OFFSPRING_TRAIT_KEY);
        int traitB = parentB != null ? parentB.getPersistentData().getInt(OFFSPRING_TRAIT_KEY) : 0;
        int derivedTrait = TrustAndAnimalModel.deriveOffspringTrait(
            traitA,
            traitB,
            child.getUUID().hashCode()
        );
        childData.putInt(OFFSPRING_TRAIT_KEY, derivedTrait);

        if (derivedTrait == TrustAndAnimalModel.OFFSPRING_TRAIT_HARDY) {
            child.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 0));
        } else if (derivedTrait == TrustAndAnimalModel.OFFSPRING_TRAIT_SWIFT) {
            child.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0));
        }

        parentA.getPersistentData().putLong(PROTECTING_CHILD_UNTIL_KEY, nowTick + PARENTAL_BOND_DURATION_TICKS);
        if (parentB != null) {
            parentB.getPersistentData().putLong(PROTECTING_CHILD_UNTIL_KEY, nowTick + PARENTAL_BOND_DURATION_TICKS);
            parentA.getNavigation().moveTo(child, 1.0D);
            parentB.getNavigation().moveTo(child, 1.0D);
        }
        return derivedTrait;
    }

    /**
     * Advances young animal maturation towards adulthood (0 age ticks) when cared for near a parent
     * or sheltered/fed.
     */
    public static int stepOffspringGrowth(Animal baby, ServerLevel level, long nowTick) {
        if (baby == null || level == null || !baby.isBaby()) return 0;
        int currentAge = baby.getAge();
        if (currentAge >= 0) return 0;

        AABB area = baby.getBoundingBox().inflate(10.0D, 4.0D, 10.0D);
        boolean nearParent = findNearbyParent(baby, level, area) != null;
        boolean shelteredOrFed = hasOverheadShelter(level, baby.blockPosition())
            || findNearbyForageBlock(level, baby.blockPosition(), 6) != null;
        int trait = baby.getPersistentData().getInt(OFFSPRING_TRAIT_KEY);

        int advanceTicks = TrustAndAnimalModel.computeMaturationTicks(
            currentAge,
            nearParent,
            shelteredOrFed,
            trait
        );
        if (advanceTicks > 0) {
            baby.setAge(Math.min(0, currentAge + advanceTicks));
        }
        return advanceTicks;
    }

    public static void onAnimalInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof Animal animal) || animal.level().isClientSide()) {
            return;
        }
        if (!LivingVillagesConfig.ENABLE_ANIMAL_BEHAVIOR.get()
            || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
            || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return;
        }
        ItemStack held = player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (player.isShiftKeyDown() && held.isEmpty()) {
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            event.setCanceled(true);
            player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(describeAnimalStatus(animal))
                    .withStyle(net.minecraft.ChatFormatting.GRAY)
            );
            return;
        }
        if (isValidSpeciesBreedingFood(animal, held) && animal.level() instanceof ServerLevel serverLevel) {
            recordLivestockCareTrustBonus(serverLevel, animal.blockPosition(), player.getUUID());
        }
    }

    public static int recordLivestockCareTrustBonus(ServerLevel level, BlockPos pos, UUID playerId) {
        if (level == null || pos == null || playerId == null) return 0;
        AABB area = new AABB(pos).inflate(12.0D, 5.0D, 12.0D);
        List<net.minecraft.world.entity.npc.Villager> villagers = level.getEntitiesOfClass(
            net.minecraft.world.entity.npc.Villager.class, area, net.minecraft.world.entity.npc.Villager::isAlive);
        int affected = 0;
        for (var villager : villagers) {
            VillagerSoul.recordTrustEvent(villager, playerId, TrustAndAnimalModel.EVENT_HELP_OR_GIFT, 2, false);
            affected++;
        }
        return affected;
    }

    public static String describeAnimalStatus(Animal animal) {
        if (animal == null) return "";
        CompoundTag data = animal.getPersistentData();
        String ageText = animal.isBaby() ? "Детёныш" : "Взрослое";
        String traitText = offspringTraitNameRu(data.getInt(OFFSPRING_TRAIT_KEY));
        String stateText = animalActionNameRu(data.getInt(LAST_ACTION_KEY));
        return "Животное: " + ageText + " · Особенность: " + traitText + " · Состояние: " + stateText;
    }

    public static String offspringTraitNameRu(int trait) {
        return switch (trait) {
            case TrustAndAnimalModel.OFFSPRING_TRAIT_HARDY -> "Выносливый";
            case TrustAndAnimalModel.OFFSPRING_TRAIT_SWIFT -> "Быстрый";
            case TrustAndAnimalModel.OFFSPRING_TRAIT_CALM -> "Спокойный";
            case TrustAndAnimalModel.OFFSPRING_TRAIT_VIGOROUS -> "Жизнелюбивый";
            default -> "Обычный";
        };
    }

    public static String animalActionNameRu(int action) {
        return switch (action) {
            case TrustAndAnimalModel.ANIMAL_ACTION_FLEE_THREAT -> "Убегает от опасности";
            case TrustAndAnimalModel.ANIMAL_ACTION_PROTECT_OFFSPRING -> "Защищает потомство";
            case TrustAndAnimalModel.ANIMAL_ACTION_FOLLOW_PARENT -> "Следует за родителем";
            case TrustAndAnimalModel.ANIMAL_ACTION_SEEK_SHELTER -> "Ищет укрытие";
            case TrustAndAnimalModel.ANIMAL_ACTION_FORAGE_OR_GRAZE -> "Ищет корм / пасётся";
            case TrustAndAnimalModel.ANIMAL_ACTION_COURTSHIP -> "Брачное сближение";
            default -> "Спокойно";
        };
    }
}
