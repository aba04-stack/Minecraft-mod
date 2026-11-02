#!/usr/bin/env python3
"""Static regression checks for honest monster perception, retry caps, and tick budgets."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"

monster_ai = (JAVA / "VillageMonsterAI.java").read_text(encoding="utf-8")
budget = (JAVA / "VillageTickBudget.java").read_text(encoding="utf-8")
config = (JAVA / "LivingVillagesConfig.java").read_text(encoding="utf-8")
main = (JAVA / "LivingVillages.java").read_text(encoding="utf-8")
gametests = (JAVA / "VillageReservationGameTests.java").read_text(encoding="utf-8")

assert 'define("enable_tactical_monster_ai", true)' in config
assert 'defineInRange("monster_tactical_radius_blocks", 24, 8, 48)' in config
assert 'defineInRange("max_ai_checks_per_tick", 24, 4, 128)' in config
assert "NeoForge.EVENT_BUS.addListener(VillageMonsterAI::onEntityTick);" in main

for marker in (
    "TickBudgetModel.isScheduledThisTick(",
    "VillageTickBudget.tryConsumeAiCheck(level)",
    "level.hasChunkAt(mob.blockPosition())",
    "ClipContext.Block.COLLIDER",
    "hasClearLineOfSight(ServerLevel level, LivingEntity observer, LivingEntity target)",
    "MonsterTacticalModel.canPerceiveTarget(",
    "MonsterTacticalModel.evaluateTacticalStep(",
    "GameRules.RULE_MOBGRIEFING",
    "decision.resetTarget()",
    "alertNearbyRaiders(",
    "stepLastKnownSearchOrPatrol(",
    "selectPriorityAllyForWitchSupport(",
    "hasClimbableVerticalSurfaceNear(",
):
    assert marker in monster_ai, f"VillageMonsterAI.java missing {marker}"

defense = (JAVA / "VillageDefense.java").read_text(encoding="utf-8")
for marker in (
    "selectPriorityThreat(",
    "assistWoundedVillagerAfterCombat(",
    "scoreGuardThreatPriority(",
):
    assert marker in defense, f"VillageDefense.java missing {marker}"

for marker in (
    "MAX_SIGNALS_PER_LEVEL = 32",
    "SIGNAL_TTL_TICKS = 160L",
    "tryConsumeAiCheck(ServerLevel level)",
    "recordActivitySignal(ServerLevel level, BlockPos pos)",
    "findRecentSignalNear(ServerLevel level, BlockPos origin, double maxRangeSqr)",
):
    assert marker in budget, f"VillageTickBudget.java missing {marker}"

assert "monsterTacticalPerceptionRejectsHiddenTargetsAndCapsPathRetries" in gametests
assert "enhancedMobBehaviorsPillagerAlertSearchPatrolSkeletonSpreadCreeperHoldWitchTriageAndGuardAidWorkInWorld" in gametests
assert "villagerTrustTiersAnimalHerdParentalBreedingAndPillagerReloadWorkInWorld" in gametests
assert "isPillagerReloadingCrossbow(" in monster_ai
assert "alertNearbyZombies(" in monster_ai

animal_ai = (JAVA / "VillageAnimalAI.java").read_text(encoding="utf-8")
for marker in (
    "alertHerdOnDanger(",
    "evaluateAndApplyAnimalBehavior(",
    "isValidSpeciesBreedingFood(",
    "performPreBreedingCourtship(",
    "registerOffspringFromParents(",
    "stepOffspringGrowth(",
):
    assert marker in animal_ai, f"VillageAnimalAI.java missing {marker}"

soul = (JAVA / "VillagerSoul.java").read_text(encoding="utf-8")
for marker in (
    "getPlayerTrust(",
    "recordTrustEvent(",
    "recordVillageDefenseHelp(",
    "recordPlayerTheft(",
    "applyTrustBehaviorToVillager(",
):
    assert marker in soul, f"VillagerSoul.java missing {marker}"

print("MONSTER TACTICAL AI PASSED: honest LOS perception, bounded signals, path-retry cap, and tick quotas")
