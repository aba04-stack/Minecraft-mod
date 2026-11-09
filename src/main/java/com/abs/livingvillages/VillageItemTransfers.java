package com.abs.livingvillages;

import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Moves real item stacks between a settlement storage and a worker.
 *
 * <p>Every transfer in this mod used to remove a counted number of items and then rebuild a fresh
 * stack out of the item and that count on the other side. For plain materials the two are equivalent, but
 * for anything carrying data the fresh stack silently destroys value: a worn hoe or a worn pair of
 * shears arrived brand new, which is a free repair out of nothing, and enchantments, custom names,
 * dyed colours or the contents of a shulker box were dropped from the save. These helpers move the
 * stacks themselves, so identity, damage and components survive a transfer in both directions.</p>
 *
 * <p>Nothing is created and nothing disappears: whatever the destination rejects is put back into the
 * source as the same stack, and only what the source then also rejects is dropped by the worker.</p>
 */
public final class VillageItemTransfers {
    private VillageItemTransfers() {}

    /**
     * Moves up to {@code amount} real items of {@code item} from {@code source} into {@code destination}.
     *
     * @param dropOwner worker that drops whatever neither container can hold; may be {@code null}, in
     *                  which case such a remainder stays where the source cannot take it back only if
     *                  the source itself is full - callers that pass {@code null} must have sized the
     *                  destination beforehand
     * @return how many items really moved into {@code destination}
     */
    public static int moveRealItems(Container source, Container destination, Item item, int amount,
                                    Villager dropOwner) {
        if (source == null || destination == null || item == null || item == Items.AIR || amount <= 0) return 0;
        int remaining = amount;
        int movedTotal = 0;
        boolean touched = false;
        for (int slot = 0; slot < source.getContainerSize() && remaining > 0; slot++) {
            ItemStack stored = source.getItem(slot);
            if (stored.isEmpty() || !stored.is(item)) continue;
            int take = Math.min(remaining, stored.getCount());
            ItemStack taken = source.removeItem(slot, take);
            if (taken.isEmpty()) continue;
            touched = true;
            remaining -= taken.getCount();
            int carried = taken.getCount();
            // insertReal only uses Container primitives, so the same code works for a villager's
            // SimpleContainer and for a chest, barrel or dispenser.
            ItemStack rejected = insertReal(destination, taken);
            int accepted = rejected.isEmpty() ? carried : carried - rejected.getCount();
            movedTotal += accepted;
            if (rejected.isEmpty()) continue;
            // The destination is full: the same real stack goes back, keeping damage and components.
            ItemStack stillOut = insertReal(source, rejected);
            if (!stillOut.isEmpty() && dropOwner != null) dropOwner.spawnAtLocation(stillOut);
        }
        if (touched) {
            source.setChanged();
            destination.setChanged();
        }
        return movedTotal;
    }

    /**
     * Inserts a real stack into {@code destination}, filling matching stacks before empty slots.
     *
     * @return the same stack instance, holding whatever did not fit
     */
    public static ItemStack insertReal(Container destination, ItemStack stack) {
        if (destination == null || stack == null) return stack == null ? ItemStack.EMPTY : stack;
        int before = stack.getCount();
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            for (int slot = 0; slot < destination.getContainerSize() && !stack.isEmpty(); slot++) {
                if (!destination.canPlaceItem(slot, stack)) continue;
                ItemStack existing = destination.getItem(slot);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) continue;
                    int room = Math.min(existing.getMaxStackSize(), destination.getMaxStackSize()) - existing.getCount();
                    int moved = Math.min(Math.max(0, room), stack.getCount());
                    if (moved > 0) { existing.grow(moved); stack.shrink(moved); }
                } else if (existing.isEmpty()) {
                    int moved = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), destination.getMaxStackSize()));
                    if (moved > 0) destination.setItem(slot, stack.split(moved));
                }
            }
        }
        if (stack.getCount() != before) destination.setChanged();
        return stack;
    }
}
