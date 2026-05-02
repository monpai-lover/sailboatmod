# Road Planner Route Preload + LOD Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace viewport-style minimap resampling with a route-driven preload pipeline that caches `LOD_1` through `LOD_8` per world/dimension and renders preloaded route areas immediately.

**Architecture:** Keep route coverage math and LOD math pure, then wire them into a server-driven preload service and a client-side request scheduler. The old snapshot path stays available as fallback/manual debug, but zoom and route updates move to the new preload packets so the map stops re-sampling on every zoom.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, Gradle/JUnit 5, existing `SimpleChannel` packets, `NativeImage`/`DynamicTexture`, server tick services.

---

## File Structure

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapTileSpec.java`
  - Shared tile constants (`TILE_BLOCKS = 256`, `TILE_PIXELS = 256`) used by both server preload and client rendering.

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapRoutePreloadPlanner.java`
  - Pure route-to-chunk planner with rectangle-vs-path fallback.
  - Keeps chunk selection and budget logic out of the server service.

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapRoutePreloadPlan.java`
  - Immutable plan output.
  - Exposes route chunk ordering, coverage mode, and `tileKeys(...)` conversion for the preload service.

- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/map/MapLod.java`
  - Add `LOD_8`.

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapLodPyramid.java`
  - Pure LOD derivation helper from `LOD_1` source pixels to display-sized `LOD_2`/`LOD_4`/`LOD_8` tiles.

- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileKey.java`
  - Add `lod` to the cache identity so world/dimension/LOD never leak into each other.

- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java`
  - Add bulk pixel replacement for preload packets.
  - Keep the existing chunk and single-pixel paths as fallback.

- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`
  - Make tile lookup LOD-aware.
  - Add `applyTileSync(...)` for the new packet.
  - Make disk paths include `lod_<N>`.

- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileLodSelector.java`
  - Pure zoom-to-render-LOD selector.

- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`
  - Render the best cached LOD for the current zoom.
  - Fall back to the highest-precision cached tile when the preferred LOD is missing.

- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoutePreloadScheduler.java`
  - Pure client-side request/revision tracker for `ENTER_PLANNER_PRELOAD`, `ROUTE_PRELOAD`, and `FORCE_RENDER`.

- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
  - Send preload requests on screen open and after auto-complete.
  - Stop sending terrain resample requests on zoom.
  - Keep local chunk rendering as fallback only.
  - Apply preload progress/tile packets.
  - Cancel active preload work when the screen closes.

- Create `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapPreloadRequestPacket.java`
  - Client-to-server preload request.

- Create `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapPreloadProgressPacket.java`
  - Server-to-client progress update.

- Create `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapTileSyncPacket.java`
  - Server-to-client tile payload.

- Create `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapPreloadCancelPacket.java`
  - Client-to-server cancel packet for screen close / stale requests.

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJob.java`
  - Pure batch processor that turns route tiles into tile-sync packets.

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java`
  - Server singleton that owns active preload jobs and ticks them on the server thread.

- Modify `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
  - Register the new preload packets.

- Modify `src/main/java/com/monpai/sailboatmod/ServerEvents.java`
  - Tick the preload service every server tick.
  - Clear its runtime state on server stop.

- Modify `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`
  - Add round-trip coverage for the new preload packets.

- Create `src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapRoutePreloadPlannerTest.java`
  - Pure planner tests for rectangle mode, path-only fallback, and tile-key dedupe.

- Create `src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapLodPyramidTest.java`
  - Pure LOD derivation tests.

- Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManagerLodIsolationTest.java`
  - Ensures world/dimension/LOD cache isolation works.

- Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileLodSelectorTest.java`
  - Ensures zoom thresholds map to the intended render LODs.

- Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoutePreloadSchedulerTest.java`
  - Ensures request IDs, purposes, and stale-response rejection work.

- Create `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJobTest.java`
  - Pure job test for progress and tile emission.

---

## Task 1: Add Route Coverage Planning

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapRoutePreloadPlanner.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapRoutePreloadPlan.java`
- Create: `src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapRoutePreloadPlannerTest.java`

