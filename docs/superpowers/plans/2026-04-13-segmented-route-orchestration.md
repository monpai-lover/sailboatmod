# Segmented Route Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace long single-shot road searches with shared segmented route orchestration that reuses prioritized anchors, retries failed segments with alternate rules, and returns explicit failure reasons while fixing bridge-pier path generation.

**Architecture:** Add a shared orchestration layer above `RoadPathfinder` that breaks long routes into anchor-to-anchor segments for manual roads, auto post routes, and structure-road links. Keep `RoadPathfinder` as the single-segment planner, but extend it with richer result reporting and bridge-anchor helpers so bridge piers become true segmented routing anchors rather than a weak raster fallback.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Gradle, existing `RoadPathfinder`, `RoadHybridRouteResolver`, `ManualRoadPlannerService`, `RoadAutoRouteService`, `StructureConstructionManager`, and `RoadRouteNodePlanner`.

---

## File Map

- Create: `src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java`
  - Shared orchestration layer that collects anchors, builds anchor chains, retries failed segments, subdivides long failing segments, and returns success or structured failure.
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java`
  - Locks down segmentation, retries, fallback subdivision, and failure reporting.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
  - Add structured segment-planning results and richer failure reason propagation for orchestration use.
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java`
  - Replace weak linear pier raster fallback with a true pier-node bridge graph search that can fail with explicit bridge reasons.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
  - Swap long-route resolution over to the shared segmented orchestrator and surface failure reasons for user-facing messages/logs.
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
  - Route automatic post-station planning through the shared segmented orchestrator.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - Route auto structure-link and preview-road path resolution through the shared segmented orchestrator.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
  - Lock down manual-road segmented fallback behavior and failure reporting.
- Modify: `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`
  - Lock down auto-route use of segmentation.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
  - Lock down structure-link use of segmentation.
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadRouteNodePlannerTest.java`
  - Add bridge-pier graph regressions that prove bridge anchors can create paths where raster fallback fails.

## Task 1: Lock down segmented orchestration behavior with failing tests

**Files:**
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java`

- [ ] **Step 1: Write the failing segmentation and retry tests**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentedRoadPathOrchestratorTest {
    @Test
    void longRouteUsesIntermediateAnchorsInsteadOfSingleSegment() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos mid = new BlockPos(32, 64, 0);
        BlockPos end = new BlockPos(64, 64, 0);

        SegmentedRoadPathOrchestrator.OrchestratedPath result = SegmentedRoadPathOrchestrator.planForTest(
                start,
                end,
                List.of(mid),
                request -> List.of(request.from(), request.to()),
                request -> false
        );

        assertTrue(result.success());
        assertTrue(result.path().contains(mid));
        assertEquals(2, result.segments().size());
    }

    @Test
    void failedSegmentRetriesWithSubdivisionBeforeReturningFailure() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(48, 64, 0);

        SegmentedRoadPathOrchestrator.OrchestratedPath result = SegmentedRoadPathOrchestrator.planForTest(
                start,
                end,
                List.of(),
                request -> request.to().getX() - request.from().getX() > 24 ? List.of() : List.of(request.from(), request.to()),
                request -> true
        );

        assertTrue(result.success());
        assertFalse(result.segments().isEmpty());
        assertTrue(result.segments().size() > 1);
    }

    @Test
    void returnsStructuredFailureReasonWhenRetriesAreExhausted() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(80, 64, 0);

        SegmentedRoadPathOrchestrator.OrchestratedPath result = SegmentedRoadPathOrchestrator.planForTest(
                start,
                end,
                List.of(),
                request -> List.of(),
                request -> false
        );

        assertFalse(result.success());
        assertEquals(SegmentedRoadPathOrchestrator.FailureReason.SUBDIVISION_LIMIT_EXCEEDED, result.failureReason());
        assertFalse(result.failedSegments().isEmpty());
    }
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest
```

Expected: FAIL because `SegmentedRoadPathOrchestrator` does not exist yet.

- [ ] **Step 3: Commit the failing test**

```bash
git add src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java
git commit -m "Add segmented road orchestration regressions"
```

## Task 2: Implement the shared segmented orchestration layer

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java`

- [ ] **Step 1: Implement the minimal orchestration types**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

public final class SegmentedRoadPathOrchestrator {
    private static final int MAX_SUBDIVISION_DEPTH = 3;

    private SegmentedRoadPathOrchestrator() {
    }

