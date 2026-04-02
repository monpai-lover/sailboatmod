package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record ConstructionMaterialRequestRecord(
        String projectId,
        String townId,
        String commodityKey,
        int required,
        int fulfilled,
        int purchased,
        long createdAt,
        long updatedAt
) {
    public ConstructionMaterialRequestRecord {
        projectId = sanitize(projectId);
        townId = sanitize(townId);
        commodityKey = sanitizeCommodityKey(commodityKey);
        required = Math.max(0, required);
        fulfilled = Math.max(0, fulfilled);
        purchased = Math.max(0, purchased);
        createdAt = Math.max(0L, createdAt);
        updatedAt = Math.max(createdAt, updatedAt);
    }

    public static ConstructionMaterialRequestRecord create(String projectId, String townId, String commodityKey,
                                                           int required, int fulfilled, int purchased) {
        long now = System.currentTimeMillis();
        return new ConstructionMaterialRequestRecord(projectId, townId, commodityKey, required, fulfilled, purchased, now, now);
    }

    public ConstructionMaterialRequestRecord merge(int extraRequired, int extraFulfilled, int extraPurchased) {
        long now = System.currentTimeMillis();
        return new ConstructionMaterialRequestRecord(
                projectId,
                townId,
                commodityKey,
                required + Math.max(0, extraRequired),
                fulfilled + Math.max(0, extraFulfilled),
                purchased + Math.max(0, extraPurchased),
                createdAt,
                now
        );
    }

    public ConstructionMaterialRequestRecord addFulfillment(int extraFulfilled) {
        long now = System.currentTimeMillis();
        return new ConstructionMaterialRequestRecord(
                projectId,
                townId,
                commodityKey,
                required,
                fulfilled + Math.max(0, extraFulfilled),
                purchased,
                createdAt,
                now
        );
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ProjectId", projectId);
        tag.putString("TownId", townId);
        tag.putString("CommodityKey", commodityKey);
        tag.putInt("Required", required);
        tag.putInt("Fulfilled", fulfilled);
        tag.putInt("Purchased", purchased);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static ConstructionMaterialRequestRecord load(CompoundTag tag) {
        return new ConstructionMaterialRequestRecord(
                tag.getString("ProjectId"),
                tag.getString("TownId"),
                tag.getString("CommodityKey"),
                tag.getInt("Required"),
                tag.getInt("Fulfilled"),
                tag.getInt("Purchased"),
                tag.contains("CreatedAt") ? tag.getLong("CreatedAt") : 0L,
                tag.contains("UpdatedAt") ? tag.getLong("UpdatedAt") : 0L
        );
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeCommodityKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
