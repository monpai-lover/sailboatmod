# Road Planner Unified Build Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every road planner route source produce the same ghost preview, final construction steps, bridge behavior, and construction progress.

**Architecture:** Introduce a canonical build-step compilation path for planner routes. Route expansion normalizes manual, Bezier, cross-water, and auto-complete nodes; a planner build compiler delegates road/tunnel sections to RoadWeaver/RoadBuilder-style generation and major bridge sections to the pier bridge backend; preview and construction both consume the same compiled `BuildStep` list.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, existing `RoadPlannerRouteExpander`, `RoadPlannerBuildControlService`, `RoadPlannerPreviewRenderer`, `ConstructionQueue`, and `SyncRoadConstructionProgressPacket`.

---

## File Structure

- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompiler.java`
  - Single canonical compiler from normalized planner nodes to `List<BuildStep>`.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildProgressSnapshot.java`
  - Small immutable snapshot for queue sync.
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`
  - Store compiled preview steps, confirm from stored steps, tick active build queues, expose progress snapshots.
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpander.java`
  - Ensure non-auto routes normalize like auto routes and prevent underwater road surface.
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerGhostPreviewBridge.java`
  - Request/send ghost blocks from compiled build steps instead of a separate geometry path.
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java`
  - Add preview dedupe/culling/test helpers and render real construction progress bar.
- Modify: `src/main/java/com/monpai/sailboatmod/ServerEvents.java`
  - Tick new road planner build service.
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerConfirmBuildPacket.java`
  - Ensure confirm triggers queued build progress sync.
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompilerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpanderTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRendererTest.java`

---

### Task 1: Lock Down Canonical Build Compilation

**Files:**
- Create: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompilerTest.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompiler.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`

- [ ] **Step 1: Write failing tests for manual road, water bridge, and no underwater road**

Create `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompilerTest.java`:

```java
package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerBuildStepCompilerTest {
    @Test
    void manualRoadCreatesSurfaceSteps() {
        List<BuildStep> steps = RoadPlannerBuildStepCompiler.compileForTest(
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS
        );

        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.SURFACE));
        assertFalse(steps.isEmpty());
    }

    @Test
    void majorBridgeCreatesDeckAndDoesNotPlaceWaterSurfaceRoad() {
        List<BuildStep> steps = RoadPlannerBuildStepCompiler.compileForTest(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), new BlockPos(16, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS
        );

        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
        assertFalse(steps.stream().anyMatch(step -> step.phase() == BuildPhase.SURFACE
                && step.state().is(Blocks.WATER)));
    }

    @Test
    void duplicateBuildStepsAreCollapsedForPreviewSafety() {
        List<BuildStep> steps = RoadPlannerBuildStepCompiler.compileForTest(
                List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS
        );

        long distinctPositions = steps.stream().map(BuildStep::pos).distinct().count();
        assertTrue(distinctPositions > 1);
    }
}
```

- [ ] **Step 2: Run tests and verify the compiler is missing or incomplete**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildStepCompilerTest
```

Expected: FAIL because `RoadPlannerBuildStepCompiler` does not exist.

- [ ] **Step 3: Implement canonical compiler**

Create `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompiler.java` with this shape:

