# Road Planner Bridge Preview Actual Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Road Planner bridge preview/build geometry follow the same water-surface, deck-height, short-span, and pier-foundation rules as the actual bridge construction path.

**Architecture:** Introduce a focused `RoadPlannerBridgeGeometryPlanner` that computes bridge deck/ramp points and pier ranges from `RoadTerrainSampler` and `BridgeConfig`. `BridgeStructureEmitter` becomes a BuildStep adapter over that geometry instead of calculating its own bridge profile. `RoadTerrainSampler` exposes explicit `waterSurfaceY` so planner tests and server-backed runtime can match actual bridge rules.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, Gradle.

---

## File Map

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java`
  - Pure bridge geometry planning: actual-style deck Y, ramp/deck point phases, pier ranges.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadTerrainSampler.java`
  - Add `waterSurfaceY(int x, int z)` default and server-backed implementation.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java`
  - Replace custom bridge profile/pier math with `RoadPlannerBridgeGeometryPlanner` output.
- Modify `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`
  - Update bridge tests to verify actual-style short-span/no-pier and long-span pier floor/deck behavior.
- Create `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlannerTest.java`
  - Unit tests for deck height, short-span pier suppression, long-span pier ranges.

---

### Task 1: Add water surface and geometry planner tests

**Files:**
- Create: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlannerTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`

- [ ] **Step 1: Write failing geometry planner tests**

Create `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlannerTest.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.config.BridgeConfig;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerBridgeGeometryPlannerTest {
    @Test
    void deckHeightUsesActualWaterSurfacePlusConfigHeightWhenShoresDoNotRaiseIt() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 24, 63),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 24, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 55),
                new BridgeConfig()
        );

        assertEquals(68, plan.deckY());
    }

    @Test
    void shortSpanDoesNotPlanPiersLikeActualBridgeBuilder() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 8, 63),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 8, RoadPlannerSegmentType.BRIDGE_SMALL),
                waterSampler(63, 55),
                new BridgeConfig()
        );

        assertTrue(plan.piers().isEmpty());
        assertFalse(plan.points().isEmpty());
        assertTrue(plan.points().stream().allMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
    }

    @Test
    void longSpanPiersStartAtOceanFloorAndReachDeck() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 24, 63),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 24, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 54),
                new BridgeConfig()
        );

        assertFalse(plan.piers().isEmpty());
        assertTrue(plan.piers().stream().allMatch(pier -> pier.bottomY() == 54));
        assertTrue(plan.piers().stream().allMatch(pier -> pier.topY() == plan.deckY()));
    }

    private static List<RoadCenterlinePoint> centerline(int startX, int endX, int y) {
        java.util.ArrayList<RoadCenterlinePoint> points = new java.util.ArrayList<>();
        for (int x = startX; x <= endX; x++) {
            points.add(new RoadCenterlinePoint(
                    new BlockPos(x, y, 0),
                    0,
                    RoadPlannerSegmentType.BRIDGE_MAJOR,
                    y,
                    y,
                    x - startX
            ));
        }
        return List.copyOf(points);
    }

    private static RoadTerrainSampler waterSampler(int waterSurfaceY, int oceanFloorY) {
        return new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return waterSurfaceY;
            }

            @Override
            public int waterSurfaceY(int x, int z) {
                return waterSurfaceY;
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return oceanFloorY;
            }
        };
    }
}
```

- [ ] **Step 2: Update existing short bridge test expectation**

In `RoadNodeStructureExpanderTest.shortSmallBridgeStillEmitsRampAndDeck`, rename it to `shortSmallBridgeSkipsPiersLikeActualBridgeBuilder` and replace the assertions with:

```java
assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
assertFalse(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER));
```

- [ ] **Step 3: Run tests and verify failure**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.roadplanner.structure.RoadPlannerBridgeGeometryPlannerTest \
  --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: compilation fails because `RoadPlannerBridgeGeometryPlanner` and `RoadTerrainSampler.waterSurfaceY(...)` do not exist.

- [ ] **Step 4: Commit tests after they are red**

Do not commit yet if production code is still missing; proceed to Task 2.

---

