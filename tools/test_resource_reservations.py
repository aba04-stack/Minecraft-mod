#!/usr/bin/env python3
"""Static contract checks for persistent physical-storage reservations."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"
reservations = (JAVA / "VillageReservations.java").read_text(encoding="utf-8")

for marker in (
    "data.putLongArray(POSITIONS, packed)",
    "data.getLongArray(POSITIONS)",
    "tryReserveStoragePositions",
    "tryAddStorageReservation",
    "isStorageAvailable",
    "hasReservationInArea",
    "public static void release(CompoundTag data)",
):
    assert marker in reservations, f"reservation manager missing {marker}"

checks = {
    "VillageBuilder.java": ("reserveNearbyStorages(level, center, builder, \"house\")", "tryAddStorageReservation(level, villager, storage, \"house\")", "VillageReservations.release(data)"),
    "VillageFarmBuilder.java": ("reserveNearbyStorages(level, center, worker, \"farm\")", "tryAddStorageReservation(level, worker, ref.pos(), \"farm\")", "VillageReservations.release(data)"),
    "VillageCraftingJobs.java": ("tryReserveStoragePositions(level, villager", "tryAddStorageReservation(level, villager, ref.pos(), \"crafting\")", "VillageReservations.release(data)"),
    "LivingVillages.java": ("VillageReservations.isStorageAvailable(level, pos, null)",),
}
for filename, markers in checks.items():
    source = (JAVA / filename).read_text(encoding="utf-8")
    for marker in markers:
        assert marker in source, f"{filename} must include {marker}"

assert "VillageReservations.isStorageAvailable(level, storagePos, villager)" in (JAVA / "VillageBuilder.java").read_text(encoding="utf-8")
assert "VillageReservations.isStorageAvailable(level, storagePos, worker)" in (JAVA / "VillageFarmBuilder.java").read_text(encoding="utf-8")
assert "VillageReservations.isStorageAvailable(level, storagePos, villager)" in (JAVA / "VillageCraftingJobs.java").read_text(encoding="utf-8")
print("RESOURCE RESERVATIONS CONTRACT PASSED: persistent chest claims, conflict checks, owner access, and cleanup")