- [ ] **Step 1: Write the failing test**

```java
class RoadMapRoutePreloadPlannerTest {
    @Test
    void rectangleModeUsesBoundingRectangleWhenBudgetAllows() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(4096, 4, 3, 8);
        RoadMapRoutePreloadPlan plan = planner.plan(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(256, 64, 256),
                new BlockPos(512, 64, 256)));

        assertEquals(RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE, plan.coverageMode());
        assertTrue(plan.chunks().size() > 0);
    }

    @Test
    void oversizedRectangleFallsBackToPathChunks() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(4, 4, 3, 8);
        RoadMapRoutePreloadPlan plan = planner.plan(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4096, 64, 0)));

        assertEquals(RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY, plan.coverageMode());
    }

    @Test
    void tileKeysDeduplicateRepeatedRouteChunks() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(4096, 4, 3, 8);
        RoadMapRoutePreloadPlan plan = planner.plan(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(256, 64, 0),
                new BlockPos(256, 64, 0)));

        var keys = plan.tileKeys("world_a", "minecraft:overworld", MapLod.LOD_1);
        assertEquals(keys.size(), new java.util.LinkedHashSet<>(keys).size());
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlannerTest
```

Expected: compile or test failure because `RoadMapRoutePreloadPlanner` and `RoadMapRoutePreloadPlan` do not exist yet.

- [ ] **Step 3: Implement the pure planner and plan record**

```java
public final class RoadMapRoutePreloadPlanner {
    private final int maxRectangleChunks;
    private final int rectanglePaddingChunks;
    private final int pathPaddingChunks;
    private final int segmentSampleStepBlocks;

    public RoadMapRoutePreloadPlanner(int maxRectangleChunks,
                                      int rectanglePaddingChunks,
                                      int pathPaddingChunks,
                                      int segmentSampleStepBlocks) {
        this.maxRectangleChunks = Math.max(1, maxRectangleChunks);
        this.rectanglePaddingChunks = Math.max(0, rectanglePaddingChunks);
        this.pathPaddingChunks = Math.max(0, pathPaddingChunks);
        this.segmentSampleStepBlocks = Math.max(1, segmentSampleStepBlocks);
    }

    public RoadMapRoutePreloadPlan plan(List<BlockPos> routeNodes) {
        List<BlockPos> nodes = routeNodes == null ? List.of() : routeNodes.stream().map(BlockPos::immutable).toList();
        if (nodes.isEmpty()) {
            return new RoadMapRoutePreloadPlan(RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY, List.of(), 0, 0);
        }
        List<ChunkPos> pathChunks = pathChunks(nodes);
        List<ChunkPos> rectangleChunks = rectangleChunks(nodes);
        RoadMapRoutePreloadPlan.CoverageMode mode =
                rectangleChunks.size() <= maxRectangleChunks
                        ? RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE
                        : RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY;
        LinkedHashSet<ChunkPos> ordered = new LinkedHashSet<>(pathChunks);
        if (mode == RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE) {
            ordered.addAll(rectangleChunks);
        }
        return new RoadMapRoutePreloadPlan(mode, List.copyOf(ordered), pathChunks.size(), rectangleChunks.size());
    }
}
```

`RoadMapRoutePreloadPlan` should expose `tileKeys(String worldId, String dimensionId, MapLod lod)` and dedupe chunk-to-tile conversion with a `LinkedHashSet<RoadPlannerTileKey>`.

- [ ] **Step 4: Re-run the test and confirm it passes**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlannerTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapRoutePreloadPlanner.java \
        src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapRoutePreloadPlan.java \
        src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapRoutePreloadPlannerTest.java
git commit -m "feat: add route preload coverage planner"
```

---

## Task 2: Add LOD Pyramid and LOD-Isolated Tile Cache

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/map/MapLod.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapTileSpec.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapLodPyramid.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileKey.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileLodSelector.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`
- Create: `src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapLodPyramidTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManagerLodIsolationTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileLodSelectorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
class RoadMapLodPyramidTest {
    @Test
    void deriveDisplayPixelsRepeatsCoarseColorsForLowerLods() {
        int[] source = new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
        Arrays.fill(source, 0xFF223344);
        source[0] = 0xFF556677;

        int[] derived = RoadMapLodPyramid.deriveDisplayPixels(source, RoadMapTileSpec.TILE_PIXELS, RoadMapTileSpec.TILE_PIXELS, MapLod.LOD_4);

        assertEquals(RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS, derived.length);
        assertEquals(derived[0], derived[1]);
        assertEquals(derived[0], derived[RoadMapTileSpec.TILE_PIXELS]);
    }
}

class RoadPlannerTileLodSelectorTest {
    @Test
    void scaleThresholdsPreferHigherPrecisionWhenZoomedIn() {
        assertEquals(MapLod.LOD_1, RoadPlannerTileLodSelector.select(3.0D));
        assertEquals(MapLod.LOD_2, RoadPlannerTileLodSelector.select(1.5D));
        assertEquals(MapLod.LOD_4, RoadPlannerTileLodSelector.select(0.8D));
        assertEquals(MapLod.LOD_8, RoadPlannerTileLodSelector.select(0.3D));
    }
}
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.roadplanner.map.RoadMapLodPyramidTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManagerLodIsolationTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileLodSelectorTest
```

Expected: compilation failures because `LOD_8`, `RoadMapTileSpec`, `RoadMapLodPyramid`, and the LOD-aware tile APIs do not exist yet.

- [ ] **Step 3: Implement the LOD constants and tile cache split**

```java
public enum MapLod {
    LOD_1(1),
    LOD_2(2),
    LOD_4(4),
    LOD_8(8);

    private final int blocksPerPixel;

    MapLod(int blocksPerPixel) {
        this.blocksPerPixel = blocksPerPixel;
    }

    public int blocksPerPixel() {
        return blocksPerPixel;
    }
}
```

```java
public final class RoadMapTileSpec {
    public static final int TILE_BLOCKS = 256;
    public static final int TILE_PIXELS = 256;

    private RoadMapTileSpec() {
    }
}
```

```java
public record RoadPlannerTileKey(String worldId, String dimensionId, MapLod lod, int tileX, int tileZ) {
    public RoadPlannerTileKey {
        worldId = sanitize(worldId == null || worldId.isBlank() ? "unknown" : worldId);
        dimensionId = sanitize(dimensionId == null || dimensionId.isBlank() ? "overworld" : dimensionId);
        lod = lod == null ? MapLod.LOD_1 : lod;
    }
}
```

`RoadPlannerTileManager` should:

- replace the 2-arg tile lookup with `getOrCreateTile(int tileX, int tileZ, MapLod lod)`
- replace `hasCachedTileForChunk(ChunkPos)` with `hasCachedTileForChunk(ChunkPos, MapLod)`
- store files under `roadplanner_map_cache/<world>/<dimension>/lod_<N>/<tileX>_<tileZ>.png`
- add `resolveRenderableTile(int tileX, int tileZ, MapLod preferredLod)` that falls back from the preferred LOD toward `LOD_1`
- add `applyTileSync(RoadPlannerMapTileSyncPacket packet)`
- update the existing `applySnapshot(RoadMapSnapshotSyncPacket packet)` fallback to write into `packet.lod()` so the manual snapshot path is isolated by LOD too

`RoadPlannerTile` should add a bulk replacement method:

```java
public synchronized void replacePixels(int[] argbPixels) {
    if (image == null || argbPixels == null || argbPixels.length != RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS) {
        return;
    }
    for (int y = 0; y < RoadMapTileSpec.TILE_PIXELS; y++) {
        for (int x = 0; x < RoadMapTileSpec.TILE_PIXELS; x++) {
            image.setPixelRGBA(x, y, argbPixels[y * RoadMapTileSpec.TILE_PIXELS + x]);
        }
    }
    loadedFromCache = true;
    dirty = true;
}
```

