package com.monpai.sailboatmod.map.route;

import com.monpai.sailboatmod.integration.bluemap.BlueMapMarkerSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.network.packet.maproute.MapRoutePacketCodec;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Converts only published, persisted catalogs; it has no world, inventory or navigation access. */
public final class MapRouteCatalog {
    private static final int MAX_CAPTURE_ROUTES = 4096;
    private static final int MAX_CAPTURE_POINTS = 65536;
    private static final int MAX_SOURCE_PATH_POINTS = 8192;
    static final int MAX_INSPECTED_RECORDS = 8192;
    private static final int SNAPSHOT_BYTES = MapRoutePacketCodec.MAX_PACKET_BYTES - 1024;
    private MapRouteCatalog() { }

    public record Captured(List<MapRouteEntry> routes, boolean truncated) {
        public Captured { routes = List.copyOf(routes); }
    }

    public static List<MapRouteEntry> capture(String dimension, Collection<RoadGraphEdgeRecord> graph,
                                              Collection<RoadNetworkRecord> legacy, List<BlueMapMarkerSavedData.DockSnapshot> ports,
                                              List<StationMapRouteSavedData.StationSnapshot> stations) {
        return captureBounded(dimension, graph, legacy, ports, stations).routes();
    }

    public static Captured captureBounded(String dimension, Collection<RoadGraphEdgeRecord> graph,
                                           Collection<RoadNetworkRecord> legacy, List<BlueMapMarkerSavedData.DockSnapshot> ports,
                                           List<StationMapRouteSavedData.StationSnapshot> stations) {
        Builder result = new Builder();
        Set<UUID> graphIds = new HashSet<>();
        for (RoadGraphEdgeRecord edge : graph) {
            if (!result.inspect()) break;
            if (!dimension.equals(edge.dimensionId())) continue;
            graphIds.add(edge.edgeId()); // A cancelled/building graph record must not reappear via legacy fallback.
            if (!edge.built()) continue;
            List<BlockPos> display = edge.displayPath().size() >= 2 ? edge.displayPath() : edge.centerline();
            List<BlockPos> measured = edge.centerline().size() <= MAX_SOURCE_PATH_POINTS ? edge.centerline() : display;
            if (!result.room(display.size(), measured.size())) continue;
            List<MapRoutePoint> points = blocks(display);
            if (points.size() < 2) continue;
            result.add(new MapRouteEntry("road:" + edge.edgeId(), MapRouteKind.ROAD, text(fallback(edge.roadName(), edge.edgeId().toString())),
                    text(fallback(edge.sourceTownName(), coordinate(points.getFirst()))), text(fallback(edge.targetTownName(), coordinate(points.getLast()))),
                    blockLength(measured), List.of(points)));
        }
        for (RoadNetworkRecord road : legacy) {
            if (!result.inspect()) break;
            if (!dimension.equals(road.dimensionId()) || graphIds.contains(legacyEdgeId(road.roadId()))) continue;
            List<BlockPos> display = road.displayPath().size() >= 2 ? road.displayPath() : road.path();
            List<BlockPos> measured = road.path().size() <= MAX_SOURCE_PATH_POINTS ? road.path() : display;
            if (!result.room(display.size(), measured.size())) continue;
            List<MapRoutePoint> points = blocks(display);
            if (points.size() < 2) continue;
            result.add(new MapRouteEntry("road:" + legacyEdgeId(road.roadId()), MapRouteKind.ROAD, text(road.roadId()),
                    text(fallback(road.routeSourceName(), coordinate(points.getFirst()))), text(fallback(road.routeTargetName(), coordinate(points.getLast()))),
                    blockLength(measured), List.of(points)));
        }
        for (var station : stations) {
            if (!result.inspect()) break;
            if (dimension.equals(station.dimension())) terminal(result, MapRouteKind.LAND, dimension, station.pos(), station.name(), station.routes());
        }
        for (var port : ports) {
            if (!result.inspect()) break;
            if (dimension.equals(port.dimension())) terminal(result, MapRouteKind.SEA, dimension, port.pos(), port.dockName(), port.routes());
        }
        return new Captured(result.entries, result.truncated);
    }

