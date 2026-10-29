package com.monpai.sailboatmod.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.monpai.sailboatmod.entity.TransportEntity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class PhysicalTransportBoundaryTest {
    @Test
    void existingEntityCarrierExtendsBusinessBoundary() {
        assertTrue(LogisticsCarrier.class.isAssignableFrom(TransportEntity.class));
    }

    @Test
    void physicalCarrierCanResolveWithoutMinecraftEntity() throws Exception {
        Class<?> indexType =
                Class.forName("com.monpai.sailboatmod.transport.TransportRegistry$Index");
        var ctor = indexType.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object index = ctor.newInstance();
        UUID registration = UUID.randomUUID();
        LogisticsCarrier physical = carrier(registration, -42, LogisticsCarrier.Kind.WATER);
        indexType.getMethod("register", LogisticsCarrier.class).invoke(index, physical);

        assertSame(
                physical, indexType.getMethod("resolve", UUID.class).invoke(index, registration));
        assertSame(physical, indexType.getMethod("resolveRuntimeId", int.class).invoke(index, -42));
        assertEquals(List.of(physical), indexType.getMethod("list").invoke(index));
    }

    @Test
    void unloadedRegistrationRemainsKnownButIsNotDispatchable() throws Exception {
        Class<?> indexType =
                Class.forName("com.monpai.sailboatmod.transport.TransportRegistry$Index");
        var ctor = indexType.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object index = ctor.newInstance();
        UUID registration = UUID.randomUUID();
        LogisticsCarrier physical = carrier(registration, -43, LogisticsCarrier.Kind.LAND);
        indexType.getMethod("register", LogisticsCarrier.class).invoke(index, physical);
        indexType.getMethod("unregister", UUID.class).invoke(index, registration);

        assertNull(indexType.getMethod("resolve", UUID.class).invoke(index, registration));
        assertEquals(true, indexType.getMethod("isKnown", UUID.class).invoke(index, registration));
        assertEquals(List.of(), indexType.getMethod("list").invoke(index));
        indexType.getMethod("forget", UUID.class).invoke(index, registration);
        assertEquals(false, indexType.getMethod("isKnown", UUID.class).invoke(index, registration));
    }

    @Test
    void replacementKeepsOnlyCurrentCarrierForStableRegistration() throws Exception {
        Class<?> indexType =
                Class.forName("com.monpai.sailboatmod.transport.TransportRegistry$Index");
        var ctor = indexType.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object index = ctor.newInstance();
        UUID registration = UUID.randomUUID();
        LogisticsCarrier first = carrier(registration, -44, LogisticsCarrier.Kind.WATER);
        LogisticsCarrier current = carrier(registration, -45, LogisticsCarrier.Kind.WATER);
        indexType.getMethod("register", LogisticsCarrier.class).invoke(index, first);
        indexType.getMethod("register", LogisticsCarrier.class).invoke(index, current);

        assertNull(indexType.getMethod("resolveRuntimeId", int.class).invoke(index, -44));
        assertSame(current, indexType.getMethod("resolveRuntimeId", int.class).invoke(index, -45));
        assertEquals(List.of(current), indexType.getMethod("list").invoke(index));
    }

    @Test
    void entityReceiptNeverTouchesPreexistingCargo() throws Exception {
        AtomicInteger unloaded = new AtomicInteger();
        LogisticsCarrier carrier = cargoCarrier(true, unloaded);
        assertNull(loadReceipt(carrier));
        assertEquals(0, unloaded.get());
    }

    @Test
    void entityReceiptRollsBackOnlyOnce() throws Exception {
        AtomicInteger unloaded = new AtomicInteger();
        CargoLoadReceipt receipt = loadReceipt(cargoCarrier(false, unloaded));
        assertNotNull(receipt);
        receipt.rollback();
        receipt.rollback();
        assertEquals(1, unloaded.get());
    }

    @Test
    void committedEntityReceiptCannotDrainLaterCargo() throws Exception {
        AtomicInteger unloaded = new AtomicInteger();
        CargoLoadReceipt receipt = loadReceipt(cargoCarrier(false, unloaded));
        assertNotNull(receipt);
        receipt.commit();
        receipt.rollback();
        assertEquals(0, unloaded.get());
    }

    private static CargoLoadReceipt loadReceipt(LogisticsCarrier carrier) throws Exception {
        return (CargoLoadReceipt)
                CargoLoadReceipt.class
                        .getMethod("loadEmptyCarrier", LogisticsCarrier.class, List.class)
                        .invoke(null, carrier, List.of());
    }

    private static LogisticsCarrier cargoCarrier(boolean occupied, AtomicInteger unloaded) {
        AtomicBoolean cargo = new AtomicBoolean(occupied);
        return (LogisticsCarrier)
                Proxy.newProxyInstance(
                        LogisticsCarrier.class.getClassLoader(),
                        new Class<?>[] {LogisticsCarrier.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "hasCargo" -> cargo.get();
                                    case "canLoadCargo" -> true;
                                    case "loadCargo" -> {
                                        cargo.set(true);
                                        yield true;
                                    }
                                    case "unloadAllCargo" -> {
                                        unloaded.incrementAndGet();
                                        cargo.set(false);
                                        yield List.of();
                                    }
                                    default -> null;
                                });
    }

    static LogisticsCarrier carrier(UUID uuid, int runtimeId, LogisticsCarrier.Kind kind) {
        return (LogisticsCarrier)
                Proxy.newProxyInstance(
                        LogisticsCarrier.class.getClassLoader(),
                        new Class<?>[] {LogisticsCarrier.class},
                        (proxy, method, args) -> {
                            if (method.isDefault())
                                return InvocationHandler.invokeDefault(proxy, method, args);
                            return switch (method.getName()) {
                                case "getTransportUuid" -> uuid;
                                case "getTransportId" -> runtimeId;
                                case "transportKind" -> kind;
                                case "getTransportName" -> Component.literal("Physical vessel");
                                case "transportPosition" -> Vec3.ZERO;
                                case "isTransportAlive" -> true;
                                case "equals" -> proxy == args[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> "Physical vessel " + uuid;
                                default -> null;
                            };
                        });
    }
}
