package com.monpai.sailboatmod.transport.air.rail;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.air.rail.RailAircraftPathTest.*;

class RailAircraftMissionTest {
    static Object mission(Object path) {
        return invoke(type("RailAircraftMission"),"start",UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",
                BlockPos.ZERO,new GroundAssignment(2,"rw","stand","out","in"),new BlockPos(100,64,0),
                new GroundAssignment(3,"rw","stand","out","in"),path);
    }
    @Test void onlyConfirmedCurrentProposalCommitsExactlyOneGameTick() {
        Object m=mission(straight(100,10)),first=invoke(m,"proposeStep",true);
        assertEquals(.5,number(first,"targetDistance"));assertEquals(0,number(m,"distance"));
        assertEquals(false,invoke(m,"commitStep",first,false));assertEquals(0,number(m,"distance"));
        assertEquals(true,invoke(m,"commitStep",first,true));assertEquals(.5,number(m,"distance"));
        assertEquals(false,invoke(m,"commitStep",first,true));assertEquals(.5,number(m,"distance"));
    }
    @Test void pausedUnknownAndOccupiedConditionsCannotAdvanceAndBoundaryCannotBeSkipped() {
        Object p=compile(-90,leg("TAXI_OUT",List.of(new net.minecraft.world.phys.Vec3(0,64,0),new net.minecraft.world.phys.Vec3(.2,64,0)),10,0,false),
                leg("HOLD_SHORT",List.of(new net.minecraft.world.phys.Vec3(.2,64,0)),0,0,false),
                leg("LINE_UP",List.of(new net.minecraft.world.phys.Vec3(.2,64,0),new net.minecraft.world.phys.Vec3(10,64,0)),10,0,false));
        Object m=mission(p);invoke(m,"setPaused",true);
        assertEquals(0,number(invoke(m,"proposeStep",true),"targetDistance"));
        invoke(m,"setPaused",false);invoke(m,"waitFor","unloaded");
        assertEquals(0,number(invoke(m,"proposeStep",true),"targetDistance"));
        invoke(m,"clearWait");Object step=invoke(m,"proposeStep",true);assertEquals(.2,number(step,"targetDistance"));
        assertEquals(true,invoke(m,"commitStep",step,true));
        assertEquals(.2,number(invoke(m,"proposeStep",false),"targetDistance"));
        assertEquals(.7,number(invoke(m,"proposeStep",true),"targetDistance"),1e-10);
    }
    @Test void fortyDistinctActualStandTicksAreRequiredAndCompletionIsIdempotent() {
        Object m=mission(straight(1,20));Object s=invoke(m,"proposeStep",true);invoke(m,"commitStep",s,true);
        assertEquals(false,invoke(m,"readyToComplete"));
        for(long tick=1;tick<=39;tick++){invoke(m,"observeStand",tick,true);invoke(m,"observeStand",tick,true);}
        assertEquals(39,number(m,"actualStandTicks"));assertEquals(false,invoke(m,"readyToComplete"));
        invoke(m,"observeStand",40L,true);assertEquals(true,invoke(m,"readyToComplete"));
        assertEquals(true,invoke(m,"complete"));assertEquals(false,invoke(m,"complete"));
    }
    @Test void savedProgressDoesNotReuseStaleStandDwellAfterRestart() {
        Object m=mission(straight(1,20));invoke(m,"commitStep",invoke(m,"proposeStep",true),true);
        for(long tick=1;tick<=39;tick++)invoke(m,"observeStand",tick,true);
        CompoundTag saved=(CompoundTag)invoke(m,"save");assertEquals(39,saved.getInt("ActualStandTicks"));
        Object copy=invoke(type("RailAircraftMission"),"load",saved);
        assertEquals(1,number(copy,"distance"));assertEquals(invoke(m,"bodyId"),invoke(copy,"bodyId"));
        assertEquals(invoke(m,"sourceAssignment"),invoke(copy,"sourceAssignment"));
        assertEquals(0,number(copy,"actualStandTicks"));assertEquals(false,invoke(copy,"readyToComplete"));
    }
    @Test void anInterruptedStandObservationResetsTheContinuousCounter() {
        Object m=mission(straight(1,20));invoke(m,"commitStep",invoke(m,"proposeStep",true),true);
        invoke(m,"observeStand",1L,true);invoke(m,"observeStand",2L,true);invoke(m,"observeStand",3L,false);
        assertEquals(0,number(m,"actualStandTicks"));
        invoke(m,"observeStand",4L,true);invoke(m,"observeStand",6L,true);
        assertEquals(1,number(m,"actualStandTicks"),"A missing actual tick breaks the continuous dwell");
    }
    @Test void identityShapeDistanceAndPausedStateArePersistedStrictly() {
        Object m=mission(straight(100,10));invoke(m,"commitStep",invoke(m,"proposeStep",true),true);
        invoke(m,"setPaused",true);invoke(m,"waitFor","occupied");CompoundTag saved=(CompoundTag)invoke(m,"save");
        Object copy=invoke(type("RailAircraftMission"),"load",saved);
        assertEquals(.5,number(copy,"distance"));assertEquals(true,invoke(copy,"paused"));assertEquals("occupied",invoke(copy,"waitReason"));
        for(double bad:new double[]{Double.NaN,-1,101}){CompoundTag t=saved.copy();t.putDouble("Distance",bad);assertThrows(IllegalArgumentException.class,()->invoke(type("RailAircraftMission"),"load",t));}
        CompoundTag t=saved.copy();t.putString("PathHash","incorrect");assertThrows(IllegalArgumentException.class,()->invoke(type("RailAircraftMission"),"load",t));
        CompoundTag missing=saved.copy();missing.remove("BodyId");assertThrows(IllegalArgumentException.class,()->invoke(type("RailAircraftMission"),"load",missing));
    }
    @Test void completedJournalRemainsTerminalAcrossRepeatedSaveLoadAndLaterObservations() {
        RailAircraftMission m=(RailAircraftMission)mission(straight(1,20));m.commitStep(m.proposeStep(true),true);
        for(long tick=1;tick<=40;tick++)m.observeStand(tick,true);assertTrue(m.complete());
        m.observeStand(41,false);
        RailAircraftMission copy=RailAircraftMission.load(RailAircraftMission.load(m.save()).save());
        assertTrue(copy.completed());assertFalse(copy.complete());assertEquals(1,copy.distance());
    }
    @Test void invalidOrInvalidatedStepCannotAuthorizeProgress() {
        RailAircraftMission m=(RailAircraftMission)mission(straight(100,10));RailAircraftMission.Step step=m.proposeStep(true);
        assertFalse(m.commitStep(new RailAircraftMission.Step(0,.5,null,0),true));
        m.waitFor("unloaded");assertFalse(m.commitStep(step,true));assertEquals(0,m.distance());
    }
    @Test void aDifferentUnproposedTargetOrVisualSpeedDoesNotCommit() {
        RailAircraftMission m=(RailAircraftMission)mission(straight(100,10));RailAircraftMission.Step step=m.proposeStep(true);
        assertFalse(m.commitStep(new RailAircraftMission.Step(0,.25,m.path().sample(.25),step.revision()),true));
        var s=step.sample();var changed=new RailAircraftPath.Sample(s.position(),s.tangent(),s.bankRadians(),s.phase(),99,s.pitchRadians());
        assertFalse(m.commitStep(new RailAircraftMission.Step(0,.5,changed,step.revision()),true));
        assertTrue(m.commitStep(step,true));
    }
    @Test void completedJournalIgnoresLaterPauseAndWaitRequests() {
        RailAircraftMission m=(RailAircraftMission)mission(straight(1,20));m.commitStep(m.proposeStep(true),true);
        for(long tick=1;tick<=40;tick++)m.observeStand(tick,true);assertTrue(m.complete());
        m.setPaused(true);m.waitFor("occupied");
        RailAircraftMission copy=RailAircraftMission.load(m.save());
        assertTrue(copy.completed());assertFalse(copy.paused());assertEquals("",copy.waitReason());
    }
}
