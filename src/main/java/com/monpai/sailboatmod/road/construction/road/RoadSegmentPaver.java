package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import com.monpai.sailboatmod.road.model.RoadSegmentPlacement;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

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

    public List<BuildStep> pave(List<RoadSegmentPlacement> placements,
                                 TerrainSamplingCache cache, String materialPreset) {
        List<BuildStep> steps = new ArrayList<>();
        int order = 0;
        Set<Long> placed = new HashSet<>();

        for (RoadSegmentPlacement placement : placements) {
            if (placement.bridge()) continue;

            RoadMaterial material = materialSelector.select(
                    materialPreset, cache.getBiome(placement.center().getX(), placement.center().getZ()));

            for (BlockPos pos : placement.positions()) {
                int x = pos.getX(), z = pos.getZ();
                long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
                if (!placed.add(key)) continue;

                if (cache.isWater(x, z) && cache.getWaterDepth(x, z) > 2) continue;

                // Use the placement's Y (slope-limited) as road surface height
                int roadY = pos.getY();
                // Actual terrain height for cut-face clearing
                int terrainY = cache.isWater(x, z)
                        ? cache.getWaterSurfaceY(x, z)
                        : cache.getHeight(x, z);
                int surfaceY = Math.min(roadY, terrainY);
                BlockPos surfacePos = new BlockPos(x, surfaceY, z);

                // Clear everything above road surface up to terrain top + headroom
                int motionTop = cache.motionBlockingHeight(x, z);
                int clearTop = Math.max(surfaceY + CLEAR_HEIGHT, Math.max(motionTop, terrainY + 1));
                for (int h = surfaceY + 1; h <= clearTop; h++) {
                    steps.add(new BuildStep(order++, new BlockPos(x, h, z),
                            Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
                }

                for (int d = 1; d <= 2; d++) {
                    steps.add(new BuildStep(order++, surfacePos.below(d),
                            Blocks.DIRT.defaultBlockState(), BuildPhase.FOUNDATION));
                }
                for (int d = 1; d <= MAX_FOUNDATION_DEPTH; d++) {
                    steps.add(new BuildStep(order++, surfacePos.below(d),
                            Blocks.COBBLESTONE.defaultBlockState(), BuildPhase.FOUNDATION));
                }

                steps.add(new BuildStep(order++, surfacePos,
                        material.surface().defaultBlockState(), BuildPhase.SURFACE));
            }
        }
        return steps;
    }
}
