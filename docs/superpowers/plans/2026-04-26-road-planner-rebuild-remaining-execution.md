# Road Planner Remaining Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the interactive RoadPlanner rebuild from Task 11 onward, including Elementa UI, real minimap rendering, multi-region editing, queued construction, rollback-ledger demolition, road graph topology, naming, tooltip, and jar validation.

**Architecture:** Keep the already implemented model/map/weaver/network foundations. Add client state/UI in `client.roadplanner`, construction and demolition services in `roadplanner.build`, and persistent editable topology in `roadplanner.graph`. All construction writes must record original block state and block entity NBT in a rollback ledger before changing the world; demolition restores from that ledger by default.

**Tech Stack:** Java 17, Forge 47.2.0, Minecraft 1.20.1, Elementa/UniversalCraft UI, Forge SimpleChannel packets, JUnit 5, Gradle. RoadWeaver references for map context menu/text input/render helpers are under `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury\common\src\main\java\net\shiroha233\roadweaver\client\map`.

---

## Execution Notes

- Worktree: `F:\Codex\sailboatmod\.worktrees\road-planner-rebuild`.
- Use `apply_patch` for edits.
- Use `$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'` before Gradle commands.
- Commit after each task.
- Existing full-suite caveat: `CarriageRoutePlannerTest > planReturnsConnectorRoadConnectorSegments()` has an unrelated NPE; do not fix unless explicitly asked.

## Completed Foundation

- Tasks 1-10 are implemented and committed through `8278815 Add road planner corridor map packets`.
- Rendering optimization and HTML review mockup are committed in `0886eec Optimize road planner minimap rendering`.
- Existing files to build on: `roadplanner.model`, `roadplanner.map`, `roadplanner.weaver`, `roadplanner.compile`, `network.packet.roadplanner`.

---

### Task 11: Rewire RoadPlannerItem To New Flow

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/item/RoadPlannerItem.java`
- Modify/Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerOpenScreenPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerDestinationServiceTest.java`

- [ ] **Step 1: Write failing destination tests**

Test `RoadPlannerDestinationService.fromBlock`, `fromCoordinates`, and `fromCurrentPlayerPosition` preserve exact immutable positions. Add a test for item action intent if `RoadPlannerItem` has testable helper extraction.

- [ ] **Step 2: Run red test**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerDestinationServiceTest"
```

Expected: missing test/helper behavior or no helper yet.

- [ ] **Step 3: Rewire item behavior**

Change `RoadPlannerItem` so the old planner entry no longer launches old manual path planning. It should keep the tool shell and trigger the new RoadPlanner session/open-screen packet path. Preserve existing registration and item identity.

- [ ] **Step 4: Run green test and compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerDestinationServiceTest"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 5: Commit**

Commit message: `Rewire road planner item entry`.

---

### Task 12: Elementa RoadPlanner Screen Skeleton

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClientState.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerToolbarModel.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerContextMenuModel.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClientStateTest.java`

- [ ] **Step 1: Write failing client state tests**

Test session open state, active tool switching (`ROAD`, `BRIDGE`, `TUNNEL`, `ERASE`, `SELECT`), active region index, selected width `3/5/7`, selected road edge id, and context menu actions list.

- [ ] **Step 2: Run red test**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerClientStateTest"
```

Expected: client state classes missing.

- [ ] **Step 3: Implement pure client state models**

Implement state classes without requiring Minecraft client initialization so tests remain fast. Context menu actions must include `RENAME_ROAD`, `EDIT_NODES`, `DEMOLISH_EDGE`, `DEMOLISH_BRANCH`, `CONNECT_TOWN`, and `VIEW_LEDGER`.

- [ ] **Step 4: Implement Elementa screen skeleton**

Create `RoadPlannerScreen` with toolbar, minimap placeholder, right info panel, bottom status/progress strip, and context menu hook. Keep rendering logic minimal; actual map texture is Task 13.

- [ ] **Step 5: Run green test and compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerClientStateTest"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 6: Commit**

Commit message: `Add road planner screen skeleton`.

---

### Task 13: Client Map Texture And Drawing

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTexture.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapComponent.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadStrokeSampler.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapComponentTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadStrokeSamplingTest.java`

- [ ] **Step 1: Write failing map component tests**

Test GUI/world conversion delegates to `RoadMapRegion`, drag pan changes requested region center only after scheduler threshold, and `RoadMapTileRenderCache` prevents duplicate uploads.

- [ ] **Step 2: Write failing stroke sampling tests**

Test dragging from GUI point A to B emits `RoadNode`s every `4-8` blocks using sampled surface Y.

