package com.monpai.sailboatmod.roadplanner.bridge;

import com.monpai.sailboatmod.roadplanner.build.RoadBuildStep;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadPath;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSection;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverRoadSpan;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PierBridgeToolAdapterTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void explicitBridgeSectionGeneratesPierDeckRampRailingAndInvisibleClearingSteps() {
        UUID edgeId = UUID.randomUUID();
        CompiledRoadPath path = new CompiledRoadPath(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 68, 0), new BlockPos(16, 64, 0)),
                List.of(new CompiledRoadSection(UUID.randomUUID(), CompiledRoadSectionType.BRIDGE, RoadToolType.BRIDGE,
                        List.of(new BlockPos(0, 64, 0), new BlockPos(8, 68, 0), new BlockPos(16, 64, 0)), 5)),
                List.<WeaverRoadSpan>of(),
                List.of(),
                List.of()
        );

        List<RoadBuildStep> steps = new PierBridgeToolAdapter(Blocks.STONE_BRICKS.defaultBlockState()).build(edgeId, path);

        assertTrue(steps.stream().anyMatch(step -> step.phase() == RoadBuildStep.Phase.BRIDGE_DECK));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == RoadBuildStep.Phase.BRIDGE_PIER));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == RoadBuildStep.Phase.BRIDGE_RAMP));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == RoadBuildStep.Phase.BRIDGE_RAILING));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == RoadBuildStep.Phase.CLEAR_TO_SKY && !step.visible()));
    }
}
