# Road Planner Water Bridge Ramp Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix Road Planner water crossing classification, ramp generation, and long bridge preview visibility while keeping manual and auto roads on one shared structure expander.

**Architecture:** Add a depth-aware water classifier before route expansion emits segment types; keep `RoadNodeStructureExpander` as the single build/preview source; split client preview rendering into nearby model rendering and full-route wireframe rendering. Tests drive each behavior before implementation.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, Gradle, existing `RoadPlanner*` client/network/structure classes.

---

## File Map

- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterDepthProbe.java`
  - One-purpose interface for measuring water depth at route-expansion time.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitter.java`
  - Classifies continuous water spans into ignored noise, `BRIDGE_SMALL`, or `BRIDGE_MAJOR`.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpander.java`
  - Passes depth probe into splitter and stops re-promoting tiny water noise to bridge after split.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
  - Supplies client water depth from loaded terrain.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java`
  - Guarantees bridge ramp ranges and keeps small bridge deck height low enough for short spans.
- Modify `src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java`
  - Keeps model rendering capped nearby, but renders far bridge/road preview wireframes from full ghost data.
- Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitterTest.java`
  - Covers water span thresholds and depth upgrade.
- Modify `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpanderTest.java`
  - Covers route-level bridge type output and tiny water noise behavior.
- Modify `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`
  - Covers small bridge ramp/deck and ramp height continuity.
- Modify `src/test/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRendererTest.java`
  - Covers uncropped far wireframe preview list.

---

### Task 1: Depth-aware water crossing classification

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterDepthProbe.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitter.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitterTest.java`

- [ ] **Step 1: Write the failing water splitter tests**

Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitterTest.java` with this content:

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerWaterCrossingSplitterTest {
    @Test
    void threeBlockWaterSpanIsIgnoredAsNoise() {
        RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(7, 9, 1);

        assertFalse(result.didSplit());
    }

    @Test
    void fourToTwentyFourBlockWaterSpanCreatesSmallBridge() {
        RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(6, 13, 1);

        assertTrue(result.didSplit());
        assertTrue(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_SMALL));
        assertFalse(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR));
    }

    @Test
    void wideWaterSpanCreatesMajorBridge() {
        RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(4, 32, 1);

        assertTrue(result.didSplit());
        assertTrue(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR));
    }

    @Test
    void narrowDeepWaterSpanCreatesMajorBridge() {
        RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(8, 13, 3);

        assertTrue(result.didSplit());
        assertTrue(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR));
    }

    private static RoadPlannerWaterCrossingSplitter.SplitResult splitAcrossWater(int waterStartX, int waterEndX, int waterDepth) {
        RoadPlannerBridgeRuleService.LandProbe landProbe = (x, z) -> x < waterStartX || x > waterEndX;
        RoadPlannerHeightSampler heightSampler = (x, z) -> 64;
        RoadPlannerWaterDepthProbe depthProbe = (x, z) -> landProbe.isLand(x, z) ? 0 : waterDepth;
        return RoadPlannerWaterCrossingSplitter.split(
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0),
                landProbe,
                heightSampler,
                depthProbe
        );
    }
}
```

- [ ] **Step 2: Run the splitter tests and verify they fail**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerWaterCrossingSplitterTest
```

Expected result: compilation fails because `RoadPlannerWaterDepthProbe` and the five-argument `RoadPlannerWaterCrossingSplitter.split(...)` overload do not exist.

- [ ] **Step 3: Add the water depth probe interface**

Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterDepthProbe.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

@FunctionalInterface
public interface RoadPlannerWaterDepthProbe {
    int waterDepthAt(int x, int z);

    static RoadPlannerWaterDepthProbe shallowFromLandProbe(RoadPlannerBridgeRuleService.LandProbe landProbe) {
        RoadPlannerBridgeRuleService.LandProbe safeLandProbe = landProbe == null ? (x, z) -> true : landProbe;
        return (x, z) -> safeLandProbe.isLand(x, z) ? 0 : 1;
    }
}
```

- [ ] **Step 4: Replace the splitter constants and overloads**

In `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitter.java`, replace the constants and public `split` method area with this code:

