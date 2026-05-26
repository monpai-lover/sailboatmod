package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerRoadOverlayRequestPacket(UUID sessionId,
                                                  String worldId,
                                                  String dimensionId,
                                                  BlockPos regionCenter,
                                                  int regionSize,
                                                  RoadPlannerMergeScope scope) {
    public RoadPlannerRoadOverlayRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        regionCenter = regionCenter == null ? BlockPos.ZERO : regionCenter.immutable();
        regionSize = normalizeRegionSize(regionSize);
        scope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
    }

    public static void encode(RoadPlannerRoadOverlayRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        buffer.writeBlockPos(packet.regionCenter());
        buffer.writeVarInt(packet.regionSize());
        buffer.writeEnum(packet.scope());
    }

    public static RoadPlannerRoadOverlayRequestPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerRoadOverlayRequestPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readBlockPos(),
                buffer.readVarInt(),
                buffer.readEnum(RoadPlannerMergeScope.class));
    }

    public static void handle(RoadPlannerRoadOverlayRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> handleOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void handleOnServer(RoadPlannerRoadOverlayRequestPacket packet, ServerPlayer sender) {
        if (sender == null) {
            return;
        }
        List<RoadPlannerRoadOverlaySyncPacket.Entry> entries = RoadPlannerRoadMergeService.visibleRoadOverlays(
                        sender,
                        packet.dimensionId(),
                        packet.regionCenter(),
                        packet.regionSize(),
                        packet.scope())
                .stream()
                .map(overlay -> new RoadPlannerRoadOverlaySyncPacket.Entry(
                        overlay.roadId(),
                        overlay.relationship(),
                        overlay.path()))
                .toList();
        ModNetwork.CHANNEL.sendTo(new RoadPlannerRoadOverlaySyncPacket(packet.sessionId(), entries),
                sender.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT);
    }

    public static int normalizeRegionSize(int requestedSize) {
        return Math.max(1, requestedSize);
    }
}
