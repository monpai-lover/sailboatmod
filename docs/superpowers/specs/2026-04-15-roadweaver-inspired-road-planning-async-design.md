# RoadWeaver-Inspired Road Planning Async Design

## Context

The current road planning stack still does most path search and bridge decision work synchronously on the server thread. That creates three linked problems:

1. Manual road preview can still stall ticks when segmented planning repeatedly retries failed route fragments.
2. Structure-driven road linking and other `RoadPathfinder` callers still pay the same synchronous search cost, so improving only the manual planner would leave major lag sources untouched.
3. Water-separated island links can still be misclassified as "missing continuous land" instead of being treated as bridge-first planning problems.

The user wants this next iteration to follow RoadWeaver's overall direction for performance:

1. cached terrain sampling
2. asynchronous path creation on worker threads
3. cancellation of stale planning work
4. better bridge handling for island-to-mainland links

The user also explicitly selected a full-scope rollout:

1. cover all `RoadPathfinder` call sites, not only the manual planner
2. return a "planning" state first, then push results later
3. do not hardcode a fixed span like 30 blocks; instead, detect when a target landmass is an island and plan bridges accordingly

## Goals

1. Move road path computation off the main server thread for all major `RoadPathfinder` consumers.
2. Preserve thread safety by avoiding direct background-thread reads from mutable `ServerLevel` state wherever practical.
3. Introduce RoadWeaver-style planning task management:
   - dedicated thread pool
   - epoch-based stale-task cancellation
   - request-key deduplication
4. Expand terrain sampling from simple per-column cache reuse into a planning snapshot model that can be safely consumed asynchronously.
5. Detect island-style targets and prioritize bridge planning for island-to-mainland or island-to-island routing.
6. Keep failure reasons structured so exhausted search is distinguishable from true lack of viable anchors or geometry.

## Non-Goals

1. This change does not fully port RoadWeaver's implementation classes or algorithms into this mod.
2. This change does not make block placement or construction execution asynchronous.
3. This change does not rewrite all bridge geometry rules again; it builds on the current bridge improvements and changes planning input and selection.
4. This change does not introduce a new user-facing configuration UI in this iteration.

## RoadWeaver Reference Alignment

The design will borrow four concrete ideas from `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury`:

1. `ThreadPoolManager`
   - separate managed executors
   - server-lifecycle-aware startup and shutdown
   - epoch invalidation for stale work
2. `ComputeService`
   - centralized async submission helpers
3. `TerrainSamplingCache`
   - layered cache for height, water, near-water, ocean floor, and biome
   - coarse prewarm before fine work
4. async planning services
   - submit compute tasks in the background
   - marshal final application of results back to the main thread

The design intentionally diverges from RoadWeaver in one key way:

1. This mod will not let arbitrary background planning code read live world state directly as its main data source.
2. Instead, the main thread will prepare a planning snapshot first, and worker threads will consume that immutable snapshot.

That preserves the spirit of RoadWeaver's async model while keeping the Forge-world access boundary more defensible in this codebase.

## Architecture Overview

The new planning pipeline is split into five bounded units:

1. planning task runtime
2. planning snapshot and sampling cache
3. async-capable route and bridge planner
4. caller integration layer
5. client/result delivery and stale-task suppression

Each unit should remain testable on its own.

## 1. Planning Task Runtime

### New Service

Add a centralized `RoadPlanningTaskService`.

Responsibilities:

1. own the compute executor used for async road planning
2. assign and track planning epochs
3. deduplicate or replace in-flight tasks by request key
4. expose helper methods for:
   - submit manual preview plan
   - submit structure road link plan
   - submit generic route plan for other `RoadPathfinder` consumers
5. marshal successful or failed results back to the main server thread

### Thread Model

The pipeline becomes:

1. main thread:
   - resolve inputs
   - capture planning snapshot
   - submit task
2. worker thread:
   - run route search and bridge selection against immutable snapshot data
3. main thread:
   - verify task epoch and request key are still current
   - push preview, cache result, or queue construction

### Epoch Model

Like RoadWeaver:

1. the service maintains a monotonically increasing epoch
2. server stop increments epoch and invalidates all worker results
3. replacing an older request for the same player or road-planning key makes the older result stale

### Request Keys

Every async request uses a stable request key derived from the caller:

1. manual planner preview:
   - player UUID
   - selected target town
   - planner mode
   - selected preview option
2. structure link planner:
   - source structure id
   - target structure id
   - dimension
3. generic route planner:
   - caller kind
   - dimension
   - start
   - end
   - bridge-allowed mode

