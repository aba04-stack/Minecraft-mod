#!/usr/bin/env python3
"""Regression contract for the precise village resource economy.

Covers reservation-aware stock, separate fuel/iron/sapling categories, deficit-led miner
selection, the single-pass field scan, planner cache invalidation on real deliveries, and the
paged elder summary that exposes those deficits to the player.
"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"
DEMAND = (JAVA / "domain/VillageResourceDemandModel.java").read_text(encoding="utf-8")
PLANNER = (JAVA / "VillageResourcePlanner.java").read_text(encoding="utf-8")
ROLES = (JAVA / "VillagerRoles.java").read_text(encoding="utf-8")
MINING = (JAVA / "VillagerMining.java").read_text(encoding="utf-8")
CAVE = (JAVA / "VillagerCaveSupport.java").read_text(encoding="utf-8")
LOGISTICS = (JAVA / "VillageLogistics.java").read_text(encoding="utf-8")
INDUSTRY = (JAVA / "VillageIndustry.java").read_text(encoding="utf-8")
GAME_TESTS = (JAVA / "VillageReservationGameTests.java").read_text(encoding="utf-8")

# 1. Domain: reserved quantities never count as free stock.
for marker in (
    "int reservedWoodUnits",
    "int reservedStoneUnits",
    "int reservedOreUnits",
    "int reservedFoodPoints",
    "int freeWood = freeOf(wood, stock.reservedWoodUnits());",
    "int freeStone = freeOf(stone, stock.reservedStoneUnits());",
    "int freeOre = freeOf(ore, stock.reservedOreUnits());",
    "int freeFood = freeOf(food, stock.reservedFoodPoints());",
    "boolean needsWood = adults > 1 && freeWood < targetWood;",
    "boolean needsStone = adults > 1 && freeStone < targetStone;",
    "boolean needsOre = adults > 1 && freeOre < targetOre;",
    "boolean needsFood = adults > 1 && freeFood < targetFood;",
    "private static int freeOf(int measured, int reserved) {",
    "return Math.max(0, measured - Math.max(0, reserved));",
):
    assert marker in DEMAND, f"demand model must keep reservation-aware stock: {marker}"

# 2. Domain: concrete fuel/iron/sapling categories instead of one coarse ore bucket.
for marker in (
    "int fuelUnits",
    "int ironUnits",
    "int saplings",
    "boolean furnaceAvailable",
    "int targetFuel = clamp(adults, 4, 32);",
    "int targetIron = clamp(adults, 2, 24);",
    "int targetSaplings = clamp(2 + adults / 2, 4, 16);",
    "boolean needsFuel = adults > 1 && fuel < targetFuel",
    "&& (furnaceAvailable || iron > 0 || freeOre > 0);",
    "boolean needsIron = adults > 1 && iron < targetIron;",
    "boolean needsSaplings = adults > 1 && treesAvailable && saplings < targetSaplings;",
    "public boolean needsOreWork() {",
    "return needsOre || needsFuel || needsIron;",
    "public int shortageCount() {",
):
    assert marker in DEMAND, f"demand model must expose precise deficits: {marker}"

# 3. Domain: backwards-compatible constructors keep older callers and GameTests compiling.
assert DEMAND.count("public Stock(") == 2, "both legacy Stock constructors must remain"
assert "boolean treesAvailable, int availablePickaxes) {" in DEMAND
assert "boolean treesAvailable, int availablePickaxes, boolean desertBiome) {" in DEMAND
assert "int desiredLumberjacks = needsWood && treesAvailable ? populationWorkSlots : 0;" in DEMAND, (
    "sapling demand must not turn into runaway logging once the wood target is met"
)
assert "import net.minecraft." not in DEMAND and "import net.neoforged." not in DEMAND, (
    "the demand model must stay platform independent"
)

# 4. Adapter: real measurement of fuel, iron, saplings, furnace and reserved quantities.
for marker in (
    "if (item == Items.COAL || item == Items.CHARCOAL) {",
    "if (item == Items.RAW_IRON || item == Items.IRON_INGOT) {",
    "if (state.is(BlockTags.SAPLINGS)) totals.saplings += count;",
    "VillageIndustry.hasFurnace(level, center)",
    "VillageWorkOrders.reservedItemCounts(level, center, null, now)",
    "BuiltInRegistries.ITEM.get(id)",
    "private static void accumulate(Totals totals, ItemStack stack) {",
    "private static void accumulate(Totals totals, Item item, int rawCount) {",
):
    assert marker in PLANNER, f"planner must measure precise real stock: {marker}"
assert "public static boolean hasFurnace(ServerLevel level, BlockPos center) {" in INDUSTRY

# 5. Adapter: one pass over the field volume and measurable scan cost.
assert "private static CropScan countCropFields(ServerLevel level, BlockPos center) {" in PLANNER
assert "countMatureCrops" not in PLANNER and "countPlantableFarmland" not in PLANNER, (
    "ripe crops and empty farmland must be counted in a single pass over the village box"
)
assert "private record CropScan(int matureCrops, int plantableFarmland) {}" in PLANNER
assert "VillageTickBudget.recordSubsystemNanos(level, System.nanoTime() - startedNanos);" in PLANNER, (
    "planner refresh cost must be reported to the tick-budget telemetry"
)
assert "public static void invalidate(ServerLevel level, BlockPos center) {" in PLANNER
assert "VillageResourcePlanner.invalidate(level, center);" in LOGISTICS, (
    "a real delivery must refresh quotas immediately instead of waiting for the cache"
)
# The board publishes an immutable deficit snapshot; the courier may only decrement its own copy.
assert "Map<String, Integer> deficits = VillageWorkOrders.pendingMaterialPickupDeficits(level, center);" in LOGISTICS
assert "return deficits == null ? null : new HashMap<>(deficits);" in LOGISTICS, (
    "mutating the board's immutable deficit snapshot throws inside the entity tick and crashes the server"
)
assert "public static int collectPendingMaterials(Villager villager, ServerLevel level)" in LOGISTICS, (
    "the pickup route needs a test seam: a GameTest cannot fire an entity tick event"
)
assert "item.pickupDelay <= 0" in LOGISTICS, (
    "a courier must respect the vanilla pickup delay instead of stealing player-dropped items"
)

# 6. Roles/mining: a concrete coal or iron deficit releases and steers a miner.
assert "static int desiredMinerSlots(boolean enabled, int availablePickaxes, int plannedWorkers," in ROLES
assert "if (!enabled || availablePickaxes <= 0) return 0;" in ROLES
assert "if (needsFuel || needsIron) slots = Math.max(slots, 1);" in ROLES
assert "return Math.min(slots, availablePickaxes);" in ROLES
assert "resourcePlan.needsFuel(), resourcePlan.needsIron());" in ROLES
for marker in (
    "boolean oreDemand = demand.needsOreWork();",
    "if (!demand.needsMining() && !oreDemand && requestedQuarryOutput == Items.AIR) return;",
    "findNearestOre(level, villager, pickaxe, preferredOres(demand))",
    "private static List<Block> preferredOres(",
    "preferred.add(Blocks.COAL_ORE);",
    "preferred.add(Blocks.DEEPSLATE_COAL_ORE);",
    "preferred.add(Blocks.IRON_ORE);",
    "preferred.add(Blocks.DEEPSLATE_IRON_ORE);",
    "(deficitOre ? wanted : others).add(pos.immutable());",
):
    assert marker in MINING, f"miner must prefer the ore the village actually lacks: {marker}"
assert ".needsOreWork()" in CAVE, "cave scouting must follow the precise ore deficit too"

# 7. Player-visible deficits: startup hint and in-world GameTest coverage.
assert 'if (plan.needsFuel()) {' in INDUSTRY
assert 'if (plan.needsIron()) {' in INDUSTRY
assert 'if (plan.needsSaplings()) {' in INDUSTRY
assert 'batch = "resource_demand_precision"' in GAME_TESTS
assert "reservedMaterialsAndConcreteDeficitsDrivePreciseWorkerDemand" in GAME_TESTS
for marker in (
    "committed.freeWoodUnits() == 56 && committed.needsWood()",
    "uncommitted.freeWoodUnits() == 256 && !uncommitted.needsWood()",
    "committed.needsFuel() && committed.needsIron() && committed.needsOreWork()",
    "fueled.shortageCount() == 0",
    "bareForest.needsSaplings() && bareForest.targetSaplings() == 6",
    "!treelessVillage.needsSaplings()",
    "VillagerRoles.desiredMinerSlots(true, 2, 0, false, true, false) == 1",
    "VillagerRoles.desiredMinerSlots(true, 0, 3, true, true, true) == 0",
):
    assert marker in GAME_TESTS, f"GameTest must pin the precise demand rules: {marker}"

# 8. Translations stay valid and in sync for the paged summary indicator.
lang_dir = ROOT / "src/main/resources/assets/livingvillages/lang"
keys = {}
for path in sorted(lang_dir.glob("*.json")):
    keys[path.name] = set(json.loads(path.read_text(encoding="utf-8")))
assert len(keys) >= 2, "both en_us and ru_ru translations must exist"
for name, key_set in keys.items():
    assert "livingvillages.elder.notice_page" in key_set, f"{name} must translate the summary page indicator"
assert len({frozenset(value) for value in keys.values()}) == 1, "translation key sets must stay identical"

# 9. In-world coverage: a real chest, a real furnace, a real tree and a real order on the board.
assert "plannerMeasuresRealFuelSaplingsAndSubtractsReservedOrderMaterials" in GAME_TESTS
assert 'timeoutTicks = 40, batch = "resource_demand_precision"' in GAME_TESTS
for marker in (
    "stocked.freeWoodUnits() == 256 && !stocked.needsWood()",
    "stocked.needsFuel() && !stocked.needsSaplings()",
    "!fueled.needsFuel() && !fueled.needsIron() && fueled.freeWoodUnits() == 256",
    "bareForest.treesAvailable() && bareForest.needsSaplings()",
    "regenerating.treesAvailable() && !regenerating.needsSaplings()",
    "committed.freeWoodUnits() == 56 && committed.needsWood()",
    'VillageWorkOrders.enqueue(helper.getLevel(), center, "house", "house", 55, now, 10000L,',
    "VillageWorkOrderModel.Requirement(\"minecraft:oak_planks\", 200)",
    "VillageResourcePlanner.invalidate(helper.getLevel(), center);",
    "VillageRegistry.isStorageForVillage(helper.getLevel(), center, storage)",
):
    assert marker in GAME_TESTS, f"in-world planner test must pin the measured behaviour: {marker}"
assert GAME_TESTS.count("VillageResourcePlanner.invalidate(helper.getLevel(), center);") >= 4, (
    "every fixture change must refresh the cached plan through the new invalidation hook"
)

# 9b. All GameTests share one world, so the level-wide order board must be asserted per settlement.
for marker in (
    "java.util.UUID boardVillage = VillageRegistry.villageId(helper.getLevel(), center);",
    "ownPersistedOrders == 1",
    "The board is level-wide SavedData",
    "The fixture order must be cancellable so it stops reserving materials after the check.",
    "released.freeWoodUnits() == 256 && !released.needsWood()",
):
    assert marker in GAME_TESTS, f"shared-world order board must stay settlement-scoped: {marker}"
assert "roundTrip.getList(\"orders\", Tag.TAG_COMPOUND).size() == 1" not in GAME_TESTS, (
    "a global order count couples unrelated settlements tested in the same world"
)

# 10. Gameplay code still never fabricates saplings; only the in-world fixture may place them.
gameplay_sources = "\n".join(
    path.read_text(encoding="utf-8") for path in JAVA.rglob("*.java")
    if path.name != "VillageReservationGameTests.java"
)
assert not re.search(r"new ItemStack\(Items\.[A-Z_]*SAPLING", gameplay_sources), (
    "gameplay sources must never instantiate saplings without a real input"
)
assert "REPLANT_VIRTUAL_SEED" not in gameplay_sources, "virtual sapling fallback must stay removed"

print("RESOURCE DEMAND PRECISION PASSED: reservation-aware stock, fuel/iron/sapling deficits, "
      "deficit-led miner steering, single-pass field scan, delivery-triggered refresh, paged elder summary, "
      "in-world planner measurement")