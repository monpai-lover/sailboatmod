# Editable Road Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make built roads selectable and editable, preserve terrain through a road block ledger, and prevent duplicate town-to-town road construction.

**Architecture:** Keep `RoadNetworkRecord` as the existing public road index and add editable-road ledger data beside it. New and legacy roads can produce ledger entries; edit submissions compute old/new footprints, then a tick-budgeted task releases old blocks, keeps shared blocks, and places new blocks without overwriting player edits.

**Tech Stack:** Java 17, Forge 1.20.1, Minecraft `SavedData` NBT persistence, existing Forge `SimpleChannel` packets, JUnit 5, Gradle.

---

## Execution Notes

- Work from `F:\Codex\sailboatmod`.
- Do not edit upstream reference repositories.
- Keep unrelated dirty files unstaged.
- Prefer focused tests first; use `compileJava` where Minecraft client classes are not practical to instantiate in unit tests.

## Tasks

### Task 1: Road Planner Menu Actions

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/RoadPlannerActionMenuScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMenuActionPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`

- [ ] Add packet round-trip coverage for `OPEN_TARGET_SELECTION` and `OPEN_EDIT_ROAD_SELECTION`.
- [ ] Verify the packet test fails because the enum values do not exist.
- [ ] Add enum values and server handling.
- [ ] Add `ManualRoadPlannerService.openTargetSelectionFromHeldPlanner(ServerPlayer)`.
- [ ] Wire the existing target button to `OPEN_TARGET_SELECTION`.
- [ ] Add the main menu button `编辑现有道路` wired to `OPEN_EDIT_ROAD_SELECTION`.
- [ ] Run the packet test and commit the focused change.

### Task 2: Shared Road Edit Permissions

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/edit/RoadEditPermissionService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadDemolitionService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeService.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/edit/RoadEditPermissionServiceTest.java`

- [ ] Add tests for creator, town mayor/manager path, nation official path, operator, and unrelated player denial.
- [ ] Verify tests fail before implementation.
- [ ] Implement shared permission logic and replace duplicated demolition/merge checks.
- [ ] Run the focused permission tests and existing merge tests.

### Task 3: Road Endpoint Town Metadata

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/model/RoadNetworkRecord.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/model/RoadNetworkRecordTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistryTest.java`

- [ ] Add save/load tests for `routeSourceTownId` and `routeTargetTownId`.
- [ ] Verify model tests fail before changing the record.
- [ ] Add optional town-id fields with backward-compatible constructors and NBT keys.
- [ ] Propagate source/target town IDs into manual road records when available.
- [ ] Run focused model and built-road registry tests.

### Task 4: Editable Road Ledger Data

**Files:**
- Create files under `src/main/java/com/monpai/sailboatmod/roadplanner/edit/`.
- Test files under `src/test/java/com/monpai/sailboatmod/roadplanner/edit/`.

- [ ] Add tests for `RoadEditableRecord`, nodes, segments, ledger entries, and `RoadEditableNetworkSavedData` NBT round trips.
- [ ] Verify tests fail because classes are absent.
- [ ] Implement immutable records and saved-data indexes by road ID, town pair, and block position.
- [ ] Run ledger model tests.

### Task 5: Ledger Creation And Legacy Migration

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/edit/RoadEditableMigrationService.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/edit/RoadEditableMigrationServiceTest.java`

- [ ] Add tests proving new completed roads create ledger entries from build steps and legacy roads migrate only once.
- [ ] Verify tests fail before implementation.
- [ ] Generate ledger records from completed construction data.
- [ ] Implement legacy migration from current `RoadNetworkRecord`/graph data and current world block state.
- [ ] Run focused migration and registry tests.

### Task 6: Road Edit Selection And Duplicate Routing

**Files:**
- Create edit selection packets and `RoadPlannerEditSelectionScreen`.
- Modify `RoadPlannerMenuActionPacket`, `RoadPlannerClientHooks`, and planning services.

- [ ] Add packet round-trip tests for edit selection entries.
- [ ] Add service tests for A-B duplicate detection.
- [ ] Verify tests fail before implementation.
- [ ] List editable roads for the current player and open selection UI.
- [ ] Route A-B planning into edit mode when an existing editable road is found; deny duplicate construction if the player lacks permission.
- [ ] Run focused network and service tests.

### Task 7: Edit Diff And Budgeted Execution

**Files:**
- Create: `RoadEditDiff`, `RoadEditDiffPlanner`, `RoadEditTaskService`, `RoadEditCommitService`.
- Modify: `ServerEvents.java`.

- [ ] Add diff planner tests for removed, kept, added, empty, and conflicted sets.
- [ ] Add task-service tests using a small block-access harness for ref-count release, player-modified block protection, and per-tick budget limits.
- [ ] Verify tests fail before implementation.
- [ ] Implement diff planning and budgeted persisted edit jobs.
- [ ] Tick the service from server tick and clear runtime state on server stop.
- [ ] Run focused edit tests.

### Task 8: Road Planner Edit UI

**Files:**
- Modify: `RoadPlannerClientHooks.java`
- Modify: `RoadPlannerScreen.java`
- Create or extend client behavior tests where practical.

- [ ] Add client behavior tests or compile-level assertions for loading existing nodes and sending edit commit packets.
- [ ] Verify tests fail or `compileJava` fails before implementation.
- [ ] Add edit-mode factory/state, node loading, commit submission, and diff overlay colors.
- [ ] Run focused tests or `compileJava`.

### Task 9: Refresh Caches And Final Verification

**Files:**
- Modify road edit task completion, built-road overlay refresh, route cache, and market shipment cache touchpoints as needed.

- [ ] Add tests or focused compile checks for completion refresh.
- [ ] Refresh road overlays, shared map indexes, automatic routes, and market/transport route caches after edit completion.
- [ ] Run focused road planner tests.
- [ ] Run `.\gradlew.bat build`.
- [ ] Report manual in-game checks still required.
