package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record TownMemberRecord(
        UUID playerUuid,
        String townId,
        String officeId,
        long joinedAt
) {
    public static final String OFFICE_MEMBER = "member";

    public TownMemberRecord {
        playerUuid = playerUuid == null ? new UUID(0L, 0L) : playerUuid;
        townId = sanitizeId(townId);
        officeId = sanitizeId(officeId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerUuid", playerUuid);
        tag.putString("TownId", townId);
        tag.putString("OfficeId", officeId);
        tag.putLong("JoinedAt", joinedAt);
        return tag;
    }

    public static TownMemberRecord load(CompoundTag tag) {
        UUID playerUuid = tag.hasUUID("PlayerUuid") ? tag.getUUID("PlayerUuid") : new UUID(0L, 0L);
        return new TownMemberRecord(
                playerUuid,
                tag.getString("TownId"),
                tag.getString("OfficeId"),
                tag.getLong("JoinedAt")
        );
    }

    private static String sanitizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
