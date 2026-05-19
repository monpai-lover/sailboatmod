# Bridge Road Transition Ramp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make bridge ramps connect cleanly to adjacent road surfaces while preserving ordinary road slope behavior and legacy bridge half-step continuity.

**Architecture:** Keep normal road emission unchanged. Add bridge-side transition expansion so `BridgeStructureEmitter` can absorb up to two adjacent road centerline samples at each bridge head, then resolve road/bridge overlap in those transition columns in favor of bridge ramp/deck blocks. Preserve legacy bridge `BOTTOM/TOP` and `TOP/BOTTOM` ramp profiles whenever the expanded bridge profile has enough samples.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, Gradle.

---

## File Map

- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java`
  - Accept full centerline/span context for bridge emission.
  - Build bridge emission profiles with optional land-side transition road samples.
  - Preserve legacy ramp profiles after expansion.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpander.java`
  - Pass full span context to bridge emitter.
  - Remove road surface/ramp blocks in bridge transition X/Z columns if bridge ramp/deck occupies that column.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeEmissionResult.java`
  - Carry emitted bridge steps and transition X/Z columns back to `RoadNodeStructureExpander`.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeTransitionProfile.java`
  - Build an emission-only bridge centerline with up to two adjacent road samples per side.
- Modify `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`
  - Add regression tests for bridge-to-road transition height, X/Z overlap cleanup, and ramp step continuity.

## Task 1: Add Failing Transition Tests

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`

- [ ] **Step 1: Add bridge transition tests**

Append these tests before `buildStepsUseUniquePositionsForPersistedRoadJobs()`:

```java
    @Test
    void bridgeRampConsumesRoadApproachSoUphillStartsAtRoadSurface() {
        RoadTerrainSampler approachHigherThanWater = new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return x <= 0 ? 66 : 63;
            }

            @Override
            public int waterSurfaceY(int x, int z) {
                return 63;
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return 54;
            }
        };

        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(-4, 66, 0), new BlockPos(0, 66, 0), new BlockPos(8, 63, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                approachHigherThanWater,
                RoadStructureMode.BUILD
        );

        BuildStep bridgeHead = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getZ() == 0)
                .filter(step -> step.pos().getX() == 0)
                .findFirst()
                .orElseThrow();

        assertEquals(66, bridgeHead.pos().getY(),
                "bridge transition should start at the adjacent road surface height instead of sinking below it");
    }

    @Test
    void bridgeTransitionColumnsDoNotKeepSeparateRoadSurfaceBelowOrAboveRamp() {
        RoadTerrainSampler shoreAndWater = new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return x <= 0 ? 66 : 63;
            }

            @Override
            public int waterSurfaceY(int x, int z) {
                return 63;
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return 54;
            }
        };

        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(-4, 66, 0), new BlockPos(0, 66, 0), new BlockPos(8, 63, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                shoreAndWater,
                RoadStructureMode.BUILD
        );

        List<BuildStep> usableAtBridgeHead = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE
                        || step.phase() == BuildPhase.RAMP
                        || step.phase() == BuildPhase.DECK)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getX() == 0 && step.pos().getZ() == 0)
                .toList();

        assertEquals(1, usableAtBridgeHead.size(),
                "road and bridge must not leave two usable surfaces in the same transition X/Z column");
        assertEquals(BuildPhase.RAMP, usableAtBridgeHead.get(0).phase());
    }

    @Test
    void bridgeTransitionKeepsLegacyHalfStepRampContinuityWhenExtraRoadSamplesFit() {
        RoadTerrainSampler flatWater = new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return 63;
            }

            @Override
            public int waterSurfaceY(int x, int z) {
                return 63;
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return 54;
            }
        };

        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(-2, 63, 0),
                        new BlockPos(0, 63, 0),
                        new BlockPos(8, 63, 0),
                        new BlockPos(10, 63, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                flatWater,
                RoadStructureMode.BUILD
        );

        List<BuildStep> centerRamp = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .toList();

        assertFalse(centerRamp.isEmpty());
        for (int index = 1; index < centerRamp.size(); index++) {
            BuildStep previous = centerRamp.get(index - 1);
            BuildStep current = centerRamp.get(index);
            assertTrue(Math.abs(current.pos().getY() - previous.pos().getY()) <= 1,
                    "bridge ramp Y must remain continuous at " + previous.pos() + " -> " + current.pos());
        }
        assertTrue(centerRamp.stream().anyMatch(step -> step.state().getValue(SlabBlock.TYPE) == SlabType.BOTTOM));
        assertTrue(centerRamp.stream().anyMatch(step -> step.state().getValue(SlabBlock.TYPE) == SlabType.TOP));
    }
