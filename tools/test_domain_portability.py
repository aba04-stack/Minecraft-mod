#!/usr/bin/env python3
"""Static architectural check: the domain package must remain free of Minecraft/NeoForge imports."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOMAIN_DIR = ROOT / "src/main/java/com/abs/livingvillages/domain"

assert DOMAIN_DIR.exists(), "Domain package directory must exist."
domain_files = sorted(DOMAIN_DIR.glob("*.java"))
assert len(domain_files) >= 4, "Domain package must contain progression, monster AI, tick budget, and settlement models."

forbidden_prefixes = (
    "import net.minecraft.",
    "import com.mojang.",
    "import net.neoforged.",
    "import io.netty.",
)

for path in domain_files:
    text = path.read_text(encoding="utf-8")
    assert "package com.abs.livingvillages.domain;" in text, f"{path.name} must declare the domain package"
    for prefix in forbidden_prefixes:
        assert prefix not in text, f"{path.name} must not import platform package: {prefix}"

prog_model = (DOMAIN_DIR / "VillagerProgressionModel.java").read_text(encoding="utf-8")
assert "public static final int SCHEMA_VERSION = 1;" in prog_model
assert "public record AwardResult(" in prog_model
assert "new ItemStack(" not in prog_model, "domain progression model must never instantiate items"

monster_model = (DOMAIN_DIR / "MonsterTacticalModel.java").read_text(encoding="utf-8")
assert "public static final int MAX_PATH_RETRIES = 3;" in monster_model
assert "public static boolean canPerceiveTarget(" in monster_model
assert "public record TacticalDecision(" in monster_model

budget_model = (DOMAIN_DIR / "TickBudgetModel.java").read_text(encoding="utf-8")
assert "public static boolean isScheduledThisTick(" in budget_model
assert "public static boolean canConsumeQuota(" in budget_model
assert "public record TickTelemetrySnapshot(" in budget_model
assert "public static double averageMicrosPerTick(" in budget_model

settlement_model = (DOMAIN_DIR / "SettlementSimulationModel.java").read_text(encoding="utf-8")
assert "public record ProjectCheckpoint(" in settlement_model
assert "public record CatchUpResult(" in settlement_model
assert "public record TradeExchangeResult(" in settlement_model
assert "public static boolean canResumeOrphanedProject(" in settlement_model
assert "public static CatchUpResult evaluateFurnaceCatchUp(" in settlement_model
assert "public static int evaluateRaiderSquad(" in settlement_model
assert "public static boolean canCraftInParallelWithConstruction(" in settlement_model
assert "public static TradeExchangeResult evaluateTradeCaravan(" in settlement_model

print("DOMAIN PORTABILITY PASSED: zero Minecraft/NeoForge imports in domain package; pure DTO contracts verified")
