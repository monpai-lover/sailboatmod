package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RoadLifecycleService {
    private RoadLifecycleService() {
    }

    public static Object findActiveRoadPlan(ServerLevel level, String roadId) {
        // Road system refactored - pending integration
        return null;
    }

    public static Object findRoadPlan(ServerLevel level, String roadId) {
        // Road system refactored - pending integration
        return null;
    }

    public static boolean cancelRoad(ServerLevel level, String roadId) {
        // Road system refactored - pending integration
        return false;
    }

    public static boolean demolishRoad(ServerLevel level, String roadId) {
        // Road system refactored - pending integration
        return false;
    }

    static List<BlockPos> ownedBlocksRemovalOrderForTest(List<BlockPos> ownedBlocks) {
        return removalOrder(ownedBlocks);
    }

    static List<BlockPos> removalOrder(List<BlockPos> ownedBlocks) {
        if (ownedBlocks == null || ownedBlocks.isEmpty()) {
            return List.of();
        }
        ArrayList<BlockPos> ordered = new ArrayList<>(ownedBlocks);
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }
}
