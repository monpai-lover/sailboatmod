package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real AirportBE terrain/reservation gates with physical inputs and no native flight/wheel proof. */
class RailAirportAdmissionTest {
    @TempDir Path directory;
    AirportGroundOperationsTest fixture;
    LogisticsCarrier carrier;
    final UUID physicalId=UUID.randomUUID();
    Vec3 velocity=Vec3.ZERO;
    Vec3 positionOverride;
    boolean rail=true;
    boolean allowCarrierProof,carrierArrivalReady;
    @BeforeAll static void bootstrap(){AirportGroundOperationsTest.bootstrap();}
    @BeforeEach void setup()throws Exception{
        fixture=new AirportGroundOperationsTest();fixture.directory=directory;fixture.setup();
        fixture.aircraft.hull=new AABB(-22,64,-1,-19,66,2);fixture.aircraft.grounded=false;
        var layout=fixture.airport.groundLayout();var old=layout.taxiRoutes().getLast();
        var inbound=new AirportTaxiRoute(old.id(),old.direction(),old.runwayId(),old.standId(),List.of(new Vec3(48.5,64,.5),layout.stands().getFirst().position()),old.width(),old.clearanceHeight(),old.speedLimitMetersPerSecond(),-1);
        AirportGroundOperationsTest.set(AirportBlockEntity.class,fixture.airport,"groundLayout",new AirportGroundLayout(2,layout.stands(),List.of(layout.taxiRoutes().getFirst(),inbound)));
        carrier=(LogisticsCarrier)Proxy.newProxyInstance(LogisticsCarrier.class.getClassLoader(),new Class<?>[]{LogisticsCarrier.class,AirportApproachState.class},(p,m,args)->{
            if(m.getName().equals("railAircraftAutomation"))return rail;
            if(m.getName().equals("hasCargo"))return true;
            if(m.getName().equals("transportPosition")&&positionOverride!=null)return positionOverride;
            if(m.getName().equals("groundArrivalReady")||m.getName().equals("groundAssignedArrivalReady")){
                if(allowCarrierProof)return carrierArrivalReady;
                throw new AssertionError("Rail admission may not recursively ask the carrier for its own arrival proof");
            }
            return fixture.aircraft.invoke(p,m,args);
        });
    }
    @AfterEach void cleanup(){fixture.clear();}
    private GroundAssignment assignment(){return new GroundAssignment(2,"main","a","out","in");}
    private PhysicalVehicleView actual(){return new PhysicalVehicleView(physicalId,"minecraft:overworld",fixture.aircraft.hull.getCenter(),new Vec3(0,0,1),velocity,fixture.aircraft.hull,95,true);}
    private boolean invoke(String method,Class<?>[] signature,Object...args){
        try{return (boolean)AirportBlockEntity.class.getMethod(method,signature).invoke(fixture.airport,args);}
        catch(NoSuchMethodException missing){return fail("Rail airport admission feature is missing: "+method,missing);}
        catch(InvocationTargetException failure){return fail("Rail airport proof must fail closed without callback failure",failure.getCause());}
        catch(ReflectiveOperationException failure){return fail("Rail airport API failed",failure);}
    }
    private boolean departure(PhysicalVehicleView actual,double yaw,double angular){return invoke("railDepartureReady",new Class<?>[]{LogisticsCarrier.class,PhysicalVehicleView.class,double.class,double.class},carrier,actual,yaw,angular);}
    private boolean geometry(PhysicalVehicleView actual,double yaw,double angular){return invoke("railDepartureGeometry",new Class<?>[]{LogisticsCarrier.class,PhysicalVehicleView.class,double.class,double.class},carrier,actual,yaw,angular);}
    private boolean contains(GroundAssignment assignment,UUID expected,PhysicalVehicleView actual,double yaw,double angular,long sampled){return invoke("railStandContains",new Class<?>[]{GroundAssignment.class,LogisticsCarrier.class,UUID.class,PhysicalVehicleView.class,double.class,double.class,long.class},assignment,carrier,expected,actual,yaw,angular,sampled);}
    private boolean arrived(int stable){return invoke("railAssignedStandArrival",new Class<?>[]{GroundAssignment.class,LogisticsCarrier.class,UUID.class,PhysicalVehicleView.class,double.class,double.class,int.class,long.class},assignment(),carrier,physicalId,actual(),0D,0D,stable,fixture.level.time);}
    private void prepareAndReserve(long ttl){
        AirportGroundPreparation.Check check=null;
        for(int i=0;i<250;i++,fixture.level.time+=5){check=fixture.airport.prepareGroundCandidate(fixture.attempt,fixture.aircraft.id,assignment());if(check.status()!=AirportGroundPreparation.Status.WAITING)break;}
        assertEquals(AirportGroundPreparation.Status.READY,check.status());
        assertTrue(fixture.airport.reserveGroundAssignment(fixture.attempt,assignment(),fixture.aircraft.id,ttl));
    }
    @Test void departureRunsRealBoundedTerrainPreparationWithoutFcWheelOrNativeGroundProof(){
        assertFalse(departure(actual(),0,0),"an unscanned stand must not instantly be ready");
        boolean ready=false;for(int i=0;i<250&&!ready;i++,fixture.level.time+=5)ready=departure(actual(),0,0);
        assertTrue(ready);assertTrue(fixture.airport.groundReservations().isEmpty(),"departure observation must not acquire resources");
    }
    @Test void departureCannotSkipARealTerrainObstacle(){
        fixture.level.blocked.add(new BlockPos(-20,65,0));
        for(int i=0;i<100;i++,fixture.level.time+=5)assertFalse(departure(actual(),0,0));
    }
    @Test void departureCanPrepareAnotherPairWhenTheFirstInboundRouteIsBlocked()throws Exception{
        var layout=fixture.airport.groundLayout();var main=fixture.airport.facilities().getFirst();var stand=layout.stands().getFirst();
        var reverse=new AirportFacility("reverse",main.type(),main.end(),main.start(),main.width(),main.clearanceHeight());
        var blocked=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"main",stand.id(),List.of(new Vec3(48.5,64,.5),new Vec3(48.5,64,20.5),new Vec3(-20.5,64,20.5),stand.position()),8,8,2,-1);
        var clear=new AirportTaxiRoute("in-reverse",AirportTaxiRoute.Direction.INBOUND,"reverse",stand.id(),List.of(new Vec3(.5,64,.5),stand.position()),8,8,2,-1);
        AirportGroundOperationsTest.set(AirportBlockEntity.class,fixture.airport,"facilities",new LinkedHashMap<>(Map.of("main",main,"reverse",reverse)));
        AirportGroundOperationsTest.set(AirportBlockEntity.class,fixture.airport,"groundLayout",new AirportGroundLayout(2,layout.stands(),List.of(layout.taxiRoutes().getFirst(),blocked,clear)));
        fixture.level.blocked.add(new BlockPos(-20,65,20));boolean ready=false;
        for(int i=0;i<1000&&!ready;i++,fixture.level.time+=5)ready=departure(actual(),0,0);
        assertTrue(ready,"a blocked unused inbound route must not permanently prevent loading/departure on the clear pair");
    }
    @Test void geometryObservationDoesNotNeedFortyTicksButDeliveryDoes(){
        prepareAndReserve(1000);assertTrue(contains(assignment(),physicalId,actual(),0,0,fixture.level.time));
        assertFalse(arrived(0));assertFalse(arrived(39));assertTrue(arrived(40));assertTrue(arrived(41));
    }
    @Test void matchingActualBodyAndCurrentSampleAreRequired(){
        prepareAndReserve(1000);assertFalse(contains(assignment(),UUID.randomUUID(),actual(),0,0,fixture.level.time));
        assertFalse(contains(assignment(),physicalId,actual(),0,0,fixture.level.time-1));
        assertFalse(contains(assignment(),physicalId,actual(),0,0,fixture.level.time+1));
    }
    @Test void exactResourceClaimCannotBeBorrowedAcrossAssignmentsOrAfterExpiry(){
        assertFalse(contains(assignment(),physicalId,actual(),0,0,fixture.level.time));prepareAndReserve(100);
        assertFalse(contains(new GroundAssignment(3,"main","a","out","in"),physicalId,actual(),0,0,fixture.level.time));
        fixture.level.time+=101;assertFalse(contains(assignment(),physicalId,actual(),0,0,fixture.level.time));
        assertTrue(fixture.airport.groundReserved(),"expired safety claims must remain sticky");
    }
    @Test void missingChunksCannotCertifyArrival(){prepareAndReserve(1000);fixture.level.unloaded.add(new ChunkPos(-2,0));assertFalse(arrived(40));}
    @Test void wholeHullHeadingSpeedAndAngularMotionRemainRealGates(){
        prepareAndReserve(1000);assertFalse(contains(assignment(),physicalId,actual(),15,0,fixture.level.time));
        assertFalse(contains(assignment(),physicalId,actual(),0,Math.toRadians(2.01),fixture.level.time));
        velocity=new Vec3(.251,0,0);assertFalse(arrived(40));velocity=Vec3.ZERO;
        fixture.aircraft.hull=fixture.aircraft.hull.move(8,0,0);assertFalse(arrived(40));
    }
    @Test void hullBottomNeedNotPretendToBeTheWheelSupportPlane(){prepareAndReserve(1000);fixture.aircraft.hull=fixture.aircraft.hull.move(0,.702,0);assertTrue(arrived(40));}
    @Test void aStaleOrForeignPhysicalViewCannotBorrowTheCarriersHull(){
        prepareAndReserve(1000);var v=actual();
        assertFalse(contains(assignment(),physicalId,new PhysicalVehicleView(v.physicalId(),v.dimensionId(),v.worldPosition().add(1,0,0),v.worldForward(),v.linearVelocity(),v.worldBounds(),v.mass(),true),0,0,fixture.level.time));
        assertFalse(contains(assignment(),physicalId,new PhysicalVehicleView(v.physicalId(),"minecraft:the_nether",v.worldPosition(),v.worldForward(),v.linearVelocity(),v.worldBounds(),v.mass(),true),0,0,fixture.level.time));
        assertFalse(contains(assignment(),physicalId,new PhysicalVehicleView(v.physicalId(),v.dimensionId(),v.worldPosition(),v.worldForward(),v.linearVelocity(),v.worldBounds(),v.mass(),false),0,0,fixture.level.time));
    }
    @Test void nativeCarrierCannotInvokeRailProofToBypassItsOwnGroundGates(){prepareAndReserve(1000);rail=false;assertFalse(arrived(40));assertFalse(departure(actual(),0,0));}
    @Test void nonFiniteCarrierPositionCannotMatchAnOtherwiseValidPhysicalView(){prepareAndReserve(1000);positionOverride=new Vec3(Double.NaN,65,.5);assertFalse(arrived(40));}
    @Test void missingActualGeometryFailsClosedWithoutACallbackFailure(){
        prepareAndReserve(1000);var v=actual();
        assertFalse(contains(assignment(),physicalId,new PhysicalVehicleView(v.physicalId(),v.dimensionId(),null,v.worldForward(),v.linearVelocity(),v.worldBounds(),v.mass(),true),0,0,fixture.level.time));
        assertFalse(contains(assignment(),physicalId,new PhysicalVehicleView(v.physicalId(),v.dimensionId(),v.worldPosition(),v.worldForward(),v.linearVelocity(),null,v.mass(),true),0,0,fixture.level.time));
    }
    @Test void genericAirportArrivalStillNeedsTheRailMissionProofAndWholeHull(){
        prepareAndReserve(1000);allowCarrierProof=true;
        assertFalse(fixture.airport.isCarrierInsideDockZone(carrier));carrierArrivalReady=true;
        assertTrue(fixture.airport.isCarrierInsideDockZone(carrier));fixture.aircraft.hull=fixture.aircraft.hull.move(8,0,0);
        assertFalse(fixture.airport.isCarrierInsideDockZone(carrier));
    }
    @Test void anUnscannedLoadedAircraftCanQueueFromGeometryWithoutPreparingTerrainOrCargo()throws Exception{
        assertTrue(carrier.hasCargo());assertTrue(geometry(actual(),0,0));
        for(String field:List.of("preflights","groundPreflights","validation","groundReservations","reservationChecks","arrivalChecks")){
            Field f=AirportBlockEntity.class.getDeclaredField(field);f.setAccessible(true);assertTrue(((Map<?,?>)f.get(fixture.airport)).isEmpty(),"geometry changed "+field);
        }
        Field budget=AirportBlockEntity.class.getDeclaredField("remainingBudget");budget.setAccessible(true);assertEquals(0,budget.getInt(fixture.airport));
        assertFalse(departure(actual(),0,0),"queue eligibility is not completed terrain preparation");
        assertFalse(arrived(40),"queue eligibility cannot deliver cargo without the existing permits and actual arrival gate");
    }
    @Test void unknownTerrainDoesNotBlockGeometryQueueingButStillBlocksFullDepartureAndArrival(){
        fixture.level.unloaded.add(new ChunkPos(-2,0));assertTrue(geometry(actual(),0,0));
        for(int i=0;i<20;i++,fixture.level.time+=5)assertFalse(departure(actual(),0,0));
        assertTrue(geometry(actual(),0,0));assertFalse(arrived(40));
    }
    @Test void geometryRejectsForeignFramesMotionHeadingAndAStandMismatch(){
        var v=actual();
        assertFalse(geometry(new PhysicalVehicleView(UUID.randomUUID(),v.dimensionId(),v.worldPosition().add(1,0,0),v.worldForward(),v.linearVelocity(),v.worldBounds().move(1,0,0),v.mass(),true),0,0));
        assertFalse(geometry(new PhysicalVehicleView(v.physicalId(),"minecraft:the_nether",v.worldPosition(),v.worldForward(),v.linearVelocity(),v.worldBounds(),v.mass(),true),0,0));
        assertFalse(geometry(new PhysicalVehicleView(null,v.dimensionId(),v.worldPosition(),v.worldForward(),v.linearVelocity(),v.worldBounds(),v.mass(),true),0,0));
        velocity=new Vec3(.251,0,0);assertFalse(geometry(actual(),0,0));velocity=Vec3.ZERO;
        assertFalse(geometry(actual(),0,Math.toRadians(2.01)));
        double yaw=15,radians=Math.toRadians(yaw);
        assertFalse(geometry(new PhysicalVehicleView(v.physicalId(),v.dimensionId(),v.worldPosition(),new Vec3(-Math.sin(radians),0,Math.cos(radians)),v.linearVelocity(),v.worldBounds(),v.mass(),true),yaw,0));
        fixture.aircraft.hull=fixture.aircraft.hull.move(8,0,0);assertFalse(geometry(actual(),0,0));
    }
    @Test void geometryRequiresTheCompleteCompatiblePairAndTheRailMode()throws Exception{
        assertTrue(geometry(actual(),0,0));rail=false;assertFalse(geometry(actual(),0,0));rail=true;
        var layout=fixture.airport.groundLayout();
        AirportGroundOperationsTest.set(AirportBlockEntity.class,fixture.airport,"groundLayout",new AirportGroundLayout(layout.revision(),layout.stands(),List.of(layout.taxiRoutes().getFirst())));
        assertFalse(geometry(actual(),0,0),"a stand alone cannot substitute for a compatible outbound/inbound/runway candidate");
    }
}
