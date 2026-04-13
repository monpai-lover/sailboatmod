# Segmented Route Orchestration Design

## Goal

Prevent long-distance road creation from freezing the server by replacing single huge path searches with segmented route orchestration, while also fixing bridge generation so bridge piers act as real bridge-routing anchors instead of a weak post-processing hint.

## Scope

This design covers all gameplay flows that currently rely on long road path searches:

- manual town-to-town road planning
- automatic post-station route resolution
- automatic structure/road connection resolution

It does not redesign road placement, rollback ownership, or visual corridor generation. It changes how long routes are planned before those later systems run.

## Requirements

- Long routes must be built through multiple intermediate anchors instead of one oversized pathfinding call.
- The same segmented routing system must be reused by manual roads, auto post routes, and auto structure connections.
- Intermediate anchors must prioritize special anchors before synthetic geometry cuts.
- Anchor priority order must be:
  - town / settlement anchors
  - existing road-network anchors
  - bridge-head / bridge-pier anchors
  - geometry-derived fallback split points
- If one segment fails, the planner must retry with alternative rules instead of failing the whole route immediately.
- Failed segments must be eligible for further subdivision.
- Final failure must return a concrete failure reason, not only an empty path.
- Bridge routing must treat piers as real anchor nodes in segmented bridge planning.

## Architecture

### Existing Entry Points

Existing gameplay entry points remain in place:

- `ManualRoadPlannerService`
- `RoadAutoRouteService`
- structure connection flows in `StructureConstructionManager`

They should delegate long-route planning to a new shared orchestration layer rather than each building their own long-distance fallback logic.

### New Shared Layer

Add a segmented route orchestration layer responsible for:

- collecting prioritized anchors between start and goal
- building an anchor chain
- planning each short segment with existing single-segment pathfinding
- retrying failed segments with alternative rules
- subdividing failed segments when needed
- returning either a stitched full route or a structured failure result

### Existing Single-Segment Layer

`RoadPathfinder` remains the single-segment planner.

It should continue to own:

- land segment path search
- water/bridge-enabled segment path search
- low-level interaction with `RoadRouteNodePlanner`

### Bridge Anchor Layer

Bridge piers and bridge heads become first-class anchors for segmented routing.

Cross-water planning should no longer depend on one large search over the whole span or on a weak “sorted piers then rasterize once” fallback alone.

## Data Flow

1. An entry point passes start/end positions to the segmented route orchestrator.
2. The orchestrator collects prioritized anchors:
   - town or settlement anchors
   - existing road-network anchors
   - bridge-head and bridge-pier anchors
   - geometry split points if anchor coverage is still insufficient
3. The orchestrator constructs an anchor chain from start to end.
4. It attempts each segment in order using the single-segment planner.
5. If a segment fails, it enters a retry flow:
   - retry with a broader rule set
   - retry with additional subdivision
   - retry with more bridge-aware anchor choices when applicable
6. If every segment succeeds, the orchestrator stitches them into one ordered route.
7. If retries are exhausted, it returns a structured failure record describing where and why the route failed.

## Retry Strategy

Each failed segment should move through an ordered retry ladder:

1. strict preferred rule set
2. bridge/water-enabled retry
3. finer-grained subdivision
4. alternate bridge-anchor selection for cross-water segments

The system must stop after a bounded number of retries/subdivisions per segment to avoid unbounded server work.

## Failure Reporting

Failures should become structured instead of empty-list-only.

The final failure object should identify:

- the failed segment start/end
- how many retries were attempted
- which retry modes were attempted
- a final reason such as:
  - search exhausted
  - no bridge anchors available
  - bridge heads unreachable
  - pier chain disconnected
  - subdivision limit exceeded

The calling layer can still surface a player-facing message, but the planning layer should preserve the specific reason.

## Bridge Routing Changes

Bridge planning should use three anchor types:

- bridge entry / exit anchors on land
- bridge pier anchors over water
- optional deck continuation anchors between long spans

When a segment is identified as a water-crossing candidate, the planner should construct a bridge-focused anchor chain first and solve shorter subsegments between bridge anchors.

If that chain fails:

- retry with additional candidate piers
- retry with different bridge heads
- then fall back to finer subdivision or a final explicit bridge failure

## Performance Expectations

This design reduces long main-thread stalls by replacing one huge search with several shorter, bounded segment searches.

The expected tradeoff is:

- slightly more orchestration work
- much lower worst-case search cost per attempt
- more stable planning for long-distance routes

## Testing

Tests should verify:

- long routes are segmented into multiple shorter attempts
- failed segments are retried and subdivided
- shared orchestrator behavior is reused across manual, auto-route, and structure-link entry points
- bridge-pier anchor chains can produce routes where the previous bridge fallback failed
- structured failure reasons are returned when all retries are exhausted

## Files

Likely implementation targets:

- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java`
- new shared orchestration classes under `src/main/java/com/monpai/sailboatmod/nation/service/`

## Non-Goals

- rewriting all low-level pathfinding from scratch
- moving route planning off-thread in this change set
- changing road block placement behavior
- changing bridge visual style or decoration logic
