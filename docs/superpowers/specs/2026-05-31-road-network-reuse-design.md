# Road Network Reuse Design

## Goal

Rebuild road sharing, road-network drawing, and road-network routing around a durable graph model instead of the current linear `RoadNetworkRecord.path/displayPath/sharedSpans` model.

The user-visible goals are:

- completed roads should form a real reusable network
- newly planned roads should automatically detect reusable spans and show them clearly before build confirmation
- shared road spans should not be rebuilt as duplicate blocks
- road-network rendering should be cleaner, faster, and less cluttered at different zoom levels
- post stations, logistics routes, and carriage route planning should use the new road graph
- the long-standing black minimap tile bug after road construction should be fixed as part of the same lifecycle

The technical goals are:

- move reuse decisions out of `RoadPlannerScreen`
- persist reusable graph nodes and edges directly
- keep legacy roads visible without letting them affect new reuse or routing
- reuse RoadWeaver code directly where it fits the current mod, with focused adapters where storage, permissions, or UI differ
- protect existing saves and the current dirty worktree during implementation

## Decisions From Brainstorming

The selected approach is a graph-first refactor.

Legacy `NationSavedData.RoadNetworks` entries remain readable and visible as a historical overlay. They do not participate in new road reuse, logistics routing, or carriage routing.

Newly completed roads are persisted in a new road graph saved-data store. This graph is the source of truth for:

- reuse detection
- road overlay queries
- road hit testing
- road tooltips for new roads
- logistics road routing
- carriage route segment classification

Road reuse is automatic but explicit in the planner UI. The planner detects compatible shared spans, highlights them before confirmation, and skips physical construction for reused spans.

RoadWeaver code should be copied directly when the code is a good fit and then adapted to this package and data model. RoadWeaver is MIT licensed, so copied or substantially adapted files must keep attribution in source comments or project notices.

## Scope

This design covers:

- new persistent road graph records
- RoadWeaver-inspired segment placement and spatial indexing
- automatic reuse detection
- planner preview and build confirmation data flow
- overlay rendering and hit testing for new graph roads
- legacy road overlay behavior
- minimap tile refresh correctness after construction
- logistics and carriage route integration
- focused automated tests

This design does not cover:

- migrating old `RoadNetworkRecord` roads into the new graph
- changing the core town, nation, diplomacy, or permission model
- replacing all existing terrain pathfinding logic
- importing RoadWeaver SQLite or shard storage
- world-generation tree prevention around roads
- redesigning the road planner into a full graph editor

## Existing Context

The current implementation spreads road reuse across several layers:

- `RoadNetworkRecord` stores `path`, `displayPath`, `sharedSpans`, creator metadata, and route names.
- `RoadPlannerRoadMergeService` scans `NationSavedData.getRoadNetworks()` for candidates and overlays.
- `RoadPlannerScreen` contains substantial reuse behavior, including start reuse detection, end merge spans, display-path tricks, and UI rendering.
- `RoadPlannerBuiltRoadRegistry` writes completed roads back to `NationSavedData`.
- `RoadAutoRouteService` and `CarriageRoutePlanner` build temporary adjacency from old road paths.

The current model is difficult to improve because a completed road is still fundamentally a path list. A shared road is represented by extra metadata around that path instead of as graph topology. This makes drawing, reuse, and routing fragile.

There is already a light `roadplanner.graph` package with `RoadNetworkGraph`, `RoadGraphNode`, `RoadGraphEdge`, and `RoadRouteMetadata`. It is useful directionally, but it does not yet persist enough information for construction ownership, reuse spans, map rendering, or routing.

## RoadWeaver Reuse

RoadWeaver reference: `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury`.

The following RoadWeaver pieces should be copied or closely adapted:

- `RoadSegmentPlacement`: use the same center-point plus footprint concept. In this project it becomes a graph edge placement record with `middlePos` and `positions`.
- `RoadSpatialIndex`: keep the chunk/grid/LRU shape, but replace RoadWeaver's storage queries with graph saved-data queries.
- `RoadSnapService`: keep the local `SegmentGrid`, nearest-segment lookup, direction compatibility, run extraction, transition ratio, and threshold behavior. Change the output from "rewrite secondary RoadData" to "return a reuse plan".
- `MapRenderers.renderRoadPolylines`: reuse the LOD-aware polyline rendering strategy in the existing road planner map renderer.

