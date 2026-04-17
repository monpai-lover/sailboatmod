# Bridge Slope, Exclusion Stability, and Bridge Placement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep bridgehead slopes structurally smooth, reject finalized routes that re-enter excluded columns, and verify that bridge structures preview, place, and roll back completely.

**Architecture:** Keep the current bridge planner, corridor planner, preview, and runtime construction system. Extend the existing span-plan height pipeline with a bridge-influence mask, strengthen final route acceptance in the manual planner, and add bridge-specific construction regressions around build steps, runtime placement, and rollback ownership.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Gradle

---

## File Map

- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
  - Owns span-based height shaping and terrain-envelope clamping.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
  - Owns final manual-route candidate selection and excluded-column validation.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - Owns bridge placement artifacts, runtime road placement, and rollback tracking.
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`
  - Add bridgehead slope regression cases.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
  - Add excluded-zone regression cases for final route acceptance.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
  - Add runtime bridge-placement completeness checks.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java`
  - Add rollback coverage checks for bridge-owned blocks.

### Task 1: Lock In Bridgehead Slope Regressions

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`

- [ ] **Step 1: Write the failing bridgehead touchdown test**

```java
@Test
void bridgeInfluenceColumnsStayAboveTerrainClampNearTouchdown() {
    List<BlockPos> centerPath = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0),
            new BlockPos(2, 64, 0),
            new BlockPos(3, 64, 0),
            new BlockPos(4, 64, 0),
            new BlockPos(5, 64, 0),
            new BlockPos(6, 64, 0),
            new BlockPos(7, 64, 0),
            new BlockPos(8, 64, 0),
            new BlockPos(9, 64, 0)
    );

    RoadBridgePlanner.BridgeSpanPlan spanPlan = RoadBridgePlanner.planBridgeSpanForTest(
            centerPath,
            new RoadPlacementPlan.BridgeRange(2, 7),
            index -> index >= 2 && index <= 7,
            index -> index >= 4 && index <= 5,
            index -> index <= 1 || index >= 8 ? 66 : 40,
            index -> 63,
            index -> true
    );

    int[] heights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(
            centerPath,
            List.of(spanPlan)
    );

    assertTrue(heights[1] > 67, java.util.Arrays.toString(heights));
    assertTrue(heights[8] > 67, java.util.Arrays.toString(heights));
}
```

- [ ] **Step 2: Run the slope test to verify it fails**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest.bridgeInfluenceColumnsStayAboveTerrainClampNearTouchdown`

Expected: `FAIL` because bridge-adjacent touchdown columns are still clamped back near terrain.

- [ ] **Step 3: Write the failing smoothing continuity test**

```java
@Test
void bridgeTouchdownProfileStaysSmoothAcrossThreeSegmentWindows() {
    List<BlockPos> centerPath = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0),
            new BlockPos(2, 64, 0),
            new BlockPos(3, 64, 0),
            new BlockPos(4, 64, 0),
            new BlockPos(5, 64, 0),
            new BlockPos(6, 64, 0),
            new BlockPos(7, 64, 0),
            new BlockPos(8, 64, 0),
            new BlockPos(9, 64, 0)
    );

    RoadBridgePlanner.BridgeSpanPlan spanPlan = RoadBridgePlanner.planBridgeSpanForTest(
            centerPath,
            new RoadPlacementPlan.BridgeRange(2, 7),
            index -> index >= 2 && index <= 7,
            index -> index >= 4 && index <= 5,
            index -> index <= 1 || index >= 8 ? 66 : 40,
            index -> 63,
            index -> true
    );

    int[] heights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(centerPath, List.of(spanPlan));

    for (int i = 0; i + 3 < heights.length; i++) {
        assertTrue(Math.abs(heights[i + 3] - heights[i]) <= 1, java.util.Arrays.toString(heights));
    }
}
```

