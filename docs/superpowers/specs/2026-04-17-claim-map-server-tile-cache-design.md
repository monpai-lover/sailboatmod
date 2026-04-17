# Server-Driven Claim Map Tile Cache Design

**Status:** Approved for planning after user review

**Scope:** Replace the current claim-map preview flow for town and nation screens with a server-driven terrain tile service that force-loads uncached chunks, samples real terrain asynchronously, persists results, and serves complete viewport snapshots that remain stable while the player drags the map.

## Context

The current claim-map path is split awkwardly:

- the server prepares a preview payload for the current center and sends it to the client
- the client still performs viewport-oriented raster work and local cache stitching
- dragging the map effectively changes the preview center and triggers more preview work

This does not satisfy the intended behavior for town claim and nation claim maps:

- dragging the map must not re-render already rendered terrain
- missing terrain must be fetched from the server even if the relevant chunks are currently unloaded
- terrain sampling must use real world data rather than client-local approximations
- caching must be reusable across drags, screen reopen, and server restart
- chunk changes must automatically refresh affected cached terrain in the background

## Requirements

The replacement design must satisfy all of the following:

1. Terrain sampling is authoritative on the server.
2. Unloaded chunks may be force-loaded in order to sample terrain for the map.
3. All heavy orchestration work is asynchronous and multi-threaded.
4. Direct world access remains on the server thread only.
5. Dragging the map does not trigger local terrain resampling on the client.
6. When a dragged-to area is not cached yet, the server prioritizes producing a complete viewport before sending the update.
7. Cache is two-layered: hot in-memory cache plus persistent world cache.
8. The existing `refresh` action clears only the current visible area and immediately rebuilds it.
9. Terrain-changing events invalidate and immediately resample affected chunk tiles in the background.
10. Out-of-date requests must never overwrite a newer viewport response.

## Recommended Approach

Use a server-driven chunk tile service with viewport snapshot assembly.

This is preferred over caching whole finished viewport images as primary data because viewport-key caching alone explodes when the player drags frequently. It is also preferred over incremental client stitching because the user explicitly wants complete-area refresh semantics and authoritative server sampling for unloaded chunks.

The core data unit is a sampled chunk tile, not a whole viewport. Viewports are assembled from chunk tiles and may also be cached as secondary hot snapshots for fast replay.

## Architecture

### 1. Chunk Tile Sampling Layer

Each chunk stores a fixed `SUB x SUB` sampled terrain color grid. This remains the canonical sampled terrain representation.

For each chunk tile:

- key: dimension + chunkX + chunkZ
- value: sampled colors, sample revision metadata, timestamp, dirty flag

Sampling uses real world terrain:

- if the chunk is already available, sample it directly
- if not available, force-load it on the server thread
- read heightmap and block/map-color data from the loaded chunk
- normalize to the same preview color format used by claim map rendering

This tile-level representation is stable and reusable regardless of viewport drag direction.

### 2. Dual Cache Layer

Two cache layers coexist:

**Hot memory cache**

- keeps recently used chunk tiles
- keeps recently assembled viewport snapshots
- serves fast repeated drags and quick backtracking

**Persistent world cache**

- stores chunk tile samples in world saved data
- survives screen close, reconnect, and server restart
- acts as the source of truth when memory cache is cold

Persistent cache stores chunk tiles, not whole viewport images. Whole viewport images are allowed only as a volatile optimization in memory.

### 3. Viewport Snapshot Layer

Town and nation screens request a viewport snapshot:

- dimension
- center chunk
- radius
- revision
- prefetch radius
- screen kind

The viewport coordinator first checks whether every chunk tile required by the visible area is already available. If yes, it assembles or reuses a complete viewport snapshot and returns it immediately.

If not, it does not stream partial terrain. Instead:

- all missing chunks for the visible area are scheduled at highest priority
- all missing chunks for the configured large prefetch ring are scheduled at lower priority
- only when the visible area is complete does the server assemble and send the viewport snapshot

This preserves the requested behavior: the player drags, the current map stays visually stable, and the next map appears only when the whole new viewport is ready.

### 4. Client Display Layer

The client becomes a snapshot consumer instead of a terrain sampler.

The client:

- sends viewport requests
- keeps showing the last completed snapshot while a newer one is pending
- swaps in the new snapshot only when a complete viewport response arrives
- continues drawing claim borders, selections, hover markers, and labels locally on top of the server-provided terrain snapshot

The client does not:

- sample unloaded terrain
- rebuild terrain colors while dragging
- recompute chunk terrain tiles from local world state

## Request and Response Flow

### Open Screen

When the town or nation claim screen opens:

1. Client sends a viewport request for the current visible area.
2. Server checks memory snapshot cache.
3. If missing, server checks chunk tile memory cache and persistent tile cache.
4. If the visible area is complete, server assembles and returns a full snapshot.
5. If the visible area is incomplete, server schedules missing visible chunks as high priority and the configured outer ring as lower priority.
6. Server returns the snapshot only after the visible area is complete.

### Drag Map

When the player drags:

