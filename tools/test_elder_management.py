#!/usr/bin/env python3
"""Static regression checks for the elder interaction and management orders."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
java = ROOT / "src/main/java/com/abs/livingvillages"
elder = (java / "VillageElder.java").read_text(encoding="utf-8")
main = (java / "LivingVillages.java").read_text(encoding="utf-8")
client = (java / "LivingVillagesClient.java").read_text(encoding="utf-8")

assert 'define("enable_village_elder", true)' in (java / "LivingVillagesConfig.java").read_text(encoding="utf-8")
assert 'event.registrar("1")' in elder and 'registrar.playToClient' in elder and 'registrar.playToServer' in elder
assert 'PlayerInteractEvent.EntityInteractSpecific' in elder, "elder should be interacted with through the standard entity-interaction pipeline"
assert 'player.isShiftKeyDown()' in elder and '!player.getMainHandItem().isEmpty()' in elder, "elder panel must not swallow sneak-to-talk or item interactions"
assert 'player.distanceToSqr(elder) > INTERACTION_RADIUS_SQR' in elder, "server must validate interaction range"
assert 'UUID.fromString(action.elderUuid())' in elder and 'isElder(elder)' in elder, "server must validate elder UUID and marker"
assert 'adults.size() < LivingVillagesConfig.MINIMUM_VILLAGERS.get()' in elder, "a lone villager should not be newly elected elder"
assert 'if (!LivingVillagesConfig.ENABLE_HOUSING_PROJECTS.get()' in elder, "house orders must respect configuration"
assert 'if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get())' in elder, "manual orders must not queue while the global master switch is off"
assert '!LivingVillagesConfig.ENABLE_VILLAGE_ELDER.get()' in elder[elder.index('private static void handleActionOnServer'):], "server must reject actions if elder role is disabled"
assert 'if (!LivingVillagesConfig.ENABLE_FARM_PROJECTS.get())' in elder, "farm orders must respect configuration"
assert 'normalizePendingRequest(elected)' in elder, "orders disabled after queueing must be cleared rather than stuck"
assert 'VillageElder::registerPayloads' in main and 'VillageElder::onVillagerInteract' in main
assert 'renderBackground(graphics, mouseX, mouseY, partialTick)' in client, "Screen must use the 1.21.1 renderBackground signature"
assert 'PacketDistributor.sendToServer(new VillageElder.ElderAction(data.elderUuid(), action))' in client
assert 'livingvillages.elder.order_house' in client and 'livingvillages.elder.order_farm' in client
assert 'VillagerNameplates::onVillagerTick' in main, "profession labels must be synchronized by the server"
assert 'define("show_villager_profession_names", true)' in (java / "LivingVillagesConfig.java").read_text(encoding="utf-8")
assert 'VillageIndustry.describeStartupStatus' in elder, "elder panel must explain the current settlement bootstrap blocker"
assert 'font.split(noticeComponent(), PANEL_WIDTH - 40)' in client, "long startup diagnostics must not cover management buttons"
assert 'NOTICE_LINES_PER_PAGE = 2' in client, "the summary must stay inside its two text lines per page"
assert 'line < NOTICE_LINES_PER_PAGE && firstLine + line < noticeLines.size()' in client, (
    "paged summary rendering must never draw outside the reserved status area"
)
assert 'rotateNoticePage(pages)' in client and 'livingvillages.elder.notice_page' in client, (
    "a long summary must rotate through pages and tell the player which page is visible"
)
assert 'public boolean mouseClicked(double mouseX, double mouseY, int button)' in client, (
    "the player must be able to page the summary by clicking it without adding an eighth widget"
)
print("ELDER MANAGEMENT PASSED: right-click panel, server-validated actions, profession nameplates, paged bounded startup diagnostics")
