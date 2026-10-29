package com.monpai.sailboatmod.route.water.lookahead;

import com.monpai.sailboatmod.route.water.RealBlockWaterMap;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookaheadRerouteTest {
    @Test
    void routesAroundShortWall() {
        RealBlockWaterMap map = LookaheadMaps.wall(40);
        BlockPos a = new BlockPos(64, 63, 20);
        BlockPos b = new BlockPos(128, 63, 20);

        List<BlockPos> path = LookaheadReroute.compute(map, a, b, 1);

        assertFalse(path.isEmpty());
        assertEquals(a.getX(), path.get(0).getX());
        assertEquals(a.getZ(), path.get(0).getZ());
        assertEquals(b.getX(), path.get(path.size() - 1).getX());
        assertEquals(b.getZ(), path.get(path.size() - 1).getZ());
        for (int i = 1; i < path.size(); i++) {
            assertTrue(map.segmentHullClear(path.get(i - 1), path.get(i), 1), "segment " + i + " crosses land");
        }
        assertTrue(path.size() < 20, "pulled path should be short, was " + path.size());
    }

    @Test
    void sealedWallHasNoDetour() {
        RealBlockWaterMap map = LookaheadMaps.wall(63);

        assertTrue(LookaheadReroute.compute(map, new BlockPos(64, 63, 20), new BlockPos(128, 63, 20), 1).isEmpty());
    }
}
