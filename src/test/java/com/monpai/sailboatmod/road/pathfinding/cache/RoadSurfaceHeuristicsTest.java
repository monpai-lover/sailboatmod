package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadSurfaceHeuristicsTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ignoresTreeAndNaturalNoiseBlocks() {
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.OAK_LOG.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.OAK_WOOD.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.OAK_LEAVES.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.CRIMSON_STEM.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.WARPED_HYPHAE.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.MUSHROOM_STEM.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.BAMBOO.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.KELP.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.SEAGRASS.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.SNOW_BLOCK.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.LILY_PAD.defaultBlockState()));
    }

    @Test
    void treeBlocksAreNotRoadBearingSurfaces() {
        assertFalse(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.OAK_LEAVES.defaultBlockState()));
        assertFalse(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.KELP.defaultBlockState()));
        assertFalse(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.SNOW_BLOCK.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.STONE.defaultBlockState()));
    }
}
