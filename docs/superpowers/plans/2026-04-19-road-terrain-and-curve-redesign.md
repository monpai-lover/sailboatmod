# Road Terrain And Curve Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild land-road planning, curve processing, bridge approaches, and construction execution so roads can create continuous terrain, build short arch bridges over small water, cut through mountains with slopes or tunnels, and use a RoadWeaver-style mixed interpolation plus spline centerline pipeline without fractured joins.

**Architecture:** Introduce a semantic "constructible route" layer between raw pathfinding and final geometry. Pathfinders choose segment intent such as surface road, cut, fill, tunnel, short arch bridge, or full bridge; a rebuilt centerline processor then applies the RoadWeaver mixed interpolation flow; corridor and geometry planners materialize those semantics into roadbed and bridge shapes; finally, construction runtime executes the same semantic edits so preview, auto-routing, and final build stay aligned.

**Tech Stack:** Java 17, Forge 1.20.1, existing road planner services, RoadWeaver references under `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury`, JUnit 5 test suite under `src/test/java`

---

## File Structure Lock-In

### New / expanded responsibilities

- `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java`
  - pick constructible land path candidates instead of surface-only candidates
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
  - resolve route columns into semantic constructible segments and hand them to the centerline layer
- `src/main/java/com/monpai/sailboatmod/construction/RoadBezierCenterline.java`
  - replace current lightweight centerline logic with a RoadWeaver-style mixed interpolation and spline pipeline
- `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
  - add short arch bridge span planning and continuous approach deck segments
- `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
  - consume semantic route segments and create road, tunnel, arch bridge, and full bridge slices with closure bands
- `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
  - materialize semantic corridor slices into roadbed, fill, cut, tunnel, arch bridge, and bridge approach geometry
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - execute semantic terrain edits, tunnel clearing, short arch bridge placement, and existing full bridge runtime

### Existing tests to extend

- `src/test/java/com/monpai/sailboatmod/construction/RoadBezierCenterlineTest.java`
- `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
- `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
- `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

### Likely new test coverage file

- `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderConstructibleRouteTest.java`
  - focused on cut/fill, tunnel, and short-water arch bridge route selection

---

### Task 1: Add constructible route semantics to land-road planning

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderConstructibleRouteTest.java`

- [ ] **Step 1: Write the failing constructible-route tests**

```java
@Test
void plannerPrefersCutFillBeforeFailingOnBrokenGround() {
    PlannedPathResult result = LandRoadHybridPathfinder.find(
            level,
            new BlockPos(0, 64, 0),
            new BlockPos(8, 64, 0),
            Set.of(),
            Set.of(),
            context
    );

    assertTrue(result.success(), result.failureReason());
    assertTrue(result.path().size() >= 2, result.path().toString());
    assertTrue(result.path().stream().anyMatch(pos -> pos.getY() != 64), result.path().toString());
}

@Test
void plannerUsesShortArchBridgeForSmallWaterSpan() {
    PlannedPathResult result = LandRoadHybridPathfinder.find(
            level,
            new BlockPos(0, 64, 0),
            new BlockPos(10, 64, 0),
            Set.of(),
            Set.of(),
            context
    );

    assertTrue(result.success(), result.failureReason());
    assertTrue(result.path().stream().anyMatch(pos -> pos.getY() > 64), result.path().toString());
}

@Test
void plannerAllowsTunnelWhenMountainWallBlocksSurfacePath() {
    PlannedPathResult result = LandRoadHybridPathfinder.find(
            level,
            new BlockPos(0, 64, 0),
            new BlockPos(12, 64, 0),
            Set.of(),
            Set.of(),
            context
    );

    assertTrue(result.success(), result.failureReason());
    assertTrue(result.path().stream().anyMatch(pos -> pos.getY() < 64), result.path().toString());
}
```

- [ ] **Step 2: Run the new pathfinder test file to verify it fails**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.nation.service.LandRoadHybridPathfinderConstructibleRouteTest`

Expected: FAIL because current pathfinder still treats discontinuous land as surface-only and cannot classify or prefer cut/fill, short arch bridge, or tunnel semantics.

- [ ] **Step 3: Add semantic route classification to the pathfinder**

