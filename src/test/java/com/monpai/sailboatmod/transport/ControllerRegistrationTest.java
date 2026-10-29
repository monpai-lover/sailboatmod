package com.monpai.sailboatmod.transport;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class ControllerRegistrationTest {
    @Test
    void businessIdentitySurvivesNewPhysicalAssemblyAndSaveLoad() throws Exception {
        UUID registration = UUID.randomUUID(), owner = UUID.randomUUID();
        Class<?> type =
                Class.forName("com.monpai.sailboatmod.transport.control.ControllerRegistration");
        Object state =
                type.getConstructor(UUID.class, String.class, LogisticsCarrier.Kind.class)
                        .newInstance(registration, owner.toString(), LogisticsCarrier.Kind.WATER);
        assertEquals(
                true,
                type.getMethod("register", UUID.class, double.class, boolean.class)
                        .invoke(state, UUID.randomUUID(), 100.0D, true));
        UUID replacement = UUID.randomUUID();
        assertEquals(
                true,
                type.getMethod("register", UUID.class, double.class, boolean.class)
                        .invoke(state, replacement, 120.0D, true));
        CompoundTag saved = (CompoundTag) type.getMethod("save").invoke(state);
        Object loaded = type.getMethod("load", CompoundTag.class).invoke(null, saved);
        assertEquals(registration, type.getMethod("registrationId").invoke(loaded));
        assertEquals(replacement, type.getMethod("physicalId").invoke(loaded));
    }

    @Test
    void unassembledZeroMassAndDuplicateControllersCannotRegister() throws Exception {
        Class<?> type =
                Class.forName("com.monpai.sailboatmod.transport.control.ControllerRegistration");
        Object state =
                type.getConstructor(UUID.class, String.class, LogisticsCarrier.Kind.class)
                        .newInstance(
                                UUID.randomUUID(),
                                UUID.randomUUID().toString(),
                                LogisticsCarrier.Kind.LAND);
        for (double mass : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(
                    false,
                    type.getMethod("register", UUID.class, double.class, boolean.class)
                            .invoke(state, UUID.randomUUID(), mass, true));
        }
        assertEquals(
                false,
                type.getMethod("register", UUID.class, double.class, boolean.class)
                        .invoke(state, null, 100.0D, true));
        assertEquals(
                false,
                type.getMethod("register", UUID.class, double.class, boolean.class)
                        .invoke(state, UUID.randomUUID(), 100.0D, false));
    }

    @Test
    void mutationsRequireOwnerOrAdminAndActualWorldProximity() throws Exception {
        UUID owner = UUID.randomUUID();
        Class<?> type =
                Class.forName("com.monpai.sailboatmod.transport.control.ControllerRegistration");
        Object state =
                type.getConstructor(UUID.class, String.class, LogisticsCarrier.Kind.class)
                        .newInstance(
                                UUID.randomUUID(), owner.toString(), LogisticsCarrier.Kind.WATER);
        var can = type.getMethod("canManage", UUID.class, boolean.class, double.class);
        assertEquals(true, can.invoke(state, owner, false, 16.0D));
        assertEquals(false, can.invoke(state, UUID.randomUUID(), false, 16.0D));
        assertEquals(false, can.invoke(state, owner, false, 65.0D));
        assertEquals(false, can.invoke(state, owner, true, Double.NaN));
        assertEquals(true, can.invoke(state, UUID.randomUUID(), true, 16.0D));
    }

    @Test
    void savedRegistryAllowsOnlyOneBusinessRegistrationPerPhysicalStructure() throws Exception {
        Class<?> type =
                Class.forName(
                        "com.monpai.sailboatmod.transport.control.RegisteredTransportSavedData");
        Object data = type.getConstructor().newInstance();
        var claim = type.getMethod("claim", String.class, UUID.class, UUID.class);
        UUID body = UUID.randomUUID(), first = UUID.randomUUID();
        assertEquals(true, claim.invoke(data, "minecraft:overworld", body, first));
        assertEquals(true, claim.invoke(data, "minecraft:overworld", body, first));
        assertEquals(false, claim.invoke(data, "minecraft:overworld", body, UUID.randomUUID()));
        assertEquals(true, claim.invoke(data, "minecraft:the_nether", body, UUID.randomUUID()));
    }

    @Test
    void onlyActivePhysicalRegistrationsKeepTicketsAndForgottenIdsRemainRecognizable()
            throws Exception {
        Class<?> type =
                Class.forName(
                        "com.monpai.sailboatmod.transport.control.RegisteredTransportSavedData");
        Object data = type.getConstructor().newInstance();
        Class<?> entryType = Class.forName(type.getName() + "$Entry");
        UUID registration = UUID.randomUUID(), body = UUID.randomUUID();
        var ctor =
                entryType.getConstructor(
                        UUID.class,
                        String.class,
                        net.minecraft.core.BlockPos.class,
                        UUID.class,
                        String.class,
                        boolean.class);
        type.getMethod("put", entryType)
                .invoke(
                        data,
                        ctor.newInstance(
                                registration,
                                "minecraft:overworld",
                                net.minecraft.core.BlockPos.ZERO,
                                body,
                                "owner",
                                true));
        assertEquals(
                true,
                type.getMethod("shouldRetainPhysicalTicket", UUID.class)
                        .invoke(data, registration));
        type.getMethod("put", entryType)
                .invoke(
                        data,
                        ctor.newInstance(
                                registration,
                                "minecraft:overworld",
                                net.minecraft.core.BlockPos.ZERO,
                                body,
                                "owner",
                                false));
        assertEquals(
                false,
                type.getMethod("shouldRetainPhysicalTicket", UUID.class)
                        .invoke(data, registration));
        type.getMethod("forget", UUID.class).invoke(data, registration);
        assertEquals(
                true, type.getMethod("isOwnedRegistration", UUID.class).invoke(data, registration));
        assertEquals(
                false,
                type.getMethod("shouldRetainPhysicalTicket", UUID.class)
                        .invoke(data, registration));
    }

    @Test
    void shutdownPreservesOnlyRunningUnpausedMissionLoading() throws Exception {
        Class<?> type =
                Class.forName("com.monpai.sailboatmod.transport.control.ControllerRegistration");
        var preserve =
                type.getMethod(
                        "preserveLoadingOnRemoval", boolean.class, boolean.class, boolean.class);
        assertEquals(true, preserve.invoke(null, true, true, false));
        assertEquals(false, preserve.invoke(null, false, true, false));
        assertEquals(false, preserve.invoke(null, true, false, false));
        assertEquals(false, preserve.invoke(null, true, true, true));
    }

    @Test
    void permanentRemovalStopsNativeControlsReleasesTicketsAndAllowsReplacement() throws Exception {
        Class<?> type =
                Class.forName(
                        "com.monpai.sailboatmod.transport.control.RegisteredTransportSavedData");
        Object data = type.getConstructor().newInstance();
        UUID body = UUID.randomUUID(), registration = UUID.randomUUID();
        var claim = type.getMethod("claim", String.class, UUID.class, UUID.class);
        claim.invoke(data, "minecraft:overworld", body, registration);
        java.util.List<String> events = new java.util.ArrayList<>();
        type.getMethod("removeController", UUID.class, Runnable.class, Runnable.class)
                .invoke(
                        data,
                        registration,
                        (Runnable) () -> events.add("stop"),
                        (Runnable) () -> events.add("release"));
        assertEquals(java.util.List.of("stop", "release"), events);
        assertEquals(true, claim.invoke(data, "minecraft:overworld", body, UUID.randomUUID()));
        assertEquals(
                true, type.getMethod("isOwnedRegistration", UUID.class).invoke(data, registration));
    }

    @Test
    void permanentRemovalStillReleasesClaimIfNativeActuatorThrows() throws Exception {
        Class<?> type =
                Class.forName(
                        "com.monpai.sailboatmod.transport.control.RegisteredTransportSavedData");
        Object data = type.getConstructor().newInstance();
        UUID body = UUID.randomUUID(), registration = UUID.randomUUID();
        var claim = type.getMethod("claim", String.class, UUID.class, UUID.class);
        claim.invoke(data, "minecraft:overworld", body, registration);
        java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean();
        var remove = type.getMethod("removeController", UUID.class, Runnable.class, Runnable.class);
        assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () ->
                        remove.invoke(
                                data,
                                registration,
                                (Runnable)
                                        () -> {
                                            throw new IllegalStateException("actuator failed");
                                        },
                                (Runnable) () -> released.set(true)));
        assertTrue(released.get());
        assertEquals(true, claim.invoke(data, "minecraft:overworld", body, UUID.randomUUID()));
    }
}