```

- [ ] **Step 2: Verify the new tests fail**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest.bridgeRampConsumesRoadApproachSoUphillStartsAtRoadSurface" --tests "com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest.bridgeTransitionColumnsDoNotKeepSeparateRoadSurfaceBelowOrAboveRamp" --tests "com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest.bridgeTransitionKeepsLegacyHalfStepRampContinuityWhenExtraRoadSamplesFit"
```

Expected: at least the first two tests fail because bridge emission does not yet consume road approach samples or remove road/bridge same-XZ overlap.

- [ ] **Step 3: Commit failing tests**

```powershell
git add src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
git commit -m "Add bridge road transition regression tests"
```

## Task 2: Add Bridge Transition Profile Data Structures

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeEmissionResult.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeTransitionProfile.java`

- [ ] **Step 1: Create bridge emission result**

Create `BridgeEmissionResult.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.road.model.BuildStep;

import java.util.List;
import java.util.Set;

public record BridgeEmissionResult(List<BuildStep> steps, Set<Long> transitionColumns) {
    public BridgeEmissionResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        transitionColumns = transitionColumns == null ? Set.of() : Set.copyOf(transitionColumns);
    }

    public static BridgeEmissionResult empty() {
        return new BridgeEmissionResult(List.of(), Set.of());
    }
}
```

- [ ] **Step 2: Create bridge transition profile helper**

Create `BridgeTransitionProfile.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BridgeTransitionProfile {
    private static final int MAX_TRANSITION_SAMPLES_PER_SIDE = 2;

    private BridgeTransitionProfile() {
    }

    static Result build(List<RoadCenterlinePoint> centerline, List<RoadSpan> spans, RoadSpan bridgeSpan) {
        if (centerline == null || centerline.isEmpty() || bridgeSpan == null) {
            return new Result(List.of(), Set.of(), 0);
        }
        int start = Math.max(0, Math.min(centerline.size() - 1, bridgeSpan.startIndex()));
        int end = Math.max(start, Math.min(centerline.size() - 1, bridgeSpan.endIndex()));
        List<RoadCenterlinePoint> expanded = new ArrayList<>();
        Set<Long> transitionColumns = new HashSet<>();

        int leftStart = start;
        for (int index = start - 1; index >= 0 && start - index <= MAX_TRANSITION_SAMPLES_PER_SIDE; index--) {
            if (!isRoadIndex(spans, index) || duplicateOrReversed(centerline, index, leftStart)) {
                break;
            }
            leftStart = index;
        }
        for (int index = leftStart; index < start; index++) {
            RoadCenterlinePoint transition = centerline.get(index).withTargetY(centerline.get(index).targetY());
            expanded.add(transition);
            transitionColumns.add(columnKey(transition));
        }

        int originalStartOffset = expanded.size();
        for (int index = start; index <= end; index++) {
            expanded.add(centerline.get(index));
        }

        int rightEnd = end;
        for (int index = end + 1; index < centerline.size() && index - end <= MAX_TRANSITION_SAMPLES_PER_SIDE; index++) {
            if (!isRoadIndex(spans, index) || duplicateOrReversed(centerline, rightEnd, index)) {
                break;
            }
            rightEnd = index;
            RoadCenterlinePoint transition = centerline.get(index).withTargetY(centerline.get(index).targetY());
            expanded.add(transition);
            transitionColumns.add(columnKey(transition));
        }

        return new Result(expanded, transitionColumns, originalStartOffset);
    }

    private static boolean isRoadIndex(List<RoadSpan> spans, int index) {
        if (spans == null) {
            return false;
        }
        return spans.stream().anyMatch(span -> span.type() == RoadSpanType.ROAD && span.contains(index));
    }

    private static boolean duplicateOrReversed(List<RoadCenterlinePoint> centerline, int firstIndex, int secondIndex) {
        RoadCenterlinePoint first = centerline.get(firstIndex);
        RoadCenterlinePoint second = centerline.get(secondIndex);
        return first.pos().getX() == second.pos().getX() && first.pos().getZ() == second.pos().getZ();
    }

    static long columnKey(RoadCenterlinePoint point) {
        return columnKey(point.pos().getX(), point.pos().getZ());
    }

    static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    record Result(List<RoadCenterlinePoint> points, Set<Long> transitionColumns, int originalStartOffset) {
        Result {
            points = points == null ? List.of() : List.copyOf(points);
            transitionColumns = transitionColumns == null ? Set.of() : Set.copyOf(transitionColumns);
        }
    }
}
```

- [ ] **Step 3: Compile**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: compilation succeeds.

- [ ] **Step 4: Commit data structures**

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeEmissionResult.java src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeTransitionProfile.java
git commit -m "Add bridge transition profile model"
```

