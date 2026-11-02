# RoadWeaver Segment-Growth Road Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore distinct detour/bridge route behavior and generate manual-road construction queues from a RoadWeaver-style segment-growth footprint so road width, bridge heads, short spans, vegetation cleanup, preview, and demolition stay aligned.

**Architecture:** Keep the existing manual-construction `BuildStep` queue, but generate it from preserved route policy, segment placements, target heights, bridge span categories, and route metrics. `RoadBuilder` serializes RoadWeaver-style segment growth into build steps; planning, preview, rebuild, rollback, and demolition use the same footprint model.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, Gradle, existing `sailboatmod` road packages.

**Important:** Do not create git commits unless the user explicitly asks. Work on branch `landroad_fix`.

---

## File Structure

- Create: `src/main/java/com/monpai/sailboatmod/road/model/BridgeGapKind.java` — water vs ravine vs mixed span classification.
- Create: `src/main/java/com/monpai/sailboatmod/road/planning/RoutePolicy.java` — `DETOUR` and `BRIDGE` policy rules.
- Create: `src/main/java/com/monpai/sailboatmod/road/planning/RouteCandidateMetrics.java` — bridge coverage and long-water-span checks.
- Create: `src/main/java/com/monpai/sailboatmod/road/construction/road/RoadSegmentStepPlanner.java` — segment-order queue generation.
- Modify: `src/main/java/com/monpai/sailboatmod/road/model/BridgeSpan.java` — add `gapKind` and helpers while preserving constructors.
- Modify: `src/main/java/com/monpai/sailboatmod/road/model/RoadData.java` — preserve `placements` and `targetY`.
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/post/PathPostProcessor.java` — expose half-width and fallback rasterization, return `targetY`.
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/post/RoadHeightInterpolator.java` — RoadWeaver-style projection interpolation.
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/road/RoadBuilder.java` — use segment-growth planner and return preserved artifacts.
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/road/RoadSegmentPaver.java` — per-segment paver using interpolated road Y.
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgeRangeDetector.java` and `BridgeBuilder.java` — span categories, short-flat land anchors, no-ramp short decks.
- Modify: `src/main/java/com/monpai/sailboatmod/road/planning/BothOrchestrator.java`, `BridgePlanner.java`, `RoadPlanningService.java` — policy/width/metrics wiring.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java` and `StructureConstructionManager.java` — rebuild, preview filtering, demolition pipeline.
- Test: add/update focused tests under `src/test/java/com/monpai/sailboatmod/road/...` and `src/test/java/com/monpai/sailboatmod/nation/service/...`.

---

### Task 1: Span Categories and Route Policy

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/road/model/BridgeGapKind.java`
- Create: `src/main/java/com/monpai/sailboatmod/road/planning/RoutePolicy.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/model/BridgeSpan.java:3-13`
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgeRangeDetector.java:29-220`
- Test: `src/test/java/com/monpai/sailboatmod/road/model/BridgeSpanPolicyTest.java`

- [ ] **Step 1: Write failing span policy test**

Create `BridgeSpanPolicyTest` with these assertions:

```java
BridgeSpan shortWater = new BridgeSpan(4, 9, 63, 55,
        BridgeSpanKind.SHORT_SPAN_FLAT, 66, BridgeGapKind.WATER_GAP);
BridgeSpan regularWater = new BridgeSpan(4, 20, 63, 49,
        BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);
BridgeSpan landRavine = new BridgeSpan(7, 11, 0, 58,
        BridgeSpanKind.SHORT_SPAN_FLAT, 70, BridgeGapKind.LAND_RAVINE_GAP);
assertTrue(RoutePolicy.DETOUR.allowsSpan(shortWater));
assertFalse(RoutePolicy.DETOUR.allowsSpan(regularWater));
assertTrue(landRavine.landRavineGap());
assertFalse(landRavine.waterGap());
assertTrue(RoutePolicy.BRIDGE.allowsSpan(regularWater));
```

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.model.BridgeSpanPolicyTest"
```

Expected: compile failure because `BridgeGapKind`, `RoutePolicy`, and new helpers do not exist.

- [ ] **Step 2: Add `BridgeGapKind`**

```java
package com.monpai.sailboatmod.road.model;