`RoadMapLodPyramid.deriveDisplayPixels(...)` should return display-sized `RoadMapTileSpec.TILE_PIXELS x RoadMapTileSpec.TILE_PIXELS` arrays for `LOD_2`, `LOD_4`, and `LOD_8`.

`RoadPlannerMapCanvas.renderTiles(...)` should select the render LOD from `RoadPlannerTileLodSelector.select(view.scale())` and call `tileManager.resolveRenderableTile(...)`. The fallback lookup should search from the preferred LOD down to `LOD_1` and use the first cached tile it finds.

- [ ] **Step 4: Re-run the tests and confirm they pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.roadplanner.map.RoadMapLodPyramidTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManagerLodIsolationTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileLodSelectorTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/map/MapLod.java \
        src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapTileSpec.java \
        src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapLodPyramid.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileKey.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileLodSelector.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java \
        src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapLodPyramidTest.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManagerLodIsolationTest.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileLodSelectorTest.java
git commit -m "feat: add lod-aware road planner tile cache"
```

---

## Task 3: Add Preload Packets and Server Job/Service

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapPreloadRequestPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapPreloadProgressPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapTileSyncPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapPreloadCancelPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJob.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Modify: `src/main/java/com/monpai/sailboatmod/ServerEvents.java`
- Modify: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJobTest.java`

- [ ] **Step 1: Write the failing packet and job tests**

Extend `RoadPlannerPacketRoundTripTest.java` with cases for the new packet records:

```java
@Test
void mapPreloadRequestRoundTripsIdentityAndRouteNodes() {
    RoadPlannerMapPreloadRequestPacket packet = new RoadPlannerMapPreloadRequestPacket(
            UUID.randomUUID(),
            17L,
            RoadPlannerMapPreloadRequestPacket.Purpose.ENTER_PLANNER_PRELOAD,
            "world_a",
            "minecraft:overworld",
            new BlockPos(0, 64, 0),
            new BlockPos(512, 64, 256),
            List.of(new BlockPos(128, 64, 64), new BlockPos(256, 64, 128)),
            1);

    RoadPlannerMapPreloadRequestPacket decoded = roundTrip(packet, RoadPlannerMapPreloadRequestPacket::encode, RoadPlannerMapPreloadRequestPacket::decode);

    assertEquals(packet, decoded);
}

@Test
void mapPreloadTileAndProgressPacketsRoundTrip() {
    RoadPlannerMapTileSyncPacket tile = new RoadPlannerMapTileSyncPacket(
            UUID.randomUUID(),
            17L,
            RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
            "world_a",
            "minecraft:overworld",
            MapLod.LOD_4,
            0,
            0,
            RoadMapTileSpec.TILE_PIXELS,
            RoadMapTileSpec.TILE_PIXELS,
            new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS]);
    RoadPlannerMapPreloadProgressPacket progress = new RoadPlannerMapPreloadProgressPacket(
            tile.sessionId(),
            tile.requestId(),
            tile.purpose(),
            tile.worldId(),
            tile.dimensionId(),
            RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE,
            1,
            8,
            RoadPlannerMapPreloadProgressPacket.State.SAMPLING,
            "sampling");
    RoadPlannerMapPreloadCancelPacket cancel = new RoadPlannerMapPreloadCancelPacket(
            tile.sessionId(),
            tile.requestId(),
            tile.purpose());

    assertEquals(tile, roundTrip(tile, RoadPlannerMapTileSyncPacket::encode, RoadPlannerMapTileSyncPacket::decode));
    assertEquals(progress, roundTrip(progress, RoadPlannerMapPreloadProgressPacket::encode, RoadPlannerMapPreloadProgressPacket::decode));
    assertEquals(cancel, roundTrip(cancel, RoadPlannerMapPreloadCancelPacket::encode, RoadPlannerMapPreloadCancelPacket::decode));
}
```

