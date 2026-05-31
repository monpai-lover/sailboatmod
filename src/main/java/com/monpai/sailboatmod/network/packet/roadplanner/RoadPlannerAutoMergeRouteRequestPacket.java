package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.service.RoadPlannerAutoMergeRouteService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerAutoMergeRouteRequestPacket(UUID sessionId,
                                                     UUID requestId,
                                                     String dimensionId,
                                                     List<BlockPos> routeNodes,
                                                     List<RoadPlannerSegmentType> routeSegments,
                                                     BlockPos destinationPos,
                                                     RoadPlannerMergeScope scope,
                                                     String preferredRoadId,
                                                     int preferredPathIndex) {
    public static final int MAX_ROUTE_NODES = 256;
    public static final int MAX_ROUTE_SEGMENTS = 255;

    public RoadPlannerAutoMergeRouteRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        requestId = requestId == null ? UUID.randomUUID() : requestId;
        dimensionId = dimensionId == null ? "" : dimensionId.trim().toLowerCase(Locale.ROOT);
        routeNodes = routeNodes == null ? List.of() : routeNodes.stream()
                .filter(Objects::nonNull)
                .limit(MAX_ROUTE_NODES)
                .map(BlockPos::immutable)
                .toList();
        routeSegments = routeSegments == null ? List.of() : routeSegments.stream()
                .filter(Objects::nonNull)
                .limit(MAX_ROUTE_SEGMENTS)
                .toList();
        destinationPos = destinationPos == null ? BlockPos.ZERO : destinationPos.immutable();
        scope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
        preferredRoadId = preferredRoadId == null ? "" : preferredRoadId.trim().toLowerCase(Locale.ROOT);
        preferredPathIndex = Math.max(-1, preferredPathIndex);
    }

    public static void encode(RoadPlannerAutoMergeRouteRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeUuid(buffer, packet.requestId());
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.routeNodes());
        buffer.writeVarInt(Math.min(MAX_ROUTE_SEGMENTS, packet.routeSegments().size()));
        for (RoadPlannerSegmentType segment : packet.routeSegments().stream().limit(MAX_ROUTE_SEGMENTS).toList()) {
            buffer.writeEnum(segment);
        }
        buffer.writeBlockPos(packet.destinationPos());
        buffer.writeEnum(packet.scope());
        RoadPlannerPacketCodec.writeString(buffer, packet.preferredRoadId(), 128);
        buffer.writeVarInt(packet.preferredPathIndex());
    }

    public static RoadPlannerAutoMergeRouteRequestPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        UUID requestId = RoadPlannerPacketCodec.readUuid(buffer);
        String dimensionId = buffer.readUtf(128);
        List<BlockPos> routeNodes = readCappedPositions(buffer);
        int segmentCount = buffer.readVarInt();
        if (segmentCount < 0 || segmentCount > MAX_ROUTE_SEGMENTS) {
            throw new IllegalArgumentException("Auto merge route segment count out of bounds: " + segmentCount);
        }
        java.util.ArrayList<RoadPlannerSegmentType> routeSegments = new java.util.ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            routeSegments.add(buffer.readEnum(RoadPlannerSegmentType.class));
        }
        return new RoadPlannerAutoMergeRouteRequestPacket(
                sessionId,
                requestId,
                dimensionId,
                routeNodes,
                routeSegments,
                buffer.readBlockPos(),
                buffer.readEnum(RoadPlannerMergeScope.class),
                buffer.readUtf(128),
                buffer.readVarInt());
    }

    public static void handle(RoadPlannerAutoMergeRouteRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> handleOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void handleOnServer(RoadPlannerAutoMergeRouteRequestPacket packet, ServerPlayer sender) {
        if (sender == null || !(sender.level() instanceof ServerLevel level)) {
            return;
        }
        String dimensionId = level.dimension().location().toString();
        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        RoadGraphRepository.forLevel(level),
                        dimensionId,
                        packet.routeNodes(),
                        packet.routeSegments(),
                        packet.destinationPos(),
                        packet.scope(),
                        new RoadPlannerAutoMergeRouteService.PreferredEntry(packet.preferredRoadId(), packet.preferredPathIndex()),
                        RoadPlannerAutoMergeRouteService.DEFAULT_ENTRY_RADIUS,
                        RoadPlannerAutoMergeRouteService.DEFAULT_DESTINATION_RADIUS));
        ModNetwork.CHANNEL.sendTo(RoadPlannerAutoMergeRouteSyncPacket.fromResult(packet.sessionId(), packet.requestId(), result),
                sender.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT);
    }

    private static List<BlockPos> readCappedPositions(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ROUTE_NODES) {
            throw new IllegalArgumentException("Auto merge route node count out of bounds: " + count);
        }
        java.util.ArrayList<BlockPos> positions = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            positions.add(buffer.readBlockPos());
        }
        return List.copyOf(positions);
    }
}
