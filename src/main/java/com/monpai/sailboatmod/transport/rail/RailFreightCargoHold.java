package com.monpai.sailboatmod.transport.rail;

import java.util.Objects;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** Real mounted storage with an insertion restriction for slots Create can consume as train fuel. */
public final class RailFreightCargoHold implements IItemHandlerModifiable {
    private final IItemHandlerModifiable storage;
    private final boolean fuelAccessible;

    public RailFreightCargoHold(IItemHandlerModifiable storage, boolean fuelAccessible) {
        this.storage = Objects.requireNonNull(storage);
        this.fuelAccessible = fuelAccessible;
    }

    public boolean fuelSafe() { return !fuelAccessible; }
    @Override public int getSlots() { return storage.getSlots(); }
    @Override public ItemStack getStackInSlot(int slot) { return storage.getStackInSlot(slot); }
    @Override public int getSlotLimit(int slot) { return storage.getSlotLimit(slot); }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return (!fuelAccessible || stack.getBurnTime(null) <= 0) && storage.isItemValid(slot, stack);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return isItemValid(slot, stack) ? storage.insertItem(slot, stack, simulate) : stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return storage.extractItem(slot, amount, simulate);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        // Only used to restore the physical contents from before a failed transaction.
        storage.setStackInSlot(slot, stack);
    }
}
