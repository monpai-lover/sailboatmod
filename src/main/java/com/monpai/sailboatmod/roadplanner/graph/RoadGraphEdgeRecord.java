package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record RoadGraphEdgeRecord(UUID edgeId,
                                  UUID fromNodeId,
                                  UUID toNodeId,
                                  String dimensionId,
                                  String ownerNationId,
                                  String ownerTownId,
                                  String creatorUuid,
                                  String creatorName,
                                  String sourceTownName,
                                  String targetTownName,
                                  String roadName,
                                  int width,
                                  CompiledRoadSectionType sectionType,
                                  Status status,
                                  List<BlockPos> centerline,
                                  List<BlockPos> displayPath,
                                  List<RoadGraphSegmentPlacement> placements,
                                  List<BlockPos> ownedBlockPositions,
                                  List<RoadGraphReuseSpan> reuseSpans,
                                  long createdAt,
                                  long updatedAt) {
    public RoadGraphEdgeRecord {
        edgeId = edgeId == null ? UUID.randomUUID() : edgeId;
        fromNodeId = fromNodeId == null ? new UUID(0L, 0L) : fromNodeId;
        toNodeId = toNodeId == null ? new UUID(0L, 0L) : toNodeId;
        dimensionId = normalizeText(dimensionId).toLowerCase(Locale.ROOT);
        ownerNationId = normalizeText(ownerNationId).toLowerCase(Locale.ROOT);
        ownerTownId = normalizeText(ownerTownId).toLowerCase(Locale.ROOT);
        creatorUuid = normalizeText(creatorUuid);
        creatorName = normalizeText(creatorName);
        sourceTownName = normalizeText(sourceTownName);
        targetTownName = normalizeText(targetTownName);
        roadName = normalizeText(roadName);
        width = Math.max(1, width);
        sectionType = sectionType == null ? CompiledRoadSectionType.ROAD : sectionType;
        status = status == null ? Status.BUILT : status;
        centerline = copyPositions(centerline);
        displayPath = copyPositions(displayPath);
        placements = placements == null ? List.of() : placements.stream()
                .filter(Objects::nonNull)
                .toList();
        ownedBlockPositions = copyPositions(ownedBlockPositions);
        reuseSpans = reuseSpans == null ? List.of() : reuseSpans.stream()
                .filter(Objects::nonNull)
                .toList();
        createdAt = Math.max(0L, createdAt);
        updatedAt = Math.max(createdAt, updatedAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("EdgeId", edgeId);
        tag.putUUID("FromNodeId", fromNodeId);
        tag.putUUID("ToNodeId", toNodeId);
        tag.putString("Dim", dimensionId);
        tag.putString("OwnerNationId", ownerNationId);
        tag.putString("OwnerTownId", ownerTownId);
        tag.putString("CreatorUuid", creatorUuid);
        tag.putString("CreatorName", creatorName);
        tag.putString("SourceTownName", sourceTownName);
        tag.putString("TargetTownName", targetTownName);
        tag.putString("RoadName", roadName);
        tag.putInt("Width", width);
        tag.putString("SectionType", sectionType.name());
        tag.putString("Status", status.name());
        tag.put("Centerline", savePositions(centerline));
        tag.put("DisplayPath", savePositions(displayPath));
        tag.put("Placements", savePlacements(placements));
        tag.put("OwnedBlockPositions", savePositions(ownedBlockPositions));
        tag.put("ReuseSpans", saveReuseSpans(reuseSpans));
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static RoadGraphEdgeRecord load(CompoundTag tag) {
        return new RoadGraphEdgeRecord(
                tag.hasUUID("EdgeId") ? tag.getUUID("EdgeId") : UUID.randomUUID(),
                tag.hasUUID("FromNodeId") ? tag.getUUID("FromNodeId") : new UUID(0L, 0L),
                tag.hasUUID("ToNodeId") ? tag.getUUID("ToNodeId") : new UUID(0L, 0L),
                tag.getString("Dim"),
                tag.getString("OwnerNationId"),
                tag.getString("OwnerTownId"),
                tag.getString("CreatorUuid"),
                tag.getString("CreatorName"),
                tag.getString("SourceTownName"),
                tag.getString("TargetTownName"),
                tag.getString("RoadName"),
                tag.getInt("Width"),
                parseSectionType(tag.getString("SectionType")),
                parseStatus(tag.getString("Status")),
                loadPositions(tag.getList("Centerline", Tag.TAG_COMPOUND)),
                loadPositions(tag.getList("DisplayPath", Tag.TAG_COMPOUND)),
                loadPlacements(tag.getList("Placements", Tag.TAG_COMPOUND)),
                loadPositions(tag.getList("OwnedBlockPositions", Tag.TAG_COMPOUND)),
                loadReuseSpans(tag.getList("ReuseSpans", Tag.TAG_COMPOUND)),
                tag.getLong("CreatedAt"),
                tag.getLong("UpdatedAt"));
    }

    private static List<BlockPos> copyPositions(List<BlockPos> positions) {
        return positions == null ? List.of() : positions.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    private static ListTag savePositions(List<BlockPos> positions) {
        ListTag list = new ListTag();
        for (BlockPos position : positions) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", position.asLong());
            list.add(entry);
        }
        return list;
    }

    private static List<BlockPos> loadPositions(ListTag tag) {
        java.util.ArrayList<BlockPos> positions = new java.util.ArrayList<>();
        for (int index = 0; index < tag.size(); index++) {
            positions.add(BlockPos.of(tag.getCompound(index).getLong("Pos")));
        }
        return positions;
    }

    private static ListTag savePlacements(List<RoadGraphSegmentPlacement> placements) {
        ListTag list = new ListTag();
        for (RoadGraphSegmentPlacement placement : placements) {
            list.add(placement.save());
        }
        return list;
    }

    private static List<RoadGraphSegmentPlacement> loadPlacements(ListTag tag) {
        java.util.ArrayList<RoadGraphSegmentPlacement> placements = new java.util.ArrayList<>();
        for (int index = 0; index < tag.size(); index++) {
            placements.add(RoadGraphSegmentPlacement.load(tag.getCompound(index)));
        }
        return placements;
    }

    private static ListTag saveReuseSpans(List<RoadGraphReuseSpan> spans) {
        ListTag list = new ListTag();
        for (RoadGraphReuseSpan span : spans) {
            list.add(span.save());
        }
        return list;
    }

    private static List<RoadGraphReuseSpan> loadReuseSpans(ListTag tag) {
        java.util.ArrayList<RoadGraphReuseSpan> spans = new java.util.ArrayList<>();
        for (int index = 0; index < tag.size(); index++) {
            spans.add(RoadGraphReuseSpan.load(tag.getCompound(index)));
        }
        return spans;
    }

    private static CompiledRoadSectionType parseSectionType(String value) {
        if (value == null || value.isBlank()) {
            return CompiledRoadSectionType.ROAD;
        }
        try {
            return CompiledRoadSectionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CompiledRoadSectionType.ROAD;
        }
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

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Status {
        PLANNED,
        BUILDING,
        BUILT,
        REMOVING,
        REMOVED,
        CANCELLED
    }
}
