package com.abs.livingvillages;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only integration: config screen plus the village elder management panel. */
@Mod(value = LivingVillages.MOD_ID, dist = Dist.CLIENT)
public final class LivingVillagesClient {
    public LivingVillagesClient(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    public static void openElderPanel(VillageElder.ElderPanelData data) {
        Minecraft.getInstance().setScreen(new ElderScreen(data));
    }

    private static final class ElderScreen extends Screen {
        private static final int PANEL_WIDTH = 340;
        private static final int PANEL_HEIGHT = 238;
        private static final int BUTTON_WIDTH = 300;
        private final VillageElder.ElderPanelData data;
        private int panelLeft;
        private int panelTop;

        private ElderScreen(VillageElder.ElderPanelData data) {
            super(Component.translatable("livingvillages.elder.screen_title"));
            this.data = data;
        }

        @Override
        protected void init() {
            super.init();
            panelLeft = (width - PANEL_WIDTH) / 2;
            panelTop = (height - PANEL_HEIGHT) / 2;
            int left = panelLeft + (PANEL_WIDTH - BUTTON_WIDTH) / 2;
            addRenderableWidget(Button.builder(
                Component.translatable(data.autonomous()
                    ? "livingvillages.elder.pause_auto" : "livingvillages.elder.resume_auto"),
                button -> send(0))
                .bounds(left, panelTop + 106, BUTTON_WIDTH, 20).build());
            addRenderableWidget(Button.builder(
                Component.translatable("livingvillages.elder.priority", priorityLabel(data.priority())),
                button -> send(1))
                .bounds(left, panelTop + 130, BUTTON_WIDTH, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("livingvillages.elder.order_house"),
                button -> send(2)).bounds(left, panelTop + 154, 146, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("livingvillages.elder.order_farm"),
                button -> send(3)).bounds(left + 154, panelTop + 154, 146, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("livingvillages.elder.defense_priority"),
                button -> send(4)).bounds(left, panelTop + 178, 146, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("livingvillages.elder.balanced_priority"),
                button -> send(5)).bounds(left + 154, panelTop + 178, 146, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(left, panelTop + 207, BUTTON_WIDTH, 20).build());
        }

        private void send(int action) {
            PacketDistributor.sendToServer(new VillageElder.ElderAction(data.elderUuid(), action));
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xF0182028);
            graphics.fill(panelLeft + 2, panelTop + 2, panelLeft + PANEL_WIDTH - 2,
                panelTop + PANEL_HEIGHT - 2, 0xE62C3742);
            graphics.drawCenteredString(font, title, width / 2, panelTop + 10, 0xFFFFD16D);
            graphics.drawString(font, Component.translatable("livingvillages.elder.population",
                data.population(), data.adults(), data.children()), panelLeft + 20, panelTop + 31, 0xFFFFFFFF);
            graphics.drawString(font, Component.translatable("livingvillages.elder.beds", data.beds()),
                panelLeft + 20, panelTop + 46, 0xFFFFFFFF);
            graphics.drawString(font, Component.translatable("livingvillages.elder.food", data.foodPoints()),
                panelLeft + 20, panelTop + 61, 0xFFFFFFFF);
            Component notice = Component.literal(data.notice() == null ? "" : data.notice());
            graphics.drawWordWrap(font, notice, panelLeft + 20, panelTop + 78, PANEL_WIDTH - 40, 0xFFB8D7C8);
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        private static String priorityLabel(int priority) {
            return switch (priority) {
                case VillageElder.PRIORITY_HOUSING -> Component.translatable("livingvillages.elder.priority_housing").getString();
                case VillageElder.PRIORITY_FOOD -> Component.translatable("livingvillages.elder.priority_food").getString();
                case VillageElder.PRIORITY_DEFENSE -> Component.translatable("livingvillages.elder.priority_defense").getString();
                default -> Component.translatable("livingvillages.elder.priority_balanced").getString();
            };
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
