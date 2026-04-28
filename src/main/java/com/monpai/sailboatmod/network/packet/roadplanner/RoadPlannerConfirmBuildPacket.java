package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            RoadPlannerBuildControlService.global().confirmPreview(sender.getUUID(), packet.sessionId(), sender.serverLevel())
                    .ifPresentOrElse(
                            jobId -> sender.sendSystemMessage(Component.literal("道路建造已加入施工队列，可用道路规划器管理或取消。")),
                            () -> sender.sendSystemMessage(Component.literal("没有可确认的道路预览。"))
                    );
        });
        context.setPacketHandled(true);
    }
}