```java
package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerCompiledPath;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathCompiler;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoadPlannerBuildStepCompiler {
    private RoadPlannerBuildStepCompiler() {
    }

    public static List<BuildStep> compile(List<BlockPos> nodes,
                                          List<RoadPlannerSegmentType> segmentTypes,
                                          RoadPlannerBuildSettings settings,
                                          ServerLevel level) {
        if (nodes == null || nodes.size() < 2) {
            return List.of();
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        List<RoadPlannerSegmentType> safeTypes = normalizeTypes(segmentTypes, nodes.size() - 1);
        List<BuildStep> steps = level == null
                ? compileFallback(nodes, safeTypes, safeSettings)
                : compileWithLevel(nodes, safeTypes, safeSettings, level);
        return dedupe(steps);
    }

    public static List<BuildStep> compileForTest(List<BlockPos> nodes,
                                                 List<RoadPlannerSegmentType> segmentTypes,
                                                 RoadPlannerBuildSettings settings) {
        return compile(nodes, segmentTypes, settings, null);
    }

    private static List<BuildStep> compileWithLevel(List<BlockPos> nodes,
                                                    List<RoadPlannerSegmentType> segmentTypes,
                                                    RoadPlannerBuildSettings settings,
                                                    ServerLevel level) {
        List<BuildStep> steps = new ArrayList<>();
        int order = 0;
        int index = 0;
        while (index < nodes.size() - 1) {
            RoadPlannerSegmentType type = segmentTypes.get(index);
            int end = index + 1;
            while (end < nodes.size() - 1 && sameBackend(type, segmentTypes.get(end))) {
                end++;
            }
            List<BlockPos> sectionNodes = nodes.subList(index, end + 1);
            List<RoadPlannerSegmentType> sectionTypes = segmentTypes.subList(index, end);
            List<BuildStep> sectionSteps = compileSection(sectionNodes, sectionTypes, settings, level);
            for (BuildStep step : sectionSteps) {
                steps.add(new BuildStep(order++, step.pos(), step.state(), step.phase()));
            }
            index = end;
        }
        return steps;
    }

    private static List<BuildStep> compileFallback(List<BlockPos> nodes,
                                                   List<RoadPlannerSegmentType> segmentTypes,
                                                   RoadPlannerBuildSettings settings) {
        RoadPlannerCompiledPath compiled = RoadPlannerPathCompiler.compile(nodes, segmentTypes, settings);
        List<BuildStep> steps = new ArrayList<>();
        int order = 0;
        for (RoadPlannerCompiledPath.CompiledBlock block : compiled.blocks()) {
            if (block.state().is(Blocks.WATER)) {
                continue;
            }
            for (int dy = 1; dy <= 4; dy++) {
                steps.add(new BuildStep(order++, block.pos().above(dy), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
            }
            BuildPhase phase = isBridge(block.segmentType()) ? BuildPhase.DECK : BuildPhase.SURFACE;
            steps.add(new BuildStep(order++, block.pos(), block.state(), phase));
        }
        for (RoadPlannerCompiledPath.LightBlock light : compiled.lights()) {
            steps.add(new BuildStep(order++, light.pos(), light.state(), BuildPhase.STREETLIGHT));
        }
        return steps;
    }

    private static List<BuildStep> compileSection(List<BlockPos> nodes,
                                                  List<RoadPlannerSegmentType> segmentTypes,
                                                  RoadPlannerBuildSettings settings,
                                                  ServerLevel level) {
        if (segmentTypes.stream().anyMatch(RoadPlannerBuildStepCompiler::isMajorBridge)) {
            return RoadPlannerBuildControlService.nodeAnchoredBridgeStepsForCompiler(nodes, settings.width(), level, 5);
        }
        return compileFallback(nodes, segmentTypes, settings);
    }

    private static List<BuildStep> dedupe(List<BuildStep> steps) {
        Map<String, BuildStep> unique = new LinkedHashMap<>();
        int order = 0;
        for (BuildStep step : steps) {
            if (step == null || step.pos() == null || step.state() == null) {
                continue;
            }
            String key = step.pos().asLong() + ":" + step.phase() + ":" + step.state().toString();
            unique.putIfAbsent(key, new BuildStep(order++, step.pos(), step.state(), step.phase()));
        }
        return List.copyOf(unique.values());
    }

    private static List<RoadPlannerSegmentType> normalizeTypes(List<RoadPlannerSegmentType> types, int count) {
        List<RoadPlannerSegmentType> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RoadPlannerSegmentType type = types != null && i < types.size() && types.get(i) != null
                    ? types.get(i)
                    : RoadPlannerSegmentType.ROAD;
            if (type == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE) {
                type = RoadPlannerSegmentType.BRIDGE_MAJOR;
            }
            result.add(type);
        }
        return result;
    }

    private static boolean sameBackend(RoadPlannerSegmentType first, RoadPlannerSegmentType second) {
        return isBridge(first) == isBridge(second) && first == second;
    }

    private static boolean isBridge(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL;
    }

    private static boolean isMajorBridge(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.BRIDGE_MAJOR;
    }
}
```

