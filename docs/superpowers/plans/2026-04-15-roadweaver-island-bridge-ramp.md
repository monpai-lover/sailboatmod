# RoadWeaver-Inspired Island Bridge Ramp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make island routing perform one bounded land probe before forcing bridge planning, generate straight slab-first low-slope bridge ramps, and keep planning/build execution bounded so route planning and shutdown stop hanging.

**Architecture:** The implementation keeps the existing manual planner, bridge span planner, corridor planner, and construction manager, but adds stricter island-routing state, explicit ramp/run-rise metadata, and tighter planning budgets. The bridge system continues to use short arch spans for short crossings and pier-node spans for longer ones, while approach ramps become a separate straight-only geometry type.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, Gradle, JUnit 5

---

## File Map

- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
  - Add one-shot island land-probe policy, distance/water-trigger cutoffs, and richer failure mapping.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningSnapshot.java`
  - Carry any extra island/shore probe metadata needed by the planner.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningIslandClassifier.java`
  - Expose a stricter island summary that can drive bridge-first fallback without hardcoded fixed distance.
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
  - Add straight ramp constraints, gentle run/rise smoothing, and candidate rejection when a legal ramp cannot fit.
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
  - Mark flat bridgehead, ramp, and main-span slices consistently from the new bridge span data.
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
  - Emit slab-first ramp surfaces and connector blocks while preserving no-turn ramps.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - Keep support/deck/decor ordering strict and make sure plan application does not re-open repeated step retries when new ramp geometry is present.
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

### Task 1: One-Shot Island Routing

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningSnapshot.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningIslandClassifier.java`

- [ ] **Step 1: Write the failing routing tests**

```java
@Test
void islandTargetsAllowExactlyOneShortLandProbeBeforeBridgeFallback() {
    assertEquals(
            new ManualRoadPlannerService.IslandProbePolicy(true, 1, true),
            ManualRoadPlannerService.islandProbePolicyForTest(true, true)
    );
}

@Test
void islandLandProbeStopsWhenDistanceBudgetIsConsumed() {
    assertTrue(ManualRoadPlannerService.shouldAbortIslandLandProbeForTest(
            true,
            10,
            false,
            ManualRoadPlannerService.DEFAULT_ISLAND_LAND_PROBE_DISTANCE
    ));
}

@Test
void islandLandProbeStopsWhenContinuousWaterSignalReturns() {
    assertTrue(ManualRoadPlannerService.shouldAbortIslandLandProbeForTest(
            true,
            2,
            true,
            ManualRoadPlannerService.DEFAULT_ISLAND_LAND_PROBE_DISTANCE
    ));
}
```

- [ ] **Step 2: Run the routing tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: FAIL with missing `IslandProbePolicy`, missing `islandProbePolicyForTest`, or assertions showing island paths still skip land entirely.

- [ ] **Step 3: Implement bounded island probe policy**

```java
static final int DEFAULT_ISLAND_LAND_PROBE_DISTANCE = 10;

record IslandProbePolicy(boolean islandMode,
                         int maxLandProbeAttempts,
                         boolean forceBridgeAfterProbe) {
}

private static IslandProbePolicy islandProbePolicy(RoadPlanningSnapshot snapshot,
                                                   boolean targetIslandLike) {
    if (!targetIslandLike) {
        return new IslandProbePolicy(false, 1, false);
    }
    return new IslandProbePolicy(true, 1, true);
}

private static boolean shouldAbortIslandLandProbe(boolean islandMode,
                                                  int traversedColumns,
                                                  boolean encounteredIslandSignal,
                                                  int maxProbeDistance) {
    if (!islandMode) {
        return false;
    }
    return traversedColumns >= maxProbeDistance || encounteredIslandSignal;
}
```

- [ ] **Step 4: Wire the policy into `buildPlanCandidates`**

```java
IslandProbePolicy probePolicy = islandProbePolicy(snapshot, snapshot != null && snapshot.targetIslandLike());
boolean landAttemptAllowed = !probePolicy.islandMode() || probePolicy.maxLandProbeAttempts() > 0;

