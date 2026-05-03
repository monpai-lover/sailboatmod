# Road Planner Recruits-style Fixed LOD + Route Preload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make road planner minimap zoom behave like Recruits by always rendering `LOD_1` tiles, while preserving route-based force-loaded rendering for unknown route-covered tiles.

**Architecture:** Extract tile render request calculation into a pure planner that always emits `LOD_1` requests and scales screen draw size from `RoadPlannerMapView.scale()`. Wire `RoadPlannerMapCanvas` to render those fixed-LOD requests. Add regression coverage that zoom cannot request lower LODs and that route tile sync can populate an unexplored `LOD_1` cache tile.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, Gradle `test` / `compileJava` / `jarJar`.

---

## File Structure

- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlanner.java`
  - Pure, package-private planner for visible tile requests.
  - Owns the Recruits-style rule: every render request uses `MapLod.LOD_1`.
  - Does not touch `Minecraft`, texture state, disk cache, or network.

- Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlannerTest.java`
  - Unit tests fixed-LOD request generation across zoom scales.
  - Unit tests that draw size changes with zoom while LOD remains unchanged.

- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`
  - Remove active `RoadPlannerTileLodSelector.select(view.scale())` usage.
  - Render requests from `RoadPlannerMapTileRenderPlanner.plan(rect, view)`.
  - Add package-private `tileRequestsForTest()` so tests can verify the canvas integration without constructing `GuiGraphics`.

- Modify `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvasTest.java`
  - Add integration test proving the canvas still asks for `LOD_1` after zooming out.

- Modify `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManagerLodIsolationTest.java`
  - Add regression test proving a route preload tile packet can populate `lod_1` cache without any pre-existing explored tile.

No production changes are planned for `RoadPlannerMapPreloadService` or `RoadPlannerMapPreloadJob`; their existing route force-load and `LOD_1` packet behavior is preserved and verified.

---

### Task 1: Add pure fixed-LOD render request planner

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlanner.java`
- Create: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlannerTest.java`

- [ ] **Step 1: Write the failing planner tests**

Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlannerTest.java` with this exact content:

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerMapTileRenderPlannerTest {
    @Test
    void usesLod1ForEveryZoomScale() {
        RoadPlannerVanillaLayout.Rect rect = new RoadPlannerVanillaLayout.Rect(0, 0, 320, 240);

        for (double scale : List.of(3.0D, 1.0D, 0.45D, 0.25D)) {
            RoadPlannerMapView view = RoadPlannerMapView.centered(0, 0, scale);

            List<RoadPlannerMapTileRenderPlanner.TileRequest> requests = RoadPlannerMapTileRenderPlanner.plan(rect, view);

            assertFalse(requests.isEmpty(), "scale " + scale + " should produce visible tile requests");
            assertTrue(requests.stream().allMatch(request -> request.lod() == MapLod.LOD_1),
                    "scale " + scale + " must not select a lower-detail LOD: " + requests);
        }
    }

    @Test
    void zoomChangesDrawSizeWithoutChangingLod() {
        RoadPlannerVanillaLayout.Rect rect = new RoadPlannerVanillaLayout.Rect(0, 0, 320, 240);
        RoadPlannerMapView closeView = RoadPlannerMapView.centered(0, 0, 2.0D);
        RoadPlannerMapView farView = RoadPlannerMapView.centered(0, 0, 0.5D);

        RoadPlannerMapTileRenderPlanner.TileRequest closeRequest = RoadPlannerMapTileRenderPlanner.plan(rect, closeView).get(0);
        RoadPlannerMapTileRenderPlanner.TileRequest farRequest = RoadPlannerMapTileRenderPlanner.plan(rect, farView).get(0);

        assertEquals(MapLod.LOD_1, closeRequest.lod());
        assertEquals(MapLod.LOD_1, farRequest.lod());
        assertTrue(closeRequest.screenSize() > farRequest.screenSize(),
                "zoom should change GUI draw size instead of selecting another LOD");
    }
}
```

- [ ] **Step 2: Run the planner tests and verify they fail for the right reason**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapTileRenderPlannerTest
```

