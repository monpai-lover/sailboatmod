package com.monpai.sailboatmod.market.logistics.rail;

import com.monpai.sailboatmod.market.logistics.ShippingTraceService;
import com.monpai.sailboatmod.market.logistics.TransportModes;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns train snapshots into rail shipments for the web map. Each leg (station to station) gets its own route:
 * the station it left, a point every {@link #BREADCRUMB_SPACING} blocks it travelled, and the station it is heading to.
 * Main thread only; one tracker per server.
 */
public final class RailTraceTracker {
    static final int MAX_POINTS = 400;
    static final double BREADCRUMB_SPACING = 24.0D;
    /** Waiting longer than this at a red signal (60 s) shows as stuck. */
    static final int STUCK_SIGNAL_TICKS = 20 * 60;
    private static final String TRACE_PREFIX = ShippingTraceService.EXTERNAL_TRACE_PREFIX + "rail-";

    private final Map<UUID, Leg> legs = new HashMap<>();

    public record Result(List<ShippingTraceService.ExternalShipment> shipments, List<String> removedTraceIds) {
    }

    private static final class Leg {
        String lastStation = "";
        boolean atStation;
        final List<Vec3> points = new ArrayList<>();
    }

    public static String traceId(UUID trainId) {
        return TRACE_PREFIX + trainId;
    }

    public static boolean isRailTrace(String traceId) {
        return traceId != null && traceId.startsWith(TRACE_PREFIX);
    }

    public Result update(List<RailTrainSnapshot> trains) {
        List<ShippingTraceService.ExternalShipment> shipments = new ArrayList<>();
        Set<UUID> shown = new HashSet<>();
        for (RailTrainSnapshot train : trains) {
            if (train == null || train.trainId() == null || !train.scheduled()) {
                continue;
            }
            shown.add(train.trainId());
            shipments.add(shipment(train, legs.computeIfAbsent(train.trainId(), id -> new Leg())));
        }
        List<String> removed = new ArrayList<>();
        legs.keySet().removeIf(id -> {
            if (shown.contains(id)) {
                return false;
            }
            removed.add(traceId(id));
            return true;
        });
        return new Result(shipments, removed);
    }

    private static ShippingTraceService.ExternalShipment shipment(RailTrainSnapshot train, Leg leg) {
        Vec3 here = new Vec3(train.x(), 0.0D, train.z());
        boolean atStation = train.stationName() != null && !train.stationName().isBlank();
        if (atStation) {
            leg.lastStation = train.stationName();
            leg.points.clear(); // the next leg starts here
            leg.points.add(here);
        } else if (leg.points.isEmpty() || leg.points.get(leg.points.size() - 1).distanceTo(here) >= BREADCRUMB_SPACING) {
            leg.points.add(here);
            if (leg.points.size() > MAX_POINTS) {
                leg.points.remove(1); // keep where the leg started
            }
        }
        leg.atStation = atStation;

        List<Vec3> waypoints = new ArrayList<>(leg.points);
        if (!waypoints.get(waypoints.size() - 1).equals(here)) {
            waypoints.add(here);
        }
        boolean heading = train.destinationName() != null && !Double.isNaN(train.destinationX()) && !Double.isNaN(train.destinationZ());
        if (heading) {
            waypoints.add(new Vec3(train.destinationX(), 0.0D, train.destinationZ()));
        }
        if (waypoints.size() < 2) {
            waypoints.add(here); // the map draws routes of two points or more
        }

        String status;
        if (train.derailed() || train.signalWaitTicks() > STUCK_SIGNAL_TICKS) {
            status = "STUCK";
        } else if (atStation && !heading) {
            status = "ARRIVED";
        } else {
            status = "IN_TRANSIT";
        }
        double progress = atStation && !heading ? 1.0D
                : train.distanceStartedAt() > 0.0D
                        ? Math.max(0.0D, Math.min(1.0D, 1.0D - train.distanceToDestination() / train.distanceStartedAt()))
                        : 0.0D;
        String target = heading ? train.destinationName() : (atStation ? train.stationName() : "");
        return new ShippingTraceService.ExternalShipment(
                traceId(train.trainId()),
                train.ownerUuid() == null ? "" : train.ownerUuid().toString(),
                "",
                TransportModes.RAIL,
                status,
                leg.lastStation,
                target,
                train.name(),
                List.copyOf(waypoints),
                waypoints.size() - 1,
                progress,
                train.x(),
                train.z(),
                Math.abs(train.speed()),
                List.of());
    }
}
