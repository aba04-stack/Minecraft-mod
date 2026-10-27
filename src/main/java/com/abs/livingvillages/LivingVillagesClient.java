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
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only integration: config screen plus the village elder management panel. */
@Mod(value = LivingVillages.MOD_ID, dist = Dist.CLIENT)
public final class LivingVillagesClient {
    private static final boolean SMOKE_TEST_ELDER_SCREEN =
        Boolean.getBoolean("livingvillages.smokeTestElderScreen");

    private boolean smokeScreenOpened;
    private boolean smokeTestFinished;

    public LivingVillagesClient(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    /**
     * Opt-in CI smoke test. It creates the same real screen as the server payload path and checks
     * its initialized widgets and viewport bounds. It does not simulate button clicks or networking.
     */
    private void onClientTick(ClientTickEvent.Post event) {
        if (!SMOKE_TEST_ELDER_SCREEN || smokeTestFinished) return;
        Minecraft minecraft = Minecraft.getInstance();

        if (!smokeScreenOpened) {
            smokeScreenOpened = true;
            VillageElder.ElderPanelData testData = new VillageElder.ElderPanelData(
                "00000000-0000-0000-0000-000000000001",
                5, 4, 1, 5, 12, VillageElder.PRIORITY_BALANCED, true,
                "Client layout smoke test");
            minecraft.setScreen(new ElderScreen(testData));
            return;
        }

        if (!(minecraft.screen instanceof ElderScreen screen)) {
            finishSmokeTest(minecraft, "the elder screen was not retained after initialization");
            return;
        }
        String failure = screen.layoutSmokeTestFailure();
        if (failure != null) {
            finishSmokeTest(minecraft, failure);
            return;
        }

        smokeTestFinished = true;
        System.out.println("LIVING_VILLAGES_ELDER_SCREEN_SMOKE_PASS: seven buttons fit within "
            + screen.width + "x" + screen.height + ".");
        minecraft.setScreen(null);
    }

    private void finishSmokeTest(Minecraft minecraft, String reason) {
        smokeTestFinished = true;
        System.out.println("LIVING_VILLAGES_ELDER_SCREEN_SMOKE_FAIL: " + reason);
        minecraft.setScreen(null);
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

        /** Returns a failure description for the headless CI layout check, or null when valid. */
        private String layoutSmokeTestFailure() {
            if (width < PANEL_WIDTH + 20 || height < PANEL_HEIGHT + 20) {
                return "viewport is too small for the fixed elder panel: " + width + "x" + height;
            }
            if (panelLeft < 0 || panelTop < 0 || panelLeft + PANEL_WIDTH > width
                || panelTop + PANEL_HEIGHT > height) {
                return "elder panel rectangle exceeds the viewport";
            }
            if (children().size() != 7) {
                return "expected seven elder-panel buttons, found " + children().size();
            }
            for (var child : children()) {
                if (!(child instanceof Button button)) {
                    return "elder panel contains a non-button interactive widget";
                }
                if (button.getX() < 0 || button.getY() < 0
                    || button.getX() + button.getWidth() > width
                    || button.getY() + button.getHeight() > height) {
                    return "a button extends outside the viewport: " + button.getMessage().getString();
                }
            }
            return null;
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
