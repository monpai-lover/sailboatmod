# Road Hybrid Network Bridge Design

## Summary

Adopt the useful part of RoadWeaver's bridge-linking behavior without importing its full global planner.

The mod should prefer routes that can attach to existing road-network nodes and reuse stable road segments when that produces a lower-cost or lower-risk result than a fresh direct crossing. This applies to:

- manual town-to-town road planning
- automatic post-station road routes
- structure road preview and automatic structure-road connection logic

The change is not a highway or region-topology rewrite. It is a shared hybrid route-resolution layer that compares direct new construction against network-attached alternatives and then chooses the best candidate under explicit bridge and connector constraints.

## Goals

- Learn and adopt the "bridge into an existing network" part of RoadWeaver's design.
- Let direct routes compete against network-attached routes under one scoring model.
- Prefer shorter, more stable, and less bridge-heavy routes even when the winner reuses an existing road network.
- Keep manual-road construction on the current corridor and geometry pipeline once a center path is chosen.
- Reuse the same route-selection rules across manual roads, automatic routes, and structure-road connections.

## Non-Goals

- Rebuilding the mod around RoadWeaver-style regional highway, cell, or branch planning
- Adding a new world-scale road topology pass
- Modifying `RoadNetworkRecord` storage format
- Inserting projected snap points into the middle of existing road edges in this pass
- Supporting multi-hop alternating patterns like `new -> network -> new -> network -> new`
- Reworking corridor generation, geometry generation, or construction runtime beyond the selected center path
- Adding player-facing configuration or UI for hybrid route selection

## Current Problem

The current codebase makes independent choices in three places:

- manual town roads pick anchor pairs and then directly run `RoadPathfinder`
- automatic road routes first try the saved road graph and otherwise fall back to direct terrain routing
- structure-road preview and auto road creation score direct anchor-to-anchor paths only

That produces two problems:

1. Existing roads are not treated as part of one shared routing problem. They are either used by a special-case codepath or ignored.
2. Bridge linking is too binary. A route can either directly cross water or fail, but it does not systematically ask whether a short connector into an existing road network would avoid a worse bridge.

RoadWeaver's useful lesson here is not its full graph planner. The useful lesson is that roads can attach to an existing network and fill only the missing connector gap.

## Proposed Architecture

Introduce a shared resolver named `RoadHybridRouteResolver`.

This resolver sits before the existing construction and geometry pipeline. It decides the best center path, but it does not place blocks, shape corridors, or generate build steps.

The resolver consumes:

- source anchor candidates
- target anchor candidates
- a view of existing `RoadNetworkRecord` nodes and adjacency
- routing constraints such as blocked columns and whether water fallback is allowed

The resolver returns a ranked best candidate with:

- `fullPath`
- `segments`
- `resolutionKind`
- `usedExistingNetwork`
- `connectorCount`
- `bridgeColumns`
- `longestBridgeRun`
- `adjacentWaterColumns`
- `score`

The downstream callers keep their current responsibilities:

- manual roads still normalize the chosen path and feed it into `StructureConstructionManager.createRoadPlacementPlan(...)`
- automatic routes still convert the chosen path into waypoints
- structure preview still uses the chosen path to build road previews and auto-road records

## Candidate Model

The resolver should enumerate a bounded set of candidate route shapes.

### 1. Direct

`DIRECT`

Route from source anchor to target anchor with no existing-network segment.

This preserves the current behavior and keeps a fresh road available when it is genuinely the best option.

### 2. Source Connector

`SOURCE_CONNECTOR`

Route from source anchor to one existing road-network node, traverse an existing saved road-network segment, then continue to the target side as needed.

This is useful when only one side is poorly connected to the existing road graph.

### 3. Target Connector

`TARGET_CONNECTOR`

Symmetric version of the source-connector case.

### 4. Dual Connector

`DUAL_CONNECTOR`

Route from source anchor into an existing road-network node, travel along the existing road network, then leave the network through a second connector to the target anchor.

This is the closest analogue to the RoadWeaver behavior we actually want: use the network when it is already there, and only construct the missing connecting pieces.

## Existing-Network Node Source

This pass should use explicit existing nodes only. Do not project onto the middle of stored road edges yet.

Eligible attachable nodes are:

- explicit `RoadNetworkRecord.path()` nodes from both manual and auto roads
- road nodes near post-station waiting-area exits
- in-town existing road nodes already captured in current road-network records
- structure preview targets already recognized as road targets

This keeps the model simple and avoids changing persistence or splitting stored edges.

Future extension may add edge projection and edge splitting, similar in spirit to RoadWeaver's snap behavior, but that is intentionally out of scope here.

## Connector Construction Rules

Every connector segment is treated as a small independent route-resolution problem, not as a free-form extension.

Connector search should:

- try land-only first
- only allow water fallback if the specific caller allows it
- record whether the connector used bridge columns
- record total bridge columns
- record longest contiguous bridge run
- record adjacent-water exposure
- record final path length

Connector segments have stricter bridge expectations than a full direct route because their job is to attach to an existing network, not to become the main bridge body themselves.