    private static void terminal(Builder result, MapRouteKind kind, String dimension, BlockPos position, String name, List<RouteDefinition> routes) {
        for (int index = 0; index < routes.size(); index++) {
            if (!result.inspect()) break;
            RouteDefinition route = routes.get(index);
            if (!result.room(route.waypoints().size(), route.waypoints().size())) continue;
            var points = new ArrayList<MapRoutePoint>(route.waypoints().size());
            boolean valid = true;
            for (Vec3 point : route.waypoints()) {
                if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) { valid = false; break; }
                points.add(new MapRoutePoint(point.x, point.z));
            }
            if (!valid) { result.truncated = true; continue; }
            if (points.size() < 2) continue;
            double length = route.routeLengthMeters();
            if (!Double.isFinite(length) || length < 0) { result.truncated = true; continue; }
            if (length == 0) for (int i = 1; i < route.waypoints().size(); i++) length += route.waypoints().get(i - 1).distanceTo(route.waypoints().get(i));
            if (!Double.isFinite(length)) { result.truncated = true; continue; }
            result.add(new MapRouteEntry(terminalId(kind, dimension, position, route), kind,
                    text(fallback(route.name(), name)), text(fallback(route.startDockName(), name)),
                    text(fallback(route.endDockName(), coordinate(points.getLast()))), length, List.of(points)));
        }
    }

    public static MapRouteSnapshot viewport(List<MapRouteEntry> catalog, MapRouteViewport viewport) {
        return viewport(new Captured(catalog, false), viewport);
    }

    public static MapRouteSnapshot viewport(Captured catalog, MapRouteViewport viewport) {
        List<MapRouteEntry> candidates = visibleCandidates(catalog.routes(), viewport);
        EnumSet<MapRouteKind> unrepresented = EnumSet.noneOf(MapRouteKind.class);
        candidates.forEach(entry -> unrepresented.add(entry.kind()));
        List<MapRouteEntry> routes = new ArrayList<>();
        int points = 0, parts = 0, bytes = 4;
        boolean truncated = catalog.truncated();
        for (MapRouteEntry entry : roundRobin(candidates)) {
            if (Thread.currentThread().isInterrupted()) return new MapRouteSnapshot(routes, true);
            int reservedKinds = unrepresented.size() - (unrepresented.contains(entry.kind()) ? 1 : 0);
            int pointLimit = MapRouteSnapshot.MAX_POINTS - reservedKinds * 2;
            int partLimit = MapRouteSnapshot.MAX_PARTS - reservedKinds;
            List<List<MapRoutePoint>> visible = new ArrayList<>();
            int routePoints = 0;
            for (List<MapRoutePoint> path : entry.paths()) {
                if (routes.size() >= MapRouteSnapshot.MAX_ROUTES || parts + visible.size() >= partLimit) { truncated = true; continue; }
                int room = pointLimit - points - routePoints;
                if (room < 2) { truncated = true; continue; }
                if (path.size() > room) { path = List.copyOf(path.subList(0, room)); truncated = true; }
                visible.add(path);
                routePoints += path.size();
            }
            if (visible.isEmpty()) continue;
            var clipped = new MapRouteEntry(entry.id(), entry.kind(), entry.name(), entry.fromName(), entry.toName(), entry.lengthBlocks(), visible);
            int encoded = MapRoutePacketCodec.entryBytes(clipped);
            if (bytes + encoded > SNAPSHOT_BYTES) { truncated = true; continue; }
            routes.add(clipped); bytes += encoded; points += routePoints; parts += visible.size();
            unrepresented.remove(entry.kind());
        }
        return new MapRouteSnapshot(routes, truncated);
    }

    private static List<MapRouteEntry> visibleCandidates(List<MapRouteEntry> catalog, MapRouteViewport viewport) {
        double tolerance = 0.75 / viewport.pixelsPerBlock();
        if (!Double.isFinite(tolerance)) tolerance = Double.MAX_VALUE;
        List<MapRouteEntry> candidates = new ArrayList<>();
        for (MapRouteEntry entry : catalog) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            List<List<MapRoutePoint>> visible = new ArrayList<>();
            for (List<MapRoutePoint> path : entry.paths()) for (List<MapRoutePoint> piece : MapRouteGeometry.clip(path, viewport)) {
                List<MapRoutePoint> simplified = MapRouteGeometry.simplify(piece, tolerance);
                if (simplified.size() >= 2) visible.add(simplified);
            }
            if (!visible.isEmpty()) candidates.add(new MapRouteEntry(entry.id(), entry.kind(), entry.name(), entry.fromName(), entry.toName(), entry.lengthBlocks(), visible));
        }
        return candidates;
    }

    /** A crowded road layer must not hide the presence of the other published route categories. */
    private static List<MapRouteEntry> roundRobin(List<MapRouteEntry> routes) {
        EnumMap<MapRouteKind, List<MapRouteEntry>> groups = new EnumMap<>(MapRouteKind.class);
        for (MapRouteKind kind : MapRouteKind.values()) groups.put(kind, new ArrayList<>());
        for (MapRouteEntry route : routes) groups.get(route.kind()).add(route);
        List<MapRouteEntry> result = new ArrayList<>(routes.size());
        for (int index = 0; result.size() < routes.size(); index++) for (MapRouteKind kind : MapRouteKind.values()) {
            List<MapRouteEntry> group = groups.get(kind);
            if (index < group.size()) result.add(group.get(index));
        }
        return result;
    }

    private static UUID legacyEdgeId(String roadId) {
        return UUID.nameUUIDFromBytes(("road-graph-edge:" + roadId.trim().toLowerCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }
    private static String terminalId(MapRouteKind kind, String dimension, BlockPos position, RouteDefinition route) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            ByteBuffer fieldLength = ByteBuffer.allocate(4);
            for (String field : List.of(kind.name(), dimension, position.toShortString(), route.name(), route.startDockName(), route.endDockName())) {
                byte[] encoded = text(field).getBytes(StandardCharsets.UTF_8);
                fieldLength.clear(); fieldLength.putInt(encoded.length); hash.update(fieldLength.array()); hash.update(encoded);
            }
            ByteBuffer coordinate = ByteBuffer.allocate(24);
            for (Vec3 point : route.waypoints()) {
                coordinate.clear(); coordinate.putDouble(point.x).putDouble(point.y).putDouble(point.z); hash.update(coordinate.array());
            }
            return kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + UUID.nameUUIDFromBytes(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Missing standard SHA-256", impossible); }
    }
    private static List<MapRoutePoint> blocks(List<BlockPos> positions) {
        return positions.stream().map(pos -> new MapRoutePoint(pos.getX() + .5, pos.getZ() + .5)).toList();
    }
    private static double blockLength(List<BlockPos> positions) {
        double length = 0;
        for (int i = 1; i < positions.size(); i++) {
            BlockPos a = positions.get(i - 1), b = positions.get(i);
            length += Math.sqrt(a.distSqr(b));
        }
        return length;
    }
    private static String coordinate(MapRoutePoint point) { return Math.round(point.x()) + ", " + Math.round(point.z()); }
    private static String fallback(String preferred, String other) { return preferred == null || preferred.isBlank() ? other : preferred; }
    private static String text(String value) {
        if (value == null) return "";
        int end = Math.min(value.length(), MapRoutePacketCodec.MAX_TEXT_LENGTH);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
    private static final class Builder {
        final List<MapRouteEntry> entries = new ArrayList<>();
        int points;
        int sourcePoints;
        int inspected;
        boolean truncated;
        boolean inspect() {
            if (inspected++ >= MAX_INSPECTED_RECORDS) { truncated = true; return false; }
            return true;
        }
        boolean room(int count, int originalCount) {
            if (entries.size() >= MAX_CAPTURE_ROUTES || count > MAX_SOURCE_PATH_POINTS || originalCount > MAX_SOURCE_PATH_POINTS
                    || count > MAX_CAPTURE_POINTS - points || Math.max(count, originalCount) > MAX_CAPTURE_POINTS - sourcePoints) { truncated = true; return false; }
            sourcePoints += Math.max(count, originalCount);
            return true;
        }
        void add(MapRouteEntry route) { entries.add(route); points += route.paths().stream().mapToInt(List::size).sum(); }
    }
}
