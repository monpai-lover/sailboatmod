package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission;
import com.monpai.sailboatmod.transport.air.rail.*;
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

class RegisteredAircraftGroundPersistenceTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void nativeAirshipNbtRetainsBothEndpointsAndKnownPhaseButRequiresReview() throws Exception {
        var original=fixture();set(original,"aircraftClass",AircraftClass.AIRSHIP);set(original,"groundMission",mission());set(original,"active",true);
        var tag=save(original);var restored=fixture();restored.loadAdditional(tag,null);
        var binding=(RegisteredAircraftGroundMission)get(restored,"groundMission");
        assertNotNull(binding);assertEquals(mission().sourceAirport(),binding.sourceAirport());
        assertEquals(mission().targetAirport(),binding.targetAirport());
        assertEquals(4,binding.sourceAssignment().layoutRevision());assertEquals(9,binding.targetAssignment().layoutRevision());
        assertEquals(AircraftGroundMission.Phase.STAND_STABILIZING,binding.state().phase());assertEquals(1,binding.state().pointIndex());
        assertTrue(binding.state().recovered());assertEquals(0,binding.state().stableTicks());
        assertEquals(true,get(restored,"paused"));assertEquals(true,get(restored,"recoveryRequired"));
        assertEquals(false,get(restored,"groundOutputsOwned"));assertEquals("ground_recovery_review",get(restored,"reason"));
        assertManifestPreserved(restored);
    }
    @Test void malformedGroundMissionKeepsCargoInReviewInsteadOfRevertingToLegacy() throws Exception {
        var original=fixture();set(original,"groundMission",mission());set(original,"active",true);
        CompoundTag tag=save(original);tag.getCompound("AircraftGroundMission").remove("SourceAssignment");
        var restored=fixture();restored.loadAdditional(tag,null);
        assertNull(get(restored,"groundMission"));assertEquals(true,get(restored,"invalidGroundMission"));
        assertEquals(true,get(restored,"paused"));assertEquals("rail_recovery_review",get(restored,"reason"));
        assertFalse((boolean)get(restored,"flightRecovering"));assertNull(get(restored,"flightTask"));assertManifestPreserved(restored);
    }
    @Test void aValidGuidedJournalSurvivesBesideLegacyGroundDataWithoutReplayingActuators() throws Exception {
        var original=fixture();var legacy=mission();set(original,"groundMission",legacy);set(original,"active",true);
        var path=RailAircraftPath.compile(List.of(new RailAircraftPath.Leg(RailAircraftPath.Phase.CRUISE,List.of(new Vec3(0,90,0),new Vec3(1000,90,0)),10,0,true)),-90);
        var rail=RailAircraftMission.start(UUID.randomUUID(),legacy.plan().physicalId(),"minecraft:overworld",legacy.sourceAirport(),legacy.sourceAssignment(),legacy.targetAirport(),legacy.targetAssignment(),path);
        var journal=new CompoundTag();journal.put("Mission",rail.save());journal.putString("Forward","west");journal.putDouble("PendingDistance",rail.proposeStep(true).targetDistance());
        var helper=RegisteredAircraftRailController.load(journal);assertFalse(helper.invalid());set(original,"railAircraft",helper);
        set(original,"flightTask",UUID.randomUUID());set(original,"groundOutputsOwned",true);
        var restored=fixture();restored.loadAdditional(save(original),null);var actual=(RegisteredAircraftRailController)get(restored,"railAircraft");
        assertFalse(actual.invalid());assertEquals(rail.id(),actual.mission().id());assertEquals(0,actual.mission().distance());assertEquals(.5,actual.pendingDistance());
        assertFalse((boolean)get(restored,"flightRecovering"));assertFalse((boolean)get(restored,"recoveryRequired"));assertNull(get(restored,"flightTask"));
        assertFalse((boolean)get(restored,"groundOutputsOwned"));assertTrue((boolean)get(restored,"groundOutputsNeutral"));
        assertEquals(legacy.sourceAssignment(),((RegisteredAircraftGroundMission)get(restored,"groundMission")).sourceAssignment());assertManifestPreserved(restored);
    }
    @Test void measuredCalibrationAndWheelBindingsSurviveWithoutOwningAnyActuator() throws Exception {
        var original=fixture();var body=UUID.randomUUID();
        set(original,"groundConfiguration",RegisteredAircraftGroundMission.Configuration.parse("2 8 30 15 0 2").confirmed());
        set(original,"groundCalibrationBody",body);set(original,"groundWheelOffsets",new ArrayList<>(List.of(new BlockPos(1,0,0),new BlockPos(-1,0,0),new BlockPos(0,0,3))));
        var restored=fixture();restored.loadAdditional(save(original),null);
        assertEquals(body,get(restored,"groundCalibrationBody"));
        assertTrue(((RegisteredAircraftGroundMission.Configuration)get(restored,"groundConfiguration")).calibration().liveVerified());
        assertEquals(3,((List<?>)get(restored,"groundWheelOffsets")).size());assertEquals(false,get(restored,"groundOutputsOwned"));
    }
    @Test void parkingReloadKeepsOnlyOwnerReleaseIntentNeverActuatorOwnership()throws Exception{
        var original=fixture();var parking=new RegisteredAircraftParkingBrake();parking.adopt(UUID.randomUUID());set(original,"parkingBrake",parking);
        CompoundTag heldTag=save(original);var restored=fixture();restored.loadAdditional(heldTag,null);
        var fresh=(RegisteredAircraftParkingBrake)get(restored,"parkingBrake");assertFalse(fresh.held());assertFalse(fresh.released());
        parking.restoreReleased(true);var released=fixture();released.loadAdditional(save(original),null);
        assertTrue(((RegisteredAircraftParkingBrake)get(released,"parkingBrake")).released());assertFalse(((RegisteredAircraftParkingBrake)get(released,"parkingBrake")).held());
    }
    private static RegisteredAircraftGroundMission mission(){
        UUID id=UUID.fromString("00000000-0000-0000-0000-000000000021"),body=UUID.fromString("00000000-0000-0000-0000-000000000022");
        var plan=new AircraftGroundMission.Plan(id,body,"minecraft:overworld",21,"runwayA","standB",id.toString(),
                List.of(new Vec3(0,70,0),new Vec3(0,70,10),new Vec3(0,70,20)),1,List.of(new Vec3(1000,70,20),new Vec3(1000,70,0)),
                0,0,2,3,new AircraftGroundMission.Calibration(true,2,8,30,15,0));
        var state=new AircraftGroundMission.State(id,body,plan.dimensionId(),21,"runwayA","standB",id.toString(),AircraftGroundMission.Phase.STAND_STABILIZING,1,37,100,false);
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
