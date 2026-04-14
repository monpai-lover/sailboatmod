# Bridge Slab Pier Rollback Design

## Goal

Stabilize road and bridge construction by replacing stair-based slope handling with slab-based transitions, removing solid-filled bridge undersides, and ensuring bridge piers are fully rollback-safe.

## Confirmed Constraints

- All road slopes, including bridge approaches, must use only `slab + full block`.
- No road or bridge slope may place `stairs`.
- Water crossings with continuous bridge deck span length `<= 6` columns must not generate piers.
- Water crossings with continuous bridge deck span length `>= 7` columns must generate discrete piers only.
- Discrete bridge piers should be spaced at roughly every `4` deck columns.
- Bridge heads should not force extra piers unless structurally required by existing corridor support logic.
- Bridge underside must stay hollow between discrete piers instead of being filled as a solid wall.
- Bridge piers must be part of normal ghost/build/owned/rollback tracking so cancellation and demolition restore terrain correctly.

## Chosen Approach

Use the existing corridor-based bridge pipeline and change three layers:

1. `RoadGeometryPlanner`
Convert all stair-state generation into slab/full-block transition generation.

2. `RoadCorridorPlanner`
Tighten support index selection so short bridges produce no piers and long bridges produce only discrete piers at the approved spacing.

3. `StructureConstructionManager`
Stop runtime bridge foundation flooding for every bridge deck block and make rollback tracking explicitly include all planned piers and only those piers.

This keeps the current route-first, corridor-second architecture intact and avoids introducing a second bridge generation system.

## Architecture Changes

### 1. Slope Surface Generation

Current behavior:
- `RoadGeometryPlanner.resolveState(...)` turns slope bands into `stairs`.
- Bridge ramps inherit the same behavior, which causes facing/orientation errors.

New behavior:
- Replace stair conversion with slab-only transitions.
- Keep full blocks where the placement profile says the deck should be full height.
- Use slabs only on transition bands where the current smoothing logic previously emitted stairs.
- Preserve existing material families:
  - stone roads and bridges: `STONE_BRICK_SLAB` / `STONE_BRICKS`
  - desert roads: `SMOOTH_SANDSTONE_SLAB` / `SANDSTONE`
  - swamp roads: `MUD_BRICK_SLAB` / `MUD_BRICKS`
  - wood bridges if still used elsewhere: `SPRUCE_SLAB` / matching support family

Expected outcome:
- No facing-dependent blocks in road surfaces.
- Fewer repeated placement mismatches against reused roads.
- Simpler rollback comparisons because slabs do not depend on orientation.

### 2. Bridge Pier Planning

Current behavior:
- Corridor support placement may place too many support columns for a bridge span.
- Runtime bridge stabilization can still make the entire underside look solid.

New behavior:
- Bridge ranges with span length `<= 6` produce zero support indexes.
- Bridge ranges with span length `>= 7` use discrete support indexes only.
- Preferred spacing target is one pier every `4` deck columns across the interior of the span.
- Support selection remains constrained to supportable interior indexes and must not place piers into navigable main-span markers unless existing planner rules explicitly require that.

Expected outcome:
- Small crossings stay light and clean.
- Long bridges get visible piers rather than a filled bridge belly.

### 3. Runtime Placement and Foundation Rules

Current behavior:
- `tryPlaceRoad()` calls `stabilizeRoadFoundation()` for every bridge surface block.
- For bridge styles using stone supports, that can fill downward under many deck blocks and create a solid underside.

New behavior:
- Runtime foundation stabilization for bridge deck surface blocks must no longer flood downward by default.
- Only planned support positions from the corridor/ghost plan may extend downward as piers.
- Land roads keep their existing terrain stabilization behavior.
- Bridge lights and railings remain supported by their own planned ghost columns only.

Expected outcome:
- Hollow bridge spans between piers.
- No extra runtime-only support blocks that bypass rollback tracking.

### 4. Rollback and Demolition

Current behavior:
- Rollback tracking is mostly based on planned ghost blocks, owned blocks, terrain edits, and captured foundation states.
- Runtime-added bridge support fill can escape clean rollback if it was not part of the planned footprint.

New behavior:
- Every planned pier block must be included in:
  - `ghostBlocks`
  - `buildSteps`
  - `ownedBlocks`
  - rollback tracked positions
- No runtime-only pier fill should exist outside that tracked footprint.
- Existing gradual rollback timing remains unchanged in shape, but bridge piers must now be removed as part of the same batch flow.

Expected outcome:
- Cancelling construction or removing roads restores bridge piers correctly.
- No permanent pier residue remains occupying water or terrain.

## Component-Level Work

### `RoadGeometryPlanner`

- Replace stair-specific transition generation with slab/full-block logic.
- Remove or bypass stair-facing calculations where they are no longer needed.
- Keep path closure behavior unchanged.
- Preserve material family mapping for slab/full-block pairs.

### `RoadCorridorPlanner`

- Adjust support index selection to enforce:
  - no piers for bridge spans up to 6 columns
  - discrete interior piers for spans of 7+ columns
  - approximate 4-column pier spacing
- Keep lighting coverage logic intact.

### `StructureConstructionManager`

- Stop treating bridge surface placement as a trigger to fill support downward under every deck block.
- Keep support expansion only for planned support columns.
- Ensure rollback capture and owned block calculation include the exact planned pier footprint.

## Testing Plan

Add or update regression tests for:

1. Slopes do not emit stairs
- land slope plan contains slabs/full blocks only
- bridge approach plan contains slabs/full blocks only

2. Short bridge has no piers
- continuous water crossing length `<= 6` yields no support positions and no pier ghost columns

3. Long bridge has discrete piers only
- continuous water crossing length `>= 7` yields interior support columns spaced about every 4 columns
- intermediate deck columns between piers remain hollow

4. No solid bridge underside at runtime plan level
- created bridge plan should not contain support/foundation ghost columns below every deck column

5. Rollback tracks bridge piers
- captured rollback positions include planned pier blocks
- rollback restore removes or restores pier columns correctly

## Non-Goals

- No new visual bridge style family.
- No rework of route finding or bridge anchor search in this task.
- No change to rollback speed tuning unless required to keep existing tests correct.

## Risks

- Removing stair generation may change some existing land ramp silhouettes.
- If slab transitions are too aggressive, reused-road equivalence checks may need one more material-family adjustment.
- Bridge support index spacing must not break existing navigable span clearance tests.

## Acceptance Criteria

- Road and bridge slope construction never places `stairs`.
- Small bridges across water do not spawn piers.
- Long bridges spawn discrete piers only.
- Bridge spans are hollow between piers.
- Cancelling bridge construction or removing a bridge removes planned piers through normal rollback.
- Existing bridge routing and corridor continuity tests still pass.
