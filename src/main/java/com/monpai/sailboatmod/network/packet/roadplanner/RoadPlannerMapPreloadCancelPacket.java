package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerMapPreloadCancelPacket(UUID sessionId,
                                                long requestId,
                                                RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
    public RoadPlannerMapPreloadCancelPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        purpose = purpose == null ? RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD : purpose;
    }

    public static void encode(RoadPlannerMapPreloadCancelPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        buffer.writeVarLong(packet.requestId());
        buffer.writeEnum(packet.purpose());
    }

    public static RoadPlannerMapPreloadCancelPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerMapPreloadCancelPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readVarLong(),
                buffer.readEnum(RoadPlannerMapPreloadRequestPacket.Purpose.class));
    }

    public static void handle(RoadPlannerMapPreloadCancelPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            RoadPlannerMapPreloadService.global().cancel(player, packet);
        });
        context.setPacketHandled(true);
    }
}