### Task 2: Add actual-style bridge geometry planner

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadTerrainSampler.java`

- [ ] **Step 1: Add `waterSurfaceY` to `RoadTerrainSampler`**

Modify `RoadTerrainSampler` so it includes:

```java
default int waterSurfaceY(int x, int z) {
    return terrainY(x, z) - 1;
}
```

And in `fromLevel(ServerLevel level)` override it with:

```java
@Override
public int waterSurfaceY(int x, int z) {
    return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
}
```

- [ ] **Step 2: Add the bridge geometry planner**

Create `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerBridgeGeometryPlanner {
    private static final int SHORT_SPAN_WITHOUT_PIERS_LIMIT = 8;
    private static final int SEA_LEVEL = 63;

    private RoadPlannerBridgeGeometryPlanner() {
    }

    public static Plan plan(List<RoadCenterlinePoint> points,
                            RoadSpan span,
                            RoadTerrainSampler terrainSampler,
                            BridgeConfig config) {
        if (points == null || points.isEmpty()) {
            return new Plan(List.of(), List.of(), SEA_LEVEL + safeConfig(config).getDeckHeight());
        }
        BridgeConfig safeConfig = safeConfig(config);
        RoadTerrainSampler safeSampler = terrainSampler == null ? RoadTerrainSampler.flat(points.get(0).terrainY()) : terrainSampler;
        int waterSurfaceY = bridgeWaterSurfaceY(points, safeSampler);
        int waterY = Math.max(waterSurfaceY, SEA_LEVEL);
        int entryY = Math.max(points.get(0).targetY(), waterY);
        int exitY = Math.max(points.get(points.size() - 1).targetY(), waterY);
        int deckY = deckYForActualBridge(waterY, entryY, exitY, safeConfig);
        int spanLength = span == null ? points.size() - 1 : span.endIndex() - span.startIndex();
        boolean shortSpan = spanLength <= SHORT_SPAN_WITHOUT_PIERS_LIMIT;

        List<PlannedPoint> plannedPoints = shortSpan
                ? flatDeck(points, deckY)
                : rampedDeck(points, entryY, exitY, deckY);
        List<Pier> piers = shortSpan ? List.of() : piers(points, plannedPoints, deckY, safeSampler, safeConfig);
        return new Plan(plannedPoints, piers, deckY);
    }

    private static BridgeConfig safeConfig(BridgeConfig config) {
        return config == null ? new BridgeConfig() : config;
    }

    private static int bridgeWaterSurfaceY(List<RoadCenterlinePoint> points, RoadTerrainSampler sampler) {
        int max = Integer.MIN_VALUE;
        for (RoadCenterlinePoint point : points) {
            max = Math.max(max, sampler.waterSurfaceY(point.pos().getX(), point.pos().getZ()));
        }
        return max == Integer.MIN_VALUE ? SEA_LEVEL : max;
    }

    private static int deckYForActualBridge(int waterY, int entryY, int exitY, BridgeConfig config) {
        int maxRampHeight = config.getDeckHeight();
        int deckY = Math.max(waterY + config.getDeckHeight(), Math.max(entryY + 3, exitY + 3));
        deckY = Math.min(deckY, Math.min(entryY, exitY) + maxRampHeight);
        return Math.max(deckY, waterY + config.getDeckHeight());
    }

    private static List<PlannedPoint> flatDeck(List<RoadCenterlinePoint> points, int deckY) {
        List<PlannedPoint> result = new ArrayList<>(points.size());
        for (RoadCenterlinePoint point : points) {
            result.add(new PlannedPoint(point.withTargetY(deckY), BuildPhase.DECK));
        }
        return List.copyOf(result);
    }

    private static List<PlannedPoint> rampedDeck(List<RoadCenterlinePoint> points, int entryY, int exitY, int deckY) {
        int total = points.size();
        int ascHeight = Math.max(0, deckY - entryY);
        int descHeight = Math.max(0, deckY - exitY);
        int ascLen = Math.min(Math.max(1, ascHeight * 2), Math.max(1, total / 3));
        int descLen = Math.min(Math.max(1, descHeight * 2), Math.max(1, total / 3));
        int deckStart = Math.min(total - 1, ascLen);
        int deckEndExclusive = Math.max(deckStart + 1, total - descLen);
        List<PlannedPoint> result = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int y;
            BuildPhase phase;
            if (index < deckStart) {
                y = rampY(entryY, deckY, index, Math.max(1, deckStart));
                phase = BuildPhase.RAMP;
            } else if (index >= deckEndExclusive) {
                int local = total - 1 - index;
                y = rampY(exitY, deckY, local, Math.max(1, total - deckEndExclusive));
                phase = BuildPhase.RAMP;
            } else {
                y = deckY;
                phase = BuildPhase.DECK;
            }
            result.add(new PlannedPoint(points.get(index).withTargetY(y), phase));
        }
        return List.copyOf(result);
    }

    private static int rampY(int shoreY, int deckY, int localIndex, int rampLen) {
        if (deckY <= shoreY || rampLen <= 0) {
            return deckY;
        }
        double t = Math.min(1.0D, Math.max(0.0D, localIndex / (double) rampLen));
        return (int) Math.round(shoreY + (deckY - shoreY) * t);
    }

    private static List<Pier> piers(List<RoadCenterlinePoint> sourcePoints,
                                    List<PlannedPoint> plannedPoints,
                                    int deckY,
                                    RoadTerrainSampler sampler,
                                    BridgeConfig config) {
        int interval = Math.max(5, config.getPierInterval());
        List<Pier> result = new ArrayList<>();
        for (int index = 0; index < plannedPoints.size(); index += interval) {
            addPier(result, sourcePoints.get(index), deckY, sampler);
        }
        if (!plannedPoints.isEmpty()) {
            addPier(result, sourcePoints.get(sourcePoints.size() - 1), deckY, sampler);
        }
        return List.copyOf(result);
    }

    private static void addPier(List<Pier> piers, RoadCenterlinePoint sourcePoint, int deckY, RoadTerrainSampler sampler) {
        int x = sourcePoint.pos().getX();
        int z = sourcePoint.pos().getZ();
        int bottomY = sampler.oceanFloorY(x, z);
        BlockPos center = new BlockPos(x, deckY, z);
        Pier pier = new Pier(center, bottomY, deckY);
        if (piers.stream().noneMatch(existing -> existing.center().getX() == x && existing.center().getZ() == z)) {
            piers.add(pier);
        }
    }

    public record PlannedPoint(RoadCenterlinePoint point, BuildPhase phase) {
    }

    public record Pier(BlockPos center, int bottomY, int topY) {
    }

    public record Plan(List<PlannedPoint> points, List<Pier> piers, int deckY) {
        public Plan {
            points = points == null ? List.of() : List.copyOf(points);
            piers = piers == null ? List.of() : List.copyOf(piers);
        }
    }
}
```

- [ ] **Step 3: Run geometry planner tests and verify pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadPlannerBridgeGeometryPlannerTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit Task 2**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java \
        src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadTerrainSampler.java \
        src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlannerTest.java \
        src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
git commit -m "fix: add actual bridge geometry planner"
```

