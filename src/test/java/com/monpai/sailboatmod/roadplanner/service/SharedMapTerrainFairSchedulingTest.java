package com.monpai.sailboatmod.roadplanner.service;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapTerrainFairSchedulingTest {
    @Test void largeCoarseTilesCannotMonopolizeTheWholeViewportBudget() {
        List<Integer> tiles = new ArrayList<>();
        for (int i = 0; i < 24; i++) tiles.add(i);
        Map<Integer, Long> advanced = new HashMap<>();
        for (int tick = 1; tick <= 6; tick++) {
            List<Integer> selected = SharedMapTerrainService.fairPending(tiles, tile -> true,
                    tile -> advanced.getOrDefault(tile, Long.MIN_VALUE), 4);
            assertEquals(4, selected.size());
            if (tick == 1) assertEquals(List.of(23, 22, 21, 20), selected, "newest tiles receive the first slice");
            for (int tile : selected) advanced.put(tile, (long) tick);
        }
        assertEquals(24, advanced.size(), "65536-pixel coarse jobs must share the budget rather than starving all older visible tiles");
    }
}
