package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Set;

public final class RoadPlanningIslandClassifier {
    private static final int DEFAULT_ISLAND_AREA_LIMIT = 32;

    private RoadPlanningIslandClassifier() {
    }

    public static IslandSummary classify(Set<BlockPos> targetLandmass,
                                         Set<BlockPos> sourceLandmass,
                                         boolean separatedByWater) {
        int targetArea = targetLandmass == null ? 0 : (int) targetLandmass.stream().filter(Objects::nonNull).count();
        int sourceArea = sourceLandmass == null ? 0 : (int) sourceLandmass.stream().filter(Objects::nonNull).count();
        boolean islandLike = separatedByWater
                && targetArea > 0
                && targetArea <= DEFAULT_ISLAND_AREA_LIMIT
                && (sourceArea == 0 || targetArea < sourceArea);
        return new IslandSummary(islandLike, targetArea, sourceArea, separatedByWater);
    }

    static IslandSummary classifyForTest(Set<BlockPos> targetLandmass,
                                         Set<BlockPos> sourceLandmass,
                                         boolean separatedByWater) {
        return classify(targetLandmass, sourceLandmass, separatedByWater);
    }

    public record IslandSummary(boolean isIslandLike,
                                int targetArea,
                                int sourceArea,
                                boolean separatedByWater) {
    }
}
