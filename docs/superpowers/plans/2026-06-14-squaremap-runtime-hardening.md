# Squaremap Runtime Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the market web map runtime closer to squaremap's large-world behavior by improving render progress, dirty render budgeting, configuration, and queue stability.

**Architecture:** Keep the existing Sailboat web map overlay APIs unchanged. Harden the terrain backend by replacing fullrender's eager all-chunk list with a streaming region cursor, adding a persistent dirty-region queue that is expanded into dirty chunks by budget, making hardcoded render budgets configurable, and surfacing richer status fields for operators.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, existing `MarketWebMapRenderManager`, `MarketWebMapRenderService`, and `ModConfig`.

---

### Task 1: Config Contract

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/ModConfig.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`

- [ ] Add failing resource assertions for new `webMapPartialRegionFlushChunks`, `webMapMaxTrackedRegionStates`, `webMapMaxDirtyChunks`, `webMapDirtyRegionChunksPerInterval`, `webMapProgressSaveIntervalChunks`, `webMapImageIoBacklogWarningThreshold`, `webMapSnapshotTasksPerTick`, and `webMapSameTileBurstLimit`.
- [ ] Add config fields and getter methods with conservative defaults.
- [ ] Make render code reference getters instead of hardcoded constants.
- [ ] Run the resource test and compile.

### Task 2: Streaming Full Render Progress

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`

- [ ] Add failing source assertions that `RenderJob` no longer stores `List<ChunkCoordinate> chunks` for fullrender and persists `regionIndex`/`localChunkIndex`.
- [ ] Replace eager fullrender chunk expansion with region cursor progress.
- [ ] Save progress every configurable chunk interval.
- [ ] Run the resource test and map tests.

### Task 3: Dirty Region Budgeting

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapDirtyRegionQueue.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderManager.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapDirtyRegionQueueTest.java`

- [ ] Add failing tests for dirty-region dedupe, budgeted expansion to chunk coordinates, and JSON save/load.
- [ ] Implement persistent dirty region queue.
- [ ] Change region watcher events to mark dirty regions instead of immediately marking 1024 dirty chunks.
- [ ] Expand dirty regions to dirty chunks by configurable budget in background tick.
- [ ] Run dirty queue tests and map tests.

### Task 4: Stability Status And Bounds

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderManager.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebSquareMapImageIOExecutor.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`

- [ ] Add failing assertions for richer status fields: dirty regions, current region, current local chunk, failed chunks, and bounded tracked regions.
- [ ] Add status fields and JSON output.
- [ ] Bound tracked region states by config to prevent long-lived memory growth.
- [ ] Make ImageIO backlog threshold configurable and warn when exceeded.
- [ ] Run market web tests, compile, and build.
