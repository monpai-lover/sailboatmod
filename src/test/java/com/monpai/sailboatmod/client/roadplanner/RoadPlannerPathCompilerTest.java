package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerPathCompilerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void interpolatesBetweenSparsePlannerNodes() {
        RoadPlannerCompiledPath compiled = RoadPlannerPathCompiler.compile(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                new RoadPlannerBuildSettings(3, "smooth_stone", false)
        );

        assertEquals(9, compiled.centers().size());
        assertTrue(compiled.blocks().stream().anyMatch(block -> block.pos().equals(new BlockPos(4, 64, 0))));
    }

    @Test
    void usesSelectedWidthAndPlacesLightsByDistance() {
        RoadPlannerCompiledPath compiled = RoadPlannerPathCompiler.compile(
                List.of(new BlockPos(0, 64, 0), new BlockPos(32, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                new RoadPlannerBuildSettings(7, "stone_bricks", true)
        );

        assertTrue(compiled.blocks().stream().anyMatch(block -> block.pos().equals(new BlockPos(16, 64, 3))));
        assertTrue(compiled.lights().size() >= 4);
    }
}
