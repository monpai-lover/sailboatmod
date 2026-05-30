package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSegmentPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerBuiltRoadMapRefreshTest {
    @Test
    void derivesUniqueAffectedChunksFromBuildSteps() {
        List<BuildStep> steps = List.of(
                new BuildStep(0, new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState(), BuildPhase.SURFACE),
                new BuildStep(1, new BlockPos(15, 64, 15), Blocks.STONE.defaultBlockState(), BuildPhase.SURFACE),
                new BuildStep(2, new BlockPos(16, 64, -1), Blocks.STONE.defaultBlockState(), BuildPhase.DECK)
        );

        Set<ChunkPos> chunks = RoadPlannerBuiltRoadMapRefresh.chunksForBuildSteps(steps);

        assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, -1)), chunks);
    }

    @Test
    void chunksForGraphPlacementsIncludesFootprintPositions() {
        List<RoadGraphSegmentPlacement> placements = List.of(
                new RoadGraphSegmentPlacement(new BlockPos(0, 64, 0),
                        List.of(new BlockPos(0, 64, 0), new BlockPos(17, 64, 0)))
        );

        Set<ChunkPos> chunks = RoadPlannerBuiltRoadMapRefresh.chunksForGraphPlacements(placements);

        assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), chunks);
    }
}
