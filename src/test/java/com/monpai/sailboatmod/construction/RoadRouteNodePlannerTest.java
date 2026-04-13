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
    void bridgeModeUsesPierNodesInsteadOfExploringWholeWaterSheet() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                pos -> new RoadRouteNodePlanner.RouteColumn(
                        new BlockPos(pos.getX(), 64, pos.getZ()),
                        pos.getZ() != 0,
                        pos.getX() >= 2 && pos.getX() <= 6,
                        4,
                        0,
                        false
                )
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.planWithBridgePiers(
                map,
                List.of(
                        new RoadBridgePierPlanner.PierNode(new BlockPos(2, 58, 0), 63, 68),
                        new RoadBridgePierPlanner.PierNode(new BlockPos(5, 58, 0), 63, 68)
                )
        );

        assertFalse(plan.path().isEmpty());
        assertTrue(plan.usedBridge());
    }

    @Test
    void bridgePierGraphUsesReachablePierAnchorsInsteadOfOnlyLinearRasterFallback() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(12, 64, 0),
                pos -> {
                    boolean bridge = pos.getX() >= 3 && pos.getX() <= 9;
                    return new RoadRouteNodePlanner.RouteColumn(
                            new BlockPos(pos.getX(), bridge ? 63 : 64, pos.getZ()),
                            false,
                            bridge,
                            bridge ? 2 : 0,
                            0,
                            !bridge
                    );
                }
        );

        List<RoadBridgePierPlanner.PierNode> piers = List.of(
                new RoadBridgePierPlanner.PierNode(new BlockPos(3, 58, 0), 63, 68),
                new RoadBridgePierPlanner.PierNode(new BlockPos(6, 58, 1), 63, 68),
                new RoadBridgePierPlanner.PierNode(new BlockPos(9, 58, 0), 63, 68)
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.planWithBridgePiers(map, piers);

        assertFalse(plan.path().isEmpty());
        assertTrue(plan.usedBridge());
    }

    @Test
    void bridgePierGraphCanSkipDetourPierThatWouldBreakLinearRasterChain() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(12, 64, 0),
                pos -> {
                    boolean traversable = pos.getZ() == 0 || (pos.getX() == 6 && pos.getZ() == 2);
                    boolean bridge = traversable && pos.getX() >= 3 && pos.getX() <= 9;
                    return new RoadRouteNodePlanner.RouteColumn(
                            traversable ? new BlockPos(pos.getX(), bridge ? 63 : 64, pos.getZ()) : null,
                            !traversable,
                            bridge,
                            bridge ? 2 : 0,
                            0,
                            pos.getZ() == 0
                    );
                }
        );

        List<RoadBridgePierPlanner.PierNode> piers = List.of(
                new RoadBridgePierPlanner.PierNode(new BlockPos(3, 58, 0), 63, 68),
                new RoadBridgePierPlanner.PierNode(new BlockPos(6, 58, 2), 63, 68),
                new RoadBridgePierPlanner.PierNode(new BlockPos(9, 58, 0), 63, 68)
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.planWithBridgePiers(map, piers);

        assertFalse(plan.path().isEmpty());
        assertTrue(plan.usedBridge());
        assertTrue(plan.path().contains(new BlockPos(9, 63, 0)));
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
    void allowsBridgeFallbackEvenWhenMostOfShortRouteCrossesWater() {
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
                            pos.getX() >= 2 && pos.getX() <= 4,
                            0,
                            0,
                            false
                        );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertTrue(plan.usedBridge());
        assertEquals(3, plan.totalBridgeColumns());
    }

    @Test
    void allowsLongContinuousBridgeWhenItIsTheOnlyConnection() {
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

        assertFalse(plan.path().isEmpty());
        assertTrue(plan.usedBridge());
        assertEquals(6, plan.totalBridgeColumns());
        assertEquals(6, plan.longestBridgeRun());
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
    void longStraightDryRouteStillCompletesWithinPlannerBudget() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(400, 64, 0),
                pos -> new RoadRouteNodePlanner.RouteColumn(
                        new BlockPos(pos.getX(), 64, pos.getZ()),
                        false,
                        false,
                        0,
                        0,
                        pos.getZ() == 0
                )
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertEquals(new BlockPos(0, 64, 0), plan.path().get(0));
        assertEquals(new BlockPos(400, 64, 0), plan.path().get(plan.path().size() - 1));
        assertFalse(plan.usedBridge());
    }

    @Test
    void acceptsRiverbankTransitionWithSevenBlockRise() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 71, 0),
                pos -> {
                    if (pos.getZ() != 0) {
                        return new RoadRouteNodePlanner.RouteColumn(null, true, false, 0, 0, false);
                    }
                    int y = pos.getX() <= 1 ? 64 : 71;
                    boolean bridge = pos.getX() == 2;
                    return new RoadRouteNodePlanner.RouteColumn(
                            new BlockPos(pos.getX(), y, 0),
                            false,
                            bridge,
                            bridge ? 2 : 0,
                            bridge ? 1 : 0,
                            !bridge
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertEquals(new BlockPos(0, 64, 0), plan.path().get(0));
        assertEquals(new BlockPos(4, 71, 0), plan.path().get(plan.path().size() - 1));
        assertTrue(plan.path().contains(new BlockPos(2, 71, 0)));
    }

    @Test
    void acceptsTenBlockRiverbankTransitionWhenBridgeIsOnlyConnection() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(5, 74, 0),
                pos -> {
                    if (pos.getZ() != 0) {
                        return new RoadRouteNodePlanner.RouteColumn(null, true, false, 0, 0, false);
                    }
                    int y = pos.getX() <= 1 ? 64 : 74;
                    boolean bridge = pos.getX() >= 2 && pos.getX() <= 4;
                    return new RoadRouteNodePlanner.RouteColumn(
                            new BlockPos(pos.getX(), y, 0),
                            false,
                            bridge,
                            bridge ? 2 : 0,
                            bridge ? 1 : 0,
                            !bridge
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertEquals(new BlockPos(0, 64, 0), plan.path().get(0));
        assertEquals(new BlockPos(5, 74, 0), plan.path().get(plan.path().size() - 1));
        assertTrue(plan.path().contains(new BlockPos(2, 74, 0)));
    }

    @Test
    void acceptsSevenColumnBridgeWhenCrossingRemainsMinorityOfRoute() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(24, 64, 0),
                pos -> {
                    if (pos.getZ() != 0) {
                        return new RoadRouteNodePlanner.RouteColumn(null, true, false, 0, 0, false);
                    }
                    boolean bridge = pos.getX() >= 9 && pos.getX() <= 15;
                    return new RoadRouteNodePlanner.RouteColumn(
                            new BlockPos(pos.getX(), 64, 0),
                            false,
                            bridge,
                            bridge ? 2 : 0,
                            bridge ? 1 : 0,
                            !bridge
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertTrue(plan.usedBridge());
        assertEquals(7, plan.totalBridgeColumns());
        assertEquals(7, plan.longestBridgeRun());
        assertEquals(new BlockPos(0, 64, 0), plan.path().get(0));
        assertEquals(new BlockPos(24, 64, 0), plan.path().get(plan.path().size() - 1));
    }

    @Test
    void searchBoundsAllowWideRiverBendDetourBeyondTwentyFourBlocks() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(20, 64, 0),
                pos -> {
                    boolean onWestLeg = pos.getX() == 0 && pos.getZ() >= 0 && pos.getZ() <= 24;
                    boolean onTopLeg = pos.getZ() == 24 && pos.getX() >= 0 && pos.getX() <= 20;
                    boolean onEastLeg = pos.getX() == 20 && pos.getZ() >= 0 && pos.getZ() <= 24;
                    boolean startOrEnd = pos.getZ() == 0 && (pos.getX() == 0 || pos.getX() == 20);
                    boolean traversable = onWestLeg || onTopLeg || onEastLeg || startOrEnd;
                    return new RoadRouteNodePlanner.RouteColumn(
                            traversable ? new BlockPos(pos.getX(), 64, pos.getZ()) : null,
                            !traversable,
                            false,
                            0,
                            0,
                            pos.getZ() == 24
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertFalse(plan.usedBridge());
        assertTrue(plan.path().contains(new BlockPos(10, 64, 24)));
    }

    @Test
    void searchBoundsAllowVeryLongOffAxisDetourWhenOnlyRouteLoopsFarAroundObstacle() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(2, 64, 0),
                pos -> {
                    boolean onWestLeg = pos.getX() == 0 && pos.getZ() >= 0 && pos.getZ() <= 40;
                    boolean onTopLeg = pos.getZ() == 40 && pos.getX() >= 0 && pos.getX() <= 2;
                    boolean onEastLeg = pos.getX() == 2 && pos.getZ() >= 0 && pos.getZ() <= 40;
                    boolean startOrEnd = pos.getZ() == 0 && (pos.getX() == 0 || pos.getX() == 2);
                    boolean traversable = onWestLeg || onTopLeg || onEastLeg || startOrEnd;
                    return new RoadRouteNodePlanner.RouteColumn(
                            traversable ? new BlockPos(pos.getX(), 64, pos.getZ()) : null,
                            !traversable,
                            false,
                            0,
                            0,
                            pos.getZ() == 40
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertFalse(plan.usedBridge());
        assertTrue(plan.path().contains(new BlockPos(1, 64, 40)));
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

    @Test
    void findsFarDetourThroughSingleGapAcrossLargeOpenField() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(60, 64, 0),
                pos -> {
                    boolean insideField = pos.getX() >= 0 && pos.getX() <= 60 && pos.getZ() >= -30 && pos.getZ() <= 30;
                    boolean wall = pos.getX() == 30 && pos.getZ() < 30 && pos.getZ() > -30;
                    boolean gap = pos.getX() == 30 && pos.getZ() == 30;
                    boolean traversable = insideField && (!wall || gap);
                    return new RoadRouteNodePlanner.RouteColumn(
                            traversable ? new BlockPos(pos.getX(), 64, pos.getZ()) : null,
                            !traversable,
                            false,
                            0,
                            0,
                            pos.getZ() == 30
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertTrue(plan.path().contains(new BlockPos(30, 64, 30)));
        assertEquals(new BlockPos(0, 64, 0), plan.path().get(0));
        assertEquals(new BlockPos(60, 64, 0), plan.path().get(plan.path().size() - 1));
    }

    @Test
    void findsVeryLargeFieldDetourWithoutExhaustingPlannerStateBudget() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(120, 64, 0),
                pos -> {
                    boolean insideField = pos.getX() >= 0 && pos.getX() <= 120 && pos.getZ() >= -60 && pos.getZ() <= 60;
                    boolean wall = pos.getX() == 60 && pos.getZ() < 60 && pos.getZ() > -60;
                    boolean gap = pos.getX() == 60 && pos.getZ() == 60;
                    boolean traversable = insideField && (!wall || gap);
                    return new RoadRouteNodePlanner.RouteColumn(
                            traversable ? new BlockPos(pos.getX(), 64, pos.getZ()) : null,
                            !traversable,
                            false,
                            0,
                            0,
                            pos.getZ() == 60
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertTrue(plan.path().contains(new BlockPos(60, 64, 60)));
        assertEquals(new BlockPos(0, 64, 0), plan.path().get(0));
        assertEquals(new BlockPos(120, 64, 0), plan.path().get(plan.path().size() - 1));
    }

    @Test
    void diagonalMoveIsAllowedWhenOnlyOneFlankIsBlocked() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(2, 64, 2),
                pos -> {
                    BlockPos surface = new BlockPos(pos.getX(), 64, pos.getZ());
                    boolean blocked = pos.equals(new BlockPos(1, 0, 0));
                    return new RoadRouteNodePlanner.RouteColumn(surface, blocked, false, 0, 0, true);
                }
        );

        boolean cutsCorner = RoadRouteNodePlanner.cutsCornerForTest(
                map,
                new BlockPos(0, 64, 0),
                new int[]{1, 1},
                false
        );

        assertFalse(cutsCorner);
    }

    @Test
    void bridgeSearchCanCrossLargeWaterFieldWithoutStateExplosionFromTotalBridgeCount() {
        RoadRouteNodePlanner.RouteMap map = RoadRouteNodePlanner.RouteMap.of(
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0),
                pos -> {
                    if (pos.getZ() < -20 || pos.getZ() > 20 || pos.getX() < 0 || pos.getX() > 40) {
                        return new RoadRouteNodePlanner.RouteColumn(null, true, false, 0, 0, false);
                    }
                    boolean bridge = pos.getX() >= 5 && pos.getX() <= 35;
                    return new RoadRouteNodePlanner.RouteColumn(
                            new BlockPos(pos.getX(), 64, pos.getZ()),
                            false,
                            bridge,
                            bridge ? 2 : 0,
                            0,
                            pos.getZ() == 0
                    );
                }
        );

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

        assertFalse(plan.path().isEmpty());
        assertTrue(plan.usedBridge());
        assertEquals(new BlockPos(0, 64, 0), plan.path().get(0));
        assertEquals(new BlockPos(40, 64, 0), plan.path().get(plan.path().size() - 1));
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
