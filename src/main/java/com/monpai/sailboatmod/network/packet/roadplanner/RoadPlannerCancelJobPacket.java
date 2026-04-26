package com.monpai.sailboatmod.network.packet.roadplanner;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerCancelJobPacket(UUID sessionId) {
    public RoadPlannerCancelJobPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
    }

    public static void encode(RoadPlannerCancelJobPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
    }

    public static RoadPlannerCancelJobPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerCancelJobPacket(RoadPlannerPacketCodec.readUuid(buffer));
    }

    public static void handle(RoadPlannerCancelJobPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }
}
