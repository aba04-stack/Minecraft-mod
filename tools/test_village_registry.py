#!/usr/bin/env python3
"""Static contract for persistent village identities and physical asset ownership."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"
registry = (JAVA / "VillageRegistry.java").read_text(encoding="utf-8")

for marker in (
    "extends SavedData",
    "new Factory<>(VillageRegistry::new, VillageRegistry::load, null)",
    "computeIfAbsent(FACTORY, DATA_NAME)",
    "villageAnchors",
    "storageOwners",
    "bedOwners",
    "workBlockOwners",
    "public static UUID villageId(ServerLevel level, BlockPos center)",
    "resolveOrCreateId(level, center, residents)",
    "home.dimension().equals(level.dimension())",
    "ID_MATCH_RADIUS_SQUARED",
    "VillageArea.isStorageInResidentGroup(level, groupCenter, location)",
    'tag.put("villageAnchors", villageTags)',
    'tag.put("storageOwners", writeOwners(storageOwners))',
    'tag.put("bedOwners", writeOwners(bedOwners))',
    'tag.put("workBlockOwners", writeOwners(workBlockOwners))',
    'tag.getList("villageAnchors", Tag.TAG_COMPOUND)',
    'readOwners(tag.getList("storageOwners", Tag.TAG_COMPOUND))',
    "setDirty()",
):
    assert marker in registry, f"persistent registry missing {marker}"

assert "public static boolean isStorageForVillage(" in registry
assert "public static boolean isBedForVillage(" in registry
assert "public static boolean isWorkBlockForVillage(" in registry

for filename in ("LivingVillages.java", "VillageIndustry.java", "VillageFarmBuilder.java",
                 "VillageCraftingJobs.java", "VillageElder.java"):
    source = (JAVA / filename).read_text(encoding="utf-8")
    assert "VillageRegistry.isStorageForVillage(level, center, pos)" in source, f"{filename} must use stable store ownership"

population = (JAVA / "VillagePopulation.java").read_text(encoding="utf-8")
assert "VillageRegistry.isStorageForVillage(level, center, pos)" in population
assert "VillageRegistry.isStorageForVillage(level, center, chest)" in population
elder = (JAVA / "VillageElder.java").read_text(encoding="utf-8")
assert "VillageRegistry.isBedForVillage(level, center, pos)" in elder

reservations = (JAVA / "VillageReservations.java").read_text(encoding="utf-8")
assert "VillageRegistry.isStorageForVillage(level, center, pos)" in reservations
assert "VillageRegistry.isStorageForVillage(level, groupCenter, storage)" in reservations
print("VILLAGE REGISTRY CONTRACT PASSED: saved identity, serialized asset owners, and main resource scans")