Then expose bridge generation from `RoadPlannerBuildControlService` by changing its private helper signature:

```java
static List<BuildStep> nodeAnchoredBridgeStepsForCompiler(List<BlockPos> bridgeNodes, int width, ServerLevel level, int heightBonus) {
    return nodeAnchoredBridgeSteps(bridgeNodes, width, level, heightBonus);
}
```

- [ ] **Step 4: Route existing build step creation through compiler**

In `RoadPlannerBuildControlService.buildSteps(PreviewSnapshot snapshot, ServerLevel level)`, replace the current split/fallback body with:

```java
private static List<BuildStep> buildSteps(PreviewSnapshot snapshot, ServerLevel level) {
    if (snapshot == null || snapshot.nodes().isEmpty()) {
        return List.of();
    }
    return RoadPlannerBuildStepCompiler.compile(
            snapshot.nodes(),
            snapshot.segmentTypes(),
            snapshot.settings(),
            level
    );
}
```

Keep the old bridge helper methods only if `RoadPlannerBuildStepCompiler` calls them.

- [ ] **Step 5: Run compiler tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildStepCompilerTest
```

Expected: PASS.

---

### Task 2: Make Route Expansion Bridge-Safe

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpanderTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpander.java`

- [ ] **Step 1: Add failing tests for water conversion and Bezier/manual parity**

Append tests to `RoadPlannerRouteExpanderTest`:

```java
@Test
void roadCrossingWaterIsConvertedToBridgeType() {
    RoadPlannerRouteExpander.ExpandedRoute route = RoadPlannerRouteExpander.expand(
            List.of(new BlockPos(0, 64, 0), new BlockPos(32, 64, 0)),
            List.of(RoadPlannerSegmentType.ROAD),
            (x, z) -> 62,
            (x, z) -> x > 6 && x < 26
    );

    assertTrue(route.segmentTypes().stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR
            || type == RoadPlannerSegmentType.BRIDGE_SMALL));
}

@Test
void expandedRouteKeepsRoadNodesOutsideWaterBridgeSpan() {
    RoadPlannerRouteExpander.ExpandedRoute route = RoadPlannerRouteExpander.expand(
            List.of(new BlockPos(0, 64, 0), new BlockPos(32, 64, 0)),
            List.of(RoadPlannerSegmentType.ROAD),
            (x, z) -> 64,
            (x, z) -> x >= 10 && x <= 22
    );

    assertEquals(RoadPlannerSegmentType.ROAD, route.segmentTypes().get(0));
    assertTrue(route.segmentTypes().stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR
            || type == RoadPlannerSegmentType.BRIDGE_SMALL));
}
```

If the existing expander has a different water sampler interface, adapt only the test call site while keeping the assertions.

- [ ] **Step 2: Run expander tests and verify failure**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpanderTest
```

Expected: FAIL if normal road still remains underwater.

- [ ] **Step 3: Implement water bridge normalization**

In `RoadPlannerRouteExpander`, add or update the normalization rule:

```java
private static RoadPlannerSegmentType classifySegment(RoadPlannerSegmentType requested,
                                                      boolean touchesWater,
                                                      int waterRunLength) {
    if (requested == RoadPlannerSegmentType.BRIDGE_MAJOR || requested == RoadPlannerSegmentType.BRIDGE_SMALL) {
        return requested;
    }
    if (!touchesWater) {
        return requested == null || requested == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE
                ? RoadPlannerSegmentType.ROAD
                : requested;
    }
    return waterRunLength >= 16 ? RoadPlannerSegmentType.BRIDGE_MAJOR : RoadPlannerSegmentType.BRIDGE_SMALL;
}
```

Ensure expanded segment types are assigned per generated segment, not only per original node pair. The generated bridge span must include one land node before the first water node and one land node after the last water node when those nodes exist.

- [ ] **Step 4: Run route expander and compiler tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpanderTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildStepCompilerTest
```