```java
enum ConstructibleSegmentType {
    SURFACE,
    FILL,
    CUT,
    TUNNEL,
    SHORT_ARCH_BRIDGE,
    FULL_BRIDGE
}

record ConstructibleSegment(int startIndex, int endIndex, ConstructibleSegmentType type) {}

record ConstructibleRoute(List<BlockPos> path, List<ConstructibleSegment> segments) {}
```

```java
private static ConstructibleSegmentType classifyConstructibleSegment(RouteColumn column,
                                                                     int waterSpan,
                                                                     int verticalDelta,
                                                                     boolean blockedByTerrain) {
    if (waterSpan > 0 && waterSpan <= 8) {
        return ConstructibleSegmentType.SHORT_ARCH_BRIDGE;
    }
    if (waterSpan > 8 || column.bridge()) {
        return ConstructibleSegmentType.FULL_BRIDGE;
    }
    if (blockedByTerrain && verticalDelta >= 4) {
        return ConstructibleSegmentType.TUNNEL;
    }
    if (blockedByTerrain) {
        return ConstructibleSegmentType.CUT;
    }
    if (verticalDelta > 1) {
        return ConstructibleSegmentType.FILL;
    }
    return ConstructibleSegmentType.SURFACE;
}
```

- [ ] **Step 4: Route the semantic path through `RoadPathfinder`**

```java
ConstructibleRoute route = buildConstructibleRoute(rawPath, level, context);
List<BlockPos> finalized = RoadBezierCenterline.build(
        route.path(),
        pos -> sampleSurface(level, pos, blockedColumns, context, false),
        blockedColumns,
        route.segments()
);
```

- [ ] **Step 5: Run the constructible-route tests to verify they pass**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.nation.service.LandRoadHybridPathfinderConstructibleRouteTest`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderConstructibleRouteTest.java
git commit -m "Add constructible land route semantics"
```

### Task 2: Rebuild centerline processing with the RoadWeaver mixed interpolation flow

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadBezierCenterline.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadBezierCenterlineTest.java`
- Reference: `F:/Codex/Ref/RoadWeaver-1.20.1-Architectury/common/src/main/java/net/shiroha233/roadweaver/pathfinding/impl/PathPostProcessor.java`
- Reference: `F:/Codex/Ref/RoadWeaver-1.20.1-Architectury/common/src/main/java/net/shiroha233/roadweaver/pathfinding/impl/SplineHelper.java`

- [ ] **Step 1: Write the failing centerline tests for short protected runs and slight-offset closure**

```java
@Test
void slightOffsetJoinDoesNotCreateGapAcrossSingleColumnShift() {
    List<BlockPos> centerline = RoadBezierCenterline.build(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 64, 0),
                    new BlockPos(2, 64, 0),
                    new BlockPos(3, 64, 1),
                    new BlockPos(4, 64, 1)
            ),
            flatSampler(),
            Set.of(),
            List.of()
    );

    assertTrue(isContiguous(centerline), centerline.toString());
    assertTrue(hasUniqueColumns(centerline), centerline.toString());
}

@Test
void protectedBridgeRunStaysStraightWhileLandSegmentsStaySmooth() {
    List<BlockPos> centerline = RoadBezierCenterline.build(
            routeNodes,
            protectedBridgeSampler(),
            Set.of(),
            List.of(new ConstructibleSegment(2, 5, ConstructibleSegmentType.FULL_BRIDGE))
    );

    assertTrue(centerline.stream()
            .filter(pos -> pos.getX() >= 2 && pos.getX() <= 5)
            .allMatch(pos -> pos.getZ() == 0), centerline.toString());
}
```

- [ ] **Step 2: Run the focused centerline tests to verify they fail**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadBezierCenterlineTest`

Expected: FAIL on the new tests because the current centerline helper still relies on lightweight smoothing and does not fully follow the RoadWeaver mixed pipeline.

- [ ] **Step 3: Replace the current build flow with the mixed RoadWeaver-style pipeline**

