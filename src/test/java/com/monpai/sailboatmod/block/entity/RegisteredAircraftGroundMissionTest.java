package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.ControlOwnership;
import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredAircraftGroundMissionTest {
    private final UUID mission=UUID.randomUUID(), body=UUID.randomUUID(), task=UUID.randomUUID();
    private final GroundAssignment source=new GroundAssignment(4,"srcRunway","srcStand","srcOut","srcIn");
    private final GroundAssignment target=new GroundAssignment(9,"dstRunway","dstStand","dstOut","dstIn");
    private final BlockPos sourcePos=new BlockPos(1,70,2),targetPos=new BlockPos(1001,70,2);
    private AircraftGroundMission.Plan plan() {
        return new AircraftGroundMission.Plan(mission,body,"minecraft:overworld",21,"srcRunway","dstStand",mission.toString(),
                List.of(new Vec3(0,70,0),new Vec3(0,70,10),new Vec3(0,70,20)),1,
                List.of(new Vec3(1000,70,20),new Vec3(1000,70,0)),0,0,2,3,
                new AircraftGroundMission.Calibration(true,2,5,30,15,0));
    }
    private RegisteredAircraftGroundMission binding(AircraftGroundMission.Phase phase, int stable,boolean recovered) {
        var p=plan();var s=new AircraftGroundMission.State(mission,body,p.dimensionId(),21,"srcRunway","dstStand",mission.toString(),phase,0,stable,10,recovered);
        return new RegisteredAircraftGroundMission(sourcePos,targetPos,source,target,p,s);
    }
    @Test void bothExactAirportCoordinatesAndRevisionsMustMatch() {
        var b=binding(AircraftGroundMission.Phase.TAXI_OUT,0,false);
        assertTrue(b.matches(sourcePos,4,targetPos,9));
        assertFalse(b.matches(sourcePos,5,targetPos,9));
        assertFalse(b.matches(sourcePos,4,targetPos,10));
        assertFalse(b.matches(targetPos,4,sourcePos,9));
    }
    @Test void persistentBindingKeepsPhaseIndexAndBothAssignmentsButRevokesDwell() {
        var original=binding(AircraftGroundMission.Phase.STAND_STABILIZING,37,false);
        var restored=RegisteredAircraftGroundMission.loadOptional(original.save()).orElseThrow();
        assertEquals(source,restored.sourceAssignment());assertEquals(target,restored.targetAssignment());
        assertEquals(sourcePos,restored.sourceAirport());assertEquals(targetPos,restored.targetAirport());
        assertEquals(AircraftGroundMission.Phase.STAND_STABILIZING,restored.state().phase());
        assertEquals(0,restored.state().pointIndex());assertEquals(0,restored.state().stableTicks());assertTrue(restored.state().recovered());
    }
    @Test void malformedSavedBindingCannotBecomeLegacyDispatch() {
        CompoundTag tag=binding(AircraftGroundMission.Phase.FC_ACTIVE,0,false).save();tag.remove("TargetAssignment");
        assertTrue(RegisteredAircraftGroundMission.loadOptional(tag).isEmpty());
    }
    @Test void runwayCompletionCannotAuthorizeStandDelivery() {
        var b=binding(AircraftGroundMission.Phase.FC_ACTIVE,40,false);
        assertFalse(b.deliveryReady(targetPos,true,true,true,0,0));
        assertFalse(binding(AircraftGroundMission.Phase.TAXI_IN,40,false).deliveryReady(targetPos,true,true,true,0,0));
    }
    @Test void onlyFrozenTargetStandWithFortyFreshStableTicksCanDeliver() {
        assertTrue(binding(AircraftGroundMission.Phase.COMPLETE,40,false).deliveryReady(targetPos,true,true,true,.25,Math.toRadians(2)));
        assertFalse(binding(AircraftGroundMission.Phase.COMPLETE,39,false).deliveryReady(targetPos,true,true,true,0,0));
        assertFalse(binding(AircraftGroundMission.Phase.COMPLETE,40,false).deliveryReady(sourcePos,true,true,true,0,0));
        assertFalse(binding(AircraftGroundMission.Phase.COMPLETE,40,false).deliveryReady(targetPos,false,true,true,0,0));
        assertFalse(binding(AircraftGroundMission.Phase.COMPLETE,40,false).deliveryReady(targetPos,true,true,false,0,0));
        assertFalse(binding(AircraftGroundMission.Phase.COMPLETE,40,true).deliveryReady(targetPos,true,true,true,0,0));
    }
    @Test void startingTerminalWithoutActualBrokerLeaseIsNotFlightOwnership() {
        var unowned=new ControlOwnership(true,"",body,task,"fp",false,false,false);
        assertEquals(AircraftGroundMission.Lease.NONE,RegisteredAircraftGroundMission.lease(unowned,body,task,"fp",false));
        assertEquals(AircraftGroundMission.Lease.GROUND,RegisteredAircraftGroundMission.lease(unowned,body,task,"fp",true));
        assertEquals(AircraftGroundMission.Lease.FC,RegisteredAircraftGroundMission.lease(new ControlOwnership(true,"",body,task,"fp",true,true,false),body,task,"fp",true));
    }
    @Test void foreignAndUnprovenLeasesNeverGiveGroundAuthority() {
        assertEquals(AircraftGroundMission.Lease.FOREIGN,RegisteredAircraftGroundMission.lease(new ControlOwnership(true,"",body,task,"other",true,true,true),body,task,"fp",true));
        assertEquals(AircraftGroundMission.Lease.UNKNOWN,RegisteredAircraftGroundMission.lease(new ControlOwnership(false,"missing",body,task,"fp",false,false,false),body,task,"fp",true));
        assertEquals(AircraftGroundMission.Lease.UNKNOWN,RegisteredAircraftGroundMission.lease(new ControlOwnership(true,"",body,task,"fp",false,true,false),body,task,"fp",true));
    }
    @Test void onlyExactTaskProfileAndBodyCompletionCanEnterGroundArrival() {
        var expected=new com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.ExpectedState(1,2,3,"fp");
        var completed=new com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.TerminalSnapshot(true,"",false,true,true,"COMPLETED","NONE","",task,expected,3,3,body);
        assertTrue(RegisteredAircraftGroundMission.exactCompletion(completed,body,task,"fp"));
        assertFalse(RegisteredAircraftGroundMission.exactCompletion(completed,body,UUID.randomUUID(),"fp"));
        assertFalse(RegisteredAircraftGroundMission.exactCompletion(completed,body,task,"other"));
        assertFalse(RegisteredAircraftGroundMission.exactCompletion(completed,UUID.randomUUID(),task,"fp"));
    }
    @Test void groundRoutesMustJoinTheExactFixedWingTakeoffAndRolloutEnds() {
        var source=new com.monpai.sailboatmod.transport.air.airport.AirportFacility("s",com.monpai.sailboatmod.transport.air.airport.AirportFacility.Type.RUNWAY,new BlockPos(0,70,0),new BlockPos(0,70,100),20,12);
        var target=new com.monpai.sailboatmod.transport.air.airport.AirportFacility("t",com.monpai.sailboatmod.transport.air.airport.AirportFacility.Type.RUNWAY,new BlockPos(1000,70,0),new BlockPos(1000,70,100),20,12);
        assertTrue(RegisteredAircraftGroundMission.joinsRunwayEnds(source,target,new Vec3(.5,75,.5),new Vec3(1000.5,75,100.5),3));
        assertFalse(RegisteredAircraftGroundMission.joinsRunwayEnds(source,target,new Vec3(.5,75,50.5),new Vec3(1000.5,75,100.5),3));
        assertFalse(RegisteredAircraftGroundMission.joinsRunwayEnds(source,target,new Vec3(.5,75,.5),new Vec3(1000.5,75,80.5),3));
    }
    @Test void runwayJoinsCannotExceedTheActualNarrowTaxiCorridor() {
        var source=new com.monpai.sailboatmod.transport.air.airport.AirportFacility("s",com.monpai.sailboatmod.transport.air.airport.AirportFacility.Type.RUNWAY,new BlockPos(0,70,0),new BlockPos(0,70,100),20,12);
        var target=new com.monpai.sailboatmod.transport.air.airport.AirportFacility("t",com.monpai.sailboatmod.transport.air.airport.AirportFacility.Type.RUNWAY,new BlockPos(1000,70,0),new BlockPos(1000,70,100),20,12);
        assertFalse(RegisteredAircraftGroundMission.joinsRunwayEnds(source,target,new Vec3(.5,75,.5),new Vec3(1001.2,75,100.5),.5));
        assertFalse(RegisteredAircraftGroundMission.joinsRunwayEnds(source,target,new Vec3(1.2,75,.5),new Vec3(1000.5,75,100.5),.5));
        assertTrue(RegisteredAircraftGroundMission.joinsRunwayEnds(source,target,new Vec3(1,75,.5),new Vec3(1001,75,100.5),.5));
        assertFalse(RegisteredAircraftGroundMission.joinsRunwayEnds(source,target,new Vec3(.5,75,.5),new Vec3(1000.5,75,100.5),Double.NaN));
    }
    @Test void targetRunwayMustFitMeasuredStoppingDistanceIncludingHullAndMargin() {
        assertTrue(RegisteredAircraftGroundMission.stoppingFits(100,10,2,8));
        assertFalse(RegisteredAircraftGroundMission.stoppingFits(32,10,1,8));
        assertFalse(RegisteredAircraftGroundMission.stoppingFits(100,10,Double.NaN,8));
    }
    @Test void nativeTireSupportedRolloutAllowsHullAboveTheRunwaySurface() {
        var runway=new com.monpai.sailboatmod.transport.air.airport.AirportFacility("r",com.monpai.sailboatmod.transport.air.airport.AirportFacility.Type.RUNWAY,new BlockPos(0,70,0),new BlockPos(0,70,100),20,12);
        var hull=new net.minecraft.world.phys.AABB(-3,70.75,40,3,76,48);
        assertTrue(RegisteredAircraftGroundMission.rolloutReady(runway,hull,true,.25,Math.toRadians(2)));
        assertFalse(RegisteredAircraftGroundMission.rolloutReady(runway,hull,false,0,0));
        assertFalse(RegisteredAircraftGroundMission.rolloutReady(runway,hull.move(20,0,0),true,0,0));
        assertFalse(RegisteredAircraftGroundMission.rolloutReady(runway,hull.move(0,13,0),true,0,0));
        assertFalse(RegisteredAircraftGroundMission.rolloutReady(runway,hull.move(0,-2,0),true,0,0));
        assertFalse(RegisteredAircraftGroundMission.rolloutReady(runway,hull,true,.251,0));
        assertFalse(RegisteredAircraftGroundMission.rolloutReady(runway,hull,true,0,Math.toRadians(2.1)));
    }
    @Test void ownedGroundFaultCommandsNeutralAndBrakeAndRetainsTheHold() {
        var released=new ControlOwnership(true,"",body,task,"fp",false,false,false);
        var calibration=new AircraftGroundMission.Calibration(true,2,8,30,15,0);
        var decision=RegisteredAircraftGroundMission.stopDecision(true,false,body,groundEvidence(true),released,100,100,calibration);
        assertEquals(RegisteredAircraftGroundMission.StopMode.HOLD,decision.mode());
        assertTrue(decision.retainOwnership());assertNotNull(decision.outputs());
        assertEquals(15,decision.outputs().throttleSignal());assertEquals(15,decision.outputs().brake());
        assertEquals(0,decision.outputs().left());assertEquals(0,decision.outputs().right());
    }
    @Test void groundFaultYieldsToManualOrFcButWaitsForMissingSupport() {
        var released=new ControlOwnership(true,"",body,task,"fp",false,false,false);
        var calibration=new AircraftGroundMission.Calibration(true,2,8,30,15,0);
        var manual=RegisteredAircraftGroundMission.stopDecision(true,true,body,groundEvidence(true),released,100,100,calibration);
        assertEquals(RegisteredAircraftGroundMission.StopMode.YIELD,manual.mode());assertFalse(manual.retainOwnership());assertNull(manual.outputs());
        var fc=RegisteredAircraftGroundMission.stopDecision(true,false,body,groundEvidence(true),new ControlOwnership(true,"",body,task,"fp",true,true,false),100,100,calibration);
        assertEquals(RegisteredAircraftGroundMission.StopMode.YIELD,fc.mode());assertFalse(fc.retainOwnership());assertNull(fc.outputs());
        var unsupported=RegisteredAircraftGroundMission.stopDecision(true,false,body,groundEvidence(false),released,100,100,calibration);
        assertEquals(RegisteredAircraftGroundMission.StopMode.WAIT,unsupported.mode());assertTrue(unsupported.retainOwnership());assertNull(unsupported.outputs());
        var stale=RegisteredAircraftGroundMission.stopDecision(true,false,body,groundEvidence(true),released,100,99,calibration);
        assertEquals(RegisteredAircraftGroundMission.StopMode.WAIT,stale.mode());assertTrue(stale.retainOwnership());assertNull(stale.outputs());
        var wrongBody=RegisteredAircraftGroundMission.stopDecision(true,false,UUID.randomUUID(),groundEvidence(true),released,100,100,calibration);
        assertEquals(RegisteredAircraftGroundMission.StopMode.WAIT,wrongBody.mode());assertNull(wrongBody.outputs());
    }
    private com.monpai.sailboatmod.transport.physical.NativeGroundObservation groundEvidence(boolean supported) {
        return new com.monpai.sailboatmod.transport.physical.NativeGroundObservation(body,Vec3.ZERO,List.of(
                new com.monpai.sailboatmod.transport.physical.NativeGroundObservation.WheelContact(new Vec3(0,71,0),1,.5,supported),
                new com.monpai.sailboatmod.transport.physical.NativeGroundObservation.WheelContact(new Vec3(3,71,0),1,.5,supported),
                new com.monpai.sailboatmod.transport.physical.NativeGroundObservation.WheelContact(new Vec3(0,71,3),1,.5,supported)),true,"");
    }
    @Test void calibrationRequiresExplicitConfirmationAndFiniteMeasuredValues() {
        var draft=RegisteredAircraftGroundMission.Configuration.parse("1.5 8 30 15 0 2");
        assertFalse(draft.calibration().liveVerified());assertTrue(draft.confirmed().calibration().liveVerified());
        assertEquals("1.5 8.0 30.0 15 0 2.0",draft.text());
        assertThrows(IllegalArgumentException.class,()->RegisteredAircraftGroundMission.Configuration.parse("NaN 8 30 15 0 2"));
        assertThrows(IllegalArgumentException.class,()->RegisteredAircraftGroundMission.Configuration.parse("1 8 30 15 15 2"));
        assertThrows(IllegalArgumentException.class,()->RegisteredAircraftGroundMission.Configuration.parse("1 8 45 15 0 2"));
        assertThrows(IllegalArgumentException.class,()->RegisteredAircraftGroundMission.Configuration.parse("1 8 30 15 0 6"));
    }
}
