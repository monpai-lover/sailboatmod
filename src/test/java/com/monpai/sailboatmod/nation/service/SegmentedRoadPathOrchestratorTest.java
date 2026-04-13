package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentedRoadPathOrchestratorTest {
    @Test
    void longRouteUsesIntermediateAnchorsInsteadOfSingleSegment() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos mid = new BlockPos(32, 64, 0);
        BlockPos end = new BlockPos(64, 64, 0);

        SegmentedRoadPathOrchestrator.OrchestratedPath result = SegmentedRoadPathOrchestrator.planForTest(
                start,
                end,
                List.of(mid),
                request -> List.of(request.from(), request.to()),
                request -> false
        );

        assertTrue(result.success());
        assertTrue(result.path().contains(mid));
        assertEquals(2, result.segments().size());
    }

    @Test
    void failedSegmentRetriesWithSubdivisionBeforeReturningFailure() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(48, 64, 0);

        SegmentedRoadPathOrchestrator.OrchestratedPath result = SegmentedRoadPathOrchestrator.planForTest(
                start,
                end,
                List.of(),
                request -> request.to().getX() - request.from().getX() > 24 ? List.of() : List.of(request.from(), request.to()),
                request -> true
        );

        assertTrue(result.success());
        assertFalse(result.segments().isEmpty());
        assertTrue(result.segments().size() > 1);
    }

    @Test
    void returnsStructuredFailureReasonWhenRetriesAreExhausted() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(80, 64, 0);

        SegmentedRoadPathOrchestrator.OrchestratedPath result = SegmentedRoadPathOrchestrator.planForTest(
                start,
                end,
                List.of(),
                request -> List.of(),
                request -> false
        );

        assertFalse(result.success());
        assertEquals(SegmentedRoadPathOrchestrator.FailureReason.SUBDIVISION_LIMIT_EXCEEDED, result.failureReason());
        assertFalse(result.failedSegments().isEmpty());
    }

    @Test
    void collectsIntermediateAnchorsInRouteOrderAndDropsOffCorridorCandidates() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(100, 64, 0);

        List<BlockPos> anchors = SegmentedRoadPathOrchestrator.collectIntermediateAnchorsForTest(
                start,
                end,
                List.of(
                        new BlockPos(80, 64, 0),
                        new BlockPos(20, 64, 0),
                        new BlockPos(50, 64, 20),
                        new BlockPos(80, 64, 0)
                ),
                6,
                8.0D
        );

        assertEquals(
                List.of(
                        new BlockPos(20, 64, 0),
                        new BlockPos(80, 64, 0)
                ),
                anchors
        );
    }
}
