package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.road.pathfinding.cache.RoadSurfaceHeuristics;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.IntFunction;

final class RoadTerrainColumnSampler {
    private RoadTerrainColumnSampler() {
    }

    static int terrainYFromColumn(int heightmapY,
                                  int minBuildHeight,
                                  IntFunction<BlockState> stateAtY) {
        int topBlockY = heightmapY - 1;
        for (int y = topBlockY; y >= minBuildHeight; y--) {
            BlockState state = stateAtY.apply(y);
            if (state == null || state.isAir() || RoadSurfaceHeuristics.isIgnoredSurfaceNoise(state)) {
                continue;
            }
            if (RoadSurfaceHeuristics.isRoadBearingSurface(state) || !state.getFluidState().isEmpty()) {
                return y + 1;
            }
        }
        return minBuildHeight + 1;
    }

    static int waterSurfaceYFromColumn(int heightmapY,
                                       int minY,
                                       int fallbackY,
                                       IntFunction<BlockState> stateAtY) {
        int topBlockY = heightmapY - 1;
        for (int y = topBlockY; y >= minY; y--) {
            BlockState state = stateAtY.apply(y);
            if (state == null || state.isAir() || RoadSurfaceHeuristics.isIgnoredSurfaceNoise(state)) {
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                return y;
            }
        }
        return fallbackY;
    }
}
