package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadBridgePierPlannerTest {
    @Test
    void choosesFlatDeckHeightFromWaterSurfacePlusFive() {
        List<RoadBridgePierPlanner.PierNode> nodes = RoadBridgePierPlanner.planPierNodes(
                List.of(
                        new RoadBridgePierPlanner.WaterColumn(new BlockPos(10, 62, 0), 63, 58, true, false),
                        new RoadBridgePierPlanner.WaterColumn(new BlockPos(13, 62, 0), 63, 57, true, false)
                ),
                5
        );

        assertEquals(68, nodes.get(0).deckY());
        assertEquals(68, nodes.get(1).deckY());
    }

    @Test
    void rejectsExcludedOrUnfoundedPierCandidates() {
        List<RoadBridgePierPlanner.PierNode> nodes = RoadBridgePierPlanner.planPierNodes(
                List.of(
                        new RoadBridgePierPlanner.WaterColumn(new BlockPos(20, 62, 0), 63, 58, true, true),
                        new RoadBridgePierPlanner.WaterColumn(new BlockPos(24, 62, 0), 63, Integer.MIN_VALUE, false, false)
                ),
                5
        );

        assertTrue(nodes.isEmpty());
    }

    @Test
    void onlyConnectsSpanPairsThatPreserveNavigableGapRules() {
        List<RoadBridgePierPlanner.PierNode> nodes = List.of(
                new RoadBridgePierPlanner.PierNode(new BlockPos(0, 58, 0), 63, 68),
                new RoadBridgePierPlanner.PierNode(new BlockPos(4, 57, 0), 63, 68),
                new RoadBridgePierPlanner.PierNode(new BlockPos(11, 57, 0), 63, 68)
        );

        List<RoadBridgePierPlanner.PierSpan> spans = RoadBridgePierPlanner.connect(nodes, 6);

        assertTrue(spans.stream().anyMatch(span -> span.fromIndex() == 0 && span.toIndex() == 1));
        assertFalse(spans.stream().anyMatch(span -> span.fromIndex() == 0 && span.toIndex() == 2));
    }
}