- [ ] **Step 4: Run the continuity test to verify it fails or exposes the current clamp**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest.bridgeTouchdownProfileStaysSmoothAcrossThreeSegmentWindows`

Expected: `FAIL` if the touchdown profile still gets crushed into a sharp transition.

- [ ] **Step 5: Implement a bridge-influence mask in geometry**

Modify `RoadGeometryPlanner.java` around `buildPlacementHeightProfileFromSpanPlans(...)`, `propagateBridgeApproachHeightsFromSpanPlans(...)`, and `constrainToTerrainEnvelopeFromSpanPlans(...)`.

```java
private static boolean[] bridgeInfluenceColumnsFromSpanPlans(int length,
                                                             List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
    boolean[] influenced = new boolean[length];
    if (length == 0 || bridgePlans == null || bridgePlans.isEmpty()) {
        return influenced;
    }
    for (RoadBridgePlanner.BridgeSpanPlan plan : bridgePlans) {
        if (plan == null || !plan.valid()) {
            continue;
        }
        int start = clampIndex(plan.startIndex(), length);
        int end = clampIndex(plan.endIndex(), length);
        for (int i = Math.max(0, start - 1); i <= Math.min(length - 1, end + 1); i++) {
            influenced[i] = true;
        }
        for (RoadBridgePlanner.BridgeDeckSegment segment : plan.deckSegments()) {
            if (segment == null) {
                continue;
            }
            if (segment.type() == RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_UP
                    || segment.type() == RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_DOWN
                    || segment.type() == RoadBridgePlanner.BridgeDeckSegmentType.MAIN_LEVEL) {
                int segmentStart = clampIndex(segment.startIndex(), length);
                int segmentEnd = clampIndex(segment.endIndex(), length);
                for (int i = segmentStart; i <= segmentEnd; i++) {
                    influenced[i] = true;
                }
            }
        }
    }
    return influenced;
}
```

- [ ] **Step 6: Apply the influence mask in the terrain clamp**

```java
private static int[] constrainToTerrainEnvelopeFromSpanPlans(int[] placementHeights,
                                                             int[] sampledHeights,
                                                             List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
    int[] constrained = placementHeights.clone();
    boolean[] bridgeInfluenceColumns = bridgeInfluenceColumnsFromSpanPlans(constrained.length, bridgePlans);
    boolean[] terrainLockedColumns = steepTerrainLockMask(sampledHeights);
    for (int i = 0; i < constrained.length; i++) {
        if (bridgeInfluenceColumns[i] || !terrainLockedColumns[i]) {
            continue;
        }
        int minDeckHeight = sampledHeights[i];
        int maxDeckHeight = sampledHeights[i] + 1;
        constrained[i] = Math.max(minDeckHeight, Math.min(maxDeckHeight, constrained[i]));
    }
    return constrained;
}
```

- [ ] **Step 7: Run the targeted slope tests to verify they pass**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest`

Expected: `BUILD SUCCESSFUL` and both new bridgehead tests pass.

- [ ] **Step 8: Commit the geometry fix**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java
git commit -m "Fix bridgehead touchdown slope clamping"
```

### Task 2: Make Excluded-Zone Avoidance A Hard Finalization Invariant

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`

- [ ] **Step 1: Write the failing no-safe-candidate test**

```java
@Test
void finalizePlannedPathReturnsEmptyWhenEveryCandidateReentersExcludedColumns() {
    List<BlockPos> finalized = invokeFinalizePlannedPath(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(3, 64, 0),
                    new BlockPos(6, 64, 0)
            ),
            new boolean[] {false, false, false},
            List.of(),
            Set.of(
                    BlockPos.asLong(0, 0, 0),
                    BlockPos.asLong(1, 0, 0),
                    BlockPos.asLong(2, 0, 0),
                    BlockPos.asLong(3, 0, 0),
                    BlockPos.asLong(4, 0, 0),
                    BlockPos.asLong(5, 0, 0),
                    BlockPos.asLong(6, 0, 0)
            )
    );

    assertTrue(finalized.isEmpty());
}
```

- [ ] **Step 2: Run the new manual planner test to verify it fails**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest.finalizePlannedPathReturnsEmptyWhenEveryCandidateReentersExcludedColumns`

Expected: `FAIL` if the finalizer still keeps an exclusion-invalid candidate.

- [ ] **Step 3: Strengthen final candidate validation**

Modify `ManualRoadPlannerService.java` near `finalizePlannedPath(...)` and `validateFinalPlannedPath(...)`.

```java
private static List<BlockPos> finalizePlannedPath(List<BlockPos> path,
                                                  boolean[] bridgeMask,
                                                  List<RoadNetworkRecord> roads,
                                                  Set<Long> excludedColumns) {
    List<BlockPos> raw = validateFinalPlannedPath(path, excludedColumns) ? List.copyOf(path) : List.of();
    List<BlockPos> processed = RoadPathPostProcessor.process(path, bridgeMask);
    List<BlockPos> processedSafe = validateFinalPlannedPath(processed, excludedColumns) ? List.copyOf(processed) : List.of();
    List<BlockPos> base = !processedSafe.isEmpty() ? processedSafe : raw;
    if (base.size() < 2) {
        return List.of();
    }
    List<BlockPos> snapped = RoadNetworkSnapService.snapPath(base, roads);
    return validateFinalPlannedPath(snapped, excludedColumns) ? snapped : base;
}
```

- [ ] **Step 4: Keep excluded-column rejection explicit**

```java
private static boolean validateFinalPlannedPath(List<BlockPos> candidate,
                                                Set<Long> excludedColumns) {
    if (candidate == null || candidate.size() < 2) {
        return false;
    }
    if (!SegmentedRoadPathOrchestrator.isContinuousResolvedPath(
            candidate.get(0),
            candidate.get(candidate.size() - 1),
            candidate
    )) {
        return false;
    }
    if (excludedColumns == null || excludedColumns.isEmpty()) {
        return true;
    }
    for (BlockPos pos : candidate) {
        if (isExcludedColumn(pos, excludedColumns)) {
            return false;
        }
    }
    return true;
}
```

- [ ] **Step 5: Run the manual planner regression tests**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest`