public enum BridgeGapKind {
    WATER_GAP, LAND_RAVINE_GAP, MIXED_GAP;
    public boolean includesWater() { return this == WATER_GAP || this == MIXED_GAP; }
    public boolean includesLandRavine() { return this == LAND_RAVINE_GAP || this == MIXED_GAP; }
}
```

- [ ] **Step 3: Extend `BridgeSpan` compatibly**

Use this record shape and constructor bridge:

```java
public record BridgeSpan(int startIndex, int endIndex, int waterSurfaceY, int oceanFloorY,
                         BridgeSpanKind kind, int deckY, BridgeGapKind gapKind) {
    public BridgeSpan(int startIndex, int endIndex, int waterSurfaceY, int oceanFloorY) {
        this(startIndex, endIndex, waterSurfaceY, oceanFloorY,
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);
    }
    public BridgeSpan(int startIndex, int endIndex, int waterSurfaceY, int oceanFloorY,
                      BridgeSpanKind kind, int deckY) {
        this(startIndex, endIndex, waterSurfaceY, oceanFloorY, kind, deckY, BridgeGapKind.WATER_GAP);
    }
    public BridgeSpan { kind = kind == null ? BridgeSpanKind.REGULAR_BRIDGE : kind; gapKind = gapKind == null ? BridgeGapKind.WATER_GAP : gapKind; }
    public int length() { return endIndex - startIndex; }
    public boolean hasDeckY() { return deckY != Integer.MIN_VALUE; }
    public boolean waterGap() { return gapKind.includesWater(); }
    public boolean landRavineGap() { return gapKind.includesLandRavine(); }
}
```

- [ ] **Step 4: Add `RoutePolicy`**

```java
package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.BridgeSpanKind;

public enum RoutePolicy {
    DETOUR, BRIDGE;
    public boolean allowsSpan(BridgeSpan span) {
        if (span == null) return true;
        return switch (this) {
            case DETOUR -> span.kind() == BridgeSpanKind.SHORT_SPAN_FLAT && span.length() <= 12;
            case BRIDGE -> true;
        };
    }
}
```

- [ ] **Step 5: Classify detector spans**

In `BridgeRangeDetector`, count water and land-ravine columns in `CandidateStats` and return spans with:

```java
BridgeGapKind gapKind = stats.waterColumns > 0 && stats.landGapColumns > 0
        ? BridgeGapKind.MIXED_GAP
        : stats.waterColumns > 0 ? BridgeGapKind.WATER_GAP : BridgeGapKind.LAND_RAVINE_GAP;
return new BridgeSpan(start, end, stats.maxWaterSurfaceY, stats.minFloorY, kind, deckY, gapKind);
```

- [ ] **Step 6: Verify Task 1**

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.model.BridgeSpanPolicyTest"
```

Expected: PASS.

### Task 2: Candidate Metrics and Planner Separation

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/road/planning/RouteCandidateMetrics.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/planning/BothOrchestrator.java:22-107`
- Modify: `src/main/java/com/monpai/sailboatmod/road/planning/BridgePlanner.java:75-130`
- Test: `src/test/java/com/monpai/sailboatmod/road/planning/RouteCandidateMetricsTest.java`

- [ ] **Step 1: Write failing metrics test**

Create `RouteCandidateMetricsTest`:

```java
List<BlockPos> path = java.util.stream.IntStream.range(0, 100).mapToObj(i -> new BlockPos(i, 64, 0)).toList();
BridgeSpan tiny = new BridgeSpan(10, 12, 63, 58, BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);
BridgeSpan longBridge = new BridgeSpan(10, 70, 63, 42, BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);
BridgeSpan landRavine = new BridgeSpan(1, 20, 0, 55, BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.LAND_RAVINE_GAP);
assertFalse(RouteCandidateMetrics.from(path, List.of(tiny)).bridgeDominant());
assertTrue(RouteCandidateMetrics.from(path, List.of(longBridge)).bridgeDominant());
assertFalse(RouteCandidateMetrics.containsLongWaterBridge(List.of(landRavine), 8));
```

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.planning.RouteCandidateMetricsTest"
```

