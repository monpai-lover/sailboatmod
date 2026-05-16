# Road Planner Bridge Obstacle Claim Map Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair bridge selection/building, road obstacle avoidance, merged claim borders, claim-map clipping, and planned-route right-click editing without adding new player-facing bridge tools.

**Architecture:** Keep the UI bridge tool unified and move bridge-size decisions into internal span/profile classification. Feed one blocked-column mask into route search, anchor validation, and final route validation. Make claim rendering use owner-neighbor border checks and one screen-space viewport, while right-click map handling checks planned draft targets before built graph edges.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, Gradle/JUnit 5, existing road planner client and server structure/build pipeline.

---

## File Structure

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeProfile.java`
  Internal bridge profile enum: `LOW_ARCH`, `LOW_BRIDGE`, `PIER_BRIDGE`; classifies a normalized bridge span from centerline length.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitter.java`
  Absorb short land interruptions, classify short/deep crossings as low bridges, and include land anchors.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerBridgeSegmentNormalizer.java`
  Treat `BRIDGE_SMALL` and `BRIDGE_MAJOR` as bridge, promote internal road gaps back to bridge, and preserve endpoint road segments.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoCompleteService.java`
  Use the same small/medium/large bridge thresholds while classifying auto-completed segments.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java`
  Generate low arch/low bridge ramps without piers for short and medium spans; keep pier bridges for long spans.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/BridgeBackendSelector.java`
  Stop treating the bridge tool itself as a forced large pier bridge.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/obstacle/RoadPlannerObstacleMask.java`
  Shared blocked-column collector and query object for town/nation cores and placed-structure footprints.
- Modify `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/TerrainSamplingCache.java`
  Store blocked columns and expose `isBlocked(int x, int z)`.
- Modify `src/main/java/com/monpai/sailboatmod/road/pathfinding/cost/TerrainCostModel.java`
  Return infinite move cost for blocked destination columns.
- Modify `src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/BasicAStarPathfinder.java`
- Modify `src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/BidirectionalAStarPathfinder.java`
- Modify `src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/GradientDescentPathfinder.java`
- Modify `src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/PotentialFieldPathfinder.java`
  Skip blocked neighbor nodes before they enter the open set.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerPathfinderRunnerFactory.java`
  Build the obstacle mask on the server runner and pass it into each route cache.
- Modify `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
  Replace local core radius logic with `RoadPlannerObstacleMask`, use radius 3, and validate final paths against merged blocked columns.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlayRenderer.java`
  Suppress internal same-owner borders for road planner claim overlays.
- Create `src/main/java/com/monpai/sailboatmod/client/screen/ClaimMapViewport.java`
  One immutable screen-space viewport helper for claim-map drawing, hit testing, and force-render requests.
- Modify `src/main/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapView.java`
  Add viewport overloads so `screenToChunk`, `chunkScreenRect`, `visibleChunkBounds`, and force-render requests consume the same rectangle.
- Modify `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`
  Use `ClaimMapViewport`, render the map with a map-local scissor after any page/flag scissor is disabled, and use the same screen rect for interactions.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerVanillaContextMenu.java`
  Add a planned-route menu kind with only road/bridge/tunnel actions.
- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteHitTester.java`
  Hit-test planned nodes first, then planned segments.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
  Right-click planned draft targets before built graph edges and apply property actions to the selected planned segment.

## Task 1: Tests for Bridge Split and Bridge Segment Continuity

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitterTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerBridgeSegmentNormalizerTest.java`

- [ ] **Step 1: Replace the deep narrow water test**

Replace `narrowDeepWaterSpanCreatesMajorBridge` with this test. It locks the user-reported case: a narrow deep channel must stay a low bridge.

```java
@Test
void narrowDeepWaterSpanCreatesSmallBridgeBecauseDepthDoesNotForcePiers() {
    RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(8, 13, 12);

    assertTrue(result.didSplit());
    assertTrue(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_SMALL));
    assertFalse(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR));
}
```

- [ ] **Step 2: Add a test for one-block land islands inside a crossing**

Add this helper and test to `RoadPlannerWaterCrossingSplitterTest`.

```java
@Test
void oneBlockLandIslandInsideWaterCrossingStaysOneBridgeRange() {
    RoadPlannerBridgeRuleService.LandProbe landProbe = (x, z) -> x < 6 || x > 18 || x == 12;
    RoadPlannerHeightSampler heightSampler = (x, z) -> 64;
    RoadPlannerWaterDepthProbe depthProbe = (x, z) -> landProbe.isLand(x, z) ? 0 : 2;

    RoadPlannerWaterCrossingSplitter.SplitResult result = RoadPlannerWaterCrossingSplitter.split(
            new BlockPos(0, 64, 0),
            new BlockPos(24, 64, 0),
            landProbe,
            heightSampler,
            depthProbe
    );

    assertTrue(result.didSplit());
    long bridgeNodeCount = result.nodes().stream()
            .filter(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_SMALL)
            .count();
    assertTrue(bridgeNodeCount >= 2, "bridge nodes should continue across the one-block island");
    assertFalse(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR));
}
```

- [ ] **Step 3: Add a normalizer test for internal road gaps**

Add this test to `RoadPlannerBridgeSegmentNormalizerTest`.

```java
@Test
void internalRoadSegmentBetweenBridgeSegmentsIsPromotedBackToBridge() {
    BlockPos a = new BlockPos(0, 64, 0);
    BlockPos b = new BlockPos(8, 65, 0);
    BlockPos c = new BlockPos(16, 65, 0);
    BlockPos d = new BlockPos(24, 65, 0);
    BlockPos e = new BlockPos(32, 64, 0);

    RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
            List.of(a, b, c, d, e),
            List.of(
                    RoadPlannerSegmentType.BRIDGE_SMALL,
                    RoadPlannerSegmentType.ROAD,
                    RoadPlannerSegmentType.BRIDGE_SMALL,
                    RoadPlannerSegmentType.ROAD
            ),
            (x, z) -> x == 0 || x == 32 || x == 16
    );

    assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(0));
    assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(1));
    assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(2));
    assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(3));
    assertEquals(1, result.bridgeRanges().size());
    assertEquals(0, result.bridgeRanges().get(0).startSegmentIndex());
    assertEquals(4, result.bridgeRanges().get(0).endSegmentIndexExclusive());
}
```

- [ ] **Step 4: Add a normalizer test that endpoint road segments stay road**

```java
@Test
void endpointRoadSegmentsOutsideBridgeRangeStayRoad() {
    BlockPos a = new BlockPos(-8, 64, 0);
    BlockPos b = new BlockPos(0, 64, 0);
    BlockPos c = new BlockPos(8, 66, 0);
    BlockPos d = new BlockPos(16, 64, 0);
    BlockPos e = new BlockPos(24, 64, 0);

    RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
            List.of(a, b, c, d, e),
            List.of(
                    RoadPlannerSegmentType.ROAD,
                    RoadPlannerSegmentType.BRIDGE_SMALL,
                    RoadPlannerSegmentType.BRIDGE_SMALL,
                    RoadPlannerSegmentType.ROAD
            ),
            (x, z) -> x <= 0 || x >= 16
    );

    assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(0));
    assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(1));
    assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(2));
    assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(3));
}
```

- [ ] **Step 5: Run the bridge continuity tests and confirm failure**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerWaterCrossingSplitterTest --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerBridgeSegmentNormalizerTest
```