if (landAttemptAllowed) {
    PlanCandidate detour = buildIslandAwareLandCandidate(
            level,
            sourceTown,
            targetTown,
            planningContext,
            probePolicy
    );
    if (detour != null) {
        candidates.add(detour);
    }
}

if (candidates.isEmpty() || probePolicy.forceBridgeAfterProbe()) {
    PlanCandidate bridge = buildPlanCandidate(
            level,
            sourceTown,
            targetTown,
            sourceClaims,
            targetClaims,
            blockedColumns,
            excludedColumns,
            data,
            manualRoadId,
            PreviewOptionKind.BRIDGE,
            true,
            planningContext
    );
    if (bridge != null) {
        candidates.add(bridge);
    }
}
```

- [ ] **Step 5: Re-run the routing tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: PASS for the new island probe tests and existing progress/failure mapping tests.

- [ ] **Step 6: Commit the routing change**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningSnapshot.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningIslandClassifier.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java
git commit -m "Refine island road planning fallback"
```

### Task 2: Straight Slab-First Bridge Ramps

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`

- [ ] **Step 1: Write the failing bridge/ramp tests**

```java
@Test
void rejectsPierBridgeWhenStraightRampRunCannotFit() {
    List<BlockPos> centerPath = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0),
            new BlockPos(2, 68, 1),
            new BlockPos(3, 68, 2),
            new BlockPos(4, 64, 2)
    );

    RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
            centerPath,
            new RoadPlacementPlan.BridgeRange(1, 3),
            index -> index >= 1 && index <= 3,
            index -> false,
            index -> 40,
            index -> 63,
            index -> true
    );

    assertFalse(plan.valid());
}

@Test
void corridorTreatsRaisedStraightApproachAsRampBeforeMainSpan() {
    RoadCorridorPlan plan = RoadCorridorPlanner.plan(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 65, 0),
                    new BlockPos(2, 66, 0),
                    new BlockPos(3, 67, 0),
                    new BlockPos(4, 67, 0)
            ),
            List.of(new RoadPlacementPlan.BridgeRange(1, 4)),
            List.of(),
            new int[] {64, 65, 66, 67, 67}
    );

    assertEquals(RoadCorridorPlan.SegmentKind.APPROACH_RAMP, plan.slices().get(1).segmentKind());
    assertEquals(RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH, plan.slices().get(3).segmentKind());
}
```

- [ ] **Step 2: Run the bridge and corridor tests to verify failure**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest" --tests "com.monpai.sailboatmod.construction.RoadCorridorPlannerTest"`

Expected: FAIL because the current planner still accepts bent/short ramps or classifies them as ordinary bridgehead slices.

- [ ] **Step 3: Add explicit straight-ramp metadata and run/rise limits**

```java
private static final int BRIDGE_RAMP_RUN_BLOCKS = 2;
private static final int BRIDGE_RAMP_RISE_BLOCKS = 1;

private static boolean hasStraightRampRun(List<BlockPos> centerPath, int start, int end) {
    int dx = Integer.compare(centerPath.get(end).getX() - centerPath.get(start).getX(), 0);
    int dz = Integer.compare(centerPath.get(end).getZ() - centerPath.get(start).getZ(), 0);
    for (int i = start + 1; i <= end; i++) {
        BlockPos previous = centerPath.get(i - 1);
        BlockPos current = centerPath.get(i);
        if (Integer.compare(current.getX() - previous.getX(), 0) != dx
                || Integer.compare(current.getZ() - previous.getZ(), 0) != dz) {
            return false;
        }
    }
    return true;
}

private static int requiredRampRun(int deckDelta) {
    return Math.max(0, deckDelta * BRIDGE_RAMP_RUN_BLOCKS / BRIDGE_RAMP_RISE_BLOCKS);
}
```

- [ ] **Step 4: Apply slab-first ramp heights in `RoadGeometryPlanner`**