Expected: `BUILD SUCCESSFUL`, including:
- `finalizePlannedPathRejectsSnappedFallbackThatReentersExcludedColumns`
- `finalizePlannedPathReturnsEmptyWhenEveryCandidateReentersExcludedColumns`

- [ ] **Step 6: Commit the finalization fix**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java
git commit -m "Harden excluded-zone route finalization"
```

### Task 3: Verify Bridge Build-Step Completeness And Runtime Placement

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Write the failing bridge build-step completeness test**

```java
@Test
void longBridgeBuildStepsIncludeHeadsSupportsAndLights() {
    RoadPlacementPlan plan = longBridgePlanFixture();

    List<BlockPos> buildStepPositions = plan.buildSteps().stream()
            .map(RoadGeometryPlanner.RoadBuildStep::pos)
            .toList();
    List<BlockPos> expectedSupportPositions = plan.corridorPlan().slices().stream()
            .flatMap(slice -> slice.supportPositions().stream())
            .toList();
    List<BlockPos> expectedPierLights = plan.corridorPlan().slices().stream()
            .flatMap(slice -> slice.pierLightPositions().stream())
            .toList();

    assertTrue(buildStepPositions.containsAll(expectedSupportPositions), buildStepPositions.toString());
    assertTrue(buildStepPositions.containsAll(expectedPierLights), buildStepPositions.toString());
}
```

- [ ] **Step 2: Run the bridge build-step test to verify it fails if anything is missing**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest.longBridgeBuildStepsIncludeHeadsSupportsAndLights`

Expected: `FAIL` if any bridge support/light positions are preview-only and not emitted into build steps.

- [ ] **Step 3: Write the failing bridge runtime placement test**

```java
@Test
void longBridgePlacementArtifactsOwnBridgeSupportAndFoundationCoverage() {
    RoadPlacementPlan plan = longBridgePlanFixture();

    List<BlockPos> owned = invokeRoadOwnedBlocks(null, plan);
    List<BlockPos> supports = plan.corridorPlan().slices().stream()
            .flatMap(slice -> slice.supportPositions().stream())
            .toList();

    assertTrue(owned.containsAll(supports), owned.toString());
}
```

- [ ] **Step 4: Run the runtime ownership test to verify it fails if support/foundation coverage is incomplete**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest.longBridgePlacementArtifactsOwnBridgeSupportAndFoundationCoverage`

Expected: `FAIL` if support-related owned blocks are missing.

- [ ] **Step 5: Verify the bridge artifact pipeline in `StructureConstructionManager.java`**

Focus on:
- `buildRoadPlacementArtifacts(...)`
- `placeRoadBuildSteps(...)`
- `tryPlaceRoad(...)`
- `roadOwnedBlocks(...)`

Minimal implementation target:

```java
private static boolean tryPlaceRoad(ServerLevel level, BlockPos pos, RoadPlacementStyle style) {
    if (!clearRoadDeckSpace(level, pos)) {
        return false;
    }
    BlockState at = level.getBlockState(pos);
    if (at.equals(style.surface())) {
        return false;
    }
    boolean replacingRoadSurface = canReplaceRoadSurface(at, style.surface());
    if (!replacingRoadSurface && isRoadSurface(at)) {
        return false;
    }
    if (!replacingRoadSurface && !isRoadPlacementReplaceable(at)) {
        return false;
    }
    stabilizeRoadFoundation(level, pos, style);
    BlockState below = level.getBlockState(pos.below());
    if (requiresSupportedRoadPlacement(style, below)) {
        return false;
    }
    level.setBlock(pos, style.surface(), Block.UPDATE_ALL);
    return true;
}
```

- [ ] **Step 6: Run the bridge construction tests**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest --tests com.monpai.sailboatmod.construction.BuilderHammerSupportTest`

