package com.monpai.sailboatmod.roadplanner.model;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlanModelTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void settingsAcceptOnlySupportedWidths() {
        assertEquals(3, new RoadSettings(3, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, true, true).width());
        assertEquals(5, new RoadSettings(5, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, true, true).width());
        assertEquals(7, new RoadSettings(7, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, true, true).width());
        assertThrows(IllegalArgumentException.class,
                () -> new RoadSettings(4, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, true, true));
    }

    @Test
    void segmentStoresImmutableStrokeNodes() {
        RoadNode first = new RoadNode(new BlockPos(0, 64, 0), 10L, NodeSource.MANUAL);
        RoadNode second = new RoadNode(new BlockPos(8, 64, 0), 11L, NodeSource.MANUAL);
        RoadStroke stroke = new RoadStroke(UUID.randomUUID(), RoadToolType.ROAD, List.of(first, second), RoadStrokeSettings.defaults());
        RoadSegment segment = new RoadSegment(0, new BlockPos(0, 64, 0), first.pos(), second.pos(), List.of(stroke), true);

        assertEquals(first.pos(), segment.entryPoint());
        assertEquals(second.pos(), segment.exitPoint());
        assertEquals(2, segment.strokes().get(0).nodes().size());
        assertThrows(UnsupportedOperationException.class, () -> segment.strokes().add(stroke));
    }

    @Test
    void planKnowsAllNodesInRegionOrder() {
        RoadNode a = new RoadNode(new BlockPos(0, 64, 0), 1L, NodeSource.MANUAL);
        RoadNode b = new RoadNode(new BlockPos(8, 64, 0), 2L, NodeSource.MANUAL);
        RoadNode c = new RoadNode(new BlockPos(16, 64, 0), 3L, NodeSource.MANUAL);
        RoadStroke firstStroke = new RoadStroke(UUID.randomUUID(), RoadToolType.ROAD, List.of(a, b), RoadStrokeSettings.defaults());
        RoadStroke secondStroke = new RoadStroke(UUID.randomUUID(), RoadToolType.BRIDGE, List.of(b, c), RoadStrokeSettings.defaults());
        RoadPlan plan = new RoadPlan(UUID.randomUUID(), a.pos(), c.pos(), List.of(
                new RoadSegment(0, new BlockPos(0, 64, 0), a.pos(), b.pos(), List.of(firstStroke), true),
                new RoadSegment(1, new BlockPos(128, 64, 0), b.pos(), c.pos(), List.of(secondStroke), true)
        ), RoadSettings.defaults());

        assertEquals(List.of(a, b, b, c), plan.nodesInOrder());
        assertTrue(plan.isConnectedToDestination());
    }
}
