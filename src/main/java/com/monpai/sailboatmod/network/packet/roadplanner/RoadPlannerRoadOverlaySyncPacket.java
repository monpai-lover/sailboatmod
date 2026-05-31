package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerRoadOverlaySyncPacket(UUID sessionId,
                                               BlockPos regionCenter,
                                               int regionSize,
                                               RoadPlannerMergeScope scope,
                                               List<Entry> roads) {
    private static final int MAX_ROADS = 128;
    private static final int MAX_PATH_POINTS = RoadPlannerRoadOverlayRequestPacket.MAX_REGION_SIZE * 8;
    private static final int MAX_SHARED_SPANS = 64;
    private static final int EXTENSION_MARKER = 0x524F5632; // ROV2

    public RoadPlannerRoadOverlaySyncPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        regionCenter = regionCenter == null ? BlockPos.ZERO : regionCenter.immutable();
        regionSize = RoadPlannerRoadOverlayRequestPacket.normalizeRegionSize(regionSize);
        scope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
        roads = roads == null ? List.of() : roads.stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_ROADS)
                .toList();
    }

    public RoadPlannerRoadOverlaySyncPacket(UUID sessionId, List<Entry> roads) {
        this(sessionId, BlockPos.ZERO, 1, RoadPlannerMergeScope.DISABLED, roads);
    }

    public static void encode(RoadPlannerRoadOverlaySyncPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        buffer.writeBlockPos(packet.regionCenter());
        buffer.writeVarInt(packet.regionSize());
        buffer.writeEnum(packet.scope());
        List<Entry> roads = packet.roads();
        buffer.writeVarInt(Math.min(MAX_ROADS, roads.size()));
        for (Entry entry : roads) {
            RoadPlannerPacketCodec.writeString(buffer, entry.roadId(), 128);
            buffer.writeEnum(entry.relationship());
            RoadPlannerPacketCodec.writeBlockPosList(buffer, entry.path());
            RoadPlannerPacketCodec.writeString(buffer, entry.displayName(), 128);
            buffer.writeVarInt(entry.lengthBlocks());
            RoadPlannerPacketCodec.writeString(buffer, entry.creatorName(), 64);
            RoadPlannerPacketCodec.writeString(buffer, entry.creatorUuid(), 64);
            buffer.writeLong(entry.createdAt());
            buffer.writeBoolean(entry.legacyMetadata());
            buffer.writeInt(EXTENSION_MARKER);
            RoadPlannerPacketCodec.writeBlockPosList(buffer, entry.displayPath());
            writeIntList(buffer, entry.displayPathPathIndices());
            writeSharedSpans(buffer, entry.sharedSpans());
        }
    }

    public static RoadPlannerRoadOverlaySyncPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        BlockPos regionCenter = buffer.readBlockPos();
        int regionSize = buffer.readVarInt();
        RoadPlannerMergeScope scope = buffer.readEnum(RoadPlannerMergeScope.class);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ROADS) {
            throw new IllegalArgumentException("Road overlay count out of bounds: " + count);
        }
        java.util.ArrayList<Entry> roads = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String roadId = buffer.readUtf(128);
            RoadPlannerMergeRelationship relationship = buffer.readEnum(RoadPlannerMergeRelationship.class);
            List<BlockPos> path = readCappedBlockPosList(buffer);
            String displayName = buffer.readUtf(128);
            int lengthBlocks = buffer.readVarInt();
            String creatorName = buffer.readUtf(64);
            String creatorUuid = buffer.readUtf(64);
            long createdAt = buffer.readLong();
            boolean legacyMetadata = buffer.readBoolean();
            List<BlockPos> displayPath = List.of();
            List<Integer> displayPathPathIndices = List.of();
            List<RoadPlannerSharedRoadSpan> sharedSpans = List.of();
            if (buffer.readableBytes() >= Integer.BYTES) {
                int readerIndex = buffer.readerIndex();
                int marker = buffer.readInt();
                if (marker == EXTENSION_MARKER) {
                    displayPath = readCappedBlockPosList(buffer);
                    displayPathPathIndices = readIntList(buffer);
                    sharedSpans = readSharedSpans(buffer);
                } else {
                    buffer.readerIndex(readerIndex);
                }
            }
            roads.add(new Entry(
                    roadId,
                    relationship,
                    path,
                    displayPath,
                    displayPathPathIndices,
                    sharedSpans,
                    displayName,
                    lengthBlocks,
                    creatorName,
                    creatorUuid,
                    createdAt,
                    legacyMetadata));
        }
        return new RoadPlannerRoadOverlaySyncPacket(sessionId, regionCenter, regionSize, scope, roads);
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

    private static void writeIntList(FriendlyByteBuf buffer, List<Integer> values) {
        List<Integer> safeValues = values == null ? List.of() : values;
        buffer.writeVarInt(Math.min(MAX_PATH_POINTS, safeValues.size()));
        for (Integer value : safeValues.stream().limit(MAX_PATH_POINTS).toList()) {
            buffer.writeVarInt(value == null ? -1 : value);
        }
    }

    private static List<Integer> readIntList(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_PATH_POINTS) {
            throw new IllegalArgumentException("Road overlay display path index count out of bounds: " + count);
        }
        List<Integer> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(buffer.readVarInt());
        }
        return List.copyOf(values);
    }

    private static void writeSharedSpans(FriendlyByteBuf buffer, List<RoadPlannerSharedRoadSpan> sharedSpans) {
        List<RoadPlannerSharedRoadSpan> safeSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                .filter(java.util.Objects::nonNull)
                .filter(RoadPlannerSharedRoadSpan::present)
                .limit(MAX_SHARED_SPANS)
                .toList();
        buffer.writeVarInt(safeSpans.size());
        for (RoadPlannerSharedRoadSpan span : safeSpans) {
            RoadPlannerPacketCodec.writeString(buffer, span.roadId(), 128);
            buffer.writeVarInt(span.fromPathIndex());
            buffer.writeVarInt(span.toPathIndex());
            buffer.writeBlockPos(span.fromPos());
            buffer.writeBlockPos(span.toPos());
            buffer.writeEnum(span.scope());
            buffer.writeEnum(span.role());
        }
    }

    private static List<RoadPlannerSharedRoadSpan> readSharedSpans(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_SHARED_SPANS) {
            throw new IllegalArgumentException("Road overlay shared span count out of bounds: " + count);
        }
        List<RoadPlannerSharedRoadSpan> spans = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            spans.add(new RoadPlannerSharedRoadSpan(
                    buffer.readUtf(128),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBlockPos(),
                    buffer.readBlockPos(),
                    buffer.readEnum(RoadPlannerMergeScope.class),
                    buffer.readEnum(RoadPlannerSharedRoadSpan.Role.class)));
        }
        return List.copyOf(spans);
    }

    public static void handle(RoadPlannerRoadOverlaySyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.applyRoadOverlays(
                        packet.sessionId(),
                        packet.regionCenter(),
                        packet.regionSize(),
                        packet.scope(),
                        packet.roads())));
        contextSupplier.get().setPacketHandled(true);
    }

    public record Entry(String roadId,
                        RoadPlannerMergeRelationship relationship,
                        List<BlockPos> path,
                        List<BlockPos> displayPath,
                        List<Integer> displayPathPathIndices,
                        List<RoadPlannerSharedRoadSpan> sharedSpans,
                        String displayName,
                        int lengthBlocks,
                        String creatorName,
                        String creatorUuid,
                        long createdAt,
                        boolean legacyMetadata) {
        public Entry(String roadId, RoadPlannerMergeRelationship relationship, List<BlockPos> path) {
            this(roadId, relationship, path, path, sequentialIndices(path), List.of(), roadId, 0, "", "", 0L, true);
        }

        public Entry(String roadId,
                     RoadPlannerMergeRelationship relationship,
                     List<BlockPos> path,
                     String displayName,
                     int lengthBlocks,
                     String creatorName,
                     String creatorUuid,
                     long createdAt,
                     boolean legacyMetadata) {
            this(roadId, relationship, path, path, sequentialIndices(path), List.of(),
                    displayName, lengthBlocks, creatorName, creatorUuid, createdAt, legacyMetadata);
        }

        public Entry {
            roadId = roadId == null ? "" : roadId;
            relationship = relationship == null ? RoadPlannerMergeRelationship.OWN : relationship;
            path = path == null ? List.of() : path.stream()
                    .filter(java.util.Objects::nonNull)
                    .limit(MAX_PATH_POINTS)
                    .map(BlockPos::immutable)
                    .toList();
            List<BlockPos> canonicalPath = path;
            displayPath = displayPath == null || displayPath.isEmpty()
                    ? canonicalPath
                    : displayPath.stream()
                    .filter(java.util.Objects::nonNull)
                    .limit(MAX_PATH_POINTS)
                    .map(BlockPos::immutable)
                    .toList();
            if (displayPathPathIndices == null || displayPathPathIndices.size() != displayPath.size()) {
                displayPathPathIndices = displayPath.stream()
                        .map(pos -> nearestPathIndex(canonicalPath, pos))
                        .toList();
            } else {
                displayPathPathIndices = displayPathPathIndices.stream()
                        .limit(MAX_PATH_POINTS)
                        .map(index -> index == null ? -1 : Math.max(-1, index))
                        .toList();
            }
            sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(RoadPlannerSharedRoadSpan::present)
                    .limit(MAX_SHARED_SPANS)
                    .toList();
            displayName = displayName == null || displayName.isBlank() ? roadId : displayName.trim();
            lengthBlocks = Math.max(0, lengthBlocks);
            creatorName = creatorName == null ? "" : creatorName.trim();
            creatorUuid = creatorUuid == null ? "" : creatorUuid.trim();
            createdAt = Math.max(0L, createdAt);
        }

        private static List<Integer> sequentialIndices(List<BlockPos> path) {
            int size = path == null ? 0 : Math.min(MAX_PATH_POINTS, path.size());
            List<Integer> indices = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                indices.add(index);
            }
            return List.copyOf(indices);
        }

        private static int nearestPathIndex(List<BlockPos> path, BlockPos target) {
            if (path == null || path.isEmpty() || target == null) {
                return -1;
            }
            int exact = path.indexOf(target);
            if (exact >= 0) {
                return exact;
            }
            int bestIndex = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int index = 0; index < path.size(); index++) {
                BlockPos pos = path.get(index);
                if (pos == null) {
                    continue;
                }
                long dx = (long) pos.getX() - target.getX();
                long dy = (long) pos.getY() - target.getY();
                long dz = (long) pos.getZ() - target.getZ();
                long distance = dx * dx + dy * dy + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = index;
                }
            }
            return bestIndex;
        }
    }
}
