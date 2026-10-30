package com.monpai.sailboatmod.network.packet.maproute;

import com.monpai.sailboatmod.map.route.MapRouteCatalogService;
import com.monpai.sailboatmod.map.route.MapRouteViewport;
import com.monpai.sailboatmod.network.NetworkEvent;
import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;
import java.util.function.Supplier;

public record MapRouteViewportRequestPacket(UUID sessionId, long requestId, String worldId, String dimensionId, MapRouteViewport viewport) {
    public MapRouteViewportRequestPacket {
        MapRoutePacketCodec.validateIdentity(sessionId, requestId, worldId, dimensionId);
        if (viewport == null || !Double.isFinite(viewport.minX()) || !Double.isFinite(viewport.minZ())
                || !Double.isFinite(viewport.maxX()) || !Double.isFinite(viewport.maxZ()) || viewport.maxX() <= viewport.minX()
                || viewport.maxZ() <= viewport.minZ() || !Double.isFinite(viewport.pixelsPerBlock()) || viewport.pixelsPerBlock() <= 0)
            throw new IllegalArgumentException("Invalid map-route viewport");
    }
    public static void encode(MapRouteViewportRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.sessionId); buffer.writeLong(packet.requestId);
        buffer.writeUtf(packet.worldId, MapRoutePacketCodec.MAX_ID_LENGTH); buffer.writeUtf(packet.dimensionId, MapRoutePacketCodec.MAX_ID_LENGTH);
        buffer.writeDouble(packet.viewport.minX()); buffer.writeDouble(packet.viewport.minZ());
        buffer.writeDouble(packet.viewport.maxX()); buffer.writeDouble(packet.viewport.maxZ()); buffer.writeDouble(packet.viewport.pixelsPerBlock());
    }
    public static MapRouteViewportRequestPacket decode(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() > 1024) throw new IllegalArgumentException("Oversized map-route request");
        return new MapRouteViewportRequestPacket(buffer.readUUID(), buffer.readLong(), buffer.readUtf(MapRoutePacketCodec.MAX_ID_LENGTH),
                buffer.readUtf(MapRoutePacketCodec.MAX_ID_LENGTH), new MapRouteViewport(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }
    public static void handle(MapRouteViewportRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> { if (context.getSender() != null) MapRouteCatalogService.global().request(context.getSender(), packet); });
        context.setPacketHandled(true);
    }
}
