package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.nation.service.RoadPlannerRoadDemolitionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RoadPlannerSelectDemolitionRoadPacket(String roadId) {
    public RoadPlannerSelectDemolitionRoadPacket {
        roadId = roadId == null ? "" : roadId.trim();
    }

    public static void encode(RoadPlannerSelectDemolitionRoadPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeString(buffer, packet.roadId(), 128);
    }

    public static RoadPlannerSelectDemolitionRoadPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerSelectDemolitionRoadPacket(buffer.readUtf(128));
    }

    public static void handle(RoadPlannerSelectDemolitionRoadPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            RoadPlannerRoadDemolitionService.Result result = RoadPlannerRoadDemolitionService.demolishSelectedRoad(sender, packet.roadId());
            sender.sendSystemMessage(result.message());
        });
        context.setPacketHandled(true);
    }
}
