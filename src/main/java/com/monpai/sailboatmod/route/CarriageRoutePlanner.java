package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class CarriageRoutePlanner {
    private CarriageRoutePlanner() {
    }

    public static CarriageRoutePlan plan(ServerLevel level, BlockPos start, BlockPos end) {
        RoadAutoRouteService.RouteResolution resolution = RoadAutoRouteService.resolveAutoRoutePreview(level, start, end);
        if (resolution == null || !resolution.found()) {
            return CarriageRoutePlan.empty();
        }
        return planFromPath(level, resolution.path());
    }

    public static CarriageRoutePlan planFromWaypoints(ServerLevel level, List<Vec3> waypoints) {
        if (level == null || waypoints == null || waypoints.size() < 2) {
            return CarriageRoutePlan.empty();
        }
        List<BlockPos> path = new ArrayList<>(waypoints.size());
        for (Vec3 waypoint : waypoints) {
            if (waypoint == null) {
                continue;
            }
            path.add(BlockPos.containing(waypoint.x, Mth.floor(waypoint.y - 1.0D), waypoint.z));
        }
        return planFromPath(level, path);
    }

    static CarriageRoutePlan planFromPath(ServerLevel level, List<BlockPos> path) {
        if (level == null || path == null || path.size() < 2) {
            return CarriageRoutePlan.empty();
        }
        RoadGraphRoutingService graph = new RoadGraphRoutingService(RoadGraphRepository.forLevel(level));
        List<CarriageRoutePlan.Segment> segments = splitIntoSegments(level.dimension().location().toString(), path, graph);
        return segments.isEmpty() ? CarriageRoutePlan.empty() : new CarriageRoutePlan(segments);
    }

    private static List<CarriageRoutePlan.Segment> splitIntoSegments(String dimensionId,
                                                                     List<BlockPos> path,
                                                                     RoadGraphRoutingService graph) {
        List<CarriageRoutePlan.Segment> segments = new ArrayList<>();
        if (path == null || path.size() < 2) {
            return segments;
        }

        CarriageRoutePlan.SegmentKind currentKind = classify(dimensionId, path.get(0), graph);
        List<BlockPos> currentPath = new ArrayList<>();
        currentPath.add(path.get(0).immutable());

        for (int i = 1; i < path.size(); i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            CarriageRoutePlan.SegmentKind nextKind = classify(dimensionId, current, graph);
            if (nextKind != currentKind) {
                currentPath.add(current.immutable());
                addSegmentIfUsable(segments, currentKind, currentPath);
                currentPath = new ArrayList<>();
                currentPath.add(previous.immutable());
                currentPath.add(current.immutable());
                currentKind = nextKind;
                continue;
            }
            currentPath.add(current.immutable());
        }

        addSegmentIfUsable(segments, currentKind, currentPath);
        return List.copyOf(segments);
    }

    private static void addSegmentIfUsable(List<CarriageRoutePlan.Segment> segments,
                                           CarriageRoutePlan.SegmentKind kind,
                                           List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return;
        }
        LinkedHashSet<BlockPos> deduped = new LinkedHashSet<>();
        for (BlockPos pos : path) {
            if (pos != null) {
                deduped.add(pos.immutable());
            }
        }
        if (deduped.size() >= 2) {
            segments.add(new CarriageRoutePlan.Segment(kind, List.copyOf(deduped)));
        }
    }

    private static CarriageRoutePlan.SegmentKind classify(String dimensionId, BlockPos pos, RoadGraphRoutingService graph) {
        return graph != null && graph.isRoadCorridor(dimensionId, pos, 1)
                ? CarriageRoutePlan.SegmentKind.ROAD_CORRIDOR
                : CarriageRoutePlan.SegmentKind.TERRAIN_CONNECTOR;
    }
}
