package com.monpai.sailboatmod.network.packet.roadplanner;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerRegionNavigationPacket(UUID sessionId, int targetRegionIndex) {
    public RoadPlannerRegionNavigationPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
    }

    public static void encode(RoadPlannerRegionNavigationPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        buffer.writeVarInt(packet.targetRegionIndex());
    }

    public static RoadPlannerRegionNavigationPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerRegionNavigationPacket(RoadPlannerPacketCodec.readUuid(buffer), buffer.readVarInt());
    }

    public static void handle(RoadPlannerRegionNavigationPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }
}