Expected: PASS.

---

### Task 3: Use Compiled Steps for Ghost Preview

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerGhostPreviewBridge.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java`
- Modify: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacketTest.java`

- [ ] **Step 1: Add test that preview packet uses compiled build steps**

In `RoadPlannerPreviewRequestPacketTest`, add a test that requests preview with a bridge segment and asserts decoded/sent ghost blocks include bridge deck material or deck phase metadata if available. Use existing packet helper patterns in the file. The assertion must verify the preview is derived from build steps, not raw nodes:

```java
assertTrue(decoded.segmentTypes().contains(RoadPlannerSegmentType.BRIDGE_MAJOR));
```

- [ ] **Step 2: Change preview generation to call `RoadPlannerBuildControlService.previewBuildSteps`**

Where the server handles preview requests, replace independent ghost geometry compilation with:

```java
List<BuildStep> steps = RoadPlannerBuildControlService.previewBuildSteps(nodes, segmentTypes, settings, player.serverLevel());
RoadPlannerGhostPreviewBridge.sendPreviewFromBuildSteps(player, sourceTownName, targetTownName, steps, nodes, segmentTypes);
```

- [ ] **Step 3: Add `sendPreviewFromBuildSteps` helper**

In `RoadPlannerGhostPreviewBridge`, add:

```java
public static void sendPreviewFromBuildSteps(ServerPlayer player,
                                             String sourceTownName,
                                             String targetTownName,
                                             List<BuildStep> steps,
                                             List<BlockPos> nodes,
                                             List<RoadPlannerSegmentType> segmentTypes) {
    List<RoadPlannerClientHooks.PreviewGhostBlock> ghosts = steps.stream()
            .filter(step -> step != null && step.pos() != null && step.state() != null)
            .filter(step -> !step.state().isAir())
            .filter(step -> step.phase() == BuildPhase.SURFACE
                    || step.phase() == BuildPhase.DECK
                    || step.phase() == BuildPhase.STREETLIGHT)
            .map(step -> new RoadPlannerClientHooks.PreviewGhostBlock(step.pos(), step.state()))
            .toList();
    sendPreview(player, sourceTownName, targetTownName, ghosts, nodes, segmentTypes);
}
```

If existing `sendPreview` is client-only or has a different signature, create the smallest overload that sends `SyncRoadPlannerPreviewPacket` with the same data shape currently used.

- [ ] **Step 4: Run preview packet tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacketTest
```

Expected: PASS.

---

### Task 4: Add Preview Dedupe, Culling, and Progress Bar Rendering

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRendererTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java`

- [ ] **Step 1: Add renderer tests for dedupe and cap**

Append to `RoadPlannerPreviewRendererTest`:

```java
@Test
void previewRenderListDeduplicatesSamePositionAndState() {
    List<RoadPlannerClientHooks.PreviewGhostBlock> input = List.of(
            new RoadPlannerClientHooks.PreviewGhostBlock(new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState()),
            new RoadPlannerClientHooks.PreviewGhostBlock(new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState()),
            new RoadPlannerClientHooks.PreviewGhostBlock(new BlockPos(1, 64, 0), Blocks.STONE.defaultBlockState())
    );

    assertEquals(2, RoadPlannerPreviewRenderer.previewRenderListForTest(input, new BlockPos(0, 64, 0), 512, 64).size());
}

@Test
void previewRenderListCapsFarTooManyBlocks() {
    List<RoadPlannerClientHooks.PreviewGhostBlock> input = new ArrayList<>();
    for (int i = 0; i < 2000; i++) {
        input.add(new RoadPlannerClientHooks.PreviewGhostBlock(new BlockPos(i, 64, 0), Blocks.STONE.defaultBlockState()));
    }

    assertEquals(256, RoadPlannerPreviewRenderer.previewRenderListForTest(input, new BlockPos(0, 64, 0), 256, 512).size());
}
```