Expected: compile failure because `RouteCandidateMetrics` does not exist.

- [ ] **Step 2: Add metrics class**

```java
public record RouteCandidateMetrics(int pathBlocks, int bridgeBlocks, double bridgeCoverageRatio) {
    private static final double BRIDGE_DOMINANT_RATIO = 0.35D;
    public static RouteCandidateMetrics from(List<BlockPos> path, List<BridgeSpan> spans) {
        int pathBlocks = path == null ? 0 : path.size();
        int bridgeBlocks = spans == null ? 0 : spans.stream().filter(java.util.Objects::nonNull).mapToInt(s -> Math.max(0, s.length())).sum();
        return new RouteCandidateMetrics(pathBlocks, bridgeBlocks, pathBlocks == 0 ? 0.0D : bridgeBlocks / (double) pathBlocks);
    }
    public boolean bridgeDominant() { return bridgeCoverageRatio >= BRIDGE_DOMINANT_RATIO; }
    public static boolean containsLongWaterBridge(List<BridgeSpan> spans, int maxAllowedLength) {
        return spans != null && spans.stream().anyMatch(s -> s != null && s.waterGap() && s.length() > maxAllowedLength);
    }
}
```

- [ ] **Step 3: Remove detour fallback from bridge middle path**

In `BridgePlanner.findBridgePath(...)`, replace the deep-water A* fallback with:

```java
private List<BlockPos> findBridgePath(BlockPos from, BlockPos to, TerrainSamplingCache cache) {
    return buildStraightPath(from, to, cache);
}
```

- [ ] **Step 4: Filter detour and gate bridge label**

In `BothOrchestrator`, filter spans through `RoutePolicy.DETOUR` and pass `filteredSpans` into `RoadBuilder`. In `BridgePlanner`, compute `RouteCandidateMetrics.from(finalPath, spans)` and fail or explicitly short-span-label candidates that are not bridge-dominant. Do not label a mostly-detour candidate as regular bridge.

- [ ] **Step 5: Verify Task 2**

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.planning.RouteCandidateMetricsTest" --tests "com.monpai.sailboatmod.road.construction.bridge.*"
```

Expected: PASS.

### Task 3: Width Propagation and Candidate Rebuild

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/post/PathPostProcessor.java:31-58`
- Modify: `src/main/java/com/monpai/sailboatmod/road/planning/RoadPlanningService.java:35-45`
- Modify: `src/main/java/com/monpai/sailboatmod/road/planning/BothOrchestrator.java:70-87`
- Modify: `src/main/java/com/monpai/sailboatmod/road/planning/BridgePlanner.java:75-84`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java:486-496`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java:2214-2239`
- Test: `src/test/java/com/monpai/sailboatmod/road/pathfinding/post/PathPostProcessorTest.java`

- [ ] **Step 1: Add failing width propagation test**

In `PathPostProcessorTest`, add a test that runs `process(path, cache, bridgeMinWaterDepth, 1)` and `process(path, cache, bridgeMinWaterDepth, 3)` over the same straight path and asserts the wider call produces at least double the footprint cells. Use the existing fake terrain cache pattern already present in road tests.

```java
int narrowCells = narrow.placements().stream().mapToInt(p -> p.positions().size()).sum();
int wideCells = wide.placements().stream().mapToInt(p -> p.positions().size()).sum();
assertTrue(wideCells > narrowCells * 2);
```

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.pathfinding.post.PathPostProcessorTest"
```

Expected: this test passes only for the explicit overload; planner call sites still need wiring.

- [ ] **Step 2: Add shared width helper**

In `PathPostProcessor`, add:

```java
public static int halfWidthForRoadWidth(int width) {
    return Math.max(0, width / 2);
}
```

- [ ] **Step 3: Pass half-width at every planning call site**

Use this exact pattern for config width:

```java
int width = config.getAppearance().getDefaultWidth();
int halfWidth = PathPostProcessor.halfWidthForRoadWidth(width);
PathPostProcessor.ProcessedPath processed = postProcessor.process(
        rawPath, cache, config.getBridge().getBridgeMinWaterDepth(), halfWidth);
