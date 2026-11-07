#!/usr/bin/env python3
"""Regression contract for finite treeless-desert settlement bootstrap."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
INDUSTRY = (ROOT / "src/main/java/com/abs/livingvillages/VillageIndustry.java").read_text(encoding="utf-8")
REGISTRY = (ROOT / "src/main/java/com/abs/livingvillages/VillageRegistry.java").read_text(encoding="utf-8")
CONFIG = (ROOT / "src/main/java/com/abs/livingvillages/LivingVillagesConfig.java").read_text(encoding="utf-8")
DEMAND = (ROOT / "src/main/java/com/abs/livingvillages/domain/VillageResourceDemandModel.java").read_text(encoding="utf-8")
JAVA_TESTS = (ROOT / "src/main/java/com/abs/livingvillages/VillageReservationGameTests.java").read_text(encoding="utf-8")

for marker in (
    "ENABLE_DESERT_STARTER_KIT",
    'define("enable_desert_starter_kit", true)',
    "BiomeTags.IS_DESERT",
    "VillageRegistry.hasDesertStarterKit(level, center)",
    "VillageRegistry.recordDesertStarterKitGranted(level, center)",
    "VillageResourcePlanner.getPlan(level, center, villagers)",
    "Items.SANDSTONE, 96",
    "Items.OAK_LOG, 8",
    "Items.STONE_PICKAXE, 1",
    "Items.WHEAT_SEEDS, 24",
    "Items.WATER_BUCKET, 1",
    "Items.COMPOSTER, 1",
    "Items.BREAD, 4",
    "createDesertStarterKit()",
    "canFitBundle(ref.container(), bundle)",
    "restoreContainer(destinationContainer, before)",
):
    assert marker in INDUSTRY or marker in CONFIG, f"missing desert starter marker: {marker}"

assert "desertStarterKits" in REGISTRY, "one-time desert starter claims must persist in SavedData"
assert 'tag.put("desertStarterKits", desertStarterTags)' in REGISTRY, "desert starter claims must be saved"
assert 'tag.getList("desertStarterKits", Tag.TAG_COMPOUND)' in REGISTRY, "desert starter claims must load"
assert "recordDesertStarterKitGranted" in JAVA_TESTS
assert "desertHouseReady.targetStoneUnits() == 96" in JAVA_TESTS
assert re.search(r"int targetStone = treesAvailable\s*\?\s*clamp\(24 \+ adults \* 4, 48, 160\)\s*:\s*clamp\(Math\.max\(96, 24 \+ adults \* 4\), 96, 160\);", DEMAND), (
    "treeless settlements must keep at least a full 96-block house target for quarrying"
)
assert "new ItemStack(Items.BED" not in INDUSTRY, "desert kit must not use a nonexistent generic bed item"
print("DESERT STARTER CONTRACT PASSED: finite saved one-time kit, rollback-safe storage insertion, and 96-block treeless quarry target")