Add a pure job test:

```java
class RoadPlannerMapPreloadJobTest {
    @Test
    void jobEmitsTilePacketsForAllLodsAndAdvancesProgress() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(4096, 4, 3, 8);
        RoadMapRoutePreloadPlan plan = planner.plan(List.of(new BlockPos(0, 64, 0), new BlockPos(256, 64, 0)));
        RoadPlannerMapPreloadJob job = new RoadPlannerMapPreloadJob(
                UUID.randomUUID(),
                1L,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                "world_a",
                "minecraft:overworld",
                plan);
        List<RoadPlannerMapTileSyncPacket> packets = new ArrayList<>();

        int processed = job.advance(1, key -> new RoadMapSnapshot(1L,
                RoadMapRegion.centeredOn(new BlockPos(128, 0, 128), RoadMapTileSpec.TILE_BLOCKS, MapLod.LOD_1),
                List.of(),
                new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS]),
                packets::add);

        assertEquals(1, processed);
        assertEquals(4, packets.size());
        assertEquals(MapLod.LOD_1, packets.get(0).lod());
        assertEquals(MapLod.LOD_2, packets.get(1).lod());
        assertEquals(MapLod.LOD_4, packets.get(2).lod());
        assertEquals(MapLod.LOD_8, packets.get(3).lod());
    }
}
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest \
  --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadJobTest
```

Expected: compilation failures because the new packet classes and preload job/service do not exist yet.

- [ ] **Step 3: Implement the packets and job/service**

Use a request packet shaped like this:

```java
public record RoadPlannerMapPreloadRequestPacket(UUID sessionId,
                                                 long requestId,
                                                 Purpose purpose,
                                                 String worldId,
                                                 String dimensionId,
                                                 BlockPos start,
                                                 BlockPos destination,
                                                 List<BlockPos> routeNodes,
                                                 int protocolVersion) {
    public static final int PROTOCOL_VERSION = 1;

    public enum Purpose {
        ENTER_PLANNER_PRELOAD,
        ROUTE_PRELOAD,
        FORCE_RENDER
    }
}
```

Use a progress packet with a state enum that can say `QUEUED`, `SAMPLING`, `DERIVING`, `DEGRADED_PATH_ONLY`, `COMPLETE`, `FAILED`, or `CANCELLED`.

```java
public record RoadPlannerMapPreloadProgressPacket(UUID sessionId,
                                                  long requestId,
                                                  RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                                  String worldId,
                                                  String dimensionId,
                                                  RoadMapRoutePreloadPlan.CoverageMode coverageMode,
                                                  int completedTiles,
                                                  int totalTiles,
                                                  State state,
                                                  String message) {
    public enum State {
        QUEUED,
        SAMPLING,
        DERIVING,
        DEGRADED_PATH_ONLY,
        COMPLETE,
        FAILED,
        CANCELLED
    }
}
```

Use a tile sync packet with:

```java
public record RoadPlannerMapTileSyncPacket(UUID sessionId,
                                           long requestId,
                                           RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                           String worldId,
                                           String dimensionId,
                                           MapLod lod,
                                           int tileX,
                                           int tileZ,
                                           int pixelWidth,
                                           int pixelHeight,
                                           int[] argbPixels) {}
```

Use a cancel packet with:

```java
public record RoadPlannerMapPreloadCancelPacket(UUID sessionId,
                                                long requestId,
                                                RoadPlannerMapPreloadRequestPacket.Purpose purpose) {}
```

The pure `RoadPlannerMapPreloadJob` should:

- hold the ordered `RoadPlannerTileKey` list from `RoadMapRoutePreloadPlan.tileKeys(...)`
- advance through that queue in bounded batches
- sample `LOD_1` source tiles using `RoadMapSnapshotService` + `RoadMapServerColumnSampler`
- derive `LOD_2`/`LOD_4`/`LOD_8` display pixels using `RoadMapLodPyramid`
- emit one `RoadPlannerMapTileSyncPacket` per LOD per tile
- publish progress after each processed tile

