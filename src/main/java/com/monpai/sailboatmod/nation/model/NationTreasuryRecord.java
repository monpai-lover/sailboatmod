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
        if (items == null) items = NonNullList.withSize(TREASURY_SLOTS, ItemStack.EMPTY);
        if (itemDepositors == null) itemDepositors = new String[TREASURY_SLOTS];
        salesTaxBasisPoints = Math.max(0, Math.min(MAX_SALES_TAX_BP, salesTaxBasisPoints));
        importTariffBasisPoints = Math.max(0, Math.min(MAX_IMPORT_TARIFF_BP, importTariffBasisPoints));
        if (recentTradeCount < 0) recentTradeCount = 0;
        if (lastTaxAdjustTime < 0L) lastTaxAdjustTime = 0L;
    }

    public static NationTreasuryRecord empty(String nationId) {
        return new NationTreasuryRecord(nationId, 0L, NonNullList.withSize(TREASURY_SLOTS, ItemStack.EMPTY), new String[TREASURY_SLOTS], 500, 1000, 0, 0L);
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
                if (itemDepositors[i] != null && !itemDepositors[i].isBlank()) {
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
        NonNullList<ItemStack> items = NonNullList.withSize(TREASURY_SLOTS, ItemStack.EMPTY);
        String[] depositors = new String[TREASURY_SLOTS];
        ListTag itemList = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < itemList.size(); i++) {
            CompoundTag itemTag = itemList.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot >= 0 && slot < TREASURY_SLOTS) {
                items.set(slot, ItemStack.of(itemTag));
                if (itemTag.contains("Depositor")) {
                    depositors[slot] = itemTag.getString("Depositor");
                }
            }
        }
        return new NationTreasuryRecord(nationId, currencyBalance, items, depositors, salesTaxBP, importTariffBP, recentTradeCount, lastTaxAdjustTime);
    }
}
