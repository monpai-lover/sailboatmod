package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record PeaceProposalRecord(
        String warId,
        String proposerNationId,
        String type,
        int cedeTerritoryCount,
        long reparationAmount,
        long createdAt
) {
    public static final long EXPIRY_MILLIS = 300_000L;
    public static final String TYPE_CEASEFIRE = "ceasefire";
    public static final String TYPE_CEDE_TERRITORY = "cede_territory";
    public static final String TYPE_REPARATION = "reparation";

    public PeaceProposalRecord {
        warId = warId == null ? "" : warId.trim();
        proposerNationId = proposerNationId == null ? "" : proposerNationId.trim().toLowerCase(Locale.ROOT);
        type = type == null ? TYPE_CEASEFIRE : type.trim().toLowerCase(Locale.ROOT);
        cedeTerritoryCount = Math.max(0, cedeTerritoryCount);
        reparationAmount = Math.max(0L, reparationAmount);
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > EXPIRY_MILLIS;
    }

    public long remainingMillis() {
        return Math.max(0L, EXPIRY_MILLIS - (System.currentTimeMillis() - createdAt));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("WarId", warId);
        tag.putString("ProposerNationId", proposerNationId);
        tag.putString("Type", type);
        tag.putInt("CedeCount", cedeTerritoryCount);
        tag.putLong("ReparationAmount", reparationAmount);
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static PeaceProposalRecord load(CompoundTag tag) {
        return new PeaceProposalRecord(
                tag.getString("WarId"),
                tag.getString("ProposerNationId"),
                tag.getString("Type"),
                tag.getInt("CedeCount"),
                tag.getLong("ReparationAmount"),
                tag.getLong("CreatedAt")
        );
    }
}