The following RoadWeaver pieces should not be copied directly:

- SQLite storage and sharded storage
- `ConfigService`
- global `RoadConstants` as a dependency
- RoadWeaver owner pair fields, including `ownerA2dKey` and `ownerB2dKey`
- world-generation integration for preventing trees on roads

Initial constants should be derived from RoadWeaver and then owned locally:

- spatial grid size: 8 blocks
- spatial grid shift: 3
- max cached chunk indexes per dimension: 512
- snap threshold: 12 blocks
- snap split threshold: 24 blocks
- minimum reusable run length: 3 segments
- transition segment count: 3

These values are implementation defaults, not save format requirements.

## Persistence Model

Add a new saved data object named `RoadNetworkGraphSavedData` with `DATA_NAME = "sailboatmod_road_graphs"`. It is separate from `NationSavedData` to avoid treating old roads as migrated graph data.

### Node Record

`RoadGraphNodeRecord` stores:

- `nodeId`
- `dimensionId`
- `pos`
- `kind`
- `ownerNationId`
- `ownerTownId`
- optional `structureId`
- `createdAt`
- `updatedAt`

Node kinds:

- `NORMAL`
- `JUNCTION`
- `TOWN_CONNECTION`
- `POST_STATION_CONNECTION`
- `REUSE_ANCHOR`

### Edge Record

`RoadGraphEdgeRecord` stores:

- `edgeId`
- `fromNodeId`
- `toNodeId`
- `dimensionId`
- `ownerNationId`
- `ownerTownId`
- `creatorUuid`
- `creatorName`
- `sourceTownName`
- `targetTownName`
- `roadName`
- `width`
- `sectionType`
- `status`
- dense `centerline`
- simplified `displayPath`
- `placements`
- `ownedBlockPositions`
- `reuseSpans`
- `createdAt`
- `updatedAt`

The dense centerline is for routing and precise hit testing. The display path is for map rendering. Placements are the RoadWeaver-style segment placements used for spatial indexing and footprint membership.

`ownedBlockPositions` contains only blocks physically created by this edge. Reused spans should not claim ownership of existing blocks.

### Reuse Span

`RoadGraphReuseSpan` stores:

- `sourceEdgeId`
- `sourceFromIndex`
- `sourceToIndex`
- `plannedFromIndex`
- `plannedToIndex`
- `fromPos`
- `toPos`
- `relationship`

The source range references an existing graph edge. The planned range references the new route's logical centerline. This lets routing stay connected while construction skips already existing blocks.

### Legacy Records

`RoadNetworkRecord` remains in `NationSavedData` for old roads and existing save compatibility. It should no longer be the primary output for newly built graph roads. If an existing packet or screen temporarily needs a compatibility object, it should be produced from the new graph as a DTO, not persisted as another primary road model.

## Services

### RoadGraphRepository

Owns read/write access to `RoadNetworkGraphSavedData`.

Responsibilities:

- add nodes and edges
- update edge metadata
- mark edges removed
- query edges by id
- query edges by dimension and rectangle
- expose immutable snapshots for rendering and routing
- rebuild or invalidate spatial indexes when graph data changes

### RoadGraphSpatialIndex

Adapted from RoadWeaver `RoadSpatialIndex`.

Responsibilities:

- index edge centerline points
- index placement footprint points
- query nearby placements around a point
- query edges intersecting a map viewport
- classify whether a point belongs to a road corridor
- invalidate touched chunks after graph mutation

The index is runtime cache, not the source of truth.

### RoadGraphReusePlanner

Adapted from RoadWeaver `RoadSnapService`.

Responsibilities:

- accept a planned route represented as placements
- find nearby graph edges from the spatial index
- detect direction-compatible continuous reusable runs
- reject unsupported bridge/tunnel reuse by default
- produce `RoadReusePlan`

`RoadReusePlan` includes:

- full logical centerline
- display polyline
- planned placements
- owned placement ranges
- reuse spans
- rejected candidates with reasons

### RoadGraphOverlayService

Produces road overlay DTOs for the planner map.

Responsibilities:

- query graph roads by viewport
- simplify display polylines by zoom level
- return node and edge metadata for hit testing and tooltips
- include legacy roads as weak visual overlays
- exclude legacy roads from candidate and routing metadata

### RoadGraphRoutingService

Builds route paths from the graph.

