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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RoadSegmentPaver {
    private static final int MAX_FOUNDATION_DEPTH = 3;
    private static final int CLEAR_HEIGHT = 4;

    private final BiomeMaterialSelector materialSelector;

    public RoadSegmentPaver(BiomeMaterialSelector materialSelector) {
        this.materialSelector = materialSelector;
    }

    public List<BuildStep> pave(List<BlockPos> centerPath, int width, TerrainSamplingCache cache) {
        return pave(centerPath, width, cache, "auto");
    }

    public List<BuildStep> pave(List<BlockPos> centerPath, int width, TerrainSamplingCache cache, String materialPreset) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = 0;
        Set<Long> placed = new HashSet<>();

        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos center = centerPath.get(i);
            int terrainY = cache.getHeight(center.getX(), center.getZ());
            RoadMaterial material = materialSelector.select(materialPreset, cache.getBiome(center.getX(), center.getZ()));

            Direction roadDir = getDirection(centerPath, i);
            Direction perpDir = roadDir.getClockWise();

            int prevTerrainY = (i > 0) ? cache.getHeight(centerPath.get(i - 1).getX(), centerPath.get(i - 1).getZ()) : terrainY;
            int heightDiff = terrainY - prevTerrainY;

            // Collect positions: perpendicular expansion + square fill for curve coverage
            List<int[]> segPositions = new ArrayList<>();
            // Primary: perpendicular to road direction
            for (int w = -halfWidth; w <= halfWidth; w++) {
                segPositions.add(new int[]{center.getX() + perpDir.getStepX() * w, center.getZ() + perpDir.getStepZ() * w});
            }
            // Square fill around center to cover sharp turn inner corners
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                    segPositions.add(new int[]{center.getX() + dx, center.getZ() + dz});
                }
            }

            // Place blocks for all positions in this segment
            for (int[] xz : segPositions) {
                long key = ((long) xz[0] << 32) | (xz[1] & 0xFFFFFFFFL);
                if (!placed.add(key)) continue; // Skip duplicates

                int surfaceY = cache.getHeight(xz[0], xz[1]);
                BlockPos pos = new BlockPos(xz[0], surfaceY, xz[1]);

                // Clear obstacles above road surface
                for (int h = 1; h <= CLEAR_HEIGHT; h++) {
                    steps.add(new BuildStep(order++, pos.above(h), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
                }

                // Convert grass to dirt below
                for (int d = 1; d <= 2; d++) {
                    steps.add(new BuildStep(order++, pos.below(d), Blocks.DIRT.defaultBlockState(), BuildPhase.FOUNDATION));
                }

                // Foundation fill
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
            }

            if (width >= 7) {
                int lx = center.getX() + perpDir.getStepX() * -(halfWidth + 1);
                int lz = center.getZ() + perpDir.getStepZ() * -(halfWidth + 1);
                int rx = center.getX() + perpDir.getStepX() * (halfWidth + 1);
                int rz = center.getZ() + perpDir.getStepZ() * (halfWidth + 1);
                BlockPos leftRail = new BlockPos(lx, cache.getHeight(lx, lz), lz);
                BlockPos rightRail = new BlockPos(rx, cache.getHeight(rx, rz), rz);
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
