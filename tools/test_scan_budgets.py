#!/usr/bin/env python3
"""Regression contract for the world-scan performance work (0.17.0 perf cycle).

Pins the pieces that keep settlement scanning cheap and honest:

* ring-ordered scans with an exact early exit, so a nearby answer costs a few block reads;
* bounded caches for answers that are expensive to disprove (workshop presence, forest presence);
* one shared pass where two questions used to walk the same volume twice (tools, crop fields);
* a refusal cache for resident-group membership, which costs two wide entity queries per test;
* a per-tick settlement scan quota, so caches expiring together cannot stack into one spike;
* deterministic counting in ``VillageScanStats`` and GameTests that assert those counts instead of
  wall-clock milliseconds, which would be flaky in CI.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"
STATS = (JAVA / "VillageScanStats.java").read_text(encoding="utf-8")
INDUSTRY = (JAVA / "VillageIndustry.java").read_text(encoding="utf-8")
AREA = (JAVA / "VillageArea.java").read_text(encoding="utf-8")
REGISTRY = (JAVA / "VillageRegistry.java").read_text(encoding="utf-8")
PLANNER = (JAVA / "VillageResourcePlanner.java").read_text(encoding="utf-8")
BUDGET = (JAVA / "VillageTickBudget.java").read_text(encoding="utf-8")
ROLES = (JAVA / "VillagerRoles.java").read_text(encoding="utf-8")
TREE_WORK = (JAVA / "VillagerTreeWork.java").read_text(encoding="utf-8")
GAME_TESTS = (JAVA / "VillageReservationGameTests.java").read_text(encoding="utf-8")

# 1. Deterministic counters exist, are cheap (no per-block timing) and reset even on failure.
for marker in (
    "public static void recordScan(int scannedPositions)",
    "public static void recordMembershipTest()",
    "public static void recordEntityAreaScan()",
    "public static ScanReport measure(Runnable action)",
    "public record ScanReport(long blockPositions, long membershipTests, long entityAreaScans)",
    "} finally {",
    "positions = 0L;",
):
    assert marker in STATS, f"VillageScanStats must keep {marker!r}"
assert "System.nanoTime" not in STATS, "scan accounting must count work, not measure milliseconds"
assert "if (depth > 0)" in STATS, "nested measurements must not be attributed twice"

# 2. Ring-ordered scanning with an exact early exit, shared by every subsystem that asks the question.
RINGS = (JAVA / "VillageScanRings.java").read_text(encoding="utf-8")
CRAFTING = (JAVA / "VillageCraftingJobs.java").read_text(encoding="utf-8")
for marker in (
    "static boolean walk(int radius, Visitor visitor)",
    "interface Visitor",
    "default boolean beginRing(int ring)",
    "boolean visit(int dx, int dz);",
    "return best[0] != null && (double) ring * ring > bestDistance[0];",
    "for (int dy = -downReach; dy <= upReach; dy++)",
    "VillageScanStats.recordScan(scanned[0]);",
    "if (VillageRegistry.isWorkBlockState(state)",
):
    assert marker in RINGS, f"VillageScanRings must keep {marker!r}"
# The ring walk must cover exactly the same columns as the nested loops it replaced.
assert "for (int dx = -ring; dx <= ring; dx++)" in RINGS, "rings must cover the full perimeter rows"
assert "for (int dz = -ring + 1; dz <= ring - 1; dz++)" in RINGS, "rings must cover the side columns once"
# Vertical reach must not shrink while optimizing: industry keeps -8..8, crafting jobs -6..6.
assert "VillageScanRings.hasOwned(level, center, block, radius, 8, 8)" in INDUSTRY, (
    "workstation presence must keep the dy -8..8 reach"
)
assert "VillageScanRings.nearestOwned(level, center, block, radius, 8, 8)" in INDUSTRY, (
    "nearest workstation must keep the dy -8..8 reach"
)
assert "VillageScanRings.nearestOwned(level, center, block, radius, 6, 6)" in CRAFTING, (
    "an idle craftsman must keep the dy -6..6 reach"
)
assert "for (int dx = -radius; dx <= radius; dx++)" not in CRAFTING.split(
    "private static BlockPos findNearestBlock", 1)[1][:600], (
    "crafting jobs must not keep its own copy of the full-box walk"
)
assert "for (int dy = -4; dy <= 4; dy++)" in INDUSTRY, "storage scans must still cover dy -4..4"
# Early exit must stay exact: the found match ends the scan instead of approximating it.
has_owned = RINGS.split("static boolean hasOwned", 1)[1].split("static BlockPos nearestOwned", 1)[0]
assert "// An existence check stops at the first owned match" in has_owned and "return true;" in has_owned, (
    "hasOwned must stop at the first owned match"
)

# 3. Bounded caches for answers that are expensive to disprove.
for marker in (
    "private static final long WORK_BLOCK_CACHE_TICKS = 100L;",
    "private static final int MAX_CACHED_SETTLEMENTS = 64;",
    "CRAFTING_TABLE_CACHE",
    "FURNACE_CACHE",
    "private record CachedPresence(boolean present, long expiresAtTick) {}",
    "private static boolean cachedPresence(ServerLevel level, BlockPos center, Block block,",
    "public static void invalidateWorkBlocks(ServerLevel level, BlockPos center)",
    "if (villageId == null) return hasBlock(level, center, block, radius);",
):
    assert marker in INDUSTRY, f"workstation presence cache must keep {marker!r}"
# Placing a workstation must invalidate the cache so quotas react at once.
placement = INDUSTRY.split("Blocks.CRAFTING_TABLE.defaultBlockState()", 1)[1][:600]
assert "invalidateWorkBlocks(level, center)" in placement, (
    "building a workshop must drop the cached presence answer"
)
furnace_placement = INDUSTRY.split("Blocks.FURNACE.defaultBlockState()", 1)[1][:300]
assert "invalidateWorkBlocks(level, center)" in furnace_placement, (
    "building a furnace must drop the cached presence answer"
)

# 4. Two tool questions share one storage pass.
for marker in (
    "public record AvailableTools(int pickaxes, int swords) {}",
    "public static AvailableTools countAvailableTools(ServerLevel level, BlockPos center)",
    "return countAvailableTools(level, center).pickaxes();",
    "return countAvailableTools(level, center).swords();",
    "private static boolean hasStorageItemNear(ServerLevel level, BlockPos origin, int radius,",
):
    assert marker in INDUSTRY, f"tool counting must keep {marker!r}"
assert "private static int countTools(ServerLevel level" not in INDUSTRY, (
    "the double-pass tool counter must stay removed"
)
assert "VillageIndustry.countAvailableTools(level, center)" in ROLES, (
    "role assignment must resolve both tool counts in one pass"
)
assert "VillageIndustry.countAvailablePickaxes(level, center)" not in ROLES, (
    "role assignment must not walk the storage box a second time for pickaxes"
)
assert "VillageIndustry.countAvailableSwords(level, center)" not in ROLES, (
    "role assignment must not walk the storage box a second time for swords"
)

# 5. Resident-group membership: cheaper ordering keys, batch questions, counted cost.
for marker in (
    "private static <T> Comparator<T> byStableId(List<T> items, Function<T, UUID> idOf)",
    "candidates.sort(byStableId(candidates, Villager::getUUID));",
    "component.sort(byStableId(component, resident -> resident.villager().getUUID()));",
    "public static int countInResidentGroup(ServerLevel level, BlockPos groupCenter, List<BlockPos> locations)",
    "VillageScanStats.recordEntityAreaScan();",
    "VillageScanStats.recordMembershipTest();",
):
    assert marker in AREA, f"VillageArea must keep {marker!r}"
assert "Comparator.comparing(v -> v.getUUID().toString())" not in AREA, (
    "sorting must not allocate two UUID strings per comparison"
)
for marker in (
    "private final Set<Long> claimRefusals = new HashSet<>();",
    "long refusalKey = claimRefusalKey(key, groupCenter, assetKind, villageId);",
    "if (cache.claimRefusals.contains(refusalKey)) return false;",
    "cache.claimRefusals.add(refusalKey);",
    "private static long claimRefusalKey(long locationKey, BlockPos groupCenter, long assetKind, UUID villageId)",
):
    assert marker in REGISTRY, f"the claim refusal cache must keep {marker!r}"
refusal_region = REGISTRY.split("private static boolean claimAsset", 1)[1].split("private static long claimRefusalKey", 1)[0]
assert "long assetKind" in refusal_region[:300], (
    "asset kinds must be part of the refusal key so one ownership map cannot suppress another"
)
assert refusal_region.index("claimRefusals.contains") < refusal_region.index("VillageArea.isBlockInResidentGroup"), (
    "a remembered refusal must be checked before paying for another membership test"
)
assert refusal_region.index("VillageArea.isBlockInResidentGroup") < refusal_region.index("claimRefusals.add"), (
    "only an actual refusal may be remembered, so an asset replaced in the same tick stays claimable"
)
# Membership cost is counted once, inside the shared group check, never a second time by its callers.
assert "VillageScanStats.recordMembershipTest()" not in REGISTRY, (
    "callers must not count a membership test twice; VillageArea.isBlockInResidentGroup owns the counter"
)

# 6. Planner: longer tree TTL, per-tick refresh quota, shared invalidation, batched crop membership.
for marker in (
    "private static final long TREE_CACHE_TICKS = 200L;",
    "private static final int MAX_REFRESHES_PER_TICK = 2;",
    "VillageTickBudget.tryConsumeSettlementScan(level, MAX_REFRESHES_PER_TICK)",
    "boolean treesReused = cached != null && now < cached.treesExpiresAtTick();",
    "treesReused ? cached.treesExpiresAtTick() : now + TREE_CACHE_TICKS",
    "treeSource != null ? treeSource.treesAvailable() : hasHarvestableTree(level, center)",
    "VillageIndustry.invalidateWorkBlocks(level, center);",
    "VillageArea.countInResidentGroup(level, center, matureCandidates)",
    "VillageArea.countInResidentGroup(level, center, plantableCandidates)",
    "private record CachedPlan(VillageResourceDemandModel.Plan plan, long expiresAtTick,",
):
    assert marker in PLANNER, f"the planner must keep {marker!r}"
quota_region = PLANNER.split("tryConsumeSettlementScan", 1)[1][:400]
assert "return cached.plan();" in quota_region, (
    "a settlement that misses the quota must serve its previous plan, not rescan anyway"
)
assert "VillageArea.isBlockInResidentGroup" not in PLANNER, (
    "crop tiles must be resolved in one batch instead of one membership test per tile"
)

# 7. The quota lives in the tick budget and resets with the tick.
for marker in (
    "public static boolean tryConsumeSettlementScan(ServerLevel level, int maxPerTick)",
    "state.settlementScansUsedThisTick = 0;",
    "TickBudgetModel.canConsumeQuota(state.settlementScansUsedThisTick, maxPerTick)",
):
    assert marker in BUDGET, f"VillageTickBudget must keep {marker!r}"
reset_region = BUDGET.split("private static LevelTickState stateFor", 1)[1][:400]
assert "settlementScansUsedThisTick = 0" in reset_region, (
    "the settlement scan quota must reset when the game time advances"
)

# 8. Real logging refreshes the forest answer instead of waiting out the longer TTL.
assert "VillageResourcePlanner.invalidate(level, target);" in TREE_WORK, (
    "finishing a trunk must invalidate the cached forest answer"
)

# 9. GameTests assert counts, never milliseconds.
assert "public static long totalSettlementScans(ServerLevel level)" in BUDGET, (
    "the settlement scan quota must stay observable, so a GameTest can prove it does not starve a village"
)

for marker in (
    'batch = "scan_budget_workshop"',
    "public static void nearbyWorkshopIsFoundWithoutWalkingTheWholeVillageBox(GameTestHelper helper)",
    'batch = "scan_budget_ownership"',
    "public static void assetOwnershipDecisionsAreNotRepeatedWithinATick(GameTestHelper helper)",
    "long fullBox = (2L * radius + 1) * (2L * radius + 1) * 17L;",
    "nearby.blockPositions() < fullBox / 8",
    "absent.blockPositions() == fullBox",
    "cached.blockPositions() == 0L",
    "repeatedForeignQuestion.membershipTests() == 0",
    "firstForeignQuestion.membershipTests() == 1",
    'batch = "scan_budget_quota"',
    "public static void expiredSettlementPlanRefreshesOnceAndSeesNewStock(GameTestHelper helper)",
    "helper.runAfterDelay(120, () -> {",
    "VillageTickBudget.totalSettlementScans(level)",
    "after - before <= 1",
    "refreshedWood == 128",
):
    assert marker in GAME_TESTS, f"the scan budget GameTests must keep {marker!r}"
assert "System.nanoTime" not in GAME_TESTS, (
    "GameTests must not assert on wall-clock time; CI timings are not reproducible"
)
assert len(re.findall(r"VillageScanStats\.measure\(", GAME_TESTS)) >= 6, (
    "both budget tests must measure each phase separately"
)

# 10. Storage searches: bounded ring walk with an early exit, one path per winning storage.
LOGISTICS = (JAVA / "VillageLogistics.java").read_text(encoding="utf-8")
LIVESTOCK = (JAVA / "VillageLivestock.java").read_text(encoding="utf-8")
assert "static BlockPos nearestInBox(BlockPos origin, BlockPos boundsCenter, int boundsRadius," in RINGS, (
    "the bounded ring walk must stay available to every storage search"
)
assert "if (Math.max(Math.abs(x - boundsCenter.getX()), Math.abs(z - boundsCenter.getZ())) > boundsRadius) {" in RINGS, (
    "the candidate set must stay exactly the box around the ownership center, never a wider ring"
)
assert "int reach = boundsRadius + Math.max(Math.abs(origin.getX() - boundsCenter.getX())," in RINGS, (
    "rings must stay wide enough to cover the whole box when the worker stands outside it"
)
assert "VillageScanRings.nearestInBox(origin, origin, radius, 4, 4, pos -> {" in LOGISTICS, (
    "the courier delivery search must stop at the first usable storage"
)
assert "storagePositions.sort(" not in LOGISTICS, (
    "a delivery target must not require collecting and sorting every storage of the box first"
)
assert "static BlockPos findCourierStorage(Villager villager, ServerLevel level)" in LOGISTICS, (
    "the delivery search needs a test seam: a GameTest cannot fire an entity tick event"
)
assert LIVESTOCK.count("VillageScanRings.nearestInBox(worker.blockPosition(), center, storageRadius(), 6, 6,") == 2, (
    "both herder storage searches (fetch item, deliver wool) must walk rings instead of the full box"
)
assert "refs.sort(Comparator.comparingDouble(ref -> worker.distanceToSqr(Vec3.atCenterOf(ref.pos()))));" not in LIVESTOCK, (
    "the herder must not collect and sort every storage of the box to pick one"
)
# Pathfinding dominates a stand search: order the safe sides by distance and stop at the reachable one.
assert "stands.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(Vec3.atCenterOf(pos))));" in LIVESTOCK
assert "            if (path != null && path.canReach()) return stand;" in LIVESTOCK, (
    "the nearest reachable stand must end the path search instead of building a path for all four sides"
)
assert "if (isSafeStand(level, stand)) stands.add(stand.immutable());" in LIVESTOCK

# 11. Registry storage lookups: the whole-box snapshot walks rings and reports; nearest lookups exit early.
REGISTRY = (JAVA / "VillageRegistry.java").read_text(encoding="utf-8")
assert "static List<BlockPos> collectInBox(BlockPos center, int radius, int downReach, int upReach," in RINGS, (
    "a snapshot that needs every storage must still walk rings and report its cost"
)
assert "VillageScanRings.collectInBox(center, storageLookupRadius(), 4, 4," in REGISTRY, (
    "the settlement storage snapshot must use the shared ring walk instead of its own nested loops"
)
assert "BlockPos.betweenClosed(center.offset(-radius, -4, -radius), center.offset(radius, 4, radius))" not in REGISTRY, (
    "an unreported full-box walk may not come back into the registry"
)
assert "positions.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));" not in REGISTRY, (
    "ring order already yields nearest first; collecting and sorting all storages is wasted work"
)
assert "VillageScanRings.nearestInBox(center, center, storageLookupRadius(), 4, 4, pos -> {" in REGISTRY, (
    "a nearest-storage lookup must stop at the first usable storage"
)
assert REGISTRY.count("nearestAvailableOwnedStorage(level, center,") == 2, (
    "both nearest-storage lookups (an item amount, a smelting pair) must exit early"
)
assert "for (Container container : findAvailableOwnedStorages(level, center)) {\n            if (countItemInContainer(container, item) >= minCount) {" not in REGISTRY, (
    "the item lookup must not walk every storage of the box to pick the first usable one"
)
assert "for (Container container : findAvailableOwnedStorages(level, center)) {" in REGISTRY, (
    "the stock snapshot legitimately needs every storage and must keep walking them all"
)
assert "// Ownership is re-checked inside shearManagedSheep" in LIVESTOCK, (
    "a shearing attempt must not resolve the settlement identity twice"
)

# 12. A decision that is already made must not sit behind entity queries, world geometry and a raytrace.
THROTTLE = "if (now - sheepData.getLong(SHEEP_LAST_LURE_TICK) < 20L) return;"
ENTITY_QUERY = "AABB area = sheep.getBoundingBox().inflate(10.0D, 4.0D, 10.0D);"
assert THROTTLE in LIVESTOCK, "the sheep lure throttle must stay, it is what bounds the work per animal"
assert LIVESTOCK.index(THROTTLE) < LIVESTOCK.index(ENTITY_QUERY), (
    "the 20-tick lure throttle must come before the entity query, the pen geometry and the raytrace"
)
assert "if (now - lastLure < 20L) return;" not in LIVESTOCK, (
    "the late duplicate of the same throttle must be gone"
)
assert LIVESTOCK.index("if (isSafeStand(level, stand)) stands.add(stand.immutable());") > 0

# 13. Miner and cave support: one reservation lookup per search, reported boxes, one read per position.
MINING = (JAVA / "VillagerMining.java").read_text(encoding="utf-8")
CAVE = (JAVA / "VillagerCaveSupport.java").read_text(encoding="utf-8")

assert MINING.count("List<BlockPos> reserved = reservedOreTargets(level, villager);") == 2, (
    "both miner searches must resolve the other miners' targets once per search"
)
assert MINING.count("level.getEntitiesOfClass(Villager.class, area,") == 1, (
    "the entity area query belongs to the shared lookup, never to a per-candidate question"
)
assert "VillageScanStats.recordEntityAreaScan();" in MINING, (
    "an entity area scan over a village-sized box must be counted like every other scan"
)
assert "isReservedByAnotherMiner(reserved, pos)" in MINING and "!isReservedByAnotherMiner(level" not in MINING, (
    "the reservation answer must come from the list resolved before the loop"
)
SEARCH_START = MINING.index("static BlockPos findNearestOre")
SEARCH = MINING[SEARCH_START:MINING.index("findNearestQuarryBlock", SEARCH_START)]
assert (SEARCH.index("isSafeMineTarget(level, villager, pos, state)")
        < SEARCH.index("!isReservedByAnotherMiner(reserved, pos)")
        < SEARCH.index("hasExposedFace(level, pos)")
        < SEARCH.index("findWorkPosition(level, villager, pos)")), (
    "cheapest tests first: the ray-traced work position may only run for a candidate that survived "
    "the state, safety, reservation and exposure checks"
)
assert MINING.count("VillageScanStats.recordScan(scanned);") == 2, (
    "both miner searches run on their own retry timer and must report what they read"
)
assert "if (!visited.add(pos.asLong())) continue;" in MINING, (
    "the vein snapshot must mark a position before reading it, not only when it matches the vein"
)
assert "|| !isOre(state) || !visited.add(pos.asLong())) continue;" not in MINING, (
    "marking only matching positions lets every neighbour re-read the same non-vein block"
)
assert "needsCobblestone" not in MINING and "isCountedQuarryMaterial" not in MINING, (
    "dead code that walks a 33x33x9 box unreported must not come back"
)
assert "VillageScanRings.collectInBox(origin, radius, 4, 4," in MINING, (
    "the pickaxe search must use the shared ring walk and report its box"
)
assert "VillageScanRings.collectInBox(center, radius, 4, 4," in CAVE, (
    "the torch restock must use the shared ring walk and report its box"
)

print("SCAN BUDGETS PASSED: ring-ordered early-exit scans, bounded presence caches, shared tool and "
      "crop passes, membership refusal cache, per-tick settlement scan quota, bounded storage searches, "
      "ring-ordered registry storage lookups, cheap throttles before expensive reads, "
      "one miner reservation lookup per search, deterministic GameTest budgets")
