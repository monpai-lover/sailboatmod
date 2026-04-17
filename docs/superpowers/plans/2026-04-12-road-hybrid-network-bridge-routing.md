# Road Hybrid Network Bridge Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a shared hybrid route resolver that lets manual roads, automatic post-station routes, and structure-road links prefer stable existing-network attachments over worse direct bridge-heavy crossings.

**Architecture:** Add one shared resolver in `nation/service` that compares direct, single-connector, and dual-connector route shapes under one score model. Keep `RoadPathfinder`, corridor planning, geometry generation, and runtime construction unchanged; the new layer only chooses the center path and hands the selected path back to the existing callers.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, existing `RoadPathfinder`, `RoadNetworkRecord`, `ManualRoadPlannerService`, `RoadAutoRouteService`, and `StructureConstructionManager`

---

## File Structure

**Create:**

- `src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolverTest.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

**Modify:**

- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`

**Responsibilities:**

- `RoadHybridRouteResolver.java`
  Own candidate enumeration, connector route evaluation, network graph extraction, and score comparison.
- `ManualRoadPlannerService.java`
  Replace direct path selection with resolver-driven path selection while preserving waiting-area anchor logic and downstream placement-plan generation.
- `RoadAutoRouteService.java`
  Replace the binary graph-first-or-land fallback with resolver-driven hybrid selection while keeping route-definition output unchanged.
- `StructureConstructionManager.java`
  Reuse the resolver for auto-road creation and preview-road selection so structure links prefer existing roads when they actually help.
- `RoadHybridRouteResolverTest.java`
  Prove route-shape selection, connector rejection, and fallback behavior without involving heavy game services.
- `ManualRoadPlannerServiceTest.java`
  Prove manual town-road planning can accept a resolver-selected network-attached path without breaking preview/build assumptions.
- `RoadAutoRouteServiceTest.java`
  Prove automatic route resolution and stored/generated route merging still behave correctly after resolver integration.
- `StructureConstructionManagerRoadLinkTest.java`
  Prove structure-road preview and auto-link selection can favor road-network targets instead of always choosing a fresh direct path.

### Task 1: Add The Shared Hybrid Resolver

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolverTest.java`

- [ ] **Step 1: Write the failing resolver-selection tests**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadHybridRouteResolverTest {
    @Test
    void prefersDualConnectorWhenItCutsBridgeUsage() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(20, 64, 0);
        BlockPos leftNode = new BlockPos(4, 64, 0);
        BlockPos rightNode = new BlockPos(16, 64, 0);

        RoadHybridRouteResolver.HybridRoute candidate = RoadHybridRouteResolver.resolveForTest(
                List.of(source),
                List.of(target),
                Set.of(leftNode, rightNode),
                Map.of(leftNode, Set.of(rightNode), rightNode, Set.of(leftNode)),
                (from, to, allowWaterFallback) -> {
                    if (from.equals(source) && to.equals(target)) {
                        return new RoadHybridRouteResolver.ConnectorResult(
                                List.of(source, new BlockPos(10, 64, 0), target),
                                12,
                                8,
                                10,
                                false
                        );
                    }
                    return new RoadHybridRouteResolver.ConnectorResult(List.of(from, to), 0, 0, 0, false);
                }
        );

        assertEquals(RoadHybridRouteResolver.ResolutionKind.DUAL_CONNECTOR, candidate.kind());
        assertTrue(candidate.usedExistingNetwork());
    }

    @Test
    void rejectsConnectorThatExceedsBridgeBudget() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(10, 64, 0);
        BlockPos node = new BlockPos(4, 64, 0);

        RoadHybridRouteResolver.HybridRoute candidate = RoadHybridRouteResolver.resolveForTest(
                List.of(source),
                List.of(target),
                Set.of(node),
                Map.of(node, Set.of()),
                (from, to, allowWaterFallback) -> {
                    if (to.equals(node)) {
                        return new RoadHybridRouteResolver.ConnectorResult(
                                List.of(from, new BlockPos(2, 64, 0), node),
                                20,
                                12,
                                15,
                                false
                        );
                    }
                    return new RoadHybridRouteResolver.ConnectorResult(List.of(from, to), 0, 0, 0, false);
                }
        );

        assertEquals(RoadHybridRouteResolver.ResolutionKind.DIRECT, candidate.kind());
    }
}
```

