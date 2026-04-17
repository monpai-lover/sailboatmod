# Land-Road Hybrid Planning, Construction Satisfaction, and Claim-Map Async Design

## Summary

This design merges three related improvements into one coherent execution model:

1. Apply Reign of Nether style construction placement semantics to road construction and building construction.
2. Integrate RoadWeaver-inspired land-pathfinding as a hybrid fallback backend for land-road planning only.
3. Move town claim and nation claim mini-map preparation to an async, revision-safe pipeline.

The central rule is:

- Bridge-road logic stays on the current path and is not replaced by RoadWeaver bridge or world-generation logic.
- Real world writes stay on the main thread.
- Async work is limited to snapshot building helpers, pure planning, raster preparation, and result staging.

## Goals

- Improve land-road planning reliability on broken terrain, hills, near-water terrain, and long routes.
- Preserve current public planning entry points and avoid breaking existing manual planner and route previews.
- Make road and building construction tolerant to equivalent states and already-satisfied world states.
- Reduce construction stalls caused by duplicate steps, natural clutter, and exact-state-only matching.
- Make town claim and nation claim screens open immediately and fill in mini-map content asynchronously.
- Reuse existing project infrastructure where possible instead of copying full reference-mod runtime systems.

## Non-Goals

- Do not replace current bridge planning, bridge deck anchor collection, bridge pier planning, or bridge preview logic.
- Do not import RoadWeaver world generation, highway generation, or bridge generation systems.
- Do not convert construction into an RTS-style worker-unit simulation like Reign of Nether.
- Do not move block placement, demolition, rollback, or GUI draw calls to background threads.
- Do not make all town or nation overview data streaming or async in the first pass. Only the heavy claim-map path is split out.

## Current Integration Points

Primary current mod files:

- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningSnapshotBuilder.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainService.java`
- `src/main/java/com/monpai/sailboatmod/network/packet/OpenTownScreenPacket.java`
- `src/main/java/com/monpai/sailboatmod/network/packet/OpenNationScreenPacket.java`

Reference mod concepts adopted:

- Reign of Nether:
  - `BuildingBlock.isPlaced(...)` style satisfied-state semantics
  - duplicate-work avoidance
  - tolerant block-family matching
- RoadWeaver:
  - bidirectional A*
  - terrain sampling cache
  - cost-based land routing
  - async pure-compute execution patterns

## Architecture Overview

The design splits the work into three cooperating subsystems.

### 1. Land-Road Planning

Keep `RoadPathfinder.findGroundPathForPlan(...)` as the stable land-road entry point, but change its internals from a single solver into an orchestrator:

- Run the current land solver first.
- Evaluate the result for success and quality.
- If the current solver fails, or if the route falls into complex-terrain conditions, invoke a new hybrid land solver.
- Return the final land path back into the current road-plan and centerline pipeline.

This preserves the current external contract while allowing a more robust backend.

### 2. Construction Satisfaction

Add a satisfaction layer between planned build steps and actual world writes.

Instead of treating every construction step as "must place this exact blockstate now", evaluate each step as one of:

- already satisfied
- retryable later
- hard blocked
- requires placement now

This layer is shared by:

- road construction
- building construction

The execution layer still performs the actual writes and rollback handling on the main thread.

### 3. Claim-Map Async Pipeline

Split claim-map work into two phases:

- phase 1: open the town or nation screen immediately with overview data and a map placeholder state
- phase 2: build claim-map data asynchronously and push or apply the result once ready

The server prepares terrain and claim overlay data. The client performs final raster composition and texture replacement without blocking screen open.

## Land-Road Hybrid Planning Design

### Stable Boundary

Hybrid land planning is used only from `findGroundPathForPlan(...)`.

It does not replace:

- `findPathForPlan(... allowWaterFallback = true ...)`
- `collectBridgeDeckAnchors(...)`
- bridge deck masking
- bridge range detection
- bridge slope, pier, preview, or rollback behavior

### Solver Strategy

`findGroundPathForPlan(...)` becomes a selector:

1. Run the current `RoadRouteNodePlanner` based solver.
2. If it succeeds and the result quality is acceptable, keep it.
3. If it fails or is flagged as poor quality on complex terrain, run `LandRoadHybridPathfinder`.
4. Normalize the selected result through the current plan pipeline.

### Hybrid Trigger Rules

Hard triggers:

- current land solver returns empty path
- failure reason resolves to `NO_CONTINUOUS_GROUND_ROUTE`
- result normalization fails
- centerline build fails

Soft triggers:

- large elevation variance between endpoints
- high near-water column ratio
- fragmented terrain score above threshold
- old solver path shows discontinuity risk or poor quality score

Soft-trigger thresholds should be configurable constants, not hard-coded in scattered call sites.

### Hybrid Solver Components

New components:

- `LandRoadRouteSelector`
- `LandRoadHybridPathfinder`
- `LandTerrainSamplingCache`
- `LandPathCostModel`
- `LandPathQualityEvaluator`

Recommended responsibilities:

- `LandRoadRouteSelector`
  - chooses legacy-only or hybrid fallback
- `LandRoadHybridPathfinder`
  - runs bidirectional A* style land-only search
- `LandTerrainSamplingCache`
  - caches height, water, near-water, ocean-floor, biome, and traversability samples
- `LandPathCostModel`
  - computes orthogonal, diagonal, elevation, terrain stability, near-water, and deviation costs
- `LandPathQualityEvaluator`
  - decides whether the legacy result is acceptable or should be replaced

### Data Sources

The hybrid solver should prefer immutable planning data where possible:

- `RoadPlanningSnapshot`
- `RoadPlanningPassContext`
- snapshot-seeded terrain cache

The design does not allow background threads to freely query live world state for the whole search corridor.

### Async Execution Model for Land Planning

Reuse `RoadPlanningTaskService` instead of importing RoadWeaver's thread-pool manager.

The road planning async model becomes:

1. Main thread:
   - collect request inputs
   - build or seed snapshot
2. Background compute:
   - legacy land solver
   - hybrid fallback
   - quality evaluation
3. Main thread:
   - apply preview
   - create plan candidates
   - schedule road construction

Coverage for async road planning:

- manual preview planning
- manual final planning
- automatic town-to-town background planning

Automatic town-to-town background planning must use separate `TaskKey` namespaces so it does not cancel manual preview work unintentionally.

## Construction Satisfaction Design

### Core Change

Current construction progression should stop assuming that every build step requires exact placement.

Each build step is evaluated through a shared satisfaction service before placement.

### Satisfaction States

Each step resolves to one of:

- `SATISFIED`
- `PLACE_NOW`
- `RETRYABLE`
- `BLOCKED`

Optional runtime result labels for logging and metrics:

- `SATISFIED_SKIP`
- `PLACED`
- `RETRYABLE_BLOCK`
- `HARD_BLOCKED`
- `ROLLED_BACK`

### Shared Services

Recommended new services:

- `construction/ConstructionStepSatisfactionService`
- `construction/ConstructionStateMatchers`
- `construction/ConstructionStepExecutor`

Responsibilities:

- `ConstructionStepSatisfactionService`
  - inspects world state and returns step status
- `ConstructionStateMatchers`
  - defines tolerant matches for road, support, cleanup, and headroom families
- `ConstructionStepExecutor`
  - performs the write, records rollback state, and updates runtime progress

### Matching Rules

Matcher families should be semantic, not exact-only.

Road/deck family examples:

- target block already present
- equivalent slab or stair state that preserves the intended road surface
- allowed orientation variants where functional result is the same

Support family examples:

- stone-brick family variants
- wall or support variants that are acceptable for the same support role

Cleanup family examples:

- grass
- flowers
- vines
- snow layers
- leaves

These natural blocks are removable clutter, not proof that the terrain already satisfies the intended build step.

Headroom family examples:

- air
- replaceable plants
- clearable light obstructions

### Duplicate Work Elimination

Before construction steps enter the active runtime queue:

- deduplicate repeated positions
- pre-scan steps that are already satisfied
- exclude satisfied steps from active placement progress

This prevents progress stalls caused by repeated attempts on the same position.

### Integration Points

Primary integration point:

- `StructureConstructionManager.placeRoadBuildSteps(...)`

The same satisfaction pipeline should also be used by building construction progression so that non-road structures gain the same tolerance and skip behavior.

### Main-Thread Rule

Construction satisfaction checks may read cached state or immutable planning data, but actual block writes, demolition writes, and rollback writes remain on the main server thread.

## Claim-Map Async Design

### Current Problem

Town and nation overview packets currently carry full terrain color lists synchronously.

The terrain pipeline in `ClaimPreviewTerrainService` already has partial async storage reads, but the end-to-end user flow is still largely synchronous:

- screen open waits for heavy map data
- sample calls still iterate synchronously
- blocking storage joins remain possible
- client consumes full map data directly in the screen flow

### Target Model

Town and nation claim-map rendering becomes a staged pipeline:

1. Open screen immediately with overview data and placeholder map state.
2. Build terrain color data and claim overlay data asynchronously.
3. Deliver map payload in a dedicated sync message with revision information.
4. Rasterize or combine final visual buffers asynchronously on the client.
5. Apply the finished texture on the client render thread.

### New Responsibilities

Recommended new components:

- `ClaimMapTaskService`
- `ClaimMapRevision`
- `SyncClaimPreviewMapPacket`
- `ClaimMapRasterizer` on the client side

Responsibilities:

- `ClaimMapTaskService`
  - owns claim-map compute threads
  - enforces latest-request-wins behavior
- `ClaimMapRevision`
  - binds results to a specific town/nation screen request
- `SyncClaimPreviewMapPacket`
  - sends incremental map payloads separately from overview open packets
- `ClaimMapRasterizer`
  - combines terrain colors, claim overlays, and selection highlights into a final pixel buffer

### Open Packet Changes

`OpenTownScreenPacket` and `OpenNationScreenPacket` should no longer be required to carry complete final map payloads on first open.

They should carry:

- overview data
- current map center
- current claim radius
- an explicit empty map state
- a revision or request token for follow-up async payloads

### Client Async Boundary

Client async work includes:

- raster buffer generation
- terrain + claim overlay composition
- stale-result dropping

Client main-thread work includes:

- screen state mutation
- texture upload
- final redraw

### Thread-Pool Isolation

Do not share the same thread pool between road planning and claim-map tasks.

Use:

- `RoadPlanningTaskService` for road planning
- `ClaimMapTaskService` for overview/claim-map work

This prevents background route planning from starving interactive UI map work.

## Unified Execution Rules

All three subsystems follow the same rule set:

- gather mutable world context on the main thread
- convert to immutable or revisioned work inputs
- run pure compute off-thread
- apply final results on the appropriate main thread

These rules are mandatory for:

- server safety
- cancellation
- stale-result rejection
- predictable debugging

## Failure Handling

### Land Planning Failures

Planned reason set:

- `LEGACY_FAILED_HYBRID_SUCCEEDED`
- `LEGACY_FAILED_HYBRID_FAILED`
- `SNAPSHOT_INCOMPLETE`
- `CANCELLED`
- `QUALITY_REJECTED`
- `NO_CONTINUOUS_GROUND_ROUTE`

### Construction Failures

Planned reason set:

- `SATISFIED_SKIP`
- `RETRYABLE_BLOCK`
- `HARD_BLOCKED`
- `PLACED`
- `ROLLED_BACK`

### Claim-Map Failures

Planned reason set:

- `STALE_RESULT_DROPPED`
- `TERRAIN_CACHE_MISS`
- `STORAGE_READ_FAILED`
- `RASTER_CANCELLED`
- `TEXTURE_APPLY_SKIPPED`

These reason sets are primarily for logs, debugging, and future HUD or toast improvements. The first implementation does not need to expose all of them directly to players.

## Validation Scope

### Land Planning

- flat short land routes still succeed without quality regression
- broken or hilly terrain routes can fall back to hybrid successfully
- automatic town-to-town planning can be cancelled safely
- bridge-road output remains unchanged

### Construction

- equivalent road or support states can satisfy build steps
- natural clutter does not falsely count as valid ground fulfillment
- duplicate steps do not stall progress
- building construction and road construction both reuse the same satisfaction model

### Claim-Map

- town and nation claim screens open before final map data is ready
- map content arrives incrementally without blocking interaction
- stale async map results are dropped when the user closes or changes screen state
- cache invalidation or permission changes refresh the map correctly

## Rollout Order

Recommended implementation order:

1. Claim-map staged open and async map pipeline
2. Land-road hybrid fallback and automatic async road planning integration
3. Construction satisfaction layer for roads and buildings

Reasoning:

- claim-map async introduces the revision and stale-result model in the lowest-risk path
- land-road hybrid is read-heavy and isolated from world writes
- construction satisfaction touches real placement and rollback behavior, so it should land last

## Recommended Approach

Proceed with the hybrid architecture rather than a full solver replacement or a full runtime rewrite.

This approach is recommended because it:

- preserves bridge behavior
- reuses existing project abstractions
- isolates async work safely
- applies Reign of Nether's strongest construction ideas without importing its entire building runtime
- applies RoadWeaver's strongest land-pathfinding and compute ideas without importing its world-generation stack
