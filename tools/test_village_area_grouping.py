#!/usr/bin/env python3
"""Static contract checks for shared nearest-village grouping.

These tests guard wiring and selection rules; they do not substitute for an in-world test
with two nearby villages and villagers whose HOME memories change.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"

area = (JAVA / "VillageArea.java").read_text(encoding="utf-8")
assert "MemoryModuleType.HOME" in area, "the selector should prefer saved villager HOME memories"
assert "home.dimension().equals(level.dimension())" in area, "HOME anchors must belong to the current dimension"
assert "HOME_CLUSTER_LINK_BLOCKS = 20" in area, "home-based groups need a bounded link distance"
assert "FALLBACK_CLUSTER_LINK_BLOCKS = 20" in area, "residents without HOME memories need a bounded fallback"
assert "clusters.get(nearestCluster).add(resident)" in area, "homeless residents must attach to one nearest cluster, not bridge clusters"
assert "selectNearestCluster(clusters, center)" in area, "active work must choose the group nearest its requested center"
assert "level.getEntitiesOfClass(Villager.class, area, Villager::isAlive)" in area, "only living villagers should be candidates"

checks = {
    "LivingVillages.java": "return VillageArea.findVillagers(level, center);",
    "VillagerRoles.java": "List<Villager> all = VillageArea.findVillagers(level, center);",
    "VillageIndustry.java": "List<Villager> villagers = VillageArea.findVillagers(level, center);",
    "VillageBuilder.java": "VillageArea.findVillagers(level, center).stream()",
    "VillageFarmBuilder.java": "VillageArea.findVillagers(level, center).stream()",
    "VillageCraftingJobs.java": "List<Villager> villagers = VillageArea.findVillagers(level, origin);",
    "VillageElder.java": "List<Villager> villagers = VillageArea.findVillagers(level, origin);",
    "VillagePopulation.java": "VillageArea.findVillagers(level, center).stream()",
    "VillageDefense.java": "VillageArea.findVillagers(level, guard.blockPosition(), radius, 8).stream()",
}
for filename, marker in checks.items():
    source = (JAVA / filename).read_text(encoding="utf-8")
    assert marker in source, f"{filename} must use the shared village-group selector"

print("VILLAGE AREA GROUPING CONTRACT PASSED: HOME-anchored groups, nearest-cluster selection, and shared subsystem wiring")
