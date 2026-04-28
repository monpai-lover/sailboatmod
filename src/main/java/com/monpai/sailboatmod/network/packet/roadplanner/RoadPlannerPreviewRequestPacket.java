package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import com.monpai.sailboatmod.road.model.BuildStep;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record RoadPlannerPreviewRequestPacket(String startTownName,
                                              String destinationTownName,
                                              List<BlockPos> nodes,
                                              List<RoadPlannerSegmentType> segmentTypes,
                                              RoadPlannerBuildSettings settings) {
    public RoadPlannerPreviewRequestPacket {
        startTownName = startTownName == null ? "" : startTownName;
        destinationTownName = destinationTownName == null ? "" : destinationTownName;
        nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        segmentTypes = normalizeSegments(segmentTypes, nodes.size());
        settings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
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
        return new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, settings);
    }

    public static void handle(RoadPlannerPreviewRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            RoadPlannerBuildControlService.global().startPreview(player.getUUID(), packet.nodes(), packet.segmentTypes(), packet.settings());
            ModNetwork.CHANNEL.sendTo(packet.toSafePreview(player.serverLevel()), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        });
        context.setPacketHandled(true);
    }

    public SyncRoadPlannerPreviewPacket toPreviewPacketForTest() {
        return toSafePreview(null);
    }

    private SyncRoadPlannerPreviewPacket toSafePreview(ServerLevel level) {
        if (nodes.size() < 2) {
            return new SyncRoadPlannerPreviewPacket(startTownName, destinationTownName, List.of(), List.of(), 0, null, null, null, false, List.of(), "", List.of());
        }
        List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks = ghostBlocksFromBuildSteps(level);
        return new SyncRoadPlannerPreviewPacket(
                startTownName,
                destinationTownName,
                ghostBlocks,
                nodes,
                nodes.size(),
                nodes.get(0),
                nodes.get(nodes.size() - 1),
                nodes.get(nodes.size() - 1),
                true,
                List.of(),
                "",
                bridgeRangesFromSegments()
        );
    }

    private List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocksFromBuildSteps(ServerLevel level) {
        List<BuildStep> steps = RoadPlannerBuildControlService.previewBuildSteps(nodes, segmentTypes, settings, level);
        List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks = new ArrayList<>();
        for (BuildStep step : steps) {
            if (!isVisiblePreviewStep(step)) {
                continue;
            }
            ghostBlocks.add(new SyncRoadPlannerPreviewPacket.GhostBlock(step.pos(), step.state()));
        }
        return List.copyOf(ghostBlocks);
    }

    private static boolean isVisiblePreviewStep(BuildStep step) {
        if (step == null || step.pos() == null || step.state() == null || step.phase() == null || step.state().isAir()) {
            return false;
        }
        return switch (step.phase()) {
            case SURFACE, DECK, STREETLIGHT -> true;
            default -> false;
        };
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
