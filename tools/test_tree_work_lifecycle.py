#!/usr/bin/env python3
"""Regression contract for repeatable lumber work and non-clumping settlement leisure."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"
TREE = (JAVA / "VillagerTreeWork.java").read_text(encoding="utf-8")
DAILY = (JAVA / "VillageDailyLife.java").read_text(encoding="utf-8")
DAILY_MODEL = (JAVA / "domain/DailyLifeActivityModel.java").read_text(encoding="utf-8")
CONFIG = (JAVA / "LivingVillagesConfig.java").read_text(encoding="utf-8")
DEMAND = (JAVA / "domain/VillageResourceDemandModel.java").read_text(encoding="utf-8")
GAME_TESTS = (JAVA / "VillageReservationGameTests.java").read_text(encoding="utf-8")

assert "REPLANT_MAX_WAIT_WITHOUT_SAPLING_TICKS = 2400L" in TREE, (
    "the lumberjack must wait up to two minutes for vanilla leaf-decay sapling drops"
)
assert "inflate(12.0D, 6.0D, 12.0D)" in TREE, (
    "the worker must search the canopy/drop area for a real sapling"
)
assert 'recordDiagnostic(level, home == null ? source : home, "Лес"' in TREE, (
    "the elder panel diagnostics must explain when no real sapling can be found"
)
assert "boolean needsWood = adults > 1 && wood < targetWood;" in DEMAND, (
    "wood demand must remain recorded after the last mature tree has been felled"
)
assert "int desiredLumberjacks = needsWood && treesAvailable ? populationWorkSlots : 0;" in DEMAND, (
    "do not send a lumberjack to a location with no mature tree"
)
ROLES = (JAVA / "VillagerRoles.java").read_text(encoding="utf-8")
assert "desiredLumberjackSlots(true, false, 0, true) == 0" in GAME_TESTS
assert "if (!enabled || !treesAvailable) return 0;" in ROLES
assert "resourcePlan.treesAvailable() &&" in TREE,
"no lumberjack assignment without a real tree");

assert '.define("enable_daily_life", false)' in CONFIG, (
    "social visits must be disabled by default so development has priority"
)
assert "hasActiveSocialVisitInVillage(villager, level)" in DAILY, (
    "manual daily-life enablement must still be limited to one active social pair per village"
)
assert "canStartSocialVisitInVillage" in DAILY_MODEL
assert "canStartSocialVisitInVillage(0)" in GAME_TESTS
assert "regrownForest.desiredLumberjacks() == 2" in GAME_TESTS
print("TREE WORK LIFECYCLE PASSED: persistent wood demand, longer real-sapling wait, and one social pair per village")
