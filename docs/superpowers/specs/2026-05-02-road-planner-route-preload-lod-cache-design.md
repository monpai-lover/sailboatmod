---
date: 2026-05-02
topic: road-planner-route-preload-lod-cache
status: approved-for-planning
---

# Road Planner Route Preload and LOD Cache Design

## Outcome

Make the road planner minimap behave like a pre-rendered route map instead of a viewport resampler.

The map should:

- render terrain for route areas that were not explored or client-loaded
- run RoadWeaver/pathfinder first, then preload terrain around that route
- prefer one large rectangle around the route shape
- fall back to route-passing chunks when the rectangle is too large
- sample terrain once at high precision (`LOD_1`)
- derive lower LODs from the high-precision source
- keep world/dimension/LOD caches isolated
- clear only in-memory map state when leaving a world or closing the game
- reload persisted map cache when entering the same world again

## Current Evidence

The current implementation fixes the gray map by requesting server snapshots, but it still behaves like a viewport renderer:

- `RoadPlannerMinimapRequestScheduler` sends a new region request when the visible area changes.
- Requests are currently fixed at `MapLod.LOD_4`.
- `mouseScrolled()` changes zoom, which leads to viewport-key changes and more sampling requests.
- `RoadPlannerTileManager` separates disk cache by `worldId` and `dimensionId`, but not by LOD.
- `RoadPlannerTileKey` has no LOD field.
- `RoadPlannerForceRenderQueue` still primarily tracks client-side loaded chunk rendering.
- `RoadMapCorridorPlanner` and `RoadMapPreloadQueue` exist but are not connected to a route-driven server preload pipeline.
- Server-side auto-complete already has a pathfinder entry through `RoadPlannerAutoCompleteRequestPacket` and `RoadPlannerPathfinderRunnerFactory.serverService(...)`.

This explains the observed behavior: the map now renders, but every zoom/viewport change can cause fresh resampling instead of reusing high-precision cached terrain.

## Goals

1. Use RoadWeaver/pathfinder output to decide what terrain to preload.
2. Prefer a full route bounding rectangle so the user sees a complete map area around the planned route.
3. If that rectangle is too large, preload only chunks touched by the route plus padding.
4. Sample terrain at `LOD_1` as the canonical source.
5. Derive `LOD_2`, `LOD_4`, and `LOD_8` from `LOD_1`; zooming must not trigger server resampling.
6. Cache tiles by `worldId`, `dimensionId`, `lod`, `tileX`, and `tileZ`.
7. Keep disk cache between sessions but clear in-memory hot cache on world/dimension/session teardown.
8. Trigger preload both when entering the planner and when auto-complete returns the final route.
9. Process server chunk loading and sampling in bounded batches so the game remains responsive.
10. Report clear progress and degradation state in the planner status bar.

## Non-Goals

- Do not rewrite the road planner UI.
- Do not change road/bridge construction behavior.
- Do not introduce unbounded force loading.
- Do not require the player to physically explore route terrain before it can render.
- Do not persist in-memory-only request state after screen close.

## Chosen Approach

Use a server-driven **Road Planner Map Preload Pipeline**.

The client sends high-level preload requests rather than asking the server to resample every viewport zoom. For planner entry, the server computes a route from the anchors. For final route preload, the server uses the route nodes sent by the client. In both cases it derives a bounded target chunk set, samples those chunks at `LOD_1`, derives a LOD pyramid, and streams cached tiles/progress back to the client.

This approach is preferred because it matches the desired user model: once the route area is rendered, zooming should only switch cached detail levels, not rebuild terrain.

## Core Architecture

### Client Responsibilities

`RoadPlannerScreen` should stop treating zoom as a reason to resample terrain.

It should send only high-level preload requests:

1. `ENTER_PLANNER_PRELOAD`
   - Sent when the planner opens and start/destination are known.
   - The server runs RoadWeaver/pathfinder to estimate a route.

2. `ROUTE_PRELOAD`
   - Sent after auto-complete returns the final RoadWeaver/pathfinder route.
   - Uses the final expanded route nodes and segment types.

The client stores received tiles under the correct world/dimension/LOD cache and renders whichever LOD best matches the current zoom.

### Server Responsibilities

The server owns authoritative terrain sampling.

For each preload request, it:

