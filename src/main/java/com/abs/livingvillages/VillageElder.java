package com.abs.livingvillages;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The village elder is an ordinary vanilla villager with a persistent leadership marker.
 * The elder keeps their vanilla profession/trading; the role only adds a small management panel.
 * Menu commands are server-validated and store orders on the elected villager until resources exist.
 */
public final class VillageElder {
    public static final String ELDER_KEY = "livingvillages_is_elder";
    public static final String AUTO_KEY = "livingvillages_elder_auto";
    public static final String PRIORITY_KEY = "livingvillages_elder_priority";
    public static final String REQUEST_KEY = "livingvillages_elder_request";
    public static final String NOTICE_KEY = "livingvillages_elder_notice";
    private static final String ORIGINAL_NAME_KEY = "livingvillages_elder_original_name";
    private static final String ORIGINAL_VISIBLE_KEY = "livingvillages_elder_original_visible";
    private static final String PERSONAL_NAME_KEY = "livingvillages_elder_personal_name";

    public static final int PRIORITY_BALANCED = 0;
    public static final int PRIORITY_HOUSING = 1;
    public static final int PRIORITY_FOOD = 2;
    public static final int PRIORITY_DEFENSE = 3;

    public static final int REQUEST_NONE = 0;
    public static final int REQUEST_HOUSE = 1;
    public static final int REQUEST_FARM = 2;

    private static final int ACTION_TOGGLE_AUTO = 0;
    private static final int ACTION_CYCLE_PRIORITY = 1;
    private static final int ACTION_REQUEST_HOUSE = 2;
    private static final int ACTION_REQUEST_FARM = 3;
    private static final int ACTION_DEFENSE_PRIORITY = 4;
    private static final int ACTION_BALANCED_PRIORITY = 5;
    private static final int INTERACTION_RADIUS_SQR = 64;
    private static final int FOOD_POINTS_PER_BREAD = 4;

