package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.data.TerrainPreviewSavedData;
import com.monpai.sailboatmod.nation.service.ClaimMapTaskService;
import com.monpai.sailboatmod.nation.service.ClaimMapViewportService;
import com.monpai.sailboatmod.nation.service.ClaimMapViewportSnapshot;
import com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
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

    public enum ScreenKind {
        TOWN,
        NATION
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
        ChunkPos center = new ChunkPos(normalized.centerChunkX(), normalized.centerChunkZ());
        ClaimPreviewTerrainService.queueAround(level, center, radius + prefetchRadius);

        String resolvedDimensionId = level.dimension().location().toString();
        ClaimMapTaskService taskService = ClaimMapTaskService.get();
        if (taskService != null && player.getServer() != null) {
            taskService.submitLatest(
                    new ClaimMapTaskService.TaskKey(taskKind(normalized.screenKind()), player.getUUID() + "|" + normalized.ownerId()),
                    () -> buildCompleteSnapshot(level, normalized, resolvedDimensionId, radius, prefetchRadius),
                    snapshot -> {
                        if (snapshot != null) {
                            sendSnapshot(player, normalized.screenKind(), snapshot);
                        }
                    }
            );
            return;
        }

        ClaimMapViewportSnapshot snapshot = buildCompleteSnapshot(level, normalized, resolvedDimensionId, radius, prefetchRadius);
        if (snapshot != null) {
            sendSnapshot(player, normalized.screenKind(), snapshot);
        }
    }

    private static void sendSnapshot(ServerPlayer player, ScreenKind screenKind, ClaimMapViewportSnapshot snapshot) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncClaimPreviewMapPacket(toSyncScreenKind(screenKind), snapshot)
        );
    }

    private static SyncClaimPreviewMapPacket.ScreenKind toSyncScreenKind(ScreenKind screenKind) {
        return screenKind == ScreenKind.NATION
                ? SyncClaimPreviewMapPacket.ScreenKind.NATION
                : SyncClaimPreviewMapPacket.ScreenKind.TOWN;
    }

    private static String taskKind(ScreenKind screenKind) {
        return screenKind == ScreenKind.NATION ? "nation-viewport" : "town-viewport";
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
        if (packet == null || tileLookup == null || resolvedDimensionId == null || resolvedDimensionId.isBlank()) {
            return null;
        }
        ClaimMapViewportService service = new ClaimMapViewportService(tileLookup);
        return service.tryBuildSnapshot(
                new ClaimMapViewportService.ViewportRequest(
                        packet.screenKind().name(),
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

    private static ClaimMapViewportSnapshot buildCompleteSnapshot(ServerLevel level,
                                                                  RequestClaimMapViewportPacket packet,
                                                                  String resolvedDimensionId,
                                                                  int radius,
                                                                  int prefetchRadius) {
        if (level == null || packet == null) {
            return null;
        }
        ChunkPos center = new ChunkPos(packet.centerChunkX(), packet.centerChunkZ());
        ClaimPreviewTerrainService.sample(level, center, radius);
        TerrainPreviewSavedData savedData = TerrainPreviewSavedData.get(level);
        return tryBuildCompleteSnapshot(packet, resolvedDimensionId, savedData::getTile, radius, prefetchRadius);
    }
}
