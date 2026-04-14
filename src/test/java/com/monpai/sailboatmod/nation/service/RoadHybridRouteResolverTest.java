package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadHybridRouteResolverTest {
    @Test
    void prefersExistingNetworkEvenWhenDirectPathIsShorter() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(20, 64, 0);
        BlockPos leftNode = new BlockPos(4, 64, 0);
        BlockPos rightNode = new BlockPos(16, 64, 0);

        RoadHybridRouteResolver.HybridRoute candidate = RoadHybridRouteResolver.resolveForTest(
                List.of(source),
                List.of(target),
                Set.of(leftNode, rightNode),
                continuousAdjacency(leftNode.getX(), rightNode.getX()),
                (from, to, allowWaterFallback) -> {
                    if (from.equals(source) && to.equals(target)) {
                        return new RoadHybridRouteResolver.ConnectorResult(
                                straightPath(source.getX(), target.getX()),
                                0,
                                0,
                                0,
                                true
                        );
                    }
                    return new RoadHybridRouteResolver.ConnectorResult(straightPath(from.getX(), to.getX()), 0, 0, 0, true);
                }
        );

        assertEquals(RoadHybridRouteResolver.ResolutionKind.DUAL_CONNECTOR, candidate.kind());
        assertTrue(candidate.usedExistingNetwork());
    }

    @Test
    void connectorWithLargeBridgeMetricsIsPenalizedButNotRejected() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(20, 64, 0);
        BlockPos leftNode = new BlockPos(5, 64, 0);
        BlockPos rightNode = new BlockPos(15, 64, 0);

        RoadHybridRouteResolver.HybridRoute candidate = RoadHybridRouteResolver.resolveForTest(
                List.of(source),
                List.of(target),
                Set.of(leftNode, rightNode),
                continuousAdjacency(leftNode.getX(), rightNode.getX()),
                (from, to, allowWaterFallback) -> {
                    if ((from.equals(source) && to.equals(leftNode)) || (from.equals(rightNode) && to.equals(target))) {
                        return new RoadHybridRouteResolver.ConnectorResult(
                                straightPath(from.getX(), to.getX()),
                                12,
                                7,
                                8,
                                true
                        );
                    }
                    if (from.equals(source) && to.equals(target)) {
                        return new RoadHybridRouteResolver.ConnectorResult(
                                straightPath(source.getX(), target.getX()),
                                30,
                                14,
                                20,
                                true
                        );
                    }
                    return new RoadHybridRouteResolver.ConnectorResult(straightPath(from.getX(), to.getX()), 0, 0, 0, true);
                }
        );

        assertEquals(RoadHybridRouteResolver.ResolutionKind.DUAL_CONNECTOR, candidate.kind());
        assertTrue(candidate.usedExistingNetwork());
    }

    @Test
    void fallsBackToDirectWhenNoNetworkBackedCandidateExists() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(10, 64, 0);

        RoadHybridRouteResolver.HybridRoute candidate = RoadHybridRouteResolver.resolveForTest(
                List.of(source),
                List.of(target),
                Set.of(new BlockPos(4, 64, 0)),
                Map.of(),
                (from, to, allowWaterFallback) -> new RoadHybridRouteResolver.ConnectorResult(straightPath(from.getX(), to.getX()), 0, 0, 0, true)
        );

        assertEquals(RoadHybridRouteResolver.ResolutionKind.DIRECT, candidate.kind());
        assertFalse(candidate.usedExistingNetwork());
    }

    @Test
    void ignoresNetworkBackedCandidateWhenItsFullPathIsNotContinuous() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(12, 64, 0);
        BlockPos leftNode = new BlockPos(4, 64, 0);
        BlockPos rightNode = new BlockPos(8, 64, 0);

        RoadHybridRouteResolver.HybridRoute candidate = RoadHybridRouteResolver.resolveForTest(
                List.of(source),
                List.of(target),
                Set.of(leftNode, rightNode),
                Map.of(
                        leftNode, Set.of(rightNode),
                        rightNode, Set.of(leftNode)
                ),
                (from, to, allowWaterFallback) -> {
                    if (from.equals(source) && to.equals(target)) {
                        return new RoadHybridRouteResolver.ConnectorResult(
                                List.of(
                                        source,
                                        new BlockPos(1, 64, 0),
                                        new BlockPos(2, 64, 0),
                                        new BlockPos(3, 64, 0),
                                        new BlockPos(4, 64, 0),
                                        new BlockPos(5, 64, 0),
                                        new BlockPos(6, 64, 0),
                                        new BlockPos(7, 64, 0),
                                        new BlockPos(8, 64, 0),
                                        new BlockPos(9, 64, 0),
                                        new BlockPos(10, 64, 0),
                                        new BlockPos(11, 64, 0),
                                        target
                                ),
                                18,
                                9,
                                4,
                                true
                        );
                    }
                    if (from.equals(source) && to.equals(leftNode)) {
                        return new RoadHybridRouteResolver.ConnectorResult(
                                List.of(source, new BlockPos(1, 64, 0), new BlockPos(2, 64, 0), new BlockPos(3, 64, 0), leftNode),
                                4,
                                4,
                                1,
                                true
                        );
                    }
                    if (from.equals(rightNode) && to.equals(target)) {
                        return new RoadHybridRouteResolver.ConnectorResult(
                                List.of(rightNode, new BlockPos(9, 64, 0), new BlockPos(10, 64, 0), new BlockPos(11, 64, 0), target),
                                0,
                                0,
                                0,
                                false
                        );
                    }
                    return new RoadHybridRouteResolver.ConnectorResult(List.of(), 0, 0, 0, false);
                }
        );

        assertEquals(RoadHybridRouteResolver.ResolutionKind.DIRECT, candidate.kind());
        assertFalse(candidate.usedExistingNetwork());
    }

    @Test
    void sourceConnectorStitchPreservesRequestedTargetWhenConnectorOvershootsIntoNetwork() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(4, 64, 0);
        BlockPos node = new BlockPos(5, 64, 0);

        RoadHybridRouteResolver.HybridRoute candidate = RoadHybridRouteResolver.resolveForTest(
                List.of(source),
                List.of(target),
                Set.of(node, target),
                Map.of(
                        node, Set.of(target),
                        target, Set.of(node)
                ),
                (from, to, allowWaterFallback) -> {
                    if (from.equals(source) && to.equals(target)) {
                        return new RoadHybridRouteResolver.ConnectorResult(List.of(), 0, 0, 0, false);
                    }
                    if (from.equals(source) && to.equals(node)) {
                        return new RoadHybridRouteResolver.ConnectorResult(
                                List.of(
                                        source,
                                        new BlockPos(1, 64, 0),
                                        new BlockPos(2, 64, 0),
                                        new BlockPos(3, 64, 0),
                                        target,
                                        node
                                ),
                                0,
                                0,
                                0,
                                false
                        );
                    }
                    return new RoadHybridRouteResolver.ConnectorResult(List.of(), 0, 0, 0, false);
                }
        );

        assertEquals(RoadHybridRouteResolver.ResolutionKind.SOURCE_CONNECTOR, candidate.kind());
        assertEquals(
                List.of(
                        source,
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0),
                        target,
                        node,
                        target
                ),
                candidate.fullPath()
        );
    }

    @Test
    void selectsNearestRoadNodesWithinCandidateLimit() {
        List<BlockPos> chosen = RoadHybridRouteResolver.selectNearestNodesForTest(
                new BlockPos(0, 64, 0),
                Set.of(
                        new BlockPos(2, 64, 0),
                        new BlockPos(4, 64, 0),
                        new BlockPos(6, 64, 0),
                        new BlockPos(20, 64, 0)
                ),
                3
        );

        assertEquals(
                List.of(new BlockPos(2, 64, 0), new BlockPos(4, 64, 0), new BlockPos(6, 64, 0)),
                chosen
        );
    }

    @Test
    void collectsAdjacencyAcrossExistingRoadRecordPath() {
        Map<BlockPos, Set<BlockPos>> adjacency = RoadHybridRouteResolver.collectNetworkAdjacency(
                List.of(
                        new RoadNetworkRecord(
                                "manual|town:a|town:b",
                                "nation",
                                "town",
                                "minecraft:overworld",
                                "town:a",
                                "town:b",
                                List.of(
                                        new BlockPos(0, 64, 0),
                                        new BlockPos(1, 64, 0),
                                        new BlockPos(2, 64, 0)
                                ),
                                1L,
                                RoadNetworkRecord.SOURCE_TYPE_MANUAL
                        )
                )
        );

        assertEquals(Set.of(new BlockPos(1, 64, 0)), adjacency.get(new BlockPos(0, 64, 0)));
    }

    private static List<BlockPos> straightPath(int fromX, int toX) {
        List<BlockPos> path = new java.util.ArrayList<>();
        int step = Integer.compare(toX, fromX);
        int x = fromX;
        path.add(new BlockPos(x, 64, 0));
        while (x != toX) {
            x += step;
            path.add(new BlockPos(x, 64, 0));
        }
        return List.copyOf(path);
    }

    private static Map<BlockPos, Set<BlockPos>> continuousAdjacency(int fromX, int toX) {
        java.util.Map<BlockPos, java.util.Set<BlockPos>> adjacency = new java.util.HashMap<>();
        int step = Integer.compare(toX, fromX);
        int x = fromX;
        while (x != toX) {
            BlockPos current = new BlockPos(x, 64, 0);
            BlockPos next = new BlockPos(x + step, 64, 0);
            adjacency.computeIfAbsent(current, ignored -> new java.util.HashSet<>()).add(next);
            adjacency.computeIfAbsent(next, ignored -> new java.util.HashSet<>()).add(current);
            x += step;
        }
        return Map.copyOf(adjacency.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue()))));
    }
}
