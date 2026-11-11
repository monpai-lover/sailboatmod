package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerStagedPlanTest {
    @Test
    void routeBeyondSixThousandChunksRetainsOnlySubmittedNodesAndProducesBoundedWindows() throws Exception {
        Object plan = plan(List.of(new BlockPos(0, 64, 0), new BlockPos(200_000, 70, 0)),
                List.of(RoadPlannerSegmentType.ROAD));
        assertEquals(2, call(plan, "storedNodeCount"));
        assertEquals(1563, call(plan, "totalStages"));
        Object first = stage(plan, 0);
        assertEquals(new BlockPos(0, 64, 0), call(first, "coreFrom"));
        assertEquals(128, ((BlockPos) call(first, "coreTo")).getX());
        assertEquals(128 + 120, ((BlockPos) call(first, "sampleTo")).getX());
        assertTrue(((List<?>) call(first, "chunks")).size() <= 128,
                "only one bounded sampling window may be enumerated, never the whole remote corridor");
        Object last = stage(plan, 1562);
        assertEquals(new BlockPos(200_000, 70, 0), call(last, "coreTo"));
    }

    @Test
    void intermediateBridgeWindowsKeepTheOriginalGlobalAnchors() throws Exception {
        BlockPos from = new BlockPos(-40_000, 67, -32);
        BlockPos to = new BlockPos(40_000, 69, -32);
        Object plan = plan(List.of(from, to), List.of(RoadPlannerSegmentType.BRIDGE_MAJOR));
        Object middle = stage(plan, 312);
        assertEquals(from, call(middle, "spanFrom"));
        assertEquals(to, call(middle, "spanTo"));
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, call(middle, "type"));
        assertTrue(((BlockPos) call(middle, "sampleFrom")).distManhattan((BlockPos) call(middle, "sampleTo")) <= 128 + 2 * 120 + 2);
    }

    @Test
    void turnsAndRepeatedRoadLegsAreVisitedLazilyInSubmittedOrder() throws Exception {
        BlockPos start = new BlockPos(-16, 64, -16);
        BlockPos turn = new BlockPos(112, 64, -16);
        Object plan = plan(List.of(start, turn, start), List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.TUNNEL));
        assertEquals(2, call(plan, "totalStages"));
        assertEquals(turn, call(stage(plan, 0), "coreTo"));
        assertEquals(turn, call(stage(plan, 1), "coreFrom"));
        assertEquals(start, call(stage(plan, 1), "coreTo"));
        assertEquals(RoadPlannerSegmentType.TUNNEL, call(stage(plan, 1), "type"));
    }

    @Test
    void bridgeWindowIncludesTypedRoadApproachesWithoutResettingItsOriginalAnchors() throws Exception {
        Object plan = plan(List.of(new BlockPos(-40, 64, 0), new BlockPos(0, 64, 0),
                new BlockPos(100, 64, 0), new BlockPos(140, 64, 0)), List.of(RoadPlannerSegmentType.ROAD,
                RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.ROAD));
        Object bridge = stage(plan, 1);
        assertEquals(List.of(new BlockPos(-40, 64, 0), new BlockPos(0, 64, 0),
                new BlockPos(100, 64, 0), new BlockPos(140, 64, 0)), call(bridge, "nodes"));
        assertEquals(List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR,
                RoadPlannerSegmentType.ROAD), call(bridge, "types"));
    }

    @Test
    void tellusRoadBeyondVanillaThirtyMillionStillFitsPackedBlockPositions() {
        assertDoesNotThrow(() -> plan(List.of(new BlockPos(33_431_923, 64, 0), new BlockPos(33_471_923, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD)));
    }

    @Test
    void neighbouringStagesShareABoundaryAndTheEndStagesTakeEverythingBeyondTheRoute() throws Exception {
        Object plan = plan(List.of(new BlockPos(-40, 64, 0), new BlockPos(0, 64, 0),
                new BlockPos(400, 64, 0), new BlockPos(440, 64, 0)), List.of(RoadPlannerSegmentType.ROAD,
                RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.ROAD));
        Object approach = stage(plan, 0), firstBridge = stage(plan, 1), last = stage(plan, 5);
        assertEquals(Double.NEGATIVE_INFINITY, call(approach, "ownedFrom"));
        assertEquals(40.0, call(approach, "ownedTo"));
        assertEquals(40.0, call(firstBridge, "ownedFrom"));
        assertEquals(168.0, call(firstBridge, "ownedTo"));
        assertEquals(Double.POSITIVE_INFINITY, call(last, "ownedTo"));
    }

    @Test
    void shortLegsOfOneTypeShareAStageButNeverAcrossAChangeOfType() throws Exception {
        List<BlockPos> nodes = new java.util.ArrayList<>();
        List<RoadPlannerSegmentType> types = new java.util.ArrayList<>();
        for (int i = 0; i <= 20; i++) nodes.add(new BlockPos(i * 24, 64, 0));
        for (int i = 0; i < 20; i++) types.add(i == 10 ? RoadPlannerSegmentType.BRIDGE_SMALL : RoadPlannerSegmentType.ROAD);
        Object plan = plan(nodes, types);
        assertEquals(5, call(plan, "totalStages"), "five 24 block legs fill one 128 block stage");
        assertEquals(new BlockPos(0, 64, 0), call(stage(plan, 0), "coreFrom"));
        assertEquals(new BlockPos(120, 64, 0), call(stage(plan, 0), "coreTo"));
        assertEquals(new BlockPos(240, 64, 0), call(stage(plan, 1), "coreTo"));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, call(stage(plan, 2), "type"));
        assertEquals(new BlockPos(264, 64, 0), call(stage(plan, 2), "coreTo"));
        assertEquals(new BlockPos(480, 64, 0), call(stage(plan, 4), "coreTo"));
    }

    private static Object plan(List<BlockPos> nodes, List<RoadPlannerSegmentType> types) throws Exception {
        try {
            return Class.forName("com.monpai.sailboatmod.roadplanner.service.RoadPlannerStagedPlan")
                    .getConstructor(List.class, List.class).newInstance(nodes, types);
        } catch (ClassNotFoundException missing) {
            return fail("long road planning needs a lazy window plan instead of materializing/loading its entire corridor");
        }
    }

    private static Object stage(Object plan, int index) throws Exception {
        return plan.getClass().getMethod("stage", int.class).invoke(plan, index);
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }
}
