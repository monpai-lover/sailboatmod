package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.service.StructureConstructionManager;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class ConstructorClientHooks {
    public record ConstructionProgress(BlockPos origin, String structureId, int progressPercent, int activeWorkers) {
    }

    private static List<ConstructionProgress> activeConstructions = List.of();
    private static long lastSyncAtMs = 0L;

    public static void updateProgress(List<ConstructionProgress> constructions) {
        activeConstructions = List.copyOf(constructions);
        lastSyncAtMs = System.currentTimeMillis();
    }

    public static ConstructionProgress findNearest(BlockPos playerPos, StructureConstructionManager.StructureType preferredType) {
        if (playerPos == null || activeConstructions.isEmpty() || isStale()) {
            return null;
        }

        ConstructionProgress best = null;
        double bestScore = Double.MAX_VALUE;
        for (ConstructionProgress progress : activeConstructions) {
            double distance = playerPos.distSqr(progress.origin());
            double score = distance;
            if (preferredType != null && !preferredType.nbtName().equals(progress.structureId())) {
                score += 256.0D;
            }
            if (score < bestScore) {
                bestScore = score;
                best = progress;
            }
        }
        return best;
    }

    public static List<ConstructionProgress> activeConstructions() {
        if (isStale()) {
            activeConstructions = List.of();
        }
        return new ArrayList<>(activeConstructions);
    }

    private static boolean isStale() {
        return (System.currentTimeMillis() - lastSyncAtMs) > 3000L;
    }

    private ConstructorClientHooks() {
    }
}
