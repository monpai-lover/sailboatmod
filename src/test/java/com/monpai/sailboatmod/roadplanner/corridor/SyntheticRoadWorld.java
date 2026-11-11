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
    private final Map<Long, Integer> highestPlaced = new HashMap<>();

    SyntheticRoadWorld(IntBinaryOperator ground) {
        this.ground = ground;
    }

    /** Rolling hills, a broad river along z = 0 with a narrow side stream, and a dry gorge crossing x = 260. */
    static SyntheticRoadWorld hillsRiverAndRavine() {
        return new SyntheticRoadWorld((x, z) -> {
            double hills = 76 + 6 * Math.sin(x / 31.0) * Math.cos(z / 27.0) + 3 * Math.sin((x + z) / 13.0);
            double river = Math.abs(z + 14 * Math.sin(x / 60.0));
            if (river < 34) {
                hills = Math.min(hills, 54 + river * river / 40.0);
            }
            double stream = Math.abs(x - 40 - 0.6 * (z + 75));
            if (stream < 7 && z < -40) {
                hills = Math.min(hills, 60.5 + stream * 1.5);
            }
            double gorge = Math.abs(x - 260 - 0.2 * z);
            if (gorge < 11 && z > 40) {
                hills -= 15 * (1 - gorge * gorge / 121.0);
            }
            return (int) Math.round(hills);
        });
    }

    /**
     * Open country between two towns: a sea to the west, low rolling ground broken by knolls a
     * search has to step around, a lake on the direct line, and a gorge across the route.
     */
    static SyntheticRoadWorld coastalCountry() {
        return new SyntheticRoadWorld((x, z) -> {
            double land = 68 + 3 * Math.sin(x / 47.0 + 1) * Math.cos(z / 39.0) + 1.5 * Math.sin((x - z) / 17.0);
            for (double[] knoll : new double[][]{{150, 120, 26, 16}, {230, 260, 30, 20}, {120, 420, 28, 18}, {250, 560, 34, 22}}) {
                double d = Math.hypot(x - knoll[0], z - knoll[1]) / knoll[2];
                if (d < 1) {
                    land += knoll[3] * (1 - d * d);
                }
            }
            double coast = 60 + 25 * Math.sin(z / 110.0) + 12 * Math.sin(z / 37.0);
            if (x < coast) {
                land = Math.min(land, 62.5 - (coast - x) * 0.35);
            } else if (x < coast + 12) {
                land = Math.min(land, 63 + (x - coast) * 0.5);
            }
            double lake = Math.hypot((x - 205) / 46.0, (z - 360) / 30.0);
            if (lake < 1) {
                land = Math.min(land, 63.4 - 9 * (1 - lake));
            }
            double gorge = Math.abs(z - 480 - 0.25 * (x - 180));
            if (gorge < 11 && x > 70) {
                land -= 16 * (1 - gorge / 11.0);
            }
            return (int) Math.round(land);
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
            highestPlaced.merge(((long) step.pos().getX() << 32) | (step.pos().getZ() & 0xFFFFFFFFL), step.pos().getY(), Math::max);
        }
    }

    BlockState block(int x, int y, int z) {
        BlockState state = placed.get(new BlockPos(x, y, z));
        return state != null ? state : natural(x, y, z);
    }

    /** Y of the highest block in a column that is not air, natural or built. */
    int highestBlock(int x, int z) {
        int from = Math.max(terrainY(x, z), highestPlaced.getOrDefault(((long) x << 32) | (z & 0xFFFFFFFFL), Integer.MIN_VALUE));
        for (int y = from; y > from - 128; y--) {
            if (!block(x, y, z).isAir()) {
                return y;
            }
        }
        return from - 128;
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
