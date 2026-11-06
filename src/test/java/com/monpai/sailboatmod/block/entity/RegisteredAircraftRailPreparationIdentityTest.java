package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.rail.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredAircraftRailPreparationIdentityTest {
    @Test void completedCancelledJournalRetainsEveryMissionValueButGetsFreshPreparation() throws Exception {
        var mission=mission(true);String before=mission.save().toString();var tag=tag(mission);tag.putBoolean("Cancelled",true);
        var loaded=RegisteredAircraftRailController.load(tag);
        assertFalse(loaded.invalid());assertTrue(loaded.cancelled());assertTrue(loaded.canResume(true));
        assertEquals(mission.id(),loaded.mission().id());assertEquals(before,loaded.mission().save().toString());
        assertNotEquals(mission.id(),preparation(loaded),"a queued next journey must not reuse the completed voyage's UUID");
        assertNull(loaded.pendingDistance());
    }
    @Test void separateLoadsOfACompletedJournalGetSeparateFutureAttemptIdentities() throws Exception {
        var old=mission(true);var first=RegisteredAircraftRailController.load(tag(old));var second=RegisteredAircraftRailController.load(tag(old));
        assertNotEquals(preparation(first),preparation(second));assertNotEquals(old.id(),preparation(first));assertNotEquals(old.id(),preparation(second));
        assertEquals(old.id(),first.mission().id());assertEquals(old.id(),second.mission().id());
    }
    @Test void unfinishedJournalRestoresItsIdentityAndCreditedProgress() throws Exception {
        var old=mission(false);var first=old.proposeStep(true);assertTrue(old.commitStep(first,true));String before=old.save().toString();
        var restored=RegisteredAircraftRailController.load(tag(old));assertFalse(restored.invalid());
        assertEquals(old.id(),preparation(restored));assertEquals(before,restored.mission().save().toString());assertEquals(.5,restored.mission().distance());assertFalse(restored.mission().completed());assertNull(restored.pendingDistance());
    }
    @Test void outstandingUnconfirmedStepKeepsTheSameMissionAndDoesNotCreditExtraDistance() throws Exception {
        var old=mission(false);assertTrue(old.commitStep(old.proposeStep(true),true));var tag=tag(old);double pending=old.proposeStep(true).targetDistance();tag.putDouble("PendingDistance",pending);
        var restored=RegisteredAircraftRailController.load(tag);assertFalse(restored.invalid());assertEquals(old.id(),preparation(restored));assertEquals(old.id(),restored.mission().id());
        assertEquals(.5,restored.mission().distance());assertEquals(1,restored.pendingDistance());assertEquals(pending,restored.save().getDouble("PendingDistance"));assertFalse(restored.mission().completed());
    }
    @Test void cancelledUnfinishedJournalIsStillTheSameCancelledMissionAndCannotResume() throws Exception {
        var old=mission(false);var tag=tag(old);tag.putBoolean("Cancelled",true);var restored=RegisteredAircraftRailController.load(tag);
        assertEquals(old.id(),preparation(restored));assertTrue(restored.cancelled());assertFalse(restored.canResume(true));assertFalse(restored.canResume(false));assertEquals(old.save().toString(),restored.mission().save().toString());
    }
    @Test void malformedCompletedRecordRemainsInvalidAndCannotBecomeANewFlight() {
        var t=tag(mission(true));t.getCompound("Mission").putString("PathHash","broken");var restored=RegisteredAircraftRailController.load(t);
        assertTrue(restored.invalid());assertFalse(restored.canResume(true));assertFalse(restored.canResume(false));assertTrue(restored.save().contains("Invalid"));
    }
    private static UUID preparation(RegisteredAircraftRailController controller)throws Exception {
        Field f=RegisteredAircraftRailController.class.getDeclaredField("preparation");f.setAccessible(true);return (UUID)f.get(controller);
    }
    private static CompoundTag tag(RailAircraftMission mission) {var tag=new CompoundTag();tag.put("Mission",mission.save());tag.putString("Forward","west");return tag;}
    private static RailAircraftMission mission(boolean complete) {
        var path=RailAircraftPath.compile(List.of(new RailAircraftPath.Leg(RailAircraftPath.Phase.CRUISE,List.of(new Vec3(0,80,0),new Vec3(100,80,0)),10,0,true)),-90);
        var assignment=new GroundAssignment(1,"runway","stand","out","in");var m=RailAircraftMission.start(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",new BlockPos(0,64,0),assignment,new BlockPos(100,64,0),assignment,path);
        if(complete){while(m.distance()<path.length())assertTrue(m.commitStep(m.proposeStep(true),true));for(int i=0;i<40;i++)m.observeStand(i,true);assertTrue(m.complete());}
        return m;
    }
}
