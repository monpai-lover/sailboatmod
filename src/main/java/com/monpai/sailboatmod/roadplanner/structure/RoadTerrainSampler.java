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

    /**
     * The block the ground is finished with at this column (grass, sand, stone...), or null when
     * unknown. Used to dress embankments and cuttings so the roadside matches its surroundings.
     */
    default net.minecraft.world.level.block.state.BlockState groundCover(int x, int z) {
        return null;
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
                return RoadTerrainColumnSampler.terrainYFromColumn(
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z),
                        level.getMinBuildHeight(),
                        y -> level.getBlockState(new BlockPos(x, y, z))
                );
            }

            @Override
            public int waterSurfaceY(int x, int z) {
                return RoadTerrainColumnSampler.waterSurfaceYFromColumn(
                        level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z),
                        Math.max(level.getMinBuildHeight(), level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z)),
                        level.getSeaLevel(),
                        y -> level.getBlockState(new BlockPos(x, y, z))
                );
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return !level.getFluidState(new BlockPos(x, y, z)).isEmpty();
            }

            @Override
            public net.minecraft.world.level.block.state.BlockState groundCover(int x, int z) {
                return level.getBlockState(new BlockPos(x, terrainY(x, z) - 1, z));
            }
        };
    }
}
