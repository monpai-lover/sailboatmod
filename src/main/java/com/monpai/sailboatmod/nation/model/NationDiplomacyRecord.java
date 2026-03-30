package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record NationDiplomacyRecord(
        String nationAId,
        String nationBId,
        String statusId,
        long updatedAt
) {
    public NationDiplomacyRecord {
        String normalizedA = normalize(nationAId);
        String normalizedB = normalize(nationBId);
        if (!normalizedA.isBlank() && !normalizedB.isBlank() && normalizedA.compareTo(normalizedB) > 0) {
            String swap = normalizedA;
            normalizedA = normalizedB;
            normalizedB = swap;
        }
        nationAId = normalizedA;
        nationBId = normalizedB;
        statusId = NationDiplomacyStatus.fromId(statusId).id();
        updatedAt = Math.max(0L, updatedAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("NationAId", nationAId);
        tag.putString("NationBId", nationBId);
        tag.putString("StatusId", statusId);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static NationDiplomacyRecord load(CompoundTag tag) {
        return new NationDiplomacyRecord(
                tag.getString("NationAId"),
                tag.getString("NationBId"),
                tag.getString("StatusId"),
                tag.getLong("UpdatedAt")
        );
    }

    public boolean includes(String nationId) {
        String normalized = normalize(nationId);
        return nationAId.equals(normalized) || nationBId.equals(normalized);
    }

    public String otherNationId(String nationId) {
        String normalized = normalize(nationId);
        if (nationAId.equals(normalized)) {
            return nationBId;
        }
        if (nationBId.equals(normalized)) {
            return nationAId;
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}