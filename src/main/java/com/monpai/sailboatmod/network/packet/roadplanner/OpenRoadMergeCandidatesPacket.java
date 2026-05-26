package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record OpenRoadMergeCandidatesPacket(UUID sessionId, UUID requestId, List<Entry> candidates) {
    private static final int MAX_CANDIDATES = 16;

    public OpenRoadMergeCandidatesPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        requestId = requestId == null ? new UUID(0L, 0L) : requestId;
        candidates = candidates == null ? List.of() : candidates.stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_CANDIDATES)
                .toList();
    }

    public OpenRoadMergeCandidatesPacket(UUID sessionId, List<Entry> candidates) {
        this(sessionId, new UUID(0L, 0L), candidates);
    }

    public static void encode(OpenRoadMergeCandidatesPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeUuid(buffer, packet.requestId());
        List<Entry> candidates = packet.candidates();
        buffer.writeVarInt(Math.min(MAX_CANDIDATES, candidates.size()));
        for (Entry entry : candidates) {
            RoadPlannerPacketCodec.writeString(buffer, entry.roadId(), 128);
            buffer.writeBlockPos(entry.anchorPos());
            buffer.writeVarInt(entry.pathIndex());
            buffer.writeVarInt(entry.distanceBlocks());
            RoadPlannerPacketCodec.writeString(buffer, entry.sourceName(), 96);
            RoadPlannerPacketCodec.writeString(buffer, entry.targetName(), 96);
            RoadPlannerPacketCodec.writeString(buffer, entry.ownerNationId(), 64);
            buffer.writeEnum(entry.relationship());
        }
    }

    public static OpenRoadMergeCandidatesPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        UUID requestId = RoadPlannerPacketCodec.readUuid(buffer);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_CANDIDATES) {
            throw new IllegalArgumentException("Road merge candidate count out of bounds: " + count);
        }
        java.util.ArrayList<Entry> candidates = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            candidates.add(new Entry(
                    buffer.readUtf(128),
                    buffer.readBlockPos(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(96),
                    buffer.readUtf(96),
                    buffer.readUtf(64),
                    buffer.readEnum(RoadPlannerMergeRelationship.class)));
        }
        return new OpenRoadMergeCandidatesPacket(sessionId, requestId, candidates);
    }

    public static void handle(OpenRoadMergeCandidatesPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.applyRoadMergeCandidates(packet.sessionId(), packet.requestId(), packet.candidates())));
        contextSupplier.get().setPacketHandled(true);
    }

    public record Entry(String roadId,
                        BlockPos anchorPos,
                        int pathIndex,
                        int distanceBlocks,
                        String sourceName,
                        String targetName,
                        String ownerNationId,
                        RoadPlannerMergeRelationship relationship) {
        public Entry {
            roadId = roadId == null ? "" : roadId;
            anchorPos = anchorPos == null ? BlockPos.ZERO : anchorPos.immutable();
            pathIndex = Math.max(0, pathIndex);
            distanceBlocks = Math.max(0, distanceBlocks);
            sourceName = sourceName == null || sourceName.isBlank() ? "-" : sourceName;
            targetName = targetName == null || targetName.isBlank() ? "-" : targetName;
            ownerNationId = ownerNationId == null ? "" : ownerNationId;
            relationship = relationship == null ? RoadPlannerMergeRelationship.OWN : relationship;
        }
    }
}
