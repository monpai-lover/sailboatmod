package com.monpai.sailboatmod.compat;

import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** Container slot layout stays stable while each legacy stack is data-fixed. */
public final class InventoryNbt {
    private InventoryNbt() {}

    public static void loadAllItems(CompoundTag tag, List<ItemStack> slots, HolderLookup.Provider provider) {
        var items = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            var item = items.getCompound(i);
            int slot = item.getByte("Slot") & 255;
            if (slot < slots.size()) slots.set(slot, ItemStackNbt.read(provider, item));
        }
    }

    /** Adapt the ItemStackHandler format before its registry-aware deserializer runs. */
    public static CompoundTag upgradeHandler(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag upgraded = tag.copy();
        var items = upgraded.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            var item = items.getCompound(i);
            int slot = item.getInt("Slot");
            if (!item.contains("Count", Tag.TAG_ANY_NUMERIC)) continue;
            CompoundTag replacement = ItemStackNbt.save(provider, ItemStackNbt.read(provider, item), new CompoundTag());
            replacement.putInt("Slot", slot);
            items.set(i, replacement);
        }
        return upgraded;
    }
}
