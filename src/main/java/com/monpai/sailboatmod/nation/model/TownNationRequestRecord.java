package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record TownNationRequestRecord(
        String townId,
        String nationId,
        String direction,
        UUID initiatorUuid,
        long createdAt
) {
    public static final String DIRECTION_INVITE = "invite";
    public static final String DIRECTION_APPLY = "apply";

    public TownNationRequestRecord {
        townId = sanitizeId(townId);
        nationId = sanitizeId(nationId);
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
        tag.putString("NationId", nationId);
        tag.putString("Direction", direction);
        tag.putUUID("InitiatorUuid", initiatorUuid);
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static TownNationRequestRecord load(CompoundTag tag) {
        UUID initiatorUuid = tag.hasUUID("InitiatorUuid") ? tag.getUUID("InitiatorUuid") : new UUID(0L, 0L);
        return new TownNationRequestRecord(
                tag.getString("TownId"),
                tag.getString("NationId"),
                tag.getString("Direction"),
                initiatorUuid,
                tag.getLong("CreatedAt")
        );
    }

    private static String sanitizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
