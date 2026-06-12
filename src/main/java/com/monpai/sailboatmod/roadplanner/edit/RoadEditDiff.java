package com.monpai.sailboatmod.roadplanner.edit;

import net.minecraft.core.BlockPos;

import java.util.List;

public record RoadEditDiff(String roadId,
                           List<RemovedBlock> removedBlocks,
                           List<KeptBlock> keptBlocks,
                           List<RoadEditBlockPlacement> addedBlocks,
                           List<Conflict> conflicts) {
    public RoadEditDiff {
        roadId = roadId == null ? "" : roadId.trim().toLowerCase(java.util.Locale.ROOT);
        removedBlocks = removedBlocks == null ? List.of() : removedBlocks.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        keptBlocks = keptBlocks == null ? List.of() : keptBlocks.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        addedBlocks = addedBlocks == null ? List.of() : addedBlocks.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        conflicts = conflicts == null ? List.of() : conflicts.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public boolean hasWork() {
        return !removedBlocks.isEmpty() || !addedBlocks.isEmpty();
    }

    public record RemovedBlock(String segmentId, BlockPos pos) {
        public RemovedBlock {
            segmentId = normalizeSegmentId(segmentId);
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
        }
    }

    public record KeptBlock(String segmentId, BlockPos pos) {
        public KeptBlock {
            segmentId = normalizeSegmentId(segmentId);
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
        }
    }

    public record Conflict(BlockPos pos, String reason) {
        public Conflict {
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
            reason = reason == null ? "" : reason.trim();
        }
    }

    private static String normalizeSegmentId(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
