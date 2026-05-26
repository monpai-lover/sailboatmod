package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerRoadOverlaySyncPacket(UUID sessionId, List<Entry> roads) {
    private static final int MAX_ROADS = 128;
    private static final int MAX_PATH_POINTS = 128;

    public RoadPlannerRoadOverlaySyncPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        roads = roads == null ? List.of() : roads.stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_ROADS)
                .toList();
    }

    public static void encode(RoadPlannerRoadOverlaySyncPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        List<Entry> roads = packet.roads();
        buffer.writeVarInt(Math.min(MAX_ROADS, roads.size()));
        for (Entry entry : roads) {
            RoadPlannerPacketCodec.writeString(buffer, entry.roadId(), 128);
            buffer.writeEnum(entry.relationship());
            RoadPlannerPacketCodec.writeBlockPosList(buffer, entry.path());
        }
    }

    public static RoadPlannerRoadOverlaySyncPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ROADS) {
            throw new IllegalArgumentException("Road overlay count out of bounds: " + count);
        }
        java.util.ArrayList<Entry> roads = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            roads.add(new Entry(
                    buffer.readUtf(128),
                    buffer.readEnum(RoadPlannerMergeRelationship.class),
                    readCappedBlockPosList(buffer)));
        }
        return new RoadPlannerRoadOverlaySyncPacket(sessionId, roads);
    }

    private static List<BlockPos> readCappedBlockPosList(FriendlyByteBuf buffer) {
        int advertisedCount = Math.max(0, buffer.readVarInt());
        int readCount = Math.min(MAX_PATH_POINTS, advertisedCount);
        List<BlockPos> positions = new ArrayList<>(readCount);
        for (int index = 0; index < readCount; index++) {
            positions.add(buffer.readBlockPos());
        }
        int extra = advertisedCount - readCount;
        if (extra > 0) {
            long bytesToSkip = (long) extra * Long.BYTES;
            if (bytesToSkip > Integer.MAX_VALUE || bytesToSkip > buffer.readableBytes()) {
                throw new IndexOutOfBoundsException("Overlay path advertises more BlockPos data than is readable");
            }
            buffer.skipBytes((int) bytesToSkip);
        }
        return List.copyOf(positions);
    }

    public static void handle(RoadPlannerRoadOverlaySyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.applyRoadOverlays(packet.sessionId(), packet.roads())));
        contextSupplier.get().setPacketHandled(true);
    }

    public record Entry(String roadId, RoadPlannerMergeRelationship relationship, List<BlockPos> path) {
        public Entry {
            roadId = roadId == null ? "" : roadId;
            relationship = relationship == null ? RoadPlannerMergeRelationship.OWN : relationship;
            path = path == null ? List.of() : path.stream()
                    .filter(java.util.Objects::nonNull)
                    .limit(MAX_PATH_POINTS)
                    .map(BlockPos::immutable)
                    .toList();
        }
    }
}
