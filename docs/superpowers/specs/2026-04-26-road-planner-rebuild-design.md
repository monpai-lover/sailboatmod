# Road Planner Rebuild Design

## Decision Summary

Use approach A: build a new road planning and construction system while retaining only the old tool shell and registrations.

The old road planning entry points, old road preview pipeline, old pathfinding-driven bridge placement, and old construction orchestration are not part of the new flow. RoadWeaver is the primary source for route creation, road geometry, structure/bridge building, placement rules, and path construction algorithms. The new system ports/adapts RoadWeaver core modules into `com.monpai.sailboatmod.roadplanner.weaver`, while our mod provides the Elementa planner UI, server map snapshots, session flow, packets, and queued build/rollback shell.

## Goals

- Let players select a destination and manually design a route on a top-down real map.
- Support multi-region planning when the destination is outside the current map area.
- Convert player-drawn control nodes into smooth, walkable roads using curve lofting and terrain shaping.
- Support road, bridge, tunnel, erase, and select tools in one Elementa `RoadPlannerScreen`.
- Build roads through a new lightweight queued construction system with progress, cancel, rollback, and restart recovery.
- Keep visible previews clean: show road/bridge/tunnel geometry, not invisible `AIR` clearing steps.
- Directly port RoadWeaver route creation, geometry, path placement, bridge, and structure-building modules, then expose them through the new manual planner and later automatic completion.

## Non-Goals For The First Implementation

- Do not keep the old manual road planner GUI or old planning entry point.
- Do not route new planning through `StructureConstructionManager` or the old `RoadBuilder` path chain.
- Do not copy RoadWeaver platform/bootstrap code blindly; port the algorithmic route, geometry, placement, bridge, and structure-building modules into this mod with our package names and queue interfaces.
- Do not require the old bridge placement heuristics to decide bridge target positions.
- Do not render unknown regions from client-only chunk state; the server must provide planning map snapshots.

## Existing Code Boundaries

### Keep As Shell Or Reference

- `RoadPlannerItem`: keep as the player-facing tool, but rewire actions to the new destination and planner flow.
- Mod item, packet, and client registration infrastructure.
- Existing Elementa setup already used by `MarketScreen`.
- Recruits reference ideas: `ChunkImage`, `ChunkTile`, `ChunkTileManager`, and `WorldMapScreen` patterns for top-down map coloring, chunk/tile cache, coordinate conversion, zoom, and route overlays.
- RoadWeaver MIT-licensed core modules are primary references and migration sources: `features/path/pathlogic/core`, `features/path/pathlogic/pathfinding`, `features/path/pathlogic/surface`, `features/path/pathlogic/bridge`, `features/path/bridge`, `features/highway/generation`, `features/highway/placement`, `features/highway/pathfinding`, `core/model`, `pathfinding`, `planning`, and `util/Line.java`.
- Existing sky clearing concept, but it must be part of the new build task pipeline.

### Replace For New Flow

- Old manual planner service entry points.
- Old planning preview packets as the source of road planning truth.
- Old `RoadPlacementPlan` as the primary planning model.
- Old `StructureConstructionManager` road construction path for new roads.
- Old pathfinding-first bridge placement logic.

## User Flow

1. Player sets a destination.
2. Player opens `RoadPlannerScreen` from the road planner tool.
3. The server prepares a real top-down map snapshot for the current planning region.
4. Player selects a tool: road, bridge, tunnel, erase, or select.
5. Player draws control nodes on the minimap.
6. If the destination is outside the current region, player clicks next region.
7. The current region segment is saved, the exit node becomes the next entry point, and the next region center is computed toward the destination.
8. Player repeats drawing across regions until the route reaches the destination.
9. Player reviews the compiled preview: centerline, width footprint, bridge/tunnel spans, problem markers, start, destination, and nodes.
10. Player confirms construction.
11. Server creates a queued `RoadBuildJob` and progressively executes road, bridge, tunnel, terrain, clearing, and decoration tasks.
12. Player can monitor progress, cancel, or rollback.

## Destination System

### Supported Setters

- Enter coordinates in UI.
- Right-click a block with the road planner tool.
- Save current player position as destination.

### Saved State

