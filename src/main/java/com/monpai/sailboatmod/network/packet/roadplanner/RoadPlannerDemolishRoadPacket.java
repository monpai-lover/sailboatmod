package com.monpai.sailboatmod.network.packet.roadplanner;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerDemolishRoadPacket(UUID routeId, UUID edgeId, Scope scope) {
    public RoadPlannerDemolishRoadPacket {
        routeId = routeId == null ? new UUID(0L, 0L) : routeId;
        edgeId = edgeId == null ? new UUID(0L, 0L) : edgeId;
        scope = scope == null ? Scope.EDGE : scope;
    }

    public static void encode(RoadPlannerDemolishRoadPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.routeId());
        RoadPlannerPacketCodec.writeUuid(buffer, packet.edgeId());
        buffer.writeEnum(packet.scope());
    }

    public static RoadPlannerDemolishRoadPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerDemolishRoadPacket(RoadPlannerPacketCodec.readUuid(buffer), RoadPlannerPacketCodec.readUuid(buffer), buffer.readEnum(Scope.class));
    }

    public static void handle(RoadPlannerDemolishRoadPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }

    public enum Scope {
        EDGE,
        BRANCH
    }
}
