package com.monpai.sailboatmod.roadplanner.edit;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoadEditDiffPlanner {
    private RoadEditDiffPlanner() {
    }

    public static RoadEditDiff plan(RoadEditableRecord current, List<RoadEditBlockPlacement> proposedPlacements) {
        if (current == null) {
            return new RoadEditDiff("", List.of(), List.of(), normalizeProposed(proposedPlacements), conflicts(proposedPlacements));
        }
        Map<Long, RoadEditDiff.RemovedBlock> oldByPos = oldBlocks(current);
        ProposedIndex proposed = proposedIndex(proposedPlacements);
        java.util.ArrayList<RoadEditDiff.RemovedBlock> removed = new java.util.ArrayList<>();
        java.util.ArrayList<RoadEditDiff.KeptBlock> kept = new java.util.ArrayList<>();
        java.util.ArrayList<RoadEditBlockPlacement> added = new java.util.ArrayList<>();

        for (RoadEditDiff.RemovedBlock old : oldByPos.values()) {
            if (proposed.validByPos.containsKey(old.pos().asLong())) {
                kept.add(new RoadEditDiff.KeptBlock(old.segmentId(), old.pos()));
            } else {
                removed.add(old);
            }
        }
        for (RoadEditBlockPlacement placement : proposed.validByPos.values()) {
            if (!oldByPos.containsKey(placement.pos().asLong())) {
                added.add(placement);
            }
        }
        return new RoadEditDiff(current.roadId(), removed, kept, added, proposed.conflicts);
    }

    private static Map<Long, RoadEditDiff.RemovedBlock> oldBlocks(RoadEditableRecord current) {
        LinkedHashMap<Long, RoadEditDiff.RemovedBlock> blocks = new LinkedHashMap<>();
        if (current == null || current.segments() == null) {
            return blocks;
        }
        for (RoadEditableSegment segment : current.segments()) {
            if (segment == null) {
                continue;
            }
            for (Long posLong : segment.blockPositions()) {
                if (posLong == null) {
                    continue;
                }
                blocks.putIfAbsent(posLong, new RoadEditDiff.RemovedBlock(segment.segmentId(), BlockPos.of(posLong)));
            }
        }
        return blocks;
    }

    private static List<RoadEditBlockPlacement> normalizeProposed(List<RoadEditBlockPlacement> proposedPlacements) {
        return proposedPlacements == null ? List.of() : proposedPlacements.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static List<RoadEditDiff.Conflict> conflicts(List<RoadEditBlockPlacement> proposedPlacements) {
        return proposedIndex(proposedPlacements).conflicts();
    }

    private static ProposedIndex proposedIndex(List<RoadEditBlockPlacement> proposedPlacements) {
        LinkedHashMap<Long, RoadEditBlockPlacement> allByPos = new LinkedHashMap<>();
        java.util.LinkedHashSet<Long> conflictPositions = new java.util.LinkedHashSet<>();
        java.util.ArrayList<RoadEditDiff.Conflict> conflicts = new java.util.ArrayList<>();
        for (RoadEditBlockPlacement placement : normalizeProposed(proposedPlacements)) {
            long key = placement.pos().asLong();
            RoadEditBlockPlacement existing = allByPos.get(key);
            if (existing == null) {
                allByPos.put(key, placement);
                continue;
            }
            if (!existing.roadState().equals(placement.roadState()) && conflictPositions.add(key)) {
                conflicts.add(new RoadEditDiff.Conflict(placement.pos(), "conflicting road states"));
            }
        }
        LinkedHashMap<Long, RoadEditBlockPlacement> validByPos = new LinkedHashMap<>();
        for (Map.Entry<Long, RoadEditBlockPlacement> entry : allByPos.entrySet()) {
            if (!conflictPositions.contains(entry.getKey())) {
                validByPos.put(entry.getKey(), entry.getValue());
            }
        }
        return new ProposedIndex(validByPos, List.copyOf(conflicts));
    }

    private record ProposedIndex(Map<Long, RoadEditBlockPlacement> validByPos,
                                 List<RoadEditDiff.Conflict> conflicts) {
    }
}
