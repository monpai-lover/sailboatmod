package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Map;

public final class RoadSelectionService {
    private RoadSelectionService() {
    }

    public static String findRoadId(ServerLevel level, BlockPos hitPos) {
        if (level == null || hitPos == null) {
            return "";
        }
        return findRoadIdFromHitForTest(hitPos, StructureConstructionManager.snapshotRoadOwnedBlocks(level));
    }

    static String findRoadIdFromHitForTest(BlockPos hitPos, Map<String, List<BlockPos>> ownership) {
        if (hitPos == null || ownership == null || ownership.isEmpty()) {
            return "";
        }
        for (Map.Entry<String, List<BlockPos>> entry : ownership.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            if (entry.getValue().contains(hitPos)) {
                return entry.getKey();
            }
        }
        return "";
    }
}