```

Use this exact pattern for manual selected width:

```java
int width = normalized.width();
int halfWidth = PathPostProcessor.halfWidthForRoadWidth(width);
PathPostProcessor.ProcessedPath processed = postProcessor.process(
        result.path(), cache, config.getBridge().getBridgeMinWaterDepth(), halfWidth);
```

- [ ] **Step 4: Rebuild selected candidates with placements**

In `ManualRoadPlannerService.rebuildSelectedCandidate(...)`, replace the center-only `RoadBuilder.buildRoad(...)` call with post-processing plus placements:

```java
PathPostProcessor postProcessor = new PathPostProcessor();
int halfWidth = PathPostProcessor.halfWidthForRoadWidth(normalized.width());
PathPostProcessor.ProcessedPath processed = postProcessor.process(
        finalPath, cache, roadConfig.getBridge().getBridgeMinWaterDepth(), halfWidth);
List<BridgeSpan> spans = selected.optionId().equals("detour")
        ? processed.bridgeSpans().stream().filter(RoutePolicy.DETOUR::allowsSpan).toList()
        : processed.bridgeSpans();
RoadData roadData = builder.buildRoad(selected.road().roadId(), processed.path(), normalized.width(),
        cache, normalized.materialPreset(), processed.placements(), spans);
```

- [ ] **Step 5: Verify Task 3**

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.pathfinding.post.PathPostProcessorTest" --tests "com.monpai.sailboatmod.road.construction.WidthRasterizerTest"
```

Expected: PASS.

### Task 4: Preserve Segment Placements and Target Heights

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/road/model/RoadData.java:6-14`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/post/PathPostProcessor.java:24-58`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/post/RoadHeightInterpolator.java:1-60`
- Test: `src/test/java/com/monpai/sailboatmod/road/pathfinding/post/RoadHeightInterpolatorTest.java`

- [ ] **Step 1: Write failing RoadWeaver interpolation test**

Create `RoadHeightInterpolatorTest`:

```java
List<BlockPos> centers = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 68, 0), new BlockPos(20, 68, 0));
int[] targetY = {64, 68, 68};
assertEquals(66, RoadHeightInterpolator.getInterpolatedY(5, 2, centers, targetY));
assertArrayEquals(new int[]{64, 66, 68}, RoadHeightInterpolator.batchInterpolate(
        List.of(new BlockPos(0, 0, 2), new BlockPos(5, 0, 2), new BlockPos(10, 0, 2)),
        0, centers, targetY));
```

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.pathfinding.post.RoadHeightInterpolatorTest"
```

Expected: compile failure because the RoadWeaver-style overloads do not exist.

- [ ] **Step 2: Add projection interpolation API**

In `RoadHeightInterpolator`, add `getInterpolatedY(int x, int z, List<BlockPos> centers, int[] targetY)` and `batchInterpolate(List<BlockPos> positions, int segmentIndex, List<BlockPos> centers, int[] targetY)`. Use nearest projection against segment lines, not nearest center point:

```java
private record Projection(int segmentIndex, double t, double distanceSq) {}

private static int interpolateY(int segmentIndex, double t, int[] targetY) {
    int y0 = targetY[Math.max(0, Math.min(segmentIndex, targetY.length - 1))];
    int y1 = targetY[Math.max(0, Math.min(segmentIndex + 1, targetY.length - 1))];
    return (int) Math.round(y0 + (y1 - y0) * t);
}
```

Search only `segmentIndex - 20` through `segmentIndex + 20` in the batch overload, matching RoadWeaver's bounded projection approach.

- [ ] **Step 3: Extend `RoadData`**

Change `RoadData` to include `placements` and `targetY` while preserving the old constructor:

