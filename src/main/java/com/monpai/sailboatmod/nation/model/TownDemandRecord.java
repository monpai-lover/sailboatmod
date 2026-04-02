package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record TownDemandRecord(
        String demandId,
        String townId,
        String demandType,
        String commodityKey,
        int requiredAmount,
        int fulfilledAmount,
        String sourceRef,
        String status,
        long createdAt,
        long updatedAt
) {
    public TownDemandRecord {
        demandId = sanitize(demandId);
        townId = sanitize(townId);
        demandType = sanitizeUpper(demandType);
        commodityKey = sanitizeCommodityKey(commodityKey);
        requiredAmount = Math.max(0, requiredAmount);
        fulfilledAmount = Math.max(0, fulfilledAmount);
        sourceRef = sanitize(sourceRef);
        status = normalizeStatus(status, requiredAmount, fulfilledAmount);
        createdAt = Math.max(0L, createdAt);
        updatedAt = Math.max(createdAt, updatedAt);
    }

    public static TownDemandRecord create(String townId, String demandType, String commodityKey,
                                          int requiredAmount, int fulfilledAmount, String sourceRef) {
        long now = System.currentTimeMillis();
        return new TownDemandRecord(
                demandId(townId, demandType, commodityKey, sourceRef),
                townId,
                demandType,
                commodityKey,
                requiredAmount,
                fulfilledAmount,
                sourceRef,
                "",
                now,
                now
        );
    }

    public static String demandId(String townId, String demandType, String commodityKey, String sourceRef) {
        return sanitize(townId) + "|" + sanitizeUpper(demandType) + "|" + sanitizeCommodityKey(commodityKey) + "|" + sanitize(sourceRef);
    }

    public TownDemandRecord withSnapshot(int requiredAmount, int fulfilledAmount) {
        long now = System.currentTimeMillis();
        return new TownDemandRecord(demandId, townId, demandType, commodityKey, requiredAmount, fulfilledAmount, sourceRef, status, createdAt, now);
    }

    public TownDemandRecord withAdditionalRequired(int amount) {
        return withSnapshot(requiredAmount + Math.max(0, amount), fulfilledAmount);
    }

    public TownDemandRecord withAdditionalFulfillment(int amount) {
        return withSnapshot(requiredAmount, fulfilledAmount + Math.max(0, amount));
    }

    public boolean isOpen() {
        return "OPEN".equals(status) || "PARTIAL".equals(status);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("DemandId", demandId);
        tag.putString("TownId", townId);
        tag.putString("DemandType", demandType);
        tag.putString("CommodityKey", commodityKey);
        tag.putInt("RequiredAmount", requiredAmount);
        tag.putInt("FulfilledAmount", fulfilledAmount);
        tag.putString("SourceRef", sourceRef);
        tag.putString("Status", status);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static TownDemandRecord load(CompoundTag tag) {
        return new TownDemandRecord(
                tag.getString("DemandId"),
                tag.getString("TownId"),
                tag.getString("DemandType"),
                tag.getString("CommodityKey"),
                tag.getInt("RequiredAmount"),
                tag.getInt("FulfilledAmount"),
                tag.getString("SourceRef"),
                tag.getString("Status"),
                tag.contains("CreatedAt") ? tag.getLong("CreatedAt") : 0L,
                tag.contains("UpdatedAt") ? tag.getLong("UpdatedAt") : 0L
        );
    }

    private static String normalizeStatus(String input, int requiredAmount, int fulfilledAmount) {
        String normalized = sanitizeUpper(input);
        if ("CANCELLED".equals(normalized)) {
            return normalized;
        }
        if (requiredAmount <= 0) {
            return "CANCELLED";
        }
        if (fulfilledAmount >= requiredAmount) {
            return "FILLED";
        }
        if (fulfilledAmount > 0) {
            return "PARTIAL";
        }
        return "OPEN";
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
