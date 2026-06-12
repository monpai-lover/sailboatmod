package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.roadplanner.edit.RoadPlannerRoadEditSelectionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RoadPlannerSelectEditRoadPacket(String roadId) {
    public RoadPlannerSelectEditRoadPacket {
        roadId = roadId == null ? "" : roadId.trim();
    }

    public static void encode(RoadPlannerSelectEditRoadPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeString(buffer, packet.roadId(), 128);
    }

    public static RoadPlannerSelectEditRoadPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerSelectEditRoadPacket(buffer.readUtf(128));
    }

    public static void handle(RoadPlannerSelectEditRoadPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            RoadPlannerRoadEditSelectionService.Result result =
                    RoadPlannerRoadEditSelectionService.prepareSelectedRoadForEditing(sender, packet.roadId());
            sender.sendSystemMessage(result.message());
            result.openPacket().ifPresent(openPacket ->
                    ModNetwork.CHANNEL.sendTo(openPacket, sender.connection.connection, NetworkDirection.PLAY_TO_CLIENT));
        });
        context.setPacketHandled(true);
    }
}
