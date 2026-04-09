package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadRouteNodePlannerTest {
    @Test
    void prefersDryFlatDetourOverShortWetClimb() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                pos -> {
                    int y = 64;
                    if (pos.getZ() == 0 && pos.getX() >= 3 && pos.getX() <= 5) {
                        y += pos.getX() - 2;
                    }
                    return new RoadRouteNodePlanner.RouteColumn(
                            new BlockPos(pos.getX(), y, pos.getZ()),
                            false,
                            pos.getZ() == 0 && pos.getX() >= 3 && pos.getX() <= 5,
                            pos.getZ() == 0 ? 3 : 0,
                            pos.getZ() == 0 ? 4 : 0,
                            pos.getZ() == 2
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertTrue(plan.path().contains(new BlockPos(4, 64, 2)));
        assertFalse(plan.path().contains(new BlockPos(4, 66, 0)));
        assertFalse(plan.usedBridge());
    }

    @Test
    void allowsShortBridgeOnlyAfterLandOnlySearchFails() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(6, 64, 0),
                pos -> {
                    if (pos.getZ() != 0) {
                        return new RoadRouteNodePlanner.RouteColumn(null, true, false, 0, 0, false);
                    }
                    return new RoadRouteNodePlanner.RouteColumn(
                            new BlockPos(pos.getX(), 64, 0),
                            false,
                            pos.getX() >= 2 && pos.getX() <= 3,
                            0,
                            0,
                            false
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0)
        ), plan.path());
        assertTrue(plan.usedBridge());
        assertEquals(2, plan.totalBridgeColumns());
        assertEquals(2, plan.longestBridgeRun());
    }

    @Test
    void respectsBridgeBudgetLimitsWhenNoLandOnlyRouteExists() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(7, 64, 0),
                pos -> {
                    if (pos.getZ() != 0) {
                        return new RoadRouteNodePlanner.RouteColumn(null, true, false, 0, 0, false);
                    }
                    return new RoadRouteNodePlanner.RouteColumn(
                            new BlockPos(pos.getX(), 64, 0),
                            false,
                            pos.getX() >= 1 && pos.getX() <= 6,
                            0,
                            0,
                            false
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertTrue(plan.path().isEmpty());
    }

    @Test
    void bezierSmoothingSkipsBlockedColumnsAndUnsafeGeometry() {
        List<BlockPos> controlPoints = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(4, 64, 2),
                new BlockPos(6, 64, 2)
        );
        Set<Long> blockedColumns = Set.of(columnKey(3, 1));

        List<BlockPos> smoothed = RoadBezierCenterline.build(
                controlPoints,
                pos -> new RoadBezierCenterline.SurfaceSample(
                        switch (pos.getX()) {
                            case 0 -> new BlockPos(0, 64, 0);
                            case 1 -> new BlockPos(1, 64, 0);
                            case 2 -> new BlockPos(2, 64, 0);
                            case 3 -> pos.getZ() == 2 ? new BlockPos(3, 64, 2) : null;
                            case 4 -> new BlockPos(4, 64, 2);
                            case 5 -> new BlockPos(5, 64, 2);
                            case 6 -> new BlockPos(6, 64, 2);
                            default -> null;
                        },
                        pos.getX() == 3 && pos.getZ() == 1,
                        false
                ),
                blockedColumns
        );

        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 2),
                new BlockPos(4, 64, 2),
                new BlockPos(5, 64, 2),
                new BlockPos(6, 64, 2)
        ), smoothed);
        assertFalse(smoothed.contains(new BlockPos(3, 64, 1)));
        assertTrue(smoothed.stream().noneMatch(pos -> blockedColumns.contains(columnKey(pos.getX(), pos.getZ()))));
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