Expected: `BUILD SUCCESSFUL`, including:
- `failedStructuralRoadStepsPreventLaterDecorPlacement`
- `attemptedRoadBuildStepRemainsVisibleUntilWorldStateMatchesPlan`
- `longBridgeBuildStepsIncludeHeadsSupportsAndLights`
- `longBridgePlacementArtifactsOwnBridgeSupportAndFoundationCoverage`

- [ ] **Step 7: Commit the bridge construction stability fix**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Stabilize bridge placement artifacts and runtime placement"
```

### Task 4: Verify Bridge Rollback Coverage

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java`

- [ ] **Step 1: Write the failing bridge light rollback coverage test**

```java
@Test
void rollbackTrackedPositionsIncludeBridgePierLightsAndRailingLights() {
    RoadPlacementPlan plan = longBridgePlanFixture();

    List<BlockPos> tracked = invokeRoadRollbackTrackedPositions(null, plan);
    List<BlockPos> lightPositions = plan.corridorPlan().slices().stream()
            .flatMap(slice -> java.util.stream.Stream.concat(
                    slice.railingLightPositions().stream(),
                    slice.pierLightPositions().stream()
            ))
            .toList();

    assertTrue(tracked.containsAll(lightPositions), tracked.toString());
}
```

- [ ] **Step 2: Run the rollback coverage test to verify it fails if lights are omitted**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest.rollbackTrackedPositionsIncludeBridgePierLightsAndRailingLights`

Expected: `FAIL` if light ownership is not fully tracked for rollback.

- [ ] **Step 3: Ensure rollback tracking covers all owned bridge artifacts**

Verify `roadOwnedBlocks(...)`, `collectCorridorOwnedBlocks(...)`, `roadRollbackTrackedPositions(...)`, and `captureLiveRoadFoundation(...)` preserve:
- support positions
- pier light positions
- railing light positions
- derived support/foundation positions

Expected implementation shape:

```java
private static List<BlockPos> roadRollbackTrackedPositions(ServerLevel level, RoadPlacementPlan plan) {
    if (plan == null) {
        return List.of();
    }
    LinkedHashSet<BlockPos> tracked = new LinkedHashSet<>(roadOwnedBlocks(level, plan));
    for (RoadGeometryPlanner.RoadBuildStep step : plan.buildSteps()) {
        if (step == null || step.pos() == null) {
            continue;
        }
        tracked.add(step.pos().above());
    }
    return tracked.isEmpty() ? List.of() : List.copyOf(tracked);
}
```

- [ ] **Step 4: Run the lifecycle regression suite**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest`

Expected: `BUILD SUCCESSFUL`, including:
- `rollbackTrackedPositionsIncludeBridgeSupportsAndLights`
- `rollbackTrackedPositionsIncludePlannedPierColumnsFromLongBridgePlan`
- `rollbackTrackedPositionsIncludeBridgePierLightsAndRailingLights`

- [ ] **Step 5: Commit the rollback coverage fix**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java
git commit -m "Expand bridge rollback coverage"
```

### Task 5: Full Bridge Verification And Packaging

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerPreviewPacketTest.java` (only if bridge-preview regressions need expectation updates)
- Test: existing bridge-related tests and full build output

- [ ] **Step 1: Run the focused bridge/planner regression suite**

Run:

```bash
.\gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest --tests com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest --tests com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest --tests com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacketTest
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run the full build**

Run: `.\gradlew.bat build`

Expected:
- `BUILD SUCCESSFUL`
- updated jars under `build/libs/`

- [ ] **Step 3: Confirm the expected jars exist**

Run:

```bash
Get-ChildItem build/libs | Select-Object Name,Length,LastWriteTime
```

Expected: entries including `sailboatmod-*.jar` and `sailboatmod-*-all.jar`

- [ ] **Step 4: Commit the verification sweep**

```bash
git add src/test/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerPreviewPacketTest.java
git commit -m "Verify bridge preview and packaging regressions"
```

## Self-Review

- Spec coverage:
  - Bridgehead smoothing: Task 1
  - Excluded-zone hard finalization: Task 2
  - Bridge placement stability: Task 3
  - Bridge rollback completeness: Task 4
  - Full verification and jar build: Task 5
- Placeholder scan:
  - No `TODO`, `TBD`, or implicit “write tests later” steps remain.
- Type consistency:
  - Uses existing method names and files currently in the repo:
    - `buildPlacementHeightProfileFromSpanPlans(...)`
    - `constrainToTerrainEnvelopeFromSpanPlans(...)`
    - `finalizePlannedPath(...)`
    - `placeRoadBuildSteps(...)`
    - `roadRollbackTrackedPositions(...)`

