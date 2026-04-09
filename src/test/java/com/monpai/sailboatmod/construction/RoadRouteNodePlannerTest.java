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
    void allowsShortBridgeOnlyAfterLandOnlySearchFailsWhenFinalShareStaysWithinBudget() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(10, 64, 0),
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
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(9, 64, 0),
                new BlockPos(10, 64, 0)
        ), plan.path());
        assertTrue(plan.usedBridge());
        assertEquals(2, plan.totalBridgeColumns());
        assertEquals(2, plan.longestBridgeRun());
    }

    @Test
    void rejectsBridgeFallbackWhenFinalBridgeShareWouldExceedTwentyPercent() {
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

        assertTrue(plan.path().isEmpty());
        assertFalse(plan.usedBridge());
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
    void searchBoundsAllowDryDetourBeyondDirectHalfSpan() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(12, 64, 0),
                pos -> {
                    boolean onWestLeg = pos.getX() == 0 && pos.getZ() >= 0 && pos.getZ() <= 10;
                    boolean onTopLeg = pos.getZ() == 10 && pos.getX() >= 0 && pos.getX() <= 12;
                    boolean onEastLeg = pos.getX() == 12 && pos.getZ() >= 0 && pos.getZ() <= 10;
                    boolean startOrEnd = pos.getZ() == 0 && (pos.getX() == 0 || pos.getX() == 12);
                    boolean traversable = onWestLeg || onTopLeg || onEastLeg || startOrEnd;
                    return new RoadRouteNodePlanner.RouteColumn(
                            traversable ? new BlockPos(pos.getX(), 64, pos.getZ()) : null,
                            !traversable,
                            false,
                            0,
                            0,
                            pos.getZ() == 10
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertFalse(plan.usedBridge());
        assertTrue(plan.path().contains(new BlockPos(6, 64, 10)));
    }

    @Test
    void searchBoundsAllowShortSpanDetourAroundLargeOffAxisObstacle() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(2, 64, 0),
                pos -> {
                    boolean onWestLeg = pos.getX() == 0 && pos.getZ() >= 0 && pos.getZ() <= 18;
                    boolean onTopLeg = pos.getZ() == 18 && pos.getX() >= 0 && pos.getX() <= 2;
                    boolean onEastLeg = pos.getX() == 2 && pos.getZ() >= 0 && pos.getZ() <= 18;
                    boolean startOrEnd = pos.getZ() == 0 && (pos.getX() == 0 || pos.getX() == 2);
                    boolean traversable = onWestLeg || onTopLeg || onEastLeg || startOrEnd;
                    return new RoadRouteNodePlanner.RouteColumn(
                            traversable ? new BlockPos(pos.getX(), 64, pos.getZ()) : null,
                            !traversable,
                            false,
                            0,
                            0,
                            pos.getZ() == 18
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertFalse(plan.usedBridge());
        assertTrue(plan.path().contains(new BlockPos(1, 64, 18)));
    }

    @Test
    void bezierSmoothingSkipsBlockedColumnsAndUnsafeGeometry() {
        List<BlockPos> routeNodes = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(2, 64, 1),
                new BlockPos(3, 64, 2),
                new BlockPos(4, 64, 2),
                new BlockPos(5, 64, 2),
                new BlockPos(6, 64, 2)
        );
        Set<Long> blockedColumns = Set.of(columnKey(3, 1));

        List<BlockPos> smoothed = RoadBezierCenterline.build(
                routeNodes,
                pos -> {
                    BlockPos surface = switch (pos.getX()) {
                        case 0 -> new BlockPos(0, 64, 0);
                        case 1 -> new BlockPos(1, 64, 0);
                        case 2 -> pos.getZ() == 1 ? new BlockPos(2, 64, 1) : new BlockPos(2, 64, 0);
                        case 3 -> pos.getZ() == 2 ? new BlockPos(3, 64, 2) : null;
                        case 4 -> new BlockPos(4, 64, 2);
                        case 5 -> new BlockPos(5, 64, 2);
                        case 6 -> new BlockPos(6, 64, 2);
                        default -> null;
                    };
                    return new RoadBezierCenterline.SurfaceSample(
                            surface,
                            pos.getX() == 3 && pos.getZ() == 1,
                            false,
                            0
                    );
                },
                blockedColumns
        );

        assertFalse(smoothed.isEmpty());
        assertTrue(smoothed.contains(new BlockPos(2, 64, 1)));
        assertTrue(smoothed.contains(new BlockPos(3, 64, 2)));
        assertFalse(smoothed.contains(new BlockPos(3, 64, 1)));
        assertTrue(smoothed.stream().noneMatch(pos -> blockedColumns.contains(columnKey(pos.getX(), pos.getZ()))));
        assertTrue(isContiguous(smoothed));
    }

    @Test
    void bezierSmoothingFallsBackWhenCurveWouldIntroduceBridgeColumns() {
        List<BlockPos> routeNodes = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 2),
                new BlockPos(4, 64, 2),
                new BlockPos(5, 64, 2),
                new BlockPos(6, 64, 2)
        );

        List<BlockPos> smoothed = RoadBezierCenterline.build(
                routeNodes,
                pos -> {
                    long key = columnKey(pos.getX(), pos.getZ());
                    BlockPos surface;
                    if (key == columnKey(0, 0)) {
                        surface = new BlockPos(0, 64, 0);
                    } else if (key == columnKey(1, 0)) {
                        surface = new BlockPos(1, 64, 0);
                    } else if (key == columnKey(2, 0)) {
                        surface = new BlockPos(2, 64, 0);
                    } else if (key == columnKey(3, 1)) {
                        surface = new BlockPos(3, 64, 1);
                    } else if (key == columnKey(3, 2)) {
                        surface = new BlockPos(3, 64, 2);
                    } else if (key == columnKey(4, 2)) {
                        surface = new BlockPos(4, 64, 2);
                    } else if (key == columnKey(5, 2)) {
                        surface = new BlockPos(5, 64, 2);
                    } else if (key == columnKey(6, 2)) {
                        surface = new BlockPos(6, 64, 2);
                    } else {
                        surface = null;
                    }
                    return new RoadBezierCenterline.SurfaceSample(
                            surface,
                            false,
                            pos.getX() == 3 && pos.getZ() == 1,
                            0
                    );
                },
                Set.of()
        );

        assertEquals(routeNodes, smoothed);
        assertFalse(smoothed.contains(new BlockPos(3, 64, 1)));
    }

    @Test
    void finalPathValidationRejectsBridgeRunLongerThanFive() {
        List<BlockPos> baseline = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(5, 64, 1),
                new BlockPos(6, 64, 1),
                new BlockPos(7, 64, 1)
        );
        List<BlockPos> candidate = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 1),
                new BlockPos(7, 64, 1)
        );

        boolean valid = RoadBezierCenterline.isValidCandidatePath(
                candidate,
                baseline,
                pos -> new RoadBezierCenterline.SurfaceSample(
                        new BlockPos(pos.getX(), 64, pos.getZ()),
                        false,
                        isBridgeColumn(pos),
                        0
                ),
                Set.of()
        );

        assertFalse(valid);
    }

    @Test
    void finalPathValidationRejectsNewNearWaterExposure() {
        List<BlockPos> baseline = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0)
        );
        List<BlockPos> candidate = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 1),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0)
        );

        boolean valid = RoadBezierCenterline.isValidCandidatePath(
                candidate,
                baseline,
                pos -> new RoadBezierCenterline.SurfaceSample(
                        new BlockPos(pos.getX(), 64, pos.getZ()),
                        false,
                        false,
                        pos.getX() == 2 && pos.getZ() == 1 ? 2 : 0
                ),
                Set.of()
        );

        assertFalse(valid);
    }

    @Test
    void finalPathValidationRejectsNonContiguousCandidate() {
        List<BlockPos> baseline = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0)
        );
        List<BlockPos> candidate = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0)
        );

        boolean valid = RoadBezierCenterline.isValidCandidatePath(
                candidate,
                baseline,
                pos -> new RoadBezierCenterline.SurfaceSample(
                        new BlockPos(pos.getX(), 64, pos.getZ()),
                        false,
                        false,
                        0
                ),
                Set.of()
        );

        assertFalse(valid);
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static boolean isBridgeColumn(BlockPos pos) {
        return (pos.getZ() == 0 && pos.getX() >= 1 && pos.getX() <= 5)
                || (pos.getZ() == 1 && pos.getX() >= 6 && pos.getX() <= 7);
    }

    private static boolean isContiguous(List<BlockPos> path) {
        for (int i = 1; i < path.size(); i++) {
            int dx = Math.abs(path.get(i).getX() - path.get(i - 1).getX());
            int dz = Math.abs(path.get(i).getZ() - path.get(i - 1).getZ());
            if (Math.max(dx, dz) != 1) {
                return false;
            }
        }
        return true;
    }
}
