package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerClaimOverlay;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record OpenRoadPlannerEditScreenPacket(UUID sessionId,
                                              String roadId,
                                              String sourceTownId,
                                              String sourceTownName,
                                              BlockPos sourceAnchor,
                                              String targetTownId,
                                              String targetTownName,
                                              BlockPos targetAnchor,
                                              List<BlockPos> nodes,
                                              List<RoadPlannerSegmentType> segmentTypes,
                                              RoadPlannerBuildSettings settings,
                                              List<RoadPlannerClaimOverlay> claimOverlays) {
    public OpenRoadPlannerEditScreenPacket {
        sessionId = sessionId == null ? UUID.randomUUID() : sessionId;
        roadId = roadId == null ? "" : roadId.trim().toLowerCase(java.util.Locale.ROOT);
        sourceTownId = sourceTownId == null ? "" : sourceTownId.trim().toLowerCase(java.util.Locale.ROOT);
        sourceTownName = sourceTownName == null ? "" : sourceTownName.trim();
        sourceAnchor = sourceAnchor == null ? BlockPos.ZERO : sourceAnchor.immutable();
        targetTownId = targetTownId == null ? "" : targetTownId.trim().toLowerCase(java.util.Locale.ROOT);
        targetTownName = targetTownName == null ? "" : targetTownName.trim();
        targetAnchor = targetAnchor == null ? BlockPos.ZERO : targetAnchor.immutable();
        nodes = nodes == null ? List.of() : nodes.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
        segmentTypes = normalizeSegments(segmentTypes, nodes.size());
        settings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        claimOverlays = claimOverlays == null ? List.of() : claimOverlays.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public static void encode(OpenRoadPlannerEditScreenPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeString(buffer, packet.roadId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.sourceTownId(), 40);
        RoadPlannerPacketCodec.writeString(buffer, packet.sourceTownName(), 96);
        buffer.writeBlockPos(packet.sourceAnchor());
        RoadPlannerPacketCodec.writeString(buffer, packet.targetTownId(), 40);
        RoadPlannerPacketCodec.writeString(buffer, packet.targetTownName(), 96);
        buffer.writeBlockPos(packet.targetAnchor());
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.nodes());
        buffer.writeVarInt(packet.segmentTypes().size());
        for (RoadPlannerSegmentType type : packet.segmentTypes()) {
            buffer.writeEnum(type == null ? RoadPlannerSegmentType.ROAD : type);
        }
        buffer.writeVarInt(packet.settings().width());
        RoadPlannerPacketCodec.writeString(buffer, packet.settings().materialPreset(), 32);
        buffer.writeBoolean(packet.settings().streetlightsEnabled());
        buffer.writeVarInt(packet.claimOverlays().size());
        for (RoadPlannerClaimOverlay overlay : packet.claimOverlays()) {
            buffer.writeVarInt(overlay.chunkX());
            buffer.writeVarInt(overlay.chunkZ());
            RoadPlannerPacketCodec.writeString(buffer, overlay.townId(), 40);
            RoadPlannerPacketCodec.writeString(buffer, overlay.townName(), 64);
            RoadPlannerPacketCodec.writeString(buffer, overlay.nationId(), 40);
            RoadPlannerPacketCodec.writeString(buffer, overlay.nationName(), 64);
            buffer.writeEnum(overlay.role());
            buffer.writeVarInt(overlay.primaryColorRgb());
            buffer.writeVarInt(overlay.secondaryColorRgb());
        }
    }

    public static OpenRoadPlannerEditScreenPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        String roadId = buffer.readUtf(128);
        String sourceTownId = buffer.readUtf(40);
        String sourceTownName = buffer.readUtf(96);
        BlockPos sourceAnchor = buffer.readBlockPos();
        String targetTownId = buffer.readUtf(40);
        String targetTownName = buffer.readUtf(96);
        BlockPos targetAnchor = buffer.readBlockPos();
        List<BlockPos> nodes = RoadPlannerPacketCodec.readBlockPosList(buffer);
        int segmentCount = Math.max(0, buffer.readVarInt());
        List<RoadPlannerSegmentType> segmentTypes = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            segmentTypes.add(buffer.readEnum(RoadPlannerSegmentType.class));
        }
        RoadPlannerBuildSettings settings = new RoadPlannerBuildSettings(buffer.readVarInt(), buffer.readUtf(32), buffer.readBoolean());
        int overlayCount = Math.max(0, buffer.readVarInt());
        List<RoadPlannerClaimOverlay> claimOverlays = new ArrayList<>(overlayCount);
        for (int index = 0; index < overlayCount; index++) {
            claimOverlays.add(new RoadPlannerClaimOverlay(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readEnum(RoadPlannerClaimOverlay.Role.class),
                    buffer.readVarInt(),
                    buffer.readVarInt()));
        }
        return new OpenRoadPlannerEditScreenPacket(sessionId, roadId, sourceTownId, sourceTownName, sourceAnchor,
                targetTownId, targetTownName, targetAnchor, nodes, segmentTypes, settings, claimOverlays);
    }

    public static void handle(OpenRoadPlannerEditScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.openEditPlanner(packet)));
        contextSupplier.get().setPacketHandled(true);
    }

    private static List<RoadPlannerSegmentType> normalizeSegments(List<RoadPlannerSegmentType> segmentTypes, int nodeCount) {
        int expected = Math.max(0, nodeCount - 1);
        List<RoadPlannerSegmentType> normalized = new ArrayList<>(expected);
        if (segmentTypes != null) {
            for (int index = 0; index < Math.min(expected, segmentTypes.size()); index++) {
                RoadPlannerSegmentType type = segmentTypes.get(index);
                normalized.add(type == null ? RoadPlannerSegmentType.ROAD : type);
            }
        }
        while (normalized.size() < expected) {
            normalized.add(RoadPlannerSegmentType.ROAD);
        }
        return List.copyOf(normalized);
    }
}