```java
private static final int SAMPLE_SPACING = 1;
private static final int MIN_BRIDGE_WATER_BLOCKS = 4;
private static final int SMALL_BRIDGE_MAX_BLOCKS = 24;
private static final int MAJOR_BRIDGE_MIN_DEPTH = 3;

private RoadPlannerWaterCrossingSplitter() {
}

public static SplitResult split(BlockPos from, BlockPos to,
                                RoadPlannerBridgeRuleService.LandProbe landProbe,
                                RoadPlannerHeightSampler heightSampler) {
    return split(from, to, landProbe, heightSampler, RoadPlannerWaterDepthProbe.shallowFromLandProbe(landProbe));
}

public static SplitResult split(BlockPos from, BlockPos to,
                                RoadPlannerBridgeRuleService.LandProbe landProbe,
                                RoadPlannerHeightSampler heightSampler,
                                RoadPlannerWaterDepthProbe waterDepthProbe) {
    if (from == null || to == null || landProbe == null) {
        return SplitResult.noSplit();
    }
    boolean fromLand = landProbe.isLand(from.getX(), from.getZ());
    boolean toLand = landProbe.isLand(to.getX(), to.getZ());
    if (fromLand && toLand) {
        RoadPlannerWaterDepthProbe safeDepthProbe = waterDepthProbe == null
                ? RoadPlannerWaterDepthProbe.shallowFromLandProbe(landProbe)
                : waterDepthProbe;
        List<SamplePoint> samples = sampleLine(from, to, landProbe);
        List<WaterSpan> spans = detectWaterSpans(samples, safeDepthProbe);
        if (spans.isEmpty()) {
            return SplitResult.noSplit();
        }
        return buildSplitFromSpans(from, to, samples, spans, heightSampler);
    }
    return SplitResult.noSplit();
}
```

- [ ] **Step 5: Replace `detectWaterSpans(...)` with depth-aware classification**

In `RoadPlannerWaterCrossingSplitter.java`, replace the existing `detectWaterSpans` method with this code:

```java
private static List<WaterSpan> detectWaterSpans(List<SamplePoint> samples, RoadPlannerWaterDepthProbe waterDepthProbe) {
    List<WaterSpan> spans = new ArrayList<>();
    int i = 0;
    while (i < samples.size()) {
        if (!samples.get(i).land()) {
            int spanStart = i;
            int maxDepth = 0;
            while (i < samples.size() && !samples.get(i).land()) {
                SamplePoint sample = samples.get(i);
                maxDepth = Math.max(maxDepth, Math.max(0, waterDepthProbe.waterDepthAt(sample.x(), sample.z())));
                i++;
            }
            int spanEnd = i - 1;
            int spanBlocks = Math.max(1, spanEnd - spanStart + 1) * SAMPLE_SPACING;
            if (spanBlocks >= MIN_BRIDGE_WATER_BLOCKS) {
                RoadPlannerSegmentType bridgeType = maxDepth >= MAJOR_BRIDGE_MIN_DEPTH || spanBlocks > SMALL_BRIDGE_MAX_BLOCKS
                        ? RoadPlannerSegmentType.BRIDGE_MAJOR
                        : RoadPlannerSegmentType.BRIDGE_SMALL;
                spans.add(new WaterSpan(spanStart, spanEnd, spanBlocks, maxDepth, bridgeType));
            }
        } else {
            i++;
        }
    }
    return spans;
}
```

- [ ] **Step 6: Use the span bridge type while building split nodes**

In `buildSplitFromSpans(...)`, replace this block:

```java
int spanWidth = span.endSampleIndex() - span.startSampleIndex();
int spanBlocks = spanWidth * SAMPLE_SPACING;
RoadPlannerSegmentType bridgeType = spanBlocks <= 24
        ? RoadPlannerSegmentType.BRIDGE_SMALL
        : RoadPlannerSegmentType.BRIDGE_MAJOR;
```

with:

```java
RoadPlannerSegmentType bridgeType = span.bridgeType();
```

Then replace the `WaterSpan` record at the bottom of the file with:

```java
public record WaterSpan(int startSampleIndex,
                        int endSampleIndex,
                        int spanBlocks,
                        int maxDepth,
                        RoadPlannerSegmentType bridgeType) {}
```

- [ ] **Step 7: Run the splitter tests and verify they pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerWaterCrossingSplitterTest
```

Expected result: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit Task 1**

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterDepthProbe.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitter.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitterTest.java
git commit -m "fix: classify road planner water crossings"
```

---

