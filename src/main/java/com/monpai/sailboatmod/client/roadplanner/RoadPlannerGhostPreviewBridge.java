package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;

public final class RoadPlannerGhostPreviewBridge {
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
        if (nodes == null || nodes.size() < 2) {
            return false;
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getConnection() != null) {
            ModNetwork.CHANNEL.sendToServer(new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, safeSettings));
            return true;
        }
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, safeSettings);
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
        return true;
    }
}