    public enum FailureReason {
        NONE,
        SEARCH_EXHAUSTED,
        NO_BRIDGE_ANCHORS,
        BRIDGE_HEAD_UNREACHABLE,
        PIER_CHAIN_DISCONNECTED,
        SUBDIVISION_LIMIT_EXCEEDED
    }

    public record SegmentRequest(BlockPos from, BlockPos to, int depth) {
    }

    public record SegmentAttempt(BlockPos from, BlockPos to, List<BlockPos> path, FailureReason failureReason) {
    }

    public record OrchestratedPath(boolean success,
                                   List<BlockPos> path,
                                   List<SegmentAttempt> segments,
                                   FailureReason failureReason,
                                   List<SegmentAttempt> failedSegments) {
    }

    static OrchestratedPath planForTest(BlockPos start,
                                        BlockPos end,
                                        List<BlockPos> anchors,
                                        Function<SegmentRequest, List<BlockPos>> planner,
                                        Predicate<SegmentRequest> maySubdivide) {
        return plan(start, end, anchors, planner, maySubdivide);
    }

    static OrchestratedPath plan(BlockPos start,
                                 BlockPos end,
                                 List<BlockPos> anchors,
                                 Function<SegmentRequest, List<BlockPos>> planner,
                                 Predicate<SegmentRequest> maySubdivide) {
        List<BlockPos> chain = buildAnchorChain(start, end, anchors);
        ArrayList<SegmentAttempt> successfulSegments = new ArrayList<>();
        ArrayList<SegmentAttempt> failedSegments = new ArrayList<>();
        LinkedHashSet<BlockPos> stitched = new LinkedHashSet<>();

        for (int i = 0; i + 1 < chain.size(); i++) {
            SegmentAttempt attempt = resolveSegment(chain.get(i), chain.get(i + 1), 0, planner, maySubdivide, successfulSegments, failedSegments);
            if (attempt.path().isEmpty()) {
                return new OrchestratedPath(false, List.of(), List.copyOf(successfulSegments), attempt.failureReason(), List.copyOf(failedSegments));
            }
            stitched.addAll(attempt.path());
        }
        return new OrchestratedPath(true, List.copyOf(stitched), List.copyOf(successfulSegments), FailureReason.NONE, List.of());
    }
}
```

- [ ] **Step 2: Implement anchor-chain construction and recursive segment resolution**

```java
private static List<BlockPos> buildAnchorChain(BlockPos start, BlockPos end, List<BlockPos> anchors) {
    ArrayList<BlockPos> chain = new ArrayList<>();
    chain.add(Objects.requireNonNull(start, "start").immutable());
    for (BlockPos anchor : anchors == null ? List.<BlockPos>of() : anchors) {
        if (anchor != null && !anchor.equals(start) && !anchor.equals(end)) {
            chain.add(anchor.immutable());
        }
    }
    chain.add(Objects.requireNonNull(end, "end").immutable());
    return List.copyOf(chain);
}

private static SegmentAttempt resolveSegment(BlockPos from,
                                             BlockPos to,
                                             int depth,
                                             Function<SegmentRequest, List<BlockPos>> planner,
                                             Predicate<SegmentRequest> maySubdivide,
                                             List<SegmentAttempt> successfulSegments,
                                             List<SegmentAttempt> failedSegments) {
    SegmentRequest request = new SegmentRequest(from, to, depth);
    List<BlockPos> path = planner.apply(request);
    if (path != null && path.size() >= 2) {
        SegmentAttempt success = new SegmentAttempt(from, to, List.copyOf(path), FailureReason.NONE);
        successfulSegments.add(success);
        return success;
    }
    if (depth >= MAX_SUBDIVISION_DEPTH || !maySubdivide.test(request)) {
        SegmentAttempt failure = new SegmentAttempt(from, to, List.of(), FailureReason.SUBDIVISION_LIMIT_EXCEEDED);
        failedSegments.add(failure);
        return failure;
    }

    BlockPos midpoint = midpoint(from, to);
    SegmentAttempt left = resolveSegment(from, midpoint, depth + 1, planner, maySubdivide, successfulSegments, failedSegments);
    if (left.path().isEmpty()) {
        return left;
    }
    SegmentAttempt right = resolveSegment(midpoint, to, depth + 1, planner, maySubdivide, successfulSegments, failedSegments);
    if (right.path().isEmpty()) {
        return right;
    }

    LinkedHashSet<BlockPos> stitched = new LinkedHashSet<>();
    stitched.addAll(left.path());
    stitched.addAll(right.path());
    return new SegmentAttempt(from, to, List.copyOf(stitched), FailureReason.NONE);
}