```java
public static List<BlockPos> build(List<BlockPos> routeNodes,
                                   Function<BlockPos, SurfaceSample> sampler,
                                   Set<Long> blockedColumns,
                                   List<ConstructibleSegment> protectedSegments) {
    List<BlockPos> simplified = simplifyColumns(routeNodes);
    boolean[] protectedMask = protectedMask(simplified, sampler, blockedColumns, protectedSegments);
    List<BlockPos> straightened = straightenProtectedRuns(simplified, protectedMask);
    List<BlockPos> relaxed = relaxPathSkippingProtectedRuns(straightened, protectedMask);
    List<CurvePoint> spline = generateSplinePoints(relaxed, protectedMask);
    return rasterizeContinuousGrid(spline, routeNodes, sampler, blockedColumns, protectedMask);
}
```

```java
private static boolean[] protectedMask(List<BlockPos> nodes,
                                       Function<BlockPos, SurfaceSample> sampler,
                                       Set<Long> blockedColumns,
                                       List<ConstructibleSegment> segments) {
    boolean[] mask = new boolean[nodes.size()];
    for (ConstructibleSegment segment : segments) {
        if (segment.type() == ConstructibleSegmentType.FULL_BRIDGE
                || segment.type() == ConstructibleSegmentType.SHORT_ARCH_BRIDGE
                || segment.type() == ConstructibleSegmentType.TUNNEL) {
            for (int i = segment.startIndex(); i <= segment.endIndex(); i++) {
                mask[i] = true;
            }
        }
    }
    return mask;
}
```

- [ ] **Step 4: Keep the rasterization invariants explicit**

```java
private static boolean isValidCandidatePath(List<BlockPos> candidate) {
    return isContiguous(candidate)
            && hasUniqueColumns(candidate)
            && hasNoImmediateDirectionReversal(candidate);
}
```

- [ ] **Step 5: Run the centerline tests to verify they pass**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadBezierCenterlineTest`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadBezierCenterline.java src/test/java/com/monpai/sailboatmod/construction/RoadBezierCenterlineTest.java
git commit -m "Rebuild road centerline with mixed interpolation pipeline"
```

### Task 3: Add short arch bridge and continuous bridge approach geometry

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`

- [ ] **Step 1: Write the failing geometry tests**

```java
@Test
void shortWaterCrossingProducesShortArchBridgeInsteadOfFullPierBridge() {
    RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
            centerPath,
            new RoadPlacementPlan.BridgeRange(2, 6),
            index -> index >= 2 && index <= 6,
            index -> false,
            index -> 62,
            index -> 63,
            index -> true
    );

    assertEquals(RoadBridgePlanner.BridgeMode.ARCH_SPAN, plan.mode());
}

@Test
void bridgeApproachUsesContinuousRaisedRampWithoutFragmentedPlatforms() {
    int[] heights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(centerPath, List.of(spanPlan));

    assertEquals(65, heights[1]);
    assertTrue(heights[2] >= heights[1], Arrays.toString(heights));
    assertTrue(heights[3] > heights[2], Arrays.toString(heights));
}

@Test
void turnClosureBandFillsOuterEdgeOnSlightBridgeTurn() {
    RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, List.of(spanPlan), placementHeights);

    assertTrue(hasSurfaceClosure(plan.slices().get(2).surfacePositions(), plan.slices().get(3).surfacePositions()));
}
```

- [ ] **Step 2: Run the bridge and slope tests to verify they fail**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadBridgePlannerTest --tests com.monpai.sailboatmod.construction.RoadCorridorPlannerTest --tests com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest`

Expected: FAIL because the current planner does not yet distinguish short arch bridges from full terrain bridges and still allows fragmented bridge turn closures and broken approach shape in edge cases.

- [ ] **Step 3: Add explicit short-water arch bridge classification**

```java
private static boolean shouldUseShortArchBridge(int start, int end, IntPredicate waterAt) {
    int waterColumns = 0;
    for (int i = start; i <= end; i++) {
        if (waterAt.test(i)) {
            waterColumns++;
        }
    }
    return waterColumns > 0 && waterColumns <= 8;
}
```

```java
if (shouldUseShortArchBridge(start, end, waterAt)) {
    return buildArchSpan(centerPath, start, end, terrainYAt);
}
```

- [ ] **Step 4: Keep full bridge approaches continuous and bridge-only**

