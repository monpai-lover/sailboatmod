package com.monpai.sailboatmod.transport.air;

import com.monpai.sailboatmod.transport.air.airport.AirportFacility;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FlightRoutePlannerTest {
    @Test void offCenterFlightOriginCannotPassAnUndersizedPad() {
        var hull = new AABB(0, 70, -2, 10, 74, 2);
        double diameter = FlightRoutePlanner.requiredHorizontalDiameter(hull, new Vec3(0, 73, 0));
        var source = new AirportFacility("pad", AirportFacility.Type.HELIPAD, new BlockPos(5,70,0), new BlockPos(5,70,0), 12,16);
        var target = new AirportFacility("pad", AirportFacility.Type.HELIPAD, new BlockPos(1000,70,0), new BlockPos(1000,70,0), 12,16);
        assertThrows(IllegalArgumentException.class, () -> FlightRoutePlanner.plan(AircraftClass.ROTORCRAFT, source, target,
                List.of(new Vec3(5,150,0),new Vec3(1000,150,0)),3,diameter,10));
        assertEquals(2*Math.hypot(10,2), diameter, 1e-9);
    }
    @Test void footprintCoversAllYawAnglesAroundTheFlightOrigin() {
        var hull = new AABB(-5,70,-2,5,74,2);
        double diameter = FlightRoutePlanner.requiredHorizontalDiameter(hull, new Vec3(0,73,0));
        assertEquals(2*Math.hypot(5,2), diameter, 1e-9);
        assertThrows(IllegalArgumentException.class, () -> FlightRoutePlanner.requiredHorizontalDiameter(hull,new Vec3(Double.NaN,0,0)));
    }
    @Test void returnRegeneratesProceduresFromAuthoredCruiseOnly() {
        var authored=List.of(new Vec3(.5,70,.5),new Vec3(.5,150,.5),new Vec3(1000.5,150,.5),new Vec3(1000.5,70,.5));
        var outbound=FlightRoutePlanner.plan(AircraftClass.FIXED_WING,runway(0),runway(1000),authored,3,10,10);
        var returning=FlightRoutePlanner.plan(AircraftClass.FIXED_WING,runway(1000),runway(0),
                FlightRoutePlanner.cruiseForLeg(authored,true),3,10,10);
        assertFalse(returning.contains(outbound.get(outbound.size()-2)),"Return must not visit the previous destination touchdown after taking off");
        assertEquals(new Vec3(.5,73,.5),returning.get(returning.size()-2));
        assertEquals(4,returning.size());
    }
    private AirportFacility runway(int x) { return new AirportFacility("rw", AirportFacility.Type.RUNWAY,
            new BlockPos(x,70,0),new BlockPos(x+120,70,0),16,16); }
    @Test void fixedWingFinishesAtTouchdownThenRunwayStopNotVerticalHover() {
        var plan=FlightRoutePlanner.plan(AircraftClass.FIXED_WING,runway(0),runway(1000),
                List.of(new Vec3(0,150,0),new Vec3(1000,150,0)),3,10,10);
        assertEquals(new Vec3(1000.5,73,.5),plan.get(plan.size()-2));
        assertEquals(new Vec3(1120.5,73,.5),plan.getLast());
        assertTrue(plan.stream().anyMatch(p->p.y>=150));
    }
    @Test void rotorcraftUsesVerticalDepartureAndArrival() {
        var a=new AirportFacility("pad",AirportFacility.Type.HELIPAD,new BlockPos(0,70,0),new BlockPos(0,70,0),16,16);
        var b=new AirportFacility("pad",AirportFacility.Type.HELIPAD,new BlockPos(1000,70,0),new BlockPos(1000,70,0),16,16);
        var plan=FlightRoutePlanner.plan(AircraftClass.ROTORCRAFT,a,b,List.of(new Vec3(0,150,0),new Vec3(1000,150,0)),3,8,10);
        assertEquals(.5,plan.getFirst().x);
        assertEquals(1000.5,plan.getLast().x);
        assertEquals(73,plan.getLast().y);
    }
    @Test void incompatibleOrUndersizedLandingFacilitiesRejectBeforeCargoDispatch() {
        var pad=new AirportFacility("pad",AirportFacility.Type.HELIPAD,BlockPos.ZERO,BlockPos.ZERO,16,16);
        assertThrows(IllegalArgumentException.class,()->FlightRoutePlanner.plan(AircraftClass.FIXED_WING,runway(0),pad,List.of(Vec3.ZERO,new Vec3(10,100,0)),1,10,10));
        assertThrows(IllegalArgumentException.class,()->FlightRoutePlanner.plan(AircraftClass.FIXED_WING,runway(0),runway(1000),List.of(Vec3.ZERO,new Vec3(10,100,0)),1,30,10));
    }
}
