# Road Planner Recruits-style Fixed LOD + Route Preload Rendering Design

Date: 2026-05-03
Branch: `feature/road-planner-rebuild`

## Goal

Make the road planner minimap behave like Recruits' world map while preserving the route-based unknown-area rendering we added previously.

The user-visible target is:

1. Mouse wheel zoom should not make the minimap look like it is re-sampling, switching texture sets, or re-rendering terrain.
2. Zoom should only scale already prepared map tiles on screen.
3. Two-point / RoadWeaver route planning must still be able to force-load and render unknown, unexplored tiles along the computed route area.
4. Unknown route tiles should become real rendered terrain after the preload job, not remain hidden just because the player has not explored them locally.

## Reference Behavior: Recruits World Map

Recruits' world map implementation uses a single tile texture cache for the displayed map:

- `WorldMapScreen.mouseScrolled()` changes only `scale` and `offsetX/offsetZ`.
- `WorldMapScreen.renderMapTiles()` always fetches `ChunkTileManager.getOrCreateTile(tileX, tileZ)` from the same tile cache.
- It draws the same tile texture at a different screen size: `scaledTileSize = ChunkTile.TILE_PIXEL_SIZE * scale`.
- `ChunkTileManager.updateCurrentTile()` handles sampling/updating separately from zoom.

So in Recruits, zoom is a view transform. It is not a request to change LOD, re-sample terrain, or create a different tile family.

## Current Problem

Our current road planner map does this in `RoadPlannerMapCanvas.renderTiles()`:

- Selects `MapLod` from `view.scale()` through `RoadPlannerTileLodSelector.select()`.
- Calls `tileManager.resolveRenderableTile(tileX, tileZ, renderLod)`.
- When zoom crosses thresholds, rendering may switch from `LOD_1` to `LOD_2/4/8` or create/load placeholder tiles for those LODs.

This creates the visible feeling that scrolling zoom causes a re-render/re-sample. It also risks showing placeholder LOD tiles even when high precision `LOD_1` data exists.

There is also a separate LOD derivation correctness issue: `RoadMapLodPyramid.deriveDisplayPixels()` currently stretches the upper-left portion of the source tile for lower LODs instead of deriving a correct full-tile downsample. That should not block this design because the chosen path removes LOD switching from active minimap rendering.

## Design Decision

Use **Recruits-style fixed-source rendering** for the road planner minimap.

### Core Rule

`RoadPlannerMapCanvas` always renders `MapLod.LOD_1` tiles. Zoom affects only screen-space position and draw size.

No render-frame code should select LOD based on zoom.
No mouse-wheel zoom should cause terrain sampling, route preload, snapshot requests, or LOD tile creation.

## Rendering Flow

### Before

```text
view.scale()
  -> RoadPlannerTileLodSelector.select(scale)
  -> RoadPlannerTileManager.resolveRenderableTile(tileX, tileZ, selectedLod)
  -> may load/create LOD_2/LOD_4/LOD_8 tile
  -> draw
```

### After

```text
view.scale()
  -> compute visible tileX/tileZ and screen size
  -> RoadPlannerTileManager.resolveRenderableTile(tileX, tileZ, LOD_1)
  -> draw same high precision tile scaled by GUI
```

`RoadPlannerTileLodSelector` may remain in the codebase for future zoom-level optimization, but the active planner canvas will not use it.

## Route-based Unknown Tile Rendering

The route preload behavior remains required and should be preserved.

### Trigger Sources

Preload may be requested from:

- Entering the road planner.
- Auto-route / RoadWeaver route completion.
- Manual force-render selection.
- Two-point route calculation where the start and target imply a route corridor.

### Server-side Route Coverage

The server should continue to build a `RoadMapRoutePreloadPlan` from route points:

1. Try to approximate the path coverage as a large rectangular region when within configured limits.
2. If the rectangle exceeds the limit, degrade to path-only covered chunks.
3. Use the covered chunk set as the authoritative force-load/sampling scope.

For this feature, the important guarantee is:

