#!/usr/bin/env python3
"""Static contract checks for persistent physical-storage reservations."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"
reservations = (JAVA / "VillageReservations.java").read_text(encoding="utf-8")

for marker in (
    "data.putLongArray(POSITIONS, packed)",
    "data.getLongArray(POSITIONS)",
    'data.getString(KIND).equals(expectedKind)',
    'if (data.getBoolean(ACTIVE) && !data.getString(KIND).equals(kind)) return false;',
    'if (data.getBoolean(ACTIVE) && positions.contains(storage.asLong())) return true;',
    "tryReserveStoragePositions",
    "reserveStoragesForItems",
    "available.getOrDefault(requirement.getKey(), 0) < requirement.getValue()",
    "relevantStorages.add(pos.immutable())",
    "tryAddStorageReservation",
    "isStorageAvailable",
    "hasReservationInArea",
    "public static void release(CompoundTag data)",
):
    assert marker in reservations, f"reservation manager missing {marker}"

checks = {
    "VillageBuilder.java": ("reserveStoragesForItems(level, center, builder, \"house\"", "tryAddStorageReservation(level, villager, storage, \"house\")", 'VillageReservations.release(data, "house")'),
    "VillageFarmBuilder.java": ("reserveStoragesForItems(level, center, worker, \"farm\"", "tryAddStorageReservation(level, worker, ref.pos(), \"farm\")", 'VillageReservations.release(data, "farm")'),
    "VillageCraftingJobs.java": ("reserveStoragesForItems(level, origin, villager", "missingInputs.merge(ingredient.item(), missing, Integer::sum)", "tryAddStorageReservation(level, villager, ref.pos(), \"crafting\")", 'VillageReservations.release(data, "crafting")'),
    "LivingVillages.java": ("VillageReservations.isStorageAvailable(level, pos, null)",),
    "VillagePopulation.java": ('VillageReservations.release(data, "food_delivery")',),
    "VillageLogistics.java": ('VillageReservations.release(data, "logistics_delivery")',),
    "VillagerAutonomy.java": ('VillageReservations.release(data, "food_stockpiling")',),
}
for filename, markers in checks.items():
    source = (JAVA / filename).read_text(encoding="utf-8")
    for marker in markers:
        assert marker in source, f"{filename} must include {marker}"

assert "VillageReservations.isStorageAvailable(level, storagePos, villager)" in (JAVA / "VillageBuilder.java").read_text(encoding="utf-8")
assert "VillageReservations.isStorageAvailable(level, storagePos, worker)" in (JAVA / "VillageFarmBuilder.java").read_text(encoding="utf-8")
assert "VillageReservations.isStorageAvailable(level, storagePos, villager)" in (JAVA / "VillageCraftingJobs.java").read_text(encoding="utf-8")
print("RESOURCE RESERVATIONS CONTRACT PASSED: persistent chest claims, conflict checks, owner access, and cleanup")
