package com.monpai.sailboatmod.compat.create;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.track.TrackMaterial.TrackType;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/** Bounded lookahead from a scheduled station using Create's directed track geometry and train gauge. */
final class CreateRailRouteEstimate {
    private static final int MAX_EDGE_WORK_PER_LEG = 4096;
    private static final int MAX_EDGE_WORK_PER_TICK = 16384;
    private static final int MAX_CACHED_LEGS = 256;
    private static final int CACHE_TICKS = 20;
    private static final double EDGE_EPSILON = 1e-6;
    private static final Leg UNKNOWN = new Leg(Status.UNKNOWN, 0);
    private static final Leg BLOCKED = new Leg(Status.BLOCKED, 0);
    private static final StationEdgeAccess STATION_EDGES = StationEdgeAccess.bind();

    private final Map<LegKey, CachedLeg> legs = new LinkedHashMap<>(MAX_CACHED_LEGS, 0.75f, true);
    private WeakReference<MinecraftServer> server = new WeakReference<>(null);
    private int tick;
    private int tickWork;

    Leg between(MinecraftServer server, Train train, GlobalStation from, GlobalStation to) {
        int now = server.getTickCount();
        if (this.server.get() != server) {
            legs.clear();
            this.server = new WeakReference<>(server);
            tickWork = 0;
        }
        if (tick != now) {
            tick = now;
            tickWork = 0;
        }
        Set<TrackType> types = trackTypes(train);
        if (types.isEmpty()) return BLOCKED;
        double throttle = Math.abs(train.throttle);
        double straightSpeed = train.maxSpeed() * throttle;
        double turnSpeed = Math.min(straightSpeed, train.maxTurnSpeed());
        double acceleration = train.acceleration();
        if (!positive(straightSpeed) || !positive(turnSpeed) || !positive(acceleration)) return UNKNOWN;
        TrackGraph graph = train.graph;
        LegKey key = new LegKey(graph.id, graph.getNodes().size(), from.getId(), to.getId(), types,
                straightSpeed, turnSpeed, acceleration);
        CachedLeg cached = legs.get(key);
        if (cached != null && now - cached.tick() < CACHE_TICKS) return cached.leg();
        Leg leg = search(graph, from, to, types, straightSpeed, turnSpeed, acceleration);
        if (leg.status() != Status.UNKNOWN) {
            legs.put(key, new CachedLeg(now, leg));
            if (legs.size() > MAX_CACHED_LEGS) legs.remove(legs.keySet().iterator().next());
        }
        return leg;
    }