### Task 2: Route expander integration and client water depth

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpander.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpanderTest.java`

- [ ] **Step 1: Add failing route expander tests**

Append these tests to `RoadPlannerRouteExpanderTest` before the `isBridge(...)` helper:

```java
@Test
void tinyWaterNoiseDoesNotBecomeBridgeDuringConnectionTyping() {
    RoadPlannerRouteExpander.Result expanded = RoadPlannerRouteExpander.expand(
            List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0)),
            List.of(RoadPlannerSegmentType.ROAD),
            (x, z) -> x < 18 || x > 20,
            (x, z) -> 64,
            (x, z) -> x >= 18 && x <= 20 ? 1 : 0
    );

    assertTrue(expanded.success());
    assertTrue(expanded.segmentTypes().stream().allMatch(type -> type == RoadPlannerSegmentType.ROAD));
}

@Test
void narrowWaterCrossingUsesSmallBridgeSegment() {
    RoadPlannerRouteExpander.Result expanded = RoadPlannerRouteExpander.expand(
            List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0)),
            List.of(RoadPlannerSegmentType.ROAD),
            (x, z) -> x < 16 || x > 23,
            (x, z) -> 64,
            (x, z) -> x >= 16 && x <= 23 ? 1 : 0
    );

    assertTrue(expanded.success());
    assertTrue(expanded.segmentTypes().stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_SMALL));
    assertFalse(expanded.segmentTypes().stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR));
}

@Test
void narrowDeepWaterCrossingUsesMajorBridgeSegment() {
    RoadPlannerRouteExpander.Result expanded = RoadPlannerRouteExpander.expand(
            List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0)),
            List.of(RoadPlannerSegmentType.ROAD),
            (x, z) -> x < 16 || x > 23,
            (x, z) -> 64,
            (x, z) -> x >= 16 && x <= 23 ? 3 : 0
    );

    assertTrue(expanded.success());
    assertTrue(expanded.segmentTypes().stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR));
}
```

- [ ] **Step 2: Run route expander tests and verify they fail**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpanderTest
```

Expected result: compilation fails because the five-argument `RoadPlannerRouteExpander.expand(...)` overload does not exist.

- [ ] **Step 3: Add five-argument route expander overload**

In `RoadPlannerRouteExpander.java`, replace the public `expand(...)` method with these two overloads:

```java
public static Result expand(List<BlockPos> nodes,
                            List<RoadPlannerSegmentType> segmentTypes,
                            RoadPlannerBridgeRuleService.LandProbe landProbe,
                            RoadPlannerHeightSampler heightSampler) {
    return expand(nodes, segmentTypes, landProbe, heightSampler, RoadPlannerWaterDepthProbe.shallowFromLandProbe(landProbe));
}

public static Result expand(List<BlockPos> nodes,
                            List<RoadPlannerSegmentType> segmentTypes,
                            RoadPlannerBridgeRuleService.LandProbe landProbe,
                            RoadPlannerHeightSampler heightSampler,
                            RoadPlannerWaterDepthProbe waterDepthProbe) {
    List<BlockPos> compactNodes = compact(nodes);
    if (compactNodes.size() < 2) {
        return Result.failure(compactNodes, normalizeSegments(segmentTypes, compactNodes.size()), "route_requires_two_nodes");
    }
    RoadPlannerBridgeRuleService.LandProbe safeLandProbe = landProbe == null ? (x, z) -> true : landProbe;
    RoadPlannerHeightSampler safeHeightSampler = heightSampler == null ? (x, z) -> 64 : heightSampler;
    RoadPlannerWaterDepthProbe safeWaterDepthProbe = waterDepthProbe == null
            ? RoadPlannerWaterDepthProbe.shallowFromLandProbe(safeLandProbe)
            : waterDepthProbe;
    List<RoadPlannerSegmentType> compactTypes = normalizeSegments(segmentTypes, compactNodes.size());
    List<BlockPos> expandedNodes = new ArrayList<>();
    List<RoadPlannerSegmentType> expandedTypes = new ArrayList<>();
    addDistinct(expandedNodes, compactNodes.get(0));
    for (int segmentIndex = 0; segmentIndex < compactNodes.size() - 1; segmentIndex++) {
        BlockPos from = compactNodes.get(segmentIndex);
        BlockPos to = compactNodes.get(segmentIndex + 1);
        RoadPlannerSegmentType requestedType = compactTypes.get(segmentIndex);
        List<SegmentPoint> segmentPoints = expandSegment(from, to, requestedType, safeLandProbe, safeHeightSampler, safeWaterDepthProbe);
        for (int index = 1; index < segmentPoints.size(); index++) {
            SegmentPoint previous = segmentPoints.get(index - 1);
            SegmentPoint point = segmentPoints.get(index);
            addConnectedNode(expandedNodes, expandedTypes, point.pos(), segmentTypeForConnection(previous, point));
        }
    }
    RoadPlannerBridgeSegmentNormalizer.Result normalized = RoadPlannerBridgeSegmentNormalizer.normalize(expandedNodes, expandedTypes, safeLandProbe);
    boolean hasBridge = normalized.segmentTypes().stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL);
    boolean success = !normalized.hasBlockingIssues() || hasBridge;
    return new Result(success, normalized.nodes(), normalized.segmentTypes(), normalized.issues());
}
```