```java
public record RoadData(String roadId, int width, List<RoadSegment> segments,
                       List<BridgeSpan> bridgeSpans, RoadMaterial material,
                       List<BuildStep> buildSteps, List<BlockPos> centerPath,
                       List<RoadSegmentPlacement> placements, List<Integer> targetY) {
    public RoadData(String roadId, int width, List<RoadSegment> segments, List<BridgeSpan> bridgeSpans,
                    RoadMaterial material, List<BuildStep> buildSteps, List<BlockPos> centerPath) {
        this(roadId, width, segments, bridgeSpans, material, buildSteps, centerPath,
                List.of(), centerPath == null ? List.of() : centerPath.stream().map(BlockPos::getY).toList());
    }
    public RoadData {
        segments = segments == null ? List.of() : List.copyOf(segments);
        bridgeSpans = bridgeSpans == null ? List.of() : List.copyOf(bridgeSpans);
        buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
        centerPath = centerPath == null ? List.of() : List.copyOf(centerPath);
        placements = placements == null ? List.of() : List.copyOf(placements);
        targetY = targetY == null ? List.of() : List.copyOf(targetY);
    }
}
```

- [ ] **Step 4: Extend `ProcessedPath`**

Add `targetY` to the record and return it from `process(...)`:

```java
public record ProcessedPath(List<BlockPos> path, List<BridgeSpan> bridgeSpans,
                            List<RoadSegmentPlacement> placements, List<Integer> targetY) {}
List<Integer> targetY = stablePath.stream().map(BlockPos::getY).toList();
return new ProcessedPath(stablePath, finalBridges, placements, targetY);
```

Keep the existing two-argument convenience constructor for compatibility.

- [ ] **Step 5: Verify Task 4**

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.pathfinding.post.RoadHeightInterpolatorTest"
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false compileJava
```

Expected: PASS and BUILD SUCCESSFUL.

### Task 5: Segment-Growth Build Queue for Land Road

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/road/construction/road/RoadSegmentStepPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/road/RoadSegmentPaver.java:26-75`
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/road/RoadBuilder.java:49-98`
- Test: `src/test/java/com/monpai/sailboatmod/road/construction/road/RoadSegmentStepPlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/road/construction/road/RoadSegmentPaverTest.java`

- [ ] **Step 1: Write failing segment-growth test**

Create `RoadSegmentStepPlannerTest` that builds a road with one placement containing width cells at x=0,5,10 and center heights 64->68. Assert that `roadData.placements()` equals the provided placements and surface build steps contain Y values `64`, `66`, and `68`.

```java
List<BlockPos> centerPath = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 68, 0));
List<RoadSegmentPlacement> placements = List.of(new RoadSegmentPlacement(centerPath.get(0), 0,
        List.of(new BlockPos(0, 64, 1), new BlockPos(5, 64, 1), new BlockPos(10, 64, 1)), false));
RoadData roadData = builder.buildRoad("test", centerPath, 3, cache, "auto", placements, List.of());
Set<Integer> surfaceYs = roadData.buildSteps().stream().filter(s -> s.phase() == BuildPhase.SURFACE)
        .map(s -> s.pos().getY()).collect(Collectors.toSet());
assertTrue(surfaceYs.containsAll(Set.of(64, 66, 68)));
```

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.construction.road.RoadSegmentStepPlannerTest"
```

Expected: failure because queue generation still uses old global paver semantics.

- [ ] **Step 2: Create `RoadSegmentStepPlanner`**

Implement a small coordinator that iterates segment placements in order and delegates land segments to `RoadSegmentPaver.paveSegment(...)`:

```java
public class RoadSegmentStepPlanner {
    private final RoadSegmentPaver paver;
    public RoadSegmentStepPlanner(RoadSegmentPaver paver) { this.paver = paver; }
    public List<BuildStep> buildLandSteps(List<RoadSegmentPlacement> placements, List<BlockPos> centerPath,
                                          List<Integer> targetY, List<BridgeSpan> bridgeSpans,
                                          TerrainSamplingCache cache, String materialPreset, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        int[] heights = toArray(centerPath, targetY);
        for (RoadSegmentPlacement placement : placements) {
            if (placement.bridge() || isBridgeSegment(placement.segmentIndex(), bridgeSpans)) continue;
            List<BuildStep> segmentSteps = paver.paveSegment(placement, centerPath, heights, cache, materialPreset, order);
            steps.addAll(segmentSteps);
            order += segmentSteps.size();
        }
        return steps;
    }
}
```

