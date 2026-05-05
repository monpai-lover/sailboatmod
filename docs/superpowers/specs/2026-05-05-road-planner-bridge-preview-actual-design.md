# Road Planner Bridge Preview Actual Build Alignment Design

## Goal

Make Road Planner bridge preview match the actual bridge construction behavior as closely as possible. The preview should no longer use a separate visual-only bridge height and pier model that diverges from the real bridge builder rules.

## Current Problem

Road Planner preview/build currently routes through `RoadNodeStructureExpander` and `BridgeStructureEmitter`. This keeps Road Planner preview and Road Planner confirmation consistent with each other, but `BridgeStructureEmitter` has its own bridge profile logic:

- Deck height is derived from entry/exit terrain and a Road Planner-specific profile.
- Piers are emitted on deck samples every four blocks.
- Pier bottom uses `point.terrainY()` rather than explicit water surface and ocean floor information.

The older actual bridge construction path under `road/construction/bridge` uses a different model:

- `BridgeConfig.deckHeight`, default `5`.
- Short spans do not require piers.
- Long spans use deck height based on water surface and shore constraints.
- Pier foundations start at `oceanFloorY` and extend to `deckY`.
- Pier spacing uses `BridgeConfig.pierInterval`.

Because these are separate rules, Road Planner preview can show bridge piers and deck heights that do not match the actual intended bridge structure.

## Design Decision

Use the actual bridge construction geometry rules as the source of truth for Road Planner bridge preview/build. `BridgeStructureEmitter` will become an adapter from shared bridge geometry planning into Road Planner `BuildStep`s.

## Components

### Shared bridge geometry planner

Create a small shared planner in the Road Planner structure layer or bridge construction layer that computes bridge geometry without needing to place blocks directly.

It should expose:

- Bridge deck centerline points with final Y values.
- Ramp/deck phase per point.
- Pier center positions.
- Pier bottom and top Y values.
- Whether a span is short enough to skip piers.

Inputs:

- Centerline path.
- Bridge span indices.
- Source segment type (`BRIDGE_SMALL` / `BRIDGE_MAJOR`).
- Road width/build settings.
- Terrain and water sampling data.
- Bridge config values (`deckHeight`, `pierInterval`).

### Terrain and water data

Extend the Road Planner sampling path so bridge geometry can use the same water concepts as actual construction:

- `terrainY(x, z)` remains the surface/placement terrain height.
- `waterSurfaceY(x, z)` returns the detected water surface, defaulting to sea level-style fallback when no level is available.
- `oceanFloorY(x, z)` returns the solid floor below water; server-backed sampler uses the level heightmap.
- `isWater(x, y, z)` remains available for true runtime probing.

When `ServerLevel` is available, use real level data. In tests or no-level fallback, keep deterministic safe defaults.

### Road Planner bridge emitter

Modify `BridgeStructureEmitter` so it does not independently decide deck height and pier ranges.

Instead:

1. Build shared bridge geometry for each bridge span.
2. Emit ramp/deck surface blocks using existing Road Planner footprint helpers.
3. Emit railings using existing railing placement.
4. Emit piers from planned `oceanFloorY` to `deckY`.
5. Skip piers for short spans according to the same short-span threshold as actual bridge construction.

### Preview/build consistency

`RoadNodeStructureExpander` remains the single source for Road Planner preview and Road Planner confirmation. The change is inside bridge geometry generation, so preview and final build keep using the same `BuildStep` list.

## Behavioral Requirements

1. Short bridges should not preview or build piers when the actual bridge rules would skip them.
2. Long bridges should place piers from ocean floor/foundation Y up to the deck Y.
3. Road Planner bridge deck Y should follow actual bridge rules based on water surface and config deck height.
4. `BRIDGE_SMALL` may remain visually lower only if the shared geometry planner explicitly models that as an actual build rule; otherwise actual bridge rules win.
5. Preview and confirmation build must remain identical for the same nodes/settings/level.
6. Existing Road Planner road smoothing and footprint width behavior should not regress.

## Testing Plan

Add focused tests before implementation:

- A short bridge span emits ramp/deck but no `PIER` steps.
- A long bridge span emits `PIER` steps whose minimum Y matches `oceanFloorY` and whose maximum Y reaches just below or up to deck height according to the shared rule.
- A water-backed terrain sampler produces deck Y equal to `max(waterSurfaceY, 63) + BridgeConfig.deckHeight` where shore clamping does not raise it.
- Road Planner preview expansion and build compilation produce matching bridge pier/deck positions for identical inputs.

Existing focused tests for Road Planner bridge ramp/deck/preview should continue to pass.

## Out of Scope

- Reworking RoadWeaver pathfinding.
- Changing map/minimap LOD behavior.
- Changing Town/Nation UI map behavior.
- Introducing new bridge block art or templates.
- Replacing the entire construction queue system.

## Implementation Notes

Prefer a small geometry data structure over directly calling `BridgeBuilder.build(...)` from Road Planner if direct reuse would require incompatible `RoadMaterial` or `TerrainSamplingCache` dependencies. The important requirement is shared geometry rules, not forcing Road Planner to use a block placer API that does not fit its build settings.

Keep edits localized to bridge geometry, terrain sampling, and Road Planner bridge tests.
