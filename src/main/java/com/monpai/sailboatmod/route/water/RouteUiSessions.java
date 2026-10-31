package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.RouteWorkProgressPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.util.*;

/** Authoritative task identities, progress history and chunk-ticket lifetimes; never owned by a screen. */
@EventBusSubscriber(modid = SailboatMod.MODID)
public final class RouteUiSessions {
    private static final Map<MinecraftServer, ServerState> SERVERS = new WeakHashMap<>();
    private static final net.minecraft.server.level.TicketType<UUID> ROUTE_TICKET =
            net.minecraft.server.level.TicketType.create("sailboatmod_route_ui", UUID::compareTo);

    private static final class ServerState {
        final RouteTaskRegistry registry = new RouteTaskRegistry();
        final Map<UUID, LinkedHashMap<UUID, Session>> owners = new HashMap<>();
    }
    private RouteUiSessions() {}

    public static final class Session {
        public final ServerPlayer player;
        public final UUID request;
        public final BlockPos source, target;
        public final String key;
        public volatile boolean closed;
        private WaterRouteTask ownedTask;
        public void attach(WaterRouteTask task) {
            if (closed) task.cancel(); else ownedTask = task;
        }
        private void cancelOwnedTask() { if (ownedTask != null) ownedTask.cancel(); }
        public final net.minecraft.server.level.ServerLevel level;
        private final ServerState serverState;
        private final long started = System.nanoTime();

        Session(ServerPlayer player, UUID request, BlockPos source, BlockPos target, ServerState state) {
            this.player = player; this.request = request;
            this.source = source.immutable(); this.target = target.immutable();
            level = player.serverLevel(); serverState = state;
            level.getChunkSource().addRegionTicket(ROUTE_TICKET, new net.minecraft.world.level.ChunkPos(source), 0, request);
            level.getChunkSource().addRegionTicket(ROUTE_TICKET, new net.minecraft.world.level.ChunkPos(target), 0, request);
            key = level.dimension().location() + "|" + source.asLong() + "|" + target.asLong();
        }

        public void send(int percent, int state, String stage) {
            if (closed) return;
            serverState.registry.update(player.getUUID(), request, percent, state, stage, System.currentTimeMillis());
            if (!player.isRemoved()) ModNetwork.CHANNEL.send(player,
                    new RouteWorkProgressPacket(source, request, true, percent, 0, 0, state, stage));
        }

        public void end(int percent, int state, String stage) {
            if (closed) return;
            try { send(percent, state, stage); }
            finally {
                closed = true;
                level.getChunkSource().removeRegionTicket(ROUTE_TICKET, new net.minecraft.world.level.ChunkPos(source), 0, request);
                level.getChunkSource().removeRegionTicket(ROUTE_TICKET, new net.minecraft.world.level.ChunkPos(target), 0, request);
            }
        }
    }

    private static Map<UUID, Session> owned(ServerPlayer player) {
        ServerState state = player == null ? null : SERVERS.get(player.getServer());
        return state == null ? Map.of() : state.owners.getOrDefault(player.getUUID(), new LinkedHashMap<>());
    }

    public static Session current(ServerPlayer player) {
        Session result = null;
        for (Session session : owned(player).values()) if (!session.closed) result = session;
        return result;
    }

    public static Session forRoute(ServerPlayer player, BlockPos source, BlockPos target) {
        for (Session session : owned(player).values()) {
            if (!session.closed && session.source.equals(source) && session.target.equals(target)
                    && session.level == player.level()) return session;
        }
        return null;
    }

    public static List<RouteTaskView> tasks(ServerPlayer player) {
        ServerState state = SERVERS.get(player.getServer());
        return state == null ? List.of() : state.registry.list(player.getUUID());
    }

    public static Session begin(ServerPlayer player, UUID id, BlockPos source, BlockPos target) {
        ServerState state = SERVERS.computeIfAbsent(player.getServer(), ignored -> new ServerState());
        if (!state.registry.begin(player.getUUID(), id, player.level().dimension().location().toString(),
                source, target, System.currentTimeMillis())) {
            ModNetwork.CHANNEL.send(player, new RouteWorkProgressPacket(source, id, true, 0, 0, 0,
                    RouteWorkProgressPacket.BUSY, "相同航线已运行或已达4个任务上限；请打开寻路任务队列"));
            return null;
        }
        var owned = state.owners.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>());
        owned.entrySet().removeIf(entry -> entry.getValue().closed);
        Session session = new Session(player, id, source, target, state);
        owned.put(id, session);
        session.send(0, RouteWorkProgressPacket.RUNNING, "异步载入两端码头");
        return session;
    }

    public static void cancel(ServerPlayer player, UUID id) {
        Session session = owned(player).get(id);
        if (session == null || session.closed) return;
        // The owner's stable server task ID is sufficient; no open window or dock proximity is required.
        session.cancelOwnedTask();
        session.end(0, RouteWorkProgressPacket.CANCELLED, "已取消");
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            for (Session session : List.copyOf(owned(player).values())) cancel(player, session.request);
            ServerState state = SERVERS.get(player.getServer());
            if (state != null) state.owners.remove(player.getUUID());
            // Retain bounded cancelled history for this owner on reconnect, not live workers/tickets.
        }
    }

    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        ServerState state = SERVERS.get(event.getServer());
        if (state == null) return;
        for (var owned : state.owners.values()) for (Session session : owned.values()) {
            if (!session.closed && (session.player.level() != session.level
                    || System.nanoTime() - session.started > 600_000_000_000L)) {
                session.cancelOwnedTask();
                session.end(0, RouteWorkProgressPacket.CANCELLED, "任务已超时或玩家离开维度");
            }
        }
    }

    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        ServerState state = SERVERS.remove(event.getServer());
        if (state == null) return;
        for (var owned : state.owners.values()) for (Session session : owned.values()) {
            session.cancelOwnedTask();
            session.end(0, RouteWorkProgressPacket.CANCELLED, "服务器停止");
        }
    }
}