1. Client computes the new desired center and creates a higher revision request.
2. Client keeps rendering the current completed snapshot.
3. Server repeats the same visible-area completeness check for the new viewport.
4. If the new viewport is already covered by cached chunk tiles, server can assemble the new snapshot quickly without resampling terrain.
5. If not covered, the missing chunk tiles are force-loaded and sampled asynchronously according to priority.
6. When complete, the server sends the new snapshot; older revisions are discarded.

Dragging therefore reuses already rendered terrain data. It does not invalidate finished terrain just because the viewport moved.

## Threading Model

World access must remain thread-safe. The design therefore splits work into two phases.

### Asynchronous orchestration phase

Worker threads may:

- accept viewport requests
- compute missing tile sets
- evaluate cache hits
- manage prioritization
- deduplicate repeated work
- assemble final snapshot pixel arrays from completed chunk tile data
- prepare packets and response payloads

### Server-thread world access phase

Only the server thread may:

- call chunk loading APIs with force-load enabled
- access `ServerLevel`, `ChunkAccess`, heightmaps, or block states
- sample real terrain from the world
- mutate persistent saved-data state

This still qualifies as asynchronous multi-threaded processing because heavy scheduling, deduplication, prefetch management, snapshot composition, and packet preparation happen off-thread. The server thread is reserved for the minimal critical section of authoritative chunk acquisition and sampling.

## Force-Load and Budget Rules

Visible-area completeness has priority over prefetch.

Two budgets exist:

- **Visible-area force-load budget:** dedicated budget for missing chunks inside the active viewport
- **Prefetch force-load budget:** lower-priority budget for the outer ring

This ensures the current requested map completes as fast as possible while still warming adjacent areas for smoother future drags.

Prefetch is intentionally large enough to support several continuous drag steps, because the user explicitly preferred aggressive prefetch over a minimal ring.

## Cache Invalidation and Refresh

### Automatic invalidation

Whenever terrain-relevant blocks change in a chunk:

1. Invalidate the hot tile cache entry for that chunk.
2. Invalidate the persistent tile cache entry for that chunk.
3. Invalidate any in-memory viewport snapshots that depend on that chunk.
4. Schedule immediate background resampling of that chunk.
5. If any open claim-map viewport currently depends on that chunk, rebuild that viewport after resampling and push an updated complete snapshot.

This keeps cached terrain live without waiting for the player to manually refresh.

### Manual refresh button

The existing refresh button remains, but its semantics are narrowed to the visible area only.

When pressed:

1. Determine every chunk covered by the current visible viewport.
2. Clear those chunk tiles from hot and persistent cache.
3. Clear hot viewport snapshots that depend on those chunks.
4. Schedule immediate high-priority rebuild for the current visible viewport.
5. Return the rebuilt complete snapshot when ready.

Prefetch may also be re-queued, but it must not delay the current visible viewport response.

## Revision and Staleness Rules

Every viewport request carries a monotonic revision number on the client side.

For each logical screen instance:

- only the newest revision may apply
- older work may finish, but its result must be discarded
- a rebuild triggered by auto-invalidation must also respect the latest known viewport revision

This prevents stale snapshots from overwriting a more recent drag target.

## Failure Handling

If chunk loading or sampling fails for a viewport:

- do not send a partial snapshot
- keep the client showing the previous completed snapshot
- record failure timing for the failing chunks to avoid immediate pathological retry loops
- allow manual refresh to force a retry path

If only prefetch chunks fail, the visible area result may still be returned as long as the requested visible area is complete.

## Impact on Existing Code

This design supersedes the claim-map portion of the previous async claim-map design that introduced client-side rasterization.

Expected directional changes:

- `ClaimPreviewTerrainService` evolves into a long-lived server tile service rather than a one-shot preview sampler
- `ClaimMapTaskService` remains the async coordination entry point on the server side, but now manages viewport/tile generation rather than only one-shot preview responses
- current client-side `ClaimMapRasterizer` and `ClaimMapRenderTaskService` stop being the primary terrain-generation mechanism
- town and nation claim screens become consumers of authoritative viewport snapshots plus local overlay drawing

## Testing Strategy

The implementation proves the following behaviors:

1. Dragging within already cached terrain does not resample chunk tiles.
2. Dragging into uncached terrain schedules visible chunks first and withholds response until the viewport is complete.
3. Prefetch fills adjacent tiles after the visible area is satisfied.
4. Chunk change invalidation immediately schedules resampling and refreshes affected open viewports.
5. Manual refresh clears only the visible area and rebuilds it.
6. Persistent cache survives service restart and reduces follow-up sampling work.
7. Old revisions cannot overwrite new viewport results.
8. Failed chunk sampling leaves the previous snapshot visible and avoids partial-map corruption.

## Non-Goals

The following are intentionally out of scope for this design:

- changing claim border/selection overlay visuals
- changing town/nation map interaction UX beyond snapshot delivery behavior
- introducing client-side approximation for unloaded terrain
- switching to partial progressive terrain streaming for the requested viewport

## Implementation Notes

The main design decision is to treat chunk terrain tiles as the persistent reusable asset and whole viewport images as ephemeral assembled products.

That is the key property that makes this design compatible with frequent dragging without wasting work.
