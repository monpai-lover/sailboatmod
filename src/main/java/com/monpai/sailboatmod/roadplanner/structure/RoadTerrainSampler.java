package com.monpai.sailboatmod.roadplanner.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

@FunctionalInterface
public interface RoadTerrainSampler {
    int terrainY(int x, int z);

    default int waterSurfaceY(int x, int z) {
        return terrainY(x, z) - 1;
    }

    default int oceanFloorY(int x, int z) {
        return terrainY(x, z) - 1;
    }

    default boolean isWater(int x, int y, int z) {
        return false;
    }

    static RoadTerrainSampler flat(int y) {
        return (x, z) -> y;
    }

    static RoadTerrainSampler fromLevel(ServerLevel level) {
        if (level == null) {
            return null;
        }
        return new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            }

            @Override
            public int waterSurfaceY(int x, int z) {
                return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return !level.getFluidState(new BlockPos(x, y, z)).isEmpty();
            }
        };
    }
}
