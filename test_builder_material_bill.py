#!/usr/bin/env python3
"""Static design regression test for the starter-house blueprint material bill."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = (root / "src/main/java/com/abs/livingvillages/VillageBuilder.java").read_text(encoding="utf-8")

floor_blocks = 5 * 5
lower_wall_course = 5 * 4 - 4 - 1  # 16 perimeter blocks minus the lower door opening
upper_wall_course = 5 * 4 - 4 - 1  # same 1x2 door opening
lintel_course = 5 * 4 - 4           # full perimeter course above the door
roof_blocks = 5 * 5
expected_planks = floor_blocks + lower_wall_course + upper_wall_course + lintel_course + roof_blocks
assert expected_planks == 96, f"template geometry should consume 96 planks, got {expected_planks}"
assert "PLANKS_REQUIRED = 96" in source, "code's plank requirement differs from the fixed template bill"
assert "doorItemForPlanks" in source and "DoorBlock" in source, "a real matching wooden door is required"
assert "Items.RED_BED" in source and "ROUTE_TO_STORAGE" in source, "physical bed/material collection missing"
assert "withinPlacementReach" in source and "hasClearLine" in source, "builder reach/LOS checks missing"
assert "FETCH_DOOR" in source and "DoorHingeSide" in source, "door is not fetched and installed as a real double-block door"
print("BUILDER MATERIAL BILL PASSED: 96 planks + 1 matching door + 1 real red bed; reach/LOS checks present")