Add private `toArray(...)` and `isBridgeSegment(...)` helpers in the same class. `isBridgeSegment` returns true when `segmentIndex` is within any span's `[startIndex, endIndex]`.

- [ ] **Step 3: Add per-segment paver API**

In `RoadSegmentPaver`, add `paveSegment(...)` that uses `RoadHeightInterpolator.batchInterpolate(...)`. The surface block uses interpolated `roadY`, while terrain height only controls support and clear extent:

```java
int[] heights = RoadHeightInterpolator.batchInterpolate(placement.positions(), placement.segmentIndex(), centerPath, targetY);
BlockPos surfacePos = new BlockPos(x, heights[i], z);
int terrainY = cache.isWater(x, z) ? cache.getWaterSurfaceY(x, z) : cache.getHeight(x, z);
int clearTop = Math.max(surfacePos.getY() + CLEAR_HEIGHT, Math.max(cache.motionBlockingHeight(x, z), terrainY + 1));
```

Emit steps in this local order for each footprint cell: AIR clear steps, foundation support steps, surface step. Do not globally dedupe by `x/z` inside the paver.

- [ ] **Step 4: Route `RoadBuilder` through the segment planner**

Add field initialization:

```java
private final RoadSegmentStepPlanner segmentStepPlanner;
this.segmentStepPlanner = new RoadSegmentStepPlanner(paver);
```

Replace the old land paver branch with:

```java
List<RoadSegmentPlacement> effectivePlacements = placements == null || placements.isEmpty()
        ? new PathPostProcessor().rasterizeForBuilderFallback(landPath, PathPostProcessor.halfWidthForRoadWidth(width), bridgeSpans)
        : placements;
List<Integer> targetY = centerPath.stream().map(BlockPos::getY).toList();
List<BuildStep> landSteps = segmentStepPlanner.buildLandSteps(effectivePlacements, centerPath, targetY,
        bridgeSpans, cache, normalizedPreset, order);
```

Add `PathPostProcessor.rasterizeForBuilderFallback(...)` as a public wrapper around existing `rasterizeSegments(...)`.

- [ ] **Step 5: Return preserved artifacts from `RoadBuilder`**

Return:

```java
return new RoadData(roadId, width, List.of(), bridgeSpans, defaultMaterial, allSteps, centerPath,
        effectivePlacements, targetY);
```

- [ ] **Step 6: Verify Task 5**

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.construction.road.RoadSegmentStepPlannerTest" --tests "com.monpai.sailboatmod.road.construction.road.RoadSegmentPaverTest"
```

Expected: PASS.

### Task 6: Bridge Footprint Integration and Short-Flat Anchors

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgeRangeDetector.java:12-220`
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilder.java:12-260`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/post/PathPostProcessor.java:48-52`
- Test: `src/test/java/com/monpai/sailboatmod/road/construction/bridge/BridgeRangeDetectorTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilderTest.java`

- [ ] **Step 1: Add failing short-span tests**

Add tests with fake caches that assert:

```java
assertTrue(spans.stream().noneMatch(s -> s.kind() == BridgeSpanKind.SHORT_SPAN_FLAT));
```

for a short water gap missing one land head, and:

```java
assertTrue(spans.stream().anyMatch(s -> s.kind() == BridgeSpanKind.SHORT_SPAN_FLAT
        && s.gapKind() == BridgeGapKind.LAND_RAVINE_GAP));
```

for a short ravine with two valid heads.