## Task 3: Use Expanded Bridge Profiles In Emission

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpander.java`

- [ ] **Step 1: Change bridge emitter return type**

In `BridgeStructureEmitter`, add a new overload that returns `BridgeEmissionResult` and keep the old list-returning overload as a compatibility wrapper:

```java
    public static BridgeEmissionResult emitWithTransitions(List<RoadCenterlinePoint> centerline,
                                                           List<RoadSpan> spans,
                                                           RoadPlannerBuildSettings settings,
                                                           BridgeTemplateProvider templateProvider,
                                                           int startOrder,
                                                           RoadTerrainSampler terrainSampler) {
        if (centerline == null || centerline.isEmpty() || spans == null || spans.isEmpty()) {
            return BridgeEmissionResult.empty();
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        BridgeTemplateProvider safeProvider = templateProvider == null ? BridgeTemplateProvider.empty() : templateProvider;
        List<BuildStep> steps = new ArrayList<>();
        java.util.Set<Long> transitionColumns = new java.util.HashSet<>();
        int order = startOrder;
        for (RoadSpan span : spans) {
            if (span.type() != RoadSpanType.BRIDGE) {
                continue;
            }
            BridgeTransitionProfile.Result transition = BridgeTransitionProfile.build(centerline, spans, span);
            List<RoadCenterlinePoint> bridgePoints = transition.points().isEmpty()
                    ? centerline.subList(span.startIndex(), span.endIndex() + 1)
                    : transition.points();
            transitionColumns.addAll(transition.transitionColumns());
            List<BuildStep> templateSteps = safeProvider.buildFromTemplate(bridgePoints, safeSettings, order);
            if (!templateSteps.isEmpty()) {
                steps.addAll(templateSteps);
                order += templateSteps.size();
                continue;
            }
            List<BuildStep> programmatic = emitProgrammaticBridge(bridgePoints, span, safeSettings, order, terrainSampler);
            steps.addAll(programmatic);
            order += programmatic.size();
        }
        return new BridgeEmissionResult(steps, transitionColumns);
    }
```

Update the existing `emit(...)` overload with terrain sampler to call `emitWithTransitions(...).steps()`:

```java
        return emitWithTransitions(centerline, spans, settings, templateProvider, startOrder, terrainSampler).steps();
```

- [ ] **Step 2: Use transition-aware bridge emission from expander**

In `RoadNodeStructureExpander.expand(...)`, replace:

```java
        steps.addAll(BridgeStructureEmitter.emit(allCenterline, allSpans, settings, BridgeTemplateProvider.empty(), steps.size(), terrainSampler));
        java.util.List<BuildStep> dedupedSteps = dedupeAndReorder(steps);
```

with:

```java
        BridgeEmissionResult bridgeEmission = BridgeStructureEmitter.emitWithTransitions(
                allCenterline,
                allSpans,
                settings,
                BridgeTemplateProvider.empty(),
                steps.size(),
                terrainSampler
        );
        steps.addAll(bridgeEmission.steps());
        java.util.List<BuildStep> dedupedSteps = dedupeAndReorder(steps, bridgeEmission.transitionColumns());
```

Keep the existing `dedupeAndReorder(java.util.List<BuildStep> steps)` as a wrapper:

```java
    private static java.util.List<BuildStep> dedupeAndReorder(java.util.List<BuildStep> steps) {
        return dedupeAndReorder(steps, java.util.Set.of());
    }
```

Add this overloaded signature:

```java
    private static java.util.List<BuildStep> dedupeAndReorder(java.util.List<BuildStep> steps, java.util.Set<Long> bridgeTransitionColumns) {
```

- [ ] **Step 3: Add scoped X/Z transition priority**

Inside the new `dedupeAndReorder(steps, bridgeTransitionColumns)`, before building `selectedByPosition`, filter road surface/ramp steps in bridge transition columns when any bridge ramp/deck exists in the same X/Z column:

```java
        java.util.Set<Long> bridgeSurfaceColumns = new java.util.HashSet<>();
        if (steps != null && bridgeTransitionColumns != null && !bridgeTransitionColumns.isEmpty()) {
            for (BuildStep step : steps) {
                if (step == null || step.pos() == null || step.phase() == null || step.state() == null || step.state().isAir()) {
                    continue;
                }
                if ((step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK)
                        && bridgeTransitionColumns.contains(columnKey(step.pos()))) {
                    bridgeSurfaceColumns.add(columnKey(step.pos()));
                }
            }
        }
```

Then skip road surfaces/ramp in those columns:

```java
                if ((step.phase() == BuildPhase.SURFACE || step.phase() == BuildPhase.RAMP)
                        && bridgeSurfaceColumns.contains(columnKey(step.pos()))
                        && finalBlockPriority(step) <= 60) {
                    continue;
                }
```

Add helper in `RoadNodeStructureExpander`:

```java
    private static long columnKey(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
    }
```

- [ ] **Step 4: Run focused transition tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest.bridgeRampConsumesRoadApproachSoUphillStartsAtRoadSurface" --tests "com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest.bridgeTransitionColumnsDoNotKeepSeparateRoadSurfaceBelowOrAboveRamp" --tests "com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest.bridgeTransitionKeepsLegacyHalfStepRampContinuityWhenExtraRoadSamplesFit"
```

Expected: tests pass.

- [ ] **Step 5: Commit transition emission**

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpander.java
git commit -m "Connect bridge ramps through road transition samples"
```

## Task 4: Verify Continuity And Existing Road Behavior

**Files:**
- Modify only if tests expose a bridge-specific bug:
  - `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java`
  - `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeTransitionProfile.java`
- Do not modify:
  - `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSurfaceStepEmitter.java`

- [ ] **Step 1: Run structure regression tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest" --tests "com.monpai.sailboatmod.roadplanner.structure.RoadBandRasterizerTest" --tests "com.monpai.sailboatmod.roadplanner.structure.RoadPlannerBridgeGeometryPlannerTest"
```

Expected: all tests pass. In particular, these must still pass:

- `roadCrestRampSlabsUseLocalSlopeDirection`
- `bridgeRampSlabsFollowLegacyAscendingAndDescendingPairs`
- `bridgeDeckBoundaryUsesBottomSlabToCompleteRampHalfStep`
- `shortBridgeWithHighShoreStillEmitsContinuousLegacyRampSteps`

- [ ] **Step 2: If continuity fails, fix only bridge-side code**

If a bridge continuity test fails, keep `RoadSurfaceStepEmitter.java` unchanged and adjust only:

```java
BridgeTransitionProfile.build(...)
BridgeStructureEmitter.legacyRampEmissionProfile(...)
BridgeStructureEmitter.canUseLegacyRampProfile(...)
```

The intended rule is:

```java
// legacy profile is valid only when every full block of bridge height has two samples
requiredHalfSteps = Math.max(0, deckSideY - rampStartY) * 2;
return rampPointCount >= requiredHalfSteps;
```

- [ ] **Step 3: Build jar**

Run:

```powershell
.\gradlew.bat build
```

Expected: `BUILD SUCCESSFUL` and updated jars in `build/libs/`, including `sailboatmod-1.3.8-all.jar`.

- [ ] **Step 4: Commit verification fixes if any**

If Task 4 required bridge-side fixes:

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeTransitionProfile.java src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
git commit -m "Preserve bridge transition ramp continuity"
```

If no fixes were needed, do not create an empty commit.

## Self-Review Checklist

- Spec coverage: bridge transition absorption, bridge X/Z priority, legacy half-step preservation, no road slope changes, and build verification are covered by Tasks 1-4.
- Red-flag scan: no unresolved markers or unspecified test steps remain.
- Type consistency: new types are `BridgeEmissionResult` and `BridgeTransitionProfile.Result`; call sites use those exact names.
- Scope check: implementation is limited to bridge emission, transition-column dedupe, and tests.
