package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ClaimMapTaskService;
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

import java.util.function.Supplier;

public record RequestClaimMapViewportPacket(ScreenKind screenKind,
                                            String ownerId,
                                            String dimensionId,
                                            long revision,
                                            int radius,
                                            int centerChunkX,
                                            int centerChunkZ,
                                            int prefetchRadius) {
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

        ChunkPos center = new ChunkPos(packet.centerChunkX(), packet.centerChunkZ());
        int radius = Math.max(0, packet.radius());
        ClaimPreviewTerrainService.queueAround(level, center, radius + Math.max(0, packet.prefetchRadius()));

        String resolvedDimensionId = level.dimension().location().toString();
        ClaimMapTaskService taskService = ClaimMapTaskService.get();
        if (taskService != null && player.getServer() != null) {
            taskService.submitLatest(
                    new ClaimMapTaskService.TaskKey(taskKind(packet.screenKind()), player.getUUID() + "|" + packet.ownerId()),
                    () -> new ClaimMapViewportSnapshot(
                            resolvedDimensionId,
                            packet.revision(),
                            radius,
                            packet.centerChunkX(),
                            packet.centerChunkZ(),
                            ClaimPreviewTerrainService.sample(level, center, radius)
                    ),
                    snapshot -> sendSnapshot(player, packet.screenKind(), snapshot)
            );
            return;
        }

        sendSnapshot(
                player,
                packet.screenKind(),
                new ClaimMapViewportSnapshot(
                        resolvedDimensionId,
                        packet.revision(),
                        radius,
                        packet.centerChunkX(),
                        packet.centerChunkZ(),
                        ClaimPreviewTerrainService.sample(level, center, radius)
                )
        );
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

    private static ServerLevel resolveLevel(ServerPlayer player, String dimensionId) {
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
}