- [ ] **Step 3: Run red tests**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerMapComponentTest" --tests "*RoadStrokeSamplingTest"
```

- [ ] **Step 4: Implement map texture wrapper**

Use Recruits-style `NativeImage`/`DynamicTexture` concepts, but keep testable logic separate. Only upload snapshots drained from `RoadMapTileRenderCache`.

- [ ] **Step 5: Implement map component and sampling**

Render cached region textures, road centerline, width preview, nodes, bridge/tunnel colors, corridor overlays, direction arrow, and tooltip anchor data.

- [ ] **Step 6: Run green tests and compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerMapComponentTest" --tests "*RoadStrokeSamplingTest"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 7: Commit**

Commit message: `Add road planner map drawing`.

---

### Task 14: Multi-Region Navigation And Corridor Preload

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerSessionService.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapPreloadQueue.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRegionNavigationPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerRegionNavigationTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapPreloadQueueTest.java`

- [ ] **Step 1: Write failing navigation tests**

Test next/previous region uses last node as exit point, preserves per-region strokes, and recalculates B+C corridor from last manual node to destination.

- [ ] **Step 2: Write failing preload queue tests**

Test priority order: `CURRENT`, `MANUAL_ROUTE`, `ROUGH_PATH`, `DESTINATION`, `NEIGHBOR`; test canceling old corridor job keeps newest request only.

- [ ] **Step 3: Run red tests**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerRegionNavigationTest" --tests "*RoadMapPreloadQueueTest"
```

- [ ] **Step 4: Implement navigation service methods**

Add `navigateToRegion`, `nextRegion`, `previousRegion`, `replaceRegionStrokes`, and `currentCorridorPlan` helpers.

- [ ] **Step 5: Implement preload queue model**

Pure queue first: no direct chunk loading in tests. Queue entries hold region, priority, world/dimension cache key, request id, and cancellation flag.

- [ ] **Step 6: Run green tests and compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerRegionNavigationTest" --tests "*RoadMapPreloadQueueTest"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 7: Commit**

Commit message: `Add road planner region navigation`.

---

### Task 15: Editable Road Graph, Naming, Tooltip, Context Menu

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadNetworkGraph.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphNode.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphEdge.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadRouteMetadata.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphHitTester.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadTooltipModel.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadNetworkGraphTest.java`

- [ ] **Step 1: Write failing graph tests**

Test adding nodes/edges creates a branch, edge length calculation, route/edge naming, town connections, creator storage, and selecting nearest edge by point distance.

- [ ] **Step 2: Write failing tooltip tests**

Test tooltip text includes name, connected town names, length, creator, width, road type, and status.

- [ ] **Step 3: Run red tests**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadNetworkGraphTest"
```

- [ ] **Step 4: Implement graph records and hit tester**

Graph must support tree/topology roads with multiple edges per node. Hit tester must return nearest edge within configurable pixel/world threshold.

- [ ] **Step 5: Implement tooltip/context menu model**

Borrow RoadWeaver context menu structure conceptually: action labels, enabled flags, separators. Do not port rendering into Elementa directly yet; expose model to screen.

- [ ] **Step 6: Run green tests and compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadNetworkGraphTest"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 7: Commit**

Commit message: `Add editable road graph model`.

---

### Task 16: Build Step Planner With Rollback Ledger

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildStep.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildStepPlanner.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadRollbackEntry.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadRollbackLedger.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildStepPlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/build/RoadRollbackLedgerTest.java`

- [ ] **Step 1: Write failing build planner tests**

Test compiled road output becomes visible road steps, invisible `AIR` clear-to-sky steps, edge material steps, and bridge deck steps.

- [ ] **Step 2: Write failing rollback ledger tests**

Test each build step records original `BlockState`, optional block entity NBT, road edge id, job id, actor id, and timestamp before placement.

- [ ] **Step 3: Run red tests**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadBuildStepPlannerTest" --tests "*RoadRollbackLedgerTest"
```

- [ ] **Step 4: Implement build step model and planner**

Convert `CompiledRoadPath.previewCandidates()` and section data into ordered build steps. Include `visible`, `phase`, `edgeId`, and `rollbackRequired=true` for all world writes.

- [ ] **Step 5: Implement rollback ledger model**

Ledger stores immutable entries and exposes reverse-order lookup by job id, edge id, and route id. It must not depend on live world for tests.

- [ ] **Step 6: Run green tests and compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadBuildStepPlannerTest" --tests "*RoadRollbackLedgerTest"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 7: Commit**

Commit message: `Add rollback aware road build steps`.

---

### Task 17: Build Queue, Persistence, And Ledger-Based Demolition

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildJob.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadDemolitionJob.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildQueueService.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildSavedData.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadDemolitionPlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildQueueServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/build/RoadDemolitionPlannerTest.java`

- [ ] **Step 1: Write failing queue tests**

Test enqueue, progress, cancel, and rollback state transitions.

- [ ] **Step 2: Write failing demolition tests**

