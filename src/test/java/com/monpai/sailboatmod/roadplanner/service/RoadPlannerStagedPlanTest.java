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
        assertEquals(160, ((BlockPos) call(first, "sampleTo")).getX());
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
        assertTrue(((BlockPos) call(middle, "sampleFrom")).distManhattan((BlockPos) call(middle, "sampleTo")) <= 194);
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
        assertEquals(List.of(new BlockPos(-32, 64, 0), new BlockPos(0, 64, 0),
                new BlockPos(100, 64, 0), new BlockPos(132, 64, 0)), call(bridge, "nodes"));
        assertEquals(List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR,
                RoadPlannerSegmentType.ROAD), call(bridge, "types"));
    }

    @Test
    void tellusRoadBeyondVanillaThirtyMillionStillFitsPackedBlockPositions() {
        assertDoesNotThrow(() -> plan(List.of(new BlockPos(33_431_923, 64, 0), new BlockPos(33_471_923, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD)));
    }

    @Test
    void typedHaloDoesNotExecuteAlreadyOwnedPreviousOrFutureLegColumns() throws Exception {
        Object plan = plan(List.of(new BlockPos(-40, 64, 0), new BlockPos(0, 64, 0),
                new BlockPos(400, 64, 0), new BlockPos(440, 64, 0)), List.of(RoadPlannerSegmentType.ROAD,
                RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.ROAD));
        Object approach = stage(plan, 0), firstBridge = stage(plan, 1);
        assertEquals(false, approach.getClass().getMethod("ownsColumn", int.class, int.class).invoke(approach, 10, 0));
        assertEquals(false, firstBridge.getClass().getMethod("ownsColumn", int.class, int.class).invoke(firstBridge, -1, 0));
        assertEquals(true, firstBridge.getClass().getMethod("ownsColumn", int.class, int.class).invoke(firstBridge, 0, 0));
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