private static BlockPos midpoint(BlockPos from, BlockPos to) {
    return new BlockPos(
            Math.floorDiv(from.getX() + to.getX(), 2),
            Math.floorDiv(from.getY() + to.getY(), 2),
            Math.floorDiv(from.getZ() + to.getZ(), 2)
    );
}
```

- [ ] **Step 3: Re-run the focused orchestrator test**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java
git commit -m "Add segmented road path orchestration core"
```

## Task 3: Add structured single-segment results in `RoadPathfinder`

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`

- [ ] **Step 1: Add a structured planning result for orchestration use**

```java
public record PathResult(List<BlockPos> path,
                         boolean usedBridgeFallback,
                         FailureReason failureReason,
                         ColumnDiagnostics start,
                         ColumnDiagnostics end) {
    public boolean success() {
        return path != null && path.size() >= 2;
    }
}
```

Add:

```java
public enum FailureReason {
    NONE,
    ANCHOR_REJECTED,
    PLANNER_EMPTY_PATH,
    CENTERLINE_FALLBACK_EMPTY,
    SEARCH_EXHAUSTED,
    BRIDGE_ANCHOR_REJECTED
}
```

- [ ] **Step 2: Extract a result-returning planner alongside the old API**

```java
public static PathResult findPathResult(Level level,
                                        BlockPos from,
                                        BlockPos to,
                                        Set<Long> blockedColumns,
                                        Set<Long> excludedColumns,
                                        boolean allowWaterFallback) {
    // current body of findPath(...) converted to return PathResult with an explicit FailureReason
}
```

Then keep the legacy API as a thin wrapper:

```java
public static List<BlockPos> findPath(Level level,
                                      BlockPos from,
                                      BlockPos to,
                                      Set<Long> blockedColumns,
                                      Set<Long> excludedColumns,
                                      boolean allowWaterFallback) {
    return findPathResult(level, from, to, blockedColumns, excludedColumns, allowWaterFallback).path();
}
```

- [ ] **Step 3: Re-run the existing `RoadPathfinder`-dependent tests**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: PASS, preserving current behavior while exposing richer results.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java
git commit -m "Expose structured road pathfinding results"
```

## Task 4: Fix bridge-pier routing so piers behave as true bridge anchors

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java`
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadRouteNodePlannerTest.java`

- [ ] **Step 1: Add the failing bridge-anchor regression**

```java
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
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadRouteNodePlannerTest
```

Expected: FAIL because current pier fallback still depends on a brittle ordered raster path.

- [ ] **Step 3: Replace raster-only pier fallback with a pier-node graph search**

Add a true bridge graph step:

```java
private static RoutePlan searchBridgeGraph(RouteMap map, List<RoadBridgePierPlanner.PierNode> pierNodes) {
    List<RoadBridgePierPlanner.PierNode> usable = pierNodes == null ? List.of() : pierNodes.stream()
            .filter(Objects::nonNull)
            .toList();
    if (usable.isEmpty()) {
        return RoutePlan.empty();
    }

    List<BlockPos> anchorPath = searchPierAnchorChain(map, usable);
    if (anchorPath.isEmpty()) {
        return RoutePlan.empty();
    }
    return validateRasterizedBridgeAnchorPath(map, anchorPath);
}
```

And make `searchPierAnchorChain(...)` run over:

- start anchor
- bridge head anchors
- pier deck anchors
- end anchor

using connectivity by bounded horizontal span instead of fixed linear ordering only.

- [ ] **Step 4: Re-run the focused bridge planner test**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadRouteNodePlannerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadRouteNodePlannerTest.java
git commit -m "Fix bridge pier anchor routing"
```

## Task 5: Route manual road planning through the segmented orchestrator

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`

- [ ] **Step 1: Add the failing manual segmented-route regression**

```java
@Test
void manualPlannerUsesSegmentedFallbackForLongRoutes() {
    List<BlockPos> anchors = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(32, 64, 0),
            new BlockPos(64, 64, 0)
    );

    List<BlockPos> path = ManualRoadPlannerService.stitchRouteSegments(
            List.of(anchors.get(0), anchors.get(1)),
            List.of(anchors.get(1), anchors.get(2))
    );

    assertEquals(3, path.size());
    assertEquals(anchors.get(1), path.get(1));
}
```

