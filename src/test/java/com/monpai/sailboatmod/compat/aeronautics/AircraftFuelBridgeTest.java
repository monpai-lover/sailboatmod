package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AircraftFuelBridgeTest {
    private static final UUID BODY=UUID.fromString("ad3f09b5-4167-4a62-9a5c-3838603b24e8");
    private static final BlockPos POS=new BlockPos(2,3,4);
    private static AircraftFuelBridge.Engine engine(long ticks,boolean powered){return new AircraftFuelBridge.Engine(POS,ticks,powered,powered?"":"fuel_empty");}
    private static AircraftFuelBridge.Propeller prop(boolean assembled,boolean moving){return new AircraftFuelBridge.Propeller(POS, moving?32:0,assembled,moving,"");}

    @Test void projectionCountsOnlyCurrentCounterAndUnconsumedFuel() {
        assertEquals(16800,AircraftFuelBridge.remainingTicks(800,10,1600));
        assertEquals(1599,AircraftFuelBridge.remainingTicks(1599,0,0));
        assertEquals(0,AircraftFuelBridge.remainingTicks(0,0,0));
        assertEquals(64L*Integer.MAX_VALUE+Integer.MAX_VALUE,AircraftFuelBridge.remainingTicks(Integer.MAX_VALUE,64,Integer.MAX_VALUE));
    }
    @Test void malformedFuelStateDoesNotBecomeAnUnlimitedBudget() {
        for(int[] values:new int[][]{{-1,1,1600},{0,-1,1600},{0,65,1600},{0,1,-1},{0,1,0}})
            assertThrows(IllegalArgumentException.class,()->AircraftFuelBridge.remainingTicks(values[0],values[1],values[2]));
    }
    @Test void exhaustedUnnetworkedEnginesRemainKnownEmptyInsteadOfUnknown() {
        var result=AircraftFuelBridge.finish(BODY,List.of(engine(0,false),engine(0,false)),List.of(prop(true,false)),"fuel_empty");
        assertTrue(result.available());assertFalse(result.powerReady());assertEquals("fuel_empty",result.reason());
        assertEquals(2,result.engines().size());assertTrue(result.engines().stream().allMatch(e->e.availableBurnTicks()==0));
    }
    @Test void coldFuelInventoryCannotGrantPower() {
        var result=AircraftFuelBridge.finish(BODY,List.of(engine(3200,false)),List.of(prop(true,false)),"fuel_waiting_power");
        assertTrue(result.available());assertFalse(result.powerReady());assertEquals("fuel_waiting_power",result.reason());
    }
    @Test void everyNecessaryEngineAndPropellerMustActuallyRun() {
        assertTrue(AircraftFuelBridge.finish(BODY,List.of(engine(3200,true)),List.of(prop(true,true)),"").powerReady());
        assertFalse(AircraftFuelBridge.finish(BODY,List.of(engine(3200,true),engine(0,false)),List.of(prop(true,true)),"").powerReady());
        assertFalse(AircraftFuelBridge.finish(BODY,List.of(engine(3200,true)),List.of(prop(true,false)),"").powerReady());
        assertFalse(AircraftFuelBridge.finish(BODY,List.of(engine(3200,true)),List.of(prop(false,false)),"").powerReady());
        assertFalse(AircraftFuelBridge.finish(BODY,List.of(),List.of(prop(true,true)),"").powerReady());
        assertFalse(AircraftFuelBridge.finish(BODY,List.of(engine(3200,true)),List.of(),"").powerReady());
    }
    @Test void foreignOrUnloadedNetworkCannotBeReplacedByInventoryEvidence() {
        for(String reason:List.of("fuel_foreign_source","fuel_network_unavailable","fuel_unsupported_source","fuel_network_unloaded")) {
            var result=AircraftFuelBridge.finish(BODY,List.of(engine(3200,true)),List.of(prop(true,true)),reason);
            assertFalse(result.available());assertFalse(result.powerReady());assertEquals(reason,result.reason());
        }
    }
    @Test void networkValidationUsesOnlyKnownFiniteCurrentLoadedCapacity() {
        assertEquals("",AircraftFuelBridge.networkProblem(true,1024,512,0,0,0,false));
        assertEquals("fuel_network_unloaded",AircraftFuelBridge.networkProblem(true,1024,512,1,0,0,false));
        assertEquals("fuel_network_unloaded",AircraftFuelBridge.networkProblem(true,1024,512,0,0,1,false));
        assertEquals("fuel_overstressed",AircraftFuelBridge.networkProblem(true,0,512,0,0,0,false));
        assertEquals("fuel_overstressed",AircraftFuelBridge.networkProblem(true,1024,512,0,0,0,true));
        assertEquals("fuel_network_unavailable",AircraftFuelBridge.networkProblem(false,1024,512,0,0,0,false));
        assertEquals("fuel_native_state_unavailable",AircraftFuelBridge.networkProblem(true,Double.NaN,512,0,0,0,false));
    }
    @Test void reportedMotionRequiresActualPartAndBearingStep() {
        assertTrue(AircraftFuelBridge.actualMotion(32,9.6,90,80.4,90,false));
        assertFalse(AircraftFuelBridge.actualMotion(32,9.6,90,90,90,false));
        assertFalse(AircraftFuelBridge.actualMotion(32,9.6,90,80.4,80.4,false));
        assertFalse(AircraftFuelBridge.actualMotion(32,9.6,90,80.4,90,true));
        assertFalse(AircraftFuelBridge.actualMotion(0,0,90,80.4,90,false));
        assertFalse(AircraftFuelBridge.actualMotion(Double.NaN,9.6,90,80.4,90,false));
    }
    @Test void observationOwnsImmutablePositionsAndLists() {
        var mutable=new BlockPos.MutableBlockPos(2,3,4);var engine=new AircraftFuelBridge.Engine(mutable,3200,true,"");mutable.set(9,9,9);
        assertEquals(POS,engine.absolutePosition());var observed=AircraftFuelBridge.finish(BODY,List.of(engine),List.of(prop(true,true)),"");
        assertThrows(UnsupportedOperationException.class,()->observed.engines().clear());
    }
    @Test void malformedReportedReadbacksNeverBecomeKnownReadyOrKnownEmpty() {
        assertFalse(AircraftFuelBridge.finish(null,List.of(engine(3200,true)),List.of(prop(true,true)),"").available());
        var invalid=new AircraftFuelBridge.Propeller(POS,Double.NaN,true,false,"fuel_propeller_stopped");
        assertFalse(AircraftFuelBridge.finish(BODY,List.of(engine(0,false)),List.of(invalid),"fuel_empty").available());
        assertFalse(AircraftFuelBridge.finish(BODY,List.of(engine(-1,false)),List.of(prop(true,false)),"fuel_empty").available());
    }
    @Test void absentOptionalCallerIsSafeWithoutLoadingNativeClasses() {
        var result=AircraftFuelBridge.inspect(null);assertFalse(result.available());assertFalse(result.powerReady());assertTrue(result.engines().isEmpty());
    }
    @Test void syntheticTransmissionMemberRequiresMutualLiveParentIdentityAndCannotBecomeSource()throws Exception {
        Object parent=new Object(),child=new Object(),other=new Object();
        var policy=AircraftFuelReflectionAccess.class.getDeclaredMethod("safeExtraMember",Object.class,Object.class,Object.class,Object.class,boolean.class,boolean.class);
        assertEquals(true,policy.invoke(null,child,parent,parent,child,true,false));
        assertEquals(false,policy.invoke(null,child,parent,other,child,true,false));
        assertEquals(false,policy.invoke(null,child,parent,parent,other,true,false));
        assertEquals(false,policy.invoke(null,child,parent,parent,child,false,false));
        assertEquals(false,policy.invoke(null,child,parent,parent,child,true,true));
        assertEquals(false,policy.invoke(null,null,parent,parent,null,true,false));
        assertEquals(false,policy.invoke(null,child,null,null,child,true,false));
        Object equalChild=new String("same"),foreignEqualChild=new String("same");
        assertEquals(false,policy.invoke(null,equalChild,parent,parent,foreignEqualChild,true,false));
    }
}
