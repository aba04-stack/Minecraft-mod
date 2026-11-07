#!/usr/bin/env python3
"""Guard the shared autonomous worker movement baseline against regressions."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
WORKERS = {
    "VillageIndustry.java": 0.80,
    "VillageLogistics.java": 0.80,
    "VillagerAutonomy.java": 0.80,
    "VillagerTreeWork.java": 0.80,
    "VillageBuilder.java": 0.80,
    "VillageFarmBuilder.java": 0.80,
    "VillagerMining.java": 0.80,
    "VillagerCaveSupport.java": 0.80,
    "VillagePopulation.java": 0.80,
    "VillageCraftingJobs.java": 0.80,
}

for filename, expected in WORKERS.items():
    path = ROOT / "src/main/java/com/abs/livingvillages" / filename
    source = path.read_text(encoding="utf-8")
    match = re.search(r"private\s+static\s+final\s+float\s+WALK_SPEED\s*=\s*([0-9.]+)F;", source)
    assert match, f"{filename} must declare a WALK_SPEED constant"
    actual = float(match.group(1))
    assert abs(actual - expected) < 1e-6, (
        f"{filename} WALK_SPEED is {actual}; expected {expected} to keep worker routes responsive"
    )

print("WORKER MOVEMENT SPEED PASSED: all ten autonomous work systems use 0.80")