    private VillageElder() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(ElderPanelData.TYPE, ElderPanelData.STREAM_CODEC, VillageElder::handlePanelOnClient);
        registrar.playToServer(ElderAction.TYPE, ElderAction.STREAM_CODEC, VillageElder::handleActionOnServer);
    }

    public static boolean isElder(Villager villager) {
        return villager != null && villager.getPersistentData().getBoolean(ELDER_KEY);
    }

    /** Elect at most one adult elder in the active village area; prefer established workers. */
    public static void ensureElder(ServerLevel level, BlockPos center, List<Villager> candidates) {
        if (!LivingVillagesConfig.ENABLE_VILLAGE_ELDER.get()) {
            for (Villager villager : candidates) {
                if (isElder(villager)) releaseElder(villager);
            }
            return;
        }

        List<Villager> adults = candidates.stream()
            .filter(v -> v.isAlive() && !v.isBaby())
            .toList();
        if (adults.isEmpty()) return;

        List<Villager> existing = adults.stream().filter(VillageElder::isElder)
            .sorted(Comparator.comparing(v -> v.getUUID().toString()))
            .toList();
        Villager elected;
        if (!existing.isEmpty()) {
            // Keep a previously elected elder if the village temporarily loses population.
            elected = existing.get(0);
            for (int i = 1; i < existing.size(); i++) releaseElder(existing.get(i));
        } else {
            // A lone wandering villager should not automatically become a village leader.
            if (adults.size() < LivingVillagesConfig.MINIMUM_VILLAGERS.get()) return;
            elected = adults.stream()
                .min(Comparator
                    .comparingInt(VillageElder::candidatePenalty)
                    .thenComparing(v -> v.getUUID().toString()))
                .orElse(null);
            if (elected == null) return;
            appoint(elected);
        }

        normalizePendingRequest(elected);

        // Keep the label visible without altering the vanilla profession or trade data.
        String personalName = elected.getPersistentData().getString(PERSONAL_NAME_KEY);
        if (personalName.isBlank()) {
            personalName = VillagerSoul.getPersonalName(elected);
            elected.getPersistentData().putString(PERSONAL_NAME_KEY, personalName);
        }
        Component title = Component.translatable("livingvillages.elder.name", personalName);
        if (elected.getCustomName() == null || !elected.getCustomName().equals(title)) {
            elected.setCustomName(title);
            elected.setCustomNameVisible(true);
        }
    }

    /** Drop queued orders that can no longer run because their feature was disabled in config. */
    private static void normalizePendingRequest(Villager elder) {
        var data = elder.getPersistentData();
        int request = data.getInt(REQUEST_KEY);
        if (request == REQUEST_HOUSE
            && (!LivingVillagesConfig.ENABLE_HOUSING_PROJECTS.get()
                || !LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get())) {
            data.putInt(REQUEST_KEY, REQUEST_NONE);
            data.putString(NOTICE_KEY, "Заказ дома отменён: строительство домов отключено в настройках мода.");
        } else if (request == REQUEST_FARM && !LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) {
            data.putInt(REQUEST_KEY, REQUEST_NONE);
            data.putString(NOTICE_KEY, "Заказ фермы отменён: строительство ферм отключено в настройках мода.");
        }
    }

    private static int candidatePenalty(Villager villager) {
        int penalty = 0;
        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT) penalty += 2;
        if (!villager.getPersistentData().getString(VillagerRoles.ROLE_KEY).isEmpty()) penalty += 4;
        if (hasOtherTask(villager)) penalty += 8;
        return penalty;
    }

    private static boolean hasOtherTask(Villager villager) {
        var data = villager.getPersistentData();
        return data.getBoolean(VillageBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageFarmBuilder.ACTIVE_KEY)
            || data.getBoolean(VillageCraftingJobs.ACTIVE_KEY)
            || data.getBoolean(VillagePopulation.ACTIVE_KEY)
            || data.getBoolean("livingvillages_treework_active")
            || data.getBoolean("livingvillages_mining_active")
            || data.getBoolean("livingvillages_defense_has_route");
    }

    private static void appoint(Villager villager) {
        var data = villager.getPersistentData();
        // Initialize the personality first, while the villager still has the original name.
        String personalName = VillagerSoul.getPersonalName(villager);
        data.putBoolean(ELDER_KEY, true);
        data.putString(PERSONAL_NAME_KEY, personalName);
        data.putString(ORIGINAL_NAME_KEY, villager.hasCustomName() ? villager.getCustomName().getString() : "");
        data.putBoolean(ORIGINAL_VISIBLE_KEY, villager.isCustomNameVisible());
        if (!data.contains(AUTO_KEY)) data.putBoolean(AUTO_KEY, true);
        if (!data.contains(PRIORITY_KEY)) data.putInt(PRIORITY_KEY, PRIORITY_BALANCED);
        if (!data.contains(REQUEST_KEY)) data.putInt(REQUEST_KEY, REQUEST_NONE);
        if (!data.contains(NOTICE_KEY)) data.putString(NOTICE_KEY, "Староста готов управлять поселением.");
        villager.setCustomName(Component.translatable("livingvillages.elder.name", personalName));
        villager.setCustomNameVisible(true);
    }

    private static void releaseElder(Villager villager) {
        var data = villager.getPersistentData();
        String originalName = data.getString(ORIGINAL_NAME_KEY);
        if (!originalName.isBlank()) {
            villager.setCustomName(Component.literal(originalName));
            villager.setCustomNameVisible(data.getBoolean(ORIGINAL_VISIBLE_KEY));
        } else if (LivingVillagesConfig.SHOW_PERSONALITY_NAMES.get()) {
            String personalName = data.getString(PERSONAL_NAME_KEY);
            if (!personalName.isBlank()) {
                villager.setCustomName(Component.literal(personalName));
                villager.setCustomNameVisible(true);
            } else {
                villager.setCustomName(null);
                villager.setCustomNameVisible(false);
            }
        } else {
            villager.setCustomName(null);
            villager.setCustomNameVisible(false);
        }
        data.remove(ELDER_KEY);
        data.remove(AUTO_KEY);
        data.remove(PRIORITY_KEY);
        data.remove(REQUEST_KEY);
        data.remove(NOTICE_KEY);
        data.remove(ORIGINAL_NAME_KEY);
        data.remove(ORIGINAL_VISIBLE_KEY);
        data.remove(PERSONAL_NAME_KEY);
    }

    public static boolean isAutomationEnabled(List<Villager> villagers) {
        Villager elder = findElder(villagers);
        return elder == null || elder.getPersistentData().getBoolean(AUTO_KEY);
    }

    public static int priorityForVillagers(List<Villager> villagers) {
        Villager elder = findElder(villagers);
        return elder == null ? PRIORITY_BALANCED
            : Math.max(PRIORITY_BALANCED, Math.min(PRIORITY_DEFENSE,
                elder.getPersistentData().getInt(PRIORITY_KEY)));
    }

    public static int pendingRequest(List<Villager> villagers) {
        Villager elder = findElder(villagers);
        return elder == null ? REQUEST_NONE : elder.getPersistentData().getInt(REQUEST_KEY);
    }

    public static boolean hasPendingRequest(List<Villager> villagers) {
        return pendingRequest(villagers) != REQUEST_NONE;
    }

    public static boolean hasHouseRequest(List<Villager> villagers) {
        return pendingRequest(villagers) == REQUEST_HOUSE;
    }

    public static void completeRequest(List<Villager> villagers, int completedRequest, String notice) {
        Villager elder = findElder(villagers);
        if (elder == null) return;
        var data = elder.getPersistentData();
        if (data.getInt(REQUEST_KEY) == completedRequest) {
            data.putInt(REQUEST_KEY, REQUEST_NONE);
            data.putString(NOTICE_KEY, notice);
        }
    }

    private static Villager findElder(List<Villager> villagers) {
        return villagers.stream().filter(VillageElder::isElder)
            .min(Comparator.comparing(v -> v.getUUID().toString())).orElse(null);
    }

    /** Normal right-click with an empty main hand opens the elder panel; sneak-click remains personal dialogue. */
    public static void onVillagerInteract(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
            || event.getEntity().level().isClientSide()
            || !(event.getEntity() instanceof ServerPlayer player)
            || !(event.getTarget() instanceof Villager villager)
            || !LivingVillagesConfig.ENABLE_VILLAGE_ELDER.get()
            || !isElder(villager)
            || player.isShiftKeyDown()
            || !player.getMainHandItem().isEmpty()) return;

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        sendPanel(player, villager, villager.getPersistentData().getString(NOTICE_KEY));
    }

    private static void handlePanelOnClient(ElderPanelData data, IPayloadContext context) {
        context.enqueueWork(() -> LivingVillagesClient.openElderPanel(data));
    }

    private static void handleActionOnServer(ElderAction action, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        UUID uuid;
        try {
            uuid = UUID.fromString(action.elderUuid());
        } catch (IllegalArgumentException exception) {
            return;
        }
        Entity entity = player.serverLevel().getEntity(uuid);
        if (!LivingVillagesConfig.ENABLE_VILLAGE_ELDER.get()
            || !(entity instanceof Villager elder) || !elder.isAlive() || !isElder(elder)
            || player.distanceToSqr(elder) > INTERACTION_RADIUS_SQR) return;

        var data = elder.getPersistentData();
        String notice;
        switch (action.action()) {
            case ACTION_TOGGLE_AUTO -> {
                boolean enabled = !data.getBoolean(AUTO_KEY);
                data.putBoolean(AUTO_KEY, enabled);
                notice = enabled ? "Староста возобновил самостоятельное развитие." : "Староста приостановил самостоятельное развитие.";
            }
            case ACTION_CYCLE_PRIORITY -> {
                int next = (data.getInt(PRIORITY_KEY) + 1) % (PRIORITY_DEFENSE + 1);
                data.putInt(PRIORITY_KEY, next);
                notice = "Новый приоритет: " + priorityName(next) + ".";
            }
            case ACTION_REQUEST_HOUSE -> {
                if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()) {
                    notice = "Нельзя заказать дом: сначала включи автономное развитие в настройках мода.";
                } else if (!LivingVillagesConfig.ENABLE_HOUSING_PROJECTS.get()
                    || !LivingVillagesConfig.ENABLE_VILLAGE_BUILDER.get()) {
                    notice = "Нельзя заказать дом: включи строительство домов и роль строителя в настройках мода.";
                } else {
                    data.putInt(REQUEST_KEY, REQUEST_HOUSE);
                    data.putInt(PRIORITY_KEY, PRIORITY_HOUSING);
                    notice = "Заказ дома поставлен в очередь. Строительство начнётся, когда будут доступны строитель, материалы и место.";
                }
            }
            case ACTION_REQUEST_FARM -> {
                if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()) {
                    notice = "Нельзя заказать ферму: сначала включи автономное развитие в настройках мода.";
                } else if (!LivingVillagesConfig.ENABLE_FARM_PROJECTS.get()) {
                    notice = "Нельзя заказать ферму: включи строительство ферм в настройках мода.";
                } else {
                    data.putInt(REQUEST_KEY, REQUEST_FARM);
                    data.putInt(PRIORITY_KEY, PRIORITY_FOOD);
                    notice = "Заказ фермы поставлен в очередь. Нужны настоящие семена, вода, мотыга и компостер.";
                }
            }
            case ACTION_DEFENSE_PRIORITY -> {
                data.putInt(PRIORITY_KEY, PRIORITY_DEFENSE);
                notice = "Приоритет изменён: защита поселения. Свободные жители будут распределены с учётом доступного снаряжения.";
            }
            case ACTION_BALANCED_PRIORITY -> {
                data.putInt(PRIORITY_KEY, PRIORITY_BALANCED);
                notice = "Приоритет изменён: сбалансированное развитие.";
            }
            default -> { return; }
        }
        data.putString(NOTICE_KEY, notice);
        sendPanel(player, elder, notice);
    }

    private static void sendPanel(ServerPlayer player, Villager elder, String notice) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = elder.blockPosition();
        int radius = LivingVillagesConfig.VILLAGE_RADIUS_BLOCKS.get();
        List<Villager> villagers = VillageArea.findVillagers(level, origin);
        int adults = (int) villagers.stream().filter(v -> !v.isBaby()).count();
        int children = villagers.size() - adults;
        int beds = countBeds(level, origin, radius);
        int food = countStoredFood(level, origin);
        var data = elder.getPersistentData();
        String currentNotice = notice == null || notice.isBlank() ? data.getString(NOTICE_KEY) : notice;
        PacketDistributor.sendToPlayer(player, new ElderPanelData(
            elder.getUUID().toString(), villagers.size(), adults, children, beds, food,
            Math.max(PRIORITY_BALANCED, Math.min(PRIORITY_DEFENSE, data.getInt(PRIORITY_KEY))),
            data.getBoolean(AUTO_KEY), currentNotice));
    }

    private static int countBeds(ServerLevel level, BlockPos center, int radius) {
        int count = 0;
        BlockPos min = center.offset(-radius, -8, -radius);
        BlockPos max = center.offset(radius, 8, radius);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.FOOT
                || !VillageRegistry.isBedForVillage(level, center, pos)) continue;
            BlockPos head = pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
            if (level.hasChunkAt(head) && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.above(2)).isAir()
                && level.getBlockState(head.above()).isAir()
                && level.getBlockState(head.above(2)).isAir()) count++;
        }
        return count;
    }

    private static int countStoredFood(ServerLevel level, BlockPos center) {
        int points = 0;
        int radius = LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get();
        BlockPos min = center.offset(-radius, -8, -radius);
        BlockPos max = center.offset(radius, 8, radius);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(pos)) continue;
            if (!level.getBlockState(pos).is(Blocks.CHEST)
                && !level.getBlockState(pos).is(Blocks.TRAPPED_CHEST)
                && !level.getBlockState(pos).is(Blocks.BARREL)) continue;
            if (!VillageRegistry.isStorageForVillage(level, center, pos)
                || !VillageReservations.isStorageAvailable(level, pos, null)) continue;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                var stack = container.getItem(slot);
                if (stack.is(Items.BREAD)) points += stack.getCount() * FOOD_POINTS_PER_BREAD;
                else if (stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)) points += stack.getCount();
            }
        }
        return points;
    }

    public static String priorityName(int priority) {
        return switch (priority) {
            case PRIORITY_HOUSING -> "Жильё";
            case PRIORITY_FOOD -> "Еда";
            case PRIORITY_DEFENSE -> "Оборона";
            default -> "Баланс";
        };
    }

    public record ElderPanelData(String elderUuid, int population, int adults, int children, int beds,
                                 int foodPoints, int priority, boolean autonomous, String notice)
            implements CustomPacketPayload {
        public static final Type<ElderPanelData> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(LivingVillages.MOD_ID, "elder_panel"));
        public static final StreamCodec<ByteBuf, ElderPanelData> STREAM_CODEC = StreamCodec.of(
            ElderPanelData::encode, ElderPanelData::decode);

        private static void encode(ByteBuf buffer, ElderPanelData data) {
            ByteBufCodecs.STRING_UTF8.encode(buffer, data.elderUuid());
            ByteBufCodecs.VAR_INT.encode(buffer, data.population());
            ByteBufCodecs.VAR_INT.encode(buffer, data.adults());
            ByteBufCodecs.VAR_INT.encode(buffer, data.children());
            ByteBufCodecs.VAR_INT.encode(buffer, data.beds());
            ByteBufCodecs.VAR_INT.encode(buffer, data.foodPoints());
            ByteBufCodecs.VAR_INT.encode(buffer, data.priority());
            ByteBufCodecs.BOOL.encode(buffer, data.autonomous());
            ByteBufCodecs.STRING_UTF8.encode(buffer, data.notice());
        }

        private static ElderPanelData decode(ByteBuf buffer) {
            return new ElderPanelData(
                ByteBufCodecs.STRING_UTF8.decode(buffer),
                ByteBufCodecs.VAR_INT.decode(buffer),
                ByteBufCodecs.VAR_INT.decode(buffer),
                ByteBufCodecs.VAR_INT.decode(buffer),
                ByteBufCodecs.VAR_INT.decode(buffer),
                ByteBufCodecs.VAR_INT.decode(buffer),
                ByteBufCodecs.VAR_INT.decode(buffer),
                ByteBufCodecs.BOOL.decode(buffer),
                ByteBufCodecs.STRING_UTF8.decode(buffer));
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ElderAction(String elderUuid, int action) implements CustomPacketPayload {
        public static final Type<ElderAction> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(LivingVillages.MOD_ID, "elder_action"));
        public static final StreamCodec<ByteBuf, ElderAction> STREAM_CODEC = StreamCodec.of(
            ElderAction::encode, ElderAction::decode);

        private static void encode(ByteBuf buffer, ElderAction data) {
            ByteBufCodecs.STRING_UTF8.encode(buffer, data.elderUuid());
            ByteBufCodecs.VAR_INT.encode(buffer, data.action());
        }

        private static ElderAction decode(ByteBuf buffer) {
            return new ElderAction(ByteBufCodecs.STRING_UTF8.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer));
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
