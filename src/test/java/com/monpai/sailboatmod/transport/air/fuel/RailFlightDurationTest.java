package com.monpai.sailboatmod.transport.air.fuel;

import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.rail.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailFlightDurationTest {
    static RailAircraftPath straight(double distance,double speed){return RailAircraftPath.compile(List.of(leg(RailAircraftPath.Phase.CRUISE,0,distance,speed)),-90);}
    static RailAircraftPath.Leg leg(RailAircraftPath.Phase phase,double from,double to,double speed){return new RailAircraftPath.Leg(phase,List.of(new Vec3(from,64,0),new Vec3(to,64,0)),speed,0,phase==RailAircraftPath.Phase.CRUISE);}
    static RailAircraftPath phased(){return RailAircraftPath.compile(List.of(
            leg(RailAircraftPath.Phase.TAXI_OUT,0,3,3),new RailAircraftPath.Leg(RailAircraftPath.Phase.HOLD_SHORT,List.of(new Vec3(3,64,0)),0,0,false),
            leg(RailAircraftPath.Phase.LINE_UP,3,4,3),leg(RailAircraftPath.Phase.TAKEOFF,4,104,25),leg(RailAircraftPath.Phase.CRUISE,104,204,25),
            leg(RailAircraftPath.Phase.APPROACH,204,304,15),leg(RailAircraftPath.Phase.LANDING_ROLL,304,404,15),leg(RailAircraftPath.Phase.TAXI_IN,404,414,3),
            new RailAircraftPath.Leg(RailAircraftPath.Phase.STAND_STABILIZING,List.of(new Vec3(414,64,0)),0,0,false)),-90);}
    static RailAircraftMission mission(RailAircraftPath path){var assignment=new GroundAssignment(1,"rw","stand","out","in");return RailAircraftMission.start(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO,assignment,new BlockPos(100,64,0),assignment,path);}
    static long advanceMission(RailAircraftMission mission){long ticks=0;while(mission.distance()<mission.path().length()){assertTrue(++ticks<10000);assertTrue(mission.commitStep(mission.proposeStep(true),true));}return ticks;}

    @Test void constantSpeedUsesExactDiscreteTicksAndIncludesOnlyRemainingDistance() {
        var path=straight(100,10);assertEquals(200,RailFlightDuration.estimateTicks(path,0));assertEquals(125,RailFlightDuration.estimateTicks(path,37.5));
        assertEquals(0,RailFlightDuration.estimateTicks(path,path.length()));assertEquals(21,RailFlightDuration.estimateTicks(straight(10.1,10),0));
    }
    @Test void exactPhaseBoundaryPreventsOneTickFromSkippingAShortLegOrHoldMarker() {
        var path=RailAircraftPath.compile(List.of(leg(RailAircraftPath.Phase.TAXI_OUT,0,.2,10),new RailAircraftPath.Leg(RailAircraftPath.Phase.HOLD_SHORT,List.of(new Vec3(.2,64,0)),0,0,false),leg(RailAircraftPath.Phase.CRUISE,.2,1.2,10)),-90);
        assertEquals(3,RailFlightDuration.estimateTicks(path,0));assertEquals(2,RailFlightDuration.estimateTicks(path,.2));
    }
    @Test void rampingTakeoffAndLandingAndFinalTaxiMatchTheActualFreshMissionProtocol() {
        var path=phased();var before=path.save();assertEquals(advanceMission(mission(path)),RailFlightDuration.estimateTicks(path,0));assertEquals(before,path.save());
        var takeoff=RailAircraftPath.compile(List.of(leg(RailAircraftPath.Phase.TAKEOFF,0,100,25)),-90);
        long ticks=RailFlightDuration.estimateTicks(takeoff,0);assertTrue(ticks>80&&ticks<667,"Ramp3→25 must not use constant25 or taxi3 for the whole leg");
        assertEquals(advanceMission(mission(takeoff)),ticks);
    }
    @Test void phasedRemainingBudgetMatchesOnlyUncommittedMissionSteps() {
        var path=phased();var mission=mission(path);while(mission.distance()<104)assertTrue(mission.commitStep(mission.proposeStep(true),true));
        assertEquals(104,mission.distance());assertEquals(advanceMission(mission),RailFlightDuration.estimateTicks(path,104));
    }
    @Test void arrivalDwellIsNotSilentlyAddedByTheDurationEstimator() {
        assertEquals(1,RailFlightDuration.estimateTicks(straight(1,20),0));
    }
    @Test void invalidAndOverflowingDistanceInputsFailClearlyRatherThanClamping() {
        var path=straight(10,10);for(double value:new double[]{-1,10.1,Double.NaN,Double.POSITIVE_INFINITY,Double.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->RailFlightDuration.estimateTicks(path,value));
        assertThrows(IllegalArgumentException.class,()->RailFlightDuration.estimateTicks(null,0));
    }
    @Test void millionStepLimitFailsClosedForRoutesThatWouldExhaustTheBoundedEstimator() {
        var error=assertThrows(IllegalArgumentException.class,()->RailFlightDuration.estimateTicks(straight(6000,.1),0));
        assertEquals("fuel_duration_step_limit",error.getMessage());
    }
}