- [ ] **Step 2: Run the resolver tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadHybridRouteResolverTest"`

Expected: `FAILURE` because `RoadHybridRouteResolver` does not exist yet.

- [ ] **Step 3: Write the minimal resolver implementation**

```java
package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RoadHybridRouteResolver {
    static final int MAX_CONNECTOR_BRIDGE_COLUMNS = 8;
    static final int MAX_CONNECTOR_CONTIGUOUS_BRIDGE_COLUMNS = 4;

    private RoadHybridRouteResolver() {
    }

    public enum ResolutionKind {
        DIRECT,
        SOURCE_CONNECTOR,
        TARGET_CONNECTOR,
        DUAL_CONNECTOR,
        NONE
    }

    public interface ConnectorPlanner {
        ConnectorResult plan(BlockPos from, BlockPos to, boolean allowWaterFallback);
    }

    public record ConnectorResult(List<BlockPos> path,
                                  int bridgeColumns,
                                  int longestBridgeRun,
                                  int adjacentWaterColumns,
                                  boolean usedWaterFallback) {
        public ConnectorResult {
            path = path == null ? List.of() : List.copyOf(path);
        }

        boolean usable() {
            return path.size() >= 2
                    && bridgeColumns <= MAX_CONNECTOR_BRIDGE_COLUMNS
                    && longestBridgeRun <= MAX_CONNECTOR_CONTIGUOUS_BRIDGE_COLUMNS;
        }
    }

    public record HybridRoute(ResolutionKind kind,
                              List<BlockPos> fullPath,
                              boolean usedExistingNetwork,
                              int connectorCount,
                              int bridgeColumns,
                              int longestBridgeRun,
                              int adjacentWaterColumns,
                              double score) {
        public static HybridRoute none() {
            return new HybridRoute(ResolutionKind.NONE, List.of(), false, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Double.MAX_VALUE);
        }
    }

    static HybridRoute resolveForTest(List<BlockPos> sourceAnchors,
                                      List<BlockPos> targetAnchors,
                                      Set<BlockPos> networkNodes,
                                      Map<BlockPos, Set<BlockPos>> adjacency,
                                      ConnectorPlanner planner) {
        return resolveCandidates(sourceAnchors, targetAnchors, networkNodes, adjacency, planner);
    }

    static HybridRoute resolveCandidates(List<BlockPos> sourceAnchors,
                                         List<BlockPos> targetAnchors,
                                         Set<BlockPos> networkNodes,
                                         Map<BlockPos, Set<BlockPos>> adjacency,
                                         ConnectorPlanner planner) {
        Objects.requireNonNull(sourceAnchors, "sourceAnchors");
        Objects.requireNonNull(targetAnchors, "targetAnchors");
        Objects.requireNonNull(networkNodes, "networkNodes");
        Objects.requireNonNull(adjacency, "adjacency");
        Objects.requireNonNull(planner, "planner");

        HybridRoute best = HybridRoute.none();
        for (BlockPos source : sourceAnchors) {
            for (BlockPos target : targetAnchors) {
                best = chooseBetter(best, directCandidate(source, target, planner));
                for (BlockPos leftNode : networkNodes) {
                    best = chooseBetter(best, sourceConnectorCandidate(source, target, leftNode, planner));
                    best = chooseBetter(best, targetConnectorCandidate(source, target, leftNode, planner));
                    for (BlockPos rightNode : adjacency.getOrDefault(leftNode, Set.of())) {
                        best = chooseBetter(best, dualConnectorCandidate(source, target, leftNode, rightNode, planner));
                    }
                }
            }
        }
        return best;
    }

    private static HybridRoute directCandidate(BlockPos source, BlockPos target, ConnectorPlanner planner) {
        ConnectorResult result = planner.plan(source, target, true);
        if (!result.usable()) {
            return HybridRoute.none();
        }
        return new HybridRoute(ResolutionKind.DIRECT, result.path(), false, 0, result.bridgeColumns(), result.longestBridgeRun(), result.adjacentWaterColumns(), score(result.path(), false, 0, result.bridgeColumns(), result.longestBridgeRun(), result.adjacentWaterColumns()));
    }

    private static HybridRoute sourceConnectorCandidate(BlockPos source, BlockPos target, BlockPos node, ConnectorPlanner planner) {
        ConnectorResult connector = planner.plan(source, node, true);
        ConnectorResult tail = planner.plan(node, target, true);
        if (!connector.usable() || !tail.usable()) {
            return HybridRoute.none();
        }
        List<BlockPos> merged = stitch(connector.path(), tail.path());
        return new HybridRoute(ResolutionKind.SOURCE_CONNECTOR, merged, true, 1, connector.bridgeColumns() + tail.bridgeColumns(), Math.max(connector.longestBridgeRun(), tail.longestBridgeRun()), connector.adjacentWaterColumns() + tail.adjacentWaterColumns(), score(merged, true, 1, connector.bridgeColumns() + tail.bridgeColumns(), Math.max(connector.longestBridgeRun(), tail.longestBridgeRun()), connector.adjacentWaterColumns() + tail.adjacentWaterColumns()));
    }

    private static HybridRoute targetConnectorCandidate(BlockPos source, BlockPos target, BlockPos node, ConnectorPlanner planner) {
        ConnectorResult head = planner.plan(source, node, true);
        ConnectorResult connector = planner.plan(node, target, true);
        if (!head.usable() || !connector.usable()) {
            return HybridRoute.none();
        }
        List<BlockPos> merged = stitch(head.path(), connector.path());
        return new HybridRoute(ResolutionKind.TARGET_CONNECTOR, merged, true, 1, head.bridgeColumns() + connector.bridgeColumns(), Math.max(head.longestBridgeRun(), connector.longestBridgeRun()), head.adjacentWaterColumns() + connector.adjacentWaterColumns(), score(merged, true, 1, head.bridgeColumns() + connector.bridgeColumns(), Math.max(head.longestBridgeRun(), connector.longestBridgeRun()), head.adjacentWaterColumns() + connector.adjacentWaterColumns()));
    }

    private static HybridRoute dualConnectorCandidate(BlockPos source, BlockPos target, BlockPos leftNode, BlockPos rightNode, ConnectorPlanner planner) {
        ConnectorResult left = planner.plan(source, leftNode, true);
        ConnectorResult right = planner.plan(rightNode, target, true);
        if (!left.usable() || !right.usable()) {
            return HybridRoute.none();
        }
        List<BlockPos> merged = stitch(left.path(), List.of(leftNode, rightNode), right.path());
        return new HybridRoute(ResolutionKind.DUAL_CONNECTOR, merged, true, 2, left.bridgeColumns() + right.bridgeColumns(), Math.max(left.longestBridgeRun(), right.longestBridgeRun()), left.adjacentWaterColumns() + right.adjacentWaterColumns(), score(merged, true, 2, left.bridgeColumns() + right.bridgeColumns(), Math.max(left.longestBridgeRun(), right.longestBridgeRun()), left.adjacentWaterColumns() + right.adjacentWaterColumns()));
    }

    private static HybridRoute chooseBetter(HybridRoute current, HybridRoute candidate) {
        return candidate.score() < current.score() ? candidate : current;
    }

    private static double score(List<BlockPos> path, boolean usedExistingNetwork, int connectorCount, int bridgeColumns, int longestBridgeRun, int adjacentWaterColumns) {
        double score = path.size();
        score += bridgeColumns * 6.0D;
        score += longestBridgeRun * 8.0D;
        score += adjacentWaterColumns * 1.5D;
        score += connectorCount * 5.0D;
        if (usedExistingNetwork) {
            score -= 4.0D;
        }
        return score;
    }

    private static List<BlockPos> stitch(List<BlockPos>... segments) {
        LinkedHashSet<BlockPos> merged = new LinkedHashSet<>();
        for (List<BlockPos> segment : segments) {
            for (BlockPos pos : segment) {
                merged.add(pos.immutable());
            }
        }
        return List.copyOf(merged);
        }
    }

    static Set<BlockPos> collectNetworkNodes(List<RoadNetworkRecord> roads) {
        Set<BlockPos> nodes = new HashSet<>();
        for (RoadNetworkRecord road : roads == null ? List.<RoadNetworkRecord>of() : roads) {
            for (BlockPos pos : road.path()) {
                if (pos != null) {
                    nodes.add(pos.immutable());
                }
            }
        }
        return Set.copyOf(nodes);
    }

    static Map<BlockPos, Set<BlockPos>> collectNetworkAdjacency(List<RoadNetworkRecord> roads) {
        Map<BlockPos, Set<BlockPos>> adjacency = new HashMap<>();
        for (RoadNetworkRecord road : roads == null ? List.<RoadNetworkRecord>of() : roads) {
            for (int i = 0; i + 1 < road.path().size(); i++) {
                BlockPos current = road.path().get(i).immutable();
                BlockPos next = road.path().get(i + 1).immutable();
                adjacency.computeIfAbsent(current, ignored -> new HashSet<>()).add(next);
                adjacency.computeIfAbsent(next, ignored -> new HashSet<>()).add(current);
            }
        }
        return adjacency;
    }

    static ConnectorResult summarizePath(ServerLevel level, List<BlockPos> path, boolean usedWaterFallback) {
        int bridgeColumns = 0;
        int longestBridgeRun = 0;
        int currentBridgeRun = 0;
        int adjacentWaterColumns = 0;
        for (BlockPos pos : path == null ? List.<BlockPos>of() : path) {
            RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForAnchorSelection(level, pos);
            if (diagnostics == null || diagnostics.surface() == null) {
                continue;
            }
            if (diagnostics.bridgeRequired()) {
                bridgeColumns++;
                currentBridgeRun++;
                longestBridgeRun = Math.max(longestBridgeRun, currentBridgeRun);
            } else {
                currentBridgeRun = 0;
            }
            adjacentWaterColumns += diagnostics.adjacentWater();
        }
        return new ConnectorResult(path, bridgeColumns, longestBridgeRun, adjacentWaterColumns, usedWaterFallback);
    }
```

