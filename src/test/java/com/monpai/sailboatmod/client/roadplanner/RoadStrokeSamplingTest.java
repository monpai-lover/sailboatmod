package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadStrokeSamplingTest {
    @Test
    void dragSamplingEmitsNodesEveryFourToEightBlocks() {
        RoadMapRegion region = RoadMapRegion.centeredOn(new BlockPos(64, 70, 64), 128, MapLod.LOD_1);
        RoadMapViewport viewport = new RoadMapViewport(0, 0, 128, 128);
        RoadStrokeSampler sampler = new RoadStrokeSampler(region, viewport, (x, z) -> 70 + x / 16);

        List<BlockPos> nodes = sampler.sampleDrag(0, 64, 32, 64, 4, 8);

        assertEquals(new BlockPos(0, 70, 64), nodes.get(0));
        assertEquals(new BlockPos(32, 72, 64), nodes.get(nodes.size() - 1));
        assertTrue(nodes.size() >= 5);
        for (int index = 1; index < nodes.size(); index++) {
            double distance = Math.sqrt(nodes.get(index).distSqr(nodes.get(index - 1)));
            assertTrue(distance >= 4.0D || index == nodes.size() - 1);
            assertTrue(distance <= 8.25D);
        }
    }
}