Expected: FAIL. The old splitter upgrades deep narrow water to `BRIDGE_MAJOR`, and the old normalizer only scans `BRIDGE_MAJOR`.

## Task 2: Implement Bridge Split Continuity and Internal Gap Promotion

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitter.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerBridgeSegmentNormalizer.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoCompleteService.java`

- [ ] **Step 1: Replace splitter constants**

In `RoadPlannerWaterCrossingSplitter`, replace the bridge constants with:

```java
private static final int SAMPLE_SPACING = 1;
private static final int MIN_BRIDGE_WATER_BLOCKS = 4;
private static final int LOW_ARCH_MAX_WATER_BLOCKS = 16;
private static final int LOW_BRIDGE_MAX_WATER_BLOCKS = 32;
private static final int MAX_INTERNAL_LAND_INTERRUPTION_BLOCKS = 4;
```

Remove `MAJOR_BRIDGE_MIN_DEPTH`. Depth is still sampled for diagnostics and later clearance decisions, but it must not force a short span into a pier profile.

- [ ] **Step 2: Replace water-span detection with island-absorbing crossing detection**

Replace `detectWaterSpans` with this version and keep the existing `WaterSpan` record.

```java
private static List<WaterSpan> detectWaterSpans(List<SamplePoint> samples, RoadPlannerWaterDepthProbe waterDepthProbe) {
    List<WaterSpan> spans = new ArrayList<>();
    int index = 0;
    while (index < samples.size()) {
        while (index < samples.size() && samples.get(index).land()) {
            index++;
        }
        if (index >= samples.size()) {
            break;
        }

        int spanStart = index;
        int spanEnd = index;
        int waterSamples = 0;
        int maxDepth = 0;
        int dryRun = 0;

        while (index < samples.size()) {
            SamplePoint sample = samples.get(index);
            if (sample.land()) {
                dryRun++;
                if (dryRun > MAX_INTERNAL_LAND_INTERRUPTION_BLOCKS) {
                    break;
                }
                spanEnd = index;
                index++;
                continue;
            }

            dryRun = 0;
            waterSamples++;
            maxDepth = Math.max(maxDepth, Math.max(0, waterDepthProbe.waterDepthAt(sample.x(), sample.z())));
            spanEnd = index;
            index++;
        }

        int spanBlocks = Math.max(1, waterSamples) * SAMPLE_SPACING;
        if (spanBlocks >= MIN_BRIDGE_WATER_BLOCKS) {
            spans.add(new WaterSpan(spanStart, spanEnd, spanBlocks, maxDepth, bridgeTypeForWaterSpan(spanBlocks)));
        }
    }
    return spans;
}

private static RoadPlannerSegmentType bridgeTypeForWaterSpan(int spanBlocks) {
    return spanBlocks <= LOW_BRIDGE_MAX_WATER_BLOCKS
            ? RoadPlannerSegmentType.BRIDGE_SMALL
            : RoadPlannerSegmentType.BRIDGE_MAJOR;
}
```

- [ ] **Step 3: Make span anchor lookup require land anchors**

In `buildSplitFromSpans`, replace the shore index selection block with:

```java
int shoreStartIdx = span.startSampleIndex() - 1;
while (shoreStartIdx >= 0 && !samples.get(shoreStartIdx).land()) {
    shoreStartIdx--;
}
int shoreEndIdx = span.endSampleIndex() + 1;
while (shoreEndIdx < samples.size() && !samples.get(shoreEndIdx).land()) {
    shoreEndIdx++;
}
if (shoreStartIdx < 0 || shoreEndIdx >= samples.size()) {
    return SplitResult.noSplit();
}
SamplePoint shoreStart = samples.get(shoreStartIdx);
SamplePoint shoreEnd = samples.get(shoreEndIdx);
```

Keep the existing `posAt` calls after that block.

- [ ] **Step 4: Generate enough bridge nodes for absorbed island crossings**

Replace the `bridgeSamples` calculation in `buildSplitFromSpans` with:

```java
int crossingSamples = Math.max(1, shoreEndIdx - shoreStartIdx);
int bridgeSamples = Math.max(2, crossingSamples / 4);
```

This ensures a crossing with a tiny island still has continuous bridge nodes between the land anchors.

- [ ] **Step 5: Normalize both bridge segment types**

In `RoadPlannerBridgeSegmentNormalizer`, add these helpers above `normalizeSegmentTypes`.

```java
private static boolean isBridge(RoadPlannerSegmentType type) {
    return type == RoadPlannerSegmentType.BRIDGE_SMALL || type == RoadPlannerSegmentType.BRIDGE_MAJOR;
}

private static RoadPlannerSegmentType bridgeTypeForRange(List<RoadPlannerSegmentType> types, int startInclusive, int endInclusive) {
    for (int index = startInclusive; index <= endInclusive && index < types.size(); index++) {
        if (types.get(index) == RoadPlannerSegmentType.BRIDGE_MAJOR) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
    }
    return RoadPlannerSegmentType.BRIDGE_SMALL;
}
```

- [ ] **Step 6: Promote non-endpoint road gaps before range scanning**

In `normalize`, after `normalizedTypes` is created and before the `while (index < normalizedTypes.size())` loop, insert:

```java
for (int i = 1; i < normalizedTypes.size() - 1; i++) {
    if (normalizedTypes.get(i) == RoadPlannerSegmentType.ROAD
            && isBridge(normalizedTypes.get(i - 1))
            && isBridge(normalizedTypes.get(i + 1))) {
        RoadPlannerSegmentType promoted = normalizedTypes.get(i - 1) == RoadPlannerSegmentType.BRIDGE_MAJOR
                || normalizedTypes.get(i + 1) == RoadPlannerSegmentType.BRIDGE_MAJOR
                ? RoadPlannerSegmentType.BRIDGE_MAJOR
                : RoadPlannerSegmentType.BRIDGE_SMALL;
        normalizedTypes.set(i, promoted);
    }
}
```

- [ ] **Step 7: Change the range loop from major-only to bridge-aware**

In `RoadPlannerBridgeSegmentNormalizer.normalize`, replace the two comparisons against `RoadPlannerSegmentType.BRIDGE_MAJOR` with `isBridge(...)`. When adding land anchors, use the range bridge type:

```java
RoadPlannerSegmentType rangeBridgeType = bridgeTypeForRange(normalizedTypes, bridgeStart, bridgeEnd);
```

Then replace each forced `RoadPlannerSegmentType.BRIDGE_MAJOR` assignment in this method with `rangeBridgeType`.

- [ ] **Step 8: Update auto-complete thresholds**

In `RoadPlannerAutoCompleteService.classifySegment`, replace both `horizontal <= 24` checks with `horizontal <= 32`.

- [ ] **Step 9: Run the bridge continuity tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerWaterCrossingSplitterTest --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerBridgeSegmentNormalizerTest
```

Expected: PASS.

- [ ] **Step 10: Commit bridge continuity**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitter.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerBridgeSegmentNormalizer.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoCompleteService.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitterTest.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerBridgeSegmentNormalizerTest.java
git commit -m "Fix bridge span normalization"
```

## Task 3: Bridge Geometry Profiles and Low-Arch Build Output

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeProfile.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/BridgeBackendSelector.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlannerTest.java`

