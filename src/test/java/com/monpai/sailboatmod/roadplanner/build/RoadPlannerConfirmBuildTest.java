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

class RoadPlannerConfirmBuildTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void confirmBuildCreatesGraphEdgeQueueJobAndVisiblePreview() {
        UUID routeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CompiledRoadPath path = new CompiledRoadPath(
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                List.of(new CompiledRoadSection(UUID.randomUUID(), CompiledRoadSectionType.ROAD, RoadToolType.ROAD,
                        List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)), 5)),
                List.<WeaverRoadSpan>of(),
                List.of(),
                List.of(
                        new WeaverBuildCandidate(new BlockPos(0, 70, 0), Blocks.AIR.defaultBlockState(), false),
                        new WeaverBuildCandidate(new BlockPos(0, 64, 0), Blocks.STONE_BRICKS.defaultBlockState(), true)
                )
        );

        RoadPlannerConfirmBuildResult result = new RoadPlannerConfirmBuildService(new RoadBuildStepPlanner(Blocks.SMOOTH_STONE.defaultBlockState()))
                .confirm(routeId, actorId, path, "港口大道");

        assertEquals("港口大道", result.edge().metadata().roadName());
        assertEquals(RoadBuildJob.Status.QUEUED, result.job().status());
        assertTrue(result.job().steps().stream().allMatch(RoadBuildStep::rollbackRequired));
        assertEquals(List.of(new BlockPos(0, 64, 0)), result.visiblePreview().stream().map(RoadBuildStep::pos).toList());
    }
}
