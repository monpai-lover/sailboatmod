package com.monpai.sailboatmod.roadplanner.edit;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record RoadEditableNode(String nodeId, BlockPos pos, Kind kind, String label) {
    public RoadEditableNode {
        nodeId = normalize(nodeId);
        pos = pos == null ? BlockPos.ZERO : pos.immutable();
        kind = kind == null ? Kind.NORMAL : kind;
        label = label == null ? "" : label.trim();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("NodeId", nodeId);
        tag.putLong("Pos", pos.asLong());
        tag.putString("Kind", kind.name());
        tag.putString("Label", label);
        return tag;
    }

    public static RoadEditableNode load(CompoundTag tag) {
        return new RoadEditableNode(
                tag.getString("NodeId"),
                BlockPos.of(tag.getLong("Pos")),
                parseKind(tag.getString("Kind")),
                tag.getString("Label"));
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

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Kind {
        NORMAL,
        SOURCE_TOWN,
        TARGET_TOWN,
        MARKET,
        POST_STATION,
        DOCK,
        LEGACY_IMPORTED
    }
}