- [ ] **Step 1: Add failing geometry tests**

Add these tests to `RoadPlannerBridgeGeometryPlannerTest`.

```java
@Test
void shortDeepCrossingUsesLowArchWithRampsAndNoPiers() {
    RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
            centerline(0, 12, 64),
            new RoadSpan(RoadSpanType.BRIDGE, 0, 12, RoadPlannerSegmentType.BRIDGE_MAJOR),
            waterSampler(63, 40),
            new BridgeConfig()
    );

    assertEquals(RoadPlannerBridgeProfile.LOW_ARCH, plan.profile());
    assertTrue(plan.piers().isEmpty());
    assertTrue(plan.deckY() <= 67, "low arch should stay close to shore height");
    assertTrue(plan.points().stream().anyMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
    assertTrue(plan.points().stream().anyMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
}

@Test
void mediumCrossingUsesLowBridgeWithoutPiers() {
    RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
            centerline(0, 24, 64),
            new RoadSpan(RoadSpanType.BRIDGE, 0, 24, RoadPlannerSegmentType.BRIDGE_MAJOR),
            waterSampler(63, 45),
            new BridgeConfig()
    );

    assertEquals(RoadPlannerBridgeProfile.LOW_BRIDGE, plan.profile());
    assertTrue(plan.piers().isEmpty());
    assertTrue(plan.points().stream().anyMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
}

@Test
void longCrossingUsesPierBridge() {
    RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
            centerline(0, 48, 64),
            new RoadSpan(RoadSpanType.BRIDGE, 0, 48, RoadPlannerSegmentType.BRIDGE_MAJOR),
            waterSampler(63, 42),
            new BridgeConfig()
    );

    assertEquals(RoadPlannerBridgeProfile.PIER_BRIDGE, plan.profile());
    assertFalse(plan.piers().isEmpty());
    assertTrue(plan.piers().stream().allMatch(pier -> pier.bottomY() == 42));
}
```

Update the existing `shortSpanDoesNotPlanPiersLikeActualBridgeBuilder` assertion so it expects at least one ramp instead of all deck:

```java
assertTrue(plan.points().stream().anyMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
```

- [ ] **Step 2: Run the geometry tests and confirm failure**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.structure.RoadPlannerBridgeGeometryPlannerTest
```

Expected: FAIL because `Plan` does not expose `profile()` and current short-span output is a flat raised deck.

- [ ] **Step 3: Create the bridge profile enum**

Create `RoadPlannerBridgeProfile.java` with:

```java
package com.monpai.sailboatmod.roadplanner.structure;

public enum RoadPlannerBridgeProfile {
    LOW_ARCH(16, 2, 3, false),
    LOW_BRIDGE(32, 3, 5, false),
    PIER_BRIDGE(Integer.MAX_VALUE, 5, 8, true);

    private final int maxSpanBlocks;
    private final int waterClearance;
    private final int maxRiseFromLowerShore;
    private final boolean piers;

    RoadPlannerBridgeProfile(int maxSpanBlocks, int waterClearance, int maxRiseFromLowerShore, boolean piers) {
        this.maxSpanBlocks = maxSpanBlocks;
        this.waterClearance = waterClearance;
        this.maxRiseFromLowerShore = maxRiseFromLowerShore;
        this.piers = piers;
    }

    public int waterClearance() {
        return waterClearance;
    }

    public int maxRiseFromLowerShore() {
        return maxRiseFromLowerShore;
    }

    public boolean usesPiers() {
        return piers;
    }

    public static RoadPlannerBridgeProfile classify(int spanBlocks) {
        int safeSpan = Math.max(1, spanBlocks);
        for (RoadPlannerBridgeProfile profile : values()) {
            if (safeSpan <= profile.maxSpanBlocks) {
                return profile;
            }
        }
        return PIER_BRIDGE;
    }
}
```

- [ ] **Step 4: Add `profile` to the geometry plan record**

Change the `Plan` record in `RoadPlannerBridgeGeometryPlanner` to:

```java
public record Plan(List<PlannedPoint> points,
                   List<Pier> piers,
                   int deckY,
                   RoadPlannerBridgeProfile profile) {
    public Plan {
        points = points == null ? List.of() : List.copyOf(points);
        piers = piers == null ? List.of() : List.copyOf(piers);
        profile = profile == null ? RoadPlannerBridgeProfile.PIER_BRIDGE : profile;
    }
}
```

Update the empty-plan return to:

```java
return new Plan(List.of(), List.of(), SEA_LEVEL + safeConfig.getDeckHeight(), RoadPlannerBridgeProfile.PIER_BRIDGE);
```

- [ ] **Step 5: Replace deck and pier selection in `plan`**

In `RoadPlannerBridgeGeometryPlanner.plan`, replace the `deckY`, `shortSpan`, `plannedPoints`, and `piers` block with:

```java
int spanLength = span == null ? points.size() - 1 : span.endIndex() - span.startIndex();
RoadPlannerBridgeProfile profile = RoadPlannerBridgeProfile.classify(spanLength);
int deckY = deckYForProfile(profile, waterY, entryY, exitY, safeConfig);

List<PlannedPoint> plannedPoints = rampedDeck(points, entryY, exitY, deckY);
List<Pier> piers = profile.usesPiers()
        ? piers(points, plannedPoints, deckY, safeSampler, safeConfig)
        : List.of();
return new Plan(plannedPoints, piers, deckY, profile);
```

Add this helper next to `deckYForActualBridge`:

```java
private static int deckYForProfile(RoadPlannerBridgeProfile profile,
                                   int waterY,
                                   int entryY,
                                   int exitY,
                                   BridgeConfig config) {
    RoadPlannerBridgeProfile safeProfile = profile == null ? RoadPlannerBridgeProfile.PIER_BRIDGE : profile;
    if (safeProfile == RoadPlannerBridgeProfile.PIER_BRIDGE) {
        return deckYForActualBridge(waterY, entryY, exitY, config);
    }
    int lowerShore = Math.min(entryY, exitY);
    int higherShore = Math.max(entryY, exitY);
    int clearanceDeck = waterY + safeProfile.waterClearance();
    int shoreDeck = higherShore + 1;
    int maxLowDeck = lowerShore + safeProfile.maxRiseFromLowerShore();
    return Math.max(higherShore, Math.min(Math.max(clearanceDeck, shoreDeck), maxLowDeck));
}
```

- [ ] **Step 6: Make the bridge backend selector threshold-based**

In `BridgeBackendSelector`, replace the class body with:

```java
public class BridgeBackendSelector {
    private static final int PIER_BRIDGE_MIN_SPAN_BLOCKS = 33;

    public BridgeBackend select(RoadToolType toolType, int spanBlocks, int depthBlocks, boolean canyonLike) {
        if (spanBlocks >= PIER_BRIDGE_MIN_SPAN_BLOCKS || canyonLike) {
            return BridgeBackend.PIER_LARGE_BRIDGE;
        }
        return BridgeBackend.ROADWEAVER_SIMPLE;
    }
}
```

Depth no longer upgrades a short span by itself.

- [ ] **Step 7: Run geometry tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.structure.RoadPlannerBridgeGeometryPlannerTest
```

