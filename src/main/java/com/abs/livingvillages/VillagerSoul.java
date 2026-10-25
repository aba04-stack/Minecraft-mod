package com.abs.livingvillages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;
import java.util.UUID;

/**
 * Persistent personality and social memory for vanilla villagers.
 * All decisions that change the world are made on the logical server.
 */
public final class VillagerSoul {
    private static final String SOUL_KEY = "livingvillages_soul";
    private static final String RELATIONSHIPS_KEY = "relationships";
    private static final int PERSONALITY_COUNT = 6;
    private static final long MOOD_INTERVAL_TICKS = 200L;
    private static final long SOCIAL_INTERVAL_TICKS = 600L;
    private static final String[] NAMES = {
        "Мира", "Лев", "Ника", "Тим", "Яна", "Илья", "Саша", "Рада",
        "Марк", "Лина", "Миша", "Ася", "Арсен", "Дана", "Роман", "Ева",
        "Борис", "Ира", "Кирилл", "Оля", "Тая", "Марат", "Алиса", "Федя",
        "Лука", "Вера", "Семен", "Зоя", "Дамир", "Лада"
    };
    private static final String[] PERSONALITIES = {
        "Трудолюбивый", "Дружелюбный", "Любопытный", "Осторожный", "Весельчак", "Мечтатель"
    };

