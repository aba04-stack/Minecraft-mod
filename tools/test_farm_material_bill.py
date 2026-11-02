#!/usr/bin/env python3
"""Static regression checks for the physical 5x5 farm blueprint."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = (root / "src/main/java/com/abs/livingvillages/VillageFarmBuilder.java").read_text(encoding="utf-8")

farm_size = 5
expected_plots = farm_size * farm_size - 1
assert expected_plots == 24
assert "private static final int FARM_SIZE = 5;" in source
assert "private static final int CROP_COUNT = FARM_SIZE * FARM_SIZE - 1;" in source
assert "steps.add(new FarmStep(FarmStepKind.WATER" in source
assert "FarmStepKind.TILL" in source and "FarmStepKind.PLANT" in source
assert "FarmStepKind.COMPOSTER" in source
assert "case PLANT -> current.is(cropBlock);" in source, "already-planted step must validate against planned crop type"
assert "parseItemForCrop" not in source, "old self-comparison crop check must be removed"
assert "Items.WATER_BUCKET" in source and "Items.BUCKET" in source, "water bucket must become a real empty bucket"
assert "hasHoe(List<Container> containers)" in source, "field preparation must require a real hoe"
assert "isSupportedCrop(Item seedItem, Block cropBlock)" in source, "crop validation must use explicit vanilla seed/crop pairs"
for pair in (
    "seedItem == Items.WHEAT_SEEDS && cropBlock == Blocks.WHEAT",
    "seedItem == Items.CARROT && cropBlock == Blocks.CARROTS",
    "seedItem == Items.POTATO && cropBlock == Blocks.POTATOES",
    "seedItem == Items.BEETROOT_SEEDS && cropBlock == Blocks.BEETROOTS",
):
    assert pair in source, f"missing supported seed/crop pair: {pair}"
assert "Block.byItem(seedItem) == Blocks.AIR" not in source, "seed items must not be incorrectly rejected as non-BlockItems"
assert "setRoute(worker, data" in source and "WalkTarget" in source, "worker should walk rather than teleport"
assert "new Villager(" not in source and "EntityType.VILLAGER.create" not in source
print("FARM WORKFLOW PASSED: 24 real crop items, validated vanilla crop pairs, water, hoe, composter, walking routes, no entity/resource generator")
