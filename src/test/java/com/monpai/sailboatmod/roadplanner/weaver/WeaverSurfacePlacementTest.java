package com.monpai.sailboatmod.roadplanner.weaver;

import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverClearancePlanner;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeaverSurfacePlacementTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void widthFivePaverEmitsCenterAndSidePositions() {
        List<BlockPos> centers = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));

        List<WeaverBuildCandidate> candidates = WeaverSegmentPaver.paveCenterline(centers, 5, Blocks.STONE_BRICKS.defaultBlockState());

        assertTrue(candidates.stream().anyMatch(candidate -> candidate.pos().equals(new BlockPos(0, 64, 0))));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.pos().equals(new BlockPos(0, 64, -2))));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.pos().equals(new BlockPos(0, 64, 2))));
        assertEquals(10, candidates.size());
        assertTrue(candidates.stream().allMatch(WeaverBuildCandidate::visible));
    }

    @Test
    void clearToSkyEmitsInvisibleAirStepsAboveFootprint() {
        List<BlockPos> footprint = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));

        List<WeaverBuildCandidate> candidates = WeaverClearancePlanner.clearToSky(footprint, 68);

        assertEquals(8, candidates.size());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.state().is(Blocks.AIR)));
        assertTrue(candidates.stream().noneMatch(WeaverBuildCandidate::visible));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.pos().equals(new BlockPos(0, 65, 0))));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.pos().equals(new BlockPos(1, 68, 0))));
        assertFalse(candidates.stream().anyMatch(candidate -> candidate.pos().getY() <= 64));
    }
}
