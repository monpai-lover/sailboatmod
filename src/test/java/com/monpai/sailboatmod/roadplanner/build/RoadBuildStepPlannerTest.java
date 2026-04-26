package com.monpai.sailboatmod.roadplanner.build;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadPath;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSection;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverRoadSpan;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadBuildStepPlannerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void previewCandidatesBecomeOrderedBuildStepsWithRollback() {
        UUID edgeId = UUID.randomUUID();
        BlockPos roadCenter = new BlockPos(0, 64, 0);
        BlockPos roadEdge = new BlockPos(2, 64, 0);
        BlockPos treeLeaf = new BlockPos(0, 72, 0);
        BlockPos bridgeDeck = new BlockPos(16, 70, 0);
        CompiledRoadPath path = new CompiledRoadPath(
                List.of(roadCenter, bridgeDeck),
                List.of(
                        new CompiledRoadSection(UUID.randomUUID(), CompiledRoadSectionType.ROAD, RoadToolType.ROAD, List.of(roadCenter), 5),
                        new CompiledRoadSection(UUID.randomUUID(), CompiledRoadSectionType.BRIDGE, RoadToolType.BRIDGE, List.of(bridgeDeck), 5)
                ),
                List.<WeaverRoadSpan>of(),
                List.of(),
                List.of(
                        new WeaverBuildCandidate(treeLeaf, Blocks.AIR.defaultBlockState(), false),
                        new WeaverBuildCandidate(roadCenter, Blocks.STONE_BRICKS.defaultBlockState(), true),
                        new WeaverBuildCandidate(roadEdge, Blocks.SMOOTH_STONE.defaultBlockState(), true),
                        new WeaverBuildCandidate(bridgeDeck, Blocks.OAK_PLANKS.defaultBlockState(), true)
                )
        );

        List<RoadBuildStep> steps = new RoadBuildStepPlanner(Blocks.SMOOTH_STONE.defaultBlockState()).plan(edgeId, path);

        assertEquals(RoadBuildStep.Phase.CLEAR_TO_SKY, steps.get(0).phase());
        assertEquals(RoadBuildStep.Phase.ROAD_SURFACE, steps.get(1).phase());
        assertEquals(RoadBuildStep.Phase.ROAD_EDGE, steps.get(2).phase());
        assertEquals(RoadBuildStep.Phase.BRIDGE_DECK, steps.get(3).phase());
        assertTrue(steps.stream().allMatch(RoadBuildStep::rollbackRequired));
        assertTrue(steps.stream().allMatch(step -> edgeId.equals(step.edgeId())));
    }
}
