package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class GroundRouteSkeletonPlanner {
    private GroundRouteSkeletonPlanner() {
    }

    public record PlannedGroundRoute(boolean success,
                                     RouteSkeleton skeleton,
                                     List<BlockPos> path,
                                     RoadPlanningFailureReason failureReason) {
        public PlannedGroundRoute {
            skeleton = skeleton == null ? new RouteSkeleton(List.of()) : skeleton;
            path = path == null ? List.of() : List.copyOf(path);
            failureReason = failureReason == null ? RoadPlanningFailureReason.NONE : failureReason;
        }
    }

    static PlannedGroundRoute planForTest(BlockPos start,
                                          BlockPos end,
                                          List<BlockPos> anchors,
                                          Function<SegmentedRoadPathOrchestrator.SegmentRequest, List<BlockPos>> planner) {
        SegmentedRoadPathOrchestrator.OrchestratedPath path = SegmentedRoadPathOrchestrator.planForTest(
                start,
                end,
                anchors,
                planner,
                request -> request.depth() < 2
        );
        if (!path.success()) {
            return new PlannedGroundRoute(false, new RouteSkeleton(List.of()), List.of(), RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE);
        }

        ArrayList<RouteSkeleton.Segment> segments = new ArrayList<>();
        for (int i = 1; i < path.path().size(); i++) {
            BlockPos previous = path.path().get(i - 1);
            BlockPos current = path.path().get(i);
            RouteSkeleton.SegmentType type = current.getY() == previous.getY()
                    ? RouteSkeleton.SegmentType.GROUND
                    : RouteSkeleton.SegmentType.SLOPE;
            segments.add(new RouteSkeleton.Segment(type, previous, current));
        }
        return new PlannedGroundRoute(true, new RouteSkeleton(segments), path.path(), RoadPlanningFailureReason.NONE);
    }
}
