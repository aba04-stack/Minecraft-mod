#!/usr/bin/env python3
"""Safety regression checks for active-village scans."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "src/main/java/com/abs/livingvillages/LivingVillages.java").read_text(encoding="utf-8")

start = source.index("private List<Villager> findVillagers")
end = source.index("private BlockPos averageVillagerPosition", start)
find_method = source[start:end]
assert "return VillageArea.findVillagers(level, center);" in find_method, (
    "the active-area planner must use the shared village group selector"
)
area_source = (ROOT / "src/main/java/com/abs/livingvillages/VillageArea.java").read_text(encoding="utf-8")
assert "level.getEntitiesOfClass(Villager.class, area, Villager::isAlive)" in area_source, (
    "the shared village selector must filter out dead villagers"
)

start = source.index("private int countFarmland")
end = source.index("private BlockPos findFarmSite", start)
farmland_method = source[start:end]
guard = "if (!level.hasChunkAt(pos)) continue;"
get_state = "level.getBlockState(pos)"
assert guard in farmland_method, "farmland scan must skip unloaded chunks before reading block states"
assert farmland_method.index(guard) < farmland_method.index(get_state), (
    "chunk-loaded guard must run before accessing the block state"
)

start = source.index("private int countBeds")
end = source.index("private List<Container> findResourceContainers", start)
beds_method = source[start:end]
assert "if (!level.hasChunkAt(pos)) continue;" in beds_method, (
    "bed scan must avoid touching unloaded chunks"
)
print("ACTIVE AREA SAFETY PASSED: only living villagers are counted; farmland/bed scans skip unloaded chunks")
# Site-finding loops must check chunk availability before heightmap/block access.
for method_name, next_method in (
    ("private BlockPos findFarmSite", "private boolean isSuitableFarmSite"),
    ("private BlockPos findBuildSite", "private boolean isSuitableSite"),
):
    start = source.index(method_name)
    end = source.index(next_method, start)
    finder = source[start:end]
    guard = "if (!level.hasChunkAt(new BlockPos(x, center.getY(), z))) continue;"
    assert guard in finder, f"{method_name} must skip unloaded candidate columns"
    assert finder.index(guard) < finder.index("level.getHeight("), (
        f"{method_name} must check chunk availability before querying heightmaps"
    )

for method_name, next_method in (
    ("private boolean isSuitableFarmSite", "private boolean isFarmableGround"),
    ("private boolean isSuitableSite", "private record ActiveArea"),
):
    start = source.index(method_name)
    end = source.index(next_method, start)
    suitability = source[start:end]
    guard = "if (!level.hasChunkAt(new BlockPos(x, baseY, z))) return false;"
    assert guard in suitability, f"{method_name} must reject sites spanning unloaded chunks"
    assert suitability.index(guard) < suitability.index("level.getHeight("), (
        f"{method_name} must check chunk availability before querying heightmaps"
    )

farm_start = source.index("private boolean isSuitableFarmSite")
farm_end = source.index("private boolean isFarmableGround", farm_start)
farm_suitability = source[farm_start:farm_end]
composter_guard = "if (!level.hasChunkAt(new BlockPos(composterX, baseY, composterZ))) return false;"
assert composter_guard in farm_suitability, "farm site must reject an unloaded composter column"
assert farm_suitability.index(composter_guard) < farm_suitability.index("level.getHeight(", farm_suitability.index("int composterX")), (
    "farm site must check composter chunk availability before querying its heightmap"
)

# Guard patrol-point selection must check chunk availability before querying the heightmap.
defense_source = (ROOT / "src/main/java/com/abs/livingvillages/VillageDefense.java").read_text(encoding="utf-8")
start = defense_source.index("private static BlockPos choosePatrolPoint")
end = defense_source.index("private static BlockPos getOrCreateHome", start)
patrol_method = defense_source[start:end]
patrol_guard = "if (!level.hasChunkAt(new BlockPos(x, center.getY(), z)))"
assert patrol_guard in patrol_method, "patrol selection must check the destination chunk before querying heightmaps"
assert patrol_method.index(patrol_guard) < patrol_method.index("level.getHeight("), (
    "patrol selection must not query the heightmap before checking chunk availability"
)
