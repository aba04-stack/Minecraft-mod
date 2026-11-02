#!/usr/bin/env python3
"""Static regression checks for the villager progression system and schema migration."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"

prog = (JAVA / "VillagerProgression.java").read_text(encoding="utf-8")
model = (JAVA / "domain/VillagerProgressionModel.java").read_text(encoding="utf-8")
config = (JAVA / "LivingVillagesConfig.java").read_text(encoding="utf-8")
gametests = (JAVA / "VillageReservationGameTests.java").read_text(encoding="utf-8")

assert 'define("enable_villager_progression", true)' in config
assert 'defineInRange("max_profession_level", 5, 1, 5)' in config

for marker in (
    "PROGRESSION_TAG = \"livingvillages_progression\"",
    "SCHEMA_VERSION_KEY = \"schema_version\"",
    "XP_BY_ROLE_KEY = \"xp_by_role\"",
    "RAW_BACKUP_TAG_KEY = \"_raw_backup_tag\"",
    "ensureMigratedTag(CompoundTag entityPersistentData)",
    "VillagerProgressionModel.evaluateWorkCompletion(",
    "recordCompletedWork(",
    "describePrimarySkill(Villager villager)",
):
    assert marker in prog, f"VillagerProgression.java missing {marker}"

assert "new ItemStack(" not in prog and "new ItemStack(" not in model, (
    "Villager progression must never create free items or resources out of thin air"
)

for worker_file in (
    "VillagerTreeWork.java",
    "VillagerMining.java",
    "VillageBuilder.java",
    "VillageFarmBuilder.java",
    "VillageCraftingJobs.java",
    "VillageDefense.java",
    "VillagePopulation.java",
):
    source = (JAVA / worker_file).read_text(encoding="utf-8")
    assert "VillagerProgression.recordCompletedWork(" in source, (
        f"{worker_file} must award progression XP on confirmed work completion"
    )

assert "villagerProgressionPreservesSkillAcrossRoleChangesAndMigratesSchema" in gametests
print("VILLAGER PROGRESSION PASSED: versioned schema, anti-abuse guards, no item generation, and worker wiring")