- [ ] **Step 4: Run the resolver tests to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadHybridRouteResolverTest"`

Expected: `BUILD SUCCESSFUL` and both resolver tests pass.

- [ ] **Step 5: Commit the resolver task**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java src/test/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolverTest.java
git commit -m "feat: add hybrid road network resolver"
```

### Task 2: Integrate The Resolver Into Manual Town-Road Planning

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Reuse: `src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java`

- [ ] **Step 1: Write the failing manual-planner integration test**

```java
@Test
void selectedHybridPathIsNormalizedAndUsedForPlacementPlanInputs() {
    List<BlockPos> normalized = ManualRoadPlannerService.normalizePathForTest(
            new BlockPos(1, 64, 2),
            List.of(new BlockPos(2, 64, 2), new BlockPos(4, 64, 2), new BlockPos(6, 64, 2)),
            new BlockPos(7, 64, 2)
    );

    assertEquals(
            List.of(
                    new BlockPos(1, 64, 2),
                    new BlockPos(2, 64, 2),
                    new BlockPos(4, 64, 2),
                    new BlockPos(6, 64, 2),
                    new BlockPos(7, 64, 2)
            ),
            normalized
    );
}
```

- [ ] **Step 2: Run the manual-planner test to verify the new helper is missing**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest.selectedHybridPathIsNormalizedAndUsedForPlacementPlanInputs"`

