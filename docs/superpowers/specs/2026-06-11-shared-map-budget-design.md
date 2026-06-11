# Shared Map Rendering and Tick Budget Design

Date: 2026-06-11

## Goal

Stabilize the road planner map and nation claim map by making them read the same map data and rendered chunk state. The first implementation phase focuses on shared map state, force-render synchronization, and watchdog-safe tick budgeting.

This phase does not rebuild the road construction system. Road graph and placement ledger ideas are recorded as a later phase.

## Background

The current bug pattern points to two related problems:

- The road planner map and claim map can diverge because they do not consistently read the same rendered tile/chunk state.
- Map and claim preview work can become too heavy for a single server tick, causing watchdog risk when large regions are scanned or rendered at once.

The useful reference pattern from `HundredYearsWar` is architectural, not code-level copying:

- The server owns authoritative chunk visibility/render state.
- The client keeps a dimension-indexed chunk set.
- Server sync packets send deltas.
- Applying a delta refreshes the client render state.

For watchdog prevention, the useful pattern is staged work with a fixed per-tick budget. Large jobs should be resumable across ticks instead of scanning or rendering large areas in one tick.

## Non-Goals

- Do not copy code from reverse-engineered mods.
- Do not rewrite the road network system in this phase.
- Do not add undoable roads, road width upgrades, or road network connection logic in this phase.
- Do not merge road planner overlays and claim overlays; only the map base data and rendered chunk state should be shared.

## Recommended Approach

Use a shared map-state layer while keeping existing UI screens and gameplay flows.

The claim map and road planner map remain separate screens with separate overlays. They both read from the same `SharedMapTileStore` and `RenderedChunkIndex` through small adapters. Force-render work, claim viewport requests, and road planner viewport requests all submit work to the same budgeted service. When new map data is rendered, the server sends one shared delta and both client UIs refresh from the same state.

This gives the shortest path to fixing the current desync without a large rewrite.

## Shared Server State

Introduce or consolidate a shared map state service with two responsibilities:

- `RenderedChunkIndex`: tracks rendered/available map chunks or tiles by dimension and coordinate.
- `SharedMapTileStore`: stores actual tile or snapshot data used by both UIs.

Both road planner and claim map requests should submit map needs to this shared service. They should not independently mark chunks as rendered or independently decide which LOD data is authoritative.

The shared state should support these operations:

- Query whether a dimension/chunk/tile is rendered.
- Queue missing chunks or tiles for force rendering.
- Store completed tile data.
- Produce compact delta updates after completed work.
- Invalidate stale entries when dimension, world, or tile generation rules change.

## Shared Client State

Add a client-side state holder similar in shape to `HiddenChunkClientState`, but specific to map rendering:

- State is indexed by dimension.
- Each dimension tracks rendered chunks or tiles.
- It accepts server deltas and removals.
- It exposes read-only queries to road planner and claim map screens.
- Applying a delta schedules or triggers map UI refresh.

The client-side state should be the single source of truth for whether the UI should attempt to display a terrain tile. The screens should not maintain separate hidden rendered-chunk lists.

## Unified Request Flow

Both maps use the same request pipeline:

1. A UI opens, moves, zooms, or requests force render.
2. The client sends a viewport request with a purpose flag: `ROAD_PLANNER` or `CLAIM_MAP`.
3. The server translates the request into dimension/tile/chunk work.
4. Duplicate work is merged by dimension and tile/chunk key.
5. The budgeted map worker advances queued work over multiple ticks.
6. Completed work updates `SharedMapTileStore` and `RenderedChunkIndex`.
7. The server sends a shared map delta packet to relevant clients.
8. The client applies the delta to shared map state.
9. Road planner and claim map screens redraw their base map from the shared state.

The purpose flag is for prioritization and overlay behavior only. It must not create separate base-map caches.

## UI Responsibilities

The road planner map owns road-specific overlays:

- route nodes
- route lines
- force-render selection rectangle
- road planning state

The claim map owns nation and claim overlays:

- nation borders
- claim preview rectangle
- claim price/status
- ownership and permission state

Both screens must use the same base terrain data and the same rendered-state checks. If the road planner force-renders an unknown area, the claim map should show that terrain after the shared delta is applied.

## Tick Budget Model

Map and preview work must be split into resumable stages. A single server tick should process only a bounded amount of work.

Recommended stages:

1. Collect target chunks or tiles from the viewport.
2. Filter out already-rendered entries using `RenderedChunkIndex`.
3. Queue missing entries for render or sampling.
4. Render/sample a limited number of chunks or tiles.
5. Store completed tile data.
6. Emit compact deltas.

Each stage must be resumable. A job that covers many chunks should carry its cursor/state forward into later ticks.

Initial budget targets can follow the current conservative values:

- visible viewport work: small fixed chunk/tile count per tick
- prefetch work: small fixed chunk/tile count per tick
- force-render work: separate small fixed chunk/tile count per tick

Exact constants can be tuned during implementation, but the invariant is strict: no full-region scan or full-region render in one tick.

## Queue Behavior

The shared queue should enforce:

- Deduplication by dimension and tile/chunk key.
- Priority for visible viewport work over prefetch work.
- Force-render work as explicit queued work, still budgeted.
- Cancellation or deprioritization when a player closes a UI, changes dimension, or moves far away.
- Bounded per-player and global queue growth.

This prevents stale UI requests from continuing to consume server ticks after they are no longer useful.

## Watchdog Safety Requirements

The following methods must remain budgeted and non-blocking:

- `ClaimPreviewTerrainService.tick`
- `ClaimPreviewTerrainService.processBudgetedWork`
- `ClaimPreviewTerrainService.drainQueue`
- `ServerEvents.onServerTick`
- `RoadPlannerMapPreloadService.tick`

Server tick handlers may advance work, but they must not perform unbounded loops over claims, large viewports, or force-render regions.

World reads/writes that must happen on the server thread should be done in small batches. Pure calculation and cache preparation may use workers if the results are handed back safely to the server thread.

## Packet Strategy

Add or consolidate a shared map delta packet instead of sending two unrelated map updates.

The delta should include:

- dimension id
- added or updated rendered tile/chunk keys
- removed or invalidated tile/chunk keys if needed
- optional tile metadata such as LOD, generation version, or timestamp

Existing road planner and claim map packets can be kept temporarily as adapters during migration, but the final base-map state should be updated through the shared delta path.

## Migration Plan

1. Add the shared state interfaces and client/server holders.
2. Adapt road planner map rendering to read from shared state.
3. Adapt claim map rendering to read from shared state.
4. Route road planner force-render completion into shared deltas.
5. Route claim viewport terrain completion into shared deltas.
6. Remove duplicated rendered-state decisions from the two UI paths.
7. Keep overlays separate.

The implementation should prefer small adapter steps so existing screens continue to work while their base-map source is unified.

## Verification

Minimum verification:

- `.\gradlew.bat compileJava`
- Manual road planner force-render test
- Manual claim map open/move/zoom test

Gameplay checks:

- Force-render unknown chunks in the road planner.
- Open the claim map over the same area.
- Confirm the same terrain appears in both screens.
- Move the claim map viewport and confirm the display frame matches world chunk coordinates.
- Confirm both screens use the same LOD/tile data for the same area.
- Close the UI or change dimension and confirm stale jobs stop advancing or are deprioritized.
- Stress move the viewport and confirm no watchdog hang occurs.

Regression checks:

- Road planner overlays still render independently.
- Claim overlays still render independently.
- Claim price/status text still uses claim data, not map coordinate data.
- No force-render tool becomes a bypass around shared map state.

## Later Road System Phase

The road system ideas from `HundredYearsWar` are useful for a future design, but they should not be implemented in this phase.

Future road architecture should consider:

- A saved road graph with nodes, segments, and network ids.
- A placement ledger that records original block state, road block state, and reference count.
- Staged road construction tasks: pathfinding, placement planning, and block placement.
- Undoable road construction by restoring ledgered original block states.
- Road width upgrades through refCount-aware placement records.
- Network connections between towns, markets, post stations, docks, and other settlement infrastructure.

This later phase should get its own spec because it touches gameplay persistence and world modification, which has a larger risk surface than map rendering.

## Success Criteria

This design is successful when:

- Road planner and claim map share one base map data source.
- Force-rendered terrain from one screen becomes visible in the other.
- Map rendering state is synchronized through one delta path.
- Large map requests are processed across ticks with strict budgets.
- The server no longer risks a 60 second watchdog hang from claim preview or map rendering work.
- Road system redesign remains out of scope until a separate approved plan exists.
