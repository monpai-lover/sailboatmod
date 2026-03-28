package com.example.examplemod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record NationMemberRecord(
        UUID playerUuid,
        String lastKnownName,
        String nationId,
        String officeId,
        long joinedAt
) {
    public NationMemberRecord {
        playerUuid = playerUuid == null ? new UUID(0L, 0L) : playerUuid;
        lastKnownName = sanitize(lastKnownName);
        nationId = sanitizeId(nationId);
        officeId = sanitizeId(officeId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerUuid", playerUuid);
        tag.putString("LastKnownName", lastKnownName);
        tag.putString("NationId", nationId);
        tag.putString("OfficeId", officeId);
        tag.putLong("JoinedAt", joinedAt);
        return tag;
    }

    public static NationMemberRecord load(CompoundTag tag) {
        UUID playerUuid = tag.hasUUID("PlayerUuid") ? tag.getUUID("PlayerUuid") : new UUID(0L, 0L);
        return new NationMemberRecord(
                playerUuid,
                tag.getString("LastKnownName"),
                tag.getString("NationId"),
                tag.getString("OfficeId"),
                tag.getLong("JoinedAt")
        );
    }

    public NationMemberRecord withLastKnownName(String value) {
        return new NationMemberRecord(playerUuid, value, nationId, officeId, joinedAt);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