- [ ] **Step 4: Pass depth probe into segment expansion**

Change the `expandSegment(...)` signature to:

```java
private static List<SegmentPoint> expandSegment(BlockPos from,
                                                BlockPos to,
                                                RoadPlannerSegmentType requestedType,
                                                RoadPlannerBridgeRuleService.LandProbe landProbe,
                                                RoadPlannerHeightSampler heightSampler,
                                                RoadPlannerWaterDepthProbe waterDepthProbe) {
```

Inside that method, replace the splitter call with:

```java
RoadPlannerWaterCrossingSplitter.SplitResult split = RoadPlannerWaterCrossingSplitter.split(from, to, landProbe, heightSampler, waterDepthProbe);
```

- [ ] **Step 5: Stop post-processing tiny water into bridges**

Replace `segmentTypeForConnection(...)` with this method:

```java
private static RoadPlannerSegmentType segmentTypeForConnection(SegmentPoint previous,
                                                                SegmentPoint point) {
    RoadPlannerSegmentType previousType = previous.segmentType();
    RoadPlannerSegmentType pointType = point.segmentType();
    if (previousType == RoadPlannerSegmentType.BRIDGE_MAJOR || pointType == RoadPlannerSegmentType.BRIDGE_MAJOR) {
        return RoadPlannerSegmentType.BRIDGE_MAJOR;
    }
    if (previousType == RoadPlannerSegmentType.BRIDGE_SMALL || pointType == RoadPlannerSegmentType.BRIDGE_SMALL) {
        return RoadPlannerSegmentType.BRIDGE_SMALL;
    }
    if (previousType == RoadPlannerSegmentType.TUNNEL || pointType == RoadPlannerSegmentType.TUNNEL) {
        return RoadPlannerSegmentType.TUNNEL;
    }
    return RoadPlannerSegmentType.ROAD;
}
```

Then delete the old private `segmentTouchesWater(...)` method from `RoadPlannerRouteExpander.java`.

- [ ] **Step 6: Add client water depth sampling**

In `RoadPlannerScreen.java`, add this private method below `isClientLand(...)`:

```java
private static int clientWaterDepth(int x, int z) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft == null || minecraft.level == null) {
        return 0;
    }
    ClientLevel level = minecraft.level;
    int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
    if (surfaceY < level.getMinBuildHeight()) {
        return 0;
    }
    BlockPos surface = new BlockPos(x, surfaceY, z);
    if (!level.getBlockState(surface).getFluidState().is(Fluids.WATER)
            && !level.getBlockState(surface.above()).getFluidState().is(Fluids.WATER)) {
        return 0;
    }
    int depth = 0;
    for (int y = surfaceY; y >= level.getMinBuildHeight(); y--) {
        BlockPos probe = new BlockPos(x, y, z);
        if (!level.getBlockState(probe).getFluidState().is(Fluids.WATER)) {
            break;
        }
        depth++;
        if (depth >= 16) {
            break;
        }
    }
    return depth;
}
```

- [ ] **Step 7: Pass client water depth into route expansion and splitting**

In `RoadPlannerScreen.expandRoute(...)`, replace the `RoadPlannerRouteExpander.expand(...)` call with:

```java
return RoadPlannerRouteExpander.expand(
        nodes,
        segmentTypes,
        RoadPlannerScreen::isClientLand,
        testMode ? (x, z) -> 64 : RoadPlannerHeightSampler.clientLoadedTerrain(),
        testMode ? (x, z) -> 1 : RoadPlannerScreen::clientWaterDepth
);
```

