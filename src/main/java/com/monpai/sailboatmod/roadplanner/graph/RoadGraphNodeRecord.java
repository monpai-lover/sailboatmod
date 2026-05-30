package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record RoadGraphNodeRecord(UUID nodeId,
                                  String dimensionId,
                                  BlockPos pos,
                                  Kind kind,
                                  String ownerNationId,
                                  String ownerTownId,
                                  String structureId,
                                  long createdAt,
                                  long updatedAt) {
    public RoadGraphNodeRecord {
        nodeId = nodeId == null ? UUID.randomUUID() : nodeId;
        dimensionId = normalizeText(dimensionId).toLowerCase(Locale.ROOT);
        pos = pos == null ? BlockPos.ZERO : pos.immutable();
        kind = kind == null ? Kind.NORMAL : kind;
        ownerNationId = normalizeText(ownerNationId).toLowerCase(Locale.ROOT);
        ownerTownId = normalizeText(ownerTownId).toLowerCase(Locale.ROOT);
        structureId = normalizeText(structureId);
        createdAt = Math.max(0L, createdAt);
        updatedAt = Math.max(createdAt, updatedAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("NodeId", nodeId);
        tag.putString("Dim", dimensionId);
        tag.putLong("Pos", pos.asLong());
        tag.putString("Kind", kind.name());
        tag.putString("OwnerNationId", ownerNationId);
        tag.putString("OwnerTownId", ownerTownId);
        tag.putString("StructureId", structureId);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static RoadGraphNodeRecord load(CompoundTag tag) {
        return new RoadGraphNodeRecord(
                tag.hasUUID("NodeId") ? tag.getUUID("NodeId") : UUID.randomUUID(),
                tag.getString("Dim"),
                BlockPos.of(tag.getLong("Pos")),
                parseKind(tag.getString("Kind")),
                tag.getString("OwnerNationId"),
                tag.getString("OwnerTownId"),
                tag.getString("StructureId"),
                tag.getLong("CreatedAt"),
                tag.getLong("UpdatedAt"));
    }

    private static Kind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return Kind.NORMAL;
        }
        try {
            return Kind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Kind.NORMAL;
        }
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Kind {
        NORMAL,
        JUNCTION,
        TOWN_CONNECTION,
        POST_STATION_CONNECTION,
        REUSE_ANCHOR
    }
}