1. Validates `sessionId`, player, world, and dimension.
2. Builds the entry route from anchors or uses the final route nodes from the request.
3. Computes the target chunk set.
4. Chooses rectangle mode or path-only mode according to the chunk budget.
5. Queues chunk loading and `LOD_1` sampling in bounded batches.
6. Derives lower LODs from completed `LOD_1` tiles.
7. Sends progress and tile payloads back to the requesting client.

### Request Identity

Every preload request needs:

- `sessionId`
- `preloadRevision`
- `worldId`
- `dimensionId`
- `purpose`
- route anchors or final route nodes
- preload protocol version; server-owned budget defaults are used for the first implementation

The client drops stale responses when the session, revision, world, or dimension no longer matches.

## Route Region Selection

### Path Chunks

The server converts the RoadWeaver/pathfinder route into a chunk set:

1. Iterate each route segment.
2. Sample each segment every 8-16 blocks.
3. Convert sampled positions to `ChunkPos`.
4. Expand by `pathPaddingChunks`.

This produces `pathChunks`, the guaranteed fallback area.

### Bounding Rectangle

The server computes a rectangle around all route chunks:

1. Find `minChunkX`, `maxChunkX`, `minChunkZ`, and `maxChunkZ`.
2. Expand each side by `rectanglePaddingChunks`.
3. Enumerate all chunks in that rectangle.

This produces `rectangleChunks`.

### Budget Rule

Use the rectangle when it is within budget:

```text
if rectangleChunks.size <= maxRectangleChunks:
    targetChunks = rectangleChunks
    coverageMode = RECTANGLE
else:
    targetChunks = pathChunks
    coverageMode = PATH_ONLY
```

No request is rejected just because the rectangle is too large. Oversized rectangles degrade to path-only preload.

Recommended default values:

```text
maxRectangleChunks = 4096      // 64 x 64 chunks, roughly 1024 x 1024 blocks
rectanglePaddingChunks = 4
pathPaddingChunks = 3
chunksPerTick = 4
segmentSampleStepBlocks = 8
```

## LOD Model

### Canonical Source

`LOD_1` is the only authoritative terrain sample.

For each target chunk, the server samples a 16x16 pixel `LOD_1` tile, where each pixel represents one world block column.

### Derived Pyramid

Lower LODs are derived from `LOD_1`:

- `LOD_2`: combine 2x2 `LOD_1` pixels
- `LOD_4`: combine 4x4 `LOD_1` pixels
- `LOD_8`: combine 8x8 `LOD_1` pixels

The first implementation can use stable average color. A later improvement may use water/biome-aware aggregation.

### Zoom Behavior

Zooming changes only the selected render LOD.

It must not send a terrain resampling request for terrain that is already covered by `LOD_1`.

If the preferred LOD tile is missing:

1. Use an available higher-precision tile as a temporary fallback.
2. Derive the missing LOD from local/server `LOD_1` cache.
3. Do not request fresh server sampling unless `LOD_1` itself is missing.

## Cache Layout

### Cache Key

All map tile caches use:

```text
worldId + dimensionId + lod + tileX + tileZ
```

`RoadPlannerTileKey` should include `lod`. This keeps the cache identity explicit and makes cross-LOD leakage impossible even when multiple LODs are loaded in the same manager.

### Disk Layout

Persisted cache should be separated by LOD:

```text
roadplanner_map_cache/<worldId>/<dimensionId>/lod_<N>/<tileX>_<tileZ>.png
```

### Memory Lifecycle

- Entering a world loads hot cache lazily from disk.
- Closing the planner may release loaded tile textures and in-flight request state.
- Leaving a world or switching dimension clears active in-memory map state.
- Disk cache remains and can be reused on the next world entry.
- Caches from another world or dimension must never be rendered in the current planner.

## Data Flow

### Enter Planner

1. The planner opens with start/destination data.
2. The client sends `ENTER_PLANNER_PRELOAD`.
3. The server runs RoadWeaver/pathfinder from start to destination.
4. If pathfinding fails, the server falls back to a start-destination straight-line approximation for map preload only.
5. The server computes rectangle/path-only target chunks.
6. The server samples target chunks at `LOD_1`, derives lower LODs, and sends progress/tile payloads.
7. The client updates the minimap as tiles arrive.

### Auto-Complete Route

