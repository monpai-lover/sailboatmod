package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.control.ControllerRegistration;
import com.monpai.sailboatmod.transport.control.ControllerStatus;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the same physical-view update used by ticks, opening, GUI refresh and registration. */
class RegisteredVehicleControllerAvailabilityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void assembledUnregisteredControllerClearsPreviousPhysicalUnavailableReason() throws Exception {
        var controller = fixture(ControllerStatus.UNREGISTERED, "physical_unavailable");
        var registration = registration(controller);

        updatePhysicalView(controller, physicalView(true));

        assertEquals("", field(controller, "reason"));
        assertEquals(ControllerStatus.UNREGISTERED, field(controller, "status"));
        assertFalse(registration.registered(), "Restoring physical availability must not register the vehicle");
        assertEquals(new Vec3(197.5, 82.5, 199.5), controller.transportPosition());
    }

    @Test
    void registeredIdleControllerRefreshClearsOnlyTheOldUnavailableWarning() throws Exception {
        var controller = fixture(ControllerStatus.IDLE, "physical_unavailable");
        var registration = registration(controller);
        UUID physicalId = UUID.randomUUID();
        assertTrue(registration.register(physicalId, 24.5, true));

        updatePhysicalView(controller, physicalView(true));

        assertEquals("", field(controller, "reason"));
        assertEquals(ControllerStatus.IDLE, field(controller, "status"));
        assertTrue(registration.registered());
        assertEquals(physicalId, registration.physicalId());
    }

    @Test
    void missingOrUnavailablePhysicalViewDoesNotClearTheWarning() throws Exception {
        var controller = fixture(ControllerStatus.UNASSEMBLED, "physical_unavailable");

        updatePhysicalView(controller, null);
        assertEquals("physical_unavailable", field(controller, "reason"));
        updatePhysicalView(controller, physicalView(false));

        assertEquals("physical_unavailable", field(controller, "reason"));
        assertEquals(ControllerStatus.UNASSEMBLED, field(controller, "status"));
    }

    @Test
    void availablePhysicalViewPreservesRegistrationAndFlightFailures() throws Exception {
        for (String reason : List.of("registration_failed", "duplicate_controller", "flight_terminal_required",
                "flight_state_stale", "flight_control_conflict", "flight_recovery_initializing", "manual_control")) {
            var controller = fixture(ControllerStatus.FAULT, reason);

            updatePhysicalView(controller, physicalView(true));

            assertEquals(reason, field(controller, "reason"), "A physical refresh cannot resolve " + reason);
            assertEquals(ControllerStatus.FAULT, field(controller, "status"));
        }
    }

    @Test
    void newRegistrationFailureAfterPhysicalRecoverySurvivesLaterRefreshes() throws Exception {
        var controller = fixture(ControllerStatus.UNREGISTERED, "physical_unavailable");
        updatePhysicalView(controller, physicalView(true));
        assertEquals("", field(controller, "reason"));
        set(controller, "reason", "registration_failed");

        updatePhysicalView(controller, physicalView(true));

        assertEquals("registration_failed", field(controller, "reason"));
        assertFalse(registration(controller).registered());
    }

    @Test
    void physicalRecoveryDoesNotChangeActivePausedOrManualControlState() throws Exception {
        var controller = fixture(ControllerStatus.MANUAL, "manual_control");
        set(controller, "active", true);
        set(controller, "paused", true);
        set(controller, "manualTakeover", true);

        updatePhysicalView(controller, physicalView(true));

        assertEquals("manual_control", field(controller, "reason"));
        assertEquals(ControllerStatus.MANUAL, field(controller, "status"));
        assertTrue((boolean) field(controller, "active"));
        assertTrue((boolean) field(controller, "paused"));
        assertTrue((boolean) field(controller, "manualTakeover"));
    }

    private static PhysicalVehicleView physicalView(boolean available) {
        return new PhysicalVehicleView(UUID.randomUUID(), "minecraft:overworld", new Vec3(197.5, 82.5, 199.5),
                new Vec3(0, 0, -1), Vec3.ZERO, new AABB(196, 81, 196, 201, 83, 201), 24.5, available);
    }

    private static RegisteredVehicleControllerBlockEntity fixture(ControllerStatus status, String reason) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var controller = (RegisteredVehicleControllerBlockEntity) ((Unsafe) unsafeField.get(null))
                .allocateInstance(RegisteredVehicleControllerBlockEntity.class);
        set(controller, "registration", new ControllerRegistration(UUID.randomUUID(), "owner", LogisticsCarrier.Kind.AIR));
        set(controller, "reason", reason);
        set(controller, "status", status);
        return controller;
    }

    private static ControllerRegistration registration(RegisteredVehicleControllerBlockEntity controller) throws Exception {
        return (ControllerRegistration) field(controller, "registration");
    }

    private static void updatePhysicalView(RegisteredVehicleControllerBlockEntity controller, PhysicalVehicleView view)
            throws Exception {
        Method update = RegisteredVehicleControllerBlockEntity.class.getDeclaredMethod("updatePhysicalView", PhysicalVehicleView.class);
        update.setAccessible(true);
        update.invoke(controller, view);
    }

    private static Object field(RegisteredVehicleControllerBlockEntity controller, String name) throws Exception {
        Field field = RegisteredVehicleControllerBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private static void set(RegisteredVehicleControllerBlockEntity controller, String name, Object value) throws Exception {
        Field field = RegisteredVehicleControllerBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }
}
