# Road Planner Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the new RoadPlanner system on `feature/road-planner-rebuild`: Elementa map UI, server-backed real map snapshots, multi-region manual route drawing, and RoadWeaver-derived route/geometry/build core executed through our queued rollback-safe construction service.

**Architecture:** Keep only the old road planner item shell and registrations. Port RoadWeaver MIT-licensed route creation, geometry, surface placement, bridge, highway, and structure-building modules into `com.monpai.sailboatmod.roadplanner.weaver`, then wrap them with our session, UI, packet, map snapshot, and build queue layers.

**Tech Stack:** Java 17, Forge 47.2.0 for Minecraft 1.20.1, Elementa/UniversalCraft, Forge SimpleChannel packets, JUnit 5, Gradle. RoadWeaver source reference: `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury`.

---

## Workspace

Work only in `F:\Codex\sailboatmod\.worktrees\road-planner-rebuild` on branch `feature/road-planner-rebuild`.

Baseline already passed: `./gradlew.bat compileJava`.

Design reference: `docs/superpowers/specs/2026-04-26-road-planner-rebuild-design.md`.

## RoadWeaver Source Modules To Port

Primary source root:

```text
F:\Codex\Ref\RoadWeaver-1.20.1-Architectury\common\src\main\java\net\shiroha233\roadweaver
```

Port/adapt these groups, preserving MIT notice in docs but do not add file headers unless requested:

- `util/Line.java`
- `core/model/RoadData.java`, `RoadSegmentPlacement.java`, `RoadSpan.java`, `SpanType.java`
- `pathfinding/cache/*`, `pathfinding/impl/SplineHelper.java`, `PathPostProcessor.java`, pathfinder interfaces/helpers as needed
- `features/path/pathlogic/core/RoadDirection.java`, `SegmentPaver.java`
- `features/path/pathlogic/pathfinding/RoadPathCalculator.java`, `HeightProfileService.java`, `RoadHeightInterpolator.java`, `BridgeTransitionAdjuster.java`
- `features/path/pathlogic/surface/AboveColumnClearer.java`, `PlacementRules.java`, `RoadBlockPlacer.java`, `SurfacePlacementUtil.java`
- `features/path/pathlogic/bridge/BridgeBuilder.java`, `BridgeRangeCalculator.java`, `BridgeSegmentPlanner.java`, `BridgeSegmentPlannerNew.java`
- `features/path/bridge/BridgeSegment.java`
- `features/highway/generation/HighwayHeightSmoother.java`, `HighwayRoad.java`
- `features/highway/pathfinding/*`
- `features/highway/placement/*`
- Structure-building concepts from `structures/precompute/*` and `structures/placement/*`, adapted into build tasks instead of worldgen injection.

Do not port platform bootstrap, Architectury entrypoints, mixins, commands, config UI, or unrelated worldgen registry code unless a task explicitly needs a small type.

## Package Layout

- `roadplanner.model`: session/stroke/control-node/settings records.
- `roadplanner.map`: region, LOD, sampled columns, snapshots, colorizer, server snapshot service.
- `roadplanner.service`: session and destination services.
- `roadplanner.weaver`: ported RoadWeaver core models, geometry, pathfinding, placement, bridge, highway, and structure adapters.
- `roadplanner.compile`: converts manual UI strokes to RoadWeaver-compatible inputs and compiled previews.
- `roadplanner.build`: build tasks, build job, queue service, rollback ledger, saved data.
- `client.roadplanner`: Elementa screen, client state, map texture/component, toolbar/control panel.
- `network.packet.roadplanner`: new planner packets.

---

