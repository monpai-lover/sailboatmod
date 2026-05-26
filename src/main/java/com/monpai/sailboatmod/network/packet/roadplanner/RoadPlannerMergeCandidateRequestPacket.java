package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
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

public record RoadPlannerMergeCandidateRequestPacket(UUID sessionId,
                                                     BlockPos probe,
                                                     int radius,
                                                     RoadPlannerMergeScope scope,
                                                     RoadPlannerSegmentType currentSegmentType) {
    public RoadPlannerMergeCandidateRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        probe = probe == null ? BlockPos.ZERO : probe.immutable();
        radius = Math.max(0, radius);
        scope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
        currentSegmentType = currentSegmentType == null ? RoadPlannerSegmentType.ROAD : currentSegmentType;
    }

    public static void encode(RoadPlannerMergeCandidateRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        buffer.writeBlockPos(packet.probe());
        buffer.writeVarInt(packet.radius());
        buffer.writeEnum(packet.scope());
        buffer.writeEnum(packet.currentSegmentType());
    }

    public static RoadPlannerMergeCandidateRequestPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerMergeCandidateRequestPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readBlockPos(),
                buffer.readVarInt(),
                buffer.readEnum(RoadPlannerMergeScope.class),
                buffer.readEnum(RoadPlannerSegmentType.class));
    }

    public static void handle(RoadPlannerMergeCandidateRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> handleOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void handleOnServer(RoadPlannerMergeCandidateRequestPacket packet, ServerPlayer sender) {
        if (sender == null) {
            return;
        }
        List<OpenRoadMergeCandidatesPacket.Entry> entries = RoadPlannerRoadMergeService.findCandidates(
                        sender,
                        packet.probe(),
                        packet.radius(),
                        packet.scope(),
                        packet.currentSegmentType())
                .stream()
                .map(candidate -> new OpenRoadMergeCandidatesPacket.Entry(
                        candidate.roadId(),
                        candidate.anchorPos(),
                        candidate.pathIndex(),
                        candidate.distanceBlocks(),
                        candidate.sourceName(),
                        candidate.targetName(),
                        candidate.ownerNationId(),
                        candidate.relationship()))
                .toList();
        ModNetwork.CHANNEL.sendTo(new OpenRoadMergeCandidatesPacket(packet.sessionId(), entries),
                sender.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT);
    }
}
