package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadConstructionSupportTest {
    @Test
    void rejectsBridgeRunThatExceedsContiguousLimit() {
        RoadBridgeBudgetState budget = RoadBridgeBudgetState.empty();

        budget = budget.advance(true, 3, 8);
        budget = budget.advance(true, 3, 8);
        budget = budget.advance(true, 3, 8);
        RoadBridgeBudgetState rejected = budget.advance(true, 3, 8);

        assertFalse(rejected.accepted());
        assertEquals(4, rejected.contiguousBridgeColumns());
        assertEquals(4, rejected.totalBridgeColumns());
    }

    @Test
    void landStepResetsContiguousBridgeBudget() {
        RoadBridgeBudgetState budget = RoadBridgeBudgetState.empty()
                .advance(true, 3, 8)
                .advance(true, 3, 8)
                .advance(false, 3, 8);

        assertTrue(budget.accepted());
        assertEquals(0, budget.contiguousBridgeColumns());
        assertEquals(2, budget.totalBridgeColumns());
        assertTrue(budget.advance(true, 3, 8).accepted());
    }

    @Test
    void selectsNearestRoadPreviewForRuntimeProgressOverlay() {
        RoadRuntimeProgressSelection selected = RoadRuntimeProgressSelector.pickNearest(
                new BlockPos(10, 64, 10),
                List.of(
                        new RoadRuntimeProgressSelection("far", new BlockPos(30, 64, 30), "A", "B", 25, 1),
                        new RoadRuntimeProgressSelection("near", new BlockPos(12, 64, 11), "C", "D", 65, 3)
                )
        );

        assertNotNull(selected);
        assertEquals("near", selected.jobId());
        assertEquals(65, selected.progressPercent());
        assertEquals(3, selected.activeWorkers());
    }
}
