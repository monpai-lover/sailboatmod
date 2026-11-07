package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.rail.*;
import com.monpai.sailboatmod.transport.control.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real controller's NBT path without constructing deferred mod block registries. */
class AirControllerPersistenceTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void explicitCommunityRudderProfileSurvivesPausedControllerReloadWithoutExecutionState() throws Exception {
        var original=guidedFixture();
        var profile=new com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceProfile(UUID.randomUUID(),Direction.WEST,
                List.of(new com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceBinding(
                        com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceBinding.Role.RUDDER,new BlockPos(6,3,0),0,1,20,45)));
        var configured=new CompoundTag();configured.put("Profile",profile.save());
        set(original,"airSurfaces",RegisteredAircraftSurfaces.load(configured));
        set(original,"paused",true);var restored=roundTrip(original);
        var surfaces=(RegisteredAircraftSurfaces)get(restored,"airSurfaces");
        assertEquals(profile,surfaces.profile().orElseThrow());assertFalse(surfaces.save().toString().contains("Lease"));
        assertTrue((boolean)get(restored,"paused"));assertCargoAndRoutePreserved(restored);
    }

    @Test void malformedTopLevelSurfaceProfileIsRetainedAndCannotBecomeAnUnboundAircraft() throws Exception {
        var original=guidedFixture();var registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var saved=new CompoundTag();original.saveAdditional(saved,registries);saved.putString("AirSurfaces","broken-profile");
        var restored=fixture();restored.loadAdditional(saved,registries);
        var surfaces=(RegisteredAircraftSurfaces)get(restored,"airSurfaces");assertTrue(surfaces.invalid());
        assertEquals("broken-profile",surfaces.save().getString("Raw"));assertCargoAndRoutePreserved(restored);
    }

    @Test void savedFuelReadinessCannotAuthorizeAFlightAfterReload() throws Exception {
        var original=guidedFixture();var registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var saved=new CompoundTag();original.saveAdditional(saved,registries);assertFalse(saved.contains("AirFuel"));
        var fakeReady=new CompoundTag();fakeReady.putBoolean("Ready",true);fakeReady.putBoolean("Evaluated",true);fakeReady.putLong("AvailableTicks",Long.MAX_VALUE);saved.put("AirFuel",fakeReady);
        var restored=fixture();restored.loadAdditional(saved,registries);
        var fuel=(RegisteredAircraftFuelController)get(restored,"aircraftFuel");
        assertFalse(fuel.cachedScreenData().getBoolean("Ready"));assertFalse(fuel.cachedScreenData().getBoolean("Evaluated"));
        assertCargoAndRoutePreserved(restored);
    }

    @Test void assistSettingsSurviveWithoutSavingAnyExecutionLease() throws Exception {
        var original = fixture();
        set(original, "takeoffAssistConfig", new com.monpai.sailboatmod.transport.air.TakeoffAssistConfig(true, 14));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        CompoundTag saved = new CompoundTag();
        original.saveAdditional(saved, registries);
        assertTrue(saved.getBoolean("TakeoffAssistEnabled"));
        assertEquals(14, saved.getDouble("TakeoffAssistAcceleration"));
        assertFalse(saved.contains("TakeoffAssistTask"));
        assertFalse(saved.contains("TakeoffAssistArmed"));
        assertEquals(new com.monpai.sailboatmod.transport.air.TakeoffAssistConfig(true, 14),
                get(roundTrip(original), "takeoffAssistConfig"));
    }

    @Test void oldSaveResetsStaleAssistSettingsToDisabledDefault() throws Exception {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        CompoundTag old = new CompoundTag();
        fixture().saveAdditional(old, registries);
        old.remove("TakeoffAssistEnabled"); old.remove("TakeoffAssistAcceleration");
        var restored = fixture();
        set(restored, "takeoffAssistConfig", new com.monpai.sailboatmod.transport.air.TakeoffAssistConfig(true, 20));
        restored.loadAdditional(old, registries);
        assertEquals(com.monpai.sailboatmod.transport.air.TakeoffAssistConfig.DEFAULT,
                get(restored, "takeoffAssistConfig"));
    }

    @Test void pausedReloadRetainsCargoButNeverAdoptsTheLostFlightTask() throws Exception {
        var original = fixture();
        set(original, "paused", true);
        set(original, "flightTask", UUID.randomUUID());
        var restored = roundTrip(original);
        assertTrue((boolean) get(restored, "active"));
        assertTrue((boolean) get(restored, "paused"));
        assertTrue((boolean) get(restored, "flightRecovering"));
        assertNull(get(restored, "flightTask"));
        assertCargoAndRoutePreserved(restored);
    }

    @Test void waitingDepartureRestoresItsIntentWithoutPretendingThereWasAnFcTask() throws Exception {
        var original = fixture();
        UUID attempt = UUID.randomUUID();
        set(original, "departureFlightPending", true);
        set(original, "airportPreflightAttempt", attempt);
        var restored = roundTrip(original);
        assertTrue((boolean) get(restored, "departureFlightPending"));
        assertFalse((boolean) get(restored, "flightRecovering"));
        assertEquals(attempt, get(restored, "airportPreflightAttempt"));
        assertCargoAndRoutePreserved(restored);
    }

    @Test void queuedReturnRestoresItsActualDepartureAirport() throws Exception {
        var original = fixture();
        BlockPos returnAirport = new BlockPos(300, 64, 100);
        set(original, "returnFlightPending", true);
        set(original, "returnTrip", true);
        set(original, "returnOrigin", returnAirport);
        var restored = roundTrip(original);
        assertTrue((boolean) get(restored, "returnFlightPending"));
        assertTrue((boolean) get(restored, "returnTrip"));
        assertFalse((boolean) get(restored, "flightRecovering"));
        assertEquals(returnAirport, get(restored, "returnOrigin"));
    }

    @Test void submittedProfileAndExistingRecoveryBothRecheckTheRealCardAfterReload() throws Exception {
        var submitted = fixture();
        set(submitted, "flightPending", true);
        assertTrue((boolean) get(roundTrip(submitted), "flightRecovering"));
        var recovering = fixture();
        set(recovering, "paused", true);
        set(recovering, "recoveryRequired", true);
        var restored = roundTrip(recovering);
        assertTrue((boolean) get(restored, "flightRecovering"));
        assertTrue((boolean) get(restored, "recoveryRequired"));
        assertCargoAndRoutePreserved(restored);
    }

    @Test void fixedWingDepartureFacilitySurvivesTheRealNbtRoundTrip() throws Exception {
        var original = fixture();
        set(original, "aircraftClass", AircraftClass.FIXED_WING);
        set(original, "departureFacility", "runway-source");
        assertEquals("runway-source", get(roundTrip(original), "departureFacility"));
    }

    @Test void airshipReloadStillRequiresItsNativeReplayGuard() throws Exception {
        var original=fixture();set(original,"aircraftClass",AircraftClass.AIRSHIP);set(original,"flightTask",UUID.randomUUID());
        var restored=roundTrip(original);
        assertTrue((boolean)get(restored,"flightRecovering"));assertNull(get(restored,"flightTask"));assertCargoAndRoutePreserved(restored);
    }

    @Test void guidedFixedWingReloadRetainsUncreditedPendingMotionAndCargoWithoutReplayingFc() throws Exception {
        var original=guidedFixture();var before=(RegisteredAircraftRailController)get(original,"railAircraft");
        set(original,"flightTask",UUID.randomUUID());set(original,"flightPending",true);
        var restored=roundTrip(original);var rail=(RegisteredAircraftRailController)get(restored,"railAircraft");
        assertFalse(rail.invalid());assertEquals(before.mission().id(),rail.mission().id());assertEquals(before.mission().bodyId(),rail.mission().bodyId());
        assertEquals(before.mission().path().fingerprint(),rail.mission().path().fingerprint());
        assertEquals(0,rail.mission().distance());assertEquals(before.pendingDistance(),rail.pendingDistance());
        assertTrue((boolean)get(restored,"active"));assertFalse((boolean)get(restored,"flightRecovering"));assertFalse((boolean)get(restored,"recoveryRequired"));
        assertNull(get(restored,"flightTask"));assertNull(get(restored,"flightTerminalOffset"));assertCargoAndRoutePreserved(restored);
    }

    @Test void anActiveLegacyFixedWingSaveWithoutRailProgressRequiresReviewAndKeepsCargo() throws Exception {
        var original=fixture();set(original,"aircraftClass",AircraftClass.FIXED_WING);set(original,"flightTask",UUID.randomUUID());
        var restored=roundTrip(original);
        assertFalse((boolean)get(restored,"flightRecovering"));assertNull(get(restored,"flightTask"));
        assertTrue((boolean)get(restored,"paused"));assertTrue((boolean)get(restored,"recoveryRequired"));
        assertEquals("rail_recovery_review",get(restored,"reason"));assertCargoAndRoutePreserved(restored);
    }

    @Test void aGuidedFaultWithoutFcPreservesMissionAndCargoWithoutImplyingManualTakeover() throws Exception {
        var original=guidedFixture();var rail=get(original,"railAircraft");
        Method fault=RegisteredVehicleControllerBlockEntity.class.getDeclaredMethod("fault",String.class,boolean.class);fault.setAccessible(true);
        fault.invoke(original,"cargo_unavailable",false);
        assertTrue((boolean)get(original,"active"));assertTrue((boolean)get(original,"paused"));assertFalse((boolean)get(original,"manualTakeover"));
        assertEquals(ControllerStatus.FAULT,get(original,"status"));assertEquals("cargo_unavailable",get(original,"reason"));
        assertSame(rail,get(original,"railAircraft"));assertNull(get(original,"flightTerminalOffset"));assertCargoAndRoutePreserved(original);
    }

    @Test void oldNbtWithoutDepartureFacilityClearsAnyStaleSourceBinding() throws Exception {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var original = fixture();
        set(original, "aircraftClass", AircraftClass.FIXED_WING);
        CompoundTag oldSave = new CompoundTag();
        original.saveAdditional(oldSave, registries);
        oldSave.remove("DepartureFacility");
        var restored = fixture();
        set(restored, "departureFacility", "stale-source-must-not-survive");
        restored.loadAdditional(oldSave, registries);
        assertEquals("", get(restored, "departureFacility"));
    }

    private static void assertCargoAndRoutePreserved(RegisteredVehicleControllerBlockEntity restored) throws Exception {
        assertEquals("shipping-1", restored.getPendingShipmentManifest().getFirst().shippingOrderId());
        assertEquals(2, ((List<?>) get(restored, "activeRoute")).size());
        assertEquals(new BlockPos(10, 64, 10), get(restored, "sourceTerminal"));
        assertEquals("original-real-card", get(restored, "flightFingerprint"));
    }

    private static RegisteredVehicleControllerBlockEntity roundTrip(RegisteredVehicleControllerBlockEntity original) throws Exception {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        CompoundTag saved = new CompoundTag();
        original.saveAdditional(saved, registries);
        var restored = fixture();
        restored.loadAdditional(saved, registries);
        return restored;
    }

    private static RegisteredVehicleControllerBlockEntity fixture() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var controller = (RegisteredVehicleControllerBlockEntity) ((Unsafe) unsafeField.get(null))
                .allocateInstance(RegisteredVehicleControllerBlockEntity.class);
        // Unsafe bypasses ordinary field initialization; these are real constructor-owned objects.
        set(controller,"railAircraft",new RegisteredAircraftRailController());
        set(controller,"airSurfaces",new RegisteredAircraftSurfaces());
        set(controller,"aircraftFuel",new RegisteredAircraftFuelController());
        Field state=BlockEntity.class.getDeclaredField("blockState");state.setAccessible(true);state.set(controller,Blocks.FURNACE.defaultBlockState());
        set(controller, "registration", new ControllerRegistration(UUID.randomUUID(), "owner", LogisticsCarrier.Kind.AIR));
        for (String field : List.of("vehicleName", "ownerName", "pendingShipper", "reason")) set(controller, field, "");
        set(controller, "status", ControllerStatus.PAUSED);
        set(controller, "aircraftClass", AircraftClass.ROTORCRAFT);
        set(controller, "flightPhase", "PREFLIGHT");
        set(controller, "flightFingerprint", "original-real-card");
        set(controller, "arrivalFacility", "pad");
        set(controller, "departureFacility", "");
        set(controller, "ports", new EnumMap<ControlChannel, Direction>(ControlChannel.class));
        set(controller, "cargoOffsets", new ArrayList<BlockPos>());
        set(controller, "groundWheelOffsets", new ArrayList<BlockPos>());
        List<Vec3> points = List.of(new Vec3(10, 64, 10), new Vec3(300, 64, 100));
        set(controller, "routeCatalog", new ArrayList<>(List.of(new RouteDefinition("cargo", points))));
        set(controller, "activeRoute", new ArrayList<>(points));
        set(controller, "manifest", new ArrayList<>(List.of(new ShipmentManifestEntry("listing", ItemStack.EMPTY,
                "purchase", "shipping-1", "buyer", "Buyer", 1))));
        set(controller, "active", true);
        set(controller, "sourceTerminal", new BlockPos(10, 64, 10));
        set(controller, "maxSpeed", 10D);
        return controller;
    }
    private static RegisteredVehicleControllerBlockEntity guidedFixture() throws Exception {
        var controller=fixture();set(controller,"aircraftClass",AircraftClass.FIXED_WING);
        UUID body=UUID.randomUUID();((ControllerRegistration)get(controller,"registration")).register(body,95,true);
        var path=RailAircraftPath.compile(List.of(new RailAircraftPath.Leg(RailAircraftPath.Phase.CRUISE,List.of(new Vec3(10,80,10),new Vec3(300,80,100)),10,0,true)),-90);
        var assignment=new GroundAssignment(2,"runway","stand","out","in");
        var mission=RailAircraftMission.start(UUID.randomUUID(),body,"minecraft:overworld",new BlockPos(10,64,10),assignment,new BlockPos(300,64,100),assignment,path);
        var saved=new CompoundTag();saved.put("Mission",mission.save());saved.putString("Forward","north");saved.putDouble("PendingDistance",mission.proposeStep(true).targetDistance());
        var rail=RegisteredAircraftRailController.load(saved);assertFalse(rail.invalid());set(controller,"railAircraft",rail);return controller;
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = RegisteredVehicleControllerBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = RegisteredVehicleControllerBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }
}
