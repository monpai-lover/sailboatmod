package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RoadPlannerDraftStore {
    private static final ConcurrentMap<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();

    private RoadPlannerDraftStore() {
    }

    public static void save(UUID sessionId, List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes,
                        BlockPos startPos, BlockPos endPos) {
        if (sessionId == null || nodes == null || nodes.isEmpty()) {
            return;
        }
        DRAFTS.put(sessionId, new Draft(nodes, segmentTypes, startPos, endPos));
    }

    public static Draft get(UUID sessionId) {
        return sessionId == null ? null : DRAFTS.get(sessionId);
    }

    public static void clearForTest() {
        DRAFTS.clear();
    }

    public record Draft(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes,
                        BlockPos startPos, BlockPos endPos) {
        public Draft(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes) {
            this(nodes, segmentTypes, BlockPos.ZERO, BlockPos.ZERO);
        }

        public Draft {
            nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
            segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
            startPos = startPos == null ? BlockPos.ZERO : startPos.immutable();
            endPos = endPos == null ? BlockPos.ZERO : endPos.immutable();
        }
    }
}