Add a bridge builder test that constructs a `SHORT_SPAN_FLAT` span and asserts deck steps have one Y and no `BuildPhase.RAMP`.

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.construction.bridge.BridgeRangeDetectorTest" --tests "com.monpai.sailboatmod.road.construction.bridge.BridgeBuilderTest"
```

Expected: failure if either land-head validation or no-ramp deck output is incomplete.

- [ ] **Step 2: Validate both land heads before short-flat classification**

In `BridgeRangeDetector`, add helpers:

```java
private boolean hasBothLandHeads(List<BlockPos> centerPath, int start, int end, TerrainSamplingCache cache) {
    return findLandHead(centerPath, start, -1, cache) != null && findLandHead(centerPath, end, 1, cache) != null;
}
private BlockPos findLandHead(List<BlockPos> centerPath, int edge, int direction, TerrainSamplingCache cache) {
    for (int offset = 1; offset <= LAND_HEAD_SEARCH; offset++) {
        int index = edge + direction * offset;
        if (index < 0 || index >= centerPath.size()) break;
        BlockPos pos = centerPath.get(index);
        if (!cache.isWater(pos.getX(), pos.getZ()) && cache.getHeight(pos.getX(), pos.getZ()) >= pos.getY() - MAX_SHORT_ENDPOINT_DELTA) return pos;
    }
    return null;
}
```

Only return `BridgeSpanKind.SHORT_SPAN_FLAT` when this helper returns true.

- [ ] **Step 3: Keep bridge placement Y fixed for short-flat spans**

Make sure `PathPostProcessor.anchorPlacement(...)` exits for `pl.bridge()` and `clampShortFlatDecks(...)` uses `span.deckY()` for every center point inside the span.

- [ ] **Step 4: Emit short-flat bridge without ramps**

At the top of `BridgeBuilder.build(...)`:

```java
if (span.kind() == BridgeSpanKind.SHORT_SPAN_FLAT) {
    return buildShortFlat(span, centerPath, width, material, startOrder);
}
```

`buildShortFlat(...)` emits only `BuildPhase.DECK` across the full width at one deck Y. Use the existing width-direction helper in `BridgeBuilder` so diagonal or north/south bridges do not hardcode a Z offset.

- [ ] **Step 5: Verify Task 6**

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.construction.bridge.*"
```

Expected: PASS.

### Task 7: RoadWeaver-Style Surface and Cleanup Policy

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristics.java:11-58`
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/road/AboveColumnClearer.java:17-50`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/ConstructionStepExecutor.java:11-22`
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/road/RoadSegmentPaver.java:54-60`
- Test: `src/test/java/com/monpai/sailboatmod/road/construction/road/RoadSegmentPaverTest.java`

- [ ] **Step 1: Add cleanup regression test**

In `RoadSegmentPaverTest`, add a test that creates a placement with three width cells and asserts each cell receives above-surface AIR cleanup steps:

```java
long airSteps = steps.stream().filter(step -> step.state().is(Blocks.AIR)).count();
assertTrue(airSteps >= 12, "three footprint cells should clear multiple above-surface blocks");
```

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.construction.road.RoadSegmentPaverTest"
```

Expected: PASS after Task 5 if cleanup is already emitted per footprint cell; otherwise FAIL until this task is complete.

- [ ] **Step 2: Centralize cleanup classification**

In `RoadSurfaceHeuristics`, add:

```java
public static boolean isProtectedFromNaturalCleanup(BlockState state) {
    return state != null && (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER));
}
public static boolean isNaturalCleanupTarget(BlockState state) {
    return state != null && !state.isAir() && !isProtectedFromNaturalCleanup(state)
            && (isIgnoredSurfaceNoise(state) || state.is(BlockTags.REPLACEABLE));
}
```

- [ ] **Step 3: Use the classifier in construction cleanup**

Change `AboveColumnClearer.shouldClear(...)` to:

```java
return RoadSurfaceHeuristics.isNaturalCleanupTarget(level.getBlockState(pos));
```

Change `ConstructionStepExecutor.clearNaturalObstacles(...)` to call a local helper:

```java
private static void clearIfNatural(ServerLevel level, BlockPos pos) {
    if (RoadSurfaceHeuristics.isNaturalCleanupTarget(level.getBlockState(pos))) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }
}
```

Call it for `pos` and `pos.above()`.

- [ ] **Step 4: Verify Task 7**

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.construction.road.RoadSegmentPaverTest"
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false compileJava
```

Expected: PASS and BUILD SUCCESSFUL.

