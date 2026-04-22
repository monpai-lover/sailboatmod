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

            // Skip water positions — bridges handle these
            if (cache.isWater(center.getX(), center.getZ())) {
                continue;
            }

            RoadMaterial material = materialSelector.select(materialPreset, cache.getBiome(center.getX(), center.getZ()));

            Direction roadDir = getDirection(centerPath, i);
            Direction perpDir = roadDir.getClockWise();

            int prevTerrainY = (i > 0) ? cache.getHeight(centerPath.get(i - 1).getX(), centerPath.get(i - 1).getZ()) : terrainY;
            int heightDiff = terrainY - prevTerrainY;

            // Collect positions: perpendicular expansion + Bresenham gap fill + acute angle fan
            List<int[]> segPositions = new ArrayList<>();
            // Primary: perpendicular to road direction
            for (int w = -halfWidth; w <= halfWidth; w++) {
                segPositions.add(new int[]{center.getX() + perpDir.getStepX() * w, center.getZ() + perpDir.getStepZ() * w});
            }

            // Bresenham fill between previous and current perpendicular edges
            if (i > 0) {
                BlockPos prev = centerPath.get(i - 1);
                Direction prevDir = getDirection(centerPath, i - 1);
                Direction prevPerp = prevDir.getClockWise();
                for (int w = -halfWidth; w <= halfWidth; w++) {
                    int px = prev.getX() + prevPerp.getStepX() * w;
                    int pz = prev.getZ() + prevPerp.getStepZ() * w;
                    int cx = center.getX() + perpDir.getStepX() * w;
                    int cz = center.getZ() + perpDir.getStepZ() * w;
                    int ddx = Math.abs(cx - px), ddz = Math.abs(cz - pz);
                    if (ddx > 1 || ddz > 1) {
                        int sx = px < cx ? 1 : -1, sz = pz < cz ? 1 : -1;
                        int err = ddx - ddz;
                        int fx = px, fz = pz;
                        while (fx != cx || fz != cz) {
                            segPositions.add(new int[]{fx, fz});
                            int e2 = 2 * err;
                            if (e2 > -ddz) { err -= ddz; fx += sx; }
                            if (e2 < ddx) { err += ddx; fz += sz; }
                        }
                    }
                }

                // Acute angle detection: if direction changed sharply, fill the inner corner
                if (prevDir != roadDir) {
                    // Fill a diamond/circle at the turn point to cover inner corner gaps
                    for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                        for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                            if (Math.abs(dx) + Math.abs(dz) <= halfWidth + 1) {
                                segPositions.add(new int[]{center.getX() + dx, center.getZ() + dz});
                            }
                        }
                    }
                }
            }

            // Place blocks for all positions in this segment
            for (int[] xz : segPositions) {
                long key = ((long) xz[0] << 32) | (xz[1] & 0xFFFFFFFFL);
                if (!placed.add(key)) continue; // Skip duplicates

                // Skip widened positions that fall into deep water
                if (cache.isWater(xz[0], xz[1]) && cache.getWaterDepth(xz[0], xz[1]) > 2) continue;

                int surfaceY;
                if (cache.isWater(xz[0], xz[1])) {
                    surfaceY = cache.getWaterSurfaceY(xz[0], xz[1]);
                } else {
                    surfaceY = cache.getHeight(xz[0], xz[1]);
                }
                BlockPos pos = new BlockPos(xz[0], surfaceY, xz[1]);

                // Clear obstacles above road surface up to tree canopy height
                int motionTop = cache.motionBlockingHeight(xz[0], xz[1]);
                int clearTop = Math.max(surfaceY + CLEAR_HEIGHT, motionTop);
                for (int h = surfaceY + 1; h <= clearTop; h++) {
                    steps.add(new BuildStep(order++, new BlockPos(xz[0], h, xz[1]), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
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
        int lookBack = Math.max(0, index - 4);
        int lookAhead = Math.min(path.size() - 1, index + 4);
        BlockPos prev = path.get(lookBack);
        BlockPos next = path.get(lookAhead);
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
