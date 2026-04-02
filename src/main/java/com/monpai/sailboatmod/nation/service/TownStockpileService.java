package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.market.commodity.CommodityKeyResolver;
import com.monpai.sailboatmod.nation.data.TownStockpileSavedData;
import com.monpai.sailboatmod.nation.model.TownStockpileRecord;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TownStockpileService {
    private TownStockpileService() {
    }

    public static int add(Level level, String townId, String commodityKey, int amount) {
        if (level == null || normalizeTownId(townId).isBlank() || normalizeCommodityKey(commodityKey).isBlank() || amount <= 0) {
            return 0;
        }
        TownStockpileSavedData data = TownStockpileSavedData.get(level);
        TownStockpileRecord current = data.getStockpile(townId);
        data.putStockpile(current.adjust(commodityKey, amount));
        return amount;
    }

    public static boolean remove(Level level, String townId, String commodityKey, int amount) {
        if (level == null || amount <= 0) {
            return false;
        }
        if (getAvailable(level, townId, commodityKey) < amount) {
            return false;
        }
        TownStockpileSavedData data = TownStockpileSavedData.get(level);
        TownStockpileRecord current = data.getStockpile(townId);
        data.putStockpile(current.adjust(commodityKey, -amount));
        return true;
    }

    public static int removeAvailable(Level level, String townId, String commodityKey, int requestedAmount) {
        int removable = Math.min(getAvailable(level, townId, commodityKey), Math.max(0, requestedAmount));
        if (removable <= 0) {
            return 0;
        }
        return remove(level, townId, commodityKey, removable) ? removable : 0;
    }

    public static int getAvailable(Level level, String townId, String commodityKey) {
        if (level == null) {
            return 0;
        }
        TownStockpileRecord record = TownStockpileSavedData.get(level).getStockpile(townId);
        return record.getAvailable(commodityKey);
    }

    public static int getShortage(Level level, String townId, String commodityKey, int required) {
        if (level == null) {
            return Math.max(0, required);
        }
        TownStockpileRecord record = TownStockpileSavedData.get(level).getStockpile(townId);
        return record.getShortage(commodityKey, required);
    }

    public static int addItemStack(Level level, String townId, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return add(level, townId, CommodityKeyResolver.resolve(stack), stack.getCount());
    }

    public static int addItemStack(Level level, String townId, ItemStack stack, int amount) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return add(level, townId, CommodityKeyResolver.resolve(stack), amount);
    }

    public static boolean removeItemStack(Level level, String townId, ItemStack stack, int amount) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return remove(level, townId, CommodityKeyResolver.resolve(stack), amount);
    }

    public static void addCargo(Level level, String townId, List<ItemStack> cargo) {
        if (cargo == null) {
            return;
        }
        for (ItemStack stack : cargo) {
            addItemStack(level, townId, stack);
        }
    }

    public static boolean canRemoveCargo(Level level, String townId, List<ItemStack> cargo) {
        if (level == null || cargo == null) {
            return false;
        }
        TownStockpileRecord record = TownStockpileSavedData.get(level).getStockpile(townId);
        for (CommodityAmount amount : aggregateCargo(cargo)) {
            if (record.getAvailable(amount.commodityKey()) < amount.amount()) {
                return false;
            }
        }
        return true;
    }

    public static boolean removeCargo(Level level, String townId, List<ItemStack> cargo) {
        if (!canRemoveCargo(level, townId, cargo)) {
            return false;
        }
        for (CommodityAmount amount : aggregateCargo(cargo)) {
            if (!remove(level, townId, amount.commodityKey(), amount.amount())) {
                return false;
            }
        }
        return true;
    }

    public static TownStockpileRecord getStockpile(Level level, String townId) {
        return level == null ? TownStockpileRecord.empty(townId) : TownStockpileSavedData.get(level).getStockpile(townId);
    }

    private static List<CommodityAmount> aggregateCargo(List<ItemStack> cargo) {
        List<CommodityAmount> amounts = new ArrayList<>();
        java.util.LinkedHashMap<String, Integer> totals = new java.util.LinkedHashMap<>();
        if (cargo != null) {
            for (ItemStack stack : cargo) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                totals.merge(CommodityKeyResolver.resolve(stack), stack.getCount(), Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            if (entry.getValue() > 0) {
                amounts.add(new CommodityAmount(entry.getKey(), entry.getValue()));
            }
        }
        return amounts;
    }

    private static String normalizeTownId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeCommodityKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record CommodityAmount(String commodityKey, int amount) {
    }
}
