package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission;
import com.monpai.sailboatmod.transport.control.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredGroundReloadRecoveryRegressionTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void fixedWingLegacyGroundPhasesRequireRailReviewWithoutAirborneReplay() throws Exception {
        for(var phase:List.of(AircraftGroundMission.Phase.TAXI_OUT,AircraftGroundMission.Phase.HOLD_LINE,
                AircraftGroundMission.Phase.LINE_UP,AircraftGroundMission.Phase.TAXI_IN,
                AircraftGroundMission.Phase.STAND_STABILIZING,AircraftGroundMission.Phase.COMPLETE)) {
            var original=fixture();var savedMission=mission(phase);
            set(original,"groundMission",savedMission);set(original,"active",true);set(original,"paused",true);
            set(original,"status",ControllerStatus.FAULT);set(original,"flightPhase","GROUND_REVIEW");
            set(original,"reason","ground_recovery_review");
            var restored=fixture();restored.loadAdditional(save(original),null);
            assertFalse((boolean)get(restored,"flightRecovering"),"saved "+phase+" cannot run generic airborne recovery or acquire a new native task");
            assertTrue((boolean)get(restored,"paused"));assertTrue((boolean)get(restored,"recoveryRequired"));
            assertEquals("rail_recovery_review",get(restored,"reason"));
            var actual=(RegisteredAircraftGroundMission)get(restored,"groundMission");
            assertEquals(phase,actual.state().phase());assertEquals(1,actual.state().pointIndex());
            assertTrue(actual.state().recovered());assertEquals(0,actual.state().stableTicks());
            assertEquals(savedMission.sourceAssignment(),actual.sourceAssignment());
            assertEquals(savedMission.targetAssignment(),actual.targetAssignment());
            assertNull(get(restored,"flightTask"));assertManifestPreserved(restored);
        }
    }
    @Test void potentiallySubmittedOrAirbornePhasesKeepTheExistingReplayGuard() throws Exception {
        for(var type:List.of(AircraftClass.AIRSHIP,AircraftClass.ROTORCRAFT))for(var phase:List.of(AircraftGroundMission.Phase.FC_STARTING,AircraftGroundMission.Phase.FC_ACTIVE,
                AircraftGroundMission.Phase.WAIT_FC_RELEASE)) {
            var original=fixture();set(original,"aircraftClass",type);set(original,"groundMission",mission(phase));set(original,"active",true);set(original,"paused",true);
            set(original,"flightTask",UUID.randomUUID());
            var restored=fixture();restored.loadAdditional(save(original),null);
            assertTrue((boolean)get(restored,"flightRecovering"),"cannot infer native release or ground support from "+phase);
            assertNull(get(restored,"flightTask"));assertTrue((boolean)get(restored,"paused"));assertManifestPreserved(restored);
        }
    }
    @Test void fixedWingSubmittedNativePhasesCannotBecomeNewRailProgress() throws Exception {
        for(var phase:List.of(AircraftGroundMission.Phase.FC_STARTING,AircraftGroundMission.Phase.FC_ACTIVE,AircraftGroundMission.Phase.WAIT_FC_RELEASE)){
            var original=fixture();set(original,"groundMission",mission(phase));set(original,"active",true);set(original,"flightTask",UUID.randomUUID());
            var restored=fixture();restored.loadAdditional(save(original),null);
            assertFalse((boolean)get(restored,"flightRecovering"));assertNull(get(restored,"flightTask"));
            assertTrue((boolean)get(restored,"paused"));assertTrue((boolean)get(restored,"recoveryRequired"));assertEquals("rail_recovery_review",get(restored,"reason"));
            assertFalse(((RegisteredAircraftRailController)get(restored,"railAircraft")).hasMission());
            assertEquals(phase,((RegisteredAircraftGroundMission)get(restored,"groundMission")).state().phase());assertManifestPreserved(restored);
        }
    }
    @Test void loadingGroundRecoveryDoesNotReplaySavedSignalOrRuntimeOwnership() throws Exception {
        var original=fixture();set(original,"groundMission",mission(AircraftGroundMission.Phase.TAXI_OUT));
        set(original,"active",true);set(original,"paused",true);set(original,"groundOutputsOwned",true);
        @SuppressWarnings("unchecked") var oldSignals=(Map<Direction,Integer>)get(original,"signals");oldSignals.put(Direction.UP,15);
        var restored=fixture();restored.loadAdditional(save(original),null);
        assertFalse((boolean)get(restored,"groundOutputsOwned"));assertTrue((boolean)get(restored,"groundOutputsNeutral"));
        assertTrue(((Map<?,?>)get(restored,"signals")).isEmpty(),"fresh stop-HOLD must be reconstructed after real support/authority checks, never from saved actuator bits");
        assertFalse(((RegisteredAircraftParkingBrake)get(restored,"parkingBrake")).held());
        assertTrue(((RegisteredAircraftGroundMission)get(restored,"groundMission")).state().recovered());
        assertEquals("rail_recovery_review",get(restored,"reason"));assertManifestPreserved(restored);
    }
    private static RegisteredAircraftGroundMission mission(AircraftGroundMission.Phase phase){
        UUID id=UUID.fromString("00000000-0000-0000-0000-000000000021"),body=UUID.fromString("00000000-0000-0000-0000-000000000022");
        var plan=new AircraftGroundMission.Plan(id,body,"minecraft:overworld",21,"runwayA","standB",id.toString(),
                List.of(new Vec3(0,70,0),new Vec3(0,70,10),new Vec3(0,70,20)),1,List.of(new Vec3(1000,70,20),new Vec3(1000,70,0)),
                0,0,2,3,new AircraftGroundMission.Calibration(true,2,8,30,15,0));
        int point=phase==AircraftGroundMission.Phase.WAIT_FC_RELEASE?0:
                phase==AircraftGroundMission.Phase.FC_STARTING||phase==AircraftGroundMission.Phase.FC_ACTIVE?plan.outbound().size()-1:1;
        var state=new AircraftGroundMission.State(id,body,plan.dimensionId(),21,"runwayA","standB",id.toString(),phase,point,37,100,false);
        return new RegisteredAircraftGroundMission(new BlockPos(1,70,2),new BlockPos(1001,70,2),new GroundAssignment(4,"runwayA","standA","outA","inA"),new GroundAssignment(9,"runwayB","standB","outB","inB"),plan,state);
    }
    private static RegisteredVehicleControllerBlockEntity fixture() throws Exception {
        Field uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);
        var result=(RegisteredVehicleControllerBlockEntity)((Unsafe)uf.get(null)).allocateInstance(RegisteredVehicleControllerBlockEntity.class);
        // AllocateInstance skips the ordinary constructor-owned rail journal initializer.
        set(result,"railAircraft",new RegisteredAircraftRailController());
        set(result,"airSurfaces",new RegisteredAircraftSurfaces());
        set(result,"aircraftFuel",new RegisteredAircraftFuelController());
        set(result,"registration",new ControllerRegistration(UUID.randomUUID(),"owner",LogisticsCarrier.Kind.AIR));
        for(String field:List.of("cargoOffsets","routeCatalog","activeRoute","manifest","groundWheelOffsets"))set(result,field,new ArrayList<>());
        set(result,"manifest",new ArrayList<>(List.of(new ShipmentManifestEntry("listing",ItemStack.EMPTY,"purchase","shipping-ground","buyer","Buyer",1))));
        set(result,"ports",new EnumMap<ControlChannel,Direction>(ControlChannel.class));
        set(result,"signals",new EnumMap<Direction,Integer>(Direction.class));
        for(String field:List.of("vehicleName","ownerName","pendingShipper","reason","flightPhase","flightFingerprint","departureFacility","arrivalFacility"))set(result,field,"");
        set(result,"aircraftClass",AircraftClass.FIXED_WING);set(result,"status",ControllerStatus.IDLE);set(result,"maxSpeed",10d);
        return result;
    }
    private static void assertManifestPreserved(RegisteredVehicleControllerBlockEntity controller){assertEquals("shipping-ground",controller.getPendingShipmentManifest().getFirst().shippingOrderId());}
    private static CompoundTag save(RegisteredVehicleControllerBlockEntity object)throws Exception {
        Method method=RegisteredVehicleControllerBlockEntity.class.getDeclaredMethod("saveAdditional",CompoundTag.class,HolderLookup.Provider.class);method.setAccessible(true);
        CompoundTag tag=new CompoundTag();method.invoke(object,tag,null);return tag;
    }
    private static Object get(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
}
