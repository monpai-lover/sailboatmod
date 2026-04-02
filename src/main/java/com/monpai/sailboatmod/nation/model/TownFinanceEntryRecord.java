package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record TownFinanceEntryRecord(
        String entryId,
        String townId,
        String entryKind,
        String type,
        long amount,
        String currency,
        String commodityKey,
        int quantity,
        String sourceRef,
        long timestamp,
        long gameTime
) {
    public TownFinanceEntryRecord {
        entryId = sanitize(entryId);
        townId = sanitize(townId);
        entryKind = sanitizeUpper(entryKind);
        type = sanitizeUpper(type);
        amount = Math.max(0L, amount);
        currency = sanitizeUpper(currency).isBlank() ? "EMERALD" : sanitizeUpper(currency);
        commodityKey = sanitizeCommodityKey(commodityKey);
        quantity = Math.max(0, quantity);
        sourceRef = sanitize(sourceRef);
        timestamp = Math.max(0L, timestamp);
        gameTime = Math.max(0L, gameTime);
    }

    public static TownFinanceEntryRecord create(String townId, String entryKind, String type, long amount, String currency,
                                                String commodityKey, int quantity, String sourceRef, long gameTime) {
        return new TownFinanceEntryRecord(
                "tf_" + UUID.randomUUID().toString().replace("-", ""),
                townId,
                entryKind,
                type,
                amount,
                currency,
                commodityKey,
                quantity,
                sourceRef,
                System.currentTimeMillis(),
                gameTime
        );
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("EntryId", entryId);
        tag.putString("TownId", townId);
        tag.putString("EntryKind", entryKind);
        tag.putString("Type", type);
        tag.putLong("Amount", amount);
        tag.putString("Currency", currency);
        tag.putString("CommodityKey", commodityKey);
        tag.putInt("Quantity", quantity);
        tag.putString("SourceRef", sourceRef);
        tag.putLong("Timestamp", timestamp);
        tag.putLong("GameTime", gameTime);
        return tag;
    }

    public static TownFinanceEntryRecord load(CompoundTag tag) {
        return new TownFinanceEntryRecord(
                tag.getString("EntryId"),
                tag.getString("TownId"),
                tag.getString("EntryKind"),
                tag.getString("Type"),
                tag.getLong("Amount"),
                tag.getString("Currency"),
                tag.getString("CommodityKey"),
                tag.getInt("Quantity"),
                tag.getString("SourceRef"),
                tag.contains("Timestamp") ? tag.getLong("Timestamp") : 0L,
                tag.contains("GameTime") ? tag.getLong("GameTime") : 0L
        );
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeUpper(String value) {
        return sanitize(value).toUpperCase(Locale.ROOT);
    }

    private static String sanitizeCommodityKey(String value) {
        return sanitize(value).toLowerCase(Locale.ROOT);
    }
}