When a newer request arrives with the same logical owner, the older result is discarded on completion instead of being applied.

## 2. Planning Snapshot And Sampling Cache

### New Snapshot

Add an immutable `RoadPlanningSnapshot` prepared on the main thread.

It contains the route corridor data needed by background search:

1. surface height per sampled column
2. traversable surface position per column
3. ocean-floor or support-floor height where relevant
4. water-column and near-water markers
5. terrain penalty values
6. blocked and excluded column interpretation
7. candidate bridgeheads and intermediate anchors
8. island/landmass metadata for relevant endpoints and corridor regions

### Sampling Strategy

Sampling is two-stage, inspired by RoadWeaver:

1. coarse prewarm
   - sample the broad route corridor at a larger step
   - fill the first-pass cache
   - identify likely water runs, shoreline bands, and rough bridge zones
2. fine sampling
   - refine around:
     - endpoints
     - bridgeheads
     - suspected island boundaries
     - navigable water corridors
     - candidate support columns

### Cache Lifetime

The snapshot cache is local to a single planning request.

Rules:

1. no long-lived cross-world terrain cache is introduced in this iteration
2. the existing `RoadPlanningPassContext` remains per-request
3. all sampling data is dropped when the async task finishes or is discarded

### Data Safety

The worker thread may read:

1. immutable request inputs
2. immutable planning snapshot
3. immutable route/network snapshots

The worker thread may not read:

1. live `ServerLevel` block state directly as part of normal planning
2. mutable saved-data collections without first snapshotting them on the main thread

## 3. Island Detection And Bridge-First Classification

### Problem

The current logic can still reason from "land route first, bridge fallback second." That works for some rivers but is weak for islands separated by water, especially when no continuous ground path exists by design.

### Design

Do not hardcode span thresholds such as 30 blocks to decide "small island bridge."

Instead, perform limited landmass analysis during snapshot creation:

1. flood-fill outward from the source-side anchor candidate
2. flood-fill outward from the target-side anchor candidate
3. measure:
   - landmass area
   - water enclosure ratio
   - shoreline extent
   - whether the target landmass is fully separated from the source landmass by a continuous water run inside the planning corridor

### Island Classification

A target region is considered island-like when:

1. its connected traversable landmass is bounded and relatively small inside the planning search window
2. the perimeter is dominated by water adjacency
3. the source and target landmasses are distinct
4. the corridor between them is primarily a water gap rather than blocked land

This classification is corridor-relative, not world-global. That keeps the detection bounded and fast.

### Planning Behavior

If routing is classified as island-to-mainland or island-to-island:

1. bridge planning becomes the primary route mode
2. pure ground-route search is no longer the dominant prerequisite for success
3. the planner first solves:
   - source bridgehead
   - target bridgehead
   - interior support/deck anchor chain

### Bridge Type Selection

Once a route is already classified as a bridge-first problem:

1. use `ARCH_SPAN` when:
   - water run is short
   - no interior support is needed
   - approach geometry remains smooth
2. use `PIER_BRIDGE` when:
   - water run is longer
   - support spacing and floor depth justify discrete piers
   - bridgeheads need a lifted deck over open water

This keeps bridge type selection geometry-driven without using a brittle hardcoded island span cutoff.

## 4. Async Route Planner Behavior

### Async Planner Contract

Add async-capable planner entry points that return a planning result object instead of only raw path lists.

The result object includes:

1. final path, if any
2. failure reason
3. bridge metadata
4. resolved bridge mode
5. planning diagnostics
6. stale/cancelled marker

### Search Pipeline

Worker-thread route search proceeds as:

1. consume `RoadPlanningSnapshot`
2. build `RoadPlanningPassContext`
3. run segmented planning with failed-segment suppression
4. evaluate ground, bridge, and hybrid candidates using snapshot data
5. normalize and post-process the winning path
6. emit structured result

### Failure Semantics

The async result must preserve structured failure reasons:

1. `SEARCH_EXHAUSTED`
2. `NO_CONTINUOUS_GROUND_ROUTE`
3. `TARGET_NOT_ATTACHABLE`
4. `BRIDGE_HEAD_UNREACHABLE`
5. `NO_BRIDGE_ANCHORS`
6. `PIER_CHAIN_DISCONNECTED`

For island-style bridge requests:

1. do not collapse the outcome back into generic "no continuous ground"
2. only report ground-route failure when the route was actually intended to be ground-first

## 5. Caller Integration

### Manual Planner