```java
segments.add(new BridgeDeckSegment(start, start, BridgeDeckSegmentType.BRIDGE_HEAD_PLATFORM, startDeckY, startDeckY));
appendStructuredApproachSegments(segments, start, mainStartIndex, startDeckY, mainDeckY, true);
segments.add(new BridgeDeckSegment(end, end, BridgeDeckSegmentType.BRIDGE_HEAD_PLATFORM, endDeckY, endDeckY));
appendStructuredApproachSegments(segments, end, mainEndIndex, endDeckY, mainDeckY, false);
```

- [ ] **Step 5: Ensure turn closure consumes direction-aware bands**

```java
if (requiresAdjacentSliceClosureRepair(current, next, placementHeights[i], placementHeights[i + 1])) {
    surfacePositionsByIndex.set(i + 1, ensureTransitionOverlap(surfacePositionsByIndex.get(i), surfacePositionsByIndex.get(i + 1)));
    surfacePositionsByIndex.set(i, ensureTransitionOverlap(surfacePositionsByIndex.get(i + 1), surfacePositionsByIndex.get(i)));
}
```

- [ ] **Step 6: Run the bridge and slope tests to verify they pass**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadBridgePlannerTest --tests com.monpai.sailboatmod.construction.RoadCorridorPlannerTest --tests com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java
git commit -m "Add arch water crossings and continuous bridge approaches"
```

### Task 4: Materialize cut, fill, and tunnel geometry across full road width

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Write the failing cut/fill and tunnel geometry tests**

```java
@Test
void cutSegmentOwnsFullRoadWidthExcavationInsteadOfCenterOnly() {
    RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, semanticSegments, placementHeights);

    assertTrue(plan.slices().stream()
            .flatMap(slice -> slice.excavationPositions().stream())
            .map(BlockPos::getZ)
            .distinct()
            .count() > 1);
}

@Test
void tunnelSegmentClearsHeadroomAcrossRoadWidth() {
    Object advanced = invokeAdvanceRoadBuildSteps(level, newRoadConstructionJob(level, "manual|test|tunnel", plan), 8);

    assertTrue(readRecordComponentAsInt(advanced, "placedStepCount") > 0);
    assertEquals(Blocks.AIR, level.getBlockState(new BlockPos(4, 66, 1)).getBlock());
}
```

- [ ] **Step 2: Run the focused corridor/runtime tests to verify they fail**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadCorridorPlannerTest --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest`

Expected: FAIL because cut and tunnel segments are still only partially represented in geometry and runtime clearing.

- [ ] **Step 3: Add semantic slice handling for cut, fill, and tunnel**

```java
switch (segmentType) {
    case CUT -> buildCutSurfacePositions(centerPath, i, deckHeights);
    case FILL -> buildFillSurfacePositions(centerPath, i, deckHeights);
    case TUNNEL -> buildTunnelSurfacePositions(centerPath, i, deckHeights);
    default -> buildSurfacePositions(centerPath, i, deckHeights, segmentKind);
}
```

```java
private static List<BlockPos> buildTunnelClearancePositions(List<BlockPos> surfacePositions, int clearHeight) {
    LinkedHashSet<BlockPos> clearance = new LinkedHashSet<>();
    for (BlockPos surface : surfacePositions) {
        for (int y = 1; y <= clearHeight; y++) {
            clearance.add(surface.above(y));
        }
    }
    return List.copyOf(clearance);
}
```

- [ ] **Step 4: Make runtime clear and fill across the full slice footprint**

```java
for (BlockPos excavationPos : slice.excavationPositions()) {
    clearRoadBlock(level, excavationPos, true);
}
for (BlockPos clearancePos : slice.clearancePositions()) {
    clearRoadBlock(level, clearancePos, false);
}
```

- [ ] **Step 5: Run the corridor/runtime tests to verify they pass**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadCorridorPlannerTest --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Add full-width cut fill and tunnel road geometry"
```

### Task 5: Align construction runtime with semantic route execution

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Write the failing runtime tests for semantic execution**

```java
@Test
void shortArchBridgeConstructionBuildsWithoutEscalatingToFullBridgeSupportColumns() {
    Object advanced = invokeAdvanceRoadBuildSteps(level, newRoadConstructionJob(level, "manual|test|arch_runtime", plan), 12);

    assertTrue(readRecordComponentAsInt(advanced, "placedStepCount") > 0);
    assertTrue(plan.corridorPlan().slices().stream().allMatch(slice -> slice.supportPositions().isEmpty()));
}