The job should own a `Deque<RoadPlannerTileKey>` plus `advance(int maxTiles, TileSource source, TileSink sink)` and `progress()` methods. `TileSource` returns the `RoadMapSnapshot` for one `LOD_1` tile, and `TileSink` receives the derived packets that go back to the client.

The server singleton `RoadPlannerMapPreloadService` should:

- expose `onServerStarted(...)`, `onServerStopped()`, `global()`, `enqueue(...)`, `cancel(...)`, and `tick(ServerLevel level)`
- build entry-route plans from `start`/`destination` by calling `RoadPlannerPathfinderRunnerFactory.serverService(level)` and `RoadPlannerAutoCompleteService.complete(...)` when `routeNodes` is empty
- use the final `routeNodes` directly for `ROUTE_PRELOAD`
- use `start`/`destination` as the selection line for `FORCE_RENDER`
- replace older jobs for the same session/revision when a new request arrives
- send progress and tile packets back to the requesting player only
- construct `RoadMapRoutePreloadPlanner` with the spec defaults: `4096` max rectangle chunks, `4` rectangle padding chunks, `3` path padding chunks, and `8` blocks per route-sampling step

Keep `RoadMapSnapshotRequestPacket` / `RoadMapSnapshotSyncPacket` in place as fallback/manual snapshot plumbing; do not repurpose them in this task.

- [ ] **Step 4: Re-run the tests and confirm they pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest \
  --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadJobTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapPreloadRequestPacket.java \
        src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapPreloadProgressPacket.java \
        src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapTileSyncPacket.java \
        src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapPreloadCancelPacket.java \
        src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJob.java \
        src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java \
        src/main/java/com/monpai/sailboatmod/network/ModNetwork.java \
        src/main/java/com/monpai/sailboatmod/ServerEvents.java \
        src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java \
        src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJobTest.java
git commit -m "feat: add road planner preload packets and service"
```

---

## Task 4: Wire the Client Screen, Scheduler, and LOD Selection

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoutePreloadScheduler.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java`
- Create: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoutePreloadSchedulerTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

- [ ] **Step 1: Write the failing client behavior tests**

Add scheduler tests:

```java
class RoadPlannerRoutePreloadSchedulerTest {
    @Test
    void latestRequestReplacesPreviousRequestAndRejectsStaleResponses() {
        RoadPlannerRoutePreloadScheduler scheduler = new RoadPlannerRoutePreloadScheduler();
        RoadPlannerRoutePreloadScheduler.Request first = scheduler.entryRequest(
                UUID.randomUUID(),
                "world_a",
                "minecraft:overworld",
                new BlockPos(0, 64, 0),
                new BlockPos(256, 64, 0),
                List.of()).orElseThrow();
        RoadPlannerRoutePreloadScheduler.Request second = scheduler.forceRenderRequest(
                first.sessionId(),
                first.worldId(),
                first.dimensionId(),
                new BlockPos(0, 64, 0),
                new BlockPos(128, 64, 128)).orElseThrow();

        assertFalse(scheduler.acceptsResponse(first.sessionId(), first.requestId(), first.purpose(), first.worldId(), first.dimensionId()));
        assertTrue(scheduler.acceptsResponse(second.sessionId(), second.requestId(), second.purpose(), second.worldId(), second.dimensionId()));
    }
}
```

Update `RoadPlannerScreenBehaviorTest.java` to cover:

```java
@Test
void initSendsEntryPreloadAndZoomDoesNotCreateAnotherRequest() {
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720,
            new BlockPos(0, 64, 0), new BlockPos(256, 64, 0));
    screen.init();

    RoadPlannerMapPreloadRequestPacket first = screen.lastMapPreloadRequestForTest();
    assertEquals(RoadPlannerMapPreloadRequestPacket.Purpose.ENTER_PLANNER_PRELOAD, first.purpose());

    RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
    screen.mouseScrolled(map.x() + 50, map.y() + 50, 1.0D);

    assertSame(first, screen.lastMapPreloadRequestForTest());
}
```

