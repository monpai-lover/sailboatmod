package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission;
import com.monpai.sailboatmod.transport.control.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual registrar entry and output signal; native steps below model API effects, not real rigid-body stability. */
class RegisteredGroundHoldRecoveryTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private static final class Port implements RegisteredVehicleControllerBlockEntity.GroundHoldPort {
        final RegisteredVehicleControllerBlockEntity controller;
        final Object handle=new Object();
        int neutralCalls,brakeCalls,holdRequests,releases,nativeObservations,groundThrottle=-1;
        double nativePropulsion=95.5,nativePhysicsTime=60,nativeTarget=12;
        boolean holdRequested,servo=true;long observedAt;
        Port(RegisteredVehicleControllerBlockEntity c){controller=c;}
        public boolean neutral(int signal){assertEquals(15,signal);neutralCalls++;groundThrottle=signal;return true;}
        public void brake15(){brakeCalls++;try{signals(controller).put(Direction.UP,15);}catch(Exception e){throw new AssertionError(e);}}
        public FlightControlBridge.GroundNeutralSnapshot requestHold(){holdRequests++;holdRequested=true;return new FlightControlBridge.GroundNeutralSnapshot(true,"",handle,nativeObservations>=2,4,nativePhysicsTime,nativeObservations,observedAt);}
        public void releaseHold(Object h){assertSame(handle,h);releases++;holdRequested=false;}
        public void clearOnlyOurs(){try{signals(controller).clear();}catch(Exception e){throw new AssertionError(e);}}
        void actualModeledNativePhysics(){if(holdRequested){nativePropulsion=0;servo=false;nativeTarget=0;servo=true;nativeObservations++;nativePhysicsTime+=.025;observedAt=System.nanoTime();}}
    }
    private record Fixture(RegisteredVehicleControllerBlockEntity controller,UUID body,RegisteredAircraftGroundMission mission,Port port){
        RegisteredVehicleControllerBlockEntity.GroundHoldEvidence evidence(boolean inStand){return new RegisteredVehicleControllerBlockEntity.GroundHoldEvidence(body,true,true,true,true,true,false,false,inStand,6,6);}
    }
    @Test void pausedTaxiReloadRebuildsOnlyNormalStopCommandsAndWaitsForActualNativeProcessing()throws Exception{
        var f=fixture();var c=f.controller();var p=f.port();
        assertFalse(c.maintainGroundHold(f.evidence(false),p),"first request is not an acknowledgement");
        assertEquals(15,c.signal(Direction.DOWN));assertEquals(15,p.groundThrottle);
        assertTrue(p.holdRequested,"paused ground phase outside cargo stand still needs native-neutral HOLD");
        assertEquals(95.5,p.nativePropulsion,"a request alone cannot claim force was cleared");
        p.actualModeledNativePhysics();assertFalse(c.maintainGroundHold(f.evidence(false),p));
        p.actualModeledNativePhysics();assertTrue(c.maintainGroundHold(f.evidence(false),p));
        assertEquals(0,p.nativePropulsion);assertEquals(0,p.nativeTarget);assertTrue(p.servo);
        assertEquals(15,c.signal(Direction.DOWN));assertFalse((boolean)get(c,"groundOutputsOwned"),"stop-HOLD is never drive ownership");
        assertTrue((boolean)get(c,"paused"));assertSame(f.mission(),get(c,"groundMission"));
        assertEquals(1,((RegisteredAircraftGroundMission)get(c,"groundMission")).state().pointIndex());
    }
    @Test void freshGroundRecoveryCanBeginBrakingBeforeTheAircraftIsAlreadyStopped()throws Exception{
        var f=fixture(); // Stop-only entry uses fresh support/binding and does not require a cargo stand first.
        f.controller().maintainGroundHold(f.evidence(false),f.port());
        assertEquals(15,f.controller().signal(Direction.DOWN));assertTrue(f.port().holdRequested);
    }
    @Test void completedParkingKeepsNeutralLeaseAfterMissionIsCleared()throws Exception{
        var f=fixture();var c=f.controller();set(c,"groundMission",null);set(c,"active",false);set(c,"paused",false);
        var parking=new RegisteredAircraftParkingBrake();parking.adopt(f.body());set(c,"parkingBrake",parking);
        c.maintainGroundHold(f.evidence(true),f.port());assertEquals(15,c.signal(Direction.DOWN));assertTrue(f.port().holdRequested);
        f.port().actualModeledNativePhysics();f.port().actualModeledNativePhysics();
        assertTrue(c.maintainGroundHold(f.evidence(true),f.port()));assertEquals(0,f.port().nativePropulsion);
        assertFalse((boolean)get(c,"groundOutputsOwned"));assertNull(get(c,"groundMission"));
    }
    @Test void missingSupportForeignManualChangedBodyOrUnconfirmedHardwareCannotClaimOutputs()throws Exception{
        for(int change=0;change<7;change++){
            var f=fixture();var e=f.evidence(false);
            var bad=new RegisteredVehicleControllerBlockEntity.GroundHoldEvidence(change==0?UUID.randomUUID():e.body(),
                    change!=1,change!=2,change!=3,change!=4,true,change==5,change==6,false,6,6);
            assertFalse(f.controller().maintainGroundHold(bad,f.port()));
            assertEquals(0,f.port().neutralCalls);assertEquals(0,f.port().brakeCalls);assertEquals(0,f.port().holdRequests);
        }
    }
    @Test void airborneStartingAndWaitReleaseNeverBecomeAutomaticPausedGroundHold()throws Exception{
        for(var phase:List.of(AircraftGroundMission.Phase.FC_STARTING,AircraftGroundMission.Phase.FC_ACTIVE,AircraftGroundMission.Phase.WAIT_FC_RELEASE)){
            var f=fixture();var s=f.mission().state();
            int point=phase==AircraftGroundMission.Phase.WAIT_FC_RELEASE?0:f.mission().plan().outbound().size()-1;
            set(f.controller(),"groundMission",f.mission().withState(new AircraftGroundMission.State(s.missionId(),s.physicalId(),s.dimensionId(),s.layoutRevision(),s.runwayId(),s.standId(),s.reservationId(),phase,point,0,-1,true)));
            assertFalse(f.controller().maintainGroundHold(f.evidence(false),f.port()));
            assertEquals(0,f.port().holdRequests);assertEquals(0,f.port().brakeCalls);
        }
    }
    @SuppressWarnings("unchecked")private static Map<Direction,Integer> signals(Object c)throws Exception{return(Map<Direction,Integer>)get(c,"signals");}
    private static Fixture fixture()throws Exception{
        Field uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);var c=(RegisteredVehicleControllerBlockEntity)((Unsafe)uf.get(null)).allocateInstance(RegisteredVehicleControllerBlockEntity.class);
        UUID body=UUID.randomUUID(),id=UUID.randomUUID();var reg=new ControllerRegistration(UUID.randomUUID(),"owner",LogisticsCarrier.Kind.AIR);reg.register(body,10,true);
        set(c,"registration",reg);set(c,"signals",new EnumMap<Direction,Integer>(Direction.class));set(c,"ports",new EnumMap<ControlChannel,Direction>(ControlChannel.class));
        set(c,"aircraftClass",AircraftClass.FIXED_WING);set(c,"groundCalibrationBody",body);set(c,"groundConfiguration",RegisteredAircraftGroundMission.Configuration.parse("2 8 30 15 0 2").confirmed());
        set(c,"parkingBrake",new RegisteredAircraftParkingBrake());set(c,"active",true);set(c,"paused",true);set(c,"status",ControllerStatus.FAULT);
        var plan=new AircraftGroundMission.Plan(id,body,"minecraft:overworld",21,"runwayA","standB",id.toString(),List.of(new Vec3(0,70,0),new Vec3(0,70,10),new Vec3(0,70,20)),1,List.of(new Vec3(1000,70,20),new Vec3(1000,70,0)),0,0,2,3,new AircraftGroundMission.Calibration(true,2,8,30,15,0));
        var state=new AircraftGroundMission.State(id,body,plan.dimensionId(),21,"runwayA","standB",id.toString(),AircraftGroundMission.Phase.TAXI_OUT,1,0,-1,true);
        var gm=new RegisteredAircraftGroundMission(new BlockPos(1,70,2),new BlockPos(1001,70,2),new GroundAssignment(4,"runwayA","standA","outA","inA"),new GroundAssignment(9,"runwayB","standB","outB","inB"),plan,state);
        set(c,"groundMission",gm);return new Fixture(c,body,gm,new Port(c));
    }
    private static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
}