@Test
void cutSegmentConstructionReplacesNaturalTopBlocksInsteadOfFloatingAboveThem() {
    Object advanced = invokeAdvanceRoadBuildSteps(level, newRoadConstructionJob(level, "manual|test|cut_runtime", plan), 12);

    assertEquals(Blocks.STONE_BRICK_SLAB, level.getBlockState(new BlockPos(3, 64, 0)).getBlock());
}
```

- [ ] **Step 2: Run the runtime tests to verify they fail**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest`

Expected: FAIL because runtime still mainly assumes ordinary road and full bridge behavior.

- [ ] **Step 3: Thread semantic route behaviors into placement decisions**

```java
private static boolean tryPlaceSemanticRoadStep(ServerLevel level,
                                                RoadGeometryPlanner.RoadBuildStep step,
                                                ConstructibleSegmentType segmentType,
                                                RoadPlacementStyle style) {
    return switch (segmentType) {
        case CUT, TUNNEL -> clearRoadDeckSpace(level, step.pos()) && tryPlaceRoad(level, step.pos(), style);
        case FILL -> tryPlaceRoad(level, step.pos(), style);
        case SHORT_ARCH_BRIDGE -> tryPlaceRoad(level, step.pos(), style);
        default -> tryPlaceRoad(level, step.pos(), style);
    };
}
```

- [ ] **Step 4: Keep temporary bridge scaffolding only for full bridge runtime**

```java
if (segmentType == ConstructibleSegmentType.FULL_BRIDGE && shouldAttemptTemporaryBridgeScaffolding(style, step)) {
    TemporaryRoadScaffoldingResult scaffoldingResult = ensureTemporaryBridgeScaffolding(level, step, rollbackStates, temporaryScaffoldPositions);
}
```

- [ ] **Step 5: Run the runtime tests to verify they pass**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Align road runtime with constructible route semantics"
```

### Task 6: Full verification and integration cleanup

**Files:**
- Modify as needed: touched files from Tasks 1-5 only
- Test: `src/test/java/com/monpai/sailboatmod/...` touched suites

- [ ] **Step 1: Run the focused regression suite**

Run: `./gradlew.bat test --tests com.monpai.sailboatmod.nation.service.LandRoadHybridPathfinderConstructibleRouteTest --tests com.monpai.sailboatmod.construction.RoadBezierCenterlineTest --tests com.monpai.sailboatmod.construction.RoadBridgePlannerTest --tests com.monpai.sailboatmod.construction.RoadCorridorPlannerTest --tests com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest`

Expected: PASS

- [ ] **Step 2: Run compile verification**

Run: `./gradlew.bat compileJava`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run one full build before handoff**

Run: `./gradlew.bat build`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Review acceptance checklist**

```text
[ ] broken ground no longer hard-fails when cut/fill is possible
[ ] short water (<= 8) produces short arch bridge
[ ] mountain barriers can cut through or tunnel
[ ] bridge approaches are continuous ramps, not fragmented platforms
[ ] slight offset joins remain closed
[ ] bridge turns remain closed
[ ] land turns remain closed
[ ] auto and manual road generation use the same centerline family
```

- [ ] **Step 5: Commit final integration**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadBezierCenterline.java src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/construction/RoadBezierCenterlineTest.java src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderConstructibleRouteTest.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Redesign constructible land roads and curve processing"
```

---

## Self-Review

### Spec coverage

- Constructible path planning: covered by Task 1
- Small water arch bridge / full bridge split: covered by Tasks 1 and 3
- Cut/fill/tunnel geometry: covered by Task 4
- Continuous bridge approaches: covered by Task 3
- Turn closure rules: covered by Tasks 2 and 3
- RoadWeaver-style mixed interpolation curve system: covered by Task 2
- Runtime semantic execution: covered by Task 5
- Acceptance verification: covered by Task 6

### Placeholder scan

- No `TODO`, `TBD`, or deferred implementation steps remain
- Every task includes explicit files, commands, and code targets

### Type consistency

- Semantic route layer uses `ConstructibleSegmentType`, `ConstructibleSegment`, and `ConstructibleRoute` consistently across Tasks 1-5
- Centerline task expects semantic segments from the pathfinder tasks
- Runtime tasks consume the same segment types rather than introducing a second route semantic enum
