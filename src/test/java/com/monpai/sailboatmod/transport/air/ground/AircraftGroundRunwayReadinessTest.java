package com.monpai.sailboatmod.transport.air.ground;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission.*;
import static org.junit.jupiter.api.Assertions.*;
class AircraftGroundRunwayReadinessTest {
    private final UUID body=UUID.randomUUID(),id=UUID.randomUUID();
    private final Plan plan=new Plan(id,body,"minecraft:overworld",4,"runway","stand","r",List.of(new Vec3(0,81,0),new Vec3(10,81,0),new Vec3(20,81,0)),1,List.of(new Vec3(100,81,0),new Vec3(90,81,0)),-90,90,1,3,new Calibration(true,.75,32,30,15,0));
    private State state(Phase p){return new State(id,body,plan.dimensionId(),4,"runway","stand","r",p,2,0,-1,false);}
    private Observation observation(double x,Vec3 forward,Lease lease,long sampled,boolean aligned){return new Observation(body,plan.dimensionId(),4,"r",100,sampled,new Vec3(x,83.65,0),forward,Vec3.ZERO,0,Support.CONTACT,lease,true,false,true,true,aligned,true,false,true,true,false);}
    private Observation stopped(boolean aligned){return observation(20,new Vec3(1,0,0),Lease.GROUND,100,aligned);}
    private void held(Step s){assertEquals(Phase.LINE_UP,s.state().phase());assertEquals(Handshake.NONE,s.action().handshake());assertEquals("waiting_runway_validation",s.action().reason());assertEquals(15,s.action().outputs().throttleSignal());assertEquals(15,s.action().outputs().brake());}
    @Test void lineUpAtFinalPointWaitsWithBrakesInsteadOfHardFaultingOnPendingScan(){held(step(plan,state(Phase.LINE_UP),stopped(false),null,GroundRunwayReadiness.WAITING));}
    @Test void startingBeforeStartReturnsToLineUpOnPendingRevalidation(){held(step(plan,state(Phase.FC_STARTING),stopped(false),null,GroundRunwayReadiness.WAITING));}
    @Test void realReadyAndBlockedKeepTheirExistingSafetyMeaning(){
        assertEquals(Phase.FC_STARTING,step(plan,state(Phase.LINE_UP),stopped(true),null,GroundRunwayReadiness.READY).state().phase());
        assertEquals(Handshake.START_FC,step(plan,state(Phase.FC_STARTING),stopped(true),null,GroundRunwayReadiness.READY).action().handshake());
        assertEquals(Phase.NEEDS_REVIEW,step(plan,state(Phase.LINE_UP),stopped(false),null,GroundRunwayReadiness.BLOCKED).state().phase());
        assertEquals(Phase.NEEDS_REVIEW,step(plan,state(Phase.FC_STARTING),stopped(false),null,GroundRunwayReadiness.BLOCKED).state().phase());
    }
    @Test void pendingCannotHideBadHeadingForeignLeaseOrStaleObservation(){
        for(Observation bad:List.of(observation(20,new Vec3(0,0,1),Lease.GROUND,100,false),observation(20,new Vec3(1,0,0),Lease.FOREIGN,100,false),observation(20,new Vec3(1,0,0),Lease.GROUND,97,false))){
            assertEquals(Phase.NEEDS_REVIEW,step(plan,state(Phase.LINE_UP),bad,null,GroundRunwayReadiness.WAITING).state().phase());
            assertEquals(Phase.NEEDS_REVIEW,step(plan,state(Phase.FC_STARTING),bad,null,GroundRunwayReadiness.WAITING).state().phase());
        }
    }
    @Test void oldBooleanOverloadsStillTreatFalseAsBlocked(){assertEquals(Phase.NEEDS_REVIEW,step(plan,state(Phase.LINE_UP),stopped(false)).state().phase());assertEquals(Phase.NEEDS_REVIEW,step(plan,state(Phase.FC_STARTING),stopped(false),null).state().phase());}
    @Test void pendingDoesNotStopLineUpBeforeReachingTheFinalPoint(){var s=step(plan,state(Phase.LINE_UP),observation(15,new Vec3(1,0,0),Lease.GROUND,100,false),null,GroundRunwayReadiness.WAITING);assertEquals(Phase.LINE_UP,s.state().phase());assertEquals("taxiing",s.action().reason());assertEquals(0,s.action().outputs().brake());}
    @Test void acknowledgedNativeFlightLeaseAlwaysKeepsFlightAuthority(){var s=step(plan,state(Phase.FC_STARTING),observation(20,new Vec3(1,0,0),Lease.FC,100,false),null,GroundRunwayReadiness.WAITING);assertEquals(Phase.FC_ACTIVE,s.state().phase());assertNull(s.action().outputs());assertEquals(Handshake.NONE,s.action().handshake());}
}
