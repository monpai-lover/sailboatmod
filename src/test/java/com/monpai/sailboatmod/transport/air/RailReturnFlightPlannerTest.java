package com.monpai.sailboatmod.transport.air;

import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailReturnFlightPlannerTest {
    RailReturnFlightPlanner.Airport airport(int x,long revision) {
        var stand=new AirportParkingStand("stand",new Vec3(x-100.5,-60,.5),-90,48,48,16,true);
        var out=new AirportTaxiRoute("out",AirportTaxiRoute.Direction.OUTBOUND,"departure","stand",List.of(stand.position(),new Vec3(x-50.5,-60,.5),new Vec3(x+.5,-60,.5)),48,16,3,1);
        var in=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"arrival","stand",List.of(new Vec3(x+.5,-60,.5),new Vec3(x-180.5,-60,.5),new Vec3(x-180.5,-60,80.5),new Vec3(x-140.5,-60,80.5),new Vec3(x-140.5,-60,.5),stand.position()),48,16,3,-1);
        var facilities=List.of(new AirportFacility("departure",AirportFacility.Type.RUNWAY,new BlockPos(x,-60,0),new BlockPos(x+256,-60,0),48,16),new AirportFacility("arrival",AirportFacility.Type.RUNWAY,new BlockPos(x+256,-60,0),new BlockPos(x,-60,0),48,16));
        return new RailReturnFlightPlanner.Airport(new AirportGroundLayout(revision,List.of(stand),List.of(out,in)),facilities,new GroundAssignment(revision,"departure","stand","out","in"));
    }
    RailAircraftPath outbound(RailReturnFlightPlanner.Airport a,RailReturnFlightPlanner.Airport b) {
        return RailFlightPlanBuilder.build(a.facilities().getFirst(),b.facilities().get(1),a.layout().taxiRoutes().getFirst(),b.layout().taxiRoutes().get(1),b.layout().stands().getFirst(),2.7,20,List.of(a.layout().stands().getFirst().position(),b.layout().stands().getFirst().position()),25);
    }
    @Test void verticalReturnUsesTheCapturedFacilitiesAndOutboundAnchor() {
        for (AircraftClass aircraft : List.of(AircraftClass.ROTORCRAFT, AircraftClass.AIRSHIP)) {
            var type = aircraft == AircraftClass.ROTORCRAFT ? AirportFacility.Type.HELIPAD : AirportFacility.Type.MOORING;
            var a = new AirportFacility("first", type, new BlockPos(0, 70, 0), new BlockPos(8, 70, 0), 16, 16);
            var b = new AirportFacility("second", type, new BlockPos(120, 75, 10), new BlockPos(128, 75, 10), 16, 16);
            var layout = new AirportGroundLayout(1, List.of(), List.of());
            var source = new RailReturnFlightPlanner.Airport(layout, List.of(a), GroundAssignment.direct(1, a));
            var destination = new RailReturnFlightPlanner.Airport(layout, List.of(b), GroundAssignment.direct(1, b));
            var outbound = RailFlightPlanBuilder.buildVertical(aircraft, a, b, 1.5,
                    List.of(a.landingPosition(), b.landingPosition()), 20, a.landingPosition().add(0, 1.5, 0), new Vec3(1, 0, 0));
            var back = RailReturnFlightPlanner.build(outbound, source, destination,
                    List.of(b.landingPosition(), a.landingPosition()), 20, 2);
            assertEquals(aircraft, back.airframe());
            assertEquals(outbound.sample(outbound.length()).position(), back.sample(0).position());
            assertEquals(a.landingPosition().add(0, 1.5, 0), back.sample(back.length()).position());
            assertEquals(RailAircraftPath.Phase.VERTICAL_TAKEOFF, back.legs().getFirst().phase());
            assertEquals(RailAircraftPath.Phase.VERTICAL_LANDING, back.legs().get(2).phase());
        }
    }
    @Test void returnStartsAtActualPlannedArrivalAndUsesOppositeAirportProcedures() {
        var a=airport(0,3);var b=airport(2000,7);var forward=outbound(a,b);
        var back=RailReturnFlightPlanner.build(forward,a,b,List.of(b.layout().stands().getFirst().position(),a.layout().stands().getFirst().position()),25,20);
        assertEquals(forward.sample(forward.length()).position(),back.sample(0).position());
        assertEquals(a.layout().stands().getFirst().position().add(0,2.7,0),back.sample(back.length()).position());
        var takeoff=back.legs().stream().filter(l->l.phase()==RailAircraftPath.Phase.TAKEOFF).findFirst().orElseThrow();
        assertEquals(2000.5,takeoff.points().getFirst().x);assertEquals(2256.5,takeoff.points().getLast().x);
        var roll=back.legs().stream().filter(l->l.phase()==RailAircraftPath.Phase.LANDING_ROLL).findFirst().orElseThrow();
        assertEquals(256.5,roll.points().getFirst().x);assertEquals(.5,roll.points().getLast().x);
    }
    @Test void changedAirportRevisionCannotSupplyAnInventedReturnBudget() {
        var a=airport(0,3);var b=airport(2000,7);var stale=new RailReturnFlightPlanner.Airport(b.layout(),b.facilities(),new GroundAssignment(6,"arrival","stand","out","in"));
        assertThrows(IllegalArgumentException.class,()->RailReturnFlightPlanner.build(outbound(a,b),a,stale,List.of(Vec3.ZERO,new Vec3(2000,0,0)),25,20));
    }
    @Test void returnUsesTheProjectedDestinationHullRatherThanTheOriginalDepartureRadius() {
        var a=airport(0,3);var b=airport(2000,7);var forward=outbound(a,b);
        var back=RailReturnFlightPlanner.build(forward,a,b,List.of(b.layout().stands().getFirst().position(),a.layout().stands().getFirst().position()),25,28);
        assertEquals(10,forward.rotationRadius());assertEquals(14,back.rotationRadius());
    }
}