```java
@Test
void autoCompleteResultTriggersRoutePreloadRequest() {
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
    screen.applyAutoCompleteResult(screen.state().sessionId(), true,
            List.of(new BlockPos(0, 64, 0), new BlockPos(256, 64, 0)),
            List.of(RoadPlannerSegmentType.ROAD),
            "done");

    assertEquals(RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD, screen.lastMapPreloadRequestForTest().purpose());
}
```

```java
@Test
void forceRenderSelectionTriggersForceRenderPreloadRequest() {
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
    RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();

    clickToolbarTool(screen, RoadToolType.FORCE_RENDER);
    screen.mouseClicked(map.x() + 40, map.y() + 40, 0);
    screen.mouseDragged(map.x() + 120, map.y() + 120, 0, 80, 80);
    screen.mouseReleased(map.x() + 120, map.y() + 120, 0);

    assertEquals(RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER, screen.lastMapPreloadRequestForTest().purpose());
}
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRoutePreloadSchedulerTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest
```

Expected: compilation failures because the scheduler, packet types, and screen hooks do not exist yet.

- [ ] **Step 3: Wire the screen, canvas, and fallback render path**

`RoadPlannerRoutePreloadScheduler` should expose:

```java
public final class RoadPlannerRoutePreloadScheduler {
    private long nextRequestId = 1L;
    private Request latestRequest;

    public Optional<Request> entryRequest(UUID sessionId,
                                          String worldId,
                                          String dimensionId,
                                          BlockPos start,
                                          BlockPos destination,
                                          List<BlockPos> routeNodes) {
        return Optional.of(recordRequest(sessionId, worldId, dimensionId,
                RoadPlannerMapPreloadRequestPacket.Purpose.ENTER_PLANNER_PRELOAD,
                start, destination, routeNodes));
    }

    public Optional<Request> routeRequest(UUID sessionId,
                                          String worldId,
                                          String dimensionId,
                                          List<BlockPos> routeNodes) {
        return Optional.of(recordRequest(sessionId, worldId, dimensionId,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                null, null, routeNodes));
    }

    public Optional<Request> forceRenderRequest(UUID sessionId,
                                                String worldId,
                                                String dimensionId,
                                                BlockPos start,
                                                BlockPos destination) {
        return Optional.of(recordRequest(sessionId, worldId, dimensionId,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                start, destination, List.of(start, destination)));
    }

    public boolean acceptsResponse(UUID sessionId,
                                   long requestId,
                                   RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                   String worldId,
                                   String dimensionId) {
        return latestRequest != null
                && latestRequest.sessionId().equals(sessionId == null ? new UUID(0L, 0L) : sessionId)
                && latestRequest.requestId() == requestId
                && latestRequest.purpose() == purpose
                && latestRequest.worldId().equals(worldId == null ? "" : worldId)
                && latestRequest.dimensionId().equals(dimensionId == null ? "" : dimensionId);
    }

    public Optional<Request> latestRequest() {
        return Optional.ofNullable(latestRequest);
    }

    private Request recordRequest(UUID sessionId,
                                  String worldId,
                                  String dimensionId,
                                  RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                  BlockPos start,
                                  BlockPos destination,
                                  List<BlockPos> routeNodes) {
        Request request = new Request(
                nextRequestId++,
                sessionId,
                worldId,
                dimensionId,
                purpose,
                start,
                destination,
                routeNodes);
        latestRequest = request;
        return request;
    }

    public record Request(long requestId,
                          UUID sessionId,
                          String worldId,
                          String dimensionId,
                          RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                          BlockPos start,
                          BlockPos destination,
                          List<BlockPos> routeNodes) {
        public RoadPlannerMapPreloadRequestPacket toPacket() {
            return new RoadPlannerMapPreloadRequestPacket(
                    sessionId,
                    requestId,
                    purpose,
                    worldId,
                    dimensionId,
                    start,
                    destination,
                    routeNodes,
                    RoadPlannerMapPreloadRequestPacket.PROTOCOL_VERSION);
        }

        public RoadPlannerMapPreloadCancelPacket toCancelPacket() {
            return new RoadPlannerMapPreloadCancelPacket(sessionId, requestId, purpose);
        }
    }
}
```

