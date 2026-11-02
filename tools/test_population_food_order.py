#!/usr/bin/env python3
"""Regression check: food courier targets the hungriest eligible adult first."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / "src/main/java/com/abs/livingvillages/VillagePopulation.java").read_text(encoding="utf-8")
start = source.index("private static Villager findHungryRecipient")
end = source.index("private static Villager findVillager", start)
method = source[start:end]
assert ".min(Comparator.comparingInt((Villager v) -> foodPoints(v))" in method, "must choose minimum food points (hungriest villager)"
assert ".thenComparing(v -> v.getUUID().toString())" in method, "ties should be deterministic"
assert ".reversed()" not in method, "reversing the comparator would choose the less hungry villager"
print("POPULATION FOOD ORDER PASSED: hungriest adult selected first with deterministic tie-break")
