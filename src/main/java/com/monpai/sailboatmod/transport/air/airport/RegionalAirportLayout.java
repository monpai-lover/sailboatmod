package com.monpai.sailboatmod.transport.air.airport;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Operational geometry registered with the regional airport structure's fixed orientation. */
public final class RegionalAirportLayout {
    public static final int TEMPLATE_WIDTH = 145, TEMPLATE_DEPTH = 96, TEMPLATE_HEIGHT = 27;
    private static final int WEST_APPROACH = -44, EAST_APPROACH = 188;
    public static final int MIN_WIDTH = EAST_APPROACH - WEST_APPROACH + 1, MIN_DEPTH = TEMPLATE_DEPTH;
    public static final int MAX_SIDE = AirportAutoLayout.MAX_SIDE;
    private static final int GROUND_CLEARANCE = 8, TAXI_WIDTH = 24;

    private RegionalAirportLayout() {}

    public static BlockPos origin(BlockPos airportPos) { return airportPos.offset(-68, -1, -78); }
    public static BlockPos firstCorner(BlockPos airportPos) { return origin(airportPos).offset(WEST_APPROACH, 1, 0); }
    public static BlockPos secondCorner(BlockPos airportPos) { return origin(airportPos).offset(EAST_APPROACH, 1, TEMPLATE_DEPTH - 1); }

    public static Optional<AirportEditorSession.Proposal> propose(BlockPos cornerA, BlockPos cornerB,
            BlockPos airportPos, List<AirportFacility> existingFacilities, AirportGroundLayout existingGround) {
        if (!validCorner(cornerA) || !validCorner(cornerB) || !validCorner(airportPos)
                || cornerA.getY() != airportPos.getY() || cornerB.getY() != airportPos.getY()
                || existingFacilities == null || !existingFacilities.isEmpty() || existingGround == null
                || !existingGround.stands().isEmpty() || !existingGround.taxiRoutes().isEmpty()
                || existingGround.revision() >= Long.MAX_VALUE - 1) return Optional.empty();

        BlockPos origin = origin(airportPos);
        int minX = Math.min(cornerA.getX(), cornerB.getX()), maxX = Math.max(cornerA.getX(), cornerB.getX());
        int minZ = Math.min(cornerA.getZ(), cornerB.getZ()), maxZ = Math.max(cornerA.getZ(), cornerB.getZ());
        if (maxX - minX >= MAX_SIDE || maxZ - minZ >= MAX_SIDE
                || minX > origin.getX() + WEST_APPROACH || maxX < origin.getX() + EAST_APPROACH
                || minZ > origin.getZ() || maxZ < origin.getZ() + TEMPLATE_DEPTH - 1) return Optional.empty();

        var runway = new AirportFacility("runway_09_27", AirportFacility.Type.RUNWAY,
                origin.offset(20, 1, 17), origin.offset(124, 1, 17), 25, 16);
        if (origin.getY() + TEMPLATE_HEIGHT > 2048) return Optional.empty();
        var helipad = vertical("heli_pad", AirportFacility.Type.HELIPAD, origin.offset(71, 1, 58), 19, 16);
        var mooring = vertical("airship_mooring", AirportFacility.Type.MOORING, origin.offset(129, 1, 63), 25, 24);

        // The hangars have solid back walls. Both legs use the marked apron stand outside Hangar B
        // with matching west-facing entry and departure, clear of the two vertical facilities.
        var stand = new AirportParkingStand("regional_apron_b", center(origin, 45, 53), 90, 26, 24, GROUND_CLEARANCE, true);
        var outbound = new AirportTaxiRoute("regional_taxi_out", AirportTaxiRoute.Direction.OUTBOUND, runway.id(), stand.id(),
                List.of(stand.position(), center(origin, 34, 53), center(origin, 34, 45), center(origin, 34, 24),
                        center(origin, 11, 24), center(origin, 11, 17), Vec3.atBottomCenterOf(runway.start())),
                TAXI_WIDTH, GROUND_CLEARANCE, 3, 2);
        var inbound = new AirportTaxiRoute("regional_taxi_in", AirportTaxiRoute.Direction.INBOUND, runway.id(), stand.id(),
                List.of(Vec3.atBottomCenterOf(runway.end()), center(origin, 135, 17), center(origin, 135, 25),
                        center(origin, 110, 25), center(origin, 110, 36), center(origin, 49, 36), center(origin, 49, 53), stand.position()),
                TAXI_WIDTH, GROUND_CLEARANCE, 3, -1);
        var facilities = List.of(runway, helipad, mooring);
        var ground = new AirportGroundLayout(existingGround.revision() + 1, List.of(stand), List.of(outbound, inbound));
        return Optional.of(new AirportEditorSession.Proposal(facilities, ground, runway, false, List.of(helipad, mooring)));
    }

    private static AirportFacility vertical(String id, AirportFacility.Type type, BlockPos center, int width, int clearance) {
        return new AirportFacility(id, type, center, center, width, clearance);
    }

    private static Vec3 center(BlockPos origin, int x, int z) { return Vec3.atBottomCenterOf(origin.offset(x, 1, z)); }

    private static boolean validCorner(BlockPos point) {
        return point != null && point.getY() >= -2047 && AirportGroundLayout.validPoint(Vec3.atLowerCornerOf(point));
    }
}
