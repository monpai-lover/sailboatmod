package com.example.examplemod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record NationDiplomacyRequestRecord(
        String fromNationId,
        String toNationId,
        String statusId,
        long createdAt
) {
    public NationDiplomacyRequestRecord {
        fromNationId = normalize(fromNationId);
        toNationId = normalize(toNationId);
        statusId = NationDiplomacyStatus.fromId(statusId).id();
        createdAt = Math.max(0L, createdAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("FromNationId", fromNationId);
        tag.putString("ToNationId", toNationId);
        tag.putString("StatusId", statusId);
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static NationDiplomacyRequestRecord load(CompoundTag tag) {
        return new NationDiplomacyRequestRecord(
                tag.getString("FromNationId"),
                tag.getString("ToNationId"),
                tag.getString("StatusId"),
                tag.getLong("CreatedAt")
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}