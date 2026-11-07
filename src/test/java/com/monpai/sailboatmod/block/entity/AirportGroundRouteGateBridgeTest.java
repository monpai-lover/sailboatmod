package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.air.FlightRoutePlanner;
import com.monpai.sailboatmod.transport.air.airport.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;

/** Calls the actual RVC factory used by both airport-selection call sites. */
class AirportGroundRouteGateBridgeTest {
    record Fixture(AirportGroundLayout layout,List<AirportFacility> facilities,AirportTaxiRoute route) { }
    @Test void currentSourceOutUsesRealPoseWhenParkedYawToleranceAllowsIt(){
        var f=out(-80,1);var cap=cap(new Vec3(.5,65,.5),-90,1);
        assertTrue(f.layout().stands().getFirst().contains(cap.hull(),cap.yawDegrees(),cap.speed()),"actual pose must also be physically legal in its stand");
        assertFalse(RegisteredVehicleControllerBlockEntity.groundRouteGate(f.layout(),f.facilities(),.1,false).admits(f.route(),cap,corridor(f,cap)),"virtual stand heading deliberately needs more first-segment correction");
        assertTrue(RegisteredVehicleControllerBlockEntity.groundRouteGate(f.layout(),f.facilities(),.1,true).admits(f.route(),cap,corridor(f,cap)),"current source factory must keep actual native reference and yaw");
    }
    @Test void futureRemoteOutUsesItsOwnStandPose(){
        var f=out(-90,1);var cap=cap(new Vec3(-1000,65,400),45,1);
        assertTrue(RegisteredVehicleControllerBlockEntity.groundRouteGate(f.layout(),f.facilities(),.1,false).admits(f.route(),cap,corridor(f,cap)));
        assertFalse(RegisteredVehicleControllerBlockEntity.groundRouteGate(f.layout(),f.facilities(),.1,true).admits(f.route(),cap,corridor(f,cap)),"the boolean must distinguish current source from future remote OUT");
    }
    @Test void inboundAlwaysUsesItsOwnRunwayEntryEvenWhenSearchingAtTheSource(){
        var f=in(.1);var cap=cap(new Vec3(-1000,65,400),45,1);
        assertTrue(RegisteredVehicleControllerBlockEntity.groundRouteGate(f.layout(),f.facilities(),.1,false).admits(f.route(),cap,corridor(f,cap)));
        assertTrue(RegisteredVehicleControllerBlockEntity.groundRouteGate(f.layout(),f.facilities(),.1,true).admits(f.route(),cap,corridor(f,cap)));
    }
    @Test void knownSlowCounterpartPreventsFastRoutePrefilterFromFalselyRejectingAFeasiblePair(){
        var source=out(-90,5);var destination=in(.1);var cap=cap(new Vec3(.5,65,.5),-90,.1);
        double lower=RegisteredVehicleControllerBlockEntity.selectionTaxiSpeedLowerBound(5,source.layout(),destination.layout());
        assertEquals(.1,lower,0);
        assertFalse(RegisteredVehicleControllerBlockEntity.groundRouteGate(source.layout(),source.facilities(),5,true).admits(source.route(),cap,corridor(source,cap)),"fixture really cannot stop from 5m/s in its short segment at measured a=.1");
        assertTrue(RegisteredVehicleControllerBlockEntity.groundRouteGate(source.layout(),source.facilities(),lower,true).admits(source.route(),cap,corridor(source,cap)),"the real pair will use min(5,5,.1), not 5 or parked speed zero");
    }
    private static Fixture out(double yaw,double limit){
        var runway=runway();var stand=new AirportParkingStand("stand",new Vec3(.5,64,.5),yaw,8,8,8,true);
        var route=new AirportTaxiRoute("out",AirportTaxiRoute.Direction.OUTBOUND,runway.id(),stand.id(),List.of(stand.position(),new Vec3(12.5,64,.5),new Vec3(30.5,64,.5)),4,8,limit,1);
        return checked(new Fixture(new AirportGroundLayout(3,List.of(stand),List.of(route)),List.of(runway),route));
    }
    private static Fixture in(double limit){
        var runway=runway();var stand=new AirportParkingStand("arrival",new Vec3(180.5,64,.5),-90,8,8,8,true);
        var route=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,runway.id(),stand.id(),List.of(new Vec3(158.5,64,.5),stand.position()),4,8,limit,-1);
        return checked(new Fixture(new AirportGroundLayout(4,List.of(stand),List.of(route)),List.of(runway),route));
    }
    private static AirportFacility runway(){return new AirportFacility("runway",AirportFacility.Type.RUNWAY,new BlockPos(30,64,0),new BlockPos(158,64,0),8,8);}
    private static Fixture checked(Fixture f){assertTrue(f.route().validGeometry());assertEquals(AirportGroundValidation.Result.VALID,AirportGroundValidation.references(f.layout(),f.facilities()));return f;}
    private static AirportGroundSelection.Capabilities cap(Vec3 p,double yaw,double braking){return new AirportGroundSelection.Capabilities(UUID.randomUUID(),new AABB(p.x-1,65,p.z-1,p.x+1,67,p.z+1),p,yaw,0,braking,32,30,5,true,true);}
    private static double corridor(Fixture f,AirportGroundSelection.Capabilities c){return(f.route().width()-FlightRoutePlanner.requiredHorizontalDiameter(c.hull(),c.nativeReference()))/2;}
}
