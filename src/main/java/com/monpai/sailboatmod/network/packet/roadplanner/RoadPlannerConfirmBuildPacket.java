package com.monpai.sailboatmod.network.packet.roadplanner;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerConfirmBuildPacket(UUID sessionId) {
    public RoadPlannerConfirmBuildPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
    }

    public static void encode(RoadPlannerConfirmBuildPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
    }

    public static RoadPlannerConfirmBuildPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerConfirmBuildPacket(RoadPlannerPacketCodec.readUuid(buffer));
    }

    public static void handle(RoadPlannerConfirmBuildPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }
}
