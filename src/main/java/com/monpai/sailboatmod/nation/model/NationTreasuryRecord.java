package com.monpai.sailboatmod.nation.model;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record NationTreasuryRecord(
        String nationId,
        long currencyBalance,
        NonNullList<ItemStack> items,
        String[] itemDepositors,
        int salesTaxBasisPoints,
        int importTariffBasisPoints,
        int recentTradeCount,
        long lastTaxAdjustTime
) {
    public static final int TREASURY_SLOTS = 54;
    public static final int MAX_SALES_TAX_BP = 3000;
    public static final int MAX_IMPORT_TARIFF_BP = 5000;

    public NationTreasuryRecord {
        nationId = nationId == null ? "" : nationId.trim().toLowerCase(Locale.ROOT);
        if (currencyBalance < 0L) currencyBalance = 0L;
        items = normalizeItems(items);
        itemDepositors = normalizeDepositors(itemDepositors, items.size());
        salesTaxBasisPoints = Math.max(0, Math.min(MAX_SALES_TAX_BP, salesTaxBasisPoints));
        importTariffBasisPoints = Math.max(0, Math.min(MAX_IMPORT_TARIFF_BP, importTariffBasisPoints));
        if (recentTradeCount < 0) recentTradeCount = 0;
        if (lastTaxAdjustTime < 0L) lastTaxAdjustTime = 0L;
    }

    public static NationTreasuryRecord empty(String nationId) {
        return new NationTreasuryRecord(
                nationId,
                0L,
                NonNullList.withSize(TREASURY_SLOTS, ItemStack.EMPTY),
                new String[TREASURY_SLOTS],
                500,
                1000,
                0,
                0L
        );
    }

    public NationTreasuryRecord withBalance(long newBalance) {
        return new NationTreasuryRecord(nationId, Math.max(0L, newBalance), items, itemDepositors, salesTaxBasisPoints, importTariffBasisPoints, recentTradeCount, lastTaxAdjustTime);
    }

    public NationTreasuryRecord withSalesTax(int basisPoints) {
        return new NationTreasuryRecord(nationId, currencyBalance, items, itemDepositors, basisPoints, importTariffBasisPoints, recentTradeCount, lastTaxAdjustTime);
    }

    public NationTreasuryRecord withImportTariff(int basisPoints) {
        return new NationTreasuryRecord(nationId, currencyBalance, items, itemDepositors, salesTaxBasisPoints, basisPoints, recentTradeCount, lastTaxAdjustTime);
    }

    public NationTreasuryRecord withTradeRecorded() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTaxAdjustTime;
        if (elapsed > 86_400_000L) {
            return new NationTreasuryRecord(nationId, currencyBalance, items, itemDepositors, salesTaxBasisPoints, importTariffBasisPoints, 1, now);
        }
        return new NationTreasuryRecord(nationId, currencyBalance, items, itemDepositors, salesTaxBasisPoints, importTariffBasisPoints, recentTradeCount + 1, lastTaxAdjustTime);
    }

    public int visibleSlotCount() {
        return items.size();
    }

    public long totalItemUnits() {
        long total = 0L;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public int addItem(ItemStack incoming, String depositorName) {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }
        int deposited = incoming.getCount();
        ItemStack remaining = incoming.copy();
        for (int i = 0; i < items.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, remaining) && slot.getCount() < slot.getMaxStackSize()) {
                int add = Math.min(slot.getMaxStackSize() - slot.getCount(), remaining.getCount());
                slot.grow(add);
                remaining.shrink(add);
                if (itemDepositors[i] == null || itemDepositors[i].isBlank()) {
                    itemDepositors[i] = depositorName;
                }
            }
        }
        while (!remaining.isEmpty()) {
            int add = Math.min(remaining.getMaxStackSize(), remaining.getCount());
            ItemStack stored = remaining.copy();
            stored.setCount(add);
            remaining.shrink(add);
            appendSlot(stored, depositorName);
        }
        compactTrailingEmptySlots();
        return deposited;
    }

    public List<ItemStack> removeMatching(ItemStack template, int amount) {
        List<ItemStack> removed = new ArrayList<>();
        if (template == null || template.isEmpty() || amount <= 0) {
            return removed;
        }
        int remaining = amount;
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack slot = items.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameTags(slot, template)) {
                continue;
            }
            int takeFromSlot = Math.min(remaining, slot.getCount());
            remaining -= takeFromSlot;
            int take = takeFromSlot;
            while (take > 0) {
                int split = Math.min(template.getMaxStackSize(), take);
                ItemStack extracted = slot.copy();
                extracted.setCount(split);
                removed.add(extracted);
                take -= split;
            }
            slot.shrink(takeFromSlot);
            if (slot.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
                itemDepositors[i] = null;
            }
        }
        compactTrailingEmptySlots();
        return removed;
    }

    public String getDepositor(int slot) {
        if (slot < 0 || slot >= itemDepositors.length || itemDepositors[slot] == null) return "";
        return itemDepositors[slot];
    }

    public void setDepositor(int slot, String name) {
        if (slot >= 0 && slot < itemDepositors.length) itemDepositors[slot] = name;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("NationId", nationId);
        tag.putLong("CurrencyBalance", currencyBalance);
        tag.putInt("SalesTaxBP", salesTaxBasisPoints);
        tag.putInt("ImportTariffBP", importTariffBasisPoints);
        tag.putInt("RecentTradeCount", recentTradeCount);
        tag.putLong("LastTaxAdjustTime", lastTaxAdjustTime);
        ListTag itemList = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putInt("Slot", i);
                items.get(i).save(itemTag);
                if (i < itemDepositors.length && itemDepositors[i] != null && !itemDepositors[i].isBlank()) {
                    itemTag.putString("Depositor", itemDepositors[i]);
                }
                itemList.add(itemTag);
            }
        }
        tag.put("Items", itemList);
        return tag;
    }

    public static NationTreasuryRecord load(CompoundTag tag) {
        String nationId = tag.getString("NationId");
        long currencyBalance = tag.getLong("CurrencyBalance");
        int salesTaxBP = tag.contains("SalesTaxBP") ? tag.getInt("SalesTaxBP") : 500;
        int importTariffBP = tag.contains("ImportTariffBP") ? tag.getInt("ImportTariffBP") : 1000;
        int recentTradeCount = tag.getInt("RecentTradeCount");
        long lastTaxAdjustTime = tag.getLong("LastTaxAdjustTime");
        ListTag itemList = tag.getList("Items", Tag.TAG_COMPOUND);

        int maxSlot = TREASURY_SLOTS - 1;
        for (int i = 0; i < itemList.size(); i++) {
            maxSlot = Math.max(maxSlot, itemList.getCompound(i).getInt("Slot"));
        }

        NonNullList<ItemStack> items = NonNullList.withSize(maxSlot + 1, ItemStack.EMPTY);
        String[] depositors = new String[maxSlot + 1];
        for (int i = 0; i < itemList.size(); i++) {
            CompoundTag itemTag = itemList.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot >= 0 && slot < items.size()) {
                items.set(slot, ItemStack.of(itemTag));
                if (itemTag.contains("Depositor")) {
                    depositors[slot] = itemTag.getString("Depositor");
                }
            }
        }
        return new NationTreasuryRecord(nationId, currencyBalance, items, depositors, salesTaxBP, importTariffBP, recentTradeCount, lastTaxAdjustTime);
    }

    private void appendSlot(ItemStack stack, String depositorName) {
        items.add(stack);
        int index = items.size() - 1;
        if (index >= 0 && index < itemDepositors.length) {
            itemDepositors[index] = depositorName;
        }
    }

    private void compactTrailingEmptySlots() {
        int minSize = TREASURY_SLOTS;
        int lastUsed = -1;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) {
                lastUsed = i;
            }
        }
        int targetSize = Math.max(minSize, lastUsed + 1);
        while (items.size() > targetSize) {
            items.remove(items.size() - 1);
        }
    }

    private static NonNullList<ItemStack> normalizeItems(NonNullList<ItemStack> source) {
        if (source == null || source.isEmpty()) {
            return NonNullList.withSize(TREASURY_SLOTS, ItemStack.EMPTY);
        }
        NonNullList<ItemStack> normalized = NonNullList.withSize(Math.max(TREASURY_SLOTS, source.size()), ItemStack.EMPTY);
        for (int i = 0; i < source.size(); i++) {
            normalized.set(i, source.get(i) == null ? ItemStack.EMPTY : source.get(i));
        }
        return normalized;
    }

    private static String[] normalizeDepositors(String[] source, int size) {
        String[] normalized = new String[Math.max(4096, Math.max(TREASURY_SLOTS, size))];
        if (source != null) {
            System.arraycopy(source, 0, normalized, 0, Math.min(source.length, normalized.length));
        }
        return normalized;
    }
}
