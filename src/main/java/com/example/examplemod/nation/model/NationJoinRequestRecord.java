package com.example.examplemod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record NationJoinRequestRecord(
        String nationId,
        UUID applicantUuid,
        long createdAt
) {
    public NationJoinRequestRecord {
        nationId = sanitizeId(nationId);
        applicantUuid = applicantUuid == null ? new UUID(0L, 0L) : applicantUuid;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("NationId", nationId);
        tag.putUUID("ApplicantUuid", applicantUuid);
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static NationJoinRequestRecord load(CompoundTag tag) {
        UUID applicantUuid = tag.hasUUID("ApplicantUuid") ? tag.getUUID("ApplicantUuid") : new UUID(0L, 0L);
        return new NationJoinRequestRecord(
                tag.getString("NationId"),
                applicantUuid,
                tag.getLong("CreatedAt")
        );
    }

    private static String sanitizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}