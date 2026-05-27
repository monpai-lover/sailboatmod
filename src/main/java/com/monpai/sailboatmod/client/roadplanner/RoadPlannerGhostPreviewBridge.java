package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
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
        return submitPreview(startTownName, destinationTownName, nodes, segmentTypes, settings, mergeSelection, nodes, List.of());
    }

    public static boolean submitPreview(String startTownName,
                                        String destinationTownName,
                                        List<BlockPos> nodes,
                                        List<RoadPlannerSegmentType> segmentTypes,
                                        RoadPlannerBuildSettings settings,
                                        RoadPlannerMergeSelection mergeSelection,
                                        List<BlockPos> logicalNodes,
                                        List<RoadPlannerSharedRoadSpan> sharedSpans) {
        if (nodes == null || nodes.size() < 2) {
            return false;
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        RoadPlannerMergeSelection safeMergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                startTownName,
                destinationTownName,
                nodes,
                segmentTypes,
                safeSettings,
                safeMergeSelection,
                logicalNodes,
                sharedSpans);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getConnection() != null) {
            ModNetwork.CHANNEL.sendToServer(packet);
            return true;
        }
        lastPreviewRequestForTest = packet;
        return true;
    }

    static RoadPlannerPreviewRequestPacket lastPreviewRequestForTest() {
        return lastPreviewRequestForTest;
    }

    static void clearLastPreviewRequestForTest() {
        lastPreviewRequestForTest = null;
    }
}
