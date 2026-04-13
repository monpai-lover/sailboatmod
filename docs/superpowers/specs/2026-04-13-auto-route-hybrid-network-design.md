# Auto Route Hybrid Network Design

## Goal

Finish the unfinished auto-route work so automatic route generation prefers existing road networks over pure terrain routing, while still falling back to direct terrain routing when no viable hybrid path exists.

## Scope

This design only covers automatic route resolution for services that currently flow through `RoadAutoRouteService`.

It does not change:

- manual road planning
- bridge construction placement rules
- rollback/runtime construction ownership
- unrelated worktree files under `logs/` or other unfinished route experiments

## Requirements

- Automatic routes must prefer reusing existing road networks whenever possible.
- Automatic routes may use a short connector from the source to the road network.
- Automatic routes may use a short connector from the road network to the target.
- Automatic routes may use both source-side and target-side connectors with a reused road-network segment in the middle.
- Connector routing should keep using the existing pathfinder.
- Connector routing must not hard-reject candidates because of manual bridge budget limits.
- Direct terrain routing must remain available as a fallback when no viable hybrid-network candidate exists.

## Architecture

### `RoadAutoRouteService`

`RoadAutoRouteService` remains the public entry point and keeps responsibility for:

- gathering existing road networks from world/nation data
- building the in-memory road graph for the current dimension
- preparing source/target anchors
- invoking the hybrid resolver
- converting the chosen result into the existing `RouteResolution` type

### `RoadHybridRouteResolver`

`RoadHybridRouteResolver` owns hybrid candidate generation and scoring. It should remain independent from UI, station entities, and persistence concerns.

It is responsible for:

- evaluating a direct candidate
- evaluating source-connector candidates
- evaluating target-connector candidates
- evaluating dual-connector candidates
- stitching connector and road-network path segments
- scoring and selecting the best candidate

## Candidate Types

The resolver evaluates these candidate families for each source/target anchor pair:

1. Direct terrain path from source to target
2. Source connector to one road-network node, then reused road-network path to target
3. Reused road-network path from source to one road-network node, then target connector
4. Source connector to a left road-network node, reused road-network path to a right node, then target connector

## Data Flow

1. `RoadAutoRouteService` collects all road records in the current dimension.
2. It builds `Set<BlockPos>` network nodes and `Map<BlockPos, Set<BlockPos>>` adjacency.
3. It invokes `RoadHybridRouteResolver` with source anchors, target anchors, graph data, and a connector-planner callback.
4. Connector segments are produced through `RoadPathfinder`.
5. `RoadHybridRouteResolver` summarizes each connector and computes the best hybrid candidate.
6. `RoadAutoRouteService` maps the selected hybrid result back into `RouteResolution`.

## Connector Policy

Connector planning continues to use the existing pathfinder and path diagnostics, but the resolver changes how those diagnostics are used:

- bridge columns remain a penalty metric
- contiguous bridge runs remain a penalty metric
- adjacent water columns remain a penalty metric
- these metrics no longer act as a hard rejection budget for automatic route connectors

Automatic-route connectors should therefore remain possible in cases where the direct route is technically valid but inferior to a route that reuses the existing network.

## Scoring

The scoring model should heavily prefer reused road-network paths.

Priority order:

1. Any candidate that reuses the existing road network beats a pure direct terrain candidate.
2. Among candidates that reuse the network, fewer connectors is better.
3. Shorter total path length is better.
4. Lower bridge-column count is better.
5. Lower longest contiguous bridge run is better.
6. Lower adjacent-water count is better.

This keeps automatic routing aligned with the intended gameplay preference: network reuse first, terrain fallback second.

## Testing

Tests should cover:

- network-backed route selection beating direct terrain fallback
- dual-connector selection when it reduces risky bridge/water exposure
- direct fallback when no road-network-backed route is viable
- resolver behavior remaining deterministic for small synthetic graphs

## Files

Primary implementation targets:

- `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java`
- `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolverTest.java`

## Non-Goals

- unifying manual-road and auto-route resolution into one shared global planner
- changing current manual bridge heuristics
- editing unrelated unfinished experimental files beyond what is required to make the hybrid auto-route feature complete
