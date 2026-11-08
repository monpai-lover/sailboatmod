package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The ground under one fixed-wing step, tied to the mission's selected airport resources. */
public final class AirportGroundContact {
    private static final double EDGE_TOLERANCE = 1e-7;
    private final AirportFacility runway;
    private final AABB stand;
    private final List<TaxiSegment> taxiSegments;
    private final int deckY;

    private AirportGroundContact(AirportFacility runway, AirportParkingStand stand, AirportTaxiRoute route) {
        this.runway = runway;
        this.stand = stand == null ? null : stand.bounds();
        this.deckY = runway.start().getY();
        List<TaxiSegment> segments = new ArrayList<>();
        if (route != null) {
            for (int i = 1; i < route.points().size(); i++) {
                segments.add(new TaxiSegment(route.points().get(i - 1), route.points().get(i), route.width() / 2));
            }
        }
        taxiSegments = List.copyOf(segments);
    }

    public static AirportTaxiRoute.Direction direction(Phase current, Phase next) {
        return switch (next) {
            case TAXI_OUT, HOLD_SHORT, LINE_UP, TAKEOFF -> AirportTaxiRoute.Direction.OUTBOUND;
            case LANDING_ROLL, TAXI_IN, STAND_STABILIZING -> AirportTaxiRoute.Direction.INBOUND;
            // The sweep into the first climb sample still starts on the departure runway.
            case CLIMB -> current == Phase.TAKEOFF ? AirportTaxiRoute.Direction.OUTBOUND : null;
            default -> null;
        };
    }

    public static Optional<AirportGroundContact> forStep(AirportGroundLayout layout, List<AirportFacility> facilities,
            GroundAssignment assignment, Phase current, Phase next) {
        var direction = direction(current, next);
        if (direction == null || assignment.direct()
                || assignment.forDirection(layout, facilities, direction).filter(assignment::equals).isEmpty()) return Optional.empty();
        var runway = facilities.stream().filter(facility -> facility.id().equals(assignment.runwayId())).findFirst().orElseThrow();
        if (next == Phase.TAKEOFF || next == Phase.CLIMB || next == Phase.LANDING_ROLL) {
            return Optional.of(new AirportGroundContact(runway, null, null));
        }
        var stand = layout.stand(assignment.standId()).orElseThrow();
        var route = layout.route(direction, assignment.standId(), assignment.runwayId()).orElseThrow();
        int deckY = runway.start().getY();
        if (Math.abs(stand.position().y - deckY) > EDGE_TOLERANCE
                || Math.abs(route.points().getFirst().y - deckY) > EDGE_TOLERANCE) return Optional.empty();
        return Optional.of(new AirportGroundContact(runway, stand, route));
    }

    /** Always recheck the surface even if the current collision box sits slightly above it. */
    public AABB scanBounds(AABB swept) {
        return new AABB(swept.minX, Math.min(swept.minY, deckY - 1), swept.minZ, swept.maxX, swept.maxY, swept.maxZ);
    }

    public boolean requiredSupport(BlockPos at) {
        return at.getY() == deckY - 1 && supportsColumn(at.getX(), at.getZ());
    }

    /** Ignore only solid geometry below the audited surface; the caller verifies its top block is sturdy. */
    public boolean permitsBelowDeck(BlockPos at, AABB shape) {
        return at.getY() < deckY && shape.maxY <= deckY + EDGE_TOLERANCE && supportsColumn(at.getX(), at.getZ());
    }

    private boolean supportsColumn(int x, int z) {
        if (runway.requiresSupport(x, z)) return true;
        if (stand != null && x + 1 > stand.minX && x < stand.maxX && z + 1 > stand.minZ && z < stand.maxZ) return true;
        for (TaxiSegment segment : taxiSegments) {
            if (segment.supportsColumn(x, z)) return true;
        }
        return false;
    }

    private record TaxiSegment(Vec3 start, Vec3 end, double radius) {
        private boolean supportsColumn(int x, int z) {
            return x + 1 > Math.min(start.x, end.x) - radius && x < Math.max(start.x, end.x) + radius
                    && z + 1 > Math.min(start.z, end.z) - radius && z < Math.max(start.z, end.z) + radius
                    && AirportGroundValidation.segmentDistance(x + .5, z + .5, start, end) <= radius + Math.sqrt(.5);
        }
    }
}
