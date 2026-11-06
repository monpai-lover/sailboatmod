package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.rail.*;
import com.monpai.sailboatmod.transport.control.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual owner RESUME entry point without a world, fuel observation or native mutation. */
class RegisteredAircraftRailResumeTest {
    @Test void oldCancelledCompletedJournalCanResumeDeparturePreflightWithoutBeingCleared() throws Exception {
        var rail=cancelled(mission(true));var c=controller(rail,true,false);String before=rail.save().toString();
        resume(c);
        assertFalse((boolean)get(c,"paused"));assertTrue((boolean)get(c,"active"));assertEquals("",get(c,"reason"));
        assertTrue((boolean)get(c,"departureFlightPending"),"next normal tick must still perform full preparation/fresh fuel admission");
        assertTrue(rail.cancelled(),"eligibility must never clear the old cancellation marker");assertEquals(before,rail.save().toString());
    }
    @Test void cancelledEmptyJournalCanResumeReturnPreflightWithoutInventingAMission() throws Exception {
        var rail=cancelled(null);var c=controller(rail,false,true);String before=rail.save().toString();resume(c);
        assertFalse((boolean)get(c,"paused"));assertTrue((boolean)get(c,"returnFlightPending"));assertNull(rail.mission());
        assertTrue(rail.cancelled());assertEquals(before,rail.save().toString());
    }
    @Test void cancelledUnfinishedMissionCannotUseEitherPendingFlagToResume() throws Exception {
        for(boolean departure:new boolean[]{false,true}) {
            var rail=cancelled(mission(false));var c=controller(rail,departure,!departure);assertBlocked(c,rail);
        }
    }
    @Test void cancelledCompletedOrEmptyJournalWithoutPendingPreflightStillRequiresReview() throws Exception {
        for(RailAircraftMission mission:new RailAircraftMission[]{null,mission(true)}) {
            var rail=cancelled(mission);var c=controller(rail,false,false);assertBlocked(c,rail);
        }
    }
    @Test void invalidStateAlwaysRequiresReviewEvenWithPendingPreflight() throws Exception {
        var malformed=new CompoundTag();malformed.putBoolean("Invalid",true);var rail=RegisteredAircraftRailController.load(malformed);
        var c=controller(rail,true,true);assertBlocked(c,rail);
    }
    @Test void emptyUncancelledJournalStillNeedsActualPendingWork() throws Exception {
        var rail=new RegisteredAircraftRailController();var c=controller(rail,false,false);assertBlocked(c,rail);
    }
    @Test void ordinaryUncancelledMissionResumeKeepsExistingJournalAndPendingState() throws Exception {
        var t=new CompoundTag();t.put("Mission",mission(false).save());t.putString("Forward","west");var rail=RegisteredAircraftRailController.load(t);
        var c=controller(rail,false,false);String before=rail.save().toString();resume(c);
        assertFalse((boolean)get(c,"paused"));assertFalse((boolean)get(c,"departureFlightPending"));assertEquals(before,rail.save().toString());
    }
    private static void assertBlocked(RegisteredVehicleControllerBlockEntity c,RegisteredAircraftRailController rail)throws Exception {
        String before=rail.save().toString();resume(c);assertTrue((boolean)get(c,"paused"));assertEquals("rail_recovery_review",get(c,"reason"));assertEquals(before,rail.save().toString());
    }
    private static RailAircraftMission mission(boolean complete) {
        var path=RailAircraftPath.compile(List.of(new RailAircraftPath.Leg(RailAircraftPath.Phase.CRUISE,List.of(new Vec3(0,80,0),new Vec3(1,80,0)),20,0,true)),-90);
        var a=new GroundAssignment(1,"runway","stand","out","in");var m=RailAircraftMission.start(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",new BlockPos(0,64,0),a,new BlockPos(10,64,0),a,path);
        if(complete){assertTrue(m.commitStep(m.proposeStep(true),true));for(int i=0;i<40;i++)m.observeStand(i,true);assertTrue(m.complete());}
        return m;
    }
    private static RegisteredAircraftRailController cancelled(RailAircraftMission m) {
        var tag=new CompoundTag();tag.putBoolean("Cancelled",true);if(m!=null){tag.put("Mission",m.save());tag.putString("Forward","west");}return RegisteredAircraftRailController.load(tag);
    }
    private static RegisteredVehicleControllerBlockEntity controller(RegisteredAircraftRailController rail,boolean departure,boolean returning)throws Exception {
        Field uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);var c=(RegisteredVehicleControllerBlockEntity)((Unsafe)uf.get(null)).allocateInstance(RegisteredVehicleControllerBlockEntity.class);
        set(c,"railAircraft",rail);set(c,"registration",new ControllerRegistration(UUID.randomUUID(),"owner",LogisticsCarrier.Kind.AIR));set(c,"aircraftClass",AircraftClass.FIXED_WING);
        set(c,"active",true);set(c,"paused",true);set(c,"status",ControllerStatus.PAUSED);set(c,"reason","fuel_insufficient");
        set(c,"departureFlightPending",departure);set(c,"returnFlightPending",returning);return c;
    }
    private static void resume(RegisteredVehicleControllerBlockEntity c)throws Exception {Method m=c.getClass().getDeclaredMethod("resume");m.setAccessible(true);m.invoke(c);}
    private static Object get(Object o,String key)throws Exception {Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String key,Object value)throws Exception {Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);f.set(o,value);}
}