### Connector Budget Rules

Each connector candidate must pass tighter limits than the normal direct route:

- lower maximum contiguous bridge columns
- lower maximum total bridge columns
- stronger penalty for bridge use near the attach point

If a connector requires too much bridge usage, it should be rejected even if `RoadPathfinder` technically finds a path.

### Bridgehead Stability Rule

If a connector uses bridge columns, the attach side should prefer nodes where the bridgehead lands on a stable existing network node or a stable anchor surface rather than on an unstable shoreline edge.

The system should not pick a connector that wins on raw length but creates a visibly poor or fragile attach point.

## Scoring Model

All candidate classes compete under one score model.

Base components:

- total path length
- bridge-column penalty
- contiguous-bridge penalty
- adjacent-water penalty
- connector-count penalty

Preference components:

- moderate bonus for reusing existing road-network distance
- moderate bonus for lower new-construction footprint
- moderate bonus for stable attach geometry

Guardrails:

- an existing-network route must not win solely because it touches the network if it produces a major detour
- a direct path must not win solely because it is shorter if it creates a substantially worse bridge profile

The intended behavior is:

- direct route wins when it is clearly shorter and no more dangerous
- network-attached route wins when it meaningfully reduces bridge burden or improves stability

## Integration By Caller

### Manual Town Roads

`ManualRoadPlannerService` keeps its current anchor resolution:

- waiting-area exits first
- town-anchor fallback logic as currently designed for this branch

After anchor resolution, it delegates path selection to `RoadHybridRouteResolver`.

The selected candidate path then continues through the existing pipeline:

- `normalizePath(...)`
- `StructureConstructionManager.createRoadPlacementPlan(...)`
- corridor validation
- geometry generation
- preview packet generation
- runtime construction

This means the implementation only changes route selection, not the manual-road build model.

### Automatic Post-Station Routes

`RoadAutoRouteService` currently does:

- road-network graph path first
- terrain path second

Replace that binary branch with a single call into the shared resolver so automatic routes can also choose:

- direct terrain route
- source-side network attach
- target-side network attach
- dual-side network attach

Automatic route storage remains unchanged. The chosen `fullPath` is still converted into waypoints and route definitions.

### Structure Auto-Roads And Preview

`StructureConstructionManager` currently tries direct anchor pairs only.

Replace that direct-only search with the shared resolver so structure connections can prefer extending from nearby existing roads instead of always creating a fresh standalone segment.

This should make structure-road previews and generated roads feel like actual extensions of the network rather than isolated stubs.

## Testing Strategy

Follow TDD. The new behavior should be proved in layers.

### 1. Resolver Unit Tests

Add a dedicated test class for the shared resolver.

Required cases:

- direct and network-attached candidates both exist, and the lower-risk network candidate wins
- direct candidate wins when the network alternative is too long
- connector bridge usage exceeds the connector budget and is rejected
- dual-connector route wins over a long direct bridge
- no acceptable network node exists, so the resolver falls back to direct routing

### 2. Manual-Road Service Tests

Extend `ManualRoadPlannerServiceTest` to prove:

- manual road planning can choose a network-attached route rather than a pure direct route
- preview/build still receive a valid path and usable placement plan
- current waiting-area anchor behavior is preserved

### 3. Automatic Route Tests

Extend `RoadAutoRouteServiceTest` to prove:

- automatic route resolution can prefer hybrid network attachment
- existing route-definition output remains stable
- direct terrain fallback still works when no attachable network route is valid

### 4. Structure Connection Tests

Add or extend tests around `StructureConstructionManager` so:

- preview road hints prefer nearby existing roads when they produce a better score
- auto-generated structure road links can attach into the network instead of always building a fresh direct segment

### 5. Verification

At minimum run:

- focused road-related tests
- `compileJava`

If focused test filtering is unreliable, run the broader relevant test suite instead of silently skipping verification.

## Risks And Constraints

- Existing saved road-network paths may be sparse. Because this pass only uses explicit stored nodes, some promising attach opportunities will still be invisible.
- Over-rewarding reused network distance could create ugly detours. The score must stay length-sensitive.
- Over-permissive connector bridge rules would let "network attach" become a disguised long bridge. Connector budgets must remain strict.
- Manual-road quality still depends on the downstream corridor pipeline. This design improves center-path selection but does not replace corridor validation.

## Rollout Boundaries

This implementation intentionally stops at:

- one optional existing-network segment per resolved route
- explicit-node attachment only
- unchanged persistence
- unchanged UI

That keeps the work focused on the bridge-linking behavior the user asked for while preserving the current construction architecture.

## Success Criteria

The feature is successful when:

- manual town roads can choose a path that connects into existing road networks when that path is better than a fresh direct crossing
- automatic post-station routes use the same hybrid logic instead of a binary graph-or-terrain fallback
- structure road previews and generated road links more naturally extend from nearby road networks
- connector bridge usage stays short and controlled
- no caller needs to reimplement its own network-attachment decision logic
