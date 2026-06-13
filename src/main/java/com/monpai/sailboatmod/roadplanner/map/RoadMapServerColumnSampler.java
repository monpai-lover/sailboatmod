package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;

public class RoadMapServerColumnSampler implements RoadMapColumnSampler {
    private static final int UNKNOWN_ARGB = 0xFF2A2A2A;

    private final ServerLevel level;

    public RoadMapServerColumnSampler(ServerLevel level) {
        this.level = level;
    }

    @Override
    public RoadMapColumnSample sample(int worldX, int worldZ) {
        if (level == null) {
            return unavailableSample(worldX, worldZ);
        }
        try {
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            if (level.getChunkSource().getChunk(chunkX, chunkZ, false) == null) {
                return unavailableSample(worldX, worldZ);
            }
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
            if (surfaceY < level.getMinBuildHeight()) {
                return unavailableSample(worldX, worldZ);
            }
            BlockPos pos = new BlockPos(worldX, surfaceY, worldZ);
            BlockState state = level.getBlockState(pos);
            boolean water = state.getFluidState().is(Fluids.WATER);
            int waterDepth = water ? waterDepth(level, pos) : 0;
            int reliefBaseY = Math.max(
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ + 1),
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX - 1, worldZ));
            MapColor mapColor = state.getMapColor(level, pos);
            int fallback = mapColor == null ? UNKNOWN_ARGB : mapColor.calculateRGBColor(MapColor.Brightness.NORMAL);
            int argb = MapBlockColors.colorFor(state, fallback);
            return new RoadMapColumnSample(worldX, surfaceY, worldZ, argb, water, waterDepth, reliefBaseY);
        } catch (RuntimeException ignored) {
            return unavailableSample(worldX, worldZ);
        }
    }

    public static RoadMapColumnSample unavailableSampleForTest(int worldX, int worldZ) {
        return unavailableSample(worldX, worldZ);
    }

    static boolean isUnavailableSample(RoadMapColumnSample sample) {
        return sample != null
                && sample.baseArgb() == UNKNOWN_ARGB
                && sample.surfaceY() == 0
                && sample.reliefBaseY() == 0
                && !sample.water()
                && sample.waterDepth() == 0;
    }

    private static RoadMapColumnSample unavailableSample(int worldX, int worldZ) {
        return new RoadMapColumnSample(worldX, 0, worldZ, UNKNOWN_ARGB, false, 0, 0);
    }

    private static int waterDepth(ServerLevel level, BlockPos pos) {
        int depth = 0;
        BlockPos.MutableBlockPos mutable = pos.mutable();
        while (level.getBlockState(mutable).getFluidState().is(Fluids.WATER) && mutable.getY() > level.getMinBuildHeight()) {
            depth++;
            mutable.move(Direction.DOWN);
        }
        return depth;
    }
}