---

### Task 3: Use actual-style geometry in Road Planner bridge emitter

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpander.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`

- [ ] **Step 1: Add failing expander test for long-span pier floor**

Append this test to `RoadNodeStructureExpanderTest` before `blockedBridgeMarkerBuildsAsMajorBridge()`:

```java
@Test
void longBridgePiersUseOceanFloorAndActualDeckHeight() {
    RoadTerrainSampler waterSampler = new RoadTerrainSampler() {
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
            List.of(new BlockPos(0, 63, 0), new BlockPos(24, 63, 0)),
            List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
            RoadPlannerBuildSettings.DEFAULTS,
            waterSampler,
            RoadStructureMode.BUILD
    );

    List<BlockPos> piers = result.buildSteps().stream()
            .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER)
            .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
            .toList();

    assertFalse(piers.isEmpty());
    assertEquals(54, piers.stream().mapToInt(BlockPos::getY).min().orElseThrow());
    assertEquals(68, piers.stream().mapToInt(BlockPos::getY).max().orElseThrow());
}
```

- [ ] **Step 2: Run structure tests and verify failure**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: test fails because `BridgeStructureEmitter` still uses `point.terrainY()` for pier bottom and does not receive `RoadTerrainSampler`.

- [ ] **Step 3: Pass terrain sampler into bridge emitter**

In `RoadNodeStructureExpander.expand(...)`, replace:

```java
steps.addAll(BridgeStructureEmitter.emit(allCenterline, allSpans, settings, BridgeTemplateProvider.empty(), steps.size()));
```

with:

```java
steps.addAll(BridgeStructureEmitter.emit(allCenterline, allSpans, settings, BridgeTemplateProvider.empty(), steps.size(), terrainSampler));
```

- [ ] **Step 4: Add overloaded bridge emitter signature**

In `BridgeStructureEmitter`, keep the current public signature as a delegating overload:

```java
public static List<BuildStep> emit(List<RoadCenterlinePoint> centerline,
                                   List<RoadSpan> spans,
                                   RoadPlannerBuildSettings settings,
                                   BridgeTemplateProvider templateProvider,
                                   int startOrder) {
    return emit(centerline, spans, settings, templateProvider, startOrder, null);
}
```

Add the new overload:

```java
public static List<BuildStep> emit(List<RoadCenterlinePoint> centerline,
                                   List<RoadSpan> spans,
                                   RoadPlannerBuildSettings settings,
                                   BridgeTemplateProvider templateProvider,
                                   int startOrder,
                                   RoadTerrainSampler terrainSampler) {
    // move the existing method body here and pass terrainSampler into emitProgrammaticBridge
}
```

- [ ] **Step 5: Replace custom profile usage with geometry planner output**

In `emitProgrammaticBridge(...)`, replace `buildBridgeProfile(...)` and custom pier loop with `RoadPlannerBridgeGeometryPlanner.plan(...)`:

```java
RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
        points,
        span,
        terrainSampler,
        new com.monpai.sailboatmod.road.config.BridgeConfig()
);
List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints = plan.points();
```

For each planned point:

```java
RoadPlannerBridgeGeometryPlanner.PlannedPoint planned = plannedPoints.get(index);
RoadCenterlinePoint point = planned.point();
BuildPhase phase = planned.phase();
boolean ramp = phase == BuildPhase.RAMP;
BlockState state = ramp ? rampState(settings, index) : settings.surfaceState();
List<RoadCenterlinePoint> footprintProfile = plannedPoints.stream()
        .map(RoadPlannerBridgeGeometryPlanner.PlannedPoint::point)
        .toList();