Responsibilities:

- build adjacency from graph nodes and edges
- use real edge lengths as weights
- resolve nearest graph connector within the existing connector budget
- return full route centerline waypoints
- expose road corridor membership for carriage segment classification

## Planner Data Flow

The planner flow changes from screen-owned reuse logic to service-owned reuse logic.

1. The player draws a route or accepts an auto-generated route.
2. Existing route expansion creates dense centerline points and segment types.
3. Build compilation creates RoadWeaver-style placements for the candidate route.
4. `RoadGraphReusePlanner` detects reusable spans from the new graph.
5. The server sends a preview DTO containing new-build sections, reuse spans, display path, and issues.
6. The client renders the preview:
   - new-build sections use the normal planning color
   - reused spans use a distinct highlighted overlay
   - graph roads use the built-road style
   - legacy roads use a weaker style and legacy tooltip
7. On confirmation, `RoadPlannerBuildControlService` stores the validated reuse plan in its preview snapshot.
8. The construction queue receives build steps only for owned sections.
9. On completion, `RoadPlannerBuiltRoadRegistry` writes graph nodes, graph edges, placements, owned blocks, and reuse spans.
10. Graph indexes and affected map tiles are invalidated.

The client may display reuse, but the server remains authoritative. Reuse detection must be revalidated at confirmation and completion so stale previews do not attach to deleted or changed edges.

## Reuse Rules

Automatic reuse is enabled only for new graph roads.

A candidate span is reusable when all of these are true:

- candidate and source are in the same dimension
- source edge is built and not removed
- diplomacy and ownership rules permit using the source road
- enough consecutive planned placements are near an existing edge
- the run length is at least the configured minimum
- direction compatibility passes
- height continuity is acceptable at the transition
- bridge/tunnel compatibility passes

Default bridge and tunnel rule:

- road-to-road reuse is allowed
- bridge-to-bridge reuse is allowed only when both type and height continuity match
- tunnel-to-tunnel reuse is allowed only when both type and clearance assumptions match
- road-to-bridge, bridge-to-road, road-to-tunnel, and tunnel-to-road reuse are rejected automatically

Rejected candidates should be available for diagnostics and tests. The UI does not need to show every rejection in the first implementation.

## Construction Ownership

The construction queue must not contain build steps for reused spans.

Owned sections are compiled into `BuildStep`s normally. Reused spans are persisted as graph links. This prevents duplicate surface blocks, duplicate rollback ownership, and visual double roads.

`ConstructionRuntimeSavedData.RoadJobState` should continue to store rollback and owned block state for physically executed steps only.

When a build completes:

- owned build steps are registered as owned blocks
- reused spans are registered as graph references
- the edge remains routable across both owned and reused sections
- touched chunks are collected from owned steps and from relevant placement footprints

If a build contains only reused spans and creates no owned blocks, it should still be allowed only if it creates a meaningful graph connection between distinct nodes. Otherwise it should be rejected as a no-op before confirmation.

## Map Rendering

Road rendering is split into two layers:

- base map tile layer
- road overlay layer

The base map tile layer shows sampled terrain and built blocks. The road overlay layer shows semantic roads from the graph and legacy records.

New graph roads:

- query through `RoadGraphOverlayService`
- render with LOD-aware polylines
- use stronger colors for built graph roads
- show hover and tooltip metadata from graph edges
- show nodes and junctions only at close zoom levels

Legacy roads:

- remain visible from `RoadNetworkRecord`
- render in a weaker color
- show a legacy tooltip
- never return as reuse candidates
- never return to routing services

Planning preview:

- new-build spans render as normal planned road
- reused spans render as a highlighted overlay on the existing road
- blocked or rejected spans remain in the preview issue model

The map renderer should adopt RoadWeaver's LOD behavior: skip overly dense road polyline segments when zoomed far out, and avoid drawing detail lines when they would degrade visual hierarchy.

## Black Minimap Tile Fix

The black tile bug appears when road construction causes map tile cache replacement with incomplete or placeholder pixels. The fix is part of the build completion lifecycle.

Rules:

- every completed build computes touched chunks from executed build steps and owned placement footprints
- touched chunks map to affected map tiles
- `BUILT_ROAD_REFRESH` jobs must request complete tile snapshots
- complete tile refreshes must force-load or otherwise reliably sample all chunks needed for the tile
- unknown, unavailable, or placeholder pixels must not overwrite existing client tile pixels
- if a full tile cannot be sampled reliably, the refresh should retry later or skip the unsafe pixels

