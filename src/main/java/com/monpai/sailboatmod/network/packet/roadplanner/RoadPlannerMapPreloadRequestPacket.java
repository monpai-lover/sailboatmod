package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import com.monpai.sailboatmod.network.NetworkEvent;

import java.util.List;
import java.util.ArrayList;
import com.monpai.sailboatmod.roadplanner.service.MapRequestLimiter;
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
    public static final int MAX_ROUTE_NODES = 1024;
    public static final int MAX_VIEWPORT_TILES = 64;
    public static final int MAX_SELECTION_CHUNKS = 4096;
    private static final int WORLD_LIMIT = 30_000_000;

    public RoadPlannerMapPreloadRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        purpose = purpose == null ? Purpose.ROUTE_PRELOAD : purpose;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        start = start == null ? BlockPos.ZERO : start.immutable();
        destination = destination == null ? BlockPos.ZERO : destination.immutable();
        if (routeNodes != null && routeNodes.size() > MAX_ROUTE_NODES) {
            throw new IllegalArgumentException("Too many preload route nodes");
        }
        routeNodes = routeNodes == null ? List.of() : routeNodes.stream().filter(Objects::nonNull).map(BlockPos::immutable).toList();

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
                readBoundedNodes(buffer),
                buffer.readVarInt());
    }

    public static void handle(RoadPlannerMapPreloadRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        // This handler is registered on the network thread. Admission precedes scheduling main-thread work.
        if (player != null && packet != null && packet.validClientRequest()
                && MapRequestLimiter.preload(player.getUUID(), System.nanoTime())) {
            context.enqueueWork(() -> RoadPlannerMapPreloadService.global().enqueue(player, packet));
        }
        context.setPacketHandled(true);
    }

    private static List<BlockPos> readBoundedNodes(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ROUTE_NODES || count > buffer.readableBytes() / Long.BYTES) {
            throw new IllegalArgumentException("Invalid preload node count");
        }
        List<BlockPos> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(buffer.readBlockPos());
        return result;
    }

    /** Pure checks; use long arithmetic before any plan or tile enumeration is constructed. */
    public boolean validClientRequest() {
        if (protocolVersion != PROTOCOL_VERSION || requestId < 0
                || purpose == Purpose.BUILT_ROAD_REFRESH
                || worldId.isBlank() || worldId.length() > 128 || dimensionId.isBlank() || dimensionId.length() > 128
                || routeNodes.size() > MAX_ROUTE_NODES || !validPosition(start) || !validPosition(destination)) return false;
        for (BlockPos node : routeNodes) if (!validPosition(node)) return false;
        if (purpose == Purpose.VIEWPORT) return rectangleCount(start, destination, 256) <= MAX_VIEWPORT_TILES;
        if (purpose == Purpose.FORCE_RENDER) return rectangleCount(start, destination, 16) <= MAX_SELECTION_CHUNKS;
        return true;
    }

    static long rectangleCount(BlockPos a, BlockPos b, int stride) {
        long x = Math.abs((long) Math.floorDiv(a.getX(), stride) - Math.floorDiv(b.getX(), stride)) + 1;
        long z = Math.abs((long) Math.floorDiv(a.getZ(), stride) - Math.floorDiv(b.getZ(), stride)) + 1;
        return x * z;
    }

    private static boolean validPosition(BlockPos pos) {
        return pos != null && Math.abs((long) pos.getX()) <= WORLD_LIMIT && Math.abs((long) pos.getZ()) <= WORLD_LIMIT;
    }

    public enum Purpose {
        ENTER_PLANNER_PRELOAD,
        ROUTE_PRELOAD,
        FORCE_RENDER,
        BUILT_ROAD_REFRESH,
        VIEWPORT
    }
}
