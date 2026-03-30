package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.NonNullList;

import java.util.Locale;

public record NationTreasuryRecord(
        String nationId,
        long currencyBalance,
        NonNullList<ItemStack> items
) {
    public static final int TREASURY_SLOTS = 54;

    public NationTreasuryRecord {
        nationId = nationId == null ? "" : nationId.trim().toLowerCase(Locale.ROOT);
        if (currencyBalance < 0L) currencyBalance = 0L;
        if (items == null) items = NonNullList.withSize(TREASURY_SLOTS, ItemStack.EMPTY);
    }

    public static NationTreasuryRecord empty(String nationId) {
        return new NationTreasuryRecord(nationId, 0L, NonNullList.withSize(TREASURY_SLOTS, ItemStack.EMPTY));
    }

    public NationTreasuryRecord withBalance(long newBalance) {
        return new NationTreasuryRecord(nationId, Math.max(0L, newBalance), items);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("NationId", nationId);
        tag.putLong("CurrencyBalance", currencyBalance);
        ListTag itemList = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putInt("Slot", i);
                items.get(i).save(itemTag);
                itemList.add(itemTag);
            }
        }
        tag.put("Items", itemList);
        return tag;
    }

    public static NationTreasuryRecord load(CompoundTag tag) {
        String nationId = tag.getString("NationId");
        long currencyBalance = tag.getLong("CurrencyBalance");
        NonNullList<ItemStack> items = NonNullList.withSize(TREASURY_SLOTS, ItemStack.EMPTY);
        ListTag itemList = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < itemList.size(); i++) {
            CompoundTag itemTag = itemList.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot >= 0 && slot < TREASURY_SLOTS) {
                items.set(slot, ItemStack.of(itemTag));
            }
        }
        return new NationTreasuryRecord(nationId, currencyBalance, items);
    }
}
