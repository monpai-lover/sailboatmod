package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ClaimMapTaskService;
import com.monpai.sailboatmod.nation.service.ClaimMapViewportService;
import com.monpai.sailboatmod.nation.service.ClaimMapViewportSnapshot;
import com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public record RequestClaimMapViewportPacket(ScreenKind screenKind,
                                            String ownerId,
                                            String dimensionId,
                                            long revision,
                                            int radius,
                                            int centerChunkX,
                                            int centerChunkZ,
                                            int prefetchRadius) {
    private static final int DEFAULT_SERVER_RADIUS_LIMIT = 8;
    static final int MAX_SERVER_PREFETCH_RADIUS = 2;
    private static final String VIEWPORT_TASK_KIND = "claim-map-viewport";
    private static final ConcurrentMap<String, OpenViewportState> OPEN_VIEWPORTS = new ConcurrentHashMap<>();

    public enum ScreenKind {
        TOWN,
        NATION
    }

    static final class OpenViewportState {
        private final String logicalScreenKey;
        private final UUID playerId;
        private final ScreenKind screenKind;
        private final String ownerId;
        private final String dimensionId;
        private final long revision;
        private final int radius;
        private final int centerChunkX;
        private final int centerChunkZ;
        private final int prefetchRadius;
        private volatile boolean dirty;

        private OpenViewportState(String logicalScreenKey,
                                  UUID playerId,
                                  ScreenKind screenKind,
                                  String ownerId,
                                  String dimensionId,
                                  long revision,
                                  int radius,
                                  int centerChunkX,
                                  int centerChunkZ,
                                  int prefetchRadius,
                                  boolean dirty) {
            this.logicalScreenKey = logicalScreenKey == null ? "" : logicalScreenKey;
            this.playerId = playerId;
            this.screenKind = screenKind == null ? ScreenKind.TOWN : screenKind;
            this.ownerId = ownerId == null ? "" : ownerId;
            this.dimensionId = dimensionId == null ? "" : dimensionId;
            this.revision = revision;
            this.radius = Math.max(0, radius);
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.prefetchRadius = Math.max(0, prefetchRadius);
            this.dirty = dirty;
        }

        String logicalScreenKey() {
            return logicalScreenKey;
        }

        UUID playerId() {
            return playerId;
        }

        ScreenKind screenKind() {
            return screenKind;
        }

        String ownerId() {
            return ownerId;
        }

        String dimensionId() {
            return dimensionId;
        }

        long revision() {
            return revision;
        }

        int radius() {
            return radius;
        }

        int centerChunkX() {
            return centerChunkX;
        }

        int centerChunkZ() {
            return centerChunkZ;
        }

        int prefetchRadius() {
            return prefetchRadius;
        }

        boolean dirty() {
            return dirty;
        }

        void markDirty() {
            this.dirty = true;
        }

        void markClean() {
            this.dirty = false;
        }
    }

    public RequestClaimMapViewportPacket {
        screenKind = screenKind == null ? ScreenKind.TOWN : screenKind;
        ownerId = ownerId == null ? "" : ownerId.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        radius = Math.max(0, radius);
        prefetchRadius = Math.max(0, prefetchRadius);
    }

    public static void encode(RequestClaimMapViewportPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.screenKind());
        PacketStringCodec.writeUtfSafe(buffer, packet.ownerId(), 64);
        PacketStringCodec.writeUtfSafe(buffer, packet.dimensionId(), 64);
        buffer.writeLong(packet.revision());
        buffer.writeVarInt(packet.radius());
        buffer.writeInt(packet.centerChunkX());
        buffer.writeInt(packet.centerChunkZ());
        buffer.writeVarInt(packet.prefetchRadius());
    }

    public static RequestClaimMapViewportPacket decode(FriendlyByteBuf buffer) {
        return new RequestClaimMapViewportPacket(
                buffer.readEnum(ScreenKind.class),
                buffer.readUtf(64),
                buffer.readUtf(64),
                buffer.readLong(),
                buffer.readVarInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readVarInt()
        );
    }

    public static void handle(RequestClaimMapViewportPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            dispatch(player, packet);
        });
        context.setPacketHandled(true);
    }

    static void dispatch(ServerPlayer player, RequestClaimMapViewportPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        ServerLevel level = resolveLevel(player, packet.dimensionId());
        if (level == null) {
            return;
        }

        int radius = clampRadiusForServer(packet.radius());
        int prefetchRadius = clampPrefetchRadiusForServer(packet.prefetchRadius());
        RequestClaimMapViewportPacket normalized = new RequestClaimMapViewportPacket(
                packet.screenKind(),
                packet.ownerId(),
                packet.dimensionId(),
                packet.revision(),
                radius,
                packet.centerChunkX(),
                packet.centerChunkZ(),
                prefetchRadius
        );
        String resolvedDimensionId = level.dimension().location().toString();
        ClaimPreviewTerrainService.warmViewportFromPersisted(
                level,
                resolvedDimensionId,
                normalized.centerChunkX(),
                normalized.centerChunkZ(),
                normalized.radius(),
                normalized.prefetchRadius()
        );
        String logicalScreenKey = logicalViewportKey(player.getUUID(), normalized.screenKind(), normalized.ownerId());
        OpenViewportState state = recordViewportRequest(logicalScreenKey, player.getUUID(), normalized, resolvedDimensionId);
        if (state == null || state.revision() != normalized.revision()) {
            return;
        }
        ClaimMapTaskService taskService = ClaimMapTaskService.get();
        if (taskService != null) {
            scheduleViewportTask(
                    taskService,
                    state,
                    RequestClaimMapViewportPacket::buildSnapshotFromCache,
                    (latestState, snapshot) -> {
                            ServerPlayer latestPlayer = player.getServer().getPlayerList().getPlayer(latestState.playerId());
                            if (latestPlayer != null) {
                                sendSnapshot(latestPlayer, latestState.screenKind(), latestState.ownerId(), snapshot);
                            }
                        }
                );
            }
        }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ClaimMapTaskService taskService = ClaimMapTaskService.get();
        if (taskService == null) {
            return;
        }
        Set<String> staleKeys = ConcurrentHashMap.newKeySet();
        OPEN_VIEWPORTS.forEach((key, state) -> {
            if (state == null || server.getPlayerList().getPlayer(state.playerId()) != null) {
                return;
            }
            staleKeys.add(key);
        });
        if (!staleKeys.isEmpty()) {
            staleKeys.forEach(OPEN_VIEWPORTS::remove);
        }
        runViewportCompletionCycle(
                taskService,
                state -> buildSnapshotFromCache(state),
                (state, snapshot) -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(state.playerId());
                    if (player == null) {
                        OPEN_VIEWPORTS.remove(state.logicalScreenKey(), state);
                        return;
                    }
                    sendSnapshot(player, state.screenKind(), state.ownerId(), snapshot);
                },
                ClaimPreviewTerrainService.consumeInvalidatedViewportKeys()
        );
    }

    public static void onServerStopping() {
        OPEN_VIEWPORTS.clear();
    }

    static void unregisterViewport(UUID playerId, ScreenKind screenKind, String ownerId) {
        if (playerId == null) {
            return;
        }
        String key = logicalViewportKey(playerId, screenKind, ownerId == null ? "" : ownerId.trim());
        OPEN_VIEWPORTS.remove(key);
        ClaimPreviewTerrainService.unregisterViewport(key);
    }

    private static OpenViewportState recordViewportRequest(String logicalScreenKey,
                                                           UUID playerId,
                                                           RequestClaimMapViewportPacket packet,
                                                           String resolvedDimensionId) {
        if (logicalScreenKey == null || logicalScreenKey.isBlank() || packet == null) {
            return null;
        }
        return OPEN_VIEWPORTS.compute(logicalScreenKey, (key, existing) -> {
            if (!shouldReplace(existing, playerId, packet, resolvedDimensionId)) {
                return existing;
            }
            return new OpenViewportState(
                    logicalScreenKey,
                    playerId,
                    packet.screenKind(),
                    packet.ownerId(),
                    resolvedDimensionId,
                    packet.revision(),
                    packet.radius(),
                    packet.centerChunkX(),
                    packet.centerChunkZ(),
                    packet.prefetchRadius(),
                    true
            );
        });
    }

    private static boolean shouldReplace(OpenViewportState existing,
                                         UUID playerId,
                                         RequestClaimMapViewportPacket packet,
                                         String resolvedDimensionId) {
        if (existing == null) {
            return true;
        }
        if (packet.revision() > existing.revision()) {
            return true;
        }
        if (packet.revision() < existing.revision()) {
            return false;
        }
        if (!existing.playerId().equals(playerId)) {
            return true;
        }
        return existing.radius() != packet.radius()
                || existing.centerChunkX() != packet.centerChunkX()
                || existing.centerChunkZ() != packet.centerChunkZ()
                || existing.prefetchRadius() != packet.prefetchRadius()
                || !existing.dimensionId().equals(resolvedDimensionId);
    }

    private static void sendSnapshot(ServerPlayer player, ScreenKind screenKind, String ownerId, ClaimMapViewportSnapshot snapshot) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncClaimPreviewMapPacket(toSyncScreenKind(screenKind), ownerId, snapshot)
        );
    }

    private static SyncClaimPreviewMapPacket.ScreenKind toSyncScreenKind(ScreenKind screenKind) {
        return screenKind == ScreenKind.NATION
                ? SyncClaimPreviewMapPacket.ScreenKind.NATION
                : SyncClaimPreviewMapPacket.ScreenKind.TOWN;
    }

    static int clampRadiusForServer(int requestedRadius) {
        int configuredMax;
        try {
            configuredMax = Math.max(0, com.monpai.sailboatmod.ModConfig.claimPreviewRadius());
        } catch (IllegalStateException ignored) {
            configuredMax = DEFAULT_SERVER_RADIUS_LIMIT;
        }
        return Math.max(0, Math.min(requestedRadius, configuredMax));
    }

    static int clampPrefetchRadiusForServer(int requestedPrefetchRadius) {
        return Math.max(0, Math.min(requestedPrefetchRadius, MAX_SERVER_PREFETCH_RADIUS));
    }

    static ClaimMapViewportSnapshot tryBuildCompleteSnapshot(RequestClaimMapViewportPacket packet,
                                                             String resolvedDimensionId,
                                                             ClaimMapViewportService.TileLookup tileLookup,
                                                             int radius,
                                                             int prefetchRadius) {
        return tryBuildCompleteSnapshot(
                packet,
                resolvedDimensionId,
                tileLookup,
                radius,
                prefetchRadius,
                viewportScreenKey(packet)
        );
    }

    static ClaimMapViewportSnapshot tryBuildCompleteSnapshot(RequestClaimMapViewportPacket packet,
                                                             String resolvedDimensionId,
                                                             ClaimMapViewportService.TileLookup tileLookup,
                                                             int radius,
                                                             int prefetchRadius,
                                                             String screenKey) {
        if (packet == null || tileLookup == null || resolvedDimensionId == null || resolvedDimensionId.isBlank()) {
            return null;
        }
        ClaimMapViewportService service = new ClaimMapViewportService(tileLookup);
        return service.tryBuildSnapshot(
                new ClaimMapViewportService.ViewportRequest(
                        screenKey == null ? "" : screenKey,
                        resolvedDimensionId,
                        packet.revision(),
                        radius,
                        packet.centerChunkX(),
                        packet.centerChunkZ(),
                        prefetchRadius
                ),
                List.of()
        );
    }

    static ServerLevel resolveLevel(ServerPlayer player, String dimensionId) {
        if (player == null || player.getServer() == null) {
            return null;
        }
        ResourceLocation resourceLocation = ResourceLocation.tryParse(dimensionId);
        if (resourceLocation != null) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, resourceLocation);
            ServerLevel resolved = player.getServer().getLevel(key);
            if (resolved != null) {
                return resolved;
            }
        }
        return player.serverLevel();
    }

    private static ClaimMapViewportSnapshot buildSnapshotFromCache(OpenViewportState state) {
        if (state == null) {
            return null;
        }
        RequestClaimMapViewportPacket packet = new RequestClaimMapViewportPacket(
                state.screenKind(),
                state.ownerId(),
                state.dimensionId(),
                state.revision(),
                state.radius(),
                state.centerChunkX(),
                state.centerChunkZ(),
                state.prefetchRadius()
        );
        return tryBuildCompleteSnapshot(
                packet,
                state.dimensionId(),
                ClaimPreviewTerrainService::getCachedTileForViewport,
                state.radius(),
                state.prefetchRadius(),
                state.logicalScreenKey()
        );
    }

    private static void scheduleViewportTask(ClaimMapTaskService taskService,
                                             OpenViewportState state,
                                             Function<OpenViewportState, ClaimMapViewportSnapshot> snapshotBuilder,
                                             BiConsumer<OpenViewportState, ClaimMapViewportSnapshot> sender) {
        if (taskService == null || state == null || snapshotBuilder == null || sender == null || !state.dirty()) {
            return;
        }
        String logicalKey = state.logicalScreenKey();
        long expectedRevision = state.revision();
        taskService.submitLatest(
                new ClaimMapTaskService.TaskKey(VIEWPORT_TASK_KIND, logicalKey),
                () -> {
                    OpenViewportState latest = OPEN_VIEWPORTS.get(logicalKey);
                    if (latest == null || !latest.dirty() || latest.revision() != expectedRevision) {
                        return null;
                    }
                    return snapshotBuilder.apply(latest);
                },
                snapshot -> {
                    if (snapshot == null) {
                        return;
                    }
                    OpenViewportState latest = OPEN_VIEWPORTS.get(logicalKey);
                    if (latest == null || !latest.dirty() || latest.revision() != expectedRevision) {
                        return;
                    }
                    sender.accept(latest, snapshot);
                    latest.markClean();
                }
        );
    }

    private static int runViewportCompletionCycle(ClaimMapTaskService taskService,
                                                  Function<OpenViewportState, ClaimMapViewportSnapshot> snapshotBuilder,
                                                  BiConsumer<OpenViewportState, ClaimMapViewportSnapshot> sender,
                                                  Collection<String> invalidatedScreenKeys) {
        if (taskService == null || snapshotBuilder == null || sender == null) {
            return 0;
        }
        if (invalidatedScreenKeys != null) {
            for (String key : invalidatedScreenKeys) {
                OpenViewportState state = OPEN_VIEWPORTS.get(key);
                if (state != null) {
                    state.markDirty();
                }
            }
        }
        AtomicInteger scheduled = new AtomicInteger();
        for (OpenViewportState state : OPEN_VIEWPORTS.values()) {
            if (state == null || !state.dirty()) {
                continue;
            }
            scheduleViewportTask(taskService, state, snapshotBuilder, (latestState, snapshot) -> {
                sender.accept(latestState, snapshot);
                scheduled.incrementAndGet();
            });
        }
        return scheduled.get();
    }

    static int flushOpenViewportRequestsForTest(ClaimMapTaskService taskService,
                                                Function<OpenViewportState, ClaimMapViewportSnapshot> snapshotBuilder,
                                                BiConsumer<OpenViewportState, ClaimMapViewportSnapshot> sender,
                                                Collection<String> invalidatedScreenKeys) {
        return runViewportCompletionCycle(taskService, snapshotBuilder, sender, invalidatedScreenKeys);
    }

    static String logicalViewportKeyForTest(UUID playerId, ScreenKind screenKind, String ownerId) {
        return logicalViewportKey(playerId, screenKind, ownerId);
    }

    static void recordViewportRequestForTest(String logicalScreenKey,
                                             UUID playerId,
                                             RequestClaimMapViewportPacket packet,
                                             String resolvedDimensionId) {
        recordViewportRequest(logicalScreenKey, playerId, packet, resolvedDimensionId);
    }

    static void clearOpenViewportRequestsForTest() {
        OPEN_VIEWPORTS.clear();
    }

    static boolean hasOpenViewportForTest(String logicalScreenKey) {
        return logicalScreenKey != null && OPEN_VIEWPORTS.containsKey(logicalScreenKey);
    }

    private static String logicalViewportKey(UUID playerId, ScreenKind screenKind, String ownerId) {
        String playerKey = playerId == null ? "" : playerId.toString();
        String ownerKey = ownerId == null ? "" : ownerId;
        String kind = screenKind == null ? ScreenKind.TOWN.name() : screenKind.name();
        return playerKey + "|" + kind + "|" + ownerKey;
    }

    private static String viewportScreenKey(RequestClaimMapViewportPacket packet) {
        if (packet == null) {
            return "";
        }
        return packet.screenKind().name() + "|" + packet.ownerId();
    }
}