```java
record RoadPlanningSession(
    UUID sessionId,
    UUID playerId,
    ResourceKey<Level> dimension,
    BlockPos startPos,
    BlockPos destinationPos,
    int activeRegionIndex,
    RoadPlan plan
) {}
```

The session is server-authoritative. The client may cache UI state, but confirmation must use the server session.

## Core Data Model

```java
record RoadNode(
    BlockPos pos,
    long createdAtTick,
    NodeSource source
) {}

enum NodeSource {
    MANUAL,
    ROADWEAVER,
    IMPORTED
}

enum RoadToolType {
    ROAD,
    BRIDGE,
    TUNNEL,
    ERASE,
    SELECT
}

record RoadStroke(
    UUID strokeId,
    RoadToolType tool,
    List<RoadNode> nodes,
    RoadStrokeSettings settings
) {}

record RoadSegment(
    int regionIndex,
    BlockPos regionCenter,
    BlockPos entryPoint,
    BlockPos exitPoint,
    List<RoadStroke> strokes,
    boolean completed
) {}

record RoadPlan(
    UUID planId,
    BlockPos start,
    BlockPos destination,
    List<RoadSegment> segments,
    RoadSettings settings
) {}

record RoadSettings(
    int width,
    Block mainMaterial,
    Block edgeMaterial,
    boolean enableBridge,
    boolean enableTunnel
) {}
```

### Width Rules

Supported widths are 3, 5, and 7. Width affects footprint expansion and terrain smoothing, not the player's control nodes.

- Road footprint: selected width.
- Terrain smoothing footprint: selected width plus one block of side buffer.
- Sky clearing footprint: road footprint plus side buffer.

## Map Region System

### Region Geometry

- Default region size: 128 blocks by 128 blocks.
- Initial region center: player start position.
- Region coordinates operate in world X/Z.
- Region Y is sampled per pixel or node as needed.

### Region Navigation

When clicking next region:

1. Save current region strokes into `RoadSegment`.
2. Resolve the last valid node as `exitPoint`.
3. Compute normalized direction from exit point to destination.
4. Move the region center by one region-size step in that direction, snapped to a stable grid if needed.
5. Create or load the next `RoadSegment`.
6. Set the previous exit point as the new entry point.

Previous region loads existing strokes and map snapshots from session state.

## Server Map Snapshot Pipeline

The minimap must show real top-down terrain, not an abstract placeholder. The client cannot rely on already-loaded chunks because planning may target areas outside the player's current view distance.

### Snapshot Request

Client sends:

```java
record RoadMapSnapshotRequest(
    UUID sessionId,
    int regionIndex,
    BlockPos regionCenter,
    int regionSize,
    MapLod requestedLod
) {}
```

### LOD Levels

```java
enum MapLod {
    LOD_1, // 1 block per pixel
    LOD_2, // 2 blocks per pixel
    LOD_4  // 4 blocks per pixel
}
```

Rules:

- The active editing region must eventually have `LOD_1` before confirmation.
- Adjacent or future regions may start with `LOD_2` or `LOD_4` for fast preview.
- UI may show loading/progressive refinement as snapshots arrive.

### Threading Model

Minecraft world access must remain safe.

1. Main server thread loads or tickets required chunks for the region.
2. Main server thread samples pure immutable column data:
   - top block state
   - surface Y
   - water/fluid state
   - optional biome key
   - optional light/shading data
3. Background worker converts sampled column data into pixel data, applies relief shading, compresses payload, and splits large packets if necessary.
4. Client receives snapshot chunks, reassembles them, creates or updates a `DynamicTexture`, and renders through Elementa.

### Map Coloring

Use the recruits `ChunkImage` approach as reference:

- Use the top block `MapColor`.
- Apply relief brightness based on neighboring heights.
- Use water depth to darken water.
- Use transparent/placeholder pixels only for missing data.

## Elementa RoadPlannerScreen

### Layout

- Left toolbar:
  - Road tool
  - Bridge tool
  - Tunnel tool
  - Erase tool
  - Select tool
- Center:
  - Real top-down minimap texture
  - region grid
  - current strokes
  - compiled centerline preview
  - width footprint overlay
  - bridge/tunnel colored overlays
  - start marker
  - destination marker or edge arrow
  - problem markers
