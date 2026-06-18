package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** 暂停/继续道路施工(切换)。修复:旧「暂停/继续施工」按钮只关菜单不暂停。 */
public record RoadPlannerPauseBuildPacket(UUID sessionId) {
    public RoadPlannerPauseBuildPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
    }

    public static void encode(RoadPlannerPauseBuildPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
    }

    public static RoadPlannerPauseBuildPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerPauseBuildPacket(RoadPlannerPacketCodec.readUuid(buffer));
    }

    public static void handle(RoadPlannerPauseBuildPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            RoadPlannerBuildControlService service = RoadPlannerBuildControlService.global();
            if (service.buildFor(sender.getUUID()).isEmpty()) {
                sender.sendSystemMessage(Component.literal("没有正在进行的施工任务可暂停。"));
                return;
            }
            boolean nowPaused = service.togglePauseBuild(sender.getUUID(), packet.sessionId());
            sender.sendSystemMessage(Component.literal(nowPaused ? "施工已暂停。" : "施工已继续。"));
        });
        context.setPacketHandled(true);
    }
}
