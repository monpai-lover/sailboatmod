package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record RoadGraphReuseSpan(UUID sourceEdgeId,
                                 int sourceFromIndex,
                                 int sourceToIndex,
                                 int plannedFromIndex,
                                 int plannedToIndex,
                                 BlockPos fromPos,
                                 BlockPos toPos,
                                 Relationship relationship) {
    private static final UUID EMPTY_UUID = new UUID(0L, 0L);

    public RoadGraphReuseSpan {
        sourceEdgeId = sourceEdgeId == null ? EMPTY_UUID : sourceEdgeId;
        sourceFromIndex = Math.max(-1, sourceFromIndex);
        sourceToIndex = Math.max(-1, sourceToIndex);
        plannedFromIndex = Math.max(-1, plannedFromIndex);
        plannedToIndex = Math.max(-1, plannedToIndex);
        fromPos = fromPos == null ? BlockPos.ZERO : fromPos.immutable();
        toPos = toPos == null ? BlockPos.ZERO : toPos.immutable();
        relationship = relationship == null ? Relationship.OWN : relationship;
    }

    public boolean present() {
        return !sourceEdgeId.equals(EMPTY_UUID)
                && sourceFromIndex >= 0
                && sourceToIndex >= 0
                && plannedFromIndex >= 0
                && plannedToIndex >= 0
                && sourceFromIndex != sourceToIndex
                && plannedFromIndex != plannedToIndex;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("SourceEdgeId", sourceEdgeId);
        tag.putInt("SourceFromIndex", sourceFromIndex);
        tag.putInt("SourceToIndex", sourceToIndex);
        tag.putInt("PlannedFromIndex", plannedFromIndex);
        tag.putInt("PlannedToIndex", plannedToIndex);
        tag.putLong("FromPos", fromPos.asLong());
        tag.putLong("ToPos", toPos.asLong());
        tag.putString("Relationship", relationship.name());
        return tag;
    }

    public static RoadGraphReuseSpan load(CompoundTag tag) {
        return new RoadGraphReuseSpan(
                tag.hasUUID("SourceEdgeId") ? tag.getUUID("SourceEdgeId") : EMPTY_UUID,
                tag.getInt("SourceFromIndex"),
                tag.getInt("SourceToIndex"),
                tag.getInt("PlannedFromIndex"),
                tag.getInt("PlannedToIndex"),
                BlockPos.of(tag.getLong("FromPos")),
                BlockPos.of(tag.getLong("ToPos")),
                parseRelationship(tag.getString("Relationship")));
    }

    private static Relationship parseRelationship(String value) {
        if (value == null || value.isBlank()) {
            return Relationship.OWN;
        }
        try {
            return Relationship.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Relationship.OWN;
        }
    }

    public enum Relationship {
        OWN,
        ALLIED,
        TRADE
    }
}
