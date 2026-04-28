package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            RoadPlannerBuildControlService service = RoadPlannerBuildControlService.global();
            boolean cancelledPreview = service.cancelPreview(sender.getUUID(), packet.sessionId());
            boolean cancelledBuild = !cancelledPreview && service.cancelBuild(sender.getUUID(), packet.sessionId(), sender.serverLevel());
            if (cancelledPreview) {
                sender.sendSystemMessage(Component.literal("道路预览已取消。"));
            } else if (cancelledBuild) {
                sender.sendSystemMessage(Component.literal("道路建造已取消，已请求回滚已完成步骤。"));
            } else {
                sender.sendSystemMessage(Component.literal("没有可取消的道路预览或施工任务。"));
            }
        });
        context.setPacketHandled(true);
    }
}
