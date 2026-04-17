package com.monpai.sailboatmod.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RefreshClaimMapViewportPacket(RequestClaimMapViewportPacket.ScreenKind screenKind,
                                            String ownerId,
                                            String dimensionId,
                                            long revision,
                                            int radius,
                                            int centerChunkX,
                                            int centerChunkZ) {
    public RefreshClaimMapViewportPacket {
        screenKind = screenKind == null ? RequestClaimMapViewportPacket.ScreenKind.TOWN : screenKind;
        ownerId = ownerId == null ? "" : ownerId.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        radius = Math.max(0, radius);
    }

    public static void encode(RefreshClaimMapViewportPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.screenKind());
        PacketStringCodec.writeUtfSafe(buffer, packet.ownerId(), 64);
        PacketStringCodec.writeUtfSafe(buffer, packet.dimensionId(), 64);
        buffer.writeLong(packet.revision());
        buffer.writeVarInt(packet.radius());
        buffer.writeInt(packet.centerChunkX());
        buffer.writeInt(packet.centerChunkZ());
    }

    public static RefreshClaimMapViewportPacket decode(FriendlyByteBuf buffer) {
        return new RefreshClaimMapViewportPacket(
                buffer.readEnum(RequestClaimMapViewportPacket.ScreenKind.class),
                buffer.readUtf(64),
                buffer.readUtf(64),
                buffer.readLong(),
                buffer.readVarInt(),
                buffer.readInt(),
                buffer.readInt()
        );
    }

    public static void handle(RefreshClaimMapViewportPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            RequestClaimMapViewportPacket.dispatch(
                    player,
                    new RequestClaimMapViewportPacket(
                            packet.screenKind(),
                            packet.ownerId(),
                            packet.dimensionId(),
                            packet.revision(),
                            packet.radius(),
                            packet.centerChunkX(),
                            packet.centerChunkZ(),
                            0
                    )
            );
        });
        context.setPacketHandled(true);
    }
}