In `RoadPlannerScreen.addNodeWithWaterSplit(...)`, replace the `RoadPlannerWaterCrossingSplitter.split(...)` call with:

```java
RoadPlannerWaterCrossingSplitter.SplitResult split = RoadPlannerWaterCrossingSplitter.split(
        from,
        target,
        RoadPlannerScreen::isClientLand,
        testMode ? (x, z) -> 64 : RoadPlannerHeightSampler.clientLoadedTerrain(),
        testMode ? (x, z) -> 1 : RoadPlannerScreen::clientWaterDepth
);
```

- [ ] **Step 8: Run route expander tests and verify they pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpanderTest
```

Expected result: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit Task 2**

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpander.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpanderTest.java
git commit -m "fix: route planner respects water crossing thresholds"
```

---

### Task 3: Bridge ramp profile and short bridge deck stability

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`

- [ ] **Step 1: Add failing bridge ramp tests**

Append these tests to `RoadNodeStructureExpanderTest` before `blockedBridgeMarkerBuildsAsMajorBridge()`:

```java
@Test
void shortSmallBridgeStillEmitsRampAndDeck() {
    RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
            List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
            List.of(RoadPlannerSegmentType.BRIDGE_SMALL),
            RoadPlannerBuildSettings.DEFAULTS,
            RoadTerrainSampler.flat(62),
            RoadStructureMode.BUILD
    );

    assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
    assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
}

@Test
void bridgeRampProfileDoesNotJumpMoreThanOneBlockPerSample() {
    RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
            List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)),
            List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
            RoadPlannerBuildSettings.DEFAULTS,
            RoadTerrainSampler.flat(60),
            RoadStructureMode.BUILD
    );

    List<BlockPos> centerRampAndDeck = result.buildSteps().stream()
            .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP
                    || step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK)
            .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
            .filter(pos -> pos.getZ() == 0)
            .sorted(java.util.Comparator.comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY))
            .toList();

    int previousX = Integer.MIN_VALUE;
    int previousY = Integer.MIN_VALUE;
    for (BlockPos pos : centerRampAndDeck) {
        if (pos.getX() == previousX) {
            continue;
        }
        if (previousX != Integer.MIN_VALUE) {
            assertTrue(Math.abs(pos.getY() - previousY) <= 1, "bridge y jump at x=" + pos.getX());
        }
        previousX = pos.getX();
        previousY = pos.getY();
    }
}
```

- [ ] **Step 2: Run structure tests and verify at least one new test fails**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected result: at least one new test fails because current bridge profile can compress ramp too much on short spans.

- [ ] **Step 3: Add a bridge profile record and constants**

In `BridgeStructureEmitter.java`, add this record near the constants:

```java
private record BridgeProfile(List<RoadCenterlinePoint> points, int deckStartInclusive, int deckEndExclusive) {
}
```

- [ ] **Step 4: Replace bridge profile setup in `emitProgrammaticBridge(...)`**

Inside `emitProgrammaticBridge(...)`, replace the local variables from `int heightBonus = ...` through `List<RoadCenterlinePoint> bridgeProfile = ...` with:

```java
BridgeProfile profile = buildBridgeProfile(points, span);
List<RoadCenterlinePoint> bridgeProfile = profile.points();
int deckStart = profile.deckStartInclusive();
int deckEndExclusive = profile.deckEndExclusive();
```

- [ ] **Step 5: Replace the old bridge profile helpers**

In `BridgeStructureEmitter.java`, replace `bridgeY(...)` and `bridgeProfile(...)` with these methods:

```java
private static BridgeProfile buildBridgeProfile(List<RoadCenterlinePoint> points, RoadSpan span) {
    int entryY = points.get(0).targetY();
    int exitY = points.get(points.size() - 1).targetY();
    int terrainFloor = points.stream().mapToInt(RoadCenterlinePoint::terrainY).min().orElse(Math.min(entryY, exitY));
    boolean smallBridge = span.sourceSegmentType() == RoadPlannerSegmentType.BRIDGE_SMALL;
    int desiredDeckY = smallBridge
            ? Math.max(Math.max(entryY, exitY) + 1, terrainFloor + 2)
            : Math.max(Math.max(entryY, exitY) + MAJOR_HEIGHT_BONUS, terrainFloor + 6);
    int maxAvailableRampSamples = Math.max(2, Math.max(1, (points.size() - 2) / 2));
    int maxDeckYFromEntry = entryY + maxAvailableRampSamples;
    int maxDeckYFromExit = exitY + maxAvailableRampSamples;
    int deckY = Math.min(desiredDeckY, Math.min(maxDeckYFromEntry, maxDeckYFromExit));
    deckY = Math.max(deckY, Math.max(entryY, exitY) + 1);

    int entryRampLen = Math.max(2, Math.min(points.size() / 3, Math.abs(deckY - entryY) + 1));
    int exitRampLen = Math.max(2, Math.min(points.size() / 3, Math.abs(deckY - exitY) + 1));
    int deckStart = Math.min(points.size() - 1, entryRampLen);
    int deckEndExclusive = Math.max(deckStart + 1, points.size() - exitRampLen);

    List<RoadCenterlinePoint> adjusted = new ArrayList<>(points.size());
    for (int index = 0; index < points.size(); index++) {
        RoadCenterlinePoint point = points.get(index);
        int y = bridgeY(index, points.size(), entryY, exitY, deckY, entryRampLen, exitRampLen);
        adjusted.add(point.withTargetY(y));
    }
    return new BridgeProfile(List.copyOf(adjusted), deckStart, deckEndExclusive);
}