Expected: `FAILURE` because `normalizePathForTest(...)` does not exist.

- [ ] **Step 3: Add the manual-planner resolver hook and test helper**

```java
private static List<BlockPos> resolveHybridRoadPath(ServerLevel level,
                                                    BlockPos sourceAnchor,
                                                    BlockPos targetAnchor,
                                                    Set<Long> blockedColumns,
                                                    boolean allowWaterFallback) {
    List<RoadNetworkRecord> roads = NationSavedData.get(level).getRoadNetworks();
    RoadHybridRouteResolver.HybridRoute route = RoadHybridRouteResolver.resolveCandidates(
            List.of(sourceAnchor),
            List.of(targetAnchor),
            RoadHybridRouteResolver.collectNetworkNodes(roads),
            RoadHybridRouteResolver.collectNetworkAdjacency(roads),
            (from, to, allowBridgeColumns) -> {
                List<BlockPos> path = findPreferredRoadPath(level, from, to, blockedColumns, allowBridgeColumns && allowWaterFallback);
                return RoadHybridRouteResolver.summarizePath(level, path, allowBridgeColumns && allowWaterFallback);
            }
    );
    return route.fullPath();
}

static List<BlockPos> normalizePathForTest(BlockPos start, List<BlockPos> path, BlockPos end) {
    return normalizePath(start, path, end);
}
```

