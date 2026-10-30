package com.monpai.sailboatmod.map.route;

import com.monpai.sailboatmod.integration.bluemap.BlueMapMarkerSavedData;
import com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedData;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.maproute.MapRouteSnapshotPacket;
import com.monpai.sailboatmod.network.packet.maproute.MapRouteViewportRequestPacket;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraphSavedData;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Bounded display jobs over immutable public catalog inputs captured on the server thread. */
public final class MapRouteCatalogService implements AutoCloseable {
    private static volatile MapRouteCatalogService global = new MapRouteCatalogService(null);
    private static final int MAX_OWNERS = 64;
    private static final long REQUEST_INTERVAL = 5;
    private final MinecraftServer server;
    private final MapRouteRequestTracker tracker = new MapRouteRequestTracker();
    private final LinkedHashMap<UUID, Pending> pending = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Long> touched = new LinkedHashMap<>();
    private final Map<UUID, Long> submitted = new LinkedHashMap<>();
    private final Map<UUID, Future<?>> active = new LinkedHashMap<>();
    private final LinkedHashMap<String, CachedCatalog> catalogs = new LinkedHashMap<>();
    private final ArrayBlockingQueue<Completion> completed = new ArrayBlockingQueue<>(64);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "SharedMap-Routes");
                thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;

    private record Pending(UUID owner, MapRouteRequestTracker.Generation generation, MapRouteViewport viewport) { }
    private record Completion(Pending owner, MapRouteSnapshot snapshot) { }
    private record CachedCatalog(MapRouteCatalog.Captured catalog, long tick) { }

    private MapRouteCatalogService(MinecraftServer server) { this.server = server; }
    public static MapRouteCatalogService global() { return global; }
    public static void start(MinecraftServer server) { global.close(); global = new MapRouteCatalogService(server); }
    public static void stop() { global.close(); }

    public void request(ServerPlayer player, MapRouteViewportRequestPacket packet) {
        if (closed || server == null || player == null || packet == null) return;
        String dimension = player.serverLevel().dimension().location().toString();
        if (!dimension.equals(packet.dimensionId())) return;
        UUID owner = player.getUUID();
        long now = server.getTickCount();
        var generation = tracker.accept(owner, packet.sessionId(), packet.requestId(), packet.worldId(), dimension, now);
        if (generation == null) return;
        cancel(owner);
        pending.put(owner, new Pending(owner, generation, packet.viewport()));
        touched.remove(owner); touched.put(owner, now);
        while (touched.size() > MAX_OWNERS) forget(touched.keySet().iterator().next());
    }

    /** Once per server tick; map work never reads block entities, loads chunks or invokes route generation. */
    public void tick() {
        if (closed || server == null) return;
        long now = server.getTickCount();
        for (Completion result; (result = completed.poll()) != null;) {
            Pending request = result.owner();
            ServerPlayer player = server.getPlayerList().getPlayer(request.owner());
            if (player != null && tracker.current(request.owner(), request.generation(), player.serverLevel().dimension().location().toString())) {
                var generation = request.generation();
                ModNetwork.CHANNEL.send(player, new MapRouteSnapshotPacket(generation.sessionId(), generation.requestId(),
                        generation.worldId(), generation.dimensionId(), result.snapshot()));
                active.remove(request.owner());
            }
        }
        for (UUID owner : List.copyOf(touched.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null || now - touched.get(owner) > 600) forget(owner);
        }
        int budget = 2;
        for (Pending request : List.copyOf(pending.values())) {
            if (budget == 0 || workers.getQueue().remainingCapacity() == 0) break;
            if (now - submitted.getOrDefault(request.owner(), -REQUEST_INTERVAL) < REQUEST_INTERVAL) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(request.owner());
            if (player == null || !tracker.current(request.owner(), request.generation(), player.serverLevel().dimension().location().toString())) {
                forget(request.owner()); continue;
            }
            ServerLevel level = player.serverLevel();
            String dimension = request.generation().dimensionId();
            CachedCatalog cached = catalogs.get(dimension);
            if (cached == null || now - cached.tick() >= 20) {
                cached = new CachedCatalog(capture(level), now);
                catalogs.put(dimension, cached);
                while (catalogs.size() > 4) catalogs.remove(catalogs.keySet().iterator().next());
            }
            MapRouteCatalog.Captured input = cached.catalog();
            try {
                Future<?> job = workers.submit(() -> {
                    if (closed || Thread.currentThread().isInterrupted()) return;
                    MapRouteSnapshot snapshot;
                    try { snapshot = MapRouteCatalog.viewport(input, request.viewport()); }
                    catch (CancellationException cancelled) { return; }
                    catch (RuntimeException failure) {
                        LogUtils.getLogger().warn("Unable to prepare registered map route display", failure);
                        snapshot = new MapRouteSnapshot(List.of(), true);
                    }
                    if (!closed && !Thread.currentThread().isInterrupted()) completed.offer(new Completion(request, snapshot));
                });
                active.put(request.owner(), job);
                pending.remove(request.owner()); submitted.put(request.owner(), now); budget--;
            } catch (RejectedExecutionException busy) { break; }
        }
    }

    private MapRouteCatalog.Captured capture(ServerLevel level) {
        String dimension = level.dimension().location().toString();
        var roadJobs = ConstructionRuntimeSavedData.get(level).getRoadJobs();
        if (roadJobs.size() > MapRouteCatalog.MAX_INSPECTED_RECORDS) return new MapRouteCatalog.Captured(List.of(), true);
        Set<String> unfinished = roadJobs.stream()
                .filter(job -> dimension.equals(job.dimensionId()) && (job.rollbackActive()
                        || job.placedStepCount() < (job.isLegacyPathOnly() ? job.centerPath().size() : job.buildSteps().size())))
                .map(ConstructionRuntimeSavedData.RoadJobState::roadId).collect(Collectors.toSet());
        Set<UUID> unfinishedGraph = unfinished.stream().map(road -> UUID.nameUUIDFromBytes(
                ("road-graph-edge:" + road.trim().toLowerCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8))).collect(Collectors.toSet());
        var edges = RoadNetworkGraphSavedData.get(level).edges();
        var roads = NationSavedData.get(level).getRoadNetworks();
        // Registered catalogs are map-visible; edit/dispatch permissions are deliberately not consulted here.
        var captured = MapRouteCatalog.captureBounded(dimension,
                edges.stream().limit(MapRouteCatalog.MAX_INSPECTED_RECORDS + 1L).filter(edge -> !unfinishedGraph.contains(edge.edgeId())).toList(),
                roads.stream().limit(MapRouteCatalog.MAX_INSPECTED_RECORDS + 1L).filter(road -> !unfinished.contains(road.roadId())).toList(),
                BlueMapMarkerSavedData.get(level).getDocks(level.dimension()), StationMapRouteSavedData.get(level).stations(dimension));
        return new MapRouteCatalog.Captured(captured.routes(), captured.truncated()
                || edges.size() > MapRouteCatalog.MAX_INSPECTED_RECORDS || roads.size() > MapRouteCatalog.MAX_INSPECTED_RECORDS);
    }

    public void forget(UUID owner) {
        cancel(owner); pending.remove(owner); touched.remove(owner); submitted.remove(owner); tracker.forget(owner);
    }
    private void cancel(UUID owner) {
        Future<?> job = active.remove(owner);
        if (job != null) { job.cancel(true); if (job instanceof Runnable queued) workers.remove(queued); }
    }
    @Override public void close() {
        closed = true; tracker.clear(); pending.clear(); touched.clear(); submitted.clear(); catalogs.clear();
        active.values().forEach(job -> job.cancel(true)); active.clear(); workers.shutdownNow(); completed.clear();
    }
}