private static int bridgeY(int index, int total, int entryY, int exitY, int deckY, int entryRampLen, int exitRampLen) {
    if (index < entryRampLen && entryRampLen > 1) {
        return monotonicRampY(entryY, deckY, index, entryRampLen - 1);
    }
    if (index >= total - exitRampLen && exitRampLen > 1) {
        int localIndex = total - 1 - index;
        return monotonicRampY(exitY, deckY, localIndex, exitRampLen - 1);
    }
    return deckY;
}

private static int monotonicRampY(int lowY, int highY, int localIndexFromLowEnd, int maxLocalIndex) {
    int delta = highY - lowY;
    if (delta <= 0 || maxLocalIndex <= 0) {
        return highY;
    }
    int rise = Math.min(delta, localIndexFromLowEnd);
    return lowY + rise;
}
```

- [ ] **Step 6: Run structure tests and verify they pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected result: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit Task 3**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java \
        src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
git commit -m "fix: stabilize road planner bridge ramp profiles"
```

---

### Task 4: Far bridge preview wireframe visibility

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRendererTest.java`

- [ ] **Step 1: Add failing preview renderer test**

Append this test to `RoadPlannerPreviewRendererTest` after `previewRenderListCullsByFocusDistanceAndCapsPreservingOrder()`:

```java
@Test
void wireframePreviewListKeepsFarBridgeBlocks() {
    RoadPlannerClientHooks.PreviewGhostBlock near = new RoadPlannerClientHooks.PreviewGhostBlock(
            new BlockPos(0, 64, 0),
            Blocks.STONE_BRICKS.defaultBlockState()
    );
    RoadPlannerClientHooks.PreviewGhostBlock far = new RoadPlannerClientHooks.PreviewGhostBlock(
            new BlockPos(180, 64, 0),
            Blocks.STONE_BRICKS.defaultBlockState()
    );

    List<RoadPlannerClientHooks.PreviewGhostBlock> wireframe = RoadPlannerPreviewRenderer.previewWireframeRenderListForTest(
            List.of(near, far),
            4096
    );

    assertEquals(List.of(near, far), wireframe);
}
```

- [ ] **Step 2: Run renderer tests and verify they fail**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest
```

Expected result: compilation fails because `previewWireframeRenderListForTest(...)` does not exist.

- [ ] **Step 3: Split preview rendering into model and wireframe lists**

In `RoadPlannerPreviewRenderer.java`, add this constant near the existing render constants:

```java
private static final int MAX_WIREFRAME_RENDER_BLOCKS = 4096;
```

In `onRenderLevel(...)`, replace the single `renderGhostBlocks` declaration with:

```java
List<RoadPlannerClientHooks.PreviewGhostBlock> modelGhostBlocks = getCachedRenderList(
        preview.ghostBlocks(),
        player.blockPosition(),
        MAX_PREVIEW_RENDER_DISTANCE,
        MAX_PREVIEW_RENDER_BLOCKS
);
List<RoadPlannerClientHooks.PreviewGhostBlock> wireframeGhostBlocks = previewWireframeRenderList(
        preview.ghostBlocks(),
        MAX_WIREFRAME_RENDER_BLOCKS
);
```

