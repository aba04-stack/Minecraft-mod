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

CALL_SITES = {
    "VillageLivestock.java": ("VillageItemTransfers.moveRealItems(storage, worker.getInventory(), item, amount, worker);",),
    "VillageFarmBuilder.java": (
        "VillageItemTransfers.moveRealItems(storage, inventory, item, amount, worker);",
        "VillageItemTransfers.moveRealItems(storage, worker.getInventory(), hoeItem, 1, worker);",
    ),
    "VillageBuilder.java": ("VillageItemTransfers.moveRealItems(storage, inventory, item, amount, villager);",),
    "VillageRegistry.java": ("int moved = VillageItemTransfers.moveRealItems(from, to, item, count, null);",),
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

registry = (JAVA / "VillageRegistry.java").read_text(encoding="utf-8")
assert "insertItemIntoContainer(dests.get(0), new ItemStack(item, removed));" not in registry, (
    "the storage port must not rebuild transferred items from a count"
)
assert "if (moved > 0) VillageItemTransfers.moveRealItems(to, from, item, moved, null);" in registry, (
    "a partial port transfer must be rolled back so the port stays transactional"
)
print("ITEM IDENTITY PRESERVATION PASSED: worker transfers move real stacks with their damage and components")
