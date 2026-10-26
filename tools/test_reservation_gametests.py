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
    '@GameTest(template = "empty", timeoutTicks = 20)',
    "oneTaskExcludesCompetingTaskUntilItEnds",
    "removedChestDoesNotKeepAStaleReservation",
    "neighboringVillagesKeepSeparateStorageOwnership",
    "VillageArea.findVillagers(helper.getLevel(), centerOne, 32, 32).size() == 2",
    "Neighboring HOME clusters must retain distinct persistent village identities.",
    "The first village must not claim the second village's storage.",
    "VillageReservations.tryReserveStoragePositions",
    "VillageRegistry.isStorageForVillage",
    "Removing a chest must also clear its persisted village-ownership claim.",
    "A claim must be released when its owning task ends.",
    "A removed container must not retain its reservation.",
):
    assert marker in SOURCE, f"GameTest source is missing {marker}"

assert TEMPLATE.exists(), "The GameTest empty structure template must exist."
with gzip.open(TEMPLATE, "rb") as stream:
    nbt = stream.read()
assert nbt, "The GameTest template must be a non-empty gzip-compressed NBT document."
assert "type = 'gameTestServer'" in BUILD, "Gradle must declare the real GameTestServer run."
assert (
    "systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id" in BUILD
), "GameTestServer must enable this mod's test namespace."
assert "runGameTestServer" in WORKFLOW, "CI must execute the registered GameTests."
print("RESERVATION GAMETEST CONTRACT PASSED: source registration, NBT template, Gradle and CI wiring")
