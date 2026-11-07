package com.abs.livingvillages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Server-synchronized villager labels. The composed custom name is sent by vanilla entity data,
 * so clients do not need access to server-only persistent specialist-role tags.
 *
 * Original names and visibility are preserved in entity NBT and restored when the setting is disabled.
 */
public final class VillagerNameplates {
    private static final String PREFIX = "livingvillages_nameplate_";
    private static final String ORIGINAL_NAME_JSON = PREFIX + "original_name_json";
    private static final String ORIGINAL_VISIBLE = PREFIX + "original_visible";
    private static final String DISPLAY_NAME_JSON = PREFIX + "display_name_json";

    private VillagerNameplates() {}

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()
            || !villager.isAlive() || villager.tickCount % 20 != 0
            || !(villager.level() instanceof ServerLevel)) return;

        if (!LivingVillagesConfig.SHOW_VILLAGER_PROFESSION_NAMES.get()) {
            restoreNameplate(villager);
            return;
        }
        refreshNameplate(villager);
    }

    public static boolean isManaged(Villager villager) {
        if (villager == null) return false;
        CompoundTag data = villager.getPersistentData();
        return data.contains(ORIGINAL_NAME_JSON) && data.contains(ORIGINAL_VISIBLE)
            && data.contains(DISPLAY_NAME_JSON);
    }

    public static Component getOriginalCustomName(Villager villager) {
        if (villager == null || !isManaged(villager)) return villager == null ? null : villager.getCustomName();
        adoptExternalRename(villager);
        return deserialize(villager, villager.getPersistentData().getString(ORIGINAL_NAME_JSON));
    }

    public static boolean getOriginalNameVisible(Villager villager) {
        if (villager == null) return false;
        if (!isManaged(villager)) return villager.isCustomNameVisible();
        adoptExternalRename(villager);
        return villager.getPersistentData().getBoolean(ORIGINAL_VISIBLE);
    }

    public static void setOriginalName(Villager villager, Component name, boolean visible) {
        if (villager == null) return;
        if (!isManaged(villager)) {
            villager.setCustomName(name);
            villager.setCustomNameVisible(visible);
            return;
        }
        CompoundTag data = villager.getPersistentData();
        data.putString(ORIGINAL_NAME_JSON, serialize(villager, name));
        data.putBoolean(ORIGINAL_VISIBLE, visible);
        refreshNameplate(villager);
    }

    public static void refreshNameplate(Villager villager) {
        if (villager == null || !villager.isAlive() || villager.level().isClientSide()
            || !(villager.level() instanceof ServerLevel)) return;

        CompoundTag data = villager.getPersistentData();
        if (!isManaged(villager)) {
            data.putString(ORIGINAL_NAME_JSON, serialize(villager, villager.getCustomName()));
            data.putBoolean(ORIGINAL_VISIBLE, villager.isCustomNameVisible());
        } else {
            adoptExternalRename(villager);
        }

        Component original = deserialize(villager, data.getString(ORIGINAL_NAME_JSON));
        var professionKey = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
        String professionPath = professionKey == null ? "none" : professionKey.getPath();
        String role = data.getString(VillagerRoles.ROLE_KEY);
        String specialistKey = specialistTranslationKey(role);
        boolean professionIsGeneric = "none".equals(professionPath) || "nitwit".equals(professionPath);
        boolean specialistMatchesProfession = roleMatchesProfession(role, professionPath);

        MutableComponent display = original == null ? Component.empty() : original.copy();
        boolean hasPart = !display.getString().isBlank();
        if (!professionIsGeneric || specialistKey == null) {
            display = appendLabel(display, hasPart,
                Component.translatable("livingvillages.profession." + professionPath).withStyle(ChatFormatting.GRAY));
            hasPart = true;
        }
        if (specialistKey != null && !specialistMatchesProfession) {
            display = appendLabel(display, hasPart,
                Component.translatable(specialistKey).withStyle(ChatFormatting.GOLD));
            hasPart = true;
        }
        if (VillageElder.isElder(villager)) {
            display = appendLabel(display, hasPart,
                Component.translatable("livingvillages.role.elder").withStyle(ChatFormatting.YELLOW));
            hasPart = true;
        }
        if (!hasPart) display = Component.translatable("livingvillages.profession.none");

        data.putString(DISPLAY_NAME_JSON, serialize(villager, display));
        villager.setCustomName(display);
        villager.setCustomNameVisible(true);
    }

    public static void restoreNameplate(Villager villager) {
        if (villager == null || !isManaged(villager) || !(villager.level() instanceof ServerLevel)) return;
        adoptExternalRename(villager);
        CompoundTag data = villager.getPersistentData();
        Component original = deserialize(villager, data.getString(ORIGINAL_NAME_JSON));
        boolean visible = data.getBoolean(ORIGINAL_VISIBLE);
        villager.setCustomName(original);
        villager.setCustomNameVisible(visible);
        clearTracking(villager);
    }

    public static void forgetTracking(Villager villager) {
        if (villager != null) clearTracking(villager);
    }

    private static void adoptExternalRename(Villager villager) {
        if (!isManaged(villager)) return;
        CompoundTag data = villager.getPersistentData();
        String currentJson = serialize(villager, villager.getCustomName());
        if (!currentJson.equals(data.getString(DISPLAY_NAME_JSON))) {
            data.putString(ORIGINAL_NAME_JSON, currentJson);
            data.putBoolean(ORIGINAL_VISIBLE, villager.isCustomNameVisible());
        }
    }

    private static MutableComponent appendLabel(MutableComponent base, boolean hasBase, Component label) {
        if (!hasBase) return label.copy();
        return base.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY)).append(label);
    }

    private static String specialistTranslationKey(String role) {
        return switch (role) {
            case VillagerRoles.ROLE_MINER -> "livingvillages.role.miner";
            case VillagerRoles.ROLE_GUARD -> "livingvillages.role.guard";
            case VillagerRoles.ROLE_CRAFTSMAN -> "livingvillages.role.craftsman";
            case VillagerRoles.ROLE_BUILDER -> "livingvillages.role.builder";
            case VillagerRoles.ROLE_LUMBERJACK -> "livingvillages.role.lumberjack";
            case VillagerRoles.ROLE_FARMER -> "livingvillages.role.farmer";
            case VillagerRoles.ROLE_HERDER -> "livingvillages.role.herder";
            default -> null;
        };
    }

    private static boolean roleMatchesProfession(String role, String professionPath) {
        return VillagerRoles.ROLE_FARMER.equals(role) && "farmer".equals(professionPath);
    }

    private static String serialize(Villager villager, Component component) {
        if (component == null) return "";
        return Component.Serializer.toJson(component, ((ServerLevel) villager.level()).registryAccess());
    }

    private static Component deserialize(Villager villager, String json) {
        return json == null || json.isBlank() ? null
            : Component.Serializer.fromJson(json, ((ServerLevel) villager.level()).registryAccess());
    }

    private static void clearTracking(Villager villager) {
        CompoundTag data = villager.getPersistentData();
        data.remove(ORIGINAL_NAME_JSON);
        data.remove(ORIGINAL_VISIBLE);
        data.remove(DISPLAY_NAME_JSON);
    }
}
