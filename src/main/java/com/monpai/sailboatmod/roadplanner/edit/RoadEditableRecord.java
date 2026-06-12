package com.monpai.sailboatmod.roadplanner.edit;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Locale;

public record RoadEditableRecord(String roadId,
                                 String edgeId,
                                 String dimensionId,
                                 String ownerNationId,
                                 String ownerTownId,
                                 String creatorUuid,
                                 String creatorName,
                                 String sourceTownId,
                                 String targetTownId,
                                 String sourceTownName,
                                 String targetTownName,
                                 int width,
                                 String materialId,
                                 Status status,
                                 boolean legacyMigrated,
                                 List<RoadEditableNode> nodes,
                                 List<RoadEditableSegment> segments,
                                 long createdAt,
                                 long updatedAt) {
    public RoadEditableRecord {
        roadId = normalizeId(roadId);
        edgeId = normalize(edgeId);
        dimensionId = normalizeId(dimensionId);
        ownerNationId = normalizeId(ownerNationId);
        ownerTownId = normalizeId(ownerTownId);
        creatorUuid = normalize(creatorUuid);
        creatorName = normalize(creatorName);
        sourceTownId = normalizeId(sourceTownId);
        targetTownId = normalizeId(targetTownId);
        sourceTownName = normalize(sourceTownName);
        targetTownName = normalize(targetTownName);
        width = Math.max(1, width);
        materialId = normalizeId(materialId);
        status = status == null ? Status.BUILT : status;
        nodes = nodes == null ? List.of() : nodes.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        segments = segments == null ? List.of() : segments.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        createdAt = Math.max(0L, createdAt);
        updatedAt = Math.max(createdAt, updatedAt);
    }

    public String townConnectionKey() {
        return RoadNetworkRecord.townConnectionKey(sourceTownId, targetTownId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("RoadId", roadId);
        tag.putString("EdgeId", edgeId);
        tag.putString("Dim", dimensionId);
        tag.putString("OwnerNationId", ownerNationId);
        tag.putString("OwnerTownId", ownerTownId);
        tag.putString("CreatorUuid", creatorUuid);
        tag.putString("CreatorName", creatorName);
        tag.putString("SourceTownId", sourceTownId);
        tag.putString("TargetTownId", targetTownId);
        tag.putString("SourceTownName", sourceTownName);
        tag.putString("TargetTownName", targetTownName);
        tag.putInt("Width", width);
        tag.putString("MaterialId", materialId);
        tag.putString("Status", status.name());
        tag.putBoolean("LegacyMigrated", legacyMigrated);
        ListTag nodeTag = new ListTag();
        for (RoadEditableNode node : nodes) {
            nodeTag.add(node.save());
        }
        tag.put("Nodes", nodeTag);
        ListTag segmentTag = new ListTag();
        for (RoadEditableSegment segment : segments) {
            segmentTag.add(segment.save());
        }
        tag.put("Segments", segmentTag);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static RoadEditableRecord load(CompoundTag tag) {
        return new RoadEditableRecord(
                tag.getString("RoadId"),
                tag.getString("EdgeId"),
                tag.getString("Dim"),
                tag.getString("OwnerNationId"),
                tag.getString("OwnerTownId"),
                tag.getString("CreatorUuid"),
                tag.getString("CreatorName"),
                tag.getString("SourceTownId"),
                tag.getString("TargetTownId"),
                tag.getString("SourceTownName"),
                tag.getString("TargetTownName"),
                tag.getInt("Width"),
                tag.getString("MaterialId"),
                parseStatus(tag.getString("Status")),
                tag.getBoolean("LegacyMigrated"),
                loadNodes(tag.getList("Nodes", Tag.TAG_COMPOUND)),
                loadSegments(tag.getList("Segments", Tag.TAG_COMPOUND)),
                tag.getLong("CreatedAt"),
                tag.getLong("UpdatedAt"));
    }

    private static List<RoadEditableNode> loadNodes(ListTag tag) {
        java.util.ArrayList<RoadEditableNode> nodes = new java.util.ArrayList<>();
        for (int index = 0; index < tag.size(); index++) {
            nodes.add(RoadEditableNode.load(tag.getCompound(index)));
        }
        return nodes;
    }

    private static List<RoadEditableSegment> loadSegments(ListTag tag) {
        java.util.ArrayList<RoadEditableSegment> segments = new java.util.ArrayList<>();
        for (int index = 0; index < tag.size(); index++) {
            segments.add(RoadEditableSegment.load(tag.getCompound(index)));
        }
        return segments;
    }

    private static Status parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return Status.BUILT;
        }
        try {
            return Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Status.BUILT;
        }
    }

    private static String normalizeId(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Status {
        PLANNED,
        BUILDING,
        BUILT,
        EDITING,
        REMOVING,
        REMOVED
    }
}
