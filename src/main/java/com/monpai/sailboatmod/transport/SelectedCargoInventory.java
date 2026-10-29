package com.monpai.sailboatmod.transport;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.Collections;

/** A view of explicitly selected real inventories; it never owns a second copy of stored cargo. */
public final class SelectedCargoInventory {
    private record Slot(IItemHandler handler, int index) { }
    private record Insertion(Slot slot, ItemStack stack) { }
    private record Snapshot(Slot slot, ItemStack before) { }
    private final List<Slot> slots;

    public SelectedCargoInventory(List<IItemHandler> handlers) {
        List<Slot> selected = new ArrayList<>();
        Set<IItemHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (handlers != null) {
            for (IItemHandler handler : handlers) {
                if (handler != null && !seen.add(handler)) continue;
                if (handler == null || handler.getSlots() < 0 || handler.getSlots() > 4096
                        || selected.size() + handler.getSlots() > 4096) throw new IllegalArgumentException("Invalid selected cargo inventory");
                for (int slot = 0; slot < handler.getSlots(); slot++) selected.add(new Slot(handler, slot));
            }
        }
        slots = List.copyOf(selected);
    }

    public boolean canLoad(List<ItemStack> cargo) {
        try { return plan(cargo) != null; }
        catch (RuntimeException exception) { return false; }
    }

    public CargoLoadReceipt load(List<ItemStack> cargo) {
        List<Snapshot> accepted = new ArrayList<>();
        try {
            List<Insertion> plan = plan(cargo);
            if (plan == null) return null;
            for (Insertion insertion : plan) {
                // Snapshot before entering a third-party insertion, including one that mutates then throws.
                accepted.add(new Snapshot(insertion.slot, insertion.slot.handler.getStackInSlot(insertion.slot.index).copy()));
                ItemStack remainder = insertion.slot.handler.insertItem(insertion.slot.index, insertion.stack.copy(), false);
                if (!remainder.isEmpty()) {
                    rollback(accepted);
                    return null;
                }
            }
        } catch (RuntimeException exception) {
            rollback(accepted);
            return null;
        }
        return new CargoLoadReceipt() {
            private boolean closed;
            @Override public void commit() { closed = true; }
            @Override public void rollback() {
                if (closed) return;
                closed = true;
                SelectedCargoInventory.rollback(accepted);
            }
        };
    }

    private List<Insertion> plan(List<ItemStack> cargo) {
        if (cargo == null || cargo.size() > 4096) return null;
        // Insert-only/forwarding capabilities cannot promise atomic dispatch rollback. Bind the real modifiable hold.
        if (slots.stream().anyMatch(slot -> !(slot.handler instanceof IItemHandlerModifiable))) return null;
        List<ItemStack> simulated = new ArrayList<>(slots.size());
        for (Slot slot : slots) simulated.add(slot.handler.getStackInSlot(slot.index).copy());
        List<Insertion> insertions = new ArrayList<>();
        for (ItemStack request : cargo) {
            if (request == null) return null;
            if (request.isEmpty()) continue;
            ItemStack remaining = request.copy();
            for (int i = 0; i < slots.size() && !remaining.isEmpty(); i++) {
                Slot slot = slots.get(i);
                ItemStack existing = simulated.get(i);
                if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, remaining)) continue;
                if (!slot.handler.isItemValid(slot.index, remaining)) continue;
                int room = Math.min(slot.handler.getSlotLimit(slot.index), remaining.getMaxStackSize()) - existing.getCount();
                if (room <= 0) continue;
                ItemStack offered = remaining.copyWithCount(Math.min(room, remaining.getCount()));
                ItemStack remainder = slot.handler.insertItem(slot.index, offered.copy(), true);
                int count = offered.getCount() - remainder.getCount();
                if (count <= 0 || count > offered.getCount()) continue;
                ItemStack added = offered.copyWithCount(count);
                insertions.add(new Insertion(slot, added));
                if (existing.isEmpty()) simulated.set(i, added.copy()); else existing.grow(count);
                remaining.shrink(count);
            }
            if (!remaining.isEmpty()) return null;
        }
        return insertions;
    }

    private static void rollback(List<Snapshot> insertions) {
        for (int i = insertions.size() - 1; i >= 0; i--) {
            Snapshot insertion = insertions.get(i);
            ((IItemHandlerModifiable) insertion.slot.handler).setStackInSlot(insertion.slot.index, insertion.before.copy());
        }
    }

    public List<ItemStack> contents() {
        List<ItemStack> result = new ArrayList<>();
        for (Slot slot : slots) {
            ItemStack stack = slot.handler.getStackInSlot(slot.index);
            if (!stack.isEmpty()) result.add(stack.copy());
        }
        return List.copyOf(result);
    }

    public List<ItemStack> drain() {
        List<ItemStack> result = new ArrayList<>();
        for (Slot slot : slots) {
            ItemStack taken = slot.handler.extractItem(slot.index, Integer.MAX_VALUE, false);
            if (!taken.isEmpty()) result.add(taken);
        }
        return result;
    }
}
