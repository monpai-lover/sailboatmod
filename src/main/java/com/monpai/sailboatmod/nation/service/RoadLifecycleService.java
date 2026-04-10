package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RoadLifecycleService {
    private RoadLifecycleService() {
    }

    public static RoadPlacementPlan findActiveRoadPlan(ServerLevel level, String roadId) {
        return StructureConstructionManager.findActiveRoadPlacementPlan(level, roadId);
    }

    public static RoadPlacementPlan findRoadPlan(ServerLevel level, String roadId) {
        return StructureConstructionManager.findRoadPlacementPlan(level, roadId);
    }

    public static boolean cancelRoad(ServerLevel level, String roadId) {
        return StructureConstructionManager.cancelActiveRoadConstruction(level, roadId);
    }

    public static boolean demolishRoad(ServerLevel level, String roadId) {
        return StructureConstructionManager.demolishRoadById(level, roadId);
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