- If the route goes through an unexplored or unloaded area, the preload service force-loads the covered chunks on the server and samples them anyway.
- The resulting tile packets are sent to the client and written into the `LOD_1` tile cache.
- The client can render those route tiles even though they were not previously explored by local player movement.

### Render Source for Unknown Tiles

The canvas does not decide whether an area is explored. It simply renders `LOD_1` tile cache contents.

Unknown route areas become visible by being populated through the preload pipeline:

```text
RoadWeaver/two-point route
  -> RoadMapRoutePreloadPlanner
  -> covered chunks / rectangle
  -> server force-load + RoadMapSnapshotService sample
  -> RoadPlannerMapTileSyncPacket(LOD_1)
  -> RoadPlannerTileManager.applyTileSync()
  -> LOD_1 tile image replaced/saved
  -> canvas draws it at any zoom
```

If a visible tile has never been explored and is not covered by a route preload, it may remain a placeholder. That is expected. The requirement is specifically that route-covered unknown tiles can be force-rendered.

## Cache Behavior

Keep the existing world/dimension/lod cache layout:

```text
roadplanner_map_cache/<world>/<dimension>/lod_1/<tileX>_<tileZ>.png
roadplanner_map_cache/<world>/<dimension>/lod_2/<tileX>_<tileZ>.png
roadplanner_map_cache/<world>/<dimension>/lod_4/<tileX>_<tileZ>.png
roadplanner_map_cache/<world>/<dimension>/lod_8/<tileX>_<tileZ>.png
```

But active road planner minimap rendering consumes only `lod_1`.

World/dimension isolation stays unchanged. Leaving a world should still clear in-memory tile state. Disk cache should remain available for re-entry unless explicitly deleted by a user action or future cache policy.

## Component Changes

### `RoadPlannerMapCanvas`

- Remove active use of `RoadPlannerTileLodSelector.select(view.scale())`.
- Resolve tiles with `MapLod.LOD_1` only.
- Keep screen-space scaling calculation similar to current code:
  - visible world bounds from `view.screenToWorldX/Z()`
  - tile range from `TILE_SIZE_BLOCKS`
  - `tileScreenSize = TILE_SIZE_BLOCKS * view.scale()`
- Draw the selected `LOD_1` texture at the computed size.

### `RoadPlannerTileManager`

- `resolveRenderableTile(tileX, tileZ, MapLod.LOD_1)` should not create or touch lower-detail LOD buckets.
- Existing LOD-aware methods can stay for compatibility and tests, but canvas should not exercise them during zoom.

### Preload Service / Packets

- Route preload should at minimum emit `LOD_1` tile packets.
- Emitting `LOD_2/4/8` may remain if already implemented, but those packets are no longer required for active canvas rendering.
- If performance becomes a concern, later implementation may stop deriving/sending `LOD_2/4/8` during route preload. That is optional and outside this design's minimum fix.

## Non-goals

- Do not redesign the whole map colorizer.
- Do not remove the disk cache layout.
- Do not remove LOD enum/classes unless they become actively harmful.
- Do not make arbitrary unexplored areas render without a route/force-render/preload request.
- Do not implement a new visual style; the change is about render behavior and preload semantics.

## Testing Plan

Add or update tests to prove:

1. `RoadPlannerMapCanvas` requests `LOD_1` regardless of scale.
2. Zooming from high scale to low scale does not request `LOD_2`, `LOD_4`, or `LOD_8` from the tile manager.
3. Route preload packets with `LOD_1` still populate the tile cache and are renderable by the canvas.
4. Existing route preload planner tests still prove rectangle-first and path-only fallback behavior.
5. Existing world/dimension cache isolation tests remain passing.

Optional future test:

- A preload job for an unknown route-covered chunk should generate an `LOD_1` tile packet even when that chunk was not previously present in the client cache.

## Success Criteria

The work is complete when:

- Scrolling zoom in the planner only scales existing `LOD_1` textures.
- No LOD switching or LOD placeholder creation occurs as a direct result of zooming.
- Two-point / RoadWeaver route preload still force-loads route-covered unknown chunks on the server and sends rendered `LOD_1` tiles to the client.
- The generated `sailboatmod-1.3.7-all.jar` passes compile/tests and can replace the Prism instance jar.
