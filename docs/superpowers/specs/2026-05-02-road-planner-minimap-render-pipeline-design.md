---
date: 2026-05-02
topic: road-planner-minimap-render-pipeline
---

# Road Planner Minimap Render Pipeline Design

## Outcome

Fix the road planner minimap so it renders terrain when the planner opens and when the player uses force render. The visible symptoms are:

- entering the planner leaves the minimap as the gray loading checkerboard
- force render selection does not visibly update the map or progress in useful cases

The fix must make the minimap update for the current planner viewport, including route areas that are not already loaded by the client.

## Current Evidence

Runtime code on `feature/road-planner-rebuild` shows the minimap is still mostly client-local:

- `RoadPlannerScreen.tick()` calls `renderPlayerAreaChunks(6)` and `processCorridorDirect(4)`.
- `renderPlayerAreaChunks` scans around `mc.player.blockPosition()`, not around the current `mapView` center.
- `renderChunkDirect` returns false unless `mc.level.hasChunk(cp.x, cp.z)` is true.
- `RoadPlannerScreen.applyTownRoute` recenters `mapView` to the midpoint between start and destination towns, which is often far from the player.
- `RoadMapSnapshotRequestPacket.handle()` and `RoadMapSnapshotSyncPacket.handle()` currently only mark packets handled; they do not build, send, or apply a snapshot.

This explains both confirmed symptoms: the initial view can stay gray because it is not near loaded player chunks, and force render can do nothing when the selected chunks are not loaded on the client.

## Goals

1. Open the planner with a real terrain minimap for the current route viewport.
2. Make panning and zooming request updated terrain for the visible map area.
3. Make force render work for selected areas even when those chunks are not loaded on the client.
4. Keep UI responsive and avoid loading or rendering too much data per frame.
5. Preserve local tile caching by world and dimension so already-rendered areas reappear quickly.
6. Add tests around request scheduling, packet handling, stale-result rejection, and tile/progress state.

## Non-Goals

- Do not rewrite the whole planner UI.
- Do not replace the road drawing, bridge tools, draft persistence, or build confirmation flow.
- Do not force-load large chunk ranges indefinitely.
- Do not make final art or color styling changes beyond what is needed to display terrain snapshots.

## Recommended Approach

Use the existing server snapshot model instead of relying on client-loaded chunks.

The client sends `RoadMapSnapshotRequestPacket` for the current visible planner region. The server samples the requested region using server world data and sends `RoadMapSnapshotSyncPacket` back. The client applies the snapshot into `RoadPlannerTileManager` and marks affected tiles dirty so the next render uploads new texture data.

This is preferred over client-only fixes because route planning often targets distant towns outside the player's render distance. It also avoids using long-lived forced chunk loads only to draw the UI.

## Architecture

### Client: `RoadPlannerScreen`

`RoadPlannerScreen` owns the viewport and decides when a map snapshot is needed.

It should request a snapshot when:

- the screen is initialized
- the route anchors are applied and the map view is centered
- panning or zooming moves the visible region enough to cross a request threshold
- force render selection is released
- the player manually requests a refresh, if an existing action is reused

The screen tracks a monotonically increasing `mapRequestId` or revision. Each request includes enough viewport identity to reject stale responses. Stale means a response belongs to an older session, world, dimension, request revision, or an obviously outdated visible region.

The existing client-local chunk rendering can remain as a quick fallback for loaded chunks, but it must not be the only path. It should not block server snapshots.

### Client: Request Scheduler

Add a small request scheduler owned by `RoadPlannerScreen` or extracted as `RoadPlannerMinimapRequestScheduler`.

Responsibilities:

- compute the visible world rectangle from `mapView` and `mapLayout.map()`
- snap requests to stable chunk/tile boundaries to reduce duplicate requests while panning
- throttle panning/zooming requests, for example one request every 250-500 ms
- allow immediate high-priority requests for initial open and force render
- expose progress state for the status bar

### Network Packets

Extend or reuse `RoadMapSnapshotRequestPacket`.

Required request fields:

- `sessionId`
- `worldId`
- `dimensionId`
- center or bounds of requested world region
- `regionSize` or width/height in blocks
- `lod`
- request purpose: initial viewport, viewport refresh, force render
- client request revision

`RoadMapSnapshotSyncPacket` should carry matching identity fields plus pixels. If the current packet shape is kept, add only the smallest needed revision/request fields. If packet compatibility is not a concern inside the dev branch, updating the record is acceptable.

### Server: Snapshot Handling

`RoadMapSnapshotRequestPacket.handle()` becomes the server entrypoint.