Expected: PASS.

- [ ] **Step 8: Run route expander and build-output tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpanderTest --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: PASS.

- [ ] **Step 9: Commit bridge profiles**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeProfile.java src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java src/main/java/com/monpai/sailboatmod/roadplanner/bridge/BridgeBackendSelector.java src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlannerTest.java
git commit -m "Add low bridge geometry profiles"
```

## Task 4: Obstacle Mask for Core and Structure Avoidance

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/obstacle/RoadPlannerObstacleMask.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/TerrainSamplingCache.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/cost/TerrainCostModel.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/BasicAStarPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/BidirectionalAStarPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/GradientDescentPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/PotentialFieldPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerPathfinderRunnerFactory.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Create: `src/test/java/com/monpai/sailboatmod/roadplanner/obstacle/RoadPlannerObstacleMaskTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java` if this file exists; otherwise add the tests to the nearest existing `ManualRoadPlannerService*Test` in `src/test/java/com/monpai/sailboatmod/nation/service`.

- [ ] **Step 1: Add obstacle-mask tests**

Create `RoadPlannerObstacleMaskTest.java` with:

```java
package com.monpai.sailboatmod.roadplanner.obstacle;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerObstacleMaskTest {
    @Test
    void coreMaskUsesDefaultRadiusThree() {
        RoadPlannerObstacleMask mask = RoadPlannerObstacleMask.fromColumns(
                RoadPlannerObstacleMask.coreColumns(List.of(new BlockPos(10, 64, 20)), List.of())
        );

        assertTrue(mask.isBlocked(7, 20));
        assertTrue(mask.isBlocked(13, 23));
        assertFalse(mask.isBlocked(6, 20));
    }

    @Test
    void structureFootprintIncludesOneBlockMargin() {
        PlacedStructureRecord structure = new PlacedStructureRecord(
                "s1", "nation", "town", "bank", "minecraft:overworld",
                new BlockPos(20, 64, 30).asLong(),
                4, 5, 3,
                1L,
                1,
                true,
                0
        );

        RoadPlannerObstacleMask mask = RoadPlannerObstacleMask.fromColumns(
                RoadPlannerObstacleMask.structureColumns(List.of(structure), "minecraft:overworld")
        );

        assertTrue(mask.isBlocked(19, 29));
        assertTrue(mask.isBlocked(24, 33));
        assertFalse(mask.isBlocked(18, 29));
    }

    @Test
    void unblockRemovesRouteEndpointsOnly() {
        Set<Long> columns = Set.of(
                RoadCoreExclusion.columnKey(0, 0),
                RoadCoreExclusion.columnKey(8, 0),
                RoadCoreExclusion.columnKey(16, 0)
        );
        RoadPlannerObstacleMask mask = RoadPlannerObstacleMask.fromColumns(columns)
                .withoutEndpoints(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0));

        assertFalse(mask.isBlocked(0, 0));
        assertTrue(mask.isBlocked(8, 0));
        assertFalse(mask.isBlocked(16, 0));
    }
}
```

- [ ] **Step 2: Run obstacle-mask tests and confirm failure**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.obstacle.RoadPlannerObstacleMaskTest
```

Expected: FAIL because the class does not exist.

- [ ] **Step 3: Create `RoadPlannerObstacleMask`**

Create `RoadPlannerObstacleMask.java` with:

```java
package com.monpai.sailboatmod.roadplanner.obstacle;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RoadPlannerObstacleMask {
    private final Set<Long> blockedColumns;

    private RoadPlannerObstacleMask(Set<Long> blockedColumns) {
        this.blockedColumns = blockedColumns == null || blockedColumns.isEmpty()
                ? Set.of()
                : Set.copyOf(blockedColumns);
    }

    public static RoadPlannerObstacleMask empty() {
        return new RoadPlannerObstacleMask(Set.of());
    }

    public static RoadPlannerObstacleMask fromColumns(Collection<Long> columns) {
        return new RoadPlannerObstacleMask(columns == null ? Set.of() : new HashSet<>(columns));
    }

    public static RoadPlannerObstacleMask fromNationData(ServerLevel level, NationSavedData data) {
        if (level == null || data == null) {
            return empty();
        }
        String dimensionId = level.dimension().location().toString();
        Set<Long> columns = new HashSet<>();
        columns.addAll(structureColumns(data.getPlacedStructures(), dimensionId));
        columns.addAll(coreColumns(
                data.getTowns().stream()
                        .filter(town -> town != null && town.hasCore() && dimensionId.equalsIgnoreCase(town.coreDimension()))
                        .map(town -> BlockPos.of(town.corePos()))
                        .toList(),
                data.getNations().stream()
                        .filter(nation -> nation != null && nation.hasCore() && dimensionId.equalsIgnoreCase(nation.coreDimension()))
                        .map(nation -> BlockPos.of(nation.corePos()))
                        .toList()
        ));
        return fromColumns(columns);
    }

    public static Set<Long> coreColumns(Collection<BlockPos> townCores, Collection<BlockPos> nationCores) {
        Set<BlockPos> cores = new HashSet<>();
        if (townCores != null) {
            cores.addAll(townCores);
        }
        if (nationCores != null) {
            cores.addAll(nationCores);
        }
        return RoadCoreExclusion.collectExcludedColumns(cores, RoadCoreExclusion.DEFAULT_RADIUS);
    }

    public static Set<Long> structureColumns(Collection<PlacedStructureRecord> structures, String dimensionId) {
        if (structures == null || structures.isEmpty()) {
            return Set.of();
        }
        Set<Long> blocked = new HashSet<>();
        String safeDimension = dimensionId == null ? "" : dimensionId;
        for (PlacedStructureRecord structure : structures) {
            if (structure == null || !safeDimension.equalsIgnoreCase(structure.dimensionId())) {
                continue;
            }
            BlockPos origin = structure.origin();
            int minX = origin.getX() - 1;
            int minZ = origin.getZ() - 1;
            int maxX = origin.getX() + structure.sizeW();
            int maxZ = origin.getZ() + structure.sizeD();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocked.add(RoadCoreExclusion.columnKey(x, z));
                }
            }
        }
        return blocked.isEmpty() ? Set.of() : Set.copyOf(blocked);
    }

    public Set<Long> blockedColumns() {
        return blockedColumns;
    }

    public boolean isBlocked(int x, int z) {
        return blockedColumns.contains(RoadCoreExclusion.columnKey(x, z));
    }

    public boolean isBlocked(BlockPos pos) {
        return pos != null && isBlocked(pos.getX(), pos.getZ());
    }

    public RoadPlannerObstacleMask withoutEndpoints(BlockPos... endpoints) {
        if (blockedColumns.isEmpty() || endpoints == null || endpoints.length == 0) {
            return this;
        }
        Set<Long> copy = new HashSet<>(blockedColumns);
        for (BlockPos endpoint : endpoints) {
            if (endpoint != null) {
                copy.remove(RoadCoreExclusion.columnKey(endpoint.getX(), endpoint.getZ()));
            }
        }
        return fromColumns(copy);
    }

    public boolean pathTouchesBlockedColumn(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (BlockPos pos : path) {
            if (isBlocked(pos)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Add blocked columns to `TerrainSamplingCache`**

Add a field:

```java
private final java.util.Set<Long> blockedColumns;
```

Change the constructor and add an overload:

```java
public TerrainSamplingCache(ServerLevel level, PathfindingConfig.SamplingPrecision precision) {
    this(level, precision, java.util.Set.of());
}

