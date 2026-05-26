package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;

public final class RoadPlannerGhostPreviewBridge {
    private static RoadPlannerPreviewRequestPacket lastPreviewRequestForTest;

    private RoadPlannerGhostPreviewBridge() {
    }

    public static boolean submitPreview(String startTownName, String destinationTownName, List<BlockPos> nodes) {
        return submitPreview(startTownName, destinationTownName, nodes, List.of());
    }

    public static boolean submitPreview(String startTownName,
                                        String destinationTownName,
                                        List<BlockPos> nodes,
                                        List<RoadPlannerSegmentType> segmentTypes) {
        return submitPreview(startTownName, destinationTownName, nodes, segmentTypes, RoadPlannerBuildSettings.DEFAULTS);
    }

    public static boolean submitPreview(String startTownName,
                                        String destinationTownName,
                                        List<BlockPos> nodes,
                                        List<RoadPlannerSegmentType> segmentTypes,
                                        RoadPlannerBuildSettings settings) {
        return submitPreview(startTownName, destinationTownName, nodes, segmentTypes, settings, RoadPlannerMergeSelection.none());
    }

    public static boolean submitPreview(String startTownName,
                                        String destinationTownName,
                                        List<BlockPos> nodes,
                                        List<RoadPlannerSegmentType> segmentTypes,
                                        RoadPlannerBuildSettings settings,
                                        RoadPlannerMergeSelection mergeSelection) {
        if (nodes == null || nodes.size() < 2) {
            return false;
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        RoadPlannerMergeSelection safeMergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, safeSettings, safeMergeSelection);
        lastPreviewRequestForTest = packet;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getConnection() != null) {
            ModNetwork.CHANNEL.sendToServer(packet);
            return true;
        }
        try {
            RoadPlannerClientHooks.updatePreview(new RoadPlannerClientHooks.PreviewState(
                    startTownName == null ? "" : startTownName,
                    destinationTownName == null ? "" : destinationTownName,
                    packet.toPreviewPacketForTest().ghostBlocks().stream()
                            .map(block -> new RoadPlannerClientHooks.PreviewGhostBlock(block.pos(), block.state()))
                            .toList(),
                    nodes,
                    nodes.size(),
                    nodes.get(0),
                    nodes.get(nodes.size() - 1),
                    nodes.get(nodes.size() - 1),
                    true,
                    List.of(),
                    "",
                    List.of()
            ));
        } catch (ExceptionInInitializerError | NoClassDefFoundError | IllegalArgumentException ignored) {
        }
        return true;
    }

    static RoadPlannerPreviewRequestPacket lastPreviewRequestForTest() {
        return lastPreviewRequestForTest;
    }
}
