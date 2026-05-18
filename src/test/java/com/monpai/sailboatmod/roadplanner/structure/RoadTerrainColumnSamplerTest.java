package com.monpai.sailboatmod.roadplanner.structure;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadTerrainColumnSamplerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void terrainHeightSkipsTreeBlocksAndReturnsRoadPlacementY() {
        Map<Integer, BlockState> column = Map.of(
                81, Blocks.OAK_LEAVES.defaultBlockState(),
                80, Blocks.OAK_LOG.defaultBlockState(),
                79, Blocks.OAK_LOG.defaultBlockState(),
                63, Blocks.GRASS_BLOCK.defaultBlockState()
        );

        int terrainY = RoadTerrainColumnSampler.terrainYFromColumn(82, -64, y -> column.getOrDefault(y, Blocks.AIR.defaultBlockState()));

        assertEquals(64, terrainY);
    }

    @Test
    void waterSurfaceIgnoresTreeBlocksAndOnlyReportsActualWater() {
        Map<Integer, BlockState> forestColumn = Map.of(
                81, Blocks.OAK_LEAVES.defaultBlockState(),
                80, Blocks.OAK_LOG.defaultBlockState(),
                63, Blocks.GRASS_BLOCK.defaultBlockState()
        );
        Map<Integer, BlockState> waterColumn = Map.of(
                64, Blocks.KELP.defaultBlockState(),
                63, Blocks.WATER.defaultBlockState(),
                54, Blocks.SAND.defaultBlockState()
        );

        assertEquals(63, RoadTerrainColumnSampler.waterSurfaceYFromColumn(82, 54, 63,
                y -> forestColumn.getOrDefault(y, Blocks.AIR.defaultBlockState())));
        assertEquals(63, RoadTerrainColumnSampler.waterSurfaceYFromColumn(65, 54, 63,
                y -> waterColumn.getOrDefault(y, Blocks.AIR.defaultBlockState())));
    }
}
