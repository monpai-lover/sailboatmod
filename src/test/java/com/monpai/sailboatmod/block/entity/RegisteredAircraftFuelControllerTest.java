package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.compat.aeronautics.AircraftFuelBridge;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredAircraftFuelControllerTest {
    final UUID body=UUID.fromString("0bf1d134-7a6b-45d9-825e-4a569c9addce");
    final BlockEntity controller=new BlockEntity(null,new BlockPos(40,129,90),null){@Override public boolean isValidBlockState(net.minecraft.world.level.block.state.BlockState state){return true;}};
    final RegisteredAircraftFuelController.Context context=new RegisteredAircraftFuelController.Context(controller,body);
    final Fake nativeApi=new Fake();
    RailAircraftPath path(double length){return RailAircraftPath.compile(List.of(new RailAircraftPath.Leg(RailAircraftPath.Phase.CRUISE,List.of(new Vec3(0,64,0),new Vec3(length,64,0)),10,0,true)),-90);}
    RegisteredAircraftFuelController helper(){return new RegisteredAircraftFuelController(nativeApi);}
    AircraftFuelBridge.Engine engine(int x,long ticks,boolean powered){return new AircraftFuelBridge.Engine(new BlockPos(x,129,90),ticks,powered,powered?"":"fuel_waiting_power");}
    AircraftFuelBridge.Observation observed(UUID id,long ticks,boolean powered,boolean moving){return new AircraftFuelBridge.Observation(true,moving?"":"fuel_propeller_stopped",id,List.of(engine(41,ticks,powered)),List.of(new AircraftFuelBridge.Propeller(new BlockPos(33,130,87),moving?32:0,true,moving,moving?"":"fuel_propeller_stopped")),powered&&moving);}

    @Test void exactPlanAndReturnBudgetExposeDwellOnceAndRealAbsoluteRows() {
        var h=helper();assertTrue(h.check(context,path(100),0,path(50)).success());var tag=h.cachedScreenData();
        assertTrue(tag.getBoolean("Evaluated"));assertTrue(tag.getBoolean("Ready"));assertEquals(240,tag.getLong("PlannedTicks"));assertEquals(140,tag.getLong("ReturnTicks"));
        assertEquals(1200,tag.getLong("ReserveTicks"));assertEquals(1580,tag.getLong("RequiredTicks"));assertEquals(10000,tag.getLong("AvailableTicks"));
        var engine=tag.getList("Engines",Tag.TAG_COMPOUND).getCompound(0);assertEquals(new BlockPos(41,129,90),BlockPos.of(engine.getLong("Position")));assertEquals(1580,engine.getLong("RequiredTicks"));assertTrue(engine.getBoolean("Ready"));
        var prop=tag.getList("Propellers",Tag.TAG_COMPOUND).getCompound(0);assertEquals(32,prop.getDouble("Rpm"));assertTrue(prop.getBoolean("Moving"));assertTrue(prop.getBoolean("Assembled"));
    }
    @Test void checkAlwaysReadsFreshEvenAtSameTickWhilePollHasTwentyTickCadence() {
        var h=helper();var p=path(100);h.check(context,p,0,null);h.check(context,p,0,null);assertEquals(2,nativeApi.reads);
        nativeApi.tick=119;h.poll(context);assertEquals(2,nativeApi.reads);nativeApi.tick=120;nativeApi.observation=observed(body,0,false,false);
        assertFalse(h.poll(context).success());assertEquals(3,nativeApi.reads);assertEquals("fuel_insufficient",h.cachedScreenData().getString("Reason"));
        nativeApi.observation=observed(body,10000,true,true);nativeApi.tick=121;assertTrue(h.check(context,p,50,null).success());assertEquals(4,nativeApi.reads);assertEquals(140,h.cachedScreenData().getLong("PlannedTicks"));
    }
    @Test void changingObservedBodyRejectsEvenAnOtherwisePoweredRichEngine() {
        var h=helper();nativeApi.observation=observed(UUID.randomUUID(),10000,true,true);assertFalse(h.check(context,path(100),0,null).success());
        assertEquals("fuel_body_changed",h.cachedScreenData().getString("Reason"));assertFalse(h.cachedScreenData().getBoolean("Ready"));
    }
    @Test void cachedGetterIsPureAndDefensiveAndNullContextRetainsUnavailablePositionRows() {
        var h=helper();h.check(context,path(100),0,null);int reads=nativeApi.reads;var copy=h.cachedScreenData();copy.putBoolean("Ready",false);copy.getList("Engines",Tag.TAG_COMPOUND).clear();
        assertTrue(h.cachedScreenData().getBoolean("Ready"));assertEquals(1,h.cachedScreenData().getList("Engines",Tag.TAG_COMPOUND).size());assertEquals(reads,nativeApi.reads);
        assertFalse(h.poll(null).success());var unavailable=h.cachedScreenData();assertFalse(unavailable.getBoolean("Ready"));assertEquals(-1,unavailable.getLong("AvailableTicks"));
        assertEquals(new BlockPos(41,129,90),BlockPos.of(unavailable.getList("Engines",Tag.TAG_COMPOUND).getCompound(0).getLong("Position")));assertEquals(reads,nativeApi.reads);
    }
    @Test void exhaustedChecksFreshActualHealthButDoesNotStopForOnlyReserveShortage() {
        var h=helper();nativeApi.observation=observed(body,100,true,true);assertFalse(h.check(context,path(100),0,null).success());assertEquals("fuel_insufficient",h.cachedScreenData().getString("Reason"));
        assertTrue(h.exhausted(context).success());assertEquals(2,nativeApi.reads);
        nativeApi.observation=observed(body,0,false,false);var empty=h.exhausted(context);assertFalse(empty.success());assertEquals("fuel_empty",empty.reason());assertEquals(3,nativeApi.reads);
    }
    @Test void globalPropellerReadinessIsRequiredAlongsideEachEngineBudget() {
        var h=helper();nativeApi.observation=observed(body,10000,true,false);var result=h.check(context,path(100),0,null);
        assertFalse(result.success());assertEquals("fuel_propeller_stopped",result.reason());assertFalse(h.exhausted(context).success());
        nativeApi.observation=new AircraftFuelBridge.Observation(true,"",body,List.of(engine(41,10000,true)),List.of(),true);assertFalse(h.exhausted(context).success());
    }
    @Test void unavailableNativeReadAndUnknownFuelFailClosedWithTruthyReasons() {
        var h=helper();nativeApi.observation=new AircraftFuelBridge.Observation(false,"fuel_network_unloaded",body,List.of(),List.of(),false);
        assertEquals("fuel_network_unloaded",h.check(context,path(100),0,null).reason());assertEquals(-1,h.cachedScreenData().getLong("AvailableTicks"));
        nativeApi.observation=observed(body,-1,true,true);assertEquals("fuel_unknown",h.check(context,path(100),0,null).reason());
    }
    @Test void badDurationCannotReuseAnEarlierSuccessfulPlanAndStillRefreshesNative() {
        var h=helper();var p=path(100);h.check(context,p,0,null);assertFalse(h.check(context,p,101,null).success());assertEquals(2,nativeApi.reads);
        assertEquals("fuel_duration_invalid",h.cachedScreenData().getString("Reason"));assertEquals(-1,h.cachedScreenData().getLong("RequiredTicks"));
        nativeApi.tick=120;assertFalse(h.poll(context).success());assertEquals("fuel_duration_invalid",h.cachedScreenData().getString("Reason"));
    }
    @Test void explicitPlanFailureUsesNoNativeIoAndSurvivesPollUntilFreshCheck() {
        var h=helper();h.check(context,path(100),0,null);int reads=nativeApi.reads;assertFalse(h.unavailable("rail_return_unavailable").success());assertEquals(reads,nativeApi.reads);
        var tag=h.cachedScreenData();assertTrue(tag.getBoolean("Evaluated"));assertFalse(tag.getBoolean("Ready"));assertEquals(-1,tag.getLong("RequiredTicks"));assertEquals(-1,tag.getLong("AvailableTicks"));
        nativeApi.tick=120;h.poll(context);assertEquals("rail_return_unavailable",h.cachedScreenData().getString("Reason"));assertTrue(h.check(context,path(100),0,null).success());
    }
    @Test void invalidateClearsAllTransientEvidenceAndUnplannedPollDoesNotGrantAdmission() {
        var h=helper();h.check(context,path(100),0,null);int reads=nativeApi.reads;h.invalidate();assertEquals(reads,nativeApi.reads);
        var empty=h.cachedScreenData();assertFalse(empty.getBoolean("Evaluated"));assertFalse(empty.getBoolean("Ready"));assertEquals(-1,empty.getLong("RequiredTicks"));assertTrue(empty.getList("Engines",Tag.TAG_COMPOUND).isEmpty());
        assertFalse(h.poll(context).success());assertEquals(reads+1,nativeApi.reads);assertFalse(h.cachedScreenData().getBoolean("Evaluated"));assertEquals(1,h.cachedScreenData().getList("Engines",Tag.TAG_COMPOUND).size());
    }
    @Test void everyNecessaryEngineIsIndividuallyBudgetedWithoutPooling() {
        var h=helper();nativeApi.observation=new AircraftFuelBridge.Observation(true,"",body,List.of(engine(41,10000,true),engine(42,1000,true)),observed(body,10000,true,true).propellers(),true);
        assertFalse(h.check(context,path(100),0,null).success());assertEquals(1000,h.cachedScreenData().getLong("AvailableTicks"));
        var rows=h.cachedScreenData().getList("Engines",Tag.TAG_COMPOUND);assertTrue(rows.getCompound(0).getBoolean("Ready"));assertFalse(rows.getCompound(1).getBoolean("Ready"));
        nativeApi.observation=new AircraftFuelBridge.Observation(true,"",body,List.of(engine(41,10000,false)),observed(body,10000,true,true).propellers(),true);assertFalse(h.check(context,path(100),0,null).success());
    }
    @Test void exceptionsOrNonfinitePropellerReadbackNeverEscapeAsReadiness() {
        var h=helper();nativeApi.throwing=true;assertFalse(h.check(context,path(100),0,null).success());nativeApi.throwing=false;
        nativeApi.observation=new AircraftFuelBridge.Observation(true,"",body,List.of(engine(41,10000,true)),List.of(new AircraftFuelBridge.Propeller(new BlockPos(33,130,87),Double.NaN,true,true,"")),true);
        assertFalse(h.exhausted(context).success());assertFalse(h.cachedScreenData().getList("Propellers",Tag.TAG_COMPOUND).getCompound(0).contains("Rpm"));
    }
    @Test void changedContextCannotAdmitTheOldBodyPlanAndClockRollbackRefreshes() {
        var h=helper();h.check(context,path(100),0,null);nativeApi.tick=50;h.poll(context);assertEquals(2,nativeApi.reads);
        UUID other=UUID.randomUUID();nativeApi.observation=observed(other,10000,true,true);assertFalse(h.poll(new RegisteredAircraftFuelController.Context(controller,other)).success());
        assertFalse(h.cachedScreenData().getBoolean("Ready"));assertTrue(h.check(new RegisteredAircraftFuelController.Context(controller,other),path(100),0,null).success());
    }
    @Test void durationCacheIsBoundedAcrossManyFreshRemainingDistances() throws Exception {
        var h=helper();var p=path(1000);for(int i=0;i<20;i++)h.check(context,p,i,null);
        var field=RegisteredAircraftFuelController.class.getDeclaredField("durationTicks");field.setAccessible(true);assertTrue(((Map<?,?>)field.get(h)).size()<=8);
    }
    @Test void nullExplicitCheckCannotKeepOldPlanNumbersAsANewEvaluatedPlan() {
        var h=helper();h.check(context,path(100),0,null);int reads=nativeApi.reads;assertFalse(h.check(null,path(200),0,null).success());
        assertEquals(-1,h.cachedScreenData().getLong("RequiredTicks"));assertEquals(-1,h.cachedScreenData().getLong("PlannedTicks"));assertEquals(reads,nativeApi.reads);
    }
    @Test void healthForANewExpectedBodyDoesNotReauthorizeTheOldPlanInUi() {
        var h=helper();h.check(context,path(100),0,null);UUID other=UUID.randomUUID();nativeApi.observation=observed(other,10000,true,true);
        assertTrue(h.exhausted(new RegisteredAircraftFuelController.Context(controller,other)).success(),"Health only can be good while the route plan still needs rebuilding");
        assertFalse(h.cachedScreenData().getBoolean("Ready"));assertEquals("fuel_body_changed",h.cachedScreenData().getString("Reason"));assertEquals(-1,h.cachedScreenData().getLong("RequiredTicks"));
    }
    final class Fake implements RegisteredAircraftFuelController.NativeAccess {
        long tick=100;int reads;boolean throwing;AircraftFuelBridge.Observation observation=observed(body,10000,true,true);
        public long gameTick(RegisteredAircraftFuelController.Context c){return tick;}
        public AircraftFuelBridge.Observation inspect(RegisteredAircraftFuelController.Context c){reads++;if(throwing)throw new IllegalStateException("native unavailable");return observation;}
    }
}