`ManualRoadPlannerService` changes from synchronous preview resolution to async preview submission.

Behavior:

1. player uses the planner item
2. service immediately records a pending request and returns a "planning" message
3. old preview task for the same player is replaced
4. async result returns on the main thread
5. if still current, send `SyncRoadPlannerPreviewPacket`
6. if stale, drop silently

### Preview UX

Preview semantics become:

1. initial interaction:
   - system message: planning in progress
   - clear or preserve last preview according to current mode
2. on success:
   - push preview packet
   - allow confirm step
3. on failure:
   - push preview clear if needed
   - send structured localized failure message

### Structure Construction Manager

`StructureConstructionManager` will use async planning for road-link creation where synchronous route planning currently blocks the server thread.

Behavior:

1. structure requests enqueue planning work
2. no block placement starts until the main thread receives a valid result
3. stale results are discarded if the world or request state has changed

### Other `RoadPathfinder` Callers

All major callers get a migration path:

1. manual planner
2. structure road linking
3. auto route services
4. any other gameplay system using `RoadPathfinder`

The old synchronous entry points remain available as compatibility helpers, but new work should route through the task service.

## 6. Packet And Result Delivery

### Preview Result Delivery

The current preview packet can continue to carry final preview data, but the service layer needs a pending state.

Two acceptable options:

1. use a system chat message for "planning"
2. extend preview state to support an explicit pending indicator later

This iteration only requires reliable async completion and stale-result suppression. A richer client-side pending UI is optional.

### Main-Thread Apply Rules

Before applying async results:

1. confirm server epoch still matches
2. confirm request key is still current
3. confirm player/level/context still exist
4. confirm target selection has not changed

If any check fails, discard the result.

## 7. Data Model Changes

Add or extend:

1. `RoadPlanningTaskService`
2. `RoadPlanningSnapshot`
3. `RoadPlanningTaskKey`
4. `RoadPlanningTaskResult`
5. `RoadPlanningPassContext`
   - bind snapshot-backed caches
   - keep failed-segment suppression
   - hold preferred failure reason

Potentially extend:

1. `SyncRoadPlannerPreviewPacket`
2. manual planner stack tag state for pending request bookkeeping

## 8. Testing Strategy

All implementation work remains test-first.

### Snapshot Tests

Add tests proving:

1. coarse and fine sampling reuse cached column data
2. island classification distinguishes:
   - mainland-to-mainland
   - mainland-to-island
   - island-to-island
3. snapshot data is sufficient for bridgehead and support-anchor selection without live level reads

### Async Runtime Tests

Add tests proving:

1. stale epoch results are ignored
2. newer request keys replace older ones
3. result application happens only on the owning main-thread callback path

### Planner Tests

Add tests proving:

1. island-style routes choose bridge-first planning
2. island links no longer misreport `NO_CONTINUOUS_GROUND_ROUTE` when the actual issue is exhausted bridge search
3. equivalent failed segments are not retried indefinitely in a single async planning pass

### Caller Integration Tests

Add tests proving:

1. manual planner returns immediate pending feedback and later applies only the newest result
2. structure road linking no longer blocks on synchronous route computation
3. old sync helpers still behave correctly where they remain in use

## 9. Risks

1. If the planning snapshot is too small, worker-thread planning may miss valid anchors or coastlines.
2. If the snapshot is too large, main-thread sampling may still be expensive.
3. Island detection can become too eager and force bridge-first routing in cases where a land detour would be better.
4. Async result delivery adds more state transitions, so stale-request handling must be exact.
5. Keeping both sync and async entry points temporarily can create drift if not tested carefully.

## 10. Rollout Plan

1. Add the task runtime and thread-pool lifecycle hooks.
2. Add immutable planning snapshot capture with layered terrain sampling.
3. Port manual planner preview to async pending/result flow.
4. Port structure link planning to async task submission.
5. Add island classification and bridge-first route selection.
6. Expand async usage across remaining `RoadPathfinder` callers.
7. Run targeted async, bridge, and planner regression suites.
8. Build and validate the jar in-game.

## Success Criteria

The change is successful when:

1. manual road preview no longer performs heavy path planning synchronously on the server thread
2. structure-driven road linking also uses the async planner path
3. all major `RoadPathfinder` callers have an async path through the shared task service
4. stale or replaced requests do not overwrite newer results
5. terrain sampling is reused through a single snapshot-backed planning pass
6. island links are treated as bridge planning problems instead of generic land-route failures
7. bridge failure messages no longer falsely report missing continuous land when the true issue is search exhaustion