Expected: `compileTestJava FAILED` with an error containing `cannot find symbol` for `RoadPlannerMapTileRenderPlanner`.

- [ ] **Step 3: Implement the fixed-LOD render request planner**

Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlanner.java` with this exact content:

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;

import java.util.ArrayList;
import java.util.List;

final class RoadPlannerMapTileRenderPlanner {
    private RoadPlannerMapTileRenderPlanner() {
    }

    static List<TileRequest> plan(RoadPlannerVanillaLayout.Rect rect, RoadPlannerMapView view) {
        if (rect == null || view == null) {
            return List.of();
        }
        RoadPlannerMapLayout.Rect mapRect = new RoadPlannerMapLayout.Rect(rect.x(), rect.y(), rect.width(), rect.height());
        double tileScreenSize = Math.max(16.0D, RoadPlannerTile.TILE_SIZE_BLOCKS * view.scale());
        int drawSize = (int) Math.ceil(tileScreenSize) + 1;
        int minWorldX = view.screenToWorldX(rect.x(), mapRect);
        int maxWorldX = view.screenToWorldX(rect.right(), mapRect);
        int minWorldZ = view.screenToWorldZ(rect.y(), mapRect);
        int maxWorldZ = view.screenToWorldZ(rect.bottom(), mapRect);
        int startTileX = Math.floorDiv(Math.min(minWorldX, maxWorldX), RoadPlannerTile.TILE_SIZE_BLOCKS) - 1;
        int endTileX = Math.floorDiv(Math.max(minWorldX, maxWorldX), RoadPlannerTile.TILE_SIZE_BLOCKS) + 1;
        int startTileZ = Math.floorDiv(Math.min(minWorldZ, maxWorldZ), RoadPlannerTile.TILE_SIZE_BLOCKS) - 1;
        int endTileZ = Math.floorDiv(Math.max(minWorldZ, maxWorldZ), RoadPlannerTile.TILE_SIZE_BLOCKS) + 1;
        List<TileRequest> requests = new ArrayList<>((endTileX - startTileX + 1) * (endTileZ - startTileZ + 1));
        for (int tileZ = startTileZ; tileZ <= endTileZ; tileZ++) {
            for (int tileX = startTileX; tileX <= endTileX; tileX++) {
                int screenX = view.worldToScreenX(tileX * RoadPlannerTile.TILE_SIZE_BLOCKS, mapRect);
                int screenZ = view.worldToScreenZ(tileZ * RoadPlannerTile.TILE_SIZE_BLOCKS, mapRect);
                requests.add(new TileRequest(tileX, tileZ, MapLod.LOD_1, screenX, screenZ, drawSize));
            }
        }
        return List.copyOf(requests);
    }

    record TileRequest(int tileX, int tileZ, MapLod lod, int screenX, int screenZ, int screenSize) {
    }
}
```

- [ ] **Step 4: Run the planner tests and verify they pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapTileRenderPlannerTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit Task 1**

Run:

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlanner.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlannerTest.java
git commit -m "test: add fixed lod minimap render planner"
```

Expected: commit succeeds.

---

### Task 2: Wire `RoadPlannerMapCanvas` to the fixed-LOD planner

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvasTest.java`

- [ ] **Step 1: Write the failing canvas integration test**

Modify `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvasTest.java`:

