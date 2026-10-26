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
    "if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))) continue;",
    "remaining.put(item, need - amount)",
    "if (selected.isEmpty() || remaining.values().stream().anyMatch(amount -> amount > 0)) return false;",
    "private record StorageStock(BlockPos pos, Map<Item, Integer> items)",
    "Comparator.comparingDouble(stock -> stock.pos().distSqr(center))",
    "center.offset(-radius, -6, -radius)",
    "tryAddStorageReservation",
    "isStorageAvailable",
    "hasReservationInArea",
    "public static void release(CompoundTag data)",
    "isProjectSiteAvailable",
    "private static boolean sitesConflict(BlockPos first, BlockPos second, int separation)",
    "VillageBuilder.activeProjectSite(candidate)",
    "VillageFarmBuilder.activeProjectSite(candidate)",
):
    assert marker in reservations, f"reservation manager missing {marker}"

checks = {
    "VillageBuilder.java": ("reserveStoragesForItems(level, builder.blockPosition(), builder, \"house\"", "VillageReservations.isProjectSiteAvailable(level, site, 8, null)", "tryAddStorageReservation(level, villager, storage, \"house\")", 'VillageReservations.release(data, "house")'),
    "VillageFarmBuilder.java": ("findStorages(level, candidateOrigin, null)", "firstHoeInStorage(candidateStorages)", "VillageReservations.isProjectSiteAvailable(level, site, 8, null)", "reserveStoragesForItems(level, candidateOrigin, candidate, \"farm\"", "tryAddStorageReservation(level, worker, ref.pos(), \"farm\")", 'VillageReservations.release(data, "farm")'),
    "VillageCraftingJobs.java": ("reserveStoragesForItems(level, origin, villager", "totalInputs.merge(ingredient.item(), ingredient.count(), Integer::sum)", "tryAddStorageReservation(level, villager, ref.pos(), \"crafting\")", 'VillageReservations.release(data, "crafting")'),
    "LivingVillages.java": ("VillageReservations.isStorageAvailable(level, pos, null)",),
    "VillagePopulation.java": ('VillageReservations.release(data, "food_delivery")',),
    "VillageLogistics.java": ('VillageReservations.release(data, "logistics_delivery")',),
    "VillagerAutonomy.java": ('VillageReservations.release(data, "food_stockpiling")',),
}
for filename, markers in checks.items():
    source = (JAVA / filename).read_text(encoding="utf-8")
    for marker in markers:
        assert marker in source, f"{filename} must include {marker}"

planner = (JAVA / "LivingVillages.java").read_text(encoding="utf-8")
assert "VillageReservations.isProjectSiteAvailable(level, base, 8, null)" in planner
assert planner.count("VillageReservations.isProjectSiteAvailable(level, base, 8, null)") == 2

industry = (JAVA / "VillageIndustry.java").read_text(encoding="utf-8")
assert "VillageReservations.isStorageAvailable(level, pos, null)" in industry
assert "hasStorageBlock(level, center, radius)" in industry
assert "VillageReservations.hasReservationInArea(level, center, radius, 8)) return;" not in industry
scheduler = (JAVA / "LivingVillages.java").read_text(encoding="utf-8")
assert "VillageIndustry.tickArea(level, villageCenter);" in scheduler
assert "VillagePopulation.tickArea(level, villageCenter, villagers);\n            if (villagers.stream().anyMatch" not in scheduler

assert "VillageReservations.isStorageAvailable(level, storagePos, villager)" in (JAVA / "VillageBuilder.java").read_text(encoding="utf-8")
assert "VillageReservations.isStorageAvailable(level, storagePos, worker)" in (JAVA / "VillageFarmBuilder.java").read_text(encoding="utf-8")
assert "VillageReservations.isStorageAvailable(level, storagePos, villager)" in (JAVA / "VillageCraftingJobs.java").read_text(encoding="utf-8")
print("RESOURCE RESERVATIONS CONTRACT PASSED: persistent chest claims, conflict checks, owner access, and cleanup")
