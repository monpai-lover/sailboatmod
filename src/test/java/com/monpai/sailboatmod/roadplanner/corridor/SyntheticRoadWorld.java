package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.structure.RoadTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/** A heightmap world with a sea level, for exercising road construction without a server. */
final class SyntheticRoadWorld implements RoadTerrainSampler {
    static final int SEA = 63;
    private final IntBinaryOperator ground;
    private final Map<BlockPos, BlockState> placed = new HashMap<>();

    SyntheticRoadWorld(IntBinaryOperator ground) {
        this.ground = ground;
    }

    /** Rolling hills, a river along z = 0, and a steep ravine crossing x = 260. */
    static SyntheticRoadWorld hillsRiverAndRavine() {
        return new SyntheticRoadWorld((x, z) -> {
            double hills = 70 + 7 * Math.sin(x / 31.0) * Math.cos(z / 27.0) + 4 * Math.sin((x + z) / 13.0);
            double river = Math.abs(z + 14 * Math.sin(x / 60.0));
            if (river < 16) {
                hills = Math.min(hills, 57 + river * river / 16.0);
            }
            double ravine = Math.abs(x - 260 - 0.2 * z);
            if (ravine < 9) {
                hills -= 14 * (1 - ravine / 9.0);
            }
            return (int) Math.round(hills);
        });
    }

    static SyntheticRoadWorld flat(int height) {
        return new SyntheticRoadWorld((x, z) -> height);
    }

    int solidTop(int x, int z) {
        return ground.applyAsInt(x, z);
    }

    @Override
    public int terrainY(int x, int z) {
        return Math.max(solidTop(x, z), SEA);
    }

    @Override
    public int oceanFloorY(int x, int z) {
        return solidTop(x, z);
    }

    @Override
    public boolean isWater(int x, int y, int z) {
        return y >= solidTop(x, z) && y < SEA;
    }

    @Override
    public BlockState groundCover(int x, int z) {
        return natural(x, solidTop(x, z) - 1, z);
    }

    BlockState natural(int x, int y, int z) {
        int top = solidTop(x, z);
        if (y >= top) {
            return y < SEA ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        if (y == top - 1) {
            return top <= SEA + 1 ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
        }
        return y >= top - 4 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    void apply(List<BuildStep> steps) {
        for (BuildStep step : steps) {
            placed.put(step.pos(), step.state());
        }
    }

    BlockState block(int x, int y, int z) {
        BlockState state = placed.get(new BlockPos(x, y, z));
        return state != null ? state : natural(x, y, z);
    }

    /** Height a player would stand at in this column, in half blocks, scanning down from {@code fromY}. */
    int walkableHalves(int x, int z, int fromY) {
        for (int y = fromY; y > fromY - 64; y--) {
            BlockState state = block(x, y, z);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            boolean bottomSlab = state.getBlock() instanceof SlabBlock
                    && state.getValue(SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
            return y * 2 + (bottomSlab ? 1 : 2);
        }
        return Integer.MIN_VALUE;
    }
}