Then change the block model loop from:

```java
for (RoadPlannerClientHooks.PreviewGhostBlock block : renderGhostBlocks) {
```

to:

```java
for (RoadPlannerClientHooks.PreviewGhostBlock block : modelGhostBlocks) {
```

Change the line wireframe loop from:

```java
for (RoadPlannerClientHooks.PreviewGhostBlock block : renderGhostBlocks) {
```

to:

```java
for (RoadPlannerClientHooks.PreviewGhostBlock block : wireframeGhostBlocks) {
```

Inside the wireframe loop, delete this distance cull block:

```java
if (block.pos().distSqr(player.blockPosition()) > MAX_WIREFRAME_RENDER_DISTANCE_SQR) {
    continue;
}
```

- [ ] **Step 4: Add the full wireframe preview list helper**

In `RoadPlannerPreviewRenderer.java`, add this method below `previewRenderList(...)`:

```java
private static List<RoadPlannerClientHooks.PreviewGhostBlock> previewWireframeRenderList(
        List<RoadPlannerClientHooks.PreviewGhostBlock> blocks,
        int maxBlocks
) {
    return previewRenderList(blocks, null, -1.0D, maxBlocks);
}
```

Add this test helper near `previewRenderListForTest(...)`:

```java
static List<RoadPlannerClientHooks.PreviewGhostBlock> previewWireframeRenderListForTest(
        List<RoadPlannerClientHooks.PreviewGhostBlock> blocks,
        int maxBlocks
) {
    return previewWireframeRenderList(blocks, maxBlocks);
}
```

- [ ] **Step 5: Run renderer tests and verify they pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest
```

Expected result: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 4**

```bash
git add src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java \
        src/test/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRendererTest.java
git commit -m "fix: keep far road planner bridge previews visible"
```

---

### Task 5: Integration verification and jar packaging

**Files:**
- Verify all files changed in Tasks 1-4.
- Package: `build/libs/sailboatmod-1.3.7-all.jar`

- [ ] **Step 1: Run focused tests for the fixed areas**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerWaterCrossingSplitterTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpanderTest \
  --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest \
  --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacketTest \
  --tests com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest
```

Expected result: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run compile, full tests, and jarJar**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false compileJava test jarJar
```

Expected result: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Confirm all jar exists**

Run:

```bash
ls -lh build/libs/sailboatmod-1.3.7-all.jar
```

Expected result: output includes `build/libs/sailboatmod-1.3.7-all.jar` with a non-zero file size.

- [ ] **Step 4: Clean transient test/runtime files if Gradle touched them**

Run:

```bash
git checkout -- logs/debug.log logs/debug-1.log.gz logs/debug-2.log.gz logs/debug-3.log.gz logs/debug-4.log.gz logs/debug-5.log.gz logs/latest.log roadplanner_drafts_test/a1195b7c-e031-3e97-a317-119606fd0c62.draft 2>/dev/null || true
rm -f roadplanner_drafts_test/*.draft
```

Expected result: only source/test/doc files remain in `git status --short`.

- [ ] **Step 5: Commit integration verification marker only if source changed after Task 4**

Run:

```bash
git status --short
```

If Tasks 1-4 already committed all source/test changes and no source files remain unstaged, do not create an empty commit. If a source/test fix was needed during verification, commit it with:

```bash
git add src/main/java src/test/java
git commit -m "fix: finish road planner bridge preview integration"
```

Expected result: all implementation changes are committed in small task commits.

---

## Self-Review Checklist

- Spec requirement “tiny water noise ignored” is covered by Task 1 and Task 2 tests.
- Spec requirement “4–24 blocks uses RoadWeaver small bridge” is covered by Task 1, Task 2, and Task 3.
- Spec requirement “deep narrow water upgrades to major bridge” is covered by Task 1 and Task 2.
- Spec requirement “bridge ramp exists” is covered by Task 3.
- Spec requirement “far bridge preview remains visible” is covered by Task 4.
- Spec requirement “manual and auto roads share expander” is preserved because the plan keeps `RoadPlannerBuildControlService` and `RoadPlannerBuildStepCompiler` routed through `RoadNodeStructureExpander`.
- Verification and all jar packaging are covered by Task 5.
