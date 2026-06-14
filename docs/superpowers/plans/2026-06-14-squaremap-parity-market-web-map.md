# Squaremap Parity Market Web Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the market web map terrain pipeline to match squaremap's lightweight rendering discipline while preserving Sailboat logistics, territory, flags, and market overlays.

**Architecture:** Terrain rendering is a server-side cache pipeline: HTTP only reads cached PNGs and enqueues bounded work, snapshots are acquired in small server-tick budgets, worker threads colorize immutable snapshots, and a single bounded ImageIO queue writes 512x512 square tiles plus legacy compatibility tiles. Sailboat overlays remain JSON/canvas/Leaflet layers above terrain and are not mixed into terrain PNGs.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Java ImageIO, existing market web `HttpServer`, local Leaflet assets already bundled under `src/main/resources/marketweb/vendor/leaflet`.

---

## File Structure

- Modify `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`
  - Lock hard invariants: HTTP tile handlers are public read-only cache reads; render/snapshot code must not use `ForgeChunkManager`, `forceChunk`, or `getChunkFuture(..., true)`.
- Modify `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderQueueTest.java`
  - Lock queue dedupe, priority replacement, same-tile coalescing, and bounded tile-miss region cursor behavior.
- Modify `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapChunkSnapshot.java`
  - Keep loaded chunk snapshot path.
  - Replace generated snapshot path's strong chunk scheduling with a fail-closed generated-tag check for this phase.
  - Add test-visible source markers so future mixin/accessor work is easy to audit.
- Modify `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java`
  - Reduce tile-miss fan-out.
  - Queue square tile misses as bounded chunk cursor work, not whole-region scans.
  - Keep per-tick snapshot and region cursor budgets small and explicit.
- Modify `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRegionScanService.java`
  - Keep `.mca` discovery lightweight.
  - Avoid loaded-chunk predicates that can touch/generate chunks.
- Modify `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRegionWatcher.java`
  - Fix comments so they describe background repair rather than forced loading.
- Later phase files:
  - Create `market/web/map/access/*` when a minimal accessor/mixin boundary is added.
  - Create `MarketWebMapRenderManager`, `MarketWebFullRender`, and resumable progress files when full render commands are implemented.

## Task 1: Source Invariant Tests

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`

- [ ] **Step 1: Write the failing invariant test**

Add or update `serverWebMapUsesGeneratedRegionSnapshotsLikeSquaremap` so it requires `captureGenerated` but rejects force-loading:

```java
assertTrue(snapshot.contains("captureGenerated"),
        "web map needs a generated-chunk snapshot path");
assertTrue(snapshot.contains("readGeneratedChunkTag"),
        "generated snapshot path should inspect saved chunk data before accepting work");
assertFalse(snapshot.contains("getChunkFuture"),
        "web map generated snapshots must not schedule chunk loads from map rendering");
assertFalse(snapshot.contains("true)\n                    .join()"),
        "web map must not use a blocking strong chunk load for generated tiles");
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.MarketWebAppResourceTest --console=plain
```

Expected: the test fails because current `MarketWebMapChunkSnapshot.captureGenerated` still contains `getChunkFuture`.

- [ ] **Step 3: Implement minimal production change**

Change `captureGenerated`:

```java
ChunkAccess loaded = level.getChunkSource().getChunk(chunkX, chunkZ, false);
if (loaded != null) {
    return Optional.of(captureLoaded(level, chunkX, chunkZ));
}
return readGeneratedChunkTag(level, new ChunkPos(chunkX, chunkZ))
        .filter(MarketWebMapChunkSnapshot::isFullChunkTag)
        .flatMap(tag -> Optional.empty());
```

This phase deliberately skips currently-unloaded chunks after proving they are generated instead of force-loading them. The next phase replaces `Optional.empty()` with NBT-backed snapshot decoding or a guarded accessor path.

- [ ] **Step 4: Verify GREEN**

Run the same test command. Expected: PASS for the source invariant.

## Task 2: Bounded Tile-Miss Queue

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderQueueTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java`

- [ ] **Step 1: Write the failing queue-budget test**

Add a test that enqueues a square tile miss and checks it creates a small cursor budget rather than a full-region burst:

```java
@Test
void squareTileMissQueuesOnlyBoundedVisibleWork() {
    MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(4096);
    int queued = MarketWebMapRenderService.enqueueSquareTileChunksForTest(
            queue,
            "minecraft:overworld",
            0,
            0,
            0,
            10L,
            (chunkX, chunkZ) -> chunkX >= 0 && chunkX < 4 && chunkZ >= 0 && chunkZ < 4);

    assertEquals(16, queued);
    assertEquals(16, queue.poll(64, 11L).size());
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.map.MarketWebMapRenderQueueTest --console=plain
```

Expected: compile fails until `enqueueSquareTileChunksForTest` exists.

- [ ] **Step 3: Implement bounded helper and use it from tile miss path**

Add `enqueueSquareTileChunksForTest(...)` with loaded-predicate filtering and make `enqueueSquareTileRequest(...)` enqueue tile-local cursor work instead of adding all affected regions.

- [ ] **Step 4: Verify GREEN**

Run the same queue test. Expected: PASS.

## Task 3: Remove Force-Load Language and Risky Comment Drift

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRegionWatcher.java`
- Modify: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`

- [ ] **Step 1: Add source assertion**

Assert comments and commands no longer describe forced loading:

```java
assertFalse(Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRegionWatcher.java"), StandardCharsets.UTF_8)
        .contains("强制加载"));
```

- [ ] **Step 2: Verify RED**

Run `MarketWebAppResourceTest`. Expected: fails while comment still says forced loading.

- [ ] **Step 3: Update comment text**

Replace the watcher comment with: "记录脏 region，后台按预算重绘已可安全读取的区块。"

- [ ] **Step 4: Verify GREEN**

Run `MarketWebAppResourceTest`. Expected: PASS.

## Task 4: Verification and Build Gate

**Files:**
- No new files unless earlier steps require compile fixes.

- [ ] **Step 1: Run targeted map tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.MarketWebAppResourceTest --tests com.monpai.sailboatmod.market.web.map.* --console=plain
```

Expected: selected tests pass.

- [ ] **Step 2: Compile Java**

```powershell
.\gradlew.bat compileJava --console=plain
```

Expected: exit code 0.

- [ ] **Step 3: Build jars if compile is clean**

```powershell
.\gradlew.bat build -x test --console=plain
```

Expected: main mod jar and market web jar are produced under `build/libs/`.

## Next Phase: Full Squaremap Equivalence

- Add isolated accessor package and optional mixin config only if Forge/Arclight testing confirms accessors do not conflict.
- Implement NBT-backed generated chunk snapshot decoding or a guarded storage-backed snapshot provider.
- Add resumable `fullrender start/pause/resume/cancel` and `radius` commands.
- Add progress persistence and command feedback for active workers, queued chunks, image writes, processed chunks, and processed regions.
- Keep overlay APIs unchanged: active logistics, territory primary/secondary colors, fixed flag frames, and market markers remain independent from terrain tiles.

## Self-Review Notes

- This plan covers the urgent server-lag issue by removing the current strong chunk scheduling path.
- It preserves existing map UI and overlay behavior.
- It keeps future squaremap parity work explicit instead of pretending the current partial renderer is already equivalent.
- It avoids DistanceManager or ticketing mixins, which is important for Arclight compatibility.