Then replace the direct calls inside `resolveWaitingAreaRoute(...)`, `resolveTownAnchorFallbackRoute(...)`, and `buildConnectedRouteViaTownAnchors(...)`:

```java
List<BlockPos> path = resolveHybridRoadPath(level, sourceSurface, targetSurface, adjustedBlockedColumns, allowWaterFallback);
```

- [ ] **Step 4: Run the manual-road test class**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: `BUILD SUCCESSFUL` with the existing manual-road tests still green.

- [ ] **Step 5: Commit the manual-road integration**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java
git commit -m "feat: use hybrid routing for manual roads"
```

### Task 3: Integrate The Resolver Into Automatic Post-Station Routes

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`
- Reuse: `src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java`

- [ ] **Step 1: Write the failing automatic-route resolver test**

```java
@Test
void prefersHybridNetworkResolutionOverLongerDirectFallback() {
    RoadAutoRouteService.RouteResolution direct = new RoadAutoRouteService.RouteResolution(
            RoadAutoRouteService.PathSource.LAND_TERRAIN,
            List.of(new net.minecraft.core.BlockPos(0, 64, 0), new net.minecraft.core.BlockPos(10, 64, 0))
    );
    RoadAutoRouteService.RouteResolution hybrid = new RoadAutoRouteService.RouteResolution(
            RoadAutoRouteService.PathSource.ROAD_NETWORK,
            List.of(new net.minecraft.core.BlockPos(0, 64, 0), new net.minecraft.core.BlockPos(4, 64, 0), new net.minecraft.core.BlockPos(10, 64, 0))
    );

    RoadAutoRouteService.RouteResolution chosen = RoadAutoRouteService.preferResolutionForTest(direct, hybrid);

    assertEquals(RoadAutoRouteService.PathSource.ROAD_NETWORK, chosen.source());
    assertEquals(3, chosen.path().size());
}
```

- [ ] **Step 2: Run the automatic-route test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.route.RoadAutoRouteServiceTest.prefersHybridNetworkResolutionOverLongerDirectFallback"`

Expected: `FAILURE` because `preferResolutionForTest(...)` does not exist.

- [ ] **Step 3: Implement resolver-backed auto-route resolution**

```java
private static RouteResolution resolveAutoRoute(ServerLevel level, BlockPos start, BlockPos end) {
    Graph graph = buildGraph(level);
    RoadHybridRouteResolver.HybridRoute route = RoadHybridRouteResolver.resolveCandidates(
            List.of(start),
            List.of(end),
            graph.nodes(),
            graph.adjacency(),
            (from, to, allowWaterFallback) -> {
                List<BlockPos> path = combineRouteEndpoints(from, RoadPathfinder.findPath(level, from, to, Set.of(), allowWaterFallback), to);
                return RoadHybridRouteResolver.summarizePath(level, path, allowWaterFallback);
            }
    );
    if (route.kind() == RoadHybridRouteResolver.ResolutionKind.NONE || route.fullPath().size() < 2) {
        return RouteResolution.none();
    }
    PathSource source = route.usedExistingNetwork() ? PathSource.ROAD_NETWORK : PathSource.LAND_TERRAIN;
    return new RouteResolution(source, route.fullPath());
}

static RouteResolution preferResolutionForTest(RouteResolution direct, RouteResolution hybrid) {
    if (hybrid != null && hybrid.found()) {
        return hybrid;
    }
    return direct == null ? RouteResolution.none() : direct;
}
```

- [ ] **Step 4: Run the automatic-route test class**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.route.RoadAutoRouteServiceTest"`

Expected: `BUILD SUCCESSFUL` and the merge-routes test plus the new hybrid-preference test pass together.

- [ ] **Step 5: Commit the auto-route integration**

```bash
git add src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java
git commit -m "feat: resolve auto routes through hybrid road routing"
```

### Task 4: Integrate The Resolver Into Structure Road Links And Preview

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Reuse: `src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java`

