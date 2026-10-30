package com.monpai.sailboatmod.network.packet.maproute;

import com.monpai.sailboatmod.client.map.route.SharedMapRouteLayer;
import com.monpai.sailboatmod.map.route.MapRouteSnapshot;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import java.util.UUID;
import java.util.function.Supplier;

public record MapRouteSnapshotPacket(UUID sessionId, long requestId, String worldId, String dimensionId, MapRouteSnapshot snapshot) {
    public MapRouteSnapshotPacket {
        MapRoutePacketCodec.validateIdentity(sessionId, requestId, worldId, dimensionId);
        MapRoutePacketCodec.validateSnapshot(snapshot);
        if (MapRoutePacketCodec.identityBytes(worldId, dimensionId) + MapRoutePacketCodec.snapshotBytes(snapshot) > MapRoutePacketCodec.MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Oversized map-route snapshot");
    }
    public static void encode(MapRouteSnapshotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.sessionId); buffer.writeLong(packet.requestId);
        buffer.writeUtf(packet.worldId, MapRoutePacketCodec.MAX_ID_LENGTH); buffer.writeUtf(packet.dimensionId, MapRoutePacketCodec.MAX_ID_LENGTH);
        MapRoutePacketCodec.writeSnapshot(packet.snapshot, buffer);
    }
    public static MapRouteSnapshotPacket decode(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MapRoutePacketCodec.MAX_PACKET_BYTES) throw new IllegalArgumentException("Oversized map-route snapshot");
        return new MapRouteSnapshotPacket(buffer.readUUID(), buffer.readLong(), buffer.readUtf(MapRoutePacketCodec.MAX_ID_LENGTH),
                buffer.readUtf(MapRoutePacketCodec.MAX_ID_LENGTH), MapRoutePacketCodec.readSnapshot(buffer));
    }
    public static void handle(MapRouteSnapshotPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SharedMapRouteLayer.accept(packet)));
        context.setPacketHandled(true);
    }
}
