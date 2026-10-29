package com.monpai.sailboatmod.transport;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Server-scoped resolver. Runtime ids are UI handles; UUIDs remain the business identity. */
public final class TransportRegistry {
    private static final Map<MinecraftServer, Map<String, Index>> SERVERS = new WeakHashMap<>();
    private static final AtomicInteger NEXT_RUNTIME_ID = new AtomicInteger(-2);

    private TransportRegistry() {}

    public static int allocateRuntimeId() {
        return NEXT_RUNTIME_ID.getAndDecrement();
    }

    private static synchronized Index index(ServerLevel level) {
        return SERVERS.computeIfAbsent(level.getServer(), ignored -> new HashMap<>())
                .computeIfAbsent(level.dimension().location().toString(), ignored -> new Index());
    }

    public static void register(ServerLevel level, LogisticsCarrier carrier) {
        if (level != null && carrier != null) index(level).register(carrier);
    }

    public static void unregister(ServerLevel level, UUID uuid) {
        if (level != null) index(level).unregister(uuid);
    }

    public static void forget(ServerLevel level, UUID uuid) {
        if (level != null) index(level).forget(uuid);
    }

    public static boolean isKnown(ServerLevel level, UUID uuid) {
        return level != null && index(level).isKnown(uuid);
    }

    public static void remember(ServerLevel level, UUID uuid) {
        if (level != null && uuid != null) index(level).remember(uuid);
    }

    public static synchronized void clear(MinecraftServer server) {
        SERVERS.remove(server);
    }

    public static LogisticsCarrier resolve(ServerLevel level, UUID uuid) {
        if (level == null || uuid == null) return null;
        Entity entity = level.getEntity(uuid);
        return entity instanceof LogisticsCarrier carrier ? carrier : index(level).resolve(uuid);
    }

    public static LogisticsCarrier resolveRuntimeId(ServerLevel level, int id) {
        if (level == null) return null;
        if (id < 0) return index(level).resolveRuntimeId(id);
        return level.getEntity(id) instanceof LogisticsCarrier carrier ? carrier : null;
    }

    public static List<LogisticsCarrier> list(ServerLevel level) {
        if (level == null) return List.of();
        Map<UUID, LogisticsCarrier> all = new LinkedHashMap<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof LogisticsCarrier carrier)
                all.put(carrier.getTransportUuid(), carrier);
        }
        for (LogisticsCarrier carrier : index(level).list())
            all.put(carrier.getTransportUuid(), carrier);
        return List.copyOf(all.values());
    }

    public static List<LogisticsCarrier> nearby(ServerLevel level, AABB bounds) {
        if (level == null || bounds == null) return List.of();
        Map<UUID, LogisticsCarrier> all = new LinkedHashMap<>();
        for (Entity entity :
                level.getEntitiesOfClass(
                        Entity.class, bounds, entity -> entity instanceof LogisticsCarrier)) {
            LogisticsCarrier carrier = (LogisticsCarrier) entity;
            all.put(carrier.getTransportUuid(), carrier);
        }
        for (LogisticsCarrier carrier : index(level).list()) {
            if (bounds.intersects(carrier.transportBounds()))
                all.put(carrier.getTransportUuid(), carrier);
        }
        return List.copyOf(all.values());
    }

    public static final class Index {
        private final Map<UUID, LogisticsCarrier> carriers = new LinkedHashMap<>();
        private final Set<UUID> known = new HashSet<>();

        public synchronized void register(LogisticsCarrier carrier) {
            Objects.requireNonNull(carrier.getTransportUuid(), "transport UUID");
            carriers.put(carrier.getTransportUuid(), carrier);
            known.add(carrier.getTransportUuid());
        }

        public synchronized void remember(UUID uuid) {
            known.add(uuid);
        }

        public synchronized void unregister(UUID uuid) {
            carriers.remove(uuid);
        }

        public synchronized void forget(UUID uuid) {
            carriers.remove(uuid);
            known.remove(uuid);
        }

        public synchronized boolean isKnown(UUID uuid) {
            return known.contains(uuid);
        }

        public synchronized LogisticsCarrier resolve(UUID uuid) {
            return carriers.get(uuid);
        }

        public synchronized LogisticsCarrier resolveRuntimeId(int id) {
            return carriers.values().stream()
                    .filter(carrier -> carrier.getTransportId() == id)
                    .findFirst()
                    .orElse(null);
        }

        public synchronized List<LogisticsCarrier> list() {
            return List.copyOf(carriers.values());
        }
    }
}