1. The user triggers auto-complete.
2. Server auto-complete returns final route nodes and segment types.
3. The client applies the route to `linePlan`.
4. The client sends `ROUTE_PRELOAD` with the final expanded route.
5. The server computes the target area from final route nodes.
6. The server only samples missing `LOD_1` chunks and derives missing LOD tiles.
7. Existing cached map tiles remain visible and are not invalidated just because the route changed.

### Zoom and Pan

1. The user zooms or pans.
2. The client selects the best cached LOD for the current zoom.
3. The client renders cached tiles.
4. Missing visual tiles may trigger local/server cache fetch for already-sampled data, but not terrain resampling if `LOD_1` exists.

### Close, Cancel, or Switch

- Closing the planner cancels unfinished work for that `sessionId`.
- A newer preload revision supersedes older revisions.
- Switching world/dimension clears active memory state and starts from the new cache namespace.

## Progress and Status

The planner status bar should report route preload state:

- `地图: 预加载中 37%`
- `地图: 区域过大，已切换为路径预渲染 42%`
- `地图: 使用缓存`
- `地图: 等待 LOD 派生`
- `地图: 服务端采样失败，保留已有缓存`
- `地图: 请求已取消`

Progress is based on target chunks and LOD derivation work, not on client-loaded chunk availability.

## Error Handling

- Missing sender or invalid world: drop the request safely.
- Stale response: client drops it.
- RoadWeaver/pathfinder failure during planner entry: use a straight-line approximate route for preload only.
- Final route preload with explicit final nodes: use those nodes even if pathfinder is unavailable.
- Single chunk sample failure: mark that chunk unknown/placeholder and continue.
- Oversized rectangle: switch to path-only mode and report the degradation.
- Client screen closed: cancel or ignore all in-flight work for that session.

## Threading and Safety

Minecraft world access must stay on the server thread.

Allowed off-thread work:

- request bookkeeping
- queue prioritization
- chunk-set computation from immutable route data
- LOD derivation after raw pixels have been copied out of world state
- packet payload preparation

Server-thread-only work:

- force-loading chunks
- reading `ServerLevel`
- reading heightmaps
- reading block states and biome data
- mutating persistent world cache

Processing is batched. A default budget of `chunksPerTick = 4` keeps the game responsive while still making progress.

## Packet Shape

The first implementation can add new packets instead of overloading viewport snapshot packets:

- `RoadPlannerMapPreloadRequestPacket`
  - request identity
  - purpose
  - anchors or route nodes
  - segment types
  - preload protocol version

- `RoadPlannerMapPreloadProgressPacket`
  - request identity
  - coverage mode
  - completed chunks
  - total chunks
  - status enum; the client maps it to localized text

- `RoadPlannerMapTileSyncPacket`
  - request identity
  - world/dimension
  - lod
  - tile coordinates
  - ARGB tile pixel data

Existing `RoadMapSnapshotRequestPacket` may remain for manual viewport refresh or be deprecated after the preload pipeline is stable.

## Testing

Focused tests should cover:

1. `RoadPlannerRoutePreloadRegionPlannerTest`
   - rectangle mode when under budget
   - path-only mode when over budget
   - route padding expands expected chunks

2. `RoadPlannerLodPyramidTest`
   - `LOD_1` derives `LOD_2`, `LOD_4`, and `LOD_8`
   - dimensions and colors are stable

3. `RoadPlannerTileKey` / `RoadPlannerTileManager` tests
   - world/dimension/LOD isolation
   - switching world clears memory without changing disk namespace

4. Packet round-trip tests
   - preload request fields survive encode/decode
   - progress fields survive encode/decode
   - tile sync preserves LOD and tile identity

5. Screen behavior tests
   - zoom does not send server resampling requests
   - entering planner sends preload when anchors exist
   - auto-complete result triggers route preload
   - close/cancel ignores stale responses

6. Verification commands
   - focused test set
   - `./gradlew -Dnet.minecraftforge.gradle.check.certs=false compileJava`
   - `./gradlew -Dnet.minecraftforge.gradle.check.certs=false jarJar`

## Implementation Notes

Keep the first implementation narrow:

1. Add route chunk selection and budget logic.
2. Add LOD-aware cache keys and disk paths.
3. Add LOD pyramid derivation.
4. Add preload request/progress/tile packets.
5. Connect planner entry and auto-complete completion to preload requests.
6. Stop zoom from triggering terrain resampling.
7. Keep old viewport snapshots only as a temporary fallback until route preload is verified.