    private Leg search(TrackGraph graph, GlobalStation from, GlobalStation to, Set<TrackType> types,
                       double straightSpeed, double turnSpeed, double acceleration) {
        if (STATION_EDGES == null || from.assembling || to.assembling) return UNKNOWN;
        TrackNode first = STATION_EDGES.node(graph, from, true);
        TrackNode second = STATION_EDGES.node(graph, from, false);
        if (first == null || second == null) return UNKNOWN;
        TrackEdge forward = graph.getConnectionsFrom(first).get(second);
        TrackEdge reverse = graph.getConnectionsFrom(second).get(first);
        if (forward == null || reverse == null) return UNKNOWN;

        PriorityQueue<Frontier> frontier = new PriorityQueue<>(Comparator.comparingDouble(Frontier::elapsed));
        frontier.add(new Frontier(forward, from.getLocationOn(forward), 0));
        frontier.add(new Frontier(reverse, from.getLocationOn(reverse), 0));
        Set<TrackEdge> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        double best = Double.POSITIVE_INFINITY;
        int work = 0;
        while (!frontier.isEmpty()) {
            Frontier head = frontier.remove();
            if (head.elapsed() >= best) break;
            // A seed starts inside an edge. A later loop may still need its full directed edge.
            if (head.offset() == 0 && !seen.add(head.edge())) continue;
            if (++work > MAX_EDGE_WORK_PER_LEG || ++tickWork > MAX_EDGE_WORK_PER_TICK) return UNKNOWN;
            TrackEdge edge = head.edge();
            double length = edge.getLength();
            double speed = edge.isTurn() ? turnSpeed : straightSpeed;
            if (!Double.isFinite(length) || !Double.isFinite(head.offset()) || head.offset() < -EDGE_EPSILON
                    || head.offset() > length + EDGE_EPSILON) return UNKNOWN;
            double availableUntil = length;
            double targetOffset = Double.POSITIVE_INFINITY;
            for (TrackEdgePoint point : edge.getEdgeData().getPoints()) {
                if (++work > MAX_EDGE_WORK_PER_LEG || ++tickWork > MAX_EDGE_WORK_PER_TICK) return UNKNOWN;
                double offset = point.getLocationOn(edge);
                if (offset + EDGE_EPSILON < head.offset()) continue;
                if (point != from && !point.canNavigateVia(edge.node2)) {
                    availableUntil = Math.min(availableUntil, offset);
                }
                if (point == to && to.canApproachFrom(edge.node2)) targetOffset = offset;
            }
            if (targetOffset <= availableUntil && Double.isFinite(targetOffset)) {
                double arrival = head.elapsed() + Math.max(0, targetOffset - head.offset()) / speed;
                best = Math.min(best, arrival);
            }
            if (availableUntil < length) continue;
            double elapsed = head.elapsed() + Math.max(0, length - head.offset()) / speed;
            for (TrackEdge next : graph.getConnectionsFrom(edge.node2).values()) {
                if (++work > MAX_EDGE_WORK_PER_LEG || ++tickWork > MAX_EDGE_WORK_PER_TICK) return UNKNOWN;
                if (next.node2 != edge.node1 && !seen.contains(next) && edge.canTravelTo(next)
                        && types.contains(next.getTrackMaterial().trackType)) {
                    frontier.add(new Frontier(next, 0, elapsed));
                }
            }
        }
        if (!Double.isFinite(best)) return BLOCKED;
        // Estimate a standing start and stop. Short legs reach a lower peak speed.
        double ramp = straightSpeed / acceleration;
        double duration = best < ramp ? 2 * Math.sqrt(best * ramp) : best + ramp;
        return new Leg(Status.OPEN, Math.max(1, (long) Math.ceil(duration)));
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static Set<TrackType> trackTypes(Train train) {
        Set<TrackType> types = new HashSet<>(bogeyTypes(train.carriages.getFirst().leadingBogey()));
        for (Carriage carriage : train.carriages) {
            types.retainAll(bogeyTypes(carriage.leadingBogey()));
            if (carriage.isOnTwoBogeys()) types.retainAll(bogeyTypes(carriage.trailingBogey()));
        }
        return types;
    }

    private static Set<TrackType> bogeyTypes(CarriageBogey bogey) {
        var style = bogey.getStyle();
        var block = style.getBlockForSize(bogey.getSize());
        return block == null ? Set.of() : block.getValidPathfindingTypes(style);
    }

    enum Status { OPEN, BLOCKED, UNKNOWN }
    record Leg(Status status, long ticks) {}
    private record Frontier(TrackEdge edge, double offset, double elapsed) {}
    private record LegKey(UUID graph, int nodes, UUID from, UUID to, Set<TrackType> types,
                          double straightSpeed, double turnSpeed, double acceleration) {}
    private record CachedLeg(int tick, Leg leg) {}

    /** Catnip's Couple is supplied by Create at runtime. */
    private record StationEdgeAccess(MethodHandle edge, MethodHandle first, MethodHandle second) {
        static @Nullable StationEdgeAccess bind() {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                var field = TrackEdgePoint.class.getField("edgeLocation");
                Class<?> type = field.getType();
                return new StationEdgeAccess(
                        lookup.unreflectGetter(field).asType(MethodType.methodType(Object.class, TrackEdgePoint.class)),
                        lookup.unreflect(type.getMethod("getFirst")).asType(MethodType.methodType(Object.class, Object.class)),
                        lookup.unreflect(type.getMethod("getSecond")).asType(MethodType.methodType(Object.class, Object.class)));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return null;
            }
        }

        @Nullable TrackNode node(TrackGraph graph, TrackEdgePoint station, boolean firstEnd) {
            try {
                Object pair = (Object) edge.invokeExact(station);
                if (pair == null) return null;
                MethodHandle accessor = firstEnd ? first : second;
                TrackNodeLocation location = (TrackNodeLocation) (Object) accessor.invokeExact(pair);
                return location == null ? null : graph.locateNode(location);
            } catch (Throwable failure) {
                if (failure instanceof Error error && !(error instanceof LinkageError)) throw error;
                return null;
            }
        }
    }
}