- Right panel:
  - destination info
  - distance
  - active region index
  - map LOD/loading state
  - width 3/5/7
  - material settings
  - validation warnings
  - build job status when active
- Bottom buttons:
  - Previous region
  - Next region
  - Undo node
  - Clear current region
  - Auto complete
  - Confirm build
  - Cancel

### Coordinate Conversion

World to GUI:

```text
guiX = mapLeft + (worldX - regionMinX) / regionSize * mapWidth
guiY = mapTop  + (worldZ - regionMinZ) / regionSize * mapHeight
```

GUI to world:

```text
worldX = regionMinX + (mouseX - mapLeft) / mapWidth  * regionSize
worldZ = regionMinZ + (mouseY - mapTop)  / mapHeight * regionSize
worldY = sampled height at worldX/worldZ
```

The client uses the latest snapshot height for live UI feedback. The server revalidates heights before confirmation.

## Drawing And Editing

### Road Drawing

While the player drags on the map:

- Convert mouse position to world X/Z.
- Read Y from the latest active snapshot.
- Add a node every 4 to 8 blocks, depending on zoom and path curvature.
- Reject duplicate or jitter nodes.
- Store nodes in the current `RoadStroke`.

### Tool Semantics

- Road tool: creates ordinary road strokes; may use flat bridge for small water crossings.
- Bridge tool: creates explicit bridge strokes; these compile into bridge spans and can use the retained large bridge geometry system.
- Tunnel tool: creates explicit tunnel strokes; compile into tunnel tasks.
- Erase tool: removes strokes or node ranges under the cursor.
- Select tool: selects and moves nodes/strokes, changes tool type, or edits width/material overrides.

## Path Compilation

Player nodes are control points, not final build positions.

Compilation stages:

1. Merge all region strokes by region order and node order.
2. Enforce continuity from start to destination.
3. Build curve control data from nodes.
4. Generate a smooth centerline using Catmull-Rom or Bezier interpolation.
5. Resample centerline at fixed spacing.
6. Smooth height profile across terrain.
7. Classify terrain spans:
   - normal road
   - flat bridge
   - explicit large bridge
   - canyon bridge
   - tunnel
   - invalid/problem segment
8. Expand centerline to width 3/5/7 footprint.
9. Generate a `CompiledRoadPath`.

```java
record CompiledRoadPath(
    UUID planId,
    List<BlockPos> centerline,
    List<CompiledRoadSection> sections,
    List<RoadIssue> issues,
    RoadSettings settings
) {}
```

### Terrain Engineering

For ordinary road sections:

- Smooth height profile to avoid unwalkable steps.
- Flatten road footprint to the target road surface Y.
- Add embankment/foundation where terrain drops.
- Cut small bumps above the road surface.
- Clear trees from terrain surface to sky over the footprint plus side buffer.

The clear-to-sky task produces `AIR` build steps for execution and rollback only. It must never be displayed as visible road preview geometry.

## Bridge And Tunnel Design

### Road Tool Crossings

If a road stroke crosses small water or shallow gaps, the compiler may create a simple flat bridge section automatically.

### Bridge Tool

Bridge tool strokes explicitly request bridge geometry. The new compiler converts these strokes into `RoadSpan` data and calls the retained bridge geometry library with a final centerline that already matches the player's intended route.

Rules:

- Bridge spans are derived from the compiled centerline, not raw terrain scans away from the path.
- Bridge deck positions must remain within the bridge section footprint.
- Sky clearing above bridge deck/ramp columns is included as non-visible build steps.

### RoadWeaver Core Port

RoadWeaver is not a later stub. Its algorithmic modules become the new build and geometry core. The implementation ports/adapts these groups into `com.monpai.sailboatmod.roadplanner.weaver`:

- Route/path creation: `RoadPathCalculator`, `HeightProfileService`, `RoadHeightInterpolator`, RoadWeaver pathfinding helpers, and planning utilities.
- Geometry: `Line`, `RoadDirection`, segment frame/projection logic, span/placement models.
- Surface placement: `SegmentPaver`, `RoadBlockPlacer`, `SurfacePlacementUtil`, `PlacementRules`, `AboveColumnClearer`.
- Bridge logic: `BridgeRangeCalculator`, `BridgeSegmentPlanner`, `BridgeBuilder`, `BridgeSegment`.
- Highway/large road logic: highway pathfinding, height smoothing, segment paver, and placement utilities where useful.
- Structure-building support: RoadWeaver roadside/bridge structure precompute and structure placement concepts are adapted into queued build tasks, not worldgen injection.