1. Add this import with the other static imports:

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
```

2. Add this test method before the `component(...)` helper:

```java
    @Test
    void canvasTileRequestsStayOnLod1AfterZoomingOut() {
        RoadPlannerVanillaLayout.Rect rect = new RoadPlannerVanillaLayout.Rect(100, 60, 400, 300);
        RoadPlannerMapView view = RoadPlannerMapView.centered(0, 0, 3.0D);
        RoadPlannerMapCanvas canvas = new RoadPlannerMapCanvas(rect, component(rect), view, null);

        var initialRequests = canvas.tileRequestsForTest();
        view.zoomAround(300, 210, 0.1D, new RoadPlannerMapLayout.Rect(rect.x(), rect.y(), rect.width(), rect.height()));
        var zoomedOutRequests = canvas.tileRequestsForTest();

        assertFalse(initialRequests.isEmpty());
        assertFalse(zoomedOutRequests.isEmpty());
        assertTrue(initialRequests.stream().allMatch(request -> request.lod() == MapLod.LOD_1));
        assertTrue(zoomedOutRequests.stream().allMatch(request -> request.lod() == MapLod.LOD_1));
    }
```

- [ ] **Step 2: Run the canvas test and verify it fails for the right reason**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapCanvasTest
```

Expected: `compileTestJava FAILED` with an error containing `cannot find symbol` for `tileRequestsForTest()`.

- [ ] **Step 3: Modify `RoadPlannerMapCanvas` to use fixed `LOD_1` render requests**

In `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`, add this import below the existing imports:

```java
import java.util.List;
```

Add this package-private method after `renderPlaceholder(...)`:

```java
    List<RoadPlannerMapTileRenderPlanner.TileRequest> tileRequestsForTest() {
        return tileRequestsForRender();
    }
```

Replace the existing private `renderTiles(GuiGraphics graphics)` method with this exact implementation:

```java
    private void renderTiles(GuiGraphics graphics) {
        tileManager.refreshWorldContext();
        for (RoadPlannerMapTileRenderPlanner.TileRequest request : tileRequestsForRender()) {
            RoadPlannerTile tile = tileManager.resolveRenderableTile(request.tileX(), request.tileZ(), request.lod());
            tile.render(graphics, request.screenX(), request.screenZ(), request.screenSize());
        }
    }
```

Add this private method immediately after `renderTiles(...)`:

```java
    private List<RoadPlannerMapTileRenderPlanner.TileRequest> tileRequestsForRender() {
        return RoadPlannerMapTileRenderPlanner.plan(rect, view);
    }
```

After this change, `RoadPlannerMapCanvas` must no longer call `RoadPlannerTileLodSelector.select(view.scale())`.

- [ ] **Step 4: Run the canvas and planner tests**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapCanvasTest --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapTileRenderPlannerTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verify source no longer selects render LOD from zoom in the canvas**

Run:

```bash
rg -n "RoadPlannerTileLodSelector\.select|renderLod" src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java
```

Expected: no output and exit code `1` from `rg` because no matches exist in `RoadPlannerMapCanvas.java`.

- [ ] **Step 6: Commit Task 2**

Run:

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvasTest.java
git commit -m "fix: render road planner minimap from lod1 tiles"
```

Expected: commit succeeds.

---

### Task 3: Add regression coverage for route-rendered unknown `LOD_1` tile sync

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManagerLodIsolationTest.java`

This task protects the already-required behavior that route preload output can populate a client tile even when the tile was not previously explored or loaded in the client cache.

- [ ] **Step 1: Add imports for tile sync regression test**

In `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManagerLodIsolationTest.java`, add these imports:

```java
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;

import java.util.Arrays;
import java.util.UUID;
```

Add this static import with the existing assertions:

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
```

- [ ] **Step 2: Add the regression test**

Add this test method after `differentLodsUseDifferentCacheBuckets()`:

```java
    @Test
    void tileSyncPopulatesLod1CacheForRouteRenderedUnknownTile() {
        File rootDir = tempDir.toFile();
        RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(rootDir, "world_a", "minecraft:overworld");
        int[] pixels = new int[RoadPlannerTile.TILE_PIXEL_SIZE * RoadPlannerTile.TILE_PIXEL_SIZE];
        Arrays.fill(pixels, 0xFF336699);
        RoadPlannerMapTileSyncPacket packet = new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                42L,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                2,
                -3,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                pixels);

        int applied = manager.applyTileSync(packet);

        assertEquals(1, applied);
        assertEquals(1, manager.loadedTileCount());
        assertTrue(rootDir.toPath().resolve("world_a").resolve("minecraft_overworld").resolve("lod_1").resolve("2_-3.png").toFile().exists());
        assertFalse(rootDir.toPath().resolve("world_a").resolve("minecraft_overworld").resolve("lod_4").resolve("2_-3.png").toFile().exists());
    }
