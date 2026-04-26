package com.monpai.sailboatmod.network.packet.roadplanner;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerRenameRoadPacket(UUID routeId, UUID edgeId, String roadName) {
    public RoadPlannerRenameRoadPacket {
        routeId = routeId == null ? new UUID(0L, 0L) : routeId;
        edgeId = edgeId == null ? new UUID(0L, 0L) : edgeId;
        roadName = roadName == null ? "" : roadName.trim();
    }

    public static void encode(RoadPlannerRenameRoadPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.routeId());
        RoadPlannerPacketCodec.writeUuid(buffer, packet.edgeId());
        RoadPlannerPacketCodec.writeString(buffer, packet.roadName(), 64);
    }

    public static RoadPlannerRenameRoadPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerRenameRoadPacket(RoadPlannerPacketCodec.readUuid(buffer), RoadPlannerPacketCodec.readUuid(buffer), buffer.readUtf(64));
    }

    public static void handle(RoadPlannerRenameRoadPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }
}
