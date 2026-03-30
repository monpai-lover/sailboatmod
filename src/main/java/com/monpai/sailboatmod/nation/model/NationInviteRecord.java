package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record NationInviteRecord(
        String nationId,
        UUID playerUuid,
        UUID invitedByUuid,
        long createdAt
) {
    public NationInviteRecord {
        nationId = sanitizeId(nationId);
        playerUuid = playerUuid == null ? new UUID(0L, 0L) : playerUuid;
        invitedByUuid = invitedByUuid == null ? new UUID(0L, 0L) : invitedByUuid;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("NationId", nationId);
        tag.putUUID("PlayerUuid", playerUuid);
        tag.putUUID("InvitedByUuid", invitedByUuid);
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static NationInviteRecord load(CompoundTag tag) {
        UUID playerUuid = tag.hasUUID("PlayerUuid") ? tag.getUUID("PlayerUuid") : new UUID(0L, 0L);
        UUID invitedByUuid = tag.hasUUID("InvitedByUuid") ? tag.getUUID("InvitedByUuid") : new UUID(0L, 0L);
        return new NationInviteRecord(
                tag.getString("NationId"),
                playerUuid,
                invitedByUuid,
                tag.getLong("CreatedAt")
        );
    }

    private static String sanitizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
