package com.monpai.sailboatmod.transport.air.airport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared layout draft. The airport editor still owns terrain, claim and occupancy validation. */
public final class AirportAutoLayout {
    public static final int MIN_LONG_SIDE = 128, MIN_SHORT_SIDE = 64, MAX_SIDE = 384;
    private static final int RUNWAY_WIDTH = 24, TAXI_WIDTH = 24, CLEARANCE = 12;
    private static final int STAND_SIZE = 28, LANE_SEPARATION = 34, TURN_EXTENSION = 14;

    private AirportAutoLayout() {}

    public static Optional<AirportEditorSession.Proposal> propose(BlockPos cornerA, BlockPos cornerB,
            List<AirportFacility> existingFacilities, AirportGroundLayout existingGround) {
        if (!validCorner(cornerA) || !validCorner(cornerB) || cornerA.getY() != cornerB.getY()
                || existingFacilities == null || existingFacilities.size() >= 16 || existingGround == null
                || existingGround.stands().size() >= AirportGroundLayout.MAX_STANDS
                || existingGround.taxiRoutes().size() > AirportGroundLayout.MAX_ROUTES - 2
                || existingGround.revision() >= Long.MAX_VALUE - 1) return Optional.empty();
        int xSize = Math.abs(cornerB.getX() - cornerA.getX()) + 1;
        int zSize = Math.abs(cornerB.getZ() - cornerA.getZ()) + 1;
        int longSide = Math.max(xSize, zSize), shortSide = Math.min(xSize, zSize);
        if (longSide < MIN_LONG_SIDE || longSide > MAX_SIDE || shortSide < MIN_SHORT_SIDE) return Optional.empty();

        Set<String> ids = new HashSet<>();
        List<AABB> existingBounds = new ArrayList<>();
        for (var facility : existingFacilities) {
            if (facility == null || !facility.validGeometry() || !ids.add(facility.id())) return Optional.empty();
            existingBounds.add(facility.clearanceBounds());
        }
        for (var stand : existingGround.stands()) {
            ids.add(stand.id());
            existingBounds.add(stand.bounds());
        }
        for (var route : existingGround.taxiRoutes()) {
            ids.add(route.id());
            existingBounds.add(route.bounds());
        }
        int suffix = 1;
        while (ids.contains("auto-runway-" + suffix) || ids.contains("auto-hangar-" + suffix)
                || ids.contains("auto-taxi-out-" + suffix) || ids.contains("auto-taxi-in-" + suffix)) suffix++;

        boolean eastWest = xSize >= zSize;
        int minX = Math.min(cornerA.getX(), cornerB.getX()), minZ = Math.min(cornerA.getZ(), cornerB.getZ());
        int minAlong = eastWest ? minX : minZ, minAcross = eastWest ? minZ : minX;
        int y = cornerA.getY();
        // Deck end caps and one block of margin consume a fixed width. Before the approach
        // reaches its maximum, both approaches together occupy about one runway length.
        int available = longSide - 1 - RUNWAY_WIDTH - 2;
        int approachLimit = AirportFacility.MAX_APPROACH_LENGTH * 2;
        int runwayLength = Math.max(available / 2, available - approachLimit) & ~1;
        int runwayStart = minAlong + (longSide - 1 - runwayLength) / 2;
        int runwayEnd = runwayStart + runwayLength;
        int runwayAcross = minAcross + 13 + (shortSide - MIN_SHORT_SIDE) / 2;
        int taxiAcross = runwayAcross + LANE_SEPARATION;
        BlockPos start = block(eastWest, runwayStart, runwayAcross, y);
        BlockPos end = block(eastWest, runwayEnd, runwayAcross, y);
        var runway = new AirportFacility("auto-runway-" + suffix, AirportFacility.Type.RUNWAY,
                start, end, RUNWAY_WIDTH, CLEARANCE);
        var stand = new AirportParkingStand("auto-hangar-" + suffix,
                center(eastWest, (runwayStart + runwayEnd) / 2, taxiAcross, y),
                eastWest ? 90 : 180, STAND_SIZE, STAND_SIZE, CLEARANCE, true);

        // A shared parallel apron faces back down the runway. Both taxi joins keep the exact
        // runway direction, and both stand joins keep the same parked heading.
        var outbound = new AirportTaxiRoute("auto-taxi-out-" + suffix, AirportTaxiRoute.Direction.OUTBOUND,
                runway.id(), stand.id(), List.of(stand.position(), center(eastWest, runwayStart, taxiAcross, y),
                center(eastWest, runwayStart - TURN_EXTENSION, taxiAcross, y),
                center(eastWest, runwayStart - TURN_EXTENSION, runwayAcross, y), Vec3.atBottomCenterOf(start)),
                TAXI_WIDTH, CLEARANCE, 3, 1);
        var inbound = new AirportTaxiRoute("auto-taxi-in-" + suffix, AirportTaxiRoute.Direction.INBOUND,
                runway.id(), stand.id(), List.of(Vec3.atBottomCenterOf(end),
                center(eastWest, runwayEnd + TURN_EXTENSION, runwayAcross, y),
                center(eastWest, runwayEnd + TURN_EXTENSION, taxiAcross, y), stand.position()),
                TAXI_WIDTH, CLEARANCE, 3, -1);
        if (!runway.validGeometry() || !stand.validGeometry() || !outbound.validGeometry() || !inbound.validGeometry())
            return Optional.empty();
        var bounds = List.of(runway.clearanceBounds(), stand.bounds(), outbound.bounds(), inbound.bounds());
        if (bounds.stream().anyMatch(area -> area.minX < minX || area.maxX > minX + xSize
                || area.minZ < minZ || area.maxZ > minZ + zSize || area.maxY > 2048
                || existingBounds.stream().anyMatch(area::intersects))) return Optional.empty();

        var facilities = new ArrayList<>(existingFacilities);
        facilities.add(runway);
        var stands = new ArrayList<>(existingGround.stands());
        stands.add(stand);
        var routes = new ArrayList<>(existingGround.taxiRoutes());
        routes.add(outbound);
        routes.add(inbound);
        var ground = new AirportGroundLayout(existingGround.revision() + 1, stands, routes);
        if (AirportGroundValidation.references(ground, facilities) != AirportGroundValidation.Result.VALID) return Optional.empty();
        return Optional.of(new AirportEditorSession.Proposal(facilities, ground, runway, false));
    }

    private static boolean validCorner(BlockPos corner) {
        return corner != null && corner.getY() >= -2047 && AirportGroundLayout.validPoint(Vec3.atLowerCornerOf(corner));
    }

    private static BlockPos block(boolean eastWest, int along, int across, int y) {
        return new BlockPos(eastWest ? along : across, y, eastWest ? across : along);
    }

    private static Vec3 center(boolean eastWest, int along, int across, int y) {
        return Vec3.atBottomCenterOf(block(eastWest, along, across, y));
    }
}
