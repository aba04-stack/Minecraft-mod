#!/usr/bin/env python3
"""Transfers between a settlement storage and a worker must move real item stacks.

Rebuilding a stack from a counted removal (``new ItemStack(item, removed)``) silently destroys item
data: a worn tool arrives brand new, which is a free repair out of nothing, and enchantments, custom
names or the contents of a shulker box disappear from the save.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/abs/livingvillages"

helper = (JAVA / "VillageItemTransfers.java").read_text(encoding="utf-8")
assert "public static int moveRealItems(Container source, Container destination, Item item, int amount," in helper
assert "public static ItemStack insertReal(Container destination, ItemStack stack) {" in helper
assert "ItemStack taken = source.removeItem(slot, take);" in helper, (
    "the shared transfer must move the stack that was really stored"
)
assert "ItemStack rejected = insertReal(destination, taken);" in helper, (
    "the destination insert must keep the same stack instance, with its components"
)
assert "ItemStack stillOut = insertReal(source, rejected);" in helper, (
    "whatever the worker cannot carry must go back into the same storage as the same stack"
)
assert "new ItemStack(item," not in helper, "the shared transfer helper must never rebuild a stack"

# Consumption stays count-based on purpose: the recipe destroys the ingredient, so there is no stack
# left to keep data on. Everything that MOVES an item between containers must go through the helper.
CALL_SITES = {
    "VillageLivestock.java": ("VillageItemTransfers.moveRealItems(storage, worker.getInventory(), item, amount, worker);",),
    "VillageFarmBuilder.java": (
        "VillageItemTransfers.moveRealItems(storage, inventory, item, amount, worker);",
        "VillageItemTransfers.moveRealItems(storage, worker.getInventory(), hoeItem, 1, worker);",
    ),
    "VillageBuilder.java": ("VillageItemTransfers.moveRealItems(storage, inventory, item, amount, villager);",),
    "VillageRegistry.java": ("int moved = VillageItemTransfers.moveRealItems(from, to, item, count, null);",),
    "VillageCraftingJobs.java": (
        "VillageItemTransfers.moveRealItems(container, villager.getInventory(), missing.item(), amount, villager);",
        "int delivered = VillageItemTransfers.moveRealItems(villager.getInventory(), container,",
    ),
    "VillagerAutonomy.java": (
        "movedTotal += VillageItemTransfers.moveRealItems(storage, inventory, item, limit - movedTotal, villager);",
        "ItemStack rejected = VillageItemTransfers.insertReal(destination, taken);",
    ),
    "VillagerMining.java": (
        "ItemStack rejected = VillageItemTransfers.insertReal(villager.getInventory(), stack);",
        "if (!rejected.isEmpty()) Block.popResource(level, pos, rejected);",
    ),
    "VillagerTreeWork.java": (
        "ItemStack rejected = VillageItemTransfers.insertReal(villager.getInventory(), stack);",
    ),
    "VillagerCaveSupport.java": (
        "ItemStack taken = storage.removeItem(slot, requested);",
        "ItemStack rejected = VillageItemTransfers.insertReal(inventory, taken);",
        "ItemStack back = VillageItemTransfers.insertReal(storage, rejected);",
    ),
}
for name, required in CALL_SITES.items():
    text = (JAVA / name).read_text(encoding="utf-8")
    for line in required:
        assert line in text, f"{name} must move real stacks through VillageItemTransfers"

for name in ("VillageLivestock.java", "VillageFarmBuilder.java", "VillageBuilder.java"):
    text = (JAVA / name).read_text(encoding="utf-8")
    assert "int removed = removeItem(storage, item, amount);" not in text, (
        f"{name} still rebuilds transferred items from a count instead of moving the real stack"
    )
    assert "addItem(new ItemStack(item, removed))" not in text, (
        f"{name} loses stack components when a worker takes materials from a storage"
    )
    assert "addItem(new ItemStack(hoeItem))" not in text, (
        f"{name} loses stack components when a worker takes a tool from a storage"
    )

crafting = (JAVA / "VillageCraftingJobs.java").read_text(encoding="utf-8")
assert "int removed = removeItem(container, missing.item(), amount);" not in crafting, (
    "the crafter must not rebuild an ingredient withdrawn from storage out of a count"
)
assert "ItemStack actualOutput = new ItemStack(recipe.output(), removed);" not in crafting, (
    "the crafted output must be delivered as the real stack the worker carries"
)
assert "private static ItemStack insertStack(Container container, ItemStack offered) {" not in crafting, (
    "the private copy of the insert must be gone once the shared helper owns every transfer"
)
assert "int removed = removeItem(villager.getInventory(), ingredient.item(), ingredient.count());" in crafting, (
    "consuming a recipe ingredient stays count-based: the recipe destroys it, there is no data to keep"
)

autonomy = (JAVA / "VillagerAutonomy.java").read_text(encoding="utf-8")
assert "private static int insertInto(Container destination, ItemStack source, int limit) {" not in autonomy, (
    "the component-blind insert must be gone: it merged stacks vanilla would never merge"
)
assert "destination.setItem(slot, new ItemStack(source.getItem(), moved));" not in autonomy, (
    "depositing surplus food must not rebuild the stack and drop its components"
)
assert "if (existing.isEmpty() || existing.getItem() != source.getItem()" not in autonomy, (
    "merging by item alone ignores components; the shared insertReal compares components"
)
assert "int moved = withdrawPlantingItems(villager, container, 16);" in autonomy

for name in ("VillagerMining.java", "VillagerTreeWork.java"):
    text = (JAVA / name).read_text(encoding="utf-8")
    assert "for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++)" not in text, (
        f"{name} must not keep its own copy of the two-pass insert; the shared helper owns it"
    )

cave = (JAVA / "VillagerCaveSupport.java").read_text(encoding="utf-8")
assert "ItemStack offered = new ItemStack(Items.TORCH, requested);" not in cave, (
    "a torch restock must move the stored stack, never a rebuilt one"
)
assert "int moved = requested - remainder.getCount();" not in cave, (
    "counting what a rebuilt stack did not accept is how stack data used to be lost"
)

tests = (JAVA / "VillageReservationGameTests.java").read_text(encoding="utf-8")
assert "public static void realStackInsertsNeverMergeDifferentComponents(GameTestHelper helper) {" in tests, (
    "the component rule needs an in-world regression test, not only a static one"
)
assert "public static void minerStoresRealDropsWithoutMergingDifferentComponents(GameTestHelper helper) {" in tests, (
    "the miner's own deposit path needs an in-world regression test as well"
)
assert "public static void torchRestockKeepsTheStorageReserveTheStackNameAndReportsItsBox(GameTestHelper helper) {" in tests, (
    "the torch restock needs an in-world test for its reserve and its stack identity"
)

registry = (JAVA / "VillageRegistry.java").read_text(encoding="utf-8")
assert "insertItemIntoContainer(dests.get(0), new ItemStack(item, removed));" not in registry, (
    "the storage port must not rebuild transferred items from a count"
)
assert "if (moved > 0) VillageItemTransfers.moveRealItems(to, from, item, moved, null);" in registry, (
    "a partial port transfer must be rolled back so the port stays transactional"
)
print("ITEM IDENTITY PRESERVATION PASSED: every container transfer moves real stacks with their "
      "damage and components, mining and cave deposits included; only recipe consumption stays count-based")
