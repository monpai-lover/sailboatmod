package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record TownStockpileRecord(
        String townId,
        Map<String, Integer> commodityAmounts
) {
    public TownStockpileRecord {
        townId = normalizeTownId(townId);
        Map<String, Integer> normalized = new LinkedHashMap<>();
        if (commodityAmounts != null) {
            for (Map.Entry<String, Integer> entry : commodityAmounts.entrySet()) {
                String commodityKey = normalizeCommodityKey(entry.getKey());
                int amount = Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
                if (!commodityKey.isBlank() && amount > 0) {
                    normalized.put(commodityKey, amount);
                }
            }
        }
        commodityAmounts = Map.copyOf(normalized);
    }

    public static TownStockpileRecord empty(String townId) {
        return new TownStockpileRecord(townId, Map.of());
    }

    public int getAvailable(String commodityKey) {
        return Math.max(0, commodityAmounts.getOrDefault(normalizeCommodityKey(commodityKey), 0));
    }

    public int getShortage(String commodityKey, int required) {
        return Math.max(0, Math.max(0, required) - getAvailable(commodityKey));
    }

    public TownStockpileRecord withAmount(String commodityKey, int amount) {
        String normalizedKey = normalizeCommodityKey(commodityKey);
        Map<String, Integer> updated = new LinkedHashMap<>(commodityAmounts);
        if (normalizedKey.isBlank() || amount <= 0) {
            updated.remove(normalizedKey);
        } else {
            updated.put(normalizedKey, amount);
        }
        return new TownStockpileRecord(townId, updated);
    }

    public TownStockpileRecord adjust(String commodityKey, int delta) {
        return withAmount(commodityKey, getAvailable(commodityKey) + delta);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("TownId", townId);
        ListTag entries = new ListTag();
        for (Map.Entry<String, Integer> entry : commodityAmounts.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("CommodityKey", entry.getKey());
            entryTag.putInt("Amount", Math.max(0, entry.getValue()));
            entries.add(entryTag);
        }
        tag.put("Entries", entries);
        return tag;
    }

    public static TownStockpileRecord load(CompoundTag tag) {
        Map<String, Integer> commodityAmounts = new LinkedHashMap<>();
        ListTag entries = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (Tag raw : entries) {
            if (!(raw instanceof CompoundTag entryTag)) {
                continue;
            }
            String commodityKey = normalizeCommodityKey(entryTag.getString("CommodityKey"));
            int amount = Math.max(0, entryTag.getInt("Amount"));
            if (!commodityKey.isBlank() && amount > 0) {
                commodityAmounts.put(commodityKey, amount);
            }
        }
        return new TownStockpileRecord(tag.getString("TownId"), commodityAmounts);
    }

    private static String normalizeTownId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeCommodityKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
