package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.List;

public class RoadSegmentPaver {
    private static final int MAX_FOUNDATION_DEPTH = 3;

    private final BiomeMaterialSelector materialSelector;

    public RoadSegmentPaver(BiomeMaterialSelector materialSelector) {
        this.materialSelector = materialSelector;
    }

    public List<BuildStep> pave(List<BlockPos> centerPath, int width, TerrainSamplingCache cache) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = 0;

        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos center = centerPath.get(i);
            RoadMaterial material = materialSelector.select(cache.getBiome(center.getX(), center.getZ()));

            Direction roadDir = getDirection(centerPath, i);
            Direction perpDir = roadDir.getClockWise();

            int prevY = (i > 0) ? centerPath.get(i - 1).getY() : center.getY();
            int heightDiff = center.getY() - prevY;

            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = center.relative(perpDir, w);

                for (int d = 1; d <= MAX_FOUNDATION_DEPTH; d++) {
                    BlockPos below = pos.below(d);
                    steps.add(new BuildStep(order++, below, Blocks.COBBLESTONE.defaultBlockState(), BuildPhase.FOUNDATION));
                }

                if (heightDiff > 0) {
                    BlockState stairState = material.stair().defaultBlockState()
                        .setValue(StairBlock.FACING, roadDir)
                        .setValue(StairBlock.HALF, Half.BOTTOM);
                    steps.add(new BuildStep(order++, pos, stairState, BuildPhase.SURFACE));
                } else if (heightDiff < 0) {
                    BlockState stairState = material.stair().defaultBlockState()
                        .setValue(StairBlock.FACING, roadDir.getOpposite())
                        .setValue(StairBlock.HALF, Half.BOTTOM);
                    steps.add(new BuildStep(order++, pos, stairState, BuildPhase.SURFACE));
                } else {
                    steps.add(new BuildStep(order++, pos, material.surface().defaultBlockState(), BuildPhase.SURFACE));
                }

                if (width >= 5 && (w == -halfWidth || w == halfWidth)) {
                    BlockState slabState = material.slab().defaultBlockState()
                        .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
                    steps.add(new BuildStep(order++, pos, slabState, BuildPhase.SURFACE));
                }
            }

            if (width >= 7) {
                BlockPos leftRail = center.relative(perpDir, -(halfWidth + 1));
                BlockPos rightRail = center.relative(perpDir, halfWidth + 1);
                if (i % 4 == 0) {
                    steps.add(new BuildStep(order++, leftRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
                    steps.add(new BuildStep(order++, rightRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
                }
            }
        }
        return steps;
    }

    private Direction getDirection(List<BlockPos> path, int index) {
        BlockPos curr = path.get(index);
        BlockPos next = (index < path.size() - 1) ? path.get(index + 1) : path.get(index);
        BlockPos prev = (index > 0) ? path.get(index - 1) : curr;
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