```

Emit piers after deck/ramp/railings:

```java
for (RoadPlannerBridgeGeometryPlanner.Pier pier : plan.piers()) {
    for (int pierY = pier.bottomY(); pierY <= pier.topY(); pierY++) {
        steps.add(new BuildStep(order++, new BlockPos(pier.center().getX(), pierY, pier.center().getZ()), Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.PIER));
    }
}
```

Delete the old private `BridgeProfile`, `buildBridgeProfile`, `bridgeY`, and `monotonicRampY` helpers once no longer referenced.

- [ ] **Step 6: Run structure tests and verify pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit Task 3**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpander.java \
        src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java \
        src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
git commit -m "fix: align road planner bridge emitter with actual geometry"
```

---

### Task 4: Verification and all jar packaging

**Files:**
- Verify source/test changes.
- Package: `build/libs/sailboatmod-1.3.7-all.jar`

- [ ] **Step 1: Run focused bridge tests**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.roadplanner.structure.RoadPlannerBridgeGeometryPlannerTest \
  --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpanderTest \
  --tests com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run compile, tests, and jarJar**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false compileJava test jarJar
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Confirm all jar exists**

Run:

```bash
ls -lh build/libs/sailboatmod-1.3.7-all.jar
```

Expected: output includes non-zero `build/libs/sailboatmod-1.3.7-all.jar`.

- [ ] **Step 4: Restore transient runtime files only**

Run:

```bash
git checkout -- logs/debug.log logs/debug-1.log.gz logs/debug-2.log.gz logs/debug-3.log.gz logs/debug-4.log.gz logs/debug-5.log.gz logs/latest.log roadplanner_drafts_test 2>/dev/null || true
```

Expected: only the known pre-existing source/test changes remain in `git status --short`.

---

## Self-Review Checklist

- Actual deck height from water surface + `BridgeConfig.deckHeight` is implemented in Task 2.
- Short-span pier suppression is implemented in Task 2 and verified in Task 1/3 tests.
- Long-span pier foundation uses `oceanFloorY` in Task 2/3.
- Road Planner preview/build still share `RoadNodeStructureExpander`.
- Full jar packaging is covered by Task 4.
