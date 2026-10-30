package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.control.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real controller's NBT path without constructing deferred mod block registries. */
class AirControllerPersistenceTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

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
        set(controller, "registration", new ControllerRegistration(UUID.randomUUID(), "owner", LogisticsCarrier.Kind.AIR));
        for (String field : List.of("vehicleName", "ownerName", "pendingShipper", "reason")) set(controller, field, "");
        set(controller, "status", ControllerStatus.PAUSED);
        set(controller, "aircraftClass", AircraftClass.ROTORCRAFT);
        set(controller, "flightPhase", "PREFLIGHT");
        set(controller, "flightFingerprint", "original-real-card");
        set(controller, "arrivalFacility", "pad");
        set(controller, "ports", new EnumMap<ControlChannel, Direction>(ControlChannel.class));
        set(controller, "cargoOffsets", new ArrayList<BlockPos>());
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
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = RegisteredVehicleControllerBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = RegisteredVehicleControllerBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }
}