The new manual planner converts player strokes into RoadWeaver-compatible control paths. RoadWeaver then produces road placements, target heights, spans, bridge sections, and structure/decoration build candidates. The queue owns execution and rollback.

## Preview System

Pre-confirm preview shows:

- Control nodes.
- Smooth centerline.
- Road footprint for selected width.
- Bridge and tunnel sections with separate colors.
- Start and destination markers.
- Destination edge arrow if destination is outside the current region.
- Validation issues.

Construction preview shows remaining visible build geometry only.

Never show:

- `AIR` clearing steps.
- rollback-only snapshots.
- invisible helper/support markers not meant to become visible blocks.

## Build Queue System

### External Job

```java
record RoadBuildJob(
    UUID jobId,
    UUID planId,
    UUID ownerId,
    ResourceKey<Level> dimension,
    RoadBuildStatus status,
    List<RoadBuildTask> tasks,
    int activeTaskIndex,
    RollbackLedger rollbackLedger
) {}
```

Statuses:

- queued
- running
- paused
- completed
- cancelling
- rolled_back
- failed

### Internal Tasks

```java
enum RoadBuildTaskType {
    CLEAR_SKY,
    TERRAIN_SMOOTH,
    FOUNDATION,
    ROAD_SURFACE,
    BRIDGE,
    TUNNEL,
    DECORATION
}
```

Each `RoadBuildTask` owns a bounded list of `BuildStep` records or lazily generates them from the compiled path.

### Execution Rules

- Execute a limited number of steps per tick.
- Before setting a block, record the original block state in the rollback ledger.
- Do not duplicate rollback entries for the same execution event incorrectly; preserve reverse-order correctness for repeated positions.
- Persist job state and rollback ledger periodically and on server stop.
- Cancel runs rollback in reverse order.
- Missing rollback snapshots must skip restoration rather than writing air.

## Networking

Required packets:

- Open planner session.
- Set destination by coordinate/current position/right-click block.
- Request map snapshot.
- Send map snapshot chunk.
- Add/update/delete stroke.
- Navigate region.
- Request compile preview.
- Send compile preview.
- Confirm build.
- Send build job progress.
- Cancel/rollback job.

Server remains authoritative for session state, map sampling, path compilation, and build jobs.

## Persistence

Persist:

- Active road planning sessions if needed.
- Confirmed `RoadPlan`.
- `CompiledRoadPath` or enough source data to rebuild it.
- Active `RoadBuildJob` state.
- Rollback ledger.
- Completed road network metadata.

Map snapshots may be cached client-side or server-side but are not authoritative save data.

## Validation

Before confirmation:

- Destination must be connected by region segments.
- Active regions must have sufficient LOD for edited strokes.
- Node chain must be continuous.
- Width must be 3, 5, or 7.
- Bridge and tunnel sections must have valid entrances/exits.
- Ordinary road slopes must be smoothable.
- Build footprint must be bounded and not exceed configured step limits.

Invalid segments show visual problem markers and block confirmation.

## MVP Scope

MVP includes:

1. Destination setting through coordinates, right-click block, and current position.
2. New Elementa `RoadPlannerScreen`.
3. Server-backed real top-down map snapshots with LOD support.
4. 128x128 multi-region planning.
5. Manual drawing of road, bridge, and tunnel strokes.
6. Width selection 3/5/7.
7. Curve compilation and terrain smoothing.
8. Tree clearing to sky for road/bridge/tunnel footprints.
9. Ordinary road build tasks.
10. Simple flat bridge for road-tool crossings.
11. Explicit bridge-tool sections routed through the retained bridge geometry library.
12. New queued `RoadBuildJob` with progress, cancel, rollback, and restart recovery.
13. Clean previews that exclude `AIR` steps.
14. RoadWeaver core route, geometry, surface placement, bridge, and structure-building modules ported behind the new planner adapter.