    private VillagerSoul() {
    }

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()) {
            return;
        }
        if (!LivingVillagesConfig.ENABLE_PERSONALITIES.get()) {
            return;
        }

        CompoundTag soul = getSoul(villager);
        applyNameSetting(villager, soul);

        long now = villager.level().getGameTime();
        long lastMoodUpdate = soul.getLong("lastMoodUpdate");
        if (now - lastMoodUpdate >= MOOD_INTERVAL_TICKS) {
            updateMood(villager, soul, now);
            if (LivingVillagesConfig.ENABLE_RELATIONSHIP_MEMORY.get()) {
                decayAllRelationships(soul, villager.level().getDayTime() / 24000L);
            }
            soul.putLong("lastMoodUpdate", now);
            saveSoul(villager, soul);
        }
    }

    public static void onVillagerInteract(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof Villager villager)) {
            return;
        }
        // Do not cancel the client-side event: the server must receive the interaction packet.
        if (villager.level().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!LivingVillagesConfig.ENABLE_SNEAK_TO_TALK.get()
            || !player.isShiftKeyDown()
            || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);

        CompoundTag soul = getSoul(villager);
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (isGift(held)) {
            giveGift(villager, player, held, soul);
        } else {
            talk(villager, player, soul);
        }
    }

    public static void onVillagerDamaged(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()) {
            return;
        }
        CompoundTag soul = getSoul(villager);
        if (LivingVillagesConfig.ENABLE_PERSONALITIES.get()) {
            int mood = clamp(soul.getInt("mood") - 18, 0, 100);
            soul.putInt("mood", mood);
        }
        soul.putLong("lastThreatTime", villager.level().getGameTime());

        Entity attacker = event.getSource().getEntity();
        if (attacker instanceof Player player && LivingVillagesConfig.ENABLE_RELATIONSHIP_MEMORY.get()) {
            rememberAttack(villager, player, soul, 32, 35);
            // Nearby villagers witness violence and can become wary of the attacker too.
            if (villager.level() instanceof ServerLevel serverLevel) {
                List<Villager> witnesses = serverLevel.getEntitiesOfClass(
                    Villager.class, villager.getBoundingBox().inflate(10.0D), other -> other != villager);
                for (Villager witness : witnesses) {
                    CompoundTag witnessSoul = getSoul(witness);
                    rememberAttack(witness, player, witnessSoul, 8, 12);
                    witnessSoul.putInt("mood", clamp(witnessSoul.getInt("mood") - 8, 0, 100));
                    saveSoul(witness, witnessSoul);
                }
            }
        }

        if (villager.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                villager.getX(), villager.getY() + 1.0D, villager.getZ(), 2,
                0.25D, 0.25D, 0.25D, 0.0D);
        }
        saveSoul(villager, soul);
    }

    private static void updateMood(Villager villager, CompoundTag soul, long now) {
        Level level = villager.level();
        int mood = soul.getInt("mood");
        int personality = soul.getInt("personality");
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);

        if (villager.isSleeping()) {
            mood += 2;
        } else if (dayTime >= 13000L && dayTime < 23000L) {
            mood -= 1; // They prefer settling down at night.
        } else if (dayTime >= 1000L && dayTime < 12000L) {
            mood += personality == 4 ? 2 : 1;
        }

        if (level.isRainingAt(villager.blockPosition())) {
            mood -= 1;
        }
        if (villager.getHealth() < villager.getMaxHealth() * 0.75F) {
            mood -= 2;
        }
        if (villager.isBaby()) {
            mood += 1;
        }
        if (personality == 4) {
            mood += 1; // Cheerful villagers recover their spirits more easily.
        }

        if (now - soul.getLong("lastSocialTime") >= SOCIAL_INTERVAL_TICKS) {
            soul.putLong("lastSocialTime", now);
            AABB socialArea = villager.getBoundingBox().inflate(5.0D, 2.0D, 5.0D);
            List<Villager> nearby = level.getEntitiesOfClass(
                Villager.class, socialArea, other -> other != villager && other.isAlive());
            if (!nearby.isEmpty()) {
                mood += personality == 1 ? 3 : 1;
                if (level instanceof ServerLevel serverLevel && villager.getRandom().nextFloat() < 0.06F) {
                    serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                        villager.getX(), villager.getY() + 1.0D, villager.getZ(), 1,
                        0.2D, 0.25D, 0.2D, 0.0D);
                }
            }
        }

        soul.putInt("mood", clamp(mood, 0, 100));
    }

    private static void talk(Villager villager, ServerPlayer player, CompoundTag soul) {
        long day = villager.level().getDayTime() / 24000L;
        boolean hasMemory = LivingVillagesConfig.ENABLE_RELATIONSHIP_MEMORY.get();
        CompoundTag relationship = hasMemory
            ? getRelationship(soul, player.getUUID(), soul.getInt("personality"))
            : new CompoundTag();
        int fear = hasMemory ? decayFearIfNeeded(relationship, day) : 0;
        int trustBefore = hasMemory ? relationship.getInt("trust") : 0;
        long lastTalkDay = relationship.contains("lastTalkDay") ? relationship.getLong("lastTalkDay") : -1L;
        boolean talkedToday = lastTalkDay == day;

        if (hasMemory) {
            if (!talkedToday) {
                int trustGain = soul.getInt("personality") == 1 ? 3 : 2;
                relationship.putInt("trust", clamp(trustBefore + trustGain, 0, 100));
            }
            relationship.putLong("lastTalkDay", day);
            relationship.putInt("talkCount", relationship.getInt("talkCount") + 1);
            relationship.putLong("lastSeenDay", day);
            saveRelationship(soul, player.getUUID(), relationship);
        }
        saveSoul(villager, soul);

        String line = LivingVillagesConfig.ENABLE_PERSONALITIES.get()
            ? chooseDialogue(villager, soul, relationship, day, talkedToday, fear, hasMemory)
            : chooseSimpleDialogue(villager, talkedToday, fear, hasMemory);
        String name = soul.getString("name");
        player.sendSystemMessage(Component.literal(name).withStyle(ChatFormatting.GOLD)
            .append(Component.literal(": " + line).withStyle(ChatFormatting.WHITE)));

        String status = LivingVillagesConfig.ENABLE_PERSONALITIES.get()
            ? "Характер: " + personalityName(soul.getInt("personality"))
                + " · Настроение: " + moodName(soul.getInt("mood"))
            : "Система характера и настроения отключена";
        if (hasMemory) {
            status += " · Отношения: " + relationshipName(relationship)
                + " · Доверие: " + relationship.getInt("trust") + "/100";
        }
        player.sendSystemMessage(Component.literal(status).withStyle(ChatFormatting.GRAY));

        if (hasMemory && !relationship.getString("lastGift").isEmpty()
            && day - (relationship.contains("lastGiftDay") ? relationship.getLong("lastGiftDay") : -10L) <= 3L) {
            player.sendSystemMessage(Component.literal("Память: " + relationship.getString("lastGift")
                + " — твой недавний подарок.").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static String chooseSimpleDialogue(Villager villager, boolean talkedToday, int fear, boolean hasMemory) {
        if (hasMemory && fear >= 25) {
            return "Я помню, что ты меня напугал. Мне нужно время, чтобы снова тебе доверять.";
        }
        long time = Math.floorMod(villager.level().getDayTime(), 24000L);
        if (villager.isBaby()) return "Привет! Я пока учусь всему у взрослых.";
        if (villager.level().isRainingAt(villager.blockPosition())) return "Дождь пошёл. Хорошо, что у нас есть крыша.";
        if (time >= 23000L || time < 1000L || villager.isSleeping()) return "Уже поздно. Давай поговорим завтра.";
        if (talkedToday) return "Мы уже здоровались сегодня. Рад снова тебя видеть.";
        return "Привет! Я живу в этой деревне. Надеюсь, у тебя хороший день.";
    }

    private static String chooseDialogue(Villager villager, CompoundTag soul, CompoundTag relationship,
                                         long day, boolean talkedToday, int fear, boolean hasMemory) {
        long time = Math.floorMod(villager.level().getDayTime(), 24000L);
        int mood = soul.getInt("mood");
        int personality = soul.getInt("personality");

        if (fear >= 25 && hasMemory) {
            return switch (personality) {
                case 1 -> "Я хочу снова тебе доверять, но помню, как ты меня напугал. Давай начнём с доброты.";
                case 3 -> "Пожалуйста, не подходи так резко. Я пока не уверен, что рядом с тобой безопасно.";
                default -> "Я помню, что рядом с тобой было больно. Мне нужно время, чтобы снова поверить тебе.";
            };
        }
        if (villager.isBaby()) {
            return "Хи-хи! Я хочу поиграть, но взрослые говорят, что пора домой.";
        }
        if (villager.getHealth() < villager.getMaxHealth() * 0.7F) {
            return "Сегодня я не в лучшей форме. Надеюсь, скоро смогу снова заняться делом.";
        }
        if (villager.isSleeping() || time >= 23000L || time < 1000L) {
            return "День был долгим. Надеюсь, завтра у нашей деревни будет хороший день.";
        }
        if (villager.level().isRainingAt(villager.blockPosition())) {
            return personality == 2
                ? "Дождь! Интересно, как он влияет на урожай и землю?"
                : "Хорошо, что у нас есть крыша. В такую погоду приятно немного передохнуть.";
        }
        if (villager.level().getGameTime() - soul.getLong("lastThreatTime") < 1200L) {
            return personality == 3
                ? "После недавней опасности я предпочитаю держаться поближе к дому."
                : "Недавно здесь было тревожно. Хорошо, что сейчас можно немного выдохнуть.";
        }
        if (hasMemory && relationship.getInt("talkCount") > 1 && talkedToday) {
            return personality == 5
                ? "Мы сегодня уже немного помечтали вместе. Иногда тихая беседа — это всё, что нужно."
                : "Рад снова тебя видеть. Ты уже спрашивал, как у меня дела, но поболтать ещё приятно.";
        }
        if (personality == 0 && villager.getVillagerData().getProfession() == net.minecraft.world.entity.npc.VillagerProfession.FARMER) {
            return "Я слежу за полями. Если будем беречь семена и собирать урожай вовремя, голод нам не страшен.";
        }
        if (personality == 2 && villager.getVillagerData().getProfession() == net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN) {
            return "Недавно я узнал кое-что интересное. Иногда одна книга может изменить взгляд на целый мир.";
        }
        if (villager.getVillagerData().getProfession() == net.minecraft.world.entity.npc.VillagerProfession.CLERIC) {
            return "Каждому в деревне нужна надежда. Даже в трудные дни мы должны заботиться друг о друге.";
        }
        if (mood < 25) {
            return "Честно говоря, я немного устал. Надеюсь, завтра будет спокойнее.";
        }

        return switch (personality) {
            case 0 -> time < 12000L
                ? "С утра много дел. Если каждый внесёт свой вклад, наша деревня станет лучше."
                : "Я хочу закончить работу до вечера. Хороший труд делает деревню крепче.";
            case 1 -> "Приятно, когда кто-то останавливается поговорить. Как твои дела, друг?";
            case 2 -> "Я всё думаю, что находится за холмами. Когда-нибудь хочется увидеть новые места.";
            case 3 -> "Деревня выглядит спокойно, но лучше держать запасы и следить за окрестностями.";
            case 4 -> "Хороший день! Даже простая прогулка кажется приключением, когда настроение отличное.";
            default -> "Иногда я представляю, какой станет наша деревня через много лет. Надеюсь, она будет прекрасной.";
        };
    }

    private static void giveGift(Villager villager, ServerPlayer player, ItemStack held, CompoundTag soul) {
        int giftValue = giftValue(held);
        String giftName = held.getHoverName().getString();
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }

        if (LivingVillagesConfig.ENABLE_PERSONALITIES.get()) {
            int mood = clamp(soul.getInt("mood") + 8, 0, 100);
            soul.putInt("mood", mood);
        }
        long day = villager.level().getDayTime() / 24000L;
        boolean hasMemory = LivingVillagesConfig.ENABLE_RELATIONSHIP_MEMORY.get();
        CompoundTag relationship = hasMemory
            ? getRelationship(soul, player.getUUID(), soul.getInt("personality"))
            : new CompoundTag();
        if (hasMemory) {
            int fear = decayFearIfNeeded(relationship, day);
            relationship.putInt("trust", clamp(relationship.getInt("trust") + giftValue, 0, 100));
            relationship.putInt("fear", Math.max(0, fear - Math.max(5, giftValue / 2)));
            relationship.putString("lastGift", giftName);
            relationship.putLong("lastGiftDay", day);
            relationship.putLong("fearDecayDay", day);
            relationship.putLong("lastSeenDay", day);
            relationship.putInt("giftCount", relationship.getInt("giftCount") + 1);
            saveRelationship(soul, player.getUUID(), relationship);
        }
        saveSoul(villager, soul);

        player.sendSystemMessage(Component.literal(soul.getString("name")).withStyle(ChatFormatting.GOLD)
            .append(Component.literal(": Спасибо за " + giftName + "! Мне очень приятно.").withStyle(ChatFormatting.WHITE)));
        if (hasMemory) {
            player.sendSystemMessage(Component.literal("Отношения улучшились. Доверие: "
                + relationship.getInt("trust") + "/100.").withStyle(ChatFormatting.GREEN));
        }
        if (villager.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                villager.getX(), villager.getY() + 1.0D, villager.getZ(), 5,
                0.35D, 0.35D, 0.35D, 0.0D);
        }
    }

    private static boolean isGift(ItemStack stack) {
        return giftValue(stack) > 0;
    }

    private static int giftValue(ItemStack stack) {
        if (stack.is(Items.EMERALD)) return 12;
        if (stack.is(Items.BREAD)) return 9;
        if (stack.is(Items.GOLD_INGOT)) return 8;
        if (stack.is(Items.POPPY) || stack.is(Items.DANDELION) || stack.is(Items.CORNFLOWER)
            || stack.is(Items.BLUE_ORCHID) || stack.is(Items.ALLIUM) || stack.is(Items.AZURE_BLUET)
            || stack.is(Items.RED_TULIP) || stack.is(Items.ORANGE_TULIP) || stack.is(Items.WHITE_TULIP)
            || stack.is(Items.PINK_TULIP) || stack.is(Items.OXEYE_DAISY) || stack.is(Items.LILY_OF_THE_VALLEY)) {
            return 7;
        }
        if (stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.APPLE)) return 5;
        if (stack.is(Items.WHEAT) || stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS)) return 3;
        return 0;
    }

    private static void rememberAttack(Villager villager, Player player, CompoundTag soul, int trustLoss, int fearGain) {
        if (!LivingVillagesConfig.ENABLE_RELATIONSHIP_MEMORY.get()) {
            return;
        }
        long day = villager.level().getDayTime() / 24000L;
        CompoundTag relationship = getRelationship(soul, player.getUUID(), soul.getInt("personality"));
        decayFearIfNeeded(relationship, day);
        relationship.putInt("trust", clamp(relationship.getInt("trust") - trustLoss, 0, 100));
        relationship.putInt("fear", clamp(relationship.getInt("fear") + fearGain, 0, 100));
        relationship.putLong("fearDecayDay", day);
        relationship.putLong("lastHurtDay", day);
        relationship.putLong("lastSeenDay", day);
        saveRelationship(soul, player.getUUID(), relationship);
        saveSoul(villager, soul);
    }

    private static int decayFearIfNeeded(CompoundTag relationship, long day) {
        long lastDecay = relationship.contains("fearDecayDay") ? relationship.getLong("fearDecayDay") : day;
        if (lastDecay < day) {
            long elapsedDays = Math.min(30L, day - lastDecay);
            relationship.putInt("fear", Math.max(0, relationship.getInt("fear") - (int) elapsedDays * 4));
            relationship.putLong("fearDecayDay", day);
        }
        return relationship.getInt("fear");
    }

    private static void decayAllRelationships(CompoundTag soul, long day) {
        CompoundTag relationships = soul.getCompound(RELATIONSHIPS_KEY);
        for (String playerId : relationships.getAllKeys()) {
            CompoundTag relationship = relationships.getCompound(playerId);
            decayFearIfNeeded(relationship, day);
            relationships.put(playerId, relationship);
        }
        soul.put(RELATIONSHIPS_KEY, relationships);
    }

    private static CompoundTag getRelationship(CompoundTag soul, UUID playerId, int personality) {
        CompoundTag relationships = soul.getCompound(RELATIONSHIPS_KEY);
        String key = playerId.toString();
        CompoundTag relationship = relationships.getCompound(key);
        if (!relationships.contains(key)) {
            relationship = new CompoundTag();
            relationship.putInt("trust", personality == 1 ? 5 : 0);
            relationship.putInt("fear", 0);
            relationship.putLong("lastTalkDay", -1L);
            relationship.putLong("lastSeenDay", -1L);
            relationship.putLong("fearDecayDay", 0L);
            relationship.putString("lastGift", "");
            relationships.put(key, relationship);
            soul.put(RELATIONSHIPS_KEY, relationships);
        }
        return relationship;
    }

    private static void saveRelationship(CompoundTag soul, UUID playerId, CompoundTag relationship) {
        CompoundTag relationships = soul.getCompound(RELATIONSHIPS_KEY);
        relationships.put(playerId.toString(), relationship);
        soul.put(RELATIONSHIPS_KEY, relationships);
    }

    /** Returns the persistent personal name, initializing personality data if needed. */
    public static String getPersonalName(Villager villager) {
        return getSoul(villager).getString("name");
    }

    private static CompoundTag getSoul(Villager villager) {
        CompoundTag root = villager.getPersistentData();
        CompoundTag soul;
        if (root.contains(SOUL_KEY)) {
            soul = root.getCompound(SOUL_KEY);
        } else {
            soul = new CompoundTag();
            root.put(SOUL_KEY, soul);
        }
        if (!soul.getBoolean("initialized")) {
            soul.putBoolean("initialized", true);
            soul.putString("name", villager.hasCustomName()
                ? villager.getCustomName().getString()
                : NAMES[villager.getRandom().nextInt(NAMES.length)]);
            soul.putInt("personality", villager.getRandom().nextInt(PERSONALITY_COUNT));
            soul.putInt("mood", 45 + villager.getRandom().nextInt(36));
            soul.putLong("lastMoodUpdate", villager.level().getGameTime());
            soul.putLong("lastSocialTime", villager.level().getGameTime());
            soul.putLong("lastThreatTime", -100000L);
            soul.putBoolean("modAssignedName", false);
            root.put(SOUL_KEY, soul);
        }
        return soul;
    }

    private static void saveSoul(Villager villager, CompoundTag soul) {
        villager.getPersistentData().put(SOUL_KEY, soul);
    }

    private static void applyNameSetting(Villager villager, CompoundTag soul) {
        // The elder has a separate visible title; don't overwrite it with the ordinary nameplate.
        if (VillageElder.isElder(villager)) return;
        boolean showNames = LivingVillagesConfig.SHOW_PERSONALITY_NAMES.get();
        boolean assigned = soul.getBoolean("modAssignedName");
        String name = soul.getString("name");

        if (assigned) {
            if (villager.getCustomName() == null || !villager.getCustomName().getString().equals(name)) {
                // A player or another mod renamed this villager; do not overwrite that choice.
                soul.putBoolean("modAssignedName", false);
                saveSoul(villager, soul);
                return;
            }
            if (showNames) {
                villager.setCustomNameVisible(true);
            } else {
                villager.setCustomName(null);
                villager.setCustomNameVisible(false);
                soul.putBoolean("modAssignedName", false);
                saveSoul(villager, soul);
            }
        } else if (showNames && !villager.hasCustomName()) {
            villager.setCustomName(Component.literal(name));
            villager.setCustomNameVisible(true);
            soul.putBoolean("modAssignedName", true);
            saveSoul(villager, soul);
        }
    }

    private static String personalityName(int personality) {
        return PERSONALITIES[Math.floorMod(personality, PERSONALITY_COUNT)];
    }

    private static String moodName(int mood) {
        if (mood >= 75) return "прекрасное";
        if (mood >= 50) return "хорошее";
        if (mood >= 25) return "уставшее";
        return "подавленное";
    }

    private static String relationshipName(CompoundTag relationship) {
        if (relationship.getInt("fear") >= 25) return "настороженность";
        int trust = relationship.getInt("trust");
        if (trust >= 70) return "друг";
        if (trust >= 30) return "знакомый";
        return "почти незнакомец";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
