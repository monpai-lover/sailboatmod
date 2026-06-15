package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record TownMemberInviteRecord(
        String townId,
        UUID playerUuid,
        String direction,
        UUID initiatorUuid,
        long createdAt
) {
    public static final String DIRECTION_INVITE = "invite";
    public static final String DIRECTION_APPLY = "apply";

    public TownMemberInviteRecord {
        townId = sanitizeId(townId);
        playerUuid = playerUuid == null ? new UUID(0L, 0L) : playerUuid;
        direction = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        initiatorUuid = initiatorUuid == null ? new UUID(0L, 0L) : initiatorUuid;
    }

    public boolean isInvite() {
        return DIRECTION_INVITE.equals(direction);
    }

    public boolean isApply() {
        return DIRECTION_APPLY.equals(direction);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("TownId", townId);
        tag.putUUID("PlayerUuid", playerUuid);
        tag.putString("Direction", direction);
        tag.putUUID("InitiatorUuid", initiatorUuid);
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static TownMemberInviteRecord load(CompoundTag tag) {
        UUID playerUuid = tag.hasUUID("PlayerUuid") ? tag.getUUID("PlayerUuid") : new UUID(0L, 0L);
        UUID initiatorUuid = tag.hasUUID("InitiatorUuid") ? tag.getUUID("InitiatorUuid") : new UUID(0L, 0L);
        return new TownMemberInviteRecord(
                tag.getString("TownId"),
                playerUuid,
                tag.getString("Direction"),
                initiatorUuid,
                tag.getLong("CreatedAt")
        );
    }

    private static String sanitizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