```java
private static void applyLinearBridgeSegment(int[] placementHeights,
                                             RoadBridgePlanner.BridgeDeckSegment segment) {
    int start = clampIndex(segment.startIndex(), placementHeights.length);
    int end = clampIndex(segment.endIndex(), placementHeights.length);
    for (int i = start; i <= end; i++) {
        double t = start == end ? 0.0D : (double) (i - start) / (double) (end - start);
        int target = (int) Math.floor(segment.startDeckY() + t * (segment.endDeckY() - segment.startDeckY()));
        placementHeights[i] = Math.max(placementHeights[i], target);
    }
}

private static boolean shouldUseCorridorStairState(RoadCorridorPlan corridorPlan,
                                                   int index,
                                                   int[] placementHeights) {
    return false;
}
```

- [ ] **Step 5: Re-run the bridge and corridor tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest" --tests "com.monpai.sailboatmod.construction.RoadCorridorPlannerTest"`

Expected: PASS with new ramp rejection and straight-ramp classification coverage.

- [ ] **Step 6: Commit the bridge-ramp change**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java
git commit -m "Smooth bridge ramps with slab-first spans"
```

### Task 3: End-to-End Bridge Plan Ordering

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

- [ ] **Step 1: Write the failing construction-order test**

```java
@Test
void bridgeBuildStepsStayPhaseOrderedSupportDeckDecor() {
    RoadPlacementPlan plan = longBridgePlanFixture();

    List<RoadGeometryPlanner.RoadBuildPhase> phases = plan.buildSteps().stream()
            .map(RoadGeometryPlanner.RoadBuildStep::phase)
            .toList();

    assertEquals(phases.stream().sorted().toList(), phases);
}
```

- [ ] **Step 2: Run the road link tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: FAIL if newly introduced ramp/support ghost blocks are emitted out of phase.

- [ ] **Step 3: Keep support/deck/decor phase assignment centralized**

```java
private static RoadGeometryPlanner.RoadBuildPhase resolveRoadBuildPhase(BlockState state) {
    if (isRoadSupportState(state)) {
        return RoadGeometryPlanner.RoadBuildPhase.SUPPORT;
    }
    if (isRoadDecorState(state)) {
        return RoadGeometryPlanner.RoadBuildPhase.DECOR;
    }
    return RoadGeometryPlanner.RoadBuildPhase.DECK;
}
```

- [ ] **Step 4: Re-run the road link tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: PASS with long-bridge fixtures still producing support-first then deck then lighting/decor.

- [ ] **Step 5: Commit the construction-order change**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Preserve bridge build phase ordering"
```

### Task 4: Planning Budget and Verification Pass

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`

- [ ] **Step 1: Add a failing budget-regression test**

```java
@Test
void islandPlanningDoesNotScheduleRepeatedLandAttemptStages() {
    assertEquals(
            List.of(
                    ManualRoadPlannerService.PlanningStage.TRYING_LAND,
                    ManualRoadPlannerService.PlanningStage.TRYING_BRIDGE
            ),
            ManualRoadPlannerService.planningAttemptStagesForTest(false)
    );
    assertEquals(
            List.of(
                    ManualRoadPlannerService.PlanningStage.TRYING_LAND,
                    ManualRoadPlannerService.PlanningStage.TRYING_BRIDGE
            ),
            ManualRoadPlannerService.planningAttemptStagesForTest(true)
    );
}
```

- [ ] **Step 2: Make the stage model reflect one short land probe plus bridge**

```java
private static List<PlanningStage> planningAttemptStages(boolean targetIslandLike) {
    return targetIslandLike
            ? List.of(PlanningStage.TRYING_LAND, PlanningStage.TRYING_BRIDGE)
            : List.of(PlanningStage.TRYING_LAND, PlanningStage.TRYING_BRIDGE);
}
```

- [ ] **Step 3: Run the focused planner/bridge test suite**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest" --tests "com.monpai.sailboatmod.construction.RoadCorridorPlannerTest" --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: PASS

- [ ] **Step 4: Run compile validation**

Run: `.\gradlew.bat compileJava`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Build the mod jar**

Run: `.\gradlew.bat build`

Expected: `BUILD SUCCESSFUL` and a new jar under `build/libs/`

- [ ] **Step 6: Commit the final integration pass**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java
git commit -m "Bound island planning and bridge ramp generation"
```
