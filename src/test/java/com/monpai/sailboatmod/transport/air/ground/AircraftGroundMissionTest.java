package com.monpai.sailboatmod.transport.air.ground;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission.*;
import static org.junit.jupiter.api.Assertions.*;

class AircraftGroundMissionTest {
    private final UUID physical=UUID.randomUUID();
    private final Plan plan=new Plan(UUID.randomUUID(),physical,"minecraft:overworld",4,"runway","stand","reservation",
            List.of(new Vec3(0,0,0),new Vec3(10,0,0),new Vec3(20,0,0)),1,
            List.of(new Vec3(20,0,0),new Vec3(10,0,0),new Vec3(0,0,0)), -90,90,2,3,
            new Calibration(true,1,4,30,15,0));
    private State phase(Phase phase,int index) { return new State(plan.missionId(),physical,plan.dimensionId(),4,"runway","stand","reservation",phase,index,0,-1,false); }
    private Observation observe(double x,double speed,Lease lease) {
        return new Observation(physical,plan.dimensionId(),4,"reservation",100,100,new Vec3(x,0,0),new Vec3(1,0,0),new Vec3(speed,0,0),0,
                Support.CONTACT,lease,true,false,true,true,true,true,false,true,true,false);
    }
    private Observation flags(Observation o,Support support,Lease lease,boolean manual,boolean neutral,boolean complete,boolean released,boolean stand) {
        return new Observation(o.physicalId(),o.dimensionId(),o.layoutRevision(),o.reservationId(),o.tick(),o.sampledTick(),o.position(),o.forward(),o.velocity(),o.angularSpeed(),support,lease,true,manual,o.pathClear(),o.runwayPermit(),o.runwayAligned(),neutral,complete,true,released,stand);
    }
    @Test void taxiOutUsesCalibratedInverseThrottleAndPassesIntermediateNodesWithoutStopping() {
        Step result=step(plan,departure(plan),observe(1,0.5,Lease.GROUND));
        assertEquals(1,result.state().pointIndex());
        assertTrue(result.action().outputs().throttleSignal()<15);
        assertEquals(0,result.action().outputs().brake());
    }
    @Test void holdLineMustStopBeforeLineUpAndFcStartsOnlyAfterNeutralOutputAcknowledgement() {
        Step moving=step(plan,phase(Phase.TAXI_OUT,1),observe(10,1,Lease.GROUND));
        assertEquals(Phase.TAXI_OUT,moving.state().phase()); assertEquals(15,moving.action().outputs().brake());
        Step stopped=step(plan,phase(Phase.HOLD_LINE,1),observe(10,0,Lease.GROUND));
        assertEquals(Phase.LINE_UP,stopped.state().phase()); assertEquals(Handshake.NONE,stopped.action().handshake());
        Step ready=step(plan,phase(Phase.LINE_UP,2),observe(20,0,Lease.GROUND));
        assertEquals(Phase.FC_STARTING,ready.state().phase()); assertEquals(Handshake.NONE,ready.action().handshake());
        Observation o=observe(20,0,Lease.GROUND);
        assertEquals(Handshake.NONE,step(plan,phase(Phase.FC_STARTING,2),flags(o,Support.CONTACT,Lease.GROUND,false,false,false,true,false)).action().handshake());
        assertEquals(Handshake.START_FC,step(plan,phase(Phase.FC_STARTING,2),o).action().handshake());
        assertEquals(Phase.FC_ACTIVE,step(plan,phase(Phase.FC_STARTING,2),observe(20,0,Lease.FC)).state().phase());
    }
    @Test void touchdownAndRolloutDoNotEnableGroundUntilFcCompletionAndExplicitReleaseProof() {
        Observation landed=flags(observe(20,0,Lease.FC),Support.CONTACT,Lease.FC,false,true,true,false,false);
        Step wait=step(plan,phase(Phase.FC_ACTIVE,2),landed);
        assertEquals(Phase.WAIT_FC_RELEASE,wait.state().phase()); assertNull(wait.action().outputs());
        assertEquals(Handshake.RELEASE_FC,wait.action().handshake());
        assertNull(step(plan,wait.state(),landed).action().outputs());
        Observation noRelease=flags(observe(20,0,Lease.NONE),Support.CONTACT,Lease.NONE,false,true,true,false,false);
        assertEquals(Handshake.NONE,step(plan,phase(Phase.WAIT_FC_RELEASE,0),noRelease).action().handshake());
        Observation released=flags(noRelease,Support.CONTACT,Lease.NONE,false,true,true,true,false);
        assertEquals(Handshake.ACQUIRE_GROUND,step(plan,phase(Phase.WAIT_FC_RELEASE,0),released).action().handshake());
    }
    @Test void nativeCompletedFlightMayHaveAlreadyReleasedItsLeaseButStillNeedsExactBoundProof() {
        Observation released=flags(observe(20,0,Lease.NONE),Support.CONTACT,Lease.NONE,false,true,true,true,false);
        Step result=step(plan,phase(Phase.FC_ACTIVE,2),released);
        assertEquals(Phase.WAIT_FC_RELEASE,result.state().phase());assertNull(result.action().outputs());
        Observation unbound=new Observation(physical,plan.dimensionId(),4,"reservation",100,100,released.position(),released.forward(),released.velocity(),0,
                Support.CONTACT,Lease.NONE,false,false,true,true,true,true,true,true,true,false);
        assertEquals(Phase.NEEDS_REVIEW,step(plan,phase(Phase.FC_ACTIVE,2),unbound).state().phase());
    }
    @Test void taxiGeometryUsesHorizontalCoordinatesWhileNativeContactProvesVerticalSupport() {
        Observation elevated=new Observation(physical,plan.dimensionId(),4,"reservation",100,100,new Vec3(20,3,0),new Vec3(1,0,0),Vec3.ZERO,0,
                Support.CONTACT,Lease.GROUND,true,false,true,true,true,true,false,true,true,false);
        assertEquals(Phase.FC_STARTING,step(plan,phase(Phase.LINE_UP,2),elevated).state().phase());
        Observation elevatedAir=flags(elevated,Support.AIRBORNE,Lease.GROUND,false,true,false,true,false);
        assertEquals(Phase.NEEDS_REVIEW,step(plan,phase(Phase.LINE_UP,2),elevatedAir).state().phase());
    }
    @Test void airborneFcMissionNeverReceivesGroundHoldEvenOnManualOrStaleData() {
        Observation flight=flags(observe(200,30,Lease.FC),Support.AIRBORNE,Lease.FC,false,true,false,false,false);
        assertNull(step(plan,phase(Phase.FC_ACTIVE,2),flight).action().outputs());
        assertNull(step(plan,phase(Phase.FC_ACTIVE,2),flags(flight,Support.AIRBORNE,Lease.FC,true,true,false,false,false)).action().outputs());
    }
    @Test void manualInputRevokesGroundAutomationWithoutOverwritingTheHuman() {
        Step result=step(plan,departure(plan),flags(observe(0,0,Lease.GROUND),Support.CONTACT,Lease.GROUND,true,true,false,true,false));
        assertEquals(Phase.NEEDS_REVIEW,result.state().phase()); assertNull(result.action().outputs());
        assertEquals(Handshake.RELEASE_GROUND,result.action().handshake());
    }
    @Test void staleGeometryOrForeignReservationCannotAdvance() {
        Observation o=observe(5,1,Lease.GROUND);
        Observation stale=new Observation(physical,o.dimensionId(),5,"foreign",100,90,o.position(),o.forward(),o.velocity(),0,Support.CONTACT,Lease.GROUND,true,false,true,true,true,true,false,true,true,false);
        Step result=step(plan,departure(plan),stale);
        assertEquals(Phase.NEEDS_REVIEW,result.state().phase()); assertEquals(0,result.state().pointIndex()); assertFalse(result.action().cargoReady());
    }
    @Test void standNeedsWholeHullCorrectHeadingAndContinuousFreshStableObservations() {
        State s=phase(Phase.TAXI_IN,2);
        Observation wrongHeading=flags(observe(0,0,Lease.GROUND),Support.CONTACT,Lease.GROUND,false,true,true,true,true);
        assertFalse(step(plan,s,wrongHeading).action().cargoReady());
        for(int i=0;i<45;i++) {
            Observation o=new Observation(physical,plan.dimensionId(),4,"reservation",100+i,100+i,Vec3.ZERO,new Vec3(-1,0,0),Vec3.ZERO,0,
                    Support.CONTACT,Lease.GROUND,true,false,true,true,true,true,true,true,true,true);
            Step r=step(plan,s,o); s=r.state();
            if(i<39) assertFalse(r.action().cargoReady());
        }
        assertEquals(Phase.COMPLETE,s.phase());
    }
    @Test void repeatedPhysicsSamplesAndMissingTicksCannotAccumulateStandDwell() {
        State s=new State(plan.missionId(),physical,plan.dimensionId(),4,"runway","stand","reservation",Phase.STAND_STABILIZING,2,39,99,false);
        Observation repeated=new Observation(physical,plan.dimensionId(),4,"reservation",100,99,Vec3.ZERO,new Vec3(-1,0,0),Vec3.ZERO,0,
                Support.CONTACT,Lease.GROUND,true,false,true,true,true,true,true,true,true,true);
        assertFalse(step(plan,s,repeated).action().cargoReady());
        Observation gap=new Observation(physical,plan.dimensionId(),4,"reservation",102,102,Vec3.ZERO,new Vec3(-1,0,0),Vec3.ZERO,0,
                Support.CONTACT,Lease.GROUND,true,false,true,true,true,true,true,true,true,true);
        assertEquals(1,step(plan,s,gap).state().stableTicks());
    }
    @Test void restartPreservesProgressButRequiresFreshProofAndCannotReplayCargoCompletion() {
        State restored=recovered(phase(Phase.COMPLETE,2));
        Step result=step(plan,restored,observe(0,0,Lease.GROUND));
        assertFalse(result.action().cargoReady()); assertEquals(Phase.NEEDS_REVIEW,result.state().phase()); assertEquals(2,result.state().pointIndex());
    }
    @Test void anOvershotStopPointCannotCommandForwardThrustAwayFromItsTarget() {
        Step result=step(plan,phase(Phase.TAXI_OUT,1),observe(12,0,Lease.GROUND));
        assertEquals(15,result.action().outputs().throttleSignal());assertEquals(15,result.action().outputs().brake());
        assertEquals(Phase.NEEDS_REVIEW,result.state().phase());
    }
    @Test void throttleUsesOnlyMeasuredEndpointsAndRetainsItsDecisionInsideSpeedHysteresis() {
        State s=phase(Phase.TAXI_OUT,1);
        // Start powered below 80%, retain power until the upper threshold, then retain coast until below 80%.
        double[] speeds={.5,1.8,2,1.8,1.5};int[] expected={0,0,15,15,0};
        for(int i=0;i<speeds.length;i++) {
            Observation base=observe(2,speeds[i],Lease.GROUND);
            Observation o=new Observation(base.physicalId(),base.dimensionId(),base.layoutRevision(),base.reservationId(),100+i,100+i,
                    base.position(),base.forward(),base.velocity(),base.angularSpeed(),base.support(),base.lease(),base.leaseBoundToMission(),
                    base.manualOverride(),base.pathClear(),base.runwayPermit(),base.runwayAligned(),base.groundOutputsNeutral(),
                    base.fcMissionComplete(),base.fcSafeToRelease(),base.fcReleaseConfirmed(),base.wholeHullInStand());
            Step result=step(plan,s,o);s=result.state();assertEquals(expected[i],result.action().outputs().throttleSignal(),"speed="+speeds[i]);
        }
        // Endpoints may be arbitrary calibrated native levels; intermediate redstone levels are never inferred.
        Plan reverse=new Plan(plan.missionId(),physical,plan.dimensionId(),4,"runway","stand","reservation",plan.outbound(),1,
                plan.inbound(),-90,90,2,3,new Calibration(true,1,4,30,2,13));
        assertEquals(13,step(reverse,departure(reverse),observe(1,.5,Lease.GROUND)).action().outputs().throttleSignal());
    }
    @Test void unverifiedHardwareCannotDriveAndStoppingDistanceUsesMeasuredDeceleration() {
        Plan unverified=new Plan(plan.missionId(),physical,plan.dimensionId(),4,"runway","stand","reservation",plan.outbound(),1,plan.inbound(),-90,90,2,3,new Calibration(false,1,4,30,15,0));
        assertNull(step(unverified,departure(unverified),observe(0,0,Lease.GROUND)).action().outputs());
        Step fast=step(plan,phase(Phase.TAXI_OUT,1),observe(7,3,Lease.GROUND));
        assertEquals(15,fast.action().outputs().brake()); assertEquals(15,fast.action().outputs().throttleSignal());
    }
}
