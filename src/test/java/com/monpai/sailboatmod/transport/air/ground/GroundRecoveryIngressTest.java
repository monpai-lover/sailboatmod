package com.monpai.sailboatmod.transport.air.ground;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundRecoveryIngressTest {
    private static final Vec3 FIRST=new Vec3(84.938553,81,515.581909),SECOND=new Vec3(34.5,81,512.5);
    private static final Vec3 CURRENT=new Vec3(126.59574890136719,83.65068817138672,515.76953125);
    private static final Vec3 FORWARD=new Vec3(-.9984201381737152,-.004500306231022328,-.056008704082548565);
    private final UUID body=UUID.randomUUID(),mission=UUID.randomUUID();
    private final Plan plan=new Plan(mission,body,"minecraft:overworld",4,"runway","stand",mission.toString(),
            List.of(FIRST,SECOND,new Vec3(.5,81,512.5)),1,List.of(new Vec3(-100,81,500),new Vec3(-50,81,500)),
            90,-90,1,3.5976602841628846,new Calibration(true,.75,32,30,15,0));
    private State state(Phase phase,int index){return new State(mission,body,plan.dimensionId(),4,"runway","stand",mission.toString(),phase,index,0,-1,false);}
    private GroundRecoveryIngress.Candidate candidate(){return GroundRecoveryIngress.candidate(plan,state(Phase.TAXI_OUT,1),CURRENT,FORWARD,11.402339715837115,6.06750825670315);}
    private GroundRecoveryIngress.Evidence evidence(){return new GroundRecoveryIngress.Evidence(true,true,true,true,true);}
    private GroundRecoveryIngress.Proof proof(){return GroundRecoveryIngress.authorize(candidate(),evidence());}
    private Observation observation(Vec3 position){return new Observation(body,plan.dimensionId(),4,mission.toString(),100,100,position,FORWARD,Vec3.ZERO,0,
            Support.CONTACT,Lease.GROUND,true,false,true,true,false,false,false,false,true,false);}

    @Test void actualBoundedReverseEntryUsesSharedGeometryWithoutRewritingPlan(){
        var c=candidate();assertNotNull(c,"A stopped bound pose inside the original-width bounded reverse strip must reach validation");
        assertTrue(TaxiRouteGeometry.assess(c.validationRequest()).admitted(),"Use the existing finite-radius and stopping checks on the validation-only extension");
        assertEquals(30,c.width(),1e-9);assertEquals(81,c.scanStart().y,1e-9);assertEquals(FIRST,c.scanEnd());
        assertEquals(FIRST,plan.outbound().getFirst());assertEquals(1,plan.holdPointIndex());
    }
    @Test void explicitEvidenceEnablesExistingFollowerTowardSamePointOneWithoutInstantProgress(){
        var proof=proof();assertNotNull(proof);var original=List.copyOf(plan.outbound());
        var result=step(plan,state(Phase.TAXI_OUT,1),observation(CURRENT),proof);
        assertEquals(Phase.TAXI_OUT,result.state().phase());assertEquals(1,result.state().pointIndex());
        assertEquals("taxiing",result.action().reason());assertFalse(result.action().cargoReady());
        assertEquals(0,result.action().outputs().throttleSignal());assertEquals(original,plan.outbound());
        assertEquals(mission,result.state().missionId());assertEquals(mission.toString(),result.state().reservationId());
    }
    @Test void originalThreeArgumentStepRemainsClosedWithoutIngressProof(){
        var result=step(plan,state(Phase.TAXI_OUT,1),observation(CURRENT));
        assertEquals(Phase.NEEDS_REVIEW,result.state().phase());assertEquals("outside_taxi_corridor",result.action().reason());
        assertFalse(result.action().cargoReady());assertEquals(15,result.action().outputs().brake());
    }
    @Test void allFreshTerrainSupportClaimsAndLoadedEvidenceIsRequiredBeforeIssuingProof(){
        var c=candidate();assertNotNull(c);
        assertNull(GroundRecoveryIngress.authorize(c,new GroundRecoveryIngress.Evidence(false,true,true,true,true)));
        assertNull(GroundRecoveryIngress.authorize(c,new GroundRecoveryIngress.Evidence(true,false,true,true,true)));
        assertNull(GroundRecoveryIngress.authorize(c,new GroundRecoveryIngress.Evidence(true,true,false,true,true)));
        assertNull(GroundRecoveryIngress.authorize(c,new GroundRecoveryIngress.Evidence(true,true,true,false,true)));
        assertNull(GroundRecoveryIngress.authorize(c,new GroundRecoveryIngress.Evidence(true,true,true,true,false)));
        assertNotNull(GroundRecoveryIngress.authorize(c,evidence()));
    }
    @Test void ingressDoesNotGrowWithDriftOrGrantOtherPlanIndexPhaseOrLateralEscape(){
        var proof=proof();assertNotNull(proof);
        assertTrue(GroundRecoveryIngress.allows(proof,plan,state(Phase.TAXI_OUT,1),CURRENT,FORWARD));
        assertFalse(GroundRecoveryIngress.allows(proof,plan,state(Phase.TAXI_OUT,1),CURRENT.add(1,0,0),FORWARD),"Backward drift cannot extend the captured proof");
        assertFalse(GroundRecoveryIngress.allows(proof,plan,state(Phase.TAXI_OUT,1),CURRENT.add(0,0,10),FORWARD));
        assertFalse(GroundRecoveryIngress.allows(proof,plan,state(Phase.TAXI_OUT,0),CURRENT,FORWARD));
        assertFalse(GroundRecoveryIngress.allows(proof,plan,state(Phase.TAXI_IN,1),CURRENT,FORWARD));
        var copy=new Plan(plan.missionId(),plan.physicalId(),plan.dimensionId(),plan.layoutRevision(),plan.runwayId(),plan.standId(),plan.reservationId(),plan.outbound(),plan.holdPointIndex(),plan.inbound(),plan.runwayYawDegrees(),plan.standYawDegrees(),plan.speedLimit(),plan.corridorHalfWidth(),plan.calibration());
        assertFalse(GroundRecoveryIngress.allows(proof,copy,state(Phase.TAXI_OUT,1),CURRENT,FORWARD),"A new Plan object needs a new actual validation");
    }
    @Test void reenteringOriginalSegmentRetiresExceptionWithoutChangingSavedProgress(){
        var proof=proof();assertNotNull(proof);assertFalse(GroundRecoveryIngress.completed(proof,CURRENT));
        assertTrue(GroundRecoveryIngress.completed(proof,FIRST.lerp(SECOND,.1)));
        assertFalse(GroundRecoveryIngress.allows(proof,plan,state(Phase.TAXI_OUT,1),FIRST.lerp(SECOND,.1),FORWARD));
        var result=step(plan,state(Phase.TAXI_OUT,1),observation(FIRST.lerp(SECOND,.1)));
        assertEquals("taxiing",result.action().reason());assertEquals(1,result.state().pointIndex());
    }
    @Test void excessiveExtensionReverseHeadingAndLateralEscapeCannotBecomeCandidates(){
        var direction=SECOND.subtract(FIRST).normalize();double length=FIRST.distanceTo(SECOND);
        assertNull(GroundRecoveryIngress.candidate(plan,state(Phase.TAXI_OUT,1),FIRST.subtract(direction.scale(length+1)),FORWARD,11.4,6));
        assertNull(GroundRecoveryIngress.candidate(plan,state(Phase.TAXI_OUT,1),CURRENT,FORWARD.scale(-1),11.4,6));
        assertNull(GroundRecoveryIngress.candidate(plan,state(Phase.TAXI_OUT,1),CURRENT.add(0,0,10),FORWARD,11.4,6));
        assertNull(GroundRecoveryIngress.candidate(plan,state(Phase.TAXI_OUT,2),CURRENT,FORWARD,11.4,6));
    }
    @Test void anotherAircraftSizeMustPayForItsActualHullAndCannotBorrowOldCorridorWidth(){
        var c=GroundRecoveryIngress.candidate(plan,state(Phase.TAXI_OUT,1),CURRENT,FORWARD,9,5);
        assertNotNull(c);assertEquals(2*(9+plan.corridorHalfWidth()),c.width(),1e-9);
        assertTrue(TaxiRouteGeometry.assess(c.validationRequest()).admitted());
        assertNull(GroundRecoveryIngress.candidate(plan,state(Phase.TAXI_OUT,1),CURRENT,FORWARD,31,6),"The real total sweep must still fit the existing width cap");
    }
}
