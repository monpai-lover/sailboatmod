package com.monpai.sailboatmod.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record CloseClaimMapViewportPacket(ScreenKind screenKind, String ownerId) {
    public enum ScreenKind {
        TOWN,
        NATION
    }

    public CloseClaimMapViewportPacket {
        screenKind = screenKind == null ? ScreenKind.TOWN : screenKind;
        ownerId = ownerId == null ? "" : ownerId.trim();
    }

    public static void encode(CloseClaimMapViewportPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.screenKind());
        PacketStringCodec.writeUtfSafe(buffer, packet.ownerId(), 64);
    }

    public static CloseClaimMapViewportPacket decode(FriendlyByteBuf buffer) {
        return new CloseClaimMapViewportPacket(
                buffer.readEnum(ScreenKind.class),
                buffer.readUtf(64)
        );
    }

    public static void handle(CloseClaimMapViewportPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            RequestClaimMapViewportPacket.unregisterViewport(
                    player.getUUID(),
                    toRequestScreenKind(packet.screenKind()),
                    packet.ownerId()
            );
        });
        context.setPacketHandled(true);
    }

    private static RequestClaimMapViewportPacket.ScreenKind toRequestScreenKind(ScreenKind screenKind) {
        return screenKind == ScreenKind.NATION
                ? RequestClaimMapViewportPacket.ScreenKind.NATION
                : RequestClaimMapViewportPacket.ScreenKind.TOWN;
    }
}
