# Road Planning Progress HUD Design

## Goal

Add a player-facing planning progress HUD for manual road planning so the player can see both:

- overall progress as a percentage
- the current planning stage in human-readable text

The progress display must work while async planning is running, remain visible even if the preview UI is closed, and disappear cleanly on success, failure, cancellation, or replacement by a newer request.

## Scope

This design covers manual road planning preview requests initiated through `ManualRoadPlannerService`.

It does not cover:

- road construction progress after a build job starts
- structure preview road links
- army route planning
- generic async task instrumentation across unrelated systems

## User Experience

When a player requests a manual road preview:

- a compact HUD progress bar appears near the top of the screen
- the HUD shows a stage label and percentage, for example `道路规划中: 采样地形 18%`
- progress advances using real server-side stage transitions
- within a stage, the client smooths motion between consecutive server updates so the bar does not look frozen
- if the player starts another manual planning request, the new request immediately replaces the old HUD
- if planning succeeds, the HUD reaches `生成预览 100%`, then clears and the normal preview result appears
- if planning fails or is cancelled, the HUD briefly shows the terminal stage/result and then clears

## Recommended Approach

Use a dedicated planning-progress state flow that is completely separate from:

- preview-result packets
- road construction progress packets
- road construction HUD state

This avoids semantic mixing between `planning` and `building`, keeps replacement logic simple, and prevents a planning request from overwriting an active road-build progress display.

## Architecture

### Server Components

- `ManualRoadPlannerService`
  - owns manual preview request submission
  - allocates a per-request planning id
  - emits planning progress updates as the async task advances
  - clears or finalizes progress when the request succeeds, fails, or is superseded

- `RoadPlanningTaskService`
  - continues to own latest-request suppression
  - remains the execution boundary for async compute
  - does not render or store UI policy, but accepts progress callbacks from the manual planner flow

- `ManualRoadPlanningProgressState`
  - lightweight immutable state model for a single HUD update
  - sent to the requesting player only

### Client Components

- `RoadPlannerClientHooks`
  - adds a dedicated planning-progress state store separate from existing road-build `ProgressState`
  - tracks only the latest active planning request for the local player
  - exposes read/update/clear methods for the renderer

- `SyncManualRoadPlanningProgressPacket`
  - dedicated packet for planning HUD updates
  - does not reuse `SyncRoadConstructionProgressPacket`

- `RoadPlannerPreviewRenderer`
  - extends the existing road-planning HUD renderer to draw the planning progress bar
  - uses the dedicated planning-progress state
  - shows one active planning request at a time

## Data Model

Introduce a planning-progress payload with these fields:

- `requestId`
  - monotonically increasing id for stale-update suppression on the client
- `sourceTownName`
- `targetTownName`
- `stageKey`
  - stable enum-like key such as `preparing`, `sampling_terrain`, `analyzing_island`, `trying_land`, `trying_bridge`, `building_preview`, `success`, `failed`, `cancelled`
- `stageLabel`
  - localized or localizable label rendered in the HUD
- `overallPercent`
  - integer `0..100`
- `stagePercent`
  - integer `0..100` for optional intra-stage interpolation
- `status`
  - `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED`

## Stage Model

Use these stage bands:

- `准备请求` 0-8
- `采样地形` 8-28
- `分析岛屿/桥头` 28-40
- `陆路尝试` 40-62
- `桥路尝试` 62-86
- `生成预览` 86-100

Terminal states:

- success: `生成预览 100%`
- failed: keep the last real stage label and set status `FAILED`
- cancelled: keep the last known progress and set status `CANCELLED`

The stage bands are intentionally coarse. They should reflect real work boundaries already present in the manual planner pipeline rather than inventing fake micro-steps.

## Data Flow

1. Player triggers manual preview planning.
2. `ManualRoadPlannerService` creates a new `requestId`.
3. Server immediately sends a `RUNNING` packet for `准备请求`.
4. Async planning starts through `RoadPlanningTaskService.submitLatest(...)`.
5. Manual planning code emits progress updates at stage boundaries and key milestones.
6. Client stores the newest planning state if its `requestId` is not stale.
7. HUD renders the stage text and progress bar from that state.
8. On success:
   - send final `SUCCESS` progress update
   - clear planning HUD state
   - send the normal preview packet
9. On failure or cancellation:
   - send terminal progress update
   - client keeps it visible briefly
   - client clears the planning HUD state after the hold window

## Replacement And Cancellation Rules

- Only one active planning HUD request exists per local player.
- A newer `requestId` always replaces an older one.
- If a request is superseded before completion:
  - the server stops emitting further progress for the old request
  - the client ignores any late packets for the old request
- If the player changes selected target or triggers a fresh preview request, the previous HUD is replaced immediately.

## Smoothing Rules

The client may interpolate only between two server-provided progress values for the same request and stage.

Rules:

- interpolation duration should stay short, around `200-350ms`
- interpolation must never move progress beyond the latest server value
- interpolation must never cross into the next stage before the server says that stage started
- if the server sends a terminal state, the client snaps to that state

This preserves truthful stage reporting while preventing a visually frozen bar.

## Error Handling

- If the async request fails before route generation finishes:
  - send `FAILED`
  - include the last meaningful stage label
- If the request is cancelled or superseded:
  - send `CANCELLED` only if that request is still current enough to matter to the client
- If the client receives malformed or missing progress fields:
  - ignore the update rather than corrupting HUD state
- If no fresh update arrives within a timeout window:
  - client clears the planning HUD state automatically

The timeout for planning HUD should be distinct from construction progress timeout, but can use a similar shape.

## Testing

Add tests for:

- latest planning request replaces the previous one
- stale `requestId` planning packets are ignored client-side
- planning stage order does not regress
- successful planning clears the HUD and still shows the preview
- failed planning clears after a short terminal hold window
- progress smoothing never exceeds the latest authoritative server value
- progress packets do not interfere with construction progress packets

Add targeted service tests for progress emission around:

- request submission
- stage transitions
- terminal success
- terminal failure
- superseded request cancellation

## File Impact

### New Files

- `src/main/java/com/monpai/sailboatmod/network/packet/SyncManualRoadPlanningProgressPacket.java`

### Modified Files

- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskService.java`
- `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
- `src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java`
- `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- tests in the corresponding service/client packet suites

## Non-Goals

- exact per-node search progress from the pathfinder
- a shared universal task-progress framework for all async systems
- replacing road construction progress HUD
- adding sound effects or toast notifications for stage transitions

## Implementation Notes

- The server should emit progress only at meaningful checkpoints; do not spam packets per node expansion.
- The stage model should be driven from real manual-planning boundaries already present in `buildPlanCandidates(...)` and the route-resolution helpers.
- The preview packet remains the source of truth for preview geometry; the planning-progress packet only drives HUD state.
