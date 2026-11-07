package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.rail.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredAircraftRailControllerTest {
    RailAircraftMission fixture() {
        var p=RailAircraftPath.compile(List.of(new RailAircraftPath.Leg(RailAircraftPath.Phase.CRUISE,List.of(new Vec3(0,80,0),new Vec3(100,80,0)),10,0,true)), -90);
        var a=new GroundAssignment(1,"runway","stand","out","in");
        return RailAircraftMission.start(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",new BlockPos(0,64,0),a,new BlockPos(100,64,0),a,p);
    }
    CompoundTag tag(RailAircraftMission mission) {var t=new CompoundTag();t.put("Mission",mission.save());t.putString("Forward","west");return t;}
    @Test void outstandingMotionSurvivesReloadWithoutCreditingUnobservedDistance() {
        var m=fixture();var t=tag(m);t.putDouble("PendingDistance",m.proposeStep(true).targetDistance());
        var c=RegisteredAircraftRailController.load(t);assertFalse(c.invalid());assertEquals(0,c.mission().distance());
        assertEquals(.5,c.pendingDistance());assertEquals(.5,c.save().getDouble("PendingDistance"));
    }
    @Test void inventedPendingJumpAndWrongNumericTypeRequireReview() {
        var t=tag(fixture());t.putDouble("PendingDistance",50);assertTrue(RegisteredAircraftRailController.load(t).invalid());
        t=tag(fixture());t.putString("PendingDistance",".5");assertTrue(RegisteredAircraftRailController.load(t).invalid());
    }
    @Test void malformedMissionCannotDisappearIntoAnIdleController() {
        var t=new CompoundTag();t.put("Mission",new CompoundTag());assertTrue(RegisteredAircraftRailController.load(t).invalid());
    }
    @Test void emptyIdleControllerHasNoInventedProgress() {
        var c=RegisteredAircraftRailController.load(new CompoundTag());assertFalse(c.invalid());assertNull(c.mission());assertNull(c.pendingDistance());
    }
    @Test void reloadRetainsTheAircraftNoseBinding() {
        var c=RegisteredAircraftRailController.load(tag(fixture()));assertFalse(c.invalid());assertEquals("west",c.save().getString("Forward"));
    }
    @Test void missingOrVerticalNoseBindingCannotResumeASavedMission() {
        var t=tag(fixture());t.remove("Forward");assertTrue(RegisteredAircraftRailController.load(t).invalid());
        t=tag(fixture());t.putString("Forward","up");assertTrue(RegisteredAircraftRailController.load(t).invalid());
    }
    @Test void boundedReloadEligibilityDoesNotPersistOrCreditAnUnobservedPendingStep() {
        assertFalse(new RegisteredAircraftRailController().restoringActiveMotion());
        var mission=fixture();mission.setPaused(true);
        var saved=tag(mission);saved.putBoolean("ReloadAlignmentAvailable",true);
        var restored=RegisteredAircraftRailController.load(saved);
        assertTrue(restored.restoringActiveMotion());
        assertTrue(restored.mission().paused());
        assertEquals(0,restored.mission().distance());
        assertNull(restored.pendingDistance());
        assertFalse(restored.save().contains("ReloadAlignmentAvailable"));

        var inFlight=fixture();var pendingSave=tag(inFlight);
        pendingSave.putDouble("PendingDistance",inFlight.proposeStep(true).targetDistance());
        var pending=RegisteredAircraftRailController.load(pendingSave);
        assertFalse(pending.invalid());
        assertTrue(pending.restoringActiveMotion(), "An in-flight save also needs bounded native alignment");
        assertEquals(0,pending.mission().distance(), "Loading the checkpoint must not advance the route");
        assertEquals(.5,pending.pendingDistance());
        assertFalse(pending.save().contains("ReloadAlignmentAvailable"));
        pending.cancel(null);
        assertFalse(pending.restoringActiveMotion());
        assertEquals(0,pending.mission().distance());
        assertNull(pending.pendingDistance());

        var cancelled=tag(mission);cancelled.putBoolean("Cancelled",true);
        assertFalse(RegisteredAircraftRailController.load(cancelled).restoringActiveMotion());
        restored.manualRelease(null);
        assertFalse(restored.restoringActiveMotion());
    }
}