- [ ] **Step 1: Write the failing structure-road preference tests**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureConstructionManagerRoadLinkTest {
    @Test
    void previewRoadConnectionPrefersRoadTargetBonus() {
        StructureConstructionManager.PreviewRoadConnection road = new StructureConstructionManager.PreviewRoadConnection(
                List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0)),
                StructureConstructionManager.PreviewRoadTargetKind.ROAD,
                new BlockPos(4, 64, 0)
        );
        StructureConstructionManager.PreviewRoadConnection structure = new StructureConstructionManager.PreviewRoadConnection(
                List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 1)),
                StructureConstructionManager.PreviewRoadTargetKind.STRUCTURE,
                new BlockPos(3, 64, 1)
        );

        StructureConstructionManager.PreviewRoadConnection chosen =
                StructureConstructionManager.choosePreviewConnectionForTest(List.of(structure, road), 0);

        assertEquals(StructureConstructionManager.PreviewRoadTargetKind.ROAD, chosen.targetKind());
    }
}
```

- [ ] **Step 2: Run the structure-road test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: `FAILURE` because `choosePreviewConnectionForTest(...)` does not exist.

- [ ] **Step 3: Implement resolver-backed structure-road selection**

```java
private static List<BlockPos> findBestRoadPath(ServerLevel level,
                                               com.monpai.sailboatmod.nation.model.PlacedStructureRecord first,
                                               com.monpai.sailboatmod.nation.model.PlacedStructureRecord second) {
    List<RoadAnchor> firstAnchors = getRoadAnchors(first);
    List<RoadAnchor> secondAnchors = getRoadAnchors(second);
    List<RoadNetworkRecord> roads = NationSavedData.get(level).getRoadNetworks();
    RoadHybridRouteResolver.HybridRoute route = RoadHybridRouteResolver.resolveCandidates(
            firstAnchors.stream().map(RoadAnchor::pos).toList(),
            secondAnchors.stream().map(RoadAnchor::pos).toList(),
            RoadHybridRouteResolver.collectNetworkNodes(roads),
            RoadHybridRouteResolver.collectNetworkAdjacency(roads),
            (from, to, allowWaterFallback) -> {
                List<BlockPos> path = RoadPathfinder.findPath(level, from, to, Set.of(), allowWaterFallback);
                return RoadHybridRouteResolver.summarizePath(level, path, allowWaterFallback);
            }
    );
    return route.fullPath().size() >= 2 ? route.fullPath() : RoadPathfinder.findPath(level, first.center(), second.center());
}

static PreviewRoadConnection choosePreviewConnectionForTest(List<PreviewRoadConnection> connections, int rotation) {
    return connections.stream()
            .sorted(Comparator.comparingDouble(connection -> connection.path().size()
                    - (connection.targetKind() == PreviewRoadTargetKind.ROAD ? 2.5D : 0.0D)))
            .findFirst()
            .orElseThrow();
}
```

Also update `estimatePreviewRoad(...)` so the scored candidate path comes from the resolver rather than only `RoadPathfinder.findPath(...)`.

- [ ] **Step 4: Run the structure-road test and the targeted road test sweep**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: `BUILD SUCCESSFUL`.

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.*Road*" --tests "com.monpai.sailboatmod.route.RoadAutoRouteServiceTest"`

Expected: `BUILD SUCCESSFUL` with resolver, manual-road, auto-route, and structure-road link tests all green together.

- [ ] **Step 5: Commit the structure-road integration**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java
git commit -m "feat: connect structure roads through hybrid routing"
```

### Task 5: Full Verification And Cleanup

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolverTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Run the focused hybrid-routing regression suite**

Run:

```bash
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadHybridRouteResolverTest" --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --tests "com.monpai.sailboatmod.route.RoadAutoRouteServiceTest" --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run compile verification**

Run:

```bash
.\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: If any test names or helper signatures drifted, fix them immediately in code and tests**

Use this consistency target:

```java
RoadHybridRouteResolver.resolveCandidates(...);
RoadHybridRouteResolver.HybridRoute route;
RoadHybridRouteResolver.ConnectorResult connector;
```

If the actual implementation chose different names, rename the drift now so the resolver API stays consistent across all callers and tests.

- [ ] **Step 4: Review the diff for scope control**

Run:

```bash
git diff --stat HEAD~4..HEAD
```

Expected:

- only the resolver, the three caller classes, and the related test files changed
- no persistence format changes
- no UI packet schema changes
- no unrelated corridor or geometry rewrites

- [ ] **Step 5: Commit the verification pass**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolverTest.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "test: verify hybrid road network bridge routing"
```
