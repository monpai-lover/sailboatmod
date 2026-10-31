package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SyncAutoRouteDocksPacket;
import com.monpai.sailboatmod.route.terrain.TellusTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.monpai.sailboatmod.route.water.PortBerthValidator.*;

/** Async Tellus candidate refinement. The existing packet format and persistent dock names stay unchanged. */
@EventBusSubscriber(modid = SailboatMod.MODID)
public final class PortCandidateListing {
    private static final Map<MinecraftServer, Map<UUID, AtomicBoolean>> REQUESTS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Long>> LAST_REQUEST = new WeakHashMap<>();
    private static ThreadPoolExecutor workers;
    private static final int MAX_CHECKED_PORTS = 64;
    private PortCandidateListing() {}

    public static synchronized boolean acceptRequest(MinecraftServer server, UUID player) {
        long now = System.nanoTime();
        var times = LAST_REQUEST.computeIfAbsent(server, ignored -> new HashMap<>());
        Long previous = times.get(player);
        if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(1)) return false;
        times.put(player, now);
        return true;
    }

    public static synchronized void cancelPrevious(MinecraftServer server, UUID player) {
        var requests = REQUESTS.get(server);
        if (requests != null) {
            AtomicBoolean old = requests.remove(player);
            if (old != null) old.set(true);
        }
    }

    private static synchronized AtomicBoolean begin(MinecraftServer server, UUID player) {
        cancelPrevious(server, player);
        AtomicBoolean ticket = new AtomicBoolean();
        REQUESTS.computeIfAbsent(server, ignored -> new HashMap<>()).put(player, ticket);
        return ticket;
    }

    private static synchronized ThreadPoolExecutor workers() {
        if (workers == null || workers.isShutdown()) {
            workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), r -> {
                Thread thread = new Thread(r, "PortTerrain-Worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            workers.allowCoreThreadTimeOut(true);
        }
        return workers;
    }

    public static void refine(ServerLevel level, DockBlockEntity source, List<DockBlockEntity> targets,
                              List<AvailableDockEntry> entries, ServerPlayer player) {
        AtomicBoolean ticket = begin(level.getServer(), player.getUUID());
        BlockPos sourcePos = source.getBlockPos();
        List<AvailableDockEntry> original = List.copyOf(entries);
        List<DockBlockEntity> docks = List.copyOf(targets);
        Zone sourceZone = zone(source);
        List<Zone> zones = new ArrayList<>();
        zones.add(sourceZone);
        int count = Math.min(MAX_CHECKED_PORTS, docks.size());
        for (int i = 0; i < count; i++) zones.add(zone(docks.get(i)));
        var probe = PortTerrainProbe.capture(level, zones, TellusTerrain.forLevel(level).orElse(null));
        // Show metadata immediately, explicitly pending; never hide ports because a download is outstanding.
        send(player, sourcePos, original.stream().map(e -> labelled(e, Verdict.PENDING)).toList());
        try {
            CompletableFuture.supplyAsync(() -> {
                probe.start(false, 4_000, ticket::get);
                Result start = probe.resolve(sourceZone);
                List<Verdict> verdicts = new ArrayList<>();
                for (int i = 0; i < original.size(); i++) {
                    if (ticket.get()) break;
                    Result end = i < count && !probe.expired() ? probe.resolve(zones.get(i + 1)) : Result.pending();
                    verdicts.add(combine(start.verdict(), end.verdict()));
                }
                return verdicts;
            }, workers()).whenComplete((verdicts, error) -> {
                if (ticket.get()) return;
                level.getServer().execute(() -> {
                    if (ticket.get() || player.isRemoved() || player.level() != level
                            || level.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
                    List<AvailableDockEntry> updated = new ArrayList<>();
                    boolean sourceUnchanged = !source.isRemoved() && zone(source).equals(sourceZone);
                    for (int i = 0; i < original.size(); i++) {
                        DockBlockEntity target = docks.get(i);
                        if (source.isRemoved() || target.isRemoved()
                                || !WaterRouteCandidateService.canListCandidate(level, source, target).successful()) continue;
                        Verdict verdict = error == null && verdicts != null && i < verdicts.size()
                                ? verdicts.get(i) : Verdict.PENDING;
                        if (!sourceUnchanged || i >= count || !zone(target).equals(zones.get(i + 1))) verdict = Verdict.PENDING;
                        if (verdict != Verdict.INVALID) updated.add(labelled(original.get(i), verdict));
                    }
                    send(player, sourcePos, updated);
                    finish(level.getServer(), player.getUUID(), ticket);
                });
            });
        } catch (RejectedExecutionException busy) {
            // The pending list remains usable; formal creation will validate again in its own task.
            finish(level.getServer(), player.getUUID(), ticket);
        }
    }

    static Verdict combine(Verdict source, Verdict target) {
        if (source == Verdict.INVALID || target == Verdict.INVALID) return Verdict.INVALID;
        if (source == Verdict.PENDING || target == Verdict.PENDING) return Verdict.PENDING;
        if (source == Verdict.PREDICTED || target == Verdict.PREDICTED) return Verdict.PREDICTED;
        return Verdict.VERIFIED;
    }

    static AvailableDockEntry labelled(AvailableDockEntry entry, Verdict verdict) {
        String prefix = switch (verdict) {
            case PENDING -> "[地形待验证] ";
            case PREDICTED -> "[Tellus预检] ";
            case VERIFIED -> "[泊位已验证] ";
            default -> "";
        };
        String name = entry.dockName() == null ? "" : entry.dockName();
        int end = Math.min(name.length(), 64 - prefix.length());
        if (end > 0 && end < name.length() && Character.isHighSurrogate(name.charAt(end - 1))) end--;
        return new AvailableDockEntry(entry.pos(), prefix + name.substring(0, end),
                entry.ownerName(), entry.nationName(), entry.distance());
    }

    public static Zone zone(DockBlockEntity dock) {
        BlockPos p = dock.getBlockPos();
        return new Zone(p.getX(), p.getY(), p.getZ(), dock.getZoneMinX(), dock.getZoneMaxX(), dock.getZoneMinZ(), dock.getZoneMaxZ());
    }

    private static void send(ServerPlayer player, BlockPos source, List<AvailableDockEntry> entries) {
        ModNetwork.CHANNEL.send(player, new SyncAutoRouteDocksPacket(source, TransportTerminalKind.PORT, entries));
    }

    private static synchronized void finish(MinecraftServer server, UUID player, AtomicBoolean ticket) {
        var requests = REQUESTS.get(server);
        if (requests != null) requests.remove(player, ticket);
    }

    @SubscribeEvent
    public static synchronized void stopped(ServerStoppedEvent event) {
        LAST_REQUEST.remove(event.getServer());
        var requests = REQUESTS.remove(event.getServer());
        if (requests != null) requests.values().forEach(ticket -> ticket.set(true));
        if (REQUESTS.isEmpty() && workers != null) {
            workers.shutdownNow();
            workers = null;
        }
    }
}