Client merge behavior:

- a pixel is writable only when it is covered and known
- coverage without known sample data does not count as valid replacement
- missing coverage masks should not silently turn partial placeholder data into full-tile replacement
- base tile absence should not allow a partial built-road refresh to create a black tile

This means road overlays are not responsible for hiding bad base tiles. The base tile data must be correct.

## Routing Integration

`RoadAutoRouteService` should stop building graph adjacency from `NationSavedData.getRoadNetworks()`.

New behavior:

- get graph snapshot from `RoadGraphRoutingService`
- find nearest graph node or edge connector within the existing connector budget
- resolve short terrain connector from station to graph
- run Dijkstra or A* over graph edges using edge length as cost
- return the graph centerline plus connector segments

`CarriageRoutePlanner` should stop classifying road corridor segments by checking membership in old road path sets.

New behavior:

- use graph spatial index membership for `ROAD_CORRIDOR`
- classify off-graph connector segments as `TERRAIN_CONNECTOR`
- include reused spans as road corridor because they belong to the graph topology

When no new graph roads exist, routing behaves as if no road network exists. Legacy roads do not provide road routing.

## Compatibility And Rollout

The implementation should be staged.

Stage 1:

- add graph records, saved data, codecs/NBT helpers, repository, and tests
- add RoadWeaver-based placement and spatial index adapters

Stage 2:

- add reuse planning service
- add preview DTOs and tests
- keep old UI rendering path until overlay service is ready

Stage 3:

- update build control and built-road registry to write graph roads
- skip build steps for reused spans
- keep old records visible as legacy overlays

Stage 4:

- replace road overlay query and hit testing with graph overlay service
- apply RoadWeaver LOD polyline rendering

Stage 5:

- switch logistics and carriage routing to the graph routing service
- add regression tests for old roads not participating

Stage 6:

- harden built-road map refresh and tile merge semantics
- add black-tile regression tests

During all stages, do not delete or rewrite existing `RoadNetworkRecord` save data. Do not modify logs, road planner draft files, generated resources, or unrelated reference projects.

## Error Handling

The graph services should fail closed:

- invalid edge ids return no candidate
- dimension mismatch rejects reuse
- missing source edge rejects reuse
- deleted or non-built edges reject reuse
- invalid persisted ranges are ignored during load
- route queries with missing nodes return no route
- tile refreshes with unknown pixels do not overwrite known base pixels

Server-side confirmation should revalidate reuse so stale client previews cannot create invalid graph links.

## Testing

Add or update focused tests for:

- graph NBT round trip for nodes, edges, placements, owned blocks, and reuse spans
- graph repository add/query/remove behavior
- spatial index viewport and proximity queries
- reuse run detection using RoadWeaver thresholds
- direction compatibility and threshold rejection
- bridge/tunnel reuse rejection
- build confirmation skipping reused build steps
- completed build graph persistence
- overlay query returning graph roads and weak legacy roads
- legacy roads visible but not reusable
- route service shortest path on graph branches
- route service including reused spans
- carriage planner road corridor classification through graph membership
- old roads not participating in routing
- built-road tile refresh requesting complete tile snapshots
- unknown pixels not overwriting existing tile pixels

Verification commands:

- targeted JUnit tests for changed packages
- `.\gradlew.bat compileJava`
- `.\gradlew.bat build` after network packet or saved-data changes

## Acceptance Criteria

The refactor is successful when:

- new roads persist as graph edges and nodes
- old roads remain visible but are visibly legacy and cannot be reused
- planner preview clearly distinguishes new-build and reused spans
- confirmed builds do not generate build steps for reused spans
- completed graph roads are selectable and routable
- logistics and carriage routing use new graph roads
- legacy roads do not affect logistics or carriage routing
- road overlay drawing remains responsive in large networks
- road construction no longer causes black minimap tiles along the new road
- tests cover persistence, reuse, overlay, routing, and tile refresh regressions

## Attribution

RoadWeaver is MIT licensed:

- source: `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury`
- copyright: 2025 shiroha-233

Any directly copied or substantially adapted source file should include a short comment noting that it is adapted from RoadWeaver and should retain MIT attribution in the project notice.