Flow:

1. Validate sender and server level.
2. Clamp requested region size and LOD to safe limits.
3. Build a `RoadMapRegion` from the request.
4. Use `RoadMapSnapshotService` with a server-safe `RoadMapColumnSampler`.
5. Send `RoadMapSnapshotSyncPacket` back only to the requesting player.

The sampler must read server world state, not client world state. It should avoid forcing huge chunk loads. If a column cannot be sampled because the chunk is not available or the request is outside safe bounds, it returns a placeholder/unknown sample rather than stalling the server.

### Client: Snapshot Application

`RoadMapSnapshotSyncPacket.handle()` becomes the client entrypoint.

Flow:

1. Enqueue work on the client thread.
2. Confirm the active screen is `RoadPlannerScreen` for the same `sessionId`.
3. Confirm world/dimension/request revision still matches.
4. Convert snapshot pixels into the matching `RoadPlannerTile` region.
5. Mark tile textures dirty and save tile PNGs under the current world/dimension cache.
6. Update progress/status text.

This probably belongs in `RoadPlannerTileManager`, for example `applySnapshot(...)`, to keep image/tile mutation out of the screen.

### UI Progress

The status bar should show map-render progress even when the compact inspector is hidden.

Recommended states:

- `地图: 等待请求`
- `地图: 请求中`
- `地图: 接收中`
- `地图: 已更新`
- `地图: 部分区域不可用`
- `地图: 失败，点击/强制渲染重试`

Force render progress should be based on server snapshot request completion, not only on local chunk polling. The old `RoadPlannerForceRenderQueue` can remain for local chunk fallback, but force-render UI must not report no movement when a server snapshot is in flight.

## Data Flow

### Initial Open

1. Server opens planner with start/destination anchors.
2. Client creates `RoadPlannerScreen` and centers `mapView` on the route midpoint.
3. `init()` or first `tick()` schedules an immediate viewport snapshot request.
4. Server samples and sends snapshot.
5. Client applies snapshot to tiles and the gray checkerboard is replaced by terrain.

### Pan/Zoom

1. Player pans or zooms.
2. Scheduler computes the snapped visible region.
3. If the visible request key changed and throttle allows it, send a new request.
4. Client keeps rendering existing tiles while waiting.
5. New snapshot patches tiles when received.

### Force Render

1. Player selects the force-render tool and drags a rectangle.
2. On mouse release, clear local submitted state and send a high-priority snapshot request covering the selection.
3. Status bar shows the request as active.
4. Server returns the selection snapshot.
5. Client applies it and marks the task complete.

## Error Handling

- Invalid or missing sender: mark handled and do nothing.
- Oversized request: clamp dimensions and LOD.
- Wrong dimension/world: reject client-side or return no data.
- No active screen: drop client packet.
- Stale revision: drop client packet.
- Snapshot build failure: send failure state if packet support is added; otherwise update client status through a minimal failure packet or local timeout.
- Timeout: client shows a retryable status after a short delay, without clearing existing tiles.

## Testing

### Unit Tests

Add or update tests for:

- request scheduler computes stable snapped regions for initial open, pan, zoom, and force render
- request scheduler throttles pan/zoom but not initial/force requests
- stale snapshot responses are rejected by session/revision
- `RoadPlannerTileManager.applySnapshot` updates tile pixels and marks textures dirty
- force render progress advances when a snapshot request completes
- packet round trips preserve new request/revision fields

### Integration-Level Tests

Where Minecraft runtime APIs are hard to instantiate in unit tests, isolate the sampler behind an interface and test the handler with a fake sampler/service.

Verify:

- `RoadMapSnapshotRequestPacket.handle()` calls the service and sends a response for a valid server sender
- oversized regions are clamped
- unavailable samples produce placeholder pixels instead of exceptions

### Manual Verification

1. Open planner between two far towns with no prior cache.
2. Confirm the map changes from gray checkerboard to terrain within a short wait.
3. Pan to a new area and confirm terrain arrives without closing the screen.
4. Use force render on a distant area and confirm status/progress changes and terrain appears.
5. Close and reopen; confirm cached tiles load under the same world/dimension.
6. Switch dimension/world and confirm old tiles do not leak into the new context.

## Implementation Notes

Keep the first implementation narrow:

1. Implement request scheduling and packet wiring.
2. Implement server snapshot build for bounded regions.
3. Implement client snapshot application to existing tiles.
4. Keep the existing client-local loaded-chunk renderer as fallback only.
5. Add focused tests before broad UI cleanup.