public TerrainSamplingCache(ServerLevel level, PathfindingConfig.SamplingPrecision precision, java.util.Set<Long> blockedColumns) {
    this.level = level;
    this.fastSampler = new FastHeightSampler(level);
    this.accurateSampler = new AccurateHeightSampler(level);
    this.precision = precision;
    this.blockedColumns = blockedColumns == null || blockedColumns.isEmpty()
            ? java.util.Set.of()
            : java.util.Set.copyOf(blockedColumns);
}
```

Add:

```java
public boolean isBlocked(int x, int z) {
    return blockedColumns.contains(com.monpai.sailboatmod.construction.RoadCoreExclusion.columnKey(x, z));
}
```

- [ ] **Step 5: Make terrain cost reject blocked moves**

At the top of `TerrainCostModel.moveCost`, after `stepCost` is calculated, add:

```java
if (cache.isBlocked(toX, toZ)) {
    return Double.POSITIVE_INFINITY;
}
```

- [ ] **Step 6: Skip blocked neighbors in every pathfinder**

In each pathfinder loop, immediately after `nx` and `nz` are calculated, add:

```java
if (cache.isBlocked(nx, nz)) {
    continue;
}
```

Apply this in:

- `BasicAStarPathfinder`
- `BidirectionalAStarPathfinder`, both forward and backward loops
- `GradientDescentPathfinder`
- `PotentialFieldPathfinder`

- [ ] **Step 7: Wire obstacle mask into road planner server auto-complete**

In `RoadPlannerPathfinderRunnerFactory.serverService`, import `NationSavedData` and `RoadPlannerObstacleMask`. Build the base mask before constructing the runner:

```java
RoadPlannerObstacleMask baseMask = RoadPlannerObstacleMask.fromNationData(level, NationSavedData.get(level));
```

Replace the cache construction inside the runner with a route-local cache:

```java
RoadPlannerAutoCompleteService.PathfinderRunner runner = (BlockPos from, BlockPos destination) -> {
    RoadPlannerObstacleMask routeMask = baseMask.withoutEndpoints(from, destination);
    TerrainSamplingCache routeCache = new TerrainSamplingCache(level, config.getSamplingPrecision(), routeMask.blockedColumns());
    PathResult result = pathfinder.findPath(from, destination, routeCache);
    if (!result.success() || routeMask.pathTouchesBlockedColumn(result.path())) {
        return List.of();
    }
    return result.path();
};
```

Keep the segment classifier cache as the original unblocked terrain cache so terrain classification still samples terrain normally.

- [ ] **Step 8: Replace manual planner local obstacle helpers**

In `ManualRoadPlannerService.collectCoreExclusionColumns`, replace the `dx = -1..1` and `dz = -1..1` loops with:

```java
return RoadPlannerObstacleMask.coreColumns(townCorePositions, nationCorePositions);
```

Use the existing `collectPresentCorePositions` helper to fill `townCorePositions` and `nationCorePositions` before the return.

In `collectBlockedRoadColumns`, replace the structure loop with:

```java
return RoadPlannerObstacleMask.structureColumns(data.getPlacedStructures(), dimensionId);
```

Keep endpoint unblocking only where the existing code explicitly calls `unblockPathEndpoints(...)`.

- [ ] **Step 9: Make final path validation reject obstacles**

Replace `validateFinalPlannedPath` with:

```java
private static boolean validateFinalPlannedPath(List<BlockPos> candidate,
                                                Set<Long> excludedColumns) {
    if (candidate == null || candidate.size() < 2) {
        return false;
    }
    if (excludedColumns == null || excludedColumns.isEmpty()) {
        return true;
    }
    for (BlockPos pos : candidate) {
        if (pos != null && excludedColumns.contains(columnKey(pos.getX(), pos.getZ()))) {
            return false;
        }
    }
    return true;
}
```

In `buildPlanCandidate`, after `finalPath` is assigned and before the `RoadNetworkRecord` is created, insert:

```java
Set<Long> finalBlockedColumns = unblockPathEndpoints(
        mergePlannedPathBlockedColumns(blockedColumns, excludedColumns),
        sourceAnchor,
        targetAnchor
);
if (!validateFinalPlannedPath(finalPath, finalBlockedColumns)) {
    return null;
}
```

- [ ] **Step 10: Update test helper radius**

In `ManualRoadPlannerService.collectCoreExclusionColumnsForTest`, replace the duplicated loops with:

```java
return RoadPlannerObstacleMask.coreColumns(
        townCores == null ? List.of() : townCores,
        nationCores == null ? List.of() : nationCores
);
```

- [ ] **Step 11: Run obstacle tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.obstacle.RoadPlannerObstacleMaskTest --tests com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteServiceTest
```

Expected: PASS. If `ManualRoadPlannerServiceTest` does not exist, run the existing `ManualRoadPlannerService*Test` class found in `src/test/java/com/monpai/sailboatmod/nation/service`.

- [ ] **Step 12: Commit obstacle mask**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/obstacle/RoadPlannerObstacleMask.java src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/TerrainSamplingCache.java src/main/java/com/monpai/sailboatmod/road/pathfinding/cost/TerrainCostModel.java src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/BasicAStarPathfinder.java src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/BidirectionalAStarPathfinder.java src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/GradientDescentPathfinder.java src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/PotentialFieldPathfinder.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerPathfinderRunnerFactory.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/roadplanner/obstacle/RoadPlannerObstacleMaskTest.java src/test/java/com/monpai/sailboatmod/nation/service
git commit -m "Add road planner obstacle mask"
```

## Task 5: Merged Claim Borders in Road Planner Overlay

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlayRenderer.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlayRendererTest.java`

- [ ] **Step 1: Add border-neighbor tests**

Add these tests to `RoadPlannerClaimOverlayRendererTest`.

```java
@Test
void adjacentSameOwnerChunksSuppressInternalBorder() {
    RoadPlannerClaimOverlay left = new RoadPlannerClaimOverlay(
            0, 0, "town-a", "Town A", "nation", "Nation", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
    );
    RoadPlannerClaimOverlay right = new RoadPlannerClaimOverlay(
            1, 0, "town-a", "Town A", "nation", "Nation", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
    );
    RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(left, right));

    assertFalse(renderer.visibleBorderForTest(left, RoadPlannerClaimOverlayRenderer.BorderSide.EAST));
    assertFalse(renderer.visibleBorderForTest(right, RoadPlannerClaimOverlayRenderer.BorderSide.WEST));
    assertTrue(renderer.visibleBorderForTest(left, RoadPlannerClaimOverlayRenderer.BorderSide.NORTH));
}

@Test
void differentRoleClaimsKeepBoundaryEvenWhenTownMatches() {
    RoadPlannerClaimOverlay start = new RoadPlannerClaimOverlay(
            0, 0, "town-a", "Town A", "nation", "Nation", RoadPlannerClaimOverlay.Role.START, 0x00AA00, 0x006600
    );
    RoadPlannerClaimOverlay other = new RoadPlannerClaimOverlay(
            1, 0, "town-a", "Town A", "nation", "Nation", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
    );
    RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(start, other));

    assertTrue(renderer.visibleBorderForTest(start, RoadPlannerClaimOverlayRenderer.BorderSide.EAST));
}
```

