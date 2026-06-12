package com.monpai.sailboatmod.roadplanner.edit;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Locale;

public record RoadEditableSegment(String segmentId,
                                  String fromNodeId,
                                  String toNodeId,
                                  List<BlockPos> centerline,
                                  List<BlockPos> displayPath,
                                  int width,
                                  String sectionType,
                                  String materialId,
                                  List<Long> blockPositions) {
    public RoadEditableSegment {
        segmentId = normalize(segmentId);
        fromNodeId = normalize(fromNodeId);
        toNodeId = normalize(toNodeId);
        centerline = copyPositions(centerline);
        displayPath = displayPath == null || displayPath.isEmpty() ? centerline : copyPositions(displayPath);
        width = Math.max(1, width);
        sectionType = sectionType == null || sectionType.isBlank()
                ? "ROAD"
                : sectionType.trim().toUpperCase(Locale.ROOT);
        materialId = materialId == null ? "" : materialId.trim().toLowerCase(Locale.ROOT);
        blockPositions = blockPositions == null ? List.of() : blockPositions.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("SegmentId", segmentId);
        tag.putString("FromNodeId", fromNodeId);
        tag.putString("ToNodeId", toNodeId);
        tag.put("Centerline", savePositions(centerline));
        tag.put("DisplayPath", savePositions(displayPath));
        tag.putInt("Width", width);
        tag.putString("SectionType", sectionType);
        tag.putString("MaterialId", materialId);
        tag.put("BlockPositions", saveLongPositions(blockPositions));
        return tag;
    }

    public static RoadEditableSegment load(CompoundTag tag) {
        return new RoadEditableSegment(
                tag.getString("SegmentId"),
                tag.getString("FromNodeId"),
                tag.getString("ToNodeId"),
                loadPositions(tag.getList("Centerline", Tag.TAG_COMPOUND)),
                loadPositions(tag.getList("DisplayPath", Tag.TAG_COMPOUND)),
                tag.getInt("Width"),
                tag.getString("SectionType"),
                tag.getString("MaterialId"),
                loadLongPositions(tag.getList("BlockPositions", Tag.TAG_COMPOUND)));
    }

    static ListTag savePositions(List<BlockPos> positions) {
        ListTag list = new ListTag();
        for (BlockPos position : copyPositions(positions)) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", position.asLong());
            list.add(entry);
        }
        return list;
    }

    static List<BlockPos> loadPositions(ListTag tag) {
        java.util.ArrayList<BlockPos> positions = new java.util.ArrayList<>();
        for (int index = 0; index < tag.size(); index++) {
            positions.add(BlockPos.of(tag.getCompound(index).getLong("Pos")));
        }
        return positions;
    }

    static ListTag saveLongPositions(List<Long> positions) {
        ListTag list = new ListTag();
        if (positions == null) {
            return list;
        }
        for (Long position : positions) {
            if (position == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", position);
            list.add(entry);
        }
        return list;
    }

    static List<Long> loadLongPositions(ListTag tag) {
        java.util.ArrayList<Long> positions = new java.util.ArrayList<>();
        for (int index = 0; index < tag.size(); index++) {
            positions.add(tag.getCompound(index).getLong("Pos"));
        }
        return positions;
    }

    private static List<BlockPos> copyPositions(List<BlockPos> positions) {
        return positions == null ? List.of() : positions.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
