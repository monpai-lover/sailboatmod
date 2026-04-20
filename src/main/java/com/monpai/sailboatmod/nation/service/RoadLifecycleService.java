package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.road.api.RoadNetworkApi;
import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class RoadLifecycleService {
    private RoadLifecycleService() {
    }

    private static RoadNetworkApi getApi() {
        return new RoadNetworkApi(new RoadConfig());
    }

    public static Object findActiveRoadPlan(ServerLevel level, String roadId) {
        if (level == null || roadId == null) return null;
        RoadNetworkApi api = getApi();
        Optional<ConstructionQueue> queue = api.getConstruction(roadId);
        return queue.orElse(null);
    }

    public static Object findRoadPlan(ServerLevel level, String roadId) {
        return findActiveRoadPlan(level, roadId);
    }

    public static boolean cancelRoad(ServerLevel level, String roadId) {
        if (level == null || roadId == null) return false;
        RoadNetworkApi api = getApi();
        Optional<ConstructionQueue> queue = api.getConstruction(roadId);
        if (queue.isEmpty()) return false;
        api.cancelRoad(roadId, level);
        return true;
    }

    public static boolean demolishRoad(ServerLevel level, String roadId) {
        if (level == null || roadId == null) return false;
        RoadNetworkApi api = getApi();
        api.cancelRoad(roadId, level);
        return true;
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