- [ ] **Step 2: Run overlay tests and confirm failure**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerClaimOverlayRendererTest
```

Expected: FAIL because `visibleBorderForTest` and `BorderSide` do not exist.

- [ ] **Step 3: Add border side and overlay lookup**

In `RoadPlannerClaimOverlayRenderer`, add:

```java
public enum BorderSide {
    NORTH(0, -1),
    SOUTH(0, 1),
    WEST(-1, 0),
    EAST(1, 0);

    private final int dx;
    private final int dz;

    BorderSide(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }
}
```

Add a field:

```java
private final java.util.Map<Long, RoadPlannerClaimOverlay> overlaysByChunk;
```

Update the constructor:

```java
public RoadPlannerClaimOverlayRenderer(Collection<RoadPlannerClaimOverlay> overlays) {
    this.overlays = overlays == null ? List.of() : List.copyOf(overlays);
    java.util.Map<Long, RoadPlannerClaimOverlay> byChunk = new java.util.HashMap<>();
    for (RoadPlannerClaimOverlay overlay : this.overlays) {
        byChunk.put(chunkKey(overlay.chunkX(), overlay.chunkZ()), overlay);
    }
    this.overlaysByChunk = java.util.Map.copyOf(byChunk);
}
```

Add helpers:

```java
public boolean visibleBorderForTest(RoadPlannerClaimOverlay overlay, BorderSide side) {
    return visibleBorder(overlay, side);
}

private boolean visibleBorder(RoadPlannerClaimOverlay overlay, BorderSide side) {
    if (overlay == null || side == null) {
        return true;
    }
    RoadPlannerClaimOverlay neighbor = overlaysByChunk.get(chunkKey(overlay.chunkX() + side.dx, overlay.chunkZ() + side.dz));
    return neighbor == null || !ownerKey(overlay).equals(ownerKey(neighbor));
}

private static String ownerKey(RoadPlannerClaimOverlay overlay) {
    if (overlay == null) {
        return "";
    }
    String owner = overlay.townId().isBlank() ? overlay.nationId() : overlay.townId();
    return overlay.role().name() + ":" + owner;
}

private static long chunkKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
}
```

- [ ] **Step 4: Render only visible borders**

Replace the four unconditional border draws in `render` with:

```java
if (visibleBorder(overlay, BorderSide.NORTH)) {
    graphics.fill(left, top, right, top + 1, border);
}
if (visibleBorder(overlay, BorderSide.SOUTH)) {
    graphics.fill(left, bottom - 1, right, bottom, border);
}
if (visibleBorder(overlay, BorderSide.WEST)) {
    graphics.fill(left, top, left + 1, bottom, border);
}
if (visibleBorder(overlay, BorderSide.EAST)) {
    graphics.fill(right - 1, top, right, bottom, border);
}
```

- [ ] **Step 5: Run overlay tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerClaimOverlayRendererTest
```

Expected: PASS.

- [ ] **Step 6: Commit merged road planner claim borders**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlayRenderer.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlayRendererTest.java
git commit -m "Merge road planner claim overlay borders"
```

## Task 6: Claim Map Screen-Space Viewport and Clipping Fix

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/screen/ClaimMapViewport.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapView.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapViewTest.java`

- [ ] **Step 1: Add viewport tests**

Add these tests to `ClaimWorldMapViewTest`.

```java
@Test
void claimMapViewportAppliesScrollOnce() {
    ClaimMapViewport viewport = ClaimMapViewport.scrolled(100, 120, 45, 160, 160);

    assertEquals(100, viewport.x());
    assertEquals(75, viewport.y());
    assertEquals(260, viewport.right());
    assertEquals(235, viewport.bottom());
}

@Test
void scrolledViewportHitTestingUsesScreenRect() {
    ClaimWorldMapView view = ClaimWorldMapView.forTest(10, -4, 4, 164, 164);
    ClaimMapViewport viewport = ClaimMapViewport.scrolled(20, 80, 30, 164, 164);

    assertEquals(10, view.screenToChunk(viewport.x() + 82, viewport.y() + 82, viewport).x);
    assertEquals(-4, view.screenToChunk(viewport.x() + 82, viewport.y() + 82, viewport).z);
}

@Test
void forceRenderRequestUsesViewportScreenRect() {
    ClaimWorldMapView view = ClaimWorldMapView.forTest(0, 0, 2, 160, 160);
    ClaimMapViewport viewport = ClaimMapViewport.scrolled(10, 80, 40, 160, 160);

    RoadPlannerMapPreloadRequestPacket packet = view.createVisibleForceRenderRequest(
            "world_a",
            "minecraft:overworld",
            viewport
    );

    assertTrue(packet.start().getX() < packet.destination().getX());
    assertTrue(packet.start().getZ() < packet.destination().getZ());
}
```

- [ ] **Step 2: Run viewport tests and confirm failure**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.screen.ClaimWorldMapViewTest
```

Expected: FAIL because `ClaimMapViewport` and overloads do not exist.

- [ ] **Step 3: Create `ClaimMapViewport`**

Create `ClaimMapViewport.java` with:

```java
package com.monpai.sailboatmod.client.screen;

public record ClaimMapViewport(int x, int y, int width, int height) {
    public ClaimMapViewport {
        width = Math.max(1, width);
        height = Math.max(1, height);
    }

    public static ClaimMapViewport scrolled(int logicalX, int logicalY, int pageScroll, int width, int height) {
        return new ClaimMapViewport(logicalX, logicalY - pageScroll, width, height);
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
    }
}
```

- [ ] **Step 4: Add `ClaimWorldMapView` viewport overloads**

Add these methods to `ClaimWorldMapView`:

```java
public void renderBase(GuiGraphics graphics,
                       Font font,
                       ClaimMapViewport viewport,
                       int fallbackCenterChunkX,
                       int fallbackCenterChunkZ,
                       int radius) {
    renderBase(graphics, font, viewport.x(), viewport.y(), viewport.width(), viewport.height(),
            fallbackCenterChunkX, fallbackCenterChunkZ, radius);
}

public ChunkPos screenToChunk(double screenX, double screenY, ClaimMapViewport viewport) {
    return screenToChunk(screenX, screenY, viewport.x(), viewport.y(), viewport.width(), viewport.height());
}

public ScreenRect chunkScreenRect(int chunkX, int chunkZ, ClaimMapViewport viewport) {
    return chunkScreenRect(chunkX, chunkZ, viewport.x(), viewport.y(), viewport.width(), viewport.height());
}

public ChunkBounds visibleChunkBounds(ClaimMapViewport viewport) {
    return visibleChunkBounds(viewport.x(), viewport.y(), viewport.width(), viewport.height());
}

