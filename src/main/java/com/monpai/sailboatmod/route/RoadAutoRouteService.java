package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.service.RoadHybridRouteResolver;
import com.monpai.sailboatmod.nation.service.RoadPathfinder;
import com.monpai.sailboatmod.nation.service.RoadPlanningSnapshot;
import com.monpai.sailboatmod.nation.service.RoadPlanningSnapshotBuilder;
import com.monpai.sailboatmod.nation.service.RoadPlanningTaskService;
import com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestrator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class RoadAutoRouteService {
    private static final double STATION_CONNECT_RADIUS = 16.0D;
    private static final int SEGMENT_SUBDIVIDE_MANHATTAN = 96;
    private static final int MAX_SEGMENT_INTERMEDIATE_ANCHORS = 24;
    private static final double NETWORK_ANCHOR_CORRIDOR_DISTANCE = 32.0D;
    private static final double BRIDGE_ANCHOR_CORRIDOR_DISTANCE = 20.0D;

    private RoadAutoRouteService() {
    }

    public static boolean canCreateAutoRoute(Level level, DockBlockEntity startDock, DockBlockEntity endDock) {
        if (!(startDock instanceof PostStationBlockEntity) || !(endDock instanceof PostStationBlockEntity)) {
            return false;
        }
        if (level == null || startDock == null || endDock == null) {
            return false;
        }
        String startNationId = startDock.getNationId();
        String endNationId = endDock.getNationId();
        if (startNationId.equals(endNationId) && !startNationId.isBlank()) {
            return true;
        }
        if (startNationId.isBlank() || endNationId.isBlank()) {
            return false;
        }
        NationSavedData data = NationSavedData.get(level);
        NationDiplomacyRecord relation = data.getDiplomacy(startNationId, endNationId);
        if (relation == null) {
            return false;
        }
        return relation.statusId().equals(NationDiplomacyStatus.ALLIED.id())
                || relation.statusId().equals(NationDiplomacyStatus.TRADE.id());
    }

    public static List<BlockPos> findRoadRoute(ServerLevel level, BlockPos start, BlockPos end) {
        if (level == null || start == null || end == null) {
            return List.of();
        }
        Graph graph = buildGraph(level);
        if (graph.adjacency().isEmpty()) {
            return List.of();
        }

        BlockPos startRoad = nearestRoadNode(start, graph.nodes(), STATION_CONNECT_RADIUS);
        BlockPos endRoad = nearestRoadNode(end, graph.nodes(), STATION_CONNECT_RADIUS);
        if (startRoad == null || endRoad == null) {
            return List.of();
        }

        List<BlockPos> roadPath = dijkstra(startRoad, endRoad, graph.adjacency());
        if (roadPath.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        out.add(start.immutable());
        out.addAll(roadPath);
        out.add(end.immutable());
        return new ArrayList<>(out);
    }

    public static RouteResolution resolveAutoRoute(ServerLevel level, DockBlockEntity startDock, DockBlockEntity endDock) {
        if (!canCreateAutoRoute(level, startDock, endDock)) {
            return RouteResolution.none();
        }
        return resolveAutoRoute(level, startDock.getBlockPos(), endDock.getBlockPos());
    }

    public static RouteResolution resolveAutoRoutePreview(ServerLevel level, BlockPos start, BlockPos end) {
        return resolveAutoRoute(level, start, end);
    }

    public static CompletableFuture<RouteResolution> resolveAutoRouteAsync(ServerLevel level,
                                                                           BlockPos start,
                                                                           BlockPos end,
                                                                           Consumer<RouteResolution> apply) {
        if (level == null || start == null || end == null) {
            RouteResolution resolution = RouteResolution.none();
            if (apply != null) {
                apply.accept(resolution);
            }
            return CompletableFuture.completedFuture(resolution);
        }
        RoadPlanningTaskService taskService = RoadPlanningTaskService.get();
        if (taskService == null) {
            RouteResolution resolution = resolveAutoRoute(level, start, end);
            if (apply != null) {
                apply.accept(resolution);
            }
            return CompletableFuture.completedFuture(resolution);
        }
        return taskService.submitLatest(
                new RoadPlanningTaskService.TaskKey("auto-route", start.asLong() + "|" + end.asLong()),
                () -> resolveAutoRoute(level, start, end),
                resolution -> {
                    if (apply != null) {
                        apply.accept(resolution);
                    }
                }
        );
    }

    public static boolean canResolveAutoRoute(ServerLevel level, DockBlockEntity startDock, DockBlockEntity endDock) {
        return resolveAutoRoute(level, startDock, endDock).found();
    }

    public static boolean createAndSaveAutoRoute(ServerLevel level, DockBlockEntity startDock, DockBlockEntity endDock) {
        RouteResolution resolution = resolveAutoRoute(level, startDock, endDock);
        if (!resolution.found()) {
            return false;
        }
        List<BlockPos> path = resolution.path();
        List<Vec3> waypoints = new ArrayList<>(path.size());
        for (BlockPos pos : path) {
            waypoints.add(new Vec3(pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D));
        }

        double routeLength = 0.0D;
        for (int i = 1; i < waypoints.size(); i++) {
            routeLength += waypoints.get(i - 1).distanceTo(waypoints.get(i));
        }

        String routeName = "Road Auto: " + endDock.getDockName();
        RouteDefinition route = new RouteDefinition(
                routeName,
                waypoints,
                "System",
                "",
                System.currentTimeMillis(),
                routeLength,
                startDock.getDockName(),
                endDock.getDockName()
        );
        List<RouteDefinition> routes = new ArrayList<>(startDock.getRoutesForMap());
        routes.add(route);
        startDock.setRoutes(routes, routes.size() - 1);
        return true;
    }

    public static List<RouteDefinition> buildRoadNetworkRoutes(ServerLevel level, PostStationBlockEntity startDock) {
        if (level == null || startDock == null) {
            return List.of();
        }
        List<RouteDefinition> generated = new ArrayList<>();
        for (BlockPos stationPos : PostStationRegistry.get(level)) {
            if (stationPos == null || stationPos.equals(startDock.getBlockPos())) {
                continue;
            }
            if (!(level.getBlockEntity(stationPos) instanceof PostStationBlockEntity targetDock)) {
                continue;
            }
            if (!canCreateAutoRoute(level, startDock, targetDock)) {
                continue;
            }
            List<BlockPos> path = findRoadRoute(level, startDock.getBlockPos(), targetDock.getBlockPos());
            if (path.size() < 2) {
                continue;
            }
            generated.add(routeDefinitionFromPath(startDock, targetDock, path));
        }
        generated.sort(Comparator.comparing(RouteDefinition::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(generated);
    }

    static List<RouteDefinition> mergeRoutesForTest(List<RouteDefinition> storedRoutes, List<RouteDefinition> generatedRoutes) {
        return mergeRoutes(storedRoutes, generatedRoutes);
    }

    static RouteResolution preferResolutionForTest(RouteResolution direct, RouteResolution hybrid) {
        return preferResolution(direct, hybrid);
    }

    public static List<RouteDefinition> mergeRoutes(List<RouteDefinition> storedRoutes, List<RouteDefinition> generatedRoutes) {
        List<RouteDefinition> merged = new ArrayList<>();
        for (RouteDefinition route : storedRoutes == null ? List.<RouteDefinition>of() : storedRoutes) {
            if (route != null) {
                merged.add(route.copy());
            }
        }
        for (RouteDefinition route : generatedRoutes == null ? List.<RouteDefinition>of() : generatedRoutes) {
            if (route == null || containsRouteShape(merged, route)) {
                continue;
            }
            merged.add(route.copy());
        }
        return List.copyOf(merged);
    }

    private static RouteResolution resolveAutoRoute(ServerLevel level, BlockPos start, BlockPos end) {
        if (level == null || start == null || end == null) {
            return RouteResolution.none();
        }

        Graph graph = buildGraph(level);
        SegmentedRoadPathOrchestrator.OrchestratedPath orchestrated = SegmentedRoadPathOrchestrator.plan(
                start,
                end,
                collectSegmentAnchors(level, start, end, graph.nodes()),
                request -> new SegmentedRoadPathOrchestrator.SegmentPlan(
                        resolveHybridSegment(level, request.from(), request.to(), graph.nodes(), graph.adjacency()),
                        SegmentedRoadPathOrchestrator.FailureReason.SEARCH_EXHAUSTED
                ),
                request -> shouldSubdivideSegment(request.from(), request.to())
        );
        RouteResolution hybrid = orchestrated.success()
                ? new RouteResolution(
                        usesExistingNetwork(orchestrated.path(), graph.nodes()) ? PathSource.ROAD_NETWORK : PathSource.LAND_TERRAIN,
                        orchestrated.path()
                )
                : RouteResolution.none();
        RouteResolution direct = new RouteResolution(PathSource.LAND_TERRAIN, findLandRoute(level, start, end));
        return preferResolution(direct, hybrid);
    }

    private static List<BlockPos> resolveHybridSegment(ServerLevel level,
                                                       BlockPos start,
                                                       BlockPos end,
                                                       Set<BlockPos> networkNodes,
                                                       Map<BlockPos, Set<BlockPos>> adjacency) {
        RoadHybridRouteResolver.HybridRoute route = RoadHybridRouteResolver.resolveCandidates(
                List.of(start),
                List.of(end),
                networkNodes,
                adjacency,
                (from, to, allowWaterFallback) -> {
                    List<BlockPos> path = combineRouteEndpoints(from, findPathWithSnapshot(level, from, to, allowWaterFallback), to);
                    return RoadHybridRouteResolver.summarizePath(level, path, allowWaterFallback);
                }
        );
        return route.kind() == RoadHybridRouteResolver.ResolutionKind.NONE || route.fullPath().size() < 2
                ? List.of()
                : route.fullPath();
    }

    private static List<BlockPos> collectSegmentAnchors(ServerLevel level,
                                                        BlockPos start,
                                                        BlockPos end,
                                                        Set<BlockPos> networkNodes) {
        LinkedHashSet<BlockPos> merged = new LinkedHashSet<>();
        merged.addAll(SegmentedRoadPathOrchestrator.collectIntermediateAnchors(
                start,
                end,
                networkNodes == null ? List.of() : List.copyOf(networkNodes),
                MAX_SEGMENT_INTERMEDIATE_ANCHORS,
                NETWORK_ANCHOR_CORRIDOR_DISTANCE
        ));
        merged.addAll(SegmentedRoadPathOrchestrator.collectIntermediateAnchors(
                start,
                end,
                collectBridgeDeckAnchorsWithSnapshot(level, start, end),
                Math.max(6, MAX_SEGMENT_INTERMEDIATE_ANCHORS / 2),
                BRIDGE_ANCHOR_CORRIDOR_DISTANCE
        ));
        return List.copyOf(merged);
    }

    private static boolean usesExistingNetwork(List<BlockPos> path, Set<BlockPos> networkNodes) {
        if (path == null || path.isEmpty() || networkNodes == null || networkNodes.isEmpty()) {
            return false;
        }
        for (BlockPos pos : path) {
            if (pos != null && networkNodes.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldSubdivideSegment(BlockPos from, BlockPos to) {
        return from != null && to != null && from.distManhattan(to) > SEGMENT_SUBDIVIDE_MANHATTAN;
    }

    private static RouteDefinition routeDefinitionFromPath(PostStationBlockEntity startDock,
                                                           PostStationBlockEntity endDock,
                                                           List<BlockPos> path) {
        List<Vec3> waypoints = new ArrayList<>(path.size());
        double routeLength = 0.0D;
        Vec3 previous = null;
        for (BlockPos pos : path) {
            Vec3 waypoint = new Vec3(pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D);
            if (previous != null) {
                routeLength += previous.distanceTo(waypoint);
            }
            waypoints.add(waypoint);
            previous = waypoint;
        }
        return new RouteDefinition(
                "Road Link: " + endDock.getDockName(),
                waypoints,
                "System",
                "",
                System.currentTimeMillis(),
                routeLength,
                startDock.getDockName(),
                endDock.getDockName()
        );
    }

    private static boolean containsRouteShape(List<RouteDefinition> routes, RouteDefinition incoming) {
        for (RouteDefinition existing : routes) {
            if (sameRouteShape(existing, incoming)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameRouteShape(RouteDefinition left, RouteDefinition right) {
        if (left == null || right == null || left.waypoints().size() != right.waypoints().size()) {
            return false;
        }
        for (int i = 0; i < left.waypoints().size(); i++) {
            Vec3 a = left.waypoints().get(i);
            Vec3 b = right.waypoints().get(i);
            if (Math.abs(a.x - b.x) > 0.01D || Math.abs(a.y - b.y) > 0.01D || Math.abs(a.z - b.z) > 0.01D) {
                return false;
            }
        }
        return true;
    }

    private static Graph buildGraph(ServerLevel level) {
        List<RoadNetworkRecord> roads = NationSavedData.get(level).getRoadNetworks().stream()
                .filter(road -> road != null
                        && level.dimension().location().toString().equals(road.dimensionId())
                        && road.path().size() >= 2)
                .toList();
        return new Graph(
                RoadHybridRouteResolver.collectNetworkNodes(roads),
                RoadHybridRouteResolver.collectNetworkAdjacency(roads)
        );
    }

    private static BlockPos nearestRoadNode(BlockPos origin, Set<BlockPos> candidates, double maxDistance) {
        BlockPos best = null;
        double bestDistanceSq = maxDistance * maxDistance;
        for (BlockPos candidate : candidates) {
            double distanceSq = origin.distSqr(candidate);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = candidate;
            }
        }
        return best;
    }

    private static List<BlockPos> dijkstra(BlockPos start, BlockPos end, Map<BlockPos, Set<BlockPos>> adjacency) {
        PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator.comparingDouble(RouteNode::score));
        Map<BlockPos, Double> dist = new HashMap<>();
        Map<BlockPos, BlockPos> prev = new HashMap<>();
        open.add(new RouteNode(start, 0.0D));
        dist.put(start, 0.0D);

        while (!open.isEmpty()) {
            RouteNode current = open.poll();
            if (current.pos().equals(end)) {
                break;
            }
            for (BlockPos neighbor : adjacency.getOrDefault(current.pos(), Set.of())) {
                double step = Math.sqrt(current.pos().distSqr(neighbor));
                double candidate = current.score() + step;
                if (candidate >= dist.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    continue;
                }
                dist.put(neighbor, candidate);
                prev.put(neighbor, current.pos());
                open.add(new RouteNode(neighbor, candidate));
            }
        }

        if (!dist.containsKey(end)) {
            return List.of();
        }

        List<BlockPos> path = new ArrayList<>();
        BlockPos cursor = end;
        path.add(cursor);
        while (!cursor.equals(start)) {
            cursor = prev.get(cursor);
            if (cursor == null) {
                return List.of();
            }
            path.add(cursor);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private static List<BlockPos> findLandRoute(ServerLevel level, BlockPos start, BlockPos end) {
        if (level == null || start == null || end == null) {
            return List.of();
        }
        return combineRouteEndpoints(start, findPathWithSnapshot(level, start, end, false), end);
    }

    private static List<BlockPos> findPathWithSnapshot(ServerLevel level,
                                                       BlockPos start,
                                                       BlockPos end,
                                                       boolean allowWaterFallback) {
        if (level == null || start == null || end == null) {
            return List.of();
        }
        RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.build(level, start, end, Set.of(), Set.of());
        return RoadPathfinder.findPath(level, start, end, Set.of(), Set.of(), allowWaterFallback, snapshot);
    }

    private static List<BlockPos> collectBridgeDeckAnchorsWithSnapshot(ServerLevel level,
                                                                       BlockPos start,
                                                                       BlockPos end) {
        if (level == null || start == null || end == null) {
            return List.of();
        }
        RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.build(level, start, end, Set.of(), Set.of());
        return RoadPathfinder.collectBridgeDeckAnchors(level, start, end, Set.of(), snapshot);
    }

    private static RouteResolution preferResolution(RouteResolution direct, RouteResolution hybrid) {
        if (hybrid != null && hybrid.found()) {
            return hybrid;
        }
        return direct != null && direct.found() ? direct : RouteResolution.none();
    }

    private static List<BlockPos> combineRouteEndpoints(BlockPos start, List<BlockPos> path, BlockPos end) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        out.add(start.immutable());
        for (BlockPos pos : path) {
            if (pos != null) {
                out.add(pos.immutable());
            }
        }
        out.add(end.immutable());
        return new ArrayList<>(out);
    }

    private record Graph(Set<BlockPos> nodes, Map<BlockPos, Set<BlockPos>> adjacency) {
    }

    private record RouteNode(BlockPos pos, double score) {
    }

    public enum PathSource {
        NONE,
        ROAD_NETWORK,
        LAND_TERRAIN
    }

    public record RouteResolution(PathSource source, List<BlockPos> path) {
        public boolean found() {
            return source != PathSource.NONE && path != null && path.size() >= 2;
        }

        public static RouteResolution none() {
            return new RouteResolution(PathSource.NONE, List.of());
        }
    }
}
