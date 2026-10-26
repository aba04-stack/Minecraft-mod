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
    "knownHomes",
    "knownResidents",
    "Resident UUIDs provide a fallback when all known HOME anchors move or disappear.",
    "currentResidentIds",
    "bestOverlap",
    "tag.put(\"knownHomes\", knownHomeTags)",
    "tag.put(\"knownResidents\", knownResidentTags)",
    'tag.getList("knownHomes", Tag.TAG_COMPOUND)',
    'tag.getList("knownResidents", Tag.TAG_COMPOUND)',
    "data.knownHomes.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>()).add(entry.getValue().asLong())",
    "storageOwners",
    "bedOwners",
    "workBlockOwners",
    "public static UUID villageId(ServerLevel level, BlockPos center)",
    "resolveOrCreateId(level, center, residents)",
    "home.dimension().equals(level.dimension())",
    "ID_MATCH_RADIUS_SQUARED",
    "VillageArea.isBlockInResidentGroup(level, groupCenter, location)",
    'tag.put("villageAnchors", villageTags)',
    'tag.put("storageOwners", writeOwners(storageOwners))',
    'tag.put("bedOwners", writeOwners(bedOwners))',
    'tag.put("workBlockOwners", writeOwners(workBlockOwners))',
    'tag.getList("villageAnchors", Tag.TAG_COMPOUND)',
    'readOwners(tag.getList("storageOwners", Tag.TAG_COMPOUND))',
    "setDirty()",
    "registry.knownHomes.computeIfAbsent(bestId, ignored -> new HashSet<>())",
    "registry.knownResidents.computeIfAbsent(bestId, ignored -> new HashSet<>())",
    "removeStaleOwner(registry, storage, registry.storageOwners)",
    "removeStaleOwner(registry, bedFoot, registry.bedOwners)",
    "removeStaleOwner(registry, workBlock, registry.workBlockOwners)",
    "cleanupInvalidAssets(ServerLevel level, BlockPos center, int radius)",
    "removeInvalidOwners(level, registry.storageOwners",
    "removeInvalidOwners(level, registry.bedOwners",
    "removeInvalidOwners(level, registry.workBlockOwners",
):
    assert marker in registry, f"persistent registry missing {marker}"

assert "public static boolean isStorageForVillage(" in registry
assert "public static boolean isBedForVillage(" in registry
assert "public static boolean isWorkBlockForVillage(" in registry
assert "public static boolean isWorkBlockState(BlockState state)" in registry
industry = (JAVA / "VillageIndustry.java").read_text(encoding="utf-8")
assert "VillageRegistry.isWorkBlockForVillage(level, center, pos)" in industry, "industry must only use village-owned furnaces/tables"
crafting = (JAVA / "VillageCraftingJobs.java").read_text(encoding="utf-8")
assert "VillageRegistry.isWorkBlockForVillage(level, center, pos)" in crafting, "crafting jobs must use village-owned tables"
assert "cleanupInvalidAssets(level, villageCenter, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get())" in (JAVA / "LivingVillages.java").read_text(encoding="utf-8")
assert "VillageRegistry.isBedForVillage(level, center, pos)" in (JAVA / "LivingVillages.java").read_text(encoding="utf-8")
assert "VillageRegistry.isBedForVillage(level, center, pos)" in (JAVA / "VillageBuilder.java").read_text(encoding="utf-8")
assert "VillageRegistry.isBedForVillage(level, center, pos)" in (JAVA / "VillagePopulation.java").read_text(encoding="utf-8")

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