### Task 1: Core Planner Model

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/model/*`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/model/RoadPlanModelTest.java`

- [ ] **Step 1: Write failing tests**

Test `RoadSettings` accepts only widths `3/5/7`, `RoadSegment` defensively copies strokes, and `RoadPlan.nodesInOrder()` flattens region strokes in order.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*RoadPlanModelTest"
```

Expected: model classes missing.

- [ ] **Step 3: Implement model records**

Create `NodeSource`, `RoadToolType`, `RoadNode`, `RoadStrokeSettings`, `RoadStroke`, `RoadSegment`, `RoadSettings`, `RoadPlan`, `RoadPlanningSession`. Add defaults and compact validation.

- [ ] **Step 4: Run green test**

```powershell
.\gradlew.bat test --tests "*RoadPlanModelTest"
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 2: RoadWeaver Core Model Port

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/model/WeaverRoadData.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/model/WeaverRoadSegmentPlacement.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/model/WeaverRoadSpan.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/model/WeaverSpanType.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/geometry/WeaverLine.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/weaver/WeaverCoreModelTest.java`

- [ ] **Step 1: Write failing tests**

Test `WeaverLine.getFrame` projects a point onto a segment and returns horizontal tangent/binormal; test `WeaverRoadData` stores placements/spans/targetY immutably.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*WeaverCoreModelTest"
```

Expected: weaver model classes missing.

- [ ] **Step 3: Port RoadWeaver model and geometry types**

Adapt from RoadWeaver `util/Line.java` and `core/model` records into our package names. Remove Codec dependencies unless needed by our persistence. Keep APIs small and testable.

- [ ] **Step 4: Run green test**

```powershell
.\gradlew.bat test --tests "*WeaverCoreModelTest"
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 3: Map Region, LOD, Snapshot Data

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/map/*`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapRegionTest.java`

- [ ] **Step 1: Write failing tests**

Test centered 128-block region bounds, `LOD_1/2/4.blocksPerPixel()`, pixel dimensions, `contains`, and GUI/world conversion.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*RoadMapRegionTest"
```

Expected: map classes missing.

- [ ] **Step 3: Implement region/snapshot records**

Create `MapLod`, `RoadMapRegion`, `RoadMapColumnSample`, `RoadMapSnapshot`.

- [ ] **Step 4: Run green test**

```powershell
.\gradlew.bat test --tests "*RoadMapRegionTest"
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 4: Server Map Snapshot Pipeline

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapColorizer.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapSnapshotWorker.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapSnapshotService.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapSnapshotServiceTest.java`

- [ ] **Step 1: Write failing tests**

Test water depth darkens water pixels, relief changes brightness, `sampleColumnsForTest` samples `pixelWidth * pixelHeight`, and `directExecutorForTest` builds snapshots synchronously.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*RoadMapSnapshotServiceTest"
```

Expected: snapshot service missing.

- [ ] **Step 3: Implement pure colorizer and worker**

Use recruits `ChunkImage` behavior as reference: map color, relief shading, water depth darkening. Worker must not reference `ServerLevel`.

- [ ] **Step 4: Implement server sampling method**

`requestSnapshot(ServerLevel, UUID, int, RoadMapRegion)` samples world state on caller/server thread and sends immutable samples to the worker.

- [ ] **Step 5: Run green test and compile**

```powershell
.\gradlew.bat test --tests "*RoadMapSnapshotServiceTest"
.\gradlew.bat compileJava
```

Expected: both successful.

---

### Task 5: Session And Destination Services

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerSessionService.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerDestinationService.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerSessionServiceTest.java`

- [ ] **Step 1: Write failing tests**

Test `startSession`, `getSession`, `setDestination`, and `replacePlan`.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*RoadPlannerSessionServiceTest"
```

Expected: services missing.

- [ ] **Step 3: Implement services**

Use `ConcurrentHashMap<UUID, RoadPlanningSession>`. Add helpers for block/current-position/coordinate destinations.

- [ ] **Step 4: Run green test**

```powershell
.\gradlew.bat test --tests "*RoadPlannerSessionServiceTest"
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 6: RoadWeaver Pathfinding And Height Port

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/pathfinding/*`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/terrain/*`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/weaver/WeaverPathHeightTest.java`

- [ ] **Step 1: Write failing tests**

Test spline/post-processing preserves first and last anchors; test height smoothing reduces a one-block spike without moving endpoints.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*WeaverPathHeightTest"
```

Expected: weaver path classes missing.

- [ ] **Step 3: Port RoadWeaver path/height modules**

Adapt `pathfinding/impl/SplineHelper.java`, `PathPostProcessor.java`, `features/path/pathlogic/pathfinding/HeightProfileService.java`, `RoadHeightInterpolator.java`, and `BridgeTransitionAdjuster.java`. Remove dependencies on RoadWeaver config services; use explicit parameters.

- [ ] **Step 4: Run green test**

```powershell
.\gradlew.bat test --tests "*WeaverPathHeightTest"
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 7: RoadWeaver Surface Placement Port

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/placement/*`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/weaver/WeaverSurfacePlacementTest.java`

- [ ] **Step 1: Write failing tests**

Test width `5` segment paver emits center and side positions, and clear-to-sky emits invisible `AIR` steps above the footprint.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*WeaverSurfacePlacementTest"
```

Expected: placement classes missing.

- [ ] **Step 3: Port placement modules**

Adapt RoadWeaver `SegmentPaver`, `RoadBlockPlacer`, `SurfacePlacementUtil`, `PlacementRules`, and `AboveColumnClearer` into weaver placement package. Output our neutral build candidates, not direct world writes.

- [ ] **Step 4: Run green test**

```powershell
.\gradlew.bat test --tests "*WeaverSurfacePlacementTest"
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 8: RoadWeaver Bridge And Highway Port

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/bridge/*`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/highway/*`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/weaver/WeaverBridgeHighwayTest.java`

- [ ] **Step 1: Write failing tests**

Test bridge range calculator detects a bridge-marked section from compiled centerline; test bridge builder keeps visible deck positions within width around centerline.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*WeaverBridgeHighwayTest"
```

Expected: bridge/highway classes missing.

- [ ] **Step 3: Port bridge modules**

Adapt `BridgeRangeCalculator`, `BridgeSegmentPlanner`, `BridgeSegmentPlannerNew`, `BridgeBuilder`, and `BridgeSegment`. Input must be compiled centerline/sections, never off-route raw scans.

- [ ] **Step 4: Port useful highway modules**

Adapt `HighwayHeightSmoother`, highway pathfinding interfaces, and highway placement utilities that help large/wide road generation. Keep config explicit and minimal.

- [ ] **Step 5: Run green test**

```powershell
.\gradlew.bat test --tests "*WeaverBridgeHighwayTest"
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 9: Manual Plan To RoadWeaver Adapter

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/compile/RoadPlanWeaverAdapter.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/compile/CompiledRoadPath.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/compile/CompiledRoadSection.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/compile/CompiledRoadSectionType.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/compile/RoadIssue.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/compile/RoadPlanWeaverAdapterTest.java`

- [ ] **Step 1: Write failing adapter tests**

Test one road stroke and one bridge stroke compile into RoadWeaver-compatible placements and typed compiled sections.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*RoadPlanWeaverAdapterTest"
```

Expected: adapter missing.

- [ ] **Step 3: Implement adapter**

Convert `RoadPlan` strokes to control paths, call ported RoadWeaver path/height/placement/bridge modules, and return `CompiledRoadPath` with centerline, sections, issues, and visible preview candidates.

- [ ] **Step 4: Run green test**

```powershell
.\gradlew.bat test --tests "*RoadPlanWeaverAdapterTest"
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 10: New Planner Packets And Network Registration

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/*.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`

- [ ] **Step 1: Write failing packet round-trip tests**

Cover snapshot request, snapshot sync, stroke update, region navigation, compiled preview, confirm build, job sync, and cancel job packets.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*RoadPlannerPacketRoundTripTest"
```

Expected: packets missing.

- [ ] **Step 3: Implement packet classes**

Follow existing static `encode/decode/handle` style. Handler stubs must set packet handled and call available services.

- [ ] **Step 4: Register packets**

Append registrations at end of `ModNetwork.register()`. Do not reorder existing IDs.

- [ ] **Step 5: Run green test and compile**

```powershell
.\gradlew.bat test --tests "*RoadPlannerPacketRoundTripTest"
.\gradlew.bat compileJava
```

Expected: both successful.

---

### Task 11: Rewire RoadPlannerItem

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/item/RoadPlannerItem.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerDestinationServiceTest.java`

- [ ] **Step 1: Write destination tests**

Test right-click block destination and coordinate destination exact positions.

- [ ] **Step 2: Run test**

```powershell
.\gradlew.bat test --tests "*RoadPlannerDestinationServiceTest"
```

Expected: green after Task 5 or red until helper exists.

- [ ] **Step 3: Remove old service entry**

Replace `ManualRoadPlannerService` calls. Normal use opens/resumes new planner if item has destination. Sneak use stores player position. `useOn` stores clicked block.

- [ ] **Step 4: Send open packet**

Use `OpenNewRoadPlannerPacket` with session id, start, destination, active region center, and settings.

- [ ] **Step 5: Compile**

```powershell
.\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 12: Elementa Screen Skeleton

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/*`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClientStateTest.java`

- [ ] **Step 1: Write client state test**

Test open session, active tool, region index, width selection.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*RoadPlannerClientStateTest"
```

Expected: client state missing.

- [ ] **Step 3: Implement state and Elementa shell**

Create `RoadPlannerClientState`, `RoadPlannerScreen`, `RoadPlannerToolbarComponent`, `RoadPlannerControlPanel`. Use `WindowScreen` like `MarketScreen`.

- [ ] **Step 4: Wire open packet client handler**

Set state and open `RoadPlannerScreen`.

- [ ] **Step 5: Run green test and compile**

```powershell
.\gradlew.bat test --tests "*RoadPlannerClientStateTest"
.\gradlew.bat compileJava
```

Expected: both successful.

---

### Task 13: Client Map Texture And Drawing

**Files:**
- Create/modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTexture.java`
- Create/modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapComponent.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapComponentTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadStrokeSamplingTest.java`

- [ ] **Step 1: Write tests**

Test GUI/world conversion through viewport and node sampling min distance `6`.

- [ ] **Step 2: Run red tests**

```powershell
.\gradlew.bat test --tests "*RoadPlannerMapComponentTest" --tests "*RoadStrokeSamplingTest"
```

Expected: map/drawing classes missing.

- [ ] **Step 3: Implement map texture/component**

Render server snapshot dynamic texture, loading state, region border, destination arrow, nodes, strokes, and compiled overlays.

- [ ] **Step 4: Wire mouse drawing**

Mouse down starts stroke, drag samples snapshot height, mouse up sends `UpdateRoadStrokePacket`.

- [ ] **Step 5: Run green tests and compile**

```powershell
.\gradlew.bat test --tests "*RoadPlannerMapComponentTest" --tests "*RoadStrokeSamplingTest"
.\gradlew.bat compileJava
```

Expected: both successful.

---

### Task 14: Multi-Region Navigation

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerSessionService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/NavigateRoadRegionPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerRegionNavigationTest.java`

- [ ] **Step 1: Write navigation tests**

Test next center from exit to straight and diagonal destinations.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*RoadPlannerRegionNavigationTest"
```

Expected: helper missing.

- [ ] **Step 3: Implement navigation**

Save current segment, use exit node, move region center toward destination, support previous region.

- [ ] **Step 4: Wire snapshot request**

Region navigation requests LOD snapshot for new active region.

- [ ] **Step 5: Run green test and compile**

```powershell
.\gradlew.bat test --tests "*RoadPlannerRegionNavigationTest"
.\gradlew.bat compileJava
```

Expected: both successful.

---

### Task 15: Build Task Planner From RoadWeaver Output

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/*`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildStepPlannerTest.java`

- [ ] **Step 1: Write failing test**

Test RoadWeaver output creates `CLEAR_SKY`, `ROAD_SURFACE`, and `BRIDGE` tasks; `AIR` steps are invisible.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*RoadBuildStepPlannerTest"
```

Expected: build planner missing.

- [ ] **Step 3: Implement build records**

Create `RoadBuildTaskType`, `RoadBuildStep`, `RoadBuildTask`, and `RoadBuildStepPlanner`.

- [ ] **Step 4: Convert RoadWeaver candidates to tasks**

Surface placement, sky clearing, bridge geometry, tunnel placeholders, and structure/decor candidates become typed queued tasks.

- [ ] **Step 5: Run green test**

```powershell
.\gradlew.bat test --tests "*RoadBuildStepPlannerTest"
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 16: Build Queue, Rollback, Persistence

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildStatus.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RollbackLedger.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildJob.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildQueueService.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildSavedData.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildServerEvents.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildQueueServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildSavedDataTest.java`

- [ ] **Step 1: Write tests**

Test execution records original state and rollback restores it. Test one job round-trips through NBT.

- [ ] **Step 2: Run red tests**

```powershell
.\gradlew.bat test --tests "*RoadBuildQueueServiceTest" --tests "*RoadBuildSavedDataTest"
```

Expected: queue/persistence missing.

- [ ] **Step 3: Implement queue and ledger**

Execute limited steps per tick, record original states before writes, rollback in reverse order, skip missing snapshots rather than writing air.

- [ ] **Step 4: Implement saved data and tick hook**

Persist job id, plan id, status, active task, tasks, steps, and rollback ledger. Server tick advances jobs.

- [ ] **Step 5: Run green tests and compile**

```powershell
.\gradlew.bat test --tests "*RoadBuildQueueServiceTest" --tests "*RoadBuildSavedDataTest"
.\gradlew.bat compileJava
```

Expected: both successful.

---

### Task 17: Confirm Build Flow And Preview Filtering

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerSessionService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/ConfirmRoadBuildPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/SyncCompiledRoadPreviewPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerConfirmBuildTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/CompiledRoadPreviewPacketTest.java`

- [ ] **Step 1: Write tests**

Test session plan confirms into a non-empty build job. Test compiled preview packet filters `Blocks.AIR`.

- [ ] **Step 2: Run red tests**

```powershell
.\gradlew.bat test --tests "*RoadPlannerConfirmBuildTest" --tests "*CompiledRoadPreviewPacketTest"
```

Expected: confirm/filter missing.

- [ ] **Step 3: Implement confirm flow**

Session -> RoadWeaver adapter -> compiled path -> build tasks -> queued job -> job sync packet.

- [ ] **Step 4: Implement preview packet filtering**

Never expose `state.isAir()` as visible preview geometry.

- [ ] **Step 5: Run green tests and compile**

```powershell
.\gradlew.bat test --tests "*RoadPlannerConfirmBuildTest" --tests "*CompiledRoadPreviewPacketTest"
.\gradlew.bat compileJava
```

Expected: both successful.

---

### Task 18: Full Validation And Jar Build

**Files:**
- No source changes expected unless validation exposes defects.

- [ ] **Step 1: Run focused tests**

```powershell
.\gradlew.bat test --tests "*RoadPlanModelTest" --tests "*WeaverCoreModelTest" --tests "*RoadMapRegionTest" --tests "*RoadMapSnapshotServiceTest" --tests "*RoadPlannerSessionServiceTest" --tests "*WeaverPathHeightTest" --tests "*WeaverSurfacePlacementTest" --tests "*WeaverBridgeHighwayTest" --tests "*RoadPlanWeaverAdapterTest" --tests "*RoadPlannerPacketRoundTripTest" --tests "*RoadPlannerClientStateTest" --tests "*RoadPlannerMapComponentTest" --tests "*RoadStrokeSamplingTest" --tests "*RoadPlannerRegionNavigationTest" --tests "*RoadBuildStepPlannerTest" --tests "*RoadBuildQueueServiceTest" --tests "*RoadBuildSavedDataTest" --tests "*RoadPlannerConfirmBuildTest" --tests "*CompiledRoadPreviewPacketTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Compile**

```powershell
.\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Build jar**

```powershell
.\gradlew.bat build
```

Expected: `BUILD SUCCESSFUL`; jars under `build/libs/`.

- [ ] **Step 4: Manual in-game checklist**

Run `./gradlew.bat runClient` and verify: item opens new Elementa screen, right-click stores destination, real map snapshot appears, drawing works, width 3/5/7 changes footprint, next region continues from exit node, bridge tool marks bridge section, RoadWeaver-derived placement creates valid tasks, confirm creates queued job, job builds progressively, cancel rolls back, tree canopy clears, preview never shows `AIR` boxes.

## Self-Review

- Spec coverage: destination, Elementa UI, server snapshots, LOD, multi-region planning, manual drawing, RoadWeaver route/geometry/surface/bridge/highway port, queued build, rollback, preview filtering, and old entry replacement are covered.
- Deliberate deferrals: full automatic RoadWeaver UI, NPC worker integration, builder hammer integration, advanced decoration tuning, and persistent map tile cache.
- Safety: do not edit original workspace; do not reorder old packet IDs; background workers do not read `ServerLevel`; old planner classes are not deleted; RoadWeaver platform/bootstrap/mixins are not blindly copied.

## Addendum: Preserve Existing Pier Bridge Builder

The implementation plan must treat the current pier-supported large bridge builder as the backend for explicit Bridge Tool strokes. RoadWeaver bridge modules are still ported for automatic/simple crossings, but explicit bridge drawing uses our existing pier bridge system through an adapter.

### Additional Task: Explicit Pier Bridge Tool Adapter

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/PierBridgeToolAdapter.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildStepPlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/bridge/PierBridgeToolAdapterTest.java`

- [ ] **Step 1: Write failing adapter tests**

Test a Bridge Tool section creates pier, deck, ramp, and railing build tasks using the preserved large bridge geometry. Assert visible bridge positions stay within the selected width around the player-drawn centerline, and `AIR` clearing steps are marked invisible.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*PierBridgeToolAdapterTest"
```

Expected: adapter missing.

- [ ] **Step 3: Implement adapter around existing bridge system**

Use the current pier-supported bridge/deck/ramp/railing generation code as the geometry backend. The adapter input is the new compiled Bridge Tool section, not old pathfinding output.

- [ ] **Step 4: Integrate build planner**

`RoadBuildStepPlanner` routes explicit `CompiledRoadSectionType.BRIDGE` sections created by the Bridge Tool to `PierBridgeToolAdapter`. RoadWeaver automatic/simple bridge sections use the RoadWeaver bridge adapter.

- [ ] **Step 5: Run bridge adapter and planner tests**

```powershell
.\gradlew.bat test --tests "*PierBridgeToolAdapterTest" --tests "*RoadBuildStepPlannerTest"
```

Expected: `BUILD SUCCESSFUL`.

### Additional Task: Automatic Bridge Backend Selector

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/BridgeBackend.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/BridgeBackendSelector.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildStepPlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/bridge/BridgeBackendSelectorTest.java`

- [ ] **Step 1: Write failing selector tests**

Test shallow/short water selects `ROADWEAVER_SIMPLE`, while wide/deep water and deep canyon select `PIER_LARGE_BRIDGE`. Test explicit Bridge Tool sections select `PIER_LARGE_BRIDGE` by default.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*BridgeBackendSelectorTest"
```

Expected: selector missing.

- [ ] **Step 3: Implement bridge backend enum and selector**

Create `BridgeBackend { ROADWEAVER_SIMPLE, PIER_LARGE_BRIDGE }`. Selector inputs should include section tool source, span length, water depth, canyon depth, and configured thresholds.

Default thresholds:

```text
simpleMaxSpan = 16 blocks
simpleMaxWaterDepth = 3 blocks
simpleMaxCanyonDepth = 4 blocks
```

If any value exceeds the simple threshold, choose `PIER_LARGE_BRIDGE`.

- [ ] **Step 4: Integrate build planner**

Automatic bridge sections call `BridgeBackendSelector`; `ROADWEAVER_SIMPLE` routes to RoadWeaver bridge adapter, `PIER_LARGE_BRIDGE` routes to `PierBridgeToolAdapter`. Explicit Bridge Tool sections default to `PIER_LARGE_BRIDGE`.

- [ ] **Step 5: Run selector and bridge tests**

```powershell
.\gradlew.bat test --tests "*BridgeBackendSelectorTest" --tests "*PierBridgeToolAdapterTest" --tests "*WeaverBridgeHighwayTest"
```

Expected: `BUILD SUCCESSFUL`.

### Additional Task: Pier Bridge Ramp Grounding Validator

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/PierBridgeRampGrounding.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/PierBridgeToolAdapter.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/bridge/PierBridgeRampGroundingTest.java`

- [ ] **Step 1: Write failing grounding tests**

Test ramp endpoints on solid grass/stone are accepted. Test endpoints over water, air, or leaves are rejected or shifted along the bridge centerline to the nearest valid surface within the extension limit. Test no valid surface creates a blocking `RoadIssue`.

- [ ] **Step 2: Run red test**

```powershell
.\gradlew.bat test --tests "*PierBridgeRampGroundingTest"
```

Expected: grounding validator missing.

- [ ] **Step 3: Implement stable surface predicate**

`PierBridgeRampGrounding` exposes a testable predicate that treats solid non-liquid blocks as valid and rejects air, water, leaves, replaceable plants, and unstable columns.

- [ ] **Step 4: Implement endpoint search**

Search from the drawn bridge endpoint along the centerline toward the nearest land approach for up to `maxRampGroundingExtension` blocks. Default: `24` blocks. Return grounded endpoint, surface Y, and issue status.

- [ ] **Step 5: Integrate bridge adapter**

`PierBridgeToolAdapter` must call the grounding validator before generating ramp/deck geometry. If either endpoint is invalid and cannot be grounded, return a blocking compile/build issue instead of generating a floating bridge.

- [ ] **Step 6: Run grounding and bridge adapter tests**

```powershell
.\gradlew.bat test --tests "*PierBridgeRampGroundingTest" --tests "*PierBridgeToolAdapterTest"
```

Expected: `BUILD SUCCESSFUL`.
