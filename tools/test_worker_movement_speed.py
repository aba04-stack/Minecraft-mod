#!/usr/bin/env python3
"""Regression checks for autonomous worker movement and task responsiveness."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"
WORKERS = {
    "VillageIndustry.java": 0.90,
    "VillageLogistics.java": 0.90,
    "VillagerAutonomy.java": 0.90,
    "VillagerTreeWork.java": 0.90,
    "VillageBuilder.java": 0.90,
    "VillageFarmBuilder.java": 0.90,
    "VillagerMining.java": 0.90,
    "VillagerCaveSupport.java": 0.90,
    "VillagePopulation.java": 0.90,
    "VillageCraftingJobs.java": 0.90,
}
for filename, expected in WORKERS.items():
    source = (JAVA / filename).read_text(encoding="utf-8")
    match = re.search(r"private\s+static\s+final\s+float\s+WALK_SPEED\s*=\s*([0-9.]+)F;", source)
    assert match, f"{filename} must declare a WALK_SPEED constant"
    actual = float(match.group(1))
    assert abs(actual - expected) < 1e-6, (
        f"{filename} WALK_SPEED is {actual}; expected {expected}"
    )

main = (JAVA / "LivingVillages.java").read_text(encoding="utf-8")
daily = (JAVA / "VillageDailyLife.java").read_text(encoding="utf-8")
tree = (JAVA / "VillagerTreeWork.java").read_text(encoding="utf-8")
mining = (JAVA / "VillagerMining.java").read_text(encoding="utf-8")
logistics = (JAVA / "VillageLogistics.java").read_text(encoding="utf-8")
industry = (JAVA / "VillageIndustry.java").read_text(encoding="utf-8")

assert "tickCounter % 20L == 0L" in main, "roles should be reconciled every second"
assert "tickCounter % 100L == 0L" in main, "industry should run every five seconds"
assert "Math.min(LivingVillagesConfig.SCAN_INTERVAL_SECONDS.get(), 10) * 20L" in main, (
    "full planner must not wait longer than ten seconds, including with old configs"
)
assert "SEARCH_RETRY_TICKS = 40" in tree and "ROUTE_RETRY_TICKS = 30" in tree
assert "SEARCH_RETRY_TICKS = 40" in mining and "ROUTE_RETRY_TICKS = 30" in mining
assert "WORK_RETRY_TICKS = 40L" in (JAVA / "VillageBuilder.java").read_text(encoding="utf-8")
assert "RETRY_TICKS = 40L" in (JAVA / "VillageFarmBuilder.java").read_text(encoding="utf-8")
assert "SEARCH_RETRY_TICKS = 40" in logistics and "SUCCESS_RETRY_TICKS = 40" in logistics
assert "hasSpecialistAssignment(villager)" in daily
assert "hasSpecialistAssignment(peer)" in daily
assert "VillageDailyLife.isOnSocialVisit(villager)" in logistics + industry
for worker in (tree, mining, logistics):
    assert "villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)" in worker
assert industry.count("GameRules.RULE_MOBGRIEFING") >= 3, (
    "storage/workstation/furnace placement must respect mobGriefing"
)
print("WORKER RESPONSIVENESS PASSED: 0.90 routes, 1s role assignment, 5s industry, <=10s planner, bounded retries, route ownership, specialist priority, and mobGriefing")
