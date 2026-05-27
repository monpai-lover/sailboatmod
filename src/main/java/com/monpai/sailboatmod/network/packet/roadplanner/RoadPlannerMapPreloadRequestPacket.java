package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerMapPreloadRequestPacket(UUID sessionId,
                                                 long requestId,
                                                 Purpose purpose,
                                                 String worldId,
                                                 String dimensionId,
                                                 BlockPos start,
                                                 BlockPos destination,
                                                 List<BlockPos> routeNodes,
                                                 int protocolVersion) {
    public static final int PROTOCOL_VERSION = 1;

    public RoadPlannerMapPreloadRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        purpose = purpose == null ? Purpose.ROUTE_PRELOAD : purpose;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        start = start == null ? BlockPos.ZERO : start.immutable();
        destination = destination == null ? BlockPos.ZERO : destination.immutable();
        routeNodes = routeNodes == null ? List.of() : routeNodes.stream().filter(Objects::nonNull).map(BlockPos::immutable).toList();
        protocolVersion = protocolVersion <= 0 ? PROTOCOL_VERSION : protocolVersion;
    }

    public static void encode(RoadPlannerMapPreloadRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        buffer.writeVarLong(packet.requestId());
        buffer.writeEnum(packet.purpose());
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        buffer.writeBlockPos(packet.start());
        buffer.writeBlockPos(packet.destination());
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.routeNodes());
        buffer.writeVarInt(packet.protocolVersion());
    }

    public static RoadPlannerMapPreloadRequestPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerMapPreloadRequestPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readVarLong(),
                buffer.readEnum(Purpose.class),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readBlockPos(),
                buffer.readBlockPos(),
                RoadPlannerPacketCodec.readBlockPosList(buffer),
                buffer.readVarInt());
    }

    public static void handle(RoadPlannerMapPreloadRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            RoadPlannerMapPreloadService.global().enqueue(player, packet);
        });
        context.setPacketHandled(true);
    }

    public enum Purpose {
        ENTER_PLANNER_PRELOAD,
        ROUTE_PRELOAD,
        FORCE_RENDER,
        BUILT_ROAD_REFRESH
    }
}
