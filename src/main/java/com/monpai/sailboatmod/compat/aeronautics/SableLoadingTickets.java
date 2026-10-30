package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.SailboatMod;
import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.*;

/** Own persistent ticket type; player command-forced tickets are never borrowed or removed. */
public final class SableLoadingTickets {
    private static Access access;
    private static boolean initialized;
    private record Access(Object type, MethodHandle container, MethodHandle body,
                          MethodHandle add, MethodHandle remove, MethodHandle collect) { }
    private SableLoadingTickets() { }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> type = Class.forName("dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType");
            Class<?> container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> serverContainer = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
            Class<?> body = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
            Class<?> baseBody = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            Object ownType = type.getMethod("create", ResourceLocation.class, Codec.class).invoke(null,
                    ResourceLocation.fromNamespaceAndPath(SailboatMod.MODID, "registered_transport"), UUIDUtil.CODEC);
            // Class.getMethod resolves other declared signatures too, including getContainer(ClientLevel).
            // Exact descriptors avoid linking that forbidden client class on a dedicated server.
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            access = new Access(ownType,
                    lookup.findStatic(container, "getContainer", MethodType.methodType(serverContainer, ServerLevel.class)),
                    lookup.findVirtual(container, "getSubLevel", MethodType.methodType(baseBody, UUID.class)),
                    lookup.findVirtual(serverContainer, "addForceLoadTicket", MethodType.methodType(boolean.class, body, type, Object.class)),
                    lookup.findVirtual(serverContainer, "removeForceLoadTicket", MethodType.methodType(boolean.class, body, type, Object.class)),
                    lookup.findVirtual(serverContainer, "collectForceLoadTickets", MethodType.methodType(Map.class)));
        } catch (ClassNotFoundException absent) {
            // Optional integration: ordinary sailboats need no Sable.
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(SableLoadingTickets.class).warn("Sable transport loading tickets unavailable", failure);
        }
    }

    public static boolean update(ServerLevel level, UUID physicalId, UUID registrationId, boolean retain) {
        initialize();
        if (access == null) return false;
        try {
            Object container = access.container.invoke(level);
            Object body = access.body.invoke(container, physicalId);
            if (body == null) return false;
            (retain ? access.add : access.remove).invoke(container, body, access.type, registrationId);
            return true; // false native return also means an identical ticket was already present/absent.
        } catch (Throwable failure) {
            // MethodHandle.invoke throws Throwable directly; fatal VM/native errors must not be hidden.
            if (failure instanceof Error error) throw error;
            return false;
        }
    }

    /** Only our own registration keys are reconciled. Native/player and other mods' tickets remain intact. */
    public static void reconcileOwned(ServerLevel level, Map<UUID, Set<UUID>> expected) {
        initialize();
        if (access == null) return;
        try {
            Object container = access.container.invoke(level);
            Map<?, ?> tickets = (Map<?, ?>) access.collect.invoke(container);
            Class<?> ticketType = Class.forName("dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket");
            Method type = ticketType.getMethod("type"), id = ticketType.getMethod("subLevelId"), key = ticketType.getMethod("key");
            List<Object[]> stale = new ArrayList<>();
            for (var entry : tickets.entrySet()) {
                for (Object ticket : (Collection<?>) entry.getValue()) {
                    if (!access.type.equals(type.invoke(ticket))) continue;
                    UUID physical = (UUID) id.invoke(ticket);
                    Object registration = key.invoke(ticket);
                    if (!expected.getOrDefault(physical, Set.of()).contains(registration)) stale.add(new Object[]{entry.getKey(), registration});
                }
            }
            for (Object[] ticket : stale) access.remove.invoke(container, ticket[0], access.type, ticket[1]);
        } catch (Throwable failure) {
            if (failure instanceof Error error) throw error;
            org.slf4j.LoggerFactory.getLogger(SableLoadingTickets.class).warn("Could not reconcile registered transport loading tickets", failure);
        }
    }
}