### Task 8: Preview Filtering and Physical Demolition

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java:332-360`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java:515-520`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java:2281-2288`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java:4124-4180`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`

- [ ] **Step 1: Add preview helper test**

Expose a package-private helper in `ManualRoadPlannerService` named `isVisibleRoadPreviewStep(BuildStep step)`. Test it with one AIR cleanup step and one surface step:

```java
BuildStep cleanup = new BuildStep(0, new BlockPos(0, 65, 0), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION);
BuildStep surface = new BuildStep(1, new BlockPos(0, 64, 0), Blocks.DIRT_PATH.defaultBlockState(), BuildPhase.SURFACE);
assertFalse(ManualRoadPlannerService.isVisibleRoadPreviewStep(cleanup));
assertTrue(ManualRoadPlannerService.isVisibleRoadPreviewStep(surface));
```

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"
```

Expected: compile failure if the helper is absent.

- [ ] **Step 2: Filter AIR steps out of previews**

Add helper:

```java
static boolean isVisibleRoadPreviewStep(BuildStep step) {
    return step != null && !step.state().isAir();
}
```

Apply it around each `for (BuildStep bs : roadData.buildSteps())` preview/ghost conversion in `ManualRoadPlannerService`:

```java
if (!isVisibleRoadPreviewStep(bs)) {
    continue;
}
```

- [ ] **Step 3: Route physical demolition through rollback manager**

In `previewOrDemolishRoad(...)`, for the DEMOLISH action call:

```java
boolean demolitionStarted = StructureConstructionManager.demolishRoadById(player.serverLevel(), selected.road().roadId());
if (!demolitionStarted) {
    return Component.literal("Road demolition unavailable: no construction or rollback plan was found.");
}
return Component.literal("Road demolition started.");
```

Metadata-only removal is allowed only for a preview/cancel action before physical construction exists.

- [ ] **Step 4: Keep road metadata until rollback starts**

In `StructureConstructionManager.demolishRoadById(...)`, return `true` only when an active job, persisted job, or restorable completed placement plan is found and rollback/demolition state is created. Return `false` without deleting `RoadNetworkRecord` when no physical plan exists.

- [ ] **Step 5: Verify Task 8**

Run:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"
```

Expected: PASS for helper tests. If unrelated Minecraft static initialization fails, rerun the specific helper test and record the unrelated failure.

### Task 9: Final Verification and Jar Build

**Files:**
- Modify touched files only if compile errors point to changed signatures.

- [ ] **Step 1: Run focused road model and planning tests**

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.model.*" --tests "com.monpai.sailboatmod.road.planning.*"
```

Expected: PASS.

- [ ] **Step 2: Run focused post-processing and construction tests**

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.pathfinding.post.*" --tests "com.monpai.sailboatmod.road.construction.*"
```

Expected: PASS for interpolation, width, paver, bridge detector, and bridge builder tests.

- [ ] **Step 3: Compile Java**

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build jar without full tests**

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false build -x test
```

Expected: BUILD SUCCESSFUL and jar output under `build/libs/`.

- [ ] **Step 5: Final handoff notes**

Report these exact categories:

```text
Verified:
- focused road model/planning tests: PASS or command output summary
- focused post-processing/construction tests: PASS or command output summary
- compileJava: PASS or failure summary
- build -x test: PASS or failure summary

Known unrelated failures:
- full test suite failures only if encountered and unrelated to touched road code
```

---

## Self-Review

- Spec coverage: Tasks 1-2 implement route policy, detour/bridge separation, span categories, and bridge metrics. Task 3 implements width propagation and rebuild behavior. Tasks 4-5 implement RoadWeaver-style segment-growth queue generation. Task 6 implements short-span anchors and flat no-ramp bridge decks. Task 7 implements RoadWeaver-style cleanup. Task 8 implements preview filtering and physical demolition through rollback. Task 9 packages and verifies.
- Red-flag scan: no unfinished markers, vague validation-only steps, or deferred implementation phrases are intentionally present.
- Type consistency: `BridgeGapKind`, `RoutePolicy`, `RouteCandidateMetrics`, `RoadData.placements()`, `RoadData.targetY()`, `ProcessedPath.targetY()`, `RoadHeightInterpolator.batchInterpolate(..., segmentIndex, ...)`, and `RoadSegmentStepPlanner` are introduced before later tasks use them.
- Commit policy: the plan does not ask for commits because this session has not received explicit commit permission.

