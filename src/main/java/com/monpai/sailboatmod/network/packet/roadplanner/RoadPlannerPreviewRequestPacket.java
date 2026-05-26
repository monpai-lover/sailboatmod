package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import com.monpai.sailboatmod.roadplanner.structure.RoadCenterlinePoint;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeExpansionResult;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpander;
import com.monpai.sailboatmod.roadplanner.structure.RoadPreviewBlock;
import com.monpai.sailboatmod.roadplanner.structure.RoadStructureMode;
import com.monpai.sailboatmod.roadplanner.structure.RoadTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record RoadPlannerPreviewRequestPacket(String startTownName,
                                              String destinationTownName,
                                              List<BlockPos> nodes,
                                              List<RoadPlannerSegmentType> segmentTypes,
                                              RoadPlannerBuildSettings settings,
                                              RoadPlannerMergeSelection mergeSelection) {
    public RoadPlannerPreviewRequestPacket {
        startTownName = startTownName == null ? "" : startTownName;
        destinationTownName = destinationTownName == null ? "" : destinationTownName;
        nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        segmentTypes = normalizeSegments(segmentTypes, nodes.size());
        settings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
    }

    public RoadPlannerPreviewRequestPacket(String startTownName,
                                           String destinationTownName,
                                           List<BlockPos> nodes,
                                           List<RoadPlannerSegmentType> segmentTypes,
                                           RoadPlannerBuildSettings settings) {
        this(startTownName, destinationTownName, nodes, segmentTypes, settings, RoadPlannerMergeSelection.none());
    }

    public RoadPlannerPreviewRequestPacket(String startTownName,
                                           String destinationTownName,
                                           List<BlockPos> nodes,
                                           List<RoadPlannerSegmentType> segmentTypes) {
        this(startTownName, destinationTownName, nodes, segmentTypes, RoadPlannerBuildSettings.DEFAULTS);
    }

    public static void encode(RoadPlannerPreviewRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeString(buffer, packet.startTownName(), 64);
        RoadPlannerPacketCodec.writeString(buffer, packet.destinationTownName(), 64);
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.nodes());
        buffer.writeVarInt(packet.segmentTypes().size());
        for (RoadPlannerSegmentType type : packet.segmentTypes()) {
            buffer.writeEnum(type == null ? RoadPlannerSegmentType.ROAD : type);
        }
        buffer.writeVarInt(packet.settings().width());
        RoadPlannerPacketCodec.writeString(buffer, packet.settings().materialPreset(), 32);
        buffer.writeBoolean(packet.settings().streetlightsEnabled());
        buffer.writeBoolean(packet.mergeSelection().present());
        if (packet.mergeSelection().present()) {
            RoadPlannerPacketCodec.writeString(buffer, packet.mergeSelection().roadId(), 128);
            buffer.writeVarInt(packet.mergeSelection().pathIndex());
            buffer.writeBlockPos(packet.mergeSelection().anchorPos());
            buffer.writeEnum(packet.mergeSelection().scope());
        }
    }

    public static RoadPlannerPreviewRequestPacket decode(FriendlyByteBuf buffer) {
        String startTownName = buffer.readUtf(64);
        String destinationTownName = buffer.readUtf(64);
        List<BlockPos> nodes = RoadPlannerPacketCodec.readBlockPosList(buffer);
        int segmentCount = buffer.readVarInt();
        List<RoadPlannerSegmentType> segmentTypes = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            segmentTypes.add(buffer.readEnum(RoadPlannerSegmentType.class));
        }
        RoadPlannerBuildSettings settings = buffer.isReadable()
                ? new RoadPlannerBuildSettings(buffer.readVarInt(), buffer.readUtf(32), buffer.readBoolean())
                : RoadPlannerBuildSettings.DEFAULTS;
        RoadPlannerMergeSelection mergeSelection = RoadPlannerMergeSelection.none();
        if (buffer.isReadable() && buffer.readBoolean()) {
            mergeSelection = new RoadPlannerMergeSelection(
                    buffer.readUtf(128),
                    buffer.readVarInt(),
                    buffer.readBlockPos(),
                    buffer.readEnum(RoadPlannerMergeScope.class)
            );
        }
        return new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, settings, mergeSelection);
    }

    public static void handle(RoadPlannerPreviewRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            RoadPlannerPreviewRequestPacket safePacket = packet.withServerValidatedMerge(player);
            RoadPlannerBuildControlService.global().startPreview(player.getUUID(), safePacket.nodes(), safePacket.segmentTypes(), safePacket.settings(), safePacket.mergeSelection());
            ModNetwork.CHANNEL.sendTo(safePacket.toSafePreview(player.serverLevel()), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        });
        context.setPacketHandled(true);
    }

    RoadPlannerPreviewRequestPacket withServerValidatedMerge(ServerPlayer player) {
        if (!mergeSelection.present()) {
            return this;
        }
        if (player == null || nodes.isEmpty()) {
            return new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, settings, RoadPlannerMergeSelection.none());
        }
        RoadPlannerSegmentType finalSegmentType = segmentTypes.isEmpty()
                ? RoadPlannerSegmentType.ROAD
                : segmentTypes.get(segmentTypes.size() - 1);
        BlockPos submittedEnd = nodes.get(nodes.size() - 1);
        return RoadPlannerRoadMergeService.validateSelection(
                        player,
                        submittedEnd,
                        RoadPlannerMergeCandidateRequestPacket.MAX_RADIUS,
                        mergeSelection,
                        finalSegmentType)
                .map(candidate -> {
                    List<BlockPos> snappedNodes = new ArrayList<>(nodes);
                    snappedNodes.set(snappedNodes.size() - 1, candidate.anchorPos());
                    RoadPlannerMergeSelection canonicalSelection = new RoadPlannerMergeSelection(
                            candidate.roadId(),
                            candidate.pathIndex(),
                            candidate.anchorPos(),
                            mergeSelection.scope()
                    );
                    return new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, snappedNodes, segmentTypes, settings, canonicalSelection);
                })
                .orElseGet(() -> new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, settings, RoadPlannerMergeSelection.none()));
    }

    public SyncRoadPlannerPreviewPacket toPreviewPacketForTest() {
        return toSafePreview((RoadTerrainSampler) null);
    }

    public SyncRoadPlannerPreviewPacket toPreviewPacketForTest(RoadTerrainSampler terrainSampler) {
        return toSafePreview(terrainSampler);
    }

    private SyncRoadPlannerPreviewPacket toSafePreview(ServerLevel level) {
        return toSafePreview(RoadTerrainSampler.fromLevel(level));
    }

    private SyncRoadPlannerPreviewPacket toSafePreview(RoadTerrainSampler terrainSampler) {
        if (nodes.size() < 2) {
            return new SyncRoadPlannerPreviewPacket(startTownName, destinationTownName, List.of(), List.of(), 0, null, null, null, false, List.of(), "", List.of());
        }
        RoadNodeExpansionResult expansion = RoadNodeStructureExpander.expand(
                nodes,
                segmentTypes,
                settings,
                terrainSampler,
                RoadStructureMode.PREVIEW
        );
        List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks = ghostBlocksFromBuildSteps(expansion);
        List<BlockPos> previewPathNodes = previewPathNodes(expansion);
        BlockPos startHighlight = previewPathNodes.isEmpty() ? nodes.get(0) : previewPathNodes.get(0);
        BlockPos endHighlight = previewPathNodes.isEmpty() ? nodes.get(nodes.size() - 1) : previewPathNodes.get(previewPathNodes.size() - 1);
        return new SyncRoadPlannerPreviewPacket(
                startTownName,
                destinationTownName,
                ghostBlocks,
                previewPathNodes,
                expansion.centerline().isEmpty() ? nodes.size() : expansion.centerline().size(),
                startHighlight,
                endHighlight,
                endHighlight,
                true,
                List.of(),
                "",
                bridgeRangesFromSegments()
        );
    }

    private List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocksFromBuildSteps(RoadNodeExpansionResult expansion) {
        List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks = new ArrayList<>();
        for (RoadPreviewBlock block : expansion.previewBlocks()) {
            ghostBlocks.add(new SyncRoadPlannerPreviewPacket.GhostBlock(block.pos(), block.state()));
        }
        return List.copyOf(ghostBlocks);
    }

    private static List<BlockPos> previewPathNodes(RoadNodeExpansionResult expansion) {
        if (expansion == null || expansion.centerline().isEmpty()) {
            return List.of();
        }
        List<RoadCenterlinePoint> centerline = expansion.centerline();
        if (centerline.size() <= 64) {
            return centerline.stream()
                    .map(RoadPlannerPreviewRequestPacket::targetPos)
                    .toList();
        }
        List<BlockPos> sampled = new ArrayList<>();
        sampled.add(targetPos(centerline.get(0)));
        int step = Math.max(1, centerline.size() / 62);
        for (int index = step; index < centerline.size() - 1; index += step) {
            sampled.add(targetPos(centerline.get(index)));
        }
        sampled.add(targetPos(centerline.get(centerline.size() - 1)));
        return List.copyOf(sampled);
    }

    private static BlockPos targetPos(RoadCenterlinePoint point) {
        return new BlockPos(point.pos().getX(), point.targetY(), point.pos().getZ());
    }

    private List<SyncRoadPlannerPreviewPacket.BridgeRange> bridgeRangesFromSegments() {
        List<SyncRoadPlannerPreviewPacket.BridgeRange> ranges = new ArrayList<>();
        int index = 0;
        while (index < segmentTypes.size()) {
            if (!isBridgeType(segmentTypes.get(index))) {
                index++;
                continue;
            }
            int start = index;
            while (index < segmentTypes.size() && isBridgeType(segmentTypes.get(index))) {
                index++;
            }
            ranges.add(new SyncRoadPlannerPreviewPacket.BridgeRange(start, index));
        }
        return List.copyOf(ranges);
    }

    private static boolean isBridgeType(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.BRIDGE_SMALL || type == RoadPlannerSegmentType.BRIDGE_MAJOR;
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
