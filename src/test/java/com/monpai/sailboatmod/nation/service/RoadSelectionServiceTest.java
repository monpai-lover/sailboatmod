package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadSelectionServiceTest {
    @Test
    void selectsRoadIdFromLookedAtOwnedBlock() {
        String roadId = RoadSelectionService.findRoadIdFromHitForTest(
                new BlockPos(10, 65, 10),
                Map.of(
                        "manual|town:a|town:b", List.of(new BlockPos(10, 65, 10), new BlockPos(11, 65, 10))
                )
        );

        assertEquals("manual|town:a|town:b", roadId);
    }
}
