package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundRouteSkeletonPlannerTest {
    @Test
    void successfulGroundRouteBuildsGroundAndSlopeSkeletonSegments() {
        GroundRouteSkeletonPlanner.PlannedGroundRoute planned = GroundRouteSkeletonPlanner.planForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(6, 66, 0),
                List.of(),
                request -> List.of(
                        request.from(),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 65, 0),
                        new BlockPos(3, 65, 0),
                        new BlockPos(4, 66, 0),
                        new BlockPos(5, 66, 0),
                        request.to()
                )
        );

        assertTrue(planned.success());
        assertFalse(planned.skeleton().segments().isEmpty());
        assertTrue(planned.skeleton().segments().stream().anyMatch(segment -> segment.type() == RouteSkeleton.SegmentType.GROUND));
        assertTrue(planned.skeleton().segments().stream().anyMatch(segment -> segment.type() == RouteSkeleton.SegmentType.SLOPE));
    }

    @Test
    void failedGroundRouteReturnsExplicitFailureReason() {
        GroundRouteSkeletonPlanner.PlannedGroundRoute planned = GroundRouteSkeletonPlanner.planForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(48, 64, 0),
                List.of(),
                request -> List.of()
        );

        assertEquals(RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE, planned.failureReason());
    }
}
