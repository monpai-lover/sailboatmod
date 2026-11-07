package com.monpai.sailboatmod.transport.air.rail;

import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.RailFlightPlanBuilder;
import com.monpai.sailboatmod.transport.air.airport.AirportFacility;
import com.monpai.sailboatmod.transport.air.airport.AirportGroundLayout;
import com.monpai.sailboatmod.transport.air.airport.AirportTaxiRoute;
import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailVerticalFlightTest {
    private final AirportFacility pad = new AirportFacility("pad", AirportFacility.Type.HELIPAD,
            new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), 16, 10);
    private final AirportFacility mooring = new AirportFacility("moor", AirportFacility.Type.MOORING,
            new BlockPos(100, 70, 0), new BlockPos(108, 70, 0), 20, 12);
    private final Vec3 anchor = new Vec3(2.5, 66, .5);

    @Test void facilityAssignmentsRemainTypedAndKeepTheSameFacilityOnReturn() {
        var layout = new AirportGroundLayout(3, List.of(), List.of());
        var assignment = GroundAssignment.direct(layout.revision(), pad);
        assertEquals(assignment, GroundAssignment.load(assignment.save()));
        assertEquals(assignment, assignment.forDirection(layout, List.of(pad), AirportTaxiRoute.Direction.INBOUND).orElseThrow());
        assertTrue(assignment.forDirection(layout.withRevision(4), List.of(pad), AirportTaxiRoute.Direction.OUTBOUND).isEmpty());
        assertTrue(assignment.forDirection(layout, List.of(new AirportFacility(pad.id(), mooring.type(), pad.start(),
                pad.end(), pad.width(), pad.clearanceHeight())), AirportTaxiRoute.Direction.INBOUND).isEmpty());
    }

    @Test void rotorcraftAndAirshipSelectTheirOwnFacilitiesAndRequireActualParking() {
        var aircraft = new RailAirportSelection.Aircraft(UUID.randomUUID(), new AABB(1, 64, -1, 4, 68, 2), anchor, 0);
        var layout = new AirportGroundLayout(0, List.of(), List.of());
        assertTrue(RailAirportSelection.directCandidates(AircraftClass.ROTORCRAFT, layout, List.of(pad, mooring), null)
                .stream().allMatch(candidate -> candidate.runway().type() == AirportFacility.Type.HELIPAD));
        assertEquals(mooring, RailAirportSelection.directCandidates(AircraftClass.AIRSHIP, layout, List.of(pad, mooring), null)
                .getFirst().runway());
        assertTrue(RailAirportSelection.directParked(aircraft, pad, .1, .01));
        assertFalse(RailAirportSelection.directParked(aircraft, pad, .3, .01));
        assertFalse(RailAirportSelection.directParked(aircraft, pad, .1, .1));
    }

    @Test void verticalPathStartsAtActualPoseAndDescendsWithLevelNoseAfterCruise() {
        var target = new AirportFacility("target", AirportFacility.Type.HELIPAD, new BlockPos(100, 70, 0),
                new BlockPos(104, 70, 0), 16, 10);
        var route = List.of(pad.landingPosition(), new Vec3(48, 92, 32), target.landingPosition());
        var path = RailFlightPlanBuilder.buildVertical(AircraftClass.ROTORCRAFT, pad, target, 2, route, 10,
                anchor, new Vec3(0, 0, 1));
        var loaded = RailAircraftPath.load(path.save());
        assertEquals(path.fingerprint(), loaded.fingerprint());
        assertEquals(anchor, loaded.sample(0).position());
        assertEquals(0, new Vec3(0, 0, 1).distanceTo(loaded.sample(0).tangent()), 1e-9);
        assertEquals(anchor.x, loaded.sample(1).position().x, 1e-9);
        assertTrue(loaded.sample(1).position().y > anchor.y);
        assertEquals(0, loaded.sample(1).pitchRadians(), 1e-9);
        var finalSample = loaded.sample(loaded.length());
        var descending = loaded.sample(loaded.length() - 1);
        assertEquals(finalSample.position().x, descending.position().x, 1e-9);
        assertEquals(finalSample.position().z, descending.position().z, 1e-9);
        assertTrue(descending.position().y > finalSample.position().y);
        assertEquals(0, descending.pitchRadians(), 1e-9);
        assertEquals(target.landingPosition().add(0, 2, 0), finalSample.position());
        assertEquals(finalSample.tangent(), descending.tangent());
    }
    @Test void airshipHasSlowerVerticalMotionAndLessBankThanRotorcraft() {
        var from=new AirportFacility("from",AirportFacility.Type.MOORING,pad.start(),pad.end(),16,10);
        var to=new AirportFacility("to",AirportFacility.Type.MOORING,mooring.start(),mooring.end(),20,12);
        var route=List.of(from.landingPosition(),new Vec3(48,92,32),to.landingPosition());
        var airship=RailFlightPlanBuilder.buildVertical(AircraftClass.AIRSHIP,from,to,2,route,20,anchor,new Vec3(0,0,1));
        var rotorcraft=RailFlightPlanBuilder.buildVertical(AircraftClass.ROTORCRAFT,pad,
                new AirportFacility("to",AirportFacility.Type.HELIPAD,to.start(),to.end(),20,12),2,route,20,anchor,new Vec3(0,0,1));
        assertTrue(airship.legs().getFirst().speed()<rotorcraft.legs().getFirst().speed());
        var airshipCruise=airship.controlPolygons().stream().filter(p->p.phase()==RailAircraftPath.Phase.CRUISE).toList();
        double greatestBank=0;
        for (double distance=airshipCruise.getFirst().startDistance();distance<airshipCruise.getLast().endDistance();distance+=.1) {
            greatestBank=Math.max(greatestBank,Math.abs(airship.sample(distance).bankRadians()));
        }
        assertTrue(greatestBank>0 && greatestBank<=Math.toRadians(7));
        assertEquals(AircraftClass.AIRSHIP,RailAircraftPath.load(airship.save()).airframe());
    }
}
