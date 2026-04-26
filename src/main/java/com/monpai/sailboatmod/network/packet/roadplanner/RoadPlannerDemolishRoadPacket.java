package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.build.RoadRollbackLedger;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerEditorService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerDemolishRoadPacket(UUID routeId, UUID edgeId, Scope scope) {
    public RoadPlannerDemolishRoadPacket {
        routeId = routeId == null ? new UUID(0L, 0L) : routeId;
        edgeId = edgeId == null ? new UUID(0L, 0L) : edgeId;
        scope = scope == null ? Scope.EDGE : scope;
    }

    public static void encode(RoadPlannerDemolishRoadPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.routeId());
        RoadPlannerPacketCodec.writeUuid(buffer, packet.edgeId());
        buffer.writeEnum(packet.scope());
    }

    public static RoadPlannerDemolishRoadPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerDemolishRoadPacket(RoadPlannerPacketCodec.readUuid(buffer), RoadPlannerPacketCodec.readUuid(buffer), buffer.readEnum(Scope.class));
    }

    public static void handle(RoadPlannerDemolishRoadPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            RoadPlannerEditorService.DemolishResult result = RoadPlannerEditorService.global()
                    .planDemolition(packet.routeId(), packet.edgeId(), packet.scope(), new RoadRollbackLedger(), Map.of());
            if (sender != null && result.job().isEmpty()) {
                sender.sendSystemMessage(Component.literal(result.issues().isEmpty() ? "道路拆除失败" : result.issues().get(0).message()));
            }
        });
        context.setPacketHandled(true);
    }

    public enum Scope {
        EDGE,
        BRANCH
    }
}
