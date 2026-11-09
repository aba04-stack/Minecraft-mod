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

        String codecFailure = verifyPayloadCodecRoundTrip(screen.data);
        if (codecFailure != null) {
            finishSmokeTest(minecraft, codecFailure);
            return;
        }

        smokeTestFinished = true;
        System.out.println("LIVING_VILLAGES_ELDER_SCREEN_SMOKE_PASS: seven buttons fit within "
            + screen.width + "x" + screen.height + ".");
        minecraft.setScreen(null);
    }

    private static String verifyPayloadCodecRoundTrip(VillageElder.ElderPanelData sample) {
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        try {
            VillageElder.ElderPanelData.STREAM_CODEC.encode(buf, sample);
            VillageElder.ElderPanelData decoded = VillageElder.ElderPanelData.STREAM_CODEC.decode(buf);
            if (!sample.equals(decoded)) {
                return "ElderPanelData STREAM_CODEC round-trip mismatch";
            }
            buf.clear();
            VillageElder.ElderAction action = new VillageElder.ElderAction(sample.elderUuid(), 2);
            VillageElder.ElderAction.STREAM_CODEC.encode(buf, action);
            VillageElder.ElderAction decodedAction = VillageElder.ElderAction.STREAM_CODEC.decode(buf);
            if (!action.equals(decodedAction)) {
                return "ElderAction STREAM_CODEC round-trip mismatch";
            }
            return null;
        } finally {
            buf.release();
        }
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
        private static final int PANEL_HEIGHT = 220;
        private static final int BUTTON_WIDTH = 300;
        private static final int NOTICE_LINES_PER_PAGE = 2;
        private static final long NOTICE_PAGE_MILLIS = 5_000L;
        private final VillageElder.ElderPanelData data;
        private int panelLeft;
        private int panelTop;
        private int noticePage;
        private long noticePageShownAtMillis = -1L;

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
                .bounds(left, panelTop + 122, BUTTON_WIDTH, 18).build());
            addRenderableWidget(Button.builder(
                Component.translatable("livingvillages.elder.priority", priorityLabel(data.priority())),
                button -> send(1))
                .bounds(left, panelTop + 141, BUTTON_WIDTH, 18).build());
            addRenderableWidget(Button.builder(Component.translatable("livingvillages.elder.order_house"),
                button -> send(2)).bounds(left, panelTop + 160, 146, 18).build());
            addRenderableWidget(Button.builder(Component.translatable("livingvillages.elder.order_farm"),
                button -> send(3)).bounds(left + 154, panelTop + 160, 146, 18).build());
            addRenderableWidget(Button.builder(Component.translatable("livingvillages.elder.defense_priority"),
                button -> send(4)).bounds(left, panelTop + 179, 146, 18).build());
            addRenderableWidget(Button.builder(Component.translatable("livingvillages.elder.balanced_priority"),
                button -> send(5)).bounds(left + 154, panelTop + 179, 146, 18).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(left, panelTop + 198, BUTTON_WIDTH, 18).build());
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
                if (button.getX() < panelLeft || button.getY() < panelTop + 120
                    || button.getX() + button.getWidth() > panelLeft + PANEL_WIDTH
                    || button.getY() + button.getHeight() > panelTop + PANEL_HEIGHT - 2) {
                    return "a control is outside the lower action area: " + button.getMessage().getString();
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
            graphics.drawCenteredString(font, title, width / 2, panelTop + 8, 0xFFFFD16D);
            graphics.drawString(font, Component.translatable("livingvillages.elder.population",
                data.population(), data.adults(), data.children()), panelLeft + 20, panelTop + 28, 0xFFFFFFFF);
            graphics.drawString(font, Component.translatable("livingvillages.elder.beds", data.beds()),
                panelLeft + 20, panelTop + 43, 0xFFFFFFFF);
            graphics.drawString(font, Component.translatable("livingvillages.elder.food", data.foodPoints()),
                panelLeft + 20, panelTop + 58, 0xFFFFFFFF);
            graphics.drawString(font, Component.translatable("livingvillages.elder.status_heading"),
                panelLeft + 20, panelTop + 72, 0xFFFFD16D);
            var noticeLines = font.split(noticeComponent(), PANEL_WIDTH - 40);
            int pages = noticePages(noticeLines.size());
            rotateNoticePage(pages);
            int firstLine = noticePage * NOTICE_LINES_PER_PAGE;
            for (int line = 0; line < NOTICE_LINES_PER_PAGE && firstLine + line < noticeLines.size(); line++) {
                graphics.drawString(font, noticeLines.get(firstLine + line), panelLeft + 20,
                    panelTop + 84 + line * 10, 0xFFB8D7C8);
            }
            if (pages > 1) {
                Component indicator = Component.translatable(
                    "livingvillages.elder.notice_page", noticePage + 1, pages);
                graphics.drawString(font, indicator,
                    panelLeft + PANEL_WIDTH - 20 - font.width(indicator), panelTop + 72, 0xFF8CA3B5);
            }
            graphics.fill(panelLeft + 16, panelTop + 117, panelLeft + PANEL_WIDTH - 16,
                panelTop + 118, 0x668CA3B5);
            graphics.drawString(font, Component.translatable("livingvillages.elder.actions_heading"),
                panelLeft + 20, panelTop + 108, 0xFFFFD16D);
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        /**
         * The summary carries the bootstrap hint, master levels, livestock state and diagnostics, so
         * it no longer fits in two lines. It is paged instead of being silently cut off.
         */
        private Component noticeComponent() {
            return Component.literal(data.notice() == null ? "" : data.notice());
        }

        private int noticePages(int lineCount) {
            return Math.max(1, (lineCount + NOTICE_LINES_PER_PAGE - 1) / NOTICE_LINES_PER_PAGE);
        }

        private void rotateNoticePage(int pages) {
            long now = System.currentTimeMillis();
            if (noticePageShownAtMillis < 0L) {
                noticePageShownAtMillis = now;
                noticePage = 0;
            } else if (pages > 1 && now - noticePageShownAtMillis >= NOTICE_PAGE_MILLIS) {
                noticePageShownAtMillis = now;
                noticePage = (noticePage + 1) % pages;
            }
            if (noticePage >= pages) noticePage = 0;
        }

        /** Clicking the summary area advances it manually; buttons keep their own click handling. */
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isInsideNoticeArea(mouseX, mouseY)) {
                int pages = noticePages(font.split(noticeComponent(), PANEL_WIDTH - 40).size());
                if (pages > 1) {
                    noticePage = (noticePage + 1) % pages;
                    noticePageShownAtMillis = System.currentTimeMillis();
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        private boolean isInsideNoticeArea(double mouseX, double mouseY) {
            return mouseX >= panelLeft + 16 && mouseX <= panelLeft + PANEL_WIDTH - 16
                && mouseY >= panelTop + 68 && mouseY <= panelTop + 116;
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
