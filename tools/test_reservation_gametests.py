#!/usr/bin/env python3
"""Checks that real Minecraft reservation GameTests are registered and run by CI."""
from pathlib import Path
import gzip

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages/VillageReservationGameTests.java"
TEMPLATE = ROOT / "src/main/resources/data/livingvillages/structure/empty.nbt"
BUILD = (ROOT / "build.gradle").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
SOURCE = JAVA.read_text(encoding="utf-8")

for marker in (
    "@GameTestHolder(LivingVillages.MOD_ID)",
    "@PrefixGameTestTemplate(false)",
    '@GameTest(template = "empty", timeoutTicks = 20, batch = "reservation_competition")',
    '@GameTest(template = "empty", timeoutTicks = 20, batch = "stale_storage_cleanup")',
    '@GameTest(template = "empty", timeoutTicks = 20, batch = "neighbor_village_isolation")',
    "oneTaskExcludesCompetingTaskUntilItEnds",
    "removedChestDoesNotKeepAStaleReservation",
    "neighboringVillagesKeepSeparateStorageOwnership",
    "multipleResidentsWithoutHomeDoNotBreakGrouping",
    '@GameTest(template = "empty", timeoutTicks = 20, batch = "home_less_resident_assignment")',
    "Multiple HOME-less residents must attach safely to one nearby HOME group.",
    "activeHouseAndFarmProjectsProtectTheirFootprints",
    '@GameTest(template = "empty", timeoutTicks = 20, batch = "project_site_collision")',
    "A new house or farm site inside the safety margin of an active house must be rejected.",
    "A new project must also respect the safety margin around an active farm.",
    "VillageArea.findVillagers(helper.getLevel(), centerOne, 32, 32).size() == 2",
    "Neighboring HOME clusters must retain distinct persistent village identities.",
    "The first village must not claim the second village's storage.",
    "VillageReservations.tryReserveStoragePositions",
    "VillageReservations.isProjectSiteAvailable",
    "VillageRegistry.isStorageForVillage",
    "VillageRegistry.isBedForVillage",
    "VillageRegistry.isWorkBlockForVillage",
    "The first bed must register to the first village.",
    "The first workshop must register to the first village.",
    "The first village must not count the second village's bed.",
    "The first village must not use the second village's workshop.",
    "Removing a chest must also clear its persisted village-ownership claim.",
    "A claim must be released when its owning task ends.",
    '@GameTest(template = "empty", timeoutTicks = 40, batch = "village_identity_recovery")',
    "villageIdentitySurvivesHomeAnchorLossAndRelocation",
    '@GameTest(template = "empty", timeoutTicks = 20, batch = "registry_save_load_roundtrip")',
    "villageRegistryPreservesAssetOwnershipAcrossSaveLoadRoundTrip",
    "canonicalTagRows(saved, key)",
    "SavedData load/save.",
    "Resident UUID overlap must preserve the village identity when HOME anchors disappear and residents relocate.",
    "A previously registered chest must remain owned by the same village after relocation.",
    "A removed container must not retain its reservation.",
):
    assert marker in SOURCE, f"GameTest source is missing {marker}"

assert TEMPLATE.exists(), "The GameTest empty structure template must exist."
with gzip.open(TEMPLATE, "rb") as stream:
    nbt = stream.read()
assert nbt, "The GameTest template must be a non-empty gzip-compressed NBT document."
assert b"\x00\x00\x00\x1f\x00\x00\x00\x04\x00\x00\x00\x03" in nbt, (
    "The shared GameTest template must be wide enough for the neighboring-village scenario."
)
assert "type = 'gameTestServer'" in BUILD, "Gradle must declare the real GameTestServer run."
assert (
    "systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id" in BUILD
), "GameTestServer must enable this mod's test namespace."
assert "runGameTestServer" in WORKFLOW, "CI must execute the registered GameTests."
print("RESERVATION GAMETEST CONTRACT PASSED: source registration, NBT template, Gradle and CI wiring")