- [ ] **Step 2: Integrate the orchestrator into `resolveHybridRoadPath(...)`**

Replace the one-shot resolution with:

```java
SegmentedRoadPathOrchestrator.OrchestratedPath orchestrated = SegmentedRoadPathOrchestrator.plan(
        sourceAnchor,
        targetAnchor,
        collectPreferredIntermediateAnchors(level, sourceAnchor, targetAnchor),
        request -> findPreferredRoadPath(level, request.from(), request.to(), blockedColumns, allowWaterFallback),
        request -> shouldSubdivideSegment(request.from(), request.to())
);
return orchestrated.success() ? orchestrated.path() : List.of();
```

Add explicit helpers:

```java
private static boolean shouldSubdivideSegment(BlockPos from, BlockPos to) {
    return from != null && to != null && from.distManhattan(to) > 48;
}
```

- [ ] **Step 3: Surface failure reasons in logs/messages**

Add a structured log when orchestration fails:

```java
LOGGER.warn("ManualRoadPlanner segmented failure from {} to {} reason={} failedSegments={}",
        sourceAnchor,
        targetAnchor,
        orchestrated.failureReason(),
        orchestrated.failedSegments().size());
```

- [ ] **Step 4: Re-run the focused manual planner tests**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java
git commit -m "Segment manual road route planning"
```

## Task 6: Route auto post routes and structure links through the shared orchestrator

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Modify: `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Add focused regressions for shared segmented use**

```java
@Test
void autoRouteStillPrefersNetworkBackedPathAfterSegmentation() {
    RoadAutoRouteService.RouteResolution chosen = RoadAutoRouteService.preferResolutionForTest(
            new RoadAutoRouteService.RouteResolution(
                    RoadAutoRouteService.PathSource.LAND_TERRAIN,
                    List.of(new BlockPos(0, 64, 0), new BlockPos(20, 64, 0))
            ),
            new RoadAutoRouteService.RouteResolution(
                    RoadAutoRouteService.PathSource.ROAD_NETWORK,
                    List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0), new BlockPos(20, 64, 0))
            )
    );

    assertEquals(RoadAutoRouteService.PathSource.ROAD_NETWORK, chosen.source());
}
```

```java
@Test
void structureRoadLinksCanStitchMultipleSegmentsWithoutDuplicateNodes() {
    List<BlockPos> path = StructureConstructionManager.choosePreviewConnectionForTest(
            List.of(
                    new StructureConstructionManager.PreviewRoadConnection(
                            List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), new BlockPos(16, 64, 0)),
                            StructureConstructionManager.PreviewRoadTargetKind.ROAD,
                            new BlockPos(16, 64, 0)
                    )
            ),
            0
    ).path();

    assertEquals(3, path.size());
}
```

- [ ] **Step 2: Integrate the orchestrator into `RoadAutoRouteService` and `StructureConstructionManager`**

Use the same orchestration entry point in both places:

```java
SegmentedRoadPathOrchestrator.OrchestratedPath orchestrated = SegmentedRoadPathOrchestrator.plan(
        start,
        end,
        collectPreferredIntermediateAnchors(...),
        request -> RoadPathfinder.findPath(...),
        request -> request.from().distManhattan(request.to()) > 48
);
```

Return stitched path on success, otherwise preserve current fallback behavior where needed.

- [ ] **Step 3: Re-run the focused service tests**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.route.RoadAutoRouteServiceTest --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Share segmented routing across auto road features"
```

## Task 7: End-to-end verification

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

- [ ] **Step 1: Run the combined focused suite**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest --tests com.monpai.sailboatmod.construction.RoadRouteNodePlannerTest --tests com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest --tests com.monpai.sailboatmod.route.RoadAutoRouteServiceTest --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: PASS.

- [ ] **Step 2: Run the full build**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit the integrated change**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java src/test/java/com/monpai/sailboatmod/construction/RoadRouteNodePlannerTest.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Add segmented road route orchestration"
```

## Notes For Execution

- Run all commands from `F:\Codex\sailboatmod`.
- Keep unrelated local files like `logs/` and backup language files out of commits.
- The new orchestrator should stay focused on route planning only. Do not let corridor generation or block placement logic leak into it.
- Preserve existing public `RoadPathfinder.findPath(...)` overloads while adding richer orchestration-facing results.
- Bridge-pier fixes belong in `RoadRouteNodePlanner` search behavior, not in late-stage geometry output.
