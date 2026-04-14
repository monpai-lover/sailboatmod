# Road Bridge Construction Stability Design

## Context

The current manual road planner and construction pipeline still has four linked problems:

1. Road construction jobs can repeatedly retry the same placement step when the live block state does not exactly match the planned block state, causing infinite reset loops and server tick stalls.
2. Bridge placement artifacts are emitted as a single unordered ghost/build set, so decorations and lighting can be scheduled before bridge supports and deck blocks.
3. Bridge approaches are still too steep in some cases. The resulting deck-height profile can create abrupt transitions that are visually harsh and not reliably traversable by players or entities.
4. Path planning over complex terrain or water-adjacent land can repeatedly exhaust search on the same segments, then subdivide and retry them, causing high CPU cost while still surfacing the generic `NO_CONTINUOUS_GROUND_ROUTE` failure.

The user wants the bridge system to follow a pier-node-first model for long bridges, preserve pure arch bridges for short water crossings, make slopes gentler with slab-first smoothing, and make every placement step execute at most once even if the final world state is imperfect.

## Goals

1. Ensure every road build step is attempted at most once during forward construction.
2. Guarantee construction order: supports/piers first, then deck and ramps, then railings/lights/decorations.
3. Make bridge approaches lower-slope and slab-first so uphill and downhill traversal is smooth.
4. Keep short water crossings as pure arch bridges without interior piers.
5. Keep long water crossings as pier-node-driven bridges where deck generation is derived from bridge supports instead of inferred afterward.
6. Reduce pathfinding tick impact by preventing repeated failure searches on the same segment and by caching repeated terrain sampling work within a planning pass.
7. Improve failure reporting so true search exhaustion is distinguishable from actual missing land continuity.

## Non-Goals

1. This change does not convert the road planner into a fully asynchronous multi-threaded planning service like RoadWeaver.
2. This change does not add new user-facing config screens in this iteration.
3. This change does not attempt a broad refactor of all road geometry outside bridge construction, approach smoothing, and segmented planning stability.

## Reference Alignment

Two RoadWeaver ideas inform this design:

1. Performance: route-level terrain caching and coarse prewarming to avoid repeated expensive sampling.
2. Bridge form selection: short spans use pure arch bridges, while larger spans use structured support placement.

The implementation will adapt these ideas to the current Forge mod architecture without introducing RoadWeaver's thread-pool execution model in this iteration.

## Architecture

The work is split into four bounded areas:

1. Construction step state machine
2. Bridge form and deck geometry
3. Segmented route planning stability and cache reuse
4. Failure diagnostics and persistence compatibility

Each area must remain independently testable.

## 1. Construction Step State Machine

### Problem

`StructureConstructionManager` currently infers step completion from live world state. If a step is placed into a slightly different but functionally acceptable state, or if support/deck/decor blocks conflict during ordering, the same step can remain "incomplete" forever and be retried on every pass.

### Design

Introduce an explicit construction phase on each `RoadBuildStep`:

- `SUPPORT`
- `DECK`
- `DECOR`

Forward progress will be tracked by attempted-step identity instead of exact world-state equivalence alone.

### Behavior Rules

1. A forward placement step is consumed after its first placement attempt.
2. "Attempted" means the engine reached the step and executed its one chance, regardless of whether the final placed block exactly matched the planned state.
3. World-state matching remains useful for preview, restore, and repair diagnostics, but it no longer drives repeated forward retries.
4. Rollback continues to operate on actual rollback snapshots, not on "attempted" flags.

### Persistence

Persist attempted forward steps in road job runtime state so resume logic does not reconstruct a retry loop after reload.

Compatibility handling:

1. Existing persisted jobs without attempted-step state are upgraded by deriving an initial attempted set from the saved placed prefix plus any already matching world-state steps.
2. Legacy path-only jobs continue to rebuild a plan, but once rebuilt they use the new attempted-step semantics.

### Ordering

Build steps will be phase-sorted before persistence and execution:

1. `SUPPORT`
2. `DECK`
3. `DECOR`

Within a phase, the existing center-path-local ordering is preserved so visual build progression still follows the route.

## 2. Bridge Form and Deck Geometry

### Short Bridge Mode

Short water crossings should use pure arch bridges:

1. No interior piers
2. Only end abutments/bridgeheads
3. Deck profile forms a single arch crest
4. End approaches still obey low-slope, slab-first traversal constraints

This mode remains selected only for short unsupported spans that do not require a high navigable main deck.

### Long Bridge Mode

Long water crossings should use a pier-node-driven bridge:

1. Determine abutments and interior pier nodes first
2. Use those nodes to derive approach-up, main-level, and approach-down deck segments
3. Generate support columns only at explicit support nodes
4. Treat deck height as a consequence of node layout, not as an after-the-fact smoothing guess

### Slope and Ramp Smoothing

Bridge approaches will be updated to prefer low-slope slab-first traversal:

1. Extend ramp length before increasing slope steepness
2. Prefer slabs for the first stage of climb/descent
3. Use full blocks only when slab-only progression cannot maintain continuity
4. Avoid sudden 2-3 block jumps near bridgeheads
5. Preserve continuous deck-height sequences so entities can traverse the bridge head reliably

### Profile Rules

For pier bridges:

1. Main deck remains level over the primary span
2. Approach segments scale with height difference instead of staying near a short fixed ramp length
3. The higher the deck lift, the longer the approach allowance

For arch bridges:

1. Preserve a single crest over the short span
2. Clamp the entry and exit gradient so the arch does not create unwalkable edge transitions
3. Prefer slab transitions near arch ends

## 3. Segmented Route Planning Stability and Cache Reuse

### Problem

Current segmented planning can fail a subsegment, subdivide, and then repeatedly retry equivalent failing searches. This wastes CPU and produces tick stalls while still ending in failure.

### Design

Segment planning will remain synchronous in this iteration, but each planning pass gains three stabilizers:

1. Route-pass terrain sampling cache
2. Coarse prewarm before fine search
3. Failure suppression for equivalent segments

### Segment Selection

Do not split by fixed length. Instead, split on meaningful anchors:

1. Existing road-network anchors
2. Bridgeheads
3. Reachable island/shore anchors
4. Other traversable intermediate anchors already derived by the orchestrator

This avoids turning a solvable route into many tiny unsatisfiable micro-segments.

### Retry Limits

Each segment gets bounded subdivision:

1. A segment may only be subdivided a limited number of times in one planning pass.
2. Equivalent failed `(from,to,mode)` requests are recorded in a per-pass failure set.
3. Once a segment is marked as exhausted in that pass, it is not immediately retried through another equivalent path.

### Terrain Cache

Within one planning pass, cache repeated `(x,z)` queries for:

1. surface/traversable column
2. bridge requirement
3. water adjacency / water surface metadata
4. terrain penalty
5. blocked/excluded interpretation where applicable

The cache is local to a plan request so it cannot go stale across long world changes.

### Coarse Prewarm

Before fine segment search, prewarm a corridor between segment endpoints using a coarse step similar in spirit to RoadWeaver's hierarchical cache warming:

1. sample a low-resolution skeleton
2. populate terrain cache for likely future columns
3. do not use the coarse path itself as the final road

### Failure Reporting

When a route fails because search exhausted the available search space, surface a dedicated failure reason rather than always mapping the outcome to `NO_CONTINUOUS_GROUND_ROUTE`.

## 4. Failure Diagnostics and Resume Behavior

### Diagnostics

Improve logs and preview messaging so the system can distinguish:

1. missing anchor surface
2. blocked anchor
3. search exhausted
4. bridge geometry invalid because no viable support layout exists

This reduces false "continuous land missing" reports when the real issue is search-space exhaustion or repeated segment failure.

### Resume

On reload:

1. attempted steps remain consumed
2. already-matching steps are still treated as complete
3. unmatched but already-attempted steps are not retried forever
4. rollback remains exact because it relies on recorded rollback states

## Data Model Changes

`RoadBuildStep` gains:

1. phase
2. stable step id or phase-aware ordering data used for persistence/attempt tracking

`RoadJobState` gains:

1. attempted-step identifiers or attempted-step positions in persisted execution order

Planning-pass memory gains:

1. terrain sampling cache
2. failed-segment suppression set
3. bounded subdivision bookkeeping

## Implementation Notes

Likely touch points:

1. `StructureConstructionManager`
2. `RoadGeometryPlanner`
3. `RoadBridgePlanner`
4. `RoadCorridorPlanner`
5. `ManualRoadPlannerService`
6. `SegmentedRoadPathOrchestrator`
7. `RoadPathfinder`
8. `RoadRouteNodePlanner`
9. runtime saved-data serialization for road jobs

## Testing Strategy

All production changes are gated by failing tests first.

### Construction Tests

Add tests proving:

1. a failed or mismatched step is consumed after one attempt
2. persisted/resumed jobs do not retry attempted steps forever
3. build-step ordering always places supports before deck and deck before decor

### Geometry Tests

Add tests proving:

1. short bridge spans stay in `ARCH_SPAN` mode with no interior piers
2. long bridge spans create explicit support nodes and level main spans
3. bridge approaches use longer, lower-slope profiles
4. slab-first transitions appear on climb/descent edges

### Planning Tests

Add tests proving:

1. equivalent failing segments are not retried indefinitely in the same planning pass
2. segmentation prefers anchor-driven subdivision instead of fixed-length splits
3. cache-backed repeated sampling reduces redundant resolver work
4. search exhaustion surfaces a specific failure reason

## Risks

1. Attempt-once semantics can leave an imperfect block in the world if the first attempt is obstructed; this is intentional per user preference, but diagnostics should make that visible.
2. Longer ramps may widen bridgehead footprint and interact with nearby terrain or claims.
3. Phase sorting must not break rollback ordering or persisted job compatibility.
4. Over-aggressive failure suppression could hide a solvable route if the equivalence key is too coarse.

## Rollout Plan

1. Add failing tests for construction semantics and ordering.
2. Implement phase-aware build-step generation and attempted-step persistence.
3. Add failing tests for lower-slope slab-first bridge approaches.
4. Implement short-arch and long-pier bridge profile refinements.
5. Add failing tests for segmented retry suppression and cache reuse.
6. Implement planning-pass cache, coarse prewarm, and segment failure suppression.
7. Run targeted test suites and build a jar for in-game validation.

## Success Criteria

The change is successful when:

1. road jobs no longer loop forever on the same placement step
2. supports always place before bridge deck, and deck before decor
3. bridge entry/exit slopes are visibly smoother and traversable
4. short water crossings produce arch bridges without piers
5. long water crossings remain possible through pier-node-driven deck generation
6. repeated route failures no longer cause severe tick stalls
7. failure output distinguishes search exhaustion from actual missing land continuity
