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
    public static final int WIDE_MIN_LONG_SIDE = 364, WIDE_MIN_SHORT_SIDE = 104;
    private static final int CLEARANCE = 12, HELIPAD_WIDTH = 11;

    private AirportAutoLayout() {}

    public static Optional<AirportEditorSession.Proposal> propose(BlockPos cornerA, BlockPos cornerB,
            List<AirportFacility> existingFacilities, AirportGroundLayout existingGround) {
        if (!validCorner(cornerA) || !validCorner(cornerB) || cornerA.getY() != cornerB.getY()
                || existingFacilities == null || existingFacilities.size() > 14 || existingGround == null
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
                || ids.contains("auto-helipad-" + suffix)
                || ids.contains("auto-taxi-out-" + suffix) || ids.contains("auto-taxi-in-" + suffix)) suffix++;

        boolean eastWest = xSize >= zSize;
        int minX = Math.min(cornerA.getX(), cornerB.getX()), minZ = Math.min(cornerA.getZ(), cornerB.getZ());
        int minAlong = eastWest ? minX : minZ, minAcross = eastWest ? minZ : minX;
        int y = cornerA.getY();
        boolean wide = longSide >= WIDE_MIN_LONG_SIDE && shortSide >= WIDE_MIN_SHORT_SIDE;
        int corridorWidth = wide ? 40 : 24;
        int standSize = wide ? 48 : 26;
        int turnExtension = wide ? 20 : 14;
        // Deck end caps and one block of margin consume a fixed width. Before the approach
        // reaches its maximum, both approaches together occupy about one runway length.
        int available = longSide - 1 - corridorWidth - 2;
        int approachLimit = AirportFacility.MAX_APPROACH_LENGTH * 2;
        int runwayLength = Math.max(available / 2, available - approachLimit) & ~1;
        int runwayStart = minAlong + (longSide - 1 - runwayLength) / 2;
        int runwayEnd = runwayStart + runwayLength;
        int runwayAcross = minAcross + corridorWidth / 2 + (shortSide - (wide ? WIDE_MIN_SHORT_SIDE : MIN_SHORT_SIDE)) / 2;
        int taxiAcross = runwayAcross + (wide ? 56 : 37);
        BlockPos start = block(eastWest, runwayStart, runwayAcross, y);
        BlockPos end = block(eastWest, runwayEnd, runwayAcross, y);
        var runway = new AirportFacility("auto-runway-" + suffix, AirportFacility.Type.RUNWAY,
                start, end, corridorWidth, CLEARANCE);
        var stand = new AirportParkingStand("auto-hangar-" + suffix,
                center(eastWest, (runwayStart + runwayEnd) / 2, taxiAcross, y),
                eastWest ? 90 : 180, standSize, standSize, CLEARANCE, true);
        // The open rotor pad fits between the parallel lanes, beyond the near taxi bend
        // and before the hangar. Its approach is vertical, away from both runway ends.
        BlockPos padCenter = block(eastWest, runwayStart + (wide ? 7 : 5), runwayAcross + (wide ? 27 : 19), y);
        var helipad = new AirportFacility("auto-helipad-" + suffix, AirportFacility.Type.HELIPAD,
                padCenter, padCenter, HELIPAD_WIDTH, CLEARANCE);

        // A shared parallel apron faces back down the runway. Both taxi joins keep the exact
        // runway direction, and both stand joins keep the same parked heading.
        var outbound = new AirportTaxiRoute("auto-taxi-out-" + suffix, AirportTaxiRoute.Direction.OUTBOUND,
                runway.id(), stand.id(), List.of(stand.position(), center(eastWest, runwayStart, taxiAcross, y),
                center(eastWest, runwayStart - turnExtension, taxiAcross, y),
                center(eastWest, runwayStart - turnExtension, runwayAcross, y), Vec3.atBottomCenterOf(start)),
                corridorWidth, CLEARANCE, 3, 1);
        var inbound = new AirportTaxiRoute("auto-taxi-in-" + suffix, AirportTaxiRoute.Direction.INBOUND,
                runway.id(), stand.id(), List.of(Vec3.atBottomCenterOf(end),
                center(eastWest, runwayEnd + turnExtension, runwayAcross, y),
                center(eastWest, runwayEnd + turnExtension, taxiAcross, y), stand.position()),
                corridorWidth, CLEARANCE, 3, -1);
        if (!runway.validGeometry() || !helipad.validGeometry() || !stand.validGeometry()
                || !outbound.validGeometry() || !inbound.validGeometry())
            return Optional.empty();
        AABB padBounds = helipad.clearanceBounds();
        if (padBounds.intersects(runway.clearanceBounds()) || padBounds.intersects(stand.bounds())
                || intersectsTaxi(padBounds, outbound) || intersectsTaxi(padBounds, inbound)) return Optional.empty();
        var bounds = List.of(runway.clearanceBounds(), stand.bounds(), outbound.bounds(), inbound.bounds(), padBounds);
        if (bounds.stream().anyMatch(area -> area.minX < minX || area.maxX > minX + xSize
                || area.minZ < minZ || area.maxZ > minZ + zSize || area.maxY > 2048
                || existingBounds.stream().anyMatch(area::intersects))) return Optional.empty();

        var facilities = new ArrayList<>(existingFacilities);
        facilities.add(runway);
        facilities.add(helipad);
        var stands = new ArrayList<>(existingGround.stands());
        stands.add(stand);
        var routes = new ArrayList<>(existingGround.taxiRoutes());
        routes.add(outbound);
        routes.add(inbound);
        var ground = new AirportGroundLayout(existingGround.revision() + 1, stands, routes);
        if (AirportGroundValidation.references(ground, facilities) != AirportGroundValidation.Result.VALID) return Optional.empty();
        return Optional.of(new AirportEditorSession.Proposal(facilities, ground, runway, false, List.of(helipad)));
    }

    private static boolean intersectsTaxi(AABB area, AirportTaxiRoute route) {
        double radius = route.width() / 2;
        for (int i = 1; i < route.points().size(); i++) {
            Vec3 a = route.points().get(i - 1), b = route.points().get(i);
            var segment = new AABB(Math.min(a.x, b.x) - radius, a.y, Math.min(a.z, b.z) - radius,
                    Math.max(a.x, b.x) + radius, a.y + route.clearanceHeight(), Math.max(a.z, b.z) + radius);
            if (area.intersects(segment)) return true;
        }
        return false;
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