```

- [ ] **Step 3: Run the LOD isolation regression test**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManagerLodIsolationTest
```

Expected: `BUILD SUCCESSFUL`. If this fails, inspect the failure before changing production code; the expected behavior already exists through `RoadPlannerTileManager.applyTileSync(...)`.

- [ ] **Step 4: Commit Task 3**

Run:

```bash
git add src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManagerLodIsolationTest.java
git commit -m "test: cover route tile sync into lod1 cache"
```

Expected: commit succeeds.

---

### Task 4: Full verification and `-all` jar packaging

**Files:**
- Verify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`
- Verify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlanner.java`
- Verify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlannerTest.java`
- Verify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvasTest.java`
- Verify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManagerLodIsolationTest.java`

- [ ] **Step 1: Run targeted road planner tests**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapTileRenderPlannerTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapCanvasTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManagerLodIsolationTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileLodSelectorTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRoutePreloadSchedulerTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest \
  --tests com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlannerTest \
  --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadJobTest \
  --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Compile production sources**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Build the all jar**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false jarJar
```

Expected: `BUILD SUCCESSFUL` and a jar at:

```text
/Users/goatdie/sailboatmod/build/libs/sailboatmod-1.3.7-all.jar
```

- [ ] **Step 4: Clean test-generated local artifacts only if present**

Run:

```bash
git status --short
```

If the output contains only `logs/...` modifications and/or untracked `roadplanner_drafts_test/...` files from tests, run:

```bash
git restore logs
git clean -fd roadplanner_drafts_test
```

Then run:

```bash
git status --short --branch
```

Expected: no uncommitted source changes. The branch may still show `ahead` commits.

- [ ] **Step 5: Record final jar metadata**

Run:

```bash
stat -f '%N %Sm %z bytes' build/libs/sailboatmod-1.3.7-all.jar
git rev-parse --short HEAD
```

Expected: the `stat` command prints the fresh `sailboatmod-1.3.7-all.jar` metadata and `git rev-parse` prints the final commit hash.

- [ ] **Step 6: Final commit only if verification changed tracked files**

Run:

```bash
git status --short
```

If tracked source or test files remain modified, commit them with:

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlanner.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTileRenderPlannerTest.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvasTest.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManagerLodIsolationTest.java
git commit -m "fix: stabilize road planner minimap zoom rendering"
```

Expected: either no commit is needed because Tasks 1-3 already committed all changes, or the final commit succeeds.

---

## Self-Review Notes

Spec coverage:

- Fixed Recruits-style zoom behavior is covered by Tasks 1 and 2.
- No render-time LOD switching is covered by Task 2 Step 5.
- Route-covered unknown `LOD_1` tile rendering is covered by Task 3.
- Existing rectangle-first / path-only route preload behavior remains covered by `RoadMapRoutePreloadPlannerTest` in Task 4.
- World/dimension/lod cache isolation remains covered by `RoadPlannerTileManagerLodIsolationTest` in Task 4.

Type consistency:

- New helper type is `RoadPlannerMapTileRenderPlanner.TileRequest`.
- `TileRequest.lod()` returns `MapLod`.
- `RoadPlannerMapCanvas.tileRequestsForTest()` returns `List<RoadPlannerMapTileRenderPlanner.TileRequest>`.
- Canvas rendering uses `request.tileX()`, `request.tileZ()`, `request.lod()`, `request.screenX()`, `request.screenZ()`, and `request.screenSize()`.