public RoadPlannerMapPreloadRequestPacket createVisibleForceRenderRequest(String worldId,
                                                                         String dimensionId,
                                                                         ClaimMapViewport viewport) {
    return createVisibleForceRenderRequest(worldId, dimensionId,
            viewport.x(), viewport.y(), viewport.width(), viewport.height());
}
```

- [ ] **Step 5: Refactor Town claim-map drawing to use the viewport**

In `TownHomeScreen`, add:

```java
private ClaimMapViewport claimMapViewport() {
    return ClaimMapViewport.scrolled(
            claimMapX(left() + BODY_X),
            claimMapY(top() + BODY_Y),
            this.pageScroll,
            CLAIM_MAP_W,
            CLAIM_MAP_H
    );
}
```

Change mouse wheel, middle-drag selection, click selection, and refresh force-render methods to use `ClaimMapViewport viewport = claimMapViewport();` and `viewport.contains(mouseX, mouseY)`.

Replace calls shaped like:

```java
this.claimWorldMapView.screenToChunk(mouseX, mouseY, mapX, mapY, CLAIM_MAP_W, CLAIM_MAP_H)
```

with:

```java
this.claimWorldMapView.screenToChunk(mouseX, mouseY, viewport)
```

Replace force-render request construction with:

```java
RoadPlannerMapPreloadRequestPacket packet = this.claimWorldMapView.createVisibleForceRenderRequest(
        this.claimWorldMapView.worldId(),
        currentDimensionId(),
        viewport
);
```

- [ ] **Step 6: Render Town claim map after scroll scissor is closed**

In `TownHomeScreen.drawContents`, keep normal page content inside the body scissor, but skip the map body from `drawClaimsPage` by adding a `boolean drawMap` parameter:

```java
private void drawClaimsPage(GuiGraphics g, int x, int y, int mouseX, int mouseY, boolean drawMap) {
    if (this.claimsSubPage == 1) {
        drawClaimsPermPage(g, x, y);
        return;
    }
    int drawY = y + 34;
    for (Component line : buildClaimLines()) {
        drawWrappedLine(g, line, x + 12, drawY, 206, 0xFFDCEEFF);
        drawY += wrappedHeight(line, 206) + 6;
    }
    int mapX = claimMapX(x);
    g.drawString(this.font, Component.translatable("screen.sailboatmod.town.claims.map_title"), mapX, y + 12, 0xFFB8C0C8);
    if (drawMap) {
        drawClaimMap(g, claimMapViewport(), mouseX, mouseY);
    }
}
```

After `g.disableScissor()` in `drawContents`, add:

```java
if (this.currentPage == Page.CLAIMS && this.claimsSubPage == 0) {
    ClaimMapViewport viewport = claimMapViewport();
    g.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
    drawClaimMap(g, viewport, mouseX, mouseY);
    g.disableScissor();
}
```

Change the scrolled switch to call `drawClaimsPage(..., false)`.

- [ ] **Step 7: Change Town `drawClaimMap` signature**

Replace:

```java
private void drawClaimMap(GuiGraphics g, int mapX, int mapY, int mouseX, int mouseY)
```

with:

```java
private void drawClaimMap(GuiGraphics g, ClaimMapViewport viewport, int mouseX, int mouseY)
```

Inside this method, replace `mapX`, `mapY`, `CLAIM_MAP_W`, and `CLAIM_MAP_H` calculations with `viewport.x()`, `viewport.y()`, `viewport.width()`, and `viewport.height()`. Use the new `ClaimWorldMapView` overloads:

```java
this.claimWorldMapView.renderBase(g, this.font, viewport, mapCenterX(), mapCenterZ(), claimRadius());
ClaimWorldMapView.ChunkBounds bounds = this.claimWorldMapView.visibleChunkBounds(viewport);
ClaimWorldMapView.ScreenRect rect = this.claimWorldMapView.chunkScreenRect(chunkX, chunkZ, viewport);
```

Change `drawClaimMarker`, `drawClaimMapProgress`, and `drawTownLabels` to accept `ClaimMapViewport viewport`.

- [ ] **Step 8: Apply the same viewport refactor to Nation claim map**

Repeat Steps 5-7 in `NationHomeScreen`, using:

```java
private ClaimMapViewport claimMapViewport() {
    return ClaimMapViewport.scrolled(
            claimMapX(left() + BODY_X),
            claimMapY(top() + BODY_Y),
            this.pageScroll,
            CLAIM_MAP_W,
            CLAIM_MAP_H
    );
}
```

For the Nation owner key in `drawClaimMap`, keep `claim.nationId()`.

- [ ] **Step 9: Run claim map tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.screen.ClaimWorldMapViewTest
```

Expected: PASS.

- [ ] **Step 10: Compile UI changes**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Commit claim map viewport**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/client/screen/ClaimMapViewport.java src/main/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapView.java src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java src/test/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapViewTest.java
git commit -m "Fix claim map viewport clipping"
```

## Task 7: Planned Route Right-Click Context Menu Priority

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteHitTester.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerVanillaContextMenu.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

- [ ] **Step 1: Add right-click behavior tests**

Add these tests to `RoadPlannerScreenBehaviorTest`.

```java
@Test
void rightClickPlannedNodeOpensPlannedRouteMenuBeforeGraphEdge() {
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
    RoadPlannerMapLayout.Rect map = screen.mapRectForTest();
    screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
    screen.mouseClicked(map.x() + 180, map.y() + 120, 0);

    RoadNetworkGraph graph = new RoadNetworkGraph();
    RoadGraphNode from = graph.addNode(new BlockPos(0, 64, 0), RoadGraphNode.Kind.TOWN_CONNECTION);
    RoadGraphNode to = graph.addNode(new BlockPos(80, 64, 0), RoadGraphNode.Kind.TOWN_CONNECTION);
    graph.addEdge(from.nodeId(), to.nodeId(), roadMetadata());
    screen.setGraphForTest(graph);

    assertTrue(screen.rightClickMapForTest(0, 0, 300, 300));

    assertEquals(RoadPlannerVanillaContextMenu.Kind.PLANNED_ROUTE, screen.contextMenuForTest().kind());
}

@Test
void plannedRouteContextMenuPropertyActionUpdatesDraftSegment() {
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
    RoadPlannerMapLayout.Rect map = screen.mapRectForTest();
    screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
    screen.mouseClicked(map.x() + 180, map.y() + 120, 0);

    assertTrue(screen.rightClickMapForTest(0, 0, 300, 300));
    screen.handleContextActionForTest(RoadPlannerContextMenuAction.SET_BRIDGE_TYPE);

    assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, screen.segmentTypeForTest(0));
}
```

If `segmentTypeForTest` does not exist, add it in Step 5.

- [ ] **Step 2: Run right-click tests and confirm failure**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest
```

Expected: FAIL because planned-route right-click currently calls `openContextMenuForGraph` directly.

- [ ] **Step 3: Create planned-route hit tester**

Create `RoadPlannerRouteHitTester.java` with:

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

public final class RoadPlannerRouteHitTester {
    private final RoadPlannerNodeHitTester nodeHitTester;
    private final double segmentRadiusSq;

    public RoadPlannerRouteHitTester(double nodeRadius, double segmentRadius) {
        this.nodeHitTester = new RoadPlannerNodeHitTester(nodeRadius);
        this.segmentRadiusSq = Math.max(1.0D, segmentRadius) * Math.max(1.0D, segmentRadius);
    }

