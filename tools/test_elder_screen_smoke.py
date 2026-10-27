#!/usr/bin/env python3
"""Static contract for the opt-in headless elder-panel layout smoke test."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENT = (ROOT / "src/main/java/com/abs/livingvillages/LivingVillagesClient.java").read_text(encoding="utf-8")
SCRIPT = (ROOT / "tools/smoke_test_client.sh").read_text(encoding="utf-8")

for marker in (
    'Boolean.getBoolean("livingvillages.smokeTestElderScreen")',
    "NeoForge.EVENT_BUS.addListener(this::onClientTick)",
    "ClientTickEvent.Post",
    "new ElderScreen(testData)",
    "layoutSmokeTestFailure()",
    "PANEL_HEIGHT = 220",
    "children().size() != 7",
    "button.getX() + button.getWidth() > width",
    "button.getY() + button.getHeight() > height",
    "LIVING_VILLAGES_ELDER_SCREEN_SMOKE_PASS:",
    "LIVING_VILLAGES_ELDER_SCREEN_SMOKE_FAIL:",
):
    assert marker in CLIENT, f"client smoke-test hook is missing: {marker}"

assert 'JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dlivingvillages.smokeTestElderScreen=true"' in SCRIPT
assert "has_smoke_pass_marker" in SCRIPT and "has_smoke_failure_marker" in SCRIPT
assert "has_startup_marker" not in SCRIPT, "resource initialization alone must no longer pass the client smoke test"
assert "did not pass the elder-panel layout smoke test" in SCRIPT
print("ELDER SCREEN SMOKE CONTRACT PASSED: client creates the screen, checks seven widgets/bounds, and requires a pass marker")