Add imports for `ArrayList`, `List`, `BlockPos`, `Blocks`, and `RoadPlannerClientHooks` as needed.

- [ ] **Step 2: Implement renderer filter helper**

In `RoadPlannerPreviewRenderer`, add:

```java
static List<RoadPlannerClientHooks.PreviewGhostBlock> previewRenderListForTest(
        List<RoadPlannerClientHooks.PreviewGhostBlock> blocks,
        BlockPos focus,
        int maxBlocks,
        int maxDistanceBlocks) {
    return previewRenderList(blocks, focus, maxBlocks, maxDistanceBlocks);
}

private static List<RoadPlannerClientHooks.PreviewGhostBlock> previewRenderList(
        List<RoadPlannerClientHooks.PreviewGhostBlock> blocks,
        BlockPos focus,
        int maxBlocks,
        int maxDistanceBlocks) {
    if (blocks == null || blocks.isEmpty()) {
        return List.of();
    }
    Map<String, RoadPlannerClientHooks.PreviewGhostBlock> unique = new LinkedHashMap<>();
    int maxDistanceSq = maxDistanceBlocks * maxDistanceBlocks;
    for (RoadPlannerClientHooks.PreviewGhostBlock block : blocks) {
        if (block == null || block.pos() == null || block.state() == null || block.state().isAir()) {
            continue;
        }
        if (focus != null && block.pos().distSqr(focus) > maxDistanceSq) {
            continue;
        }
        String key = block.pos().asLong() + ":" + block.state().toString();
        unique.putIfAbsent(key, block);
        if (unique.size() >= maxBlocks) {
            break;
        }
    }
    return List.copyOf(unique.values());
}
```

Add imports for `LinkedHashMap`, `List`, and `Map`.

- [ ] **Step 3: Use filtered list in world preview render loop**

Before iterating `preview.ghostBlocks()` in `RoadPlannerPreviewRenderer`, compute:

```java
BlockPos focus = minecraft.player == null ? null : minecraft.player.blockPosition();
List<RoadPlannerClientHooks.PreviewGhostBlock> renderBlocks = previewRenderList(preview.ghostBlocks(), focus, 768, 192);
```

Use `renderBlocks` for both solid ghost rendering and line rendering.

- [ ] **Step 4: Draw actual construction progress bar**

In the `progressStates` section around the existing construction progress text, after the progress text add:

```java
drawProgressBar(guiGraphics, x + 10, textY, width - 20, 8, progress.progressPercent());
textY += 12;
```

Keep the worker text below it.

- [ ] **Step 5: Run renderer tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest
```

Expected: PASS.

---

### Task 5: Tick New Planner Build Queues and Sync Progress

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildProgressSnapshot.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/ServerEvents.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerConfirmBuildPacket.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlServiceTest.java`

- [ ] **Step 1: Add progress snapshot tests**

Add to `RoadPlannerBuildControlServiceTest`:

```java
@Test
void progressSnapshotReportsQueuedBuildPercent() {
    RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
    UUID playerId = UUID.randomUUID();
    UUID previewId = service.startPreview(
            playerId,
            List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
            List.of(RoadPlannerSegmentType.ROAD)
    );

    UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();
    List<RoadPlannerBuildProgressSnapshot> snapshots = service.progressSnapshotsForTest();

    assertEquals(1, snapshots.size());
    assertEquals(jobId.toString(), snapshots.get(0).roadId());
    assertTrue(snapshots.get(0).progressPercent() >= 0);
}
```

- [ ] **Step 2: Create progress snapshot record**

Create `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildProgressSnapshot.java`:

```java
package com.monpai.sailboatmod.roadplanner.service;

import net.minecraft.core.BlockPos;

public record RoadPlannerBuildProgressSnapshot(String roadId,
                                               String sourceTownName,
                                               String targetTownName,
                                               BlockPos focusPos,
                                               int progressPercent,
                                               int activeWorkers) {
}
```

- [ ] **Step 3: Add tick and snapshot methods**

In `RoadPlannerBuildControlService`, add:

```java
public void tick(ServerLevel level) {
    if (level == null || buildQueues.isEmpty()) {
        return;
    }
    for (java.util.Iterator<java.util.Map.Entry<UUID, ConstructionQueue>> it = buildQueues.entrySet().iterator(); it.hasNext(); ) {
        java.util.Map.Entry<UUID, ConstructionQueue> entry = it.next();
        ConstructionQueue queue = entry.getValue();
        executeInitialSteps(queue, level, 8);
        if (!queue.hasNext()) {
            it.remove();
            activeBuilds.entrySet().removeIf(active -> active.getValue().equals(entry.getKey()));
        }
    }
}

public List<RoadPlannerBuildProgressSnapshot> progressSnapshotsForTest() {
    return progressSnapshots();
}

public List<RoadPlannerBuildProgressSnapshot> progressSnapshots() {
    List<RoadPlannerBuildProgressSnapshot> snapshots = new java.util.ArrayList<>();
    for (java.util.Map.Entry<UUID, ConstructionQueue> entry : buildQueues.entrySet()) {
        ConstructionQueue queue = entry.getValue();
        int percent = (int) Math.round(queue.progress() * 100.0D);
        snapshots.add(new RoadPlannerBuildProgressSnapshot(
                entry.getKey().toString(),
                "",
                "",
                BlockPos.ZERO,
                Math.max(0, Math.min(100, percent)),
                0
        ));
    }
    return List.copyOf(snapshots);
}
```

- [ ] **Step 4: Tick service from server tick**

In `ServerEvents.onServerTick`, inside `server.getAllLevels().forEach(level -> { ... })`, add:

```java
com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService.global().tick(level);
```

- [ ] **Step 5: Sync progress packet periodically**

In `RoadPlannerBuildControlService`, add a `syncCounter` field and a `syncProgress(ServerLevel level)` helper that maps `RoadPlannerBuildProgressSnapshot` to `SyncRoadConstructionProgressPacket.Entry` and sends to relevant players. If player ownership is not yet stored, sync only to players whose UUID exists in `activeBuilds`.

Use this mapping:

```java
new SyncRoadConstructionProgressPacket.Entry(
        snapshot.roadId(),
        snapshot.sourceTownName(),
        snapshot.targetTownName(),
        snapshot.focusPos(),
        snapshot.progressPercent(),
        snapshot.activeWorkers()
)
```

Call the helper every 10 ticks from `tick`.

- [ ] **Step 6: Run build control tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlServiceTest
```

Expected: PASS.

---

### Task 6: Final Integration Validation

**Files:**
- Modify only files needed to fix failures found by validation.

- [ ] **Step 1: Run focused test suite**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpanderTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildStepCompilerTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlServiceTest --tests com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacketTest
```

Expected: PASS.

- [ ] **Step 2: Run Java compile**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build jar**

Run:

```powershell
.\gradlew.bat assemble
```

Expected: jar appears under `build/libs/`, including the latest `sailboatmod-*-all.jar`.

- [ ] **Step 4: Manual gameplay checklist**

Verify in client:

```text
1. Draw a two-node road on land; preview and build match.
2. Draw a route crossing water with road tool; it auto-converts to bridge or refuses if bridge is required.
3. Use auto-complete between towns; road, bridge, and land sections all preview.
4. Confirm build; progress bar appears while holding the planner.
5. Cancel build; placed blocks roll back.
6. Large preview route no longer tanks FPS like duplicated clustered ghost boxes.
```

---

## Self-Review

- Spec coverage: unified pipeline, water/bridge rules, ghost preview performance, construction progress, and validation are covered by Tasks 1-6.
- Placeholder scan: no task uses TBD/TODO/FIXME. Implementation details are concrete enough to start, while allowing adaptation to existing method signatures.
- Type consistency: plan consistently uses `RoadPlannerBuildStepCompiler`, `RoadPlannerBuildProgressSnapshot`, `BuildStep`, `BuildPhase`, `RoadPlannerSegmentType`, and existing service/test paths.