    public Optional<Hit> hit(List<BlockPos> nodes, double worldX, double worldZ) {
        Optional<RoadPlannerNodeSelection> node = nodeHitTester.hitNode(nodes, worldX, worldZ);
        if (node.isPresent()) {
            int nodeIndex = node.get().nodeIndex();
            int segmentIndex = Math.max(0, Math.min(nodeIndex, nodes.size() - 2));
            return Optional.of(new Hit(nodeIndex, segmentIndex));
        }
        if (nodes == null || nodes.size() < 2) {
            return Optional.empty();
        }
        int bestSegment = -1;
        double bestDistance = segmentRadiusSq;
        for (int index = 0; index < nodes.size() - 1; index++) {
            double distance = distanceSqToSegment(worldX, worldZ, nodes.get(index), nodes.get(index + 1));
            if (distance <= bestDistance) {
                bestDistance = distance;
                bestSegment = index;
            }
        }
        return bestSegment < 0 ? Optional.empty() : Optional.of(new Hit(bestSegment, bestSegment));
    }

    private static double distanceSqToSegment(double x, double z, BlockPos a, BlockPos b) {
        double ax = a.getX();
        double az = a.getZ();
        double bx = b.getX();
        double bz = b.getZ();
        double dx = bx - ax;
        double dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        if (lenSq <= 0.0001D) {
            double px = x - ax;
            double pz = z - az;
            return px * px + pz * pz;
        }
        double t = Math.max(0.0D, Math.min(1.0D, ((x - ax) * dx + (z - az) * dz) / lenSq));
        double px = ax + dx * t;
        double pz = az + dz * t;
        double ox = x - px;
        double oz = z - pz;
        return ox * ox + oz * oz;
    }

    public record Hit(int nodeIndex, int segmentIndex) {
    }
}
```

- [ ] **Step 4: Add context menu kind and planned menu factory**

In `RoadPlannerVanillaContextMenu`, add:

```java
public enum Kind {
    ROAD_EDGE,
    PLANNED_ROUTE
}
```

Add a `kind` field and change the constructor:

```java
private final Kind kind;

private RoadPlannerVanillaContextMenu(UUID roadEdgeId, Kind kind) {
    this.roadEdgeId = roadEdgeId == null ? new UUID(0L, 0L) : roadEdgeId;
    this.kind = kind == null ? Kind.ROAD_EDGE : kind;
}
```

Update `forRoadEdge` to call:

```java
RoadPlannerVanillaContextMenu menu = new RoadPlannerVanillaContextMenu(roadEdgeId, Kind.ROAD_EDGE);
```

Add:

```java
public static RoadPlannerVanillaContextMenu forPlannedRoute() {
    RoadPlannerVanillaContextMenu menu = new RoadPlannerVanillaContextMenu(new UUID(0L, 0L), Kind.PLANNED_ROUTE);
    menu.items.add(Item.action("\u8bbe\u4e3a\u9053\u8def", RoadPlannerContextMenuAction.SET_ROAD_TYPE));
    menu.items.add(Item.action("\u8bbe\u4e3a\u6865\u6881", RoadPlannerContextMenuAction.SET_BRIDGE_TYPE));
    menu.items.add(Item.action("\u8bbe\u4e3a\u96a7\u9053", RoadPlannerContextMenuAction.SET_TUNNEL_TYPE));
    return menu;
}

public Kind kind() {
    return kind;
}
```

- [ ] **Step 5: Add planned hit testing to `RoadPlannerScreen`**

Add a field:

```java
private final RoadPlannerRouteHitTester routeHitTester = new RoadPlannerRouteHitTester(8.0D, 6.0D);
```

Add a test helper:

```java
public RoadPlannerSegmentType segmentTypeForTest(int segmentIndex) {
    return linePlan.segments().get(segmentIndex);
}
```

Replace `rightClickMapForTest` with:

```java
public boolean rightClickMapForTest(double worldX, double worldZ, int mouseX, int mouseY) {
    return openContextMenuAtWorld(worldX, worldZ, mouseX, mouseY);
}
```

Add:

```java
private boolean openContextMenuAtWorld(double worldX, double worldZ, int mouseX, int mouseY) {
    if (openContextMenuForPlannedRoute(worldX, worldZ, mouseX, mouseY)) {
        return true;
    }
    return openContextMenuForGraph(worldX, worldZ, mouseX, mouseY);
}

private boolean openContextMenuForPlannedRoute(double worldX, double worldZ, int mouseX, int mouseY) {
    RoadPlannerRouteHitTester.Hit hit = routeHitTester.hit(linePlan.nodes(), worldX, worldZ).orElse(null);
    if (hit == null || hit.segmentIndex() < 0 || hit.segmentIndex() >= linePlan.segmentCount()) {
        return false;
    }
    selectedNode = new RoadPlannerNodeSelection(hit.segmentIndex());
    state = state.withSelectedRoadEdge(null);
    contextMenu = RoadPlannerVanillaContextMenu.forPlannedRoute();
    contextMenu.open(mouseX, mouseY);
    return true;
}
```

In `mouseClicked`, replace:

```java
return openContextMenuForGraph(world.getX(), world.getZ(), (int) mouseX, (int) mouseY);
```

with:

```java
return openContextMenuAtWorld(world.getX(), world.getZ(), (int) mouseX, (int) mouseY);
```

- [ ] **Step 6: Run right-click tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest
```

Expected: PASS.

- [ ] **Step 7: Commit right-click planned menu**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteHitTester.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerVanillaContextMenu.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java
git commit -m "Restore planned route context menu"
```

## Task 8: Final Verification

**Files:**
- No new files. This task verifies all touched surfaces.

- [ ] **Step 1: Run focused road planner and claim map tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerWaterCrossingSplitterTest --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerBridgeSegmentNormalizerTest --tests com.monpai.sailboatmod.roadplanner.structure.RoadPlannerBridgeGeometryPlannerTest --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpanderTest --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest --tests com.monpai.sailboatmod.roadplanner.obstacle.RoadPlannerObstacleMaskTest --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerClaimOverlayRendererTest --tests com.monpai.sailboatmod.client.screen.ClaimWorldMapViewTest --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Compile all Java**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run full build**

Run:

```powershell
.\gradlew.bat build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Review final diff**

Run:

```powershell
git status -sb
git diff --stat
```

Expected: only intended source/test/doc changes are present before any final commit.

## Self-Review

- Spec coverage:
  - Short/deep crossings and low bridge profiles are covered by Tasks 1-3.
  - Tiny land interruptions and internal road-to-bridge promotion are covered by Tasks 1-2.
  - Core radius 3 and structure footprint plus one-block margin are covered by Task 4.
  - Merged owner boundaries are covered by Task 5.
  - Town/Nation map clipping and consistent screen rectangles are covered by Task 6.
  - Planned-route right-click priority and limited planned menu actions are covered by Task 7.
- Placeholder scan:
  - The plan contains concrete file paths, commands, test bodies, and implementation snippets.
  - No placeholder tokens or unspecified "add tests" steps remain.
- Type consistency:
  - `RoadPlannerBridgeProfile` is introduced before `Plan.profile()` is tested.
  - `ClaimMapViewport` overloads are introduced before Town/Nation screens call them.
  - `RoadPlannerRouteHitTester.Hit` and `RoadPlannerVanillaContextMenu.Kind` are introduced before tests assert planned-menu behavior.
