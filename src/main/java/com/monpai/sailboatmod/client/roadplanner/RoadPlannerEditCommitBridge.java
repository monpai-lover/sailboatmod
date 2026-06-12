package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerEditCommitPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

public final class RoadPlannerEditCommitBridge {
    private static RoadPlannerEditCommitPacket lastCommitRequestForTest;

    private RoadPlannerEditCommitBridge() {
    }

    public static boolean submitCommit(UUID sessionId,
                                       String roadId,
                                       List<BlockPos> nodes,
                                       List<RoadPlannerSegmentType> segmentTypes,
                                       RoadPlannerBuildSettings settings) {
        if (roadId == null || roadId.isBlank() || nodes == null || nodes.size() < 2) {
            return false;
        }
        RoadPlannerEditCommitPacket packet = new RoadPlannerEditCommitPacket(
                sessionId,
                roadId,
                nodes,
                segmentTypes,
                settings);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getConnection() != null) {
            ModNetwork.CHANNEL.sendToServer(packet);
            return true;
        }
        lastCommitRequestForTest = packet;
        return true;
    }

    static RoadPlannerEditCommitPacket lastCommitRequestForTest() {
        return lastCommitRequestForTest;
    }

    static void clearLastCommitRequestForTest() {
        lastCommitRequestForTest = null;
    }
}
