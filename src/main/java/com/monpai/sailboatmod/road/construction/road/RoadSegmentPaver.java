package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import com.monpai.sailboatmod.road.model.RoadSegmentPlacement;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.post.RoadHeightInterpolator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class RoadSegmentPaver {
    private static final int MAX_FOUNDATION_DEPTH = 3;
    private static final int CLEAR_HEIGHT = 4;

    private final BiomeMaterialSelector materialSelector;

    public RoadSegmentPaver(BiomeMaterialSelector materialSelector) {
        this.materialSelector = materialSelector;
    }

    public List<BuildStep> pave(List<RoadSegmentPlacement> placements,
                                 TerrainSamplingCache cache, String materialPreset) {
        List<BlockPos> centers = placements == null ? List.of() : placements.stream()
                .map(RoadSegmentPlacement::center)
                .toList();
        int[] targetY = centers.stream().mapToInt(BlockPos::getY).toArray();
        List<BuildStep> steps = new ArrayList<>();
        int order = 0;
        if (placements != null) {
            for (RoadSegmentPlacement placement : placements) {
                List<BuildStep> segmentSteps = paveSegment(placement, centers, targetY, cache, materialPreset, order);
                steps.addAll(segmentSteps);
                order += segmentSteps.size();
            }
        }
        return steps;
    }

    public List<BuildStep> paveSegment(RoadSegmentPlacement placement,
                                       List<BlockPos> centerPath,
                                       int[] targetY,
                                       TerrainSamplingCache cache,
                                       String materialPreset,
                                       int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        if (placement == null || placement.bridge() || placement.positions().isEmpty()) {
            return steps;
        }
        RoadMaterial material = materialSelector.select(
                materialPreset, cache.getBiome(placement.center().getX(), placement.center().getZ()));
        int[] heights = RoadHeightInterpolator.batchInterpolate(
                placement.positions(), placement.segmentIndex(), centerPath, targetY);
        int order = startOrder;
        for (int i = 0; i < placement.positions().size(); i++) {
            BlockPos original = placement.positions().get(i);
            int x = original.getX();
            int z = original.getZ();
            if (cache.isWater(x, z) && cache.getWaterDepth(x, z) > 2) {
                continue;
            }
            int roadY = i < heights.length ? heights[i] : original.getY();
            BlockPos surfacePos = new BlockPos(x, roadY, z);
            int terrainY = cache.isWater(x, z) ? cache.getWaterSurfaceY(x, z) : cache.getHeight(x, z);
            int motionTop = cache.motionBlockingHeight(x, z);
            int clearTop = Math.max(roadY + CLEAR_HEIGHT, Math.max(motionTop, terrainY + 1));
            for (int h = roadY + 1; h <= clearTop; h++) {
                steps.add(new BuildStep(order++, new BlockPos(x, h, z),
                        Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
            }
            for (int depth = MAX_FOUNDATION_DEPTH; depth >= 1; depth--) {
                steps.add(new BuildStep(order++, surfacePos.below(depth),
                        Blocks.COBBLESTONE.defaultBlockState(), BuildPhase.FOUNDATION));
            }
            steps.add(new BuildStep(order++, surfacePos,
                    material.surface().defaultBlockState(), BuildPhase.SURFACE));
        }
        return steps;
    }
}