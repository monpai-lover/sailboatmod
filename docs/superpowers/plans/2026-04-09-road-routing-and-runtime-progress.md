# Road Routing And Runtime Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make manual roads anchor to town-edge land, avoid buildings, use only short supplemental bridges, and show active road construction progress to nearby authorized players through the runtime preview flow.

**Architecture:** Tighten road planning in `ManualRoadPlannerService` and `RoadPathfinder` by moving from soft terrain preferences to explicit anchor validation, building obstruction checks, and bounded bridge traversal. Align road progress sync and client overlay behavior with the runtime ghost preview audience so the same nearby authorized players see both the road ghost and the matching progress state.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, existing mod networking/render hooks.

---

## File Map

- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
  - Add constrained land-first routing, bounded bridge support, and obstruction-aware shortcut validation.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
  - Tighten town anchor selection toward boundary land nodes and route failure behavior.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - Expand runtime road progress audience and align it with runtime ghost preview visibility.
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/ConstructionGhostPreviewRenderer.java`
  - Render nearby runtime road progress text under the same conditions as the runtime road ghost.
- Modify: `src/main/java/com/monpai/sailboatmod/client/ConstructionGhostClientHooks.java`
  - Expose nearby road preview/progress targeting data if the renderer needs it.
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/SyncRoadConstructionProgressPacket.java`
  - Keep the widened id-length limits in place and ensure the updated jar is the one being shipped.
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderConstraintTest.java`
  - Add focused pathfinder regression tests for bridge limits and obstruction handling.
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/RoadRuntimeProgressAudienceTest.java`
  - Add focused audience/progress visibility tests.

### Task 1: Lock In Regressions Around Current Failures

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/network/packet/ConstructionPacketStringLimitTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/RoadRuntimeProgressAudienceTest.java`

- [ ] **Step 1: Write the failing audience test**

```java
package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadRuntimeProgressAudienceTest {
    @Test
    void allowsNearbyManagerHoldingRoadTool() {
        assertTrue(StructureConstructionManagerSupport.canSeeRuntimeRoadProgress(
                true,  // sameLevel
                true,  // withinRadius
                true,  // holdingPreviewTool
                true   // canManage
        ));
    }

    @Test
    void rejectsViewerWithoutManagePermission() {
        assertFalse(StructureConstructionManagerSupport.canSeeRuntimeRoadProgress(
                true,
                true,
                true,
                false
        ));
    }
}
```

- [ ] **Step 2: Run targeted tests to verify the new test fails**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.RoadRuntimeProgressAudienceTest --tests com.monpai.sailboatmod.network.packet.ConstructionPacketStringLimitTest`
Expected: audience test fails because helper/logic does not exist yet; packet test remains green.

- [ ] **Step 3: Commit the red test scaffold once the failure is correct**

```bash
git add src/test/java/com/monpai/sailboatmod/nation/service/RoadRuntimeProgressAudienceTest.java src/test/java/com/monpai/sailboatmod/network/packet/ConstructionPacketStringLimitTest.java
git commit -m "Add road runtime progress regression tests"
```

### Task 2: Enforce Boundary-Land Anchors And Constrained Routing

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderConstraintTest.java`

- [ ] **Step 1: Write the failing pathfinder constraint tests**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPathfinderConstraintTest {
    @Test
    void rejectsRouteThatNeedsExcessiveBridgeLength() {
        RoadPathfinder.PathSummary summary = RoadPathfinderSupport.plan(
                List.of(new BlockPos(0, 64, 0), new BlockPos(30, 64, 0)),
                List.of(),
                4,
                8
        );

        assertTrue(summary.path().isEmpty());
    }

    @Test
    void keepsShortBridgeGapWithinLimits() {
        RoadPathfinder.PathSummary summary = RoadPathfinderSupport.plan(
                List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 0), new BlockPos(7, 64, 0)),
                List.of(),
                4,
                8
        );

        assertEquals(new BlockPos(7, 64, 0), summary.path().get(summary.path().size() - 1));
        assertTrue(summary.totalBridgeLength() <= 8);
    }

    @Test
    void avoidsBuildingObstructionWhenDetourExists() {
        RoadPathfinder.PathSummary summary = RoadPathfinderSupport.planAroundObstacle();

        assertTrue(summary.path().stream().noneMatch(pos -> pos.getX() == 4 && pos.getZ() == 0));
    }
}
```

- [ ] **Step 2: Run the pathfinder tests to verify they fail**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.RoadPathfinderConstraintTest`
Expected: FAIL because constrained bridge accounting and obstruction-aware planning do not exist yet.

- [ ] **Step 3: Implement minimal anchor and pathfinder changes**

```java
// ManualRoadPlannerService
private static BlockPos resolveTownAnchor(...) {
    BlockPos roadNode = nearestRoadNodeInClaims(...);
    if (isValidRoadAnchor(level, roadNode)) {
        return roadNode;
    }
    BlockPos boundary = nearestBoundaryLandAnchor(...);
    if (isValidRoadAnchor(level, boundary)) {
        return boundary;
    }
    return fallbackTownCoreSurface(...);
}