## Later Phases

- RoadWeaver automatic completion UI that uses the already-ported RoadWeaver pathfinding core.
- More advanced bridge styles and decorations.
- Tunnel interior shaping and lighting presets.
- Client/server map tile cache reuse.
- Road material presets and biome-aware styling.
- Worker NPC integration or builder hammer acceleration through adapters.
- Import/export road plan templates.

## Risks And Mitigations

### Chunk Loading Cost

Risk: force-loading 128x128 regions can be expensive.

Mitigation: ticket only the needed chunks, use LOD-first progressive loading, limit concurrent sessions, and release tickets when the player leaves the planner.

### Thread Safety

Risk: background threads reading world state can crash or corrupt state.

Mitigation: only immutable sampled column data crosses to background workers.

### Packet Size

Risk: LOD_1 snapshots are larger than normal UI packets.

Mitigation: compress pixel payloads and split snapshot chunks.

### Old System Coupling

Risk: reusing old planner pieces reintroduces current bridge/path bugs.

Mitigation: old planner entry points are not used; retained code is only called as geometry libraries with new inputs.

### Preview Noise

Risk: sky clearing and rollback helper steps can flood preview.

Mitigation: preview uses visible geometry filters and never renders `AIR` steps.

## Acceptance Criteria

- Player can set a destination and open the new RoadPlannerScreen.
- The current 128x128 region displays a real top-down map from server snapshots.
- Player can draw nodes and see a smooth preview with selected width.
- Player can move to the next region and continue from the prior exit point.
- Confirmation creates a queued road build job.
- Build job progressively constructs visible road/bridge/tunnel geometry.
- Cancel restores original blocks through rollback.
- Trees and canopies above the road footprint are cleared during construction.
- No visible preview boxes appear for `AIR` clearing steps.
- Old road planning entry points are not used by the new flow.



## Addendum: Explicit Pier Bridge Tool Backend

The Bridge Tool must preserve our current pier-supported large bridge construction capability. RoadWeaver bridge logic is used for automatic/simple crossings and route generation, but explicit Bridge Tool strokes must route through a new adapter around the existing pier bridge/deck/ramp/railing builder.

Rules:

- Player-drawn Bridge Tool centerline and endpoints are authoritative.
- The adapter converts bridge strokes into the existing pier-supported bridge geometry input.
- Visible pier, deck, ramp, railing, and decoration steps must remain aligned to the compiled Bridge Tool centerline.
- Sky-clearing above bridge deck/ramp columns remains non-visible build work.
- RoadWeaver must not replace the explicit Bridge Tool backend; it can only support automatic/simple bridges for road-tool or auto-generated routes.

## Addendum: Automatic Bridge Strategy Selection

Automatic route generation may use both bridge systems. The compiler chooses bridge backend by terrain severity:

- Small or shallow water crossings use RoadWeaver automatic/simple bridge geometry.
- Small gullies or short canyons use RoadWeaver automatic/simple bridge geometry.
- Wide water, deep water, long spans, or deep canyon crossings use the existing pier-supported large bridge builder through `PierBridgeToolAdapter`.
- Explicit Bridge Tool strokes always use the pier-supported large bridge backend unless the player later selects a simple-bridge override.

The bridge backend decision is made after compiling the centerline and sampling terrain along the section. The decision must never move the bridge away from the compiled centerline.

## Addendum: Pier Bridge Ramp Endpoint Grounding

Pier-supported large bridges must ground their ramp start and end points on valid terrain surface. Before bridge geometry is accepted, the adapter validates both ramp endpoints:

- The endpoint column must resolve to a non-air, non-liquid, stable surface block.
- The ramp start/end Y must be above that surface by the expected road deck offset, not floating in air and not buried underground.
- If a drawn endpoint is over water, leaves, replaceable vegetation, or an unstable cliff edge, the adapter searches along the bridge centerline direction toward land until it finds a valid grounding point within the configured extension limit.
- If no valid grounding point exists, the bridge section is marked invalid and confirmation is blocked with an issue marker.
- Terrain smoothing may adjust the approach road to meet the grounded ramp endpoint, but it must not move the bridge endpoint away from the validated surface column.
