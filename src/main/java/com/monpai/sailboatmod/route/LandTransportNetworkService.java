package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.DockTownResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public final class LandTransportNetworkService {
    public static final double POST_STATION_SPEED_MPS = 5.0D;

    private static final int PASS_THROUGH_STATION_MAX_DISTANCE_SQ = 9;

    private final StationSource stationSource;
    private final RouteResolver routeResolver;

    public LandTransportNetworkService() {
        this(LandTransportNetworkService::collectServerStations, LandTransportNetworkService::resolveServerRoute);
    }

    private LandTransportNetworkService(StationSource stationSource, RouteResolver routeResolver) {
        this.stationSource = stationSource;
        this.routeResolver = routeResolver;
    }

    public static LandTransportNetworkService forTests(Supplier<List<StationRef>> stations, RouteResolver routeResolver) {
        return new LandTransportNetworkService(ignored -> stations.get(), routeResolver);
    }

    public List<ReachableTown> reachableTowns(Object level, @Nullable StationRef source, boolean allowTerrainFallback) {
        if (source == null || source.townId().isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, ReachableTown> bestByTown = new LinkedHashMap<>();
        for (StationRef target : stationSource.stations(level)) {
            if (target == null || target.townId().isBlank() || target.townId().equals(source.townId())) {
                continue;
            }
            RouteAvailability availability = routeResolver.resolve(source, target, allowTerrainFallback);
            if (!availability.reachable() || availability.plan() == null) {
                continue;
            }
            LandRoutePlan plan = availability.plan();
            ReachableTown candidate = new ReachableTown(
                    target.townId(),
                    target.townName().isBlank() ? target.townId() : target.townName(),
                    target.pos(),
                    target.stationName(),
                    plan.distanceMeters(),
                    estimateEtaSeconds(plan.distanceMeters()),
                    plan.source(),
                    plan.passThroughTownNames(),
                    plan.route().name()
            );
            ReachableTown existing = bestByTown.get(candidate.townId());
            if (existing == null || candidate.distanceMeters() < existing.distanceMeters()) {
                bestByTown.put(candidate.townId(), candidate);
            }
        }
        return bestByTown.values().stream()
                .sorted(Comparator.comparingInt(ReachableTown::distanceMeters)
                        .thenComparing(ReachableTown::townName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public RouteAvailability planRouteToTown(Object level, @Nullable StationRef source, String targetTownId, boolean allowTerrainFallback) {
        String normalizedTargetTown = normalize(targetTownId);
        if (source == null || source.townId().isBlank()) {
            return RouteAvailability.unavailable(RouteFailureReason.MISSING_STATION);
        }
        if (normalizedTargetTown.isBlank()) {
            return RouteAvailability.unavailable(RouteFailureReason.MISSING_TOWN);
        }
        LandRoutePlan best = null;
        for (StationRef target : stationSource.stations(level)) {
            if (target == null || !normalizedTargetTown.equals(target.townId())) {
                continue;
            }
            RouteAvailability availability = routeResolver.resolve(source, target, allowTerrainFallback);
            if (!availability.reachable() || availability.plan() == null) {
                continue;
            }
            if (best == null || availability.plan().distanceMeters() < best.distanceMeters()) {
                best = availability.plan();
            }
        }
        return best == null
                ? RouteAvailability.unavailable(RouteFailureReason.NO_ROAD_ROUTE)
                : RouteAvailability.reachable(best);
    }

    public RouteAvailability planRouteBetweenStations(Object level,
                                                      @Nullable StationRef source,
                                                      @Nullable StationRef target,
                                                      boolean allowTerrainFallback) {
        if (source == null || target == null) {
            return RouteAvailability.unavailable(RouteFailureReason.MISSING_STATION);
        }
        return routeResolver.resolve(source, target, allowTerrainFallback);
    }

    @Nullable
    public StationRef stationRef(Level level, PostStationBlockEntity station) {
        if (level == null || station == null) {
            return null;
        }
        String townId = DockTownResolver.resolveTownForArrival(level, station.getBlockPos(), station.getTownId());
        TownRecord town = townId.isBlank() ? null : NationSavedData.get(level).getTown(townId);
        String townName = town == null ? townId : town.name();
        return new StationRef(
                DockTownResolver.dockId(level, station.getBlockPos()),
                townId,
                townName,
                station.getBlockPos().immutable(),
                station.getDockName(),
                level
        );
    }

    public static RouteDefinition routeDefinition(String name,
                                                  @Nullable StationRef source,
                                                  @Nullable StationRef target,
                                                  List<Vec3> waypoints,
                                                  String author,
                                                  RouteSource sourceKind) {
        return new RouteDefinition(
                name,
                waypoints,
                author == null ? "System" : author,
                "",
                System.currentTimeMillis(),
                routeLength(waypoints),
                source == null ? "" : source.stationName(),
                target == null ? "" : target.stationName()
        );
    }

    public static int estimateEtaSeconds(double distanceMeters) {
        return distanceMeters <= 0.0D ? 0 : Math.max(1, (int) Math.ceil(distanceMeters / POST_STATION_SPEED_MPS));
    }

    public static double routeLength(List<Vec3> waypoints) {
        if (waypoints == null || waypoints.size() < 2) {
            return 0.0D;
        }
        double length = 0.0D;
        Vec3 previous = null;
        for (Vec3 waypoint : waypoints) {
            if (waypoint == null) {
                continue;
            }
            if (previous != null) {
                length += previous.distanceTo(waypoint);
            }
            previous = waypoint;
        }
        return length;
    }

    private static List<StationRef> collectServerStations(Object rawLevel) {
        if (!(rawLevel instanceof ServerLevel level)) {
            return List.of();
        }
        LandTransportNetworkService service = new LandTransportNetworkService();
        List<StationRef> refs = new ArrayList<>();
        for (BlockPos pos : PostStationRegistry.get(level)) {
            if (level.getBlockEntity(pos) instanceof PostStationBlockEntity station) {
                StationRef ref = service.stationRef(level, station);
                if (ref != null && !ref.townId().isBlank()) {
                    refs.add(ref);
                }
            }
        }
        return refs;
    }

    private static RouteAvailability resolveServerRoute(StationRef source, StationRef target, boolean allowTerrainFallback) {
        if (!(source.level() instanceof ServerLevel level)) {
            return RouteAvailability.unavailable(RouteFailureReason.MISSING_LEVEL);
        }
        if (!(level.getBlockEntity(source.pos()) instanceof PostStationBlockEntity sourceStation)
                || !(level.getBlockEntity(target.pos()) instanceof PostStationBlockEntity targetStation)) {
            return RouteAvailability.unavailable(RouteFailureReason.MISSING_STATION);
        }
        if (!RoadAutoRouteService.canCreateAutoRoute(level, sourceStation, targetStation)) {
            return RouteAvailability.unavailable(RouteFailureReason.NO_PERMISSION);
        }
        RoadAutoRouteService.RouteResolution resolution =
                RoadAutoRouteService.resolveAutoRoutePreview(level, source.pos(), target.pos());
        if (!resolution.found()) {
            return RouteAvailability.unavailable(RouteFailureReason.NO_ROAD_ROUTE);
        }
        if (!allowTerrainFallback && resolution.source() == RoadAutoRouteService.PathSource.LAND_TERRAIN) {
            return RouteAvailability.unavailable(RouteFailureReason.NO_ROAD_ROUTE);
        }
        List<Vec3> waypoints = resolution.path().stream()
                .filter(Objects::nonNull)
                .map(pos -> new Vec3(pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D))
                .toList();
        waypoints = withFinalArrivalWaypoint(waypoints, targetStation.selectCarriageArrivalParkingPoint(source.pos()));
        RouteSource sourceKind = resolution.source() == RoadAutoRouteService.PathSource.ROAD_NETWORK
                ? RouteSource.ROAD_GRAPH
                : RouteSource.TERRAIN_FALLBACK;
        return RouteAvailability.reachable(LandRoutePlan.fromWaypoints(
                source,
                target,
                stationsOnPath(level, source, target, resolution.path()),
                waypoints,
                "System",
                sourceKind
        ));
    }

    static List<Vec3> withFinalArrivalWaypoint(List<Vec3> waypoints, Vec3 arrivalParkingPoint) {
        List<Vec3> adjusted = new ArrayList<>();
        if (waypoints != null) {
            for (Vec3 waypoint : waypoints) {
                if (waypoint != null) {
                    adjusted.add(waypoint);
                }
            }
        }
        if (arrivalParkingPoint != null) {
            if (adjusted.isEmpty()) {
                adjusted.add(arrivalParkingPoint);
            } else {
                adjusted.set(adjusted.size() - 1, arrivalParkingPoint);
            }
        }
        return List.copyOf(adjusted);
    }

    private static List<StationRef> stationsOnPath(ServerLevel level, StationRef source, StationRef target, List<BlockPos> path) {
        List<StationRef> stations = new ArrayList<>();
        stations.add(source);
        if (path != null && path.size() > 2) {
            List<StationRef> allStations = collectServerStations(level);
            for (BlockPos pathPos : path) {
                for (StationRef candidate : allStations) {
                    if (candidate == null
                            || candidate.townId().equals(source.townId())
                            || candidate.townId().equals(target.townId())
                            || containsStation(stations, candidate)
                            || candidate.pos().distSqr(pathPos) > PASS_THROUGH_STATION_MAX_DISTANCE_SQ) {
                        continue;
                    }
                    stations.add(candidate);
                }
            }
        }
        stations.add(target);
        return List.copyOf(stations);
    }

    private static boolean containsStation(List<StationRef> stations, StationRef candidate) {
        for (StationRef station : stations) {
            if (station.stationId().equals(candidate.stationId())) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @FunctionalInterface
    public interface StationSource {
        List<StationRef> stations(Object level);
    }

    @FunctionalInterface
    public interface RouteResolver {
        RouteAvailability resolve(StationRef source, StationRef target, boolean allowTerrainFallback);
    }

    public record TestLevelRef(String dimensionId) {
        public TestLevelRef {
            dimensionId = clean(dimensionId);
        }
    }

    public record StationRef(String stationId,
                             String townId,
                             String townName,
                             BlockPos pos,
                             String stationName,
                             @Nullable Object level) {
        public StationRef(String stationId, String townId, String townName, BlockPos pos, String stationName) {
            this(stationId, townId, townName, pos, stationName, null);
        }

        public StationRef {
            stationId = clean(stationId);
            townId = normalize(townId);
            townName = clean(townName);
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
            stationName = clean(stationName);
        }
    }

    public record ReachableTown(String townId,
                                String townName,
                                BlockPos targetStationPos,
                                String targetStationName,
                                int distanceMeters,
                                int etaSeconds,
                                RouteSource source,
                                List<String> passThroughTownNames,
                                String routeName) {
        public ReachableTown {
            townId = normalize(townId);
            townName = clean(townName);
            targetStationPos = targetStationPos == null ? BlockPos.ZERO : targetStationPos.immutable();
            targetStationName = clean(targetStationName);
            distanceMeters = Math.max(0, distanceMeters);
            etaSeconds = Math.max(0, etaSeconds);
            source = source == null ? RouteSource.UNKNOWN : source;
            passThroughTownNames = passThroughTownNames == null ? List.of() : List.copyOf(passThroughTownNames);
            routeName = clean(routeName);
        }
    }

    public record RouteAvailability(boolean reachable,
                                    @Nullable LandRoutePlan plan,
                                    RouteFailureReason failureReason) {
        public RouteAvailability {
            failureReason = failureReason == null ? RouteFailureReason.NONE : failureReason;
        }

        public static RouteAvailability reachable(LandRoutePlan plan) {
            return new RouteAvailability(plan != null, plan, plan == null ? RouteFailureReason.NO_ROAD_ROUTE : RouteFailureReason.NONE);
        }

        public static RouteAvailability unavailable(RouteFailureReason reason) {
            return new RouteAvailability(false, null, reason);
        }
    }

    public record LandRoutePlan(StationRef sourceStation,
                                StationRef targetStation,
                                String targetTownId,
                                BlockPos targetStationPos,
                                RouteDefinition route,
                                int distanceMeters,
                                int etaSeconds,
                                RouteSource source,
                                List<String> passThroughTownNames,
                                List<StationRef> stationPath) {
        public LandRoutePlan {
            targetTownId = normalize(targetTownId);
            targetStationPos = targetStationPos == null ? BlockPos.ZERO : targetStationPos.immutable();
            route = route == null ? new RouteDefinition("", List.of()) : route.copy();
            distanceMeters = Math.max(0, distanceMeters);
            etaSeconds = Math.max(0, etaSeconds);
            source = source == null ? RouteSource.UNKNOWN : source;
            passThroughTownNames = passThroughTownNames == null ? List.of() : List.copyOf(passThroughTownNames);
            stationPath = stationPath == null ? List.of() : List.copyOf(stationPath);
        }

        public static LandRoutePlan fromWaypoints(StationRef source,
                                                  StationRef target,
                                                  List<StationRef> stationPath,
                                                  List<Vec3> waypoints,
                                                  String author,
                                                  RouteSource sourceKind) {
            List<StationRef> safePath = stationPath == null || stationPath.isEmpty()
                    ? List.of(source, target)
                    : stationPath.stream().filter(Objects::nonNull).toList();
            double distance = routeLength(waypoints);
            List<String> passThrough = passThroughTownNames(source, target, safePath);
            String routeName = routeName(source, target);
            RouteDefinition route = routeDefinition(routeName, source, target, waypoints, author, sourceKind);
            int roundedDistance = (int) Math.round(distance);
            return new LandRoutePlan(
                    source,
                    target,
                    target == null ? "" : target.townId(),
                    target == null ? BlockPos.ZERO : target.pos(),
                    route,
                    roundedDistance,
                    estimateEtaSeconds(distance),
                    sourceKind,
                    passThrough,
                    safePath
            );
        }

        private static List<String> passThroughTownNames(StationRef source, StationRef target, List<StationRef> stationPath) {
            if (stationPath.size() <= 2) {
                return List.of();
            }
            String sourceTown = source == null ? "" : source.townId();
            String targetTown = target == null ? "" : target.townId();
            List<String> names = new ArrayList<>();
            String previousTown = "";
            for (int i = 1; i < stationPath.size() - 1; i++) {
                StationRef station = stationPath.get(i);
                String townId = station.townId();
                if (townId.isBlank()
                        || townId.equals(sourceTown)
                        || townId.equals(targetTown)
                        || townId.equals(previousTown)) {
                    continue;
                }
                names.add(station.townName().isBlank() ? townId : station.townName());
                previousTown = townId;
            }
            return List.copyOf(names);
        }

        private static String routeName(StationRef source, StationRef target) {
            String sourceName = source == null ? "" : source.townName();
            String targetName = target == null ? "" : target.townName();
            if (sourceName.isBlank()) {
                sourceName = source == null ? "" : source.stationName();
            }
            if (targetName.isBlank()) {
                targetName = target == null ? "" : target.stationName();
            }
            if (sourceName.isBlank() || targetName.isBlank()) {
                return "Land Route";
            }
            return sourceName + " -> " + targetName;
        }
    }

    public enum RouteSource {
        UNKNOWN,
        ROAD_GRAPH,
        TERRAIN_FALLBACK,
        SAVED_ROUTE
    }

    public enum RouteFailureReason {
        NONE,
        MISSING_LEVEL,
        MISSING_STATION,
        MISSING_TOWN,
        NO_PERMISSION,
        NO_ROAD_ROUTE
    }
}
