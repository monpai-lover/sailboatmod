package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.monpai.sailboatmod.network.NetworkEvent;

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

    public enum Cancelled { PREVIEW, BUILD, LOADING, NOTHING }

    /** A shown preview first, then a running build (as before loading existed), and only then a preview still loading. */
    public static Cancelled cancelInOrder(java.util.function.BooleanSupplier preview,
                                          java.util.function.BooleanSupplier build,
                                          java.util.function.BooleanSupplier loading) {
        if (preview.getAsBoolean()) {
            return Cancelled.PREVIEW;
        }
        if (build.getAsBoolean()) {
            return Cancelled.BUILD;
        }
        return loading.getAsBoolean() ? Cancelled.LOADING : Cancelled.NOTHING;
    }

    public static void handle(RoadPlannerCancelJobPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            RoadPlannerBuildControlService service = RoadPlannerBuildControlService.global();
            Cancelled cancelled = cancelInOrder(
                    () -> service.cancelPreview(sender.getUUID(), packet.sessionId()),
                    () -> service.cancelBuild(sender.getUUID(), packet.sessionId(), sender.serverLevel()),
                    () -> com.monpai.sailboatmod.roadplanner.service.RoadPlannerPreviewJobService.global().cancel(sender.getUUID()));
            boolean cancelledPreview = cancelled == Cancelled.PREVIEW || cancelled == Cancelled.LOADING;
            boolean cancelledBuild = cancelled == Cancelled.BUILD;
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