// RoadPathfinder
private static final int MAX_CONTIGUOUS_BRIDGE = 4;
private static final int MAX_TOTAL_BRIDGE = 8;

private static double getMoveCost(...) {
    if (isBlockedByStructure(level, next)) {
        return Double.POSITIVE_INFINITY;
    }
    if (crossesWater(level, next) && !canExtendBridge(current, next)) {
        return Double.POSITIVE_INFINITY;
    }
    ...
}
```

- [ ] **Step 4: Run the pathfinder tests to verify they pass**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.RoadPathfinderConstraintTest`
Expected: PASS

- [ ] **Step 5: Commit the routing changes**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderConstraintTest.java
git commit -m "Constrain road routing to land-first paths"
```

### Task 3: Align Runtime Road Progress With Runtime Preview Audience

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/ConstructionGhostPreviewRenderer.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/ConstructionGhostClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java`

- [ ] **Step 1: Write the failing UI audience helper test**

```java
@Test
void roadProgressUsesSameVisibilityGateAsRuntimeGhostPreview() {
    assertTrue(StructureConstructionManagerSupport.canSeeRuntimeRoadProgress(true, true, true, true));
    assertFalse(StructureConstructionManagerSupport.canSeeRuntimeRoadProgress(true, false, true, true));
}
```

- [ ] **Step 2: Run the audience test to verify it fails**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.RoadRuntimeProgressAudienceTest`
Expected: FAIL because the helper/logic is not wired to runtime preview rules.

- [ ] **Step 3: Implement minimal sync and renderer updates**

```java
// StructureConstructionManager
private static boolean canSeeRuntimeRoadProgress(ServerPlayer player, RoadConstructionJob job) {
    return player != null
            && player.serverLevel() == job.level
            && player.blockPosition().distSqr(getRoadFocusPos(job)) <= GHOST_PREVIEW_RADIUS_SQR
            && isHoldingConstructionPreviewTool(player)
            && canManageConstruction(job.level, player, resolveRoadTownId(job), job.nationId);
}

// ConstructionGhostPreviewRenderer
for (ConstructionGhostClientHooks.RoadPreview preview : ConstructionGhostClientHooks.roadPreviews()) {
    renderPreview(...);
    renderRoadProgressOverlay(minecraft, graphics, preview);
}
```

- [ ] **Step 4: Run the audience test plus targeted compile to verify it passes**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.RoadRuntimeProgressAudienceTest`
Expected: PASS

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit the runtime progress visibility changes**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/main/java/com/monpai/sailboatmod/client/renderer/ConstructionGhostPreviewRenderer.java src/main/java/com/monpai/sailboatmod/client/ConstructionGhostClientHooks.java src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java src/test/java/com/monpai/sailboatmod/nation/service/RoadRuntimeProgressAudienceTest.java
git commit -m "Show runtime road progress to nearby managers"
```

### Task 4: Re-verify Packet Safety And Ship The Correct Jar

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/SyncRoadConstructionProgressPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/SyncConstructionGhostPreviewPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/UseBuilderHammerPacket.java`

- [ ] **Step 1: Re-run the packet regression tests**

Run: `.\gradlew.bat test --tests com.monpai.sailboatmod.network.packet.ConstructionPacketStringLimitTest`
Expected: PASS

- [ ] **Step 2: Rebuild the full jar**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL` and updated jars under `build/libs/`

- [ ] **Step 3: Verify expected outputs exist**

```powershell
Get-ChildItem .\build\libs\ | Select-Object Name,Length,LastWriteTime
```

Expected: includes `sailboatmod-1.3.7-all.jar` and `sailboatmod-1.3.7-reobf.jar`

- [ ] **Step 4: Commit the final implementation**

```bash
git add src/main/java src/test/java docs/superpowers/plans/2026-04-09-road-routing-and-runtime-progress.md
git commit -m "Improve road routing and runtime construction progress"
```

## Self-Review

- Spec coverage:
  - boundary anchors: Task 2
  - land-first pathfinding with bounded bridges: Task 2
  - building obstruction avoidance: Task 2
  - runtime progress audience and UI alignment: Task 3
  - packet crash guard and rebuilt jar: Task 4
- Placeholder scan:
  - No TODO/TBD placeholders remain.
- Type consistency:
  - Helper names used in tests (`canSeeRuntimeRoadProgress`, `PathSummary`) must either be implemented directly or folded into equivalent production helpers during execution.