`RoadPlannerScreen` should:

- own a `RoadPlannerRoutePreloadScheduler`
- store the last request/progress/cancel packets in test mode for assertions
- expose test-only accessors named `lastMapPreloadRequestForTest()`, `lastMapPreloadProgressForTest()`, and `lastMapPreloadCancelForTest()`
- send `ENTER_PLANNER_PRELOAD` from `init()` when anchors are available
- send `ROUTE_PRELOAD` inside `applyAutoCompleteResult(...)`
- send `FORCE_RENDER` on force-render mouse release
- stop calling the old viewport-resample path from `tick()` and `mouseScrolled()`
- keep the local `renderPlayerAreaChunks(...)` / `processCorridorDirect(...)` fallback, but write those tiles into `LOD_1`
- add `applyMapPreloadProgress(...)` and `applyMapTileSync(...)`
- cancel the active request in `removed()`

`RoadPlannerMapCanvas.renderTiles(...)` should use:

```java
MapLod renderLod = RoadPlannerTileLodSelector.select(view.scale());
RoadPlannerTile tile = tileManager.resolveRenderableTile(tileX, tileZ, renderLod);
```

`RoadPlannerTileManager.getOrCreateTile(...)` and `RoadPlannerScreen.renderChunkDirect(...)` must explicitly use `MapLod.LOD_1` for the client-local fallback renderer.

`RoadPlannerScreen` should prefer server preload progress in the status bar:

- if the latest server progress belongs to the active request, show that percentage/message
- otherwise fall back to the local force-render queue progress

- [ ] **Step 4: Re-run the tests and confirm they pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRoutePreloadSchedulerTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoutePreloadScheduler.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoutePreloadSchedulerTest.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java
git commit -m "feat: wire road planner route preload into the client"
```

---

## Task 5: Final Verification and Jar Packaging

**Files:**
- No new code files.
- Verify the new plan with focused tests and the existing jarJar build.

- [ ] **Step 1: Run the focused verification test set**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test \
  --tests com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlannerTest \
  --tests com.monpai.sailboatmod.roadplanner.map.RoadMapLodPyramidTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManagerLodIsolationTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileLodSelectorTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRoutePreloadSchedulerTest \
  --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest \
  --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest \
  --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadJobTest
```

Expected: `BUILD SUCCESSFUL`.

Known unrelated baseline failures in the full suite still exist unless they are fixed separately:

- `RoadPlannerPreviewRequestPacketTest > placeholderPreviewUsesCompiledBuildStepsForBridgeDeckGhosts`
- `WeaverBridgeHighwayTest > bridgeBuilderKeepsDeckPositionsInsideRoadWidth`
- `WeaverSurfacePlacementTest > widthFivePaverEmitsCenterAndSidePositions`
- `CarriageRoutePlannerTest > planReturnsConnectorRoadConnectorSegments`

Do not spend time on those unless they regress because of this work.

- [ ] **Step 2: Compile the project**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Build the all-in-one jar**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false jarJar
```

Expected: `BUILD SUCCESSFUL` and a fresh jar at:

```text
build/libs/sailboatmod-1.3.7-all.jar
```

- [ ] **Step 4: Inspect the final artifact**

Confirm the jar timestamp and size:

```bash
ls -lh build/libs/sailboatmod-1.3.7-all.jar
stat -f '%Sm %z bytes %N' -t '%Y-%m-%d %H:%M:%S' build/libs/sailboatmod-1.3.7-all.jar
```

- [ ] **Step 5: Final commit if any last-minute fixups were needed**

If verification forces a last-minute code fix, commit it with the smallest possible follow-up commit message and keep the test/build commands in the commit body for traceability.