Test demolition of one edge restores ledger entries in reverse order, branch demolition collects descendant edges, and missing/conflicting ledger entries create blocking or warning issues without overwriting player edits.

- [ ] **Step 3: Run red tests**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadBuildQueueServiceTest" --tests "*RoadDemolitionPlannerTest"
```

- [ ] **Step 4: Implement queue service**

Queue jobs execute in chunks per tick, expose progress, support cancellation, and store rollback ledger refs. Keep world-writing adapter separated from pure queue tests.

- [ ] **Step 5: Implement demolition planner**

Default strategy is B: restore original blocks from rollback ledger. If current world state differs from recorded placed state, mark conflict and do not overwrite unless future admin override is added.

- [ ] **Step 6: Implement saved data shell**

Persist jobs, graph metadata, and ledger entries via `SavedData` NBT-compatible records. Add encode/decode helpers that tests can call without server boot.

- [ ] **Step 7: Run green tests and compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadBuildQueueServiceTest" --tests "*RoadDemolitionPlannerTest"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 8: Commit**

Commit message: `Add road build queue demolition rollback`.

---

### Task 18: Confirm Build Flow And Editor Packets

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRenameRoadPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerDemolishRoadPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerGraphSyncPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerConfirmBuildPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerEditorPacketRoundTripTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/build/RoadPlannerConfirmBuildTest.java`

- [ ] **Step 1: Write failing packet tests**

Test rename road, demolish edge/branch, graph sync, and confirm build packet round trips.

- [ ] **Step 2: Write failing confirm build tests**

Test confirm build compiles plan, creates graph edges, creates rollback-required build job, and emits preview filtering that hides invisible `AIR` steps.

- [ ] **Step 3: Run red tests**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerEditorPacketRoundTripTest" --tests "*RoadPlannerConfirmBuildTest"
```

- [ ] **Step 4: Implement editor packets and register them**

Append new packet registrations after existing RoadPlanner packet IDs. Handlers must set packet handled and call service stubs safely.

- [ ] **Step 5: Implement confirm build service**

Create a service method that bridges `RoadPlanWeaverAdapter`, `RoadBuildStepPlanner`, `RoadNetworkGraph`, and `RoadBuildQueueService`.

- [ ] **Step 6: Run green tests and compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerEditorPacketRoundTripTest" --tests "*RoadPlannerConfirmBuildTest"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 7: Commit**

Commit message: `Add road planner editor packets`.

---

### Task 19: Bridge Backend Selector And Pier Bridge Adapter

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/BridgeBackend.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/BridgeBackendSelector.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/PierBridgeRampGrounding.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/bridge/PierBridgeToolAdapter.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/build/RoadBuildStepPlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/bridge/BridgeBackendSelectorTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/bridge/PierBridgeRampGroundingTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/bridge/PierBridgeToolAdapterTest.java`

- [ ] **Step 1: Write failing bridge backend tests**

Test small shallow water selects `ROADWEAVER_SIMPLE`, wide/deep water and deep canyon select `PIER_LARGE_BRIDGE`, and explicit Bridge Tool defaults to `PIER_LARGE_BRIDGE`.

- [ ] **Step 2: Write failing grounding tests**

Test ramp endpoints on solid grass/stone are valid; endpoints over water/air/leaves shift toward land up to `24` blocks; no valid surface returns blocking `RoadIssue`.

- [ ] **Step 3: Write failing adapter tests**

Test explicit bridge section generates pier, deck, ramp, railing build steps and marks clearing steps invisible.

- [ ] **Step 4: Run red tests**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*BridgeBackendSelectorTest" --tests "*PierBridgeRampGroundingTest" --tests "*PierBridgeToolAdapterTest"
```

- [ ] **Step 5: Implement selector, grounding, and adapter**

Preserve existing pier bridge geometry concepts while changing input to `CompiledRoadPath`/sections. Ramp endpoints must be validated before geometry generation.

- [ ] **Step 6: Run green tests and compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*BridgeBackendSelectorTest" --tests "*PierBridgeRampGroundingTest" --tests "*PierBridgeToolAdapterTest"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 7: Commit**

Commit message: `Add pier bridge backend adapter`.

---

### Task 20: Final Validation And Jar Build

**Files:**
- No source changes expected unless validation exposes defects.

- [ ] **Step 1: Run focused RoadPlanner tests**

Run all `roadplanner` tests plus packet/editor tests with explicit `--tests` filters.

- [ ] **Step 2: Run compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 3: Build jar**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat build
```

If the existing unrelated `CarriageRoutePlannerTest` still fails during `build`, report it clearly and run the largest RoadPlanner-focused validation set that excludes that known failure.

- [ ] **Step 4: Report artifacts**

List generated jars under `build/libs/`, exact validation commands, and any remaining known failures.

- [ ] **Step 5: Commit validation fixes if any**

Commit message depends on fixes. Do not commit generated jars.
