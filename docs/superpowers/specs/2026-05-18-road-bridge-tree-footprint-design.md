# Road Bridge, Tree, and Footprint Repair Design

Date: 2026-05-18

## Goal

Repair the road planner structure layer without changing the current auto-complete pathfinding algorithm.

The fixes cover four related construction problems:

- Trees and vegetation are being treated as normal ground during terrain sampling.
- Bridge ramps are too steep and their slab steps can become disconnected or floating.
- Turns and lateral road movement can leave holes between adjacent road footprints.
- Major bridges are sometimes raised too high for ordinary water crossings.

The implementation must preserve these existing behaviors:

- Do not change automatic pathfinding algorithm selection, fallback, or route generation.
- Keep the current rule that deep narrow water can still use a small bridge.
- Keep the current rule that tiny land islands inside a bridge span remain bridge nodes, not road nodes.
- Keep current road planner UI and bridge tool selection behavior unless the implementation plan explicitly scopes a UI label change.

## Reference Findings

RoadWeaver handles tree and footprint problems in two separate layers.

For tree cleanup, RoadWeaver clears the vertical column above placed road surface blocks during paving. It also blocks future tree-like worldgen near existing roads through road-position queries and mixins. For this project, the immediate issue is terrain and road-bearing sampling, so the first fix should ignore tree structures during sampling and let the existing construction cleanup remove them.

For road footprint continuity, RoadWeaver does not rely only on per-centerline slices. Its path post-processor rasterizes the whole road band: for every segment of the spline, it scans the segment bounding box and includes every block whose distance to the segment is inside the road half-width. Those rasterized blocks are then assigned to the nearest center sample and paved with an interpolated height. This naturally fills holes caused by turns, diagonal movement, and lateral road offsets.

The older bridge implementation around commit `2ebae234af82beb91450613679852c07e6c96bac` used a bridge span profile before emitting blocks. It planned approach-up, main-level or arch, and approach-down segments first, then produced corridor slices and repaired adjacent slice gaps. The current planner emits ramp/deck decisions directly per point, which is the source of many steep and disconnected ramp artifacts.

## Design

### Tree and Terrain Semantics

Extend the road surface heuristics so trees and natural vertical noise are not considered road-bearing terrain.

The ignored-surface logic should include:

- Vanilla leaves, logs, flowers, saplings, tall plants, vines, bamboo, sugar cane, cactus, mushroom blocks, roots, snow layers, and replaceable natural clutter.
- Registry-name fallbacks for modded blocks whose ids end with `_leaves`, `_log`, `_wood`, `_stem`, or `_hyphae`.
- Water plants such as kelp and seagrass where they can affect water or shore sampling.

This change is only semantic sampling support. It must not introduce worldgen mixins or a new tree prevention system in this pass.

### Bridge Height Profiles

Replace the current per-point bridge height choice with a profile-first bridge plan.

Bridge profile categories:

- Low arch bridge: short spans, including deep but narrow water. It stays close to shore height, usually rising only 1-2 blocks above the higher shore or enough to clear the water surface. It has no piers.
- Low bridge: medium spans. It may use supports where needed, but should not use full navigable clearance unless the span is explicitly large or classified as a major water crossing.
- Major pier bridge: long or broad crossings. It uses a higher main deck and piers, but its height should still be bounded by approach length.

Major bridge height should be tiered instead of always using `water + deckHeight` with `deckHeight = 5`:

- Ordinary long water crossing: prefer higher shore plus 1-2 blocks and water plus 2-3 blocks.
- Navigable or large crossing: allow water plus 5 blocks.
- If approach length is too short, lower the deck or reduce the level deck length instead of making the ramp steeper.

### Bridge Ramp Geometry

Bridge ramps should be generated as buildable half-step sequences instead of one-block-per-sample climbs.

Rules:

- Each one-block rise should have at least two horizontal samples when possible: lower half step, upper half step, then the next full block height.
- Flat bridge deck sections should use the main surface block, not alternating slabs.
- Slabs are used for ramp and one-block transition points only.
- Adjacent ramp/deck slices must touch or overlap. When two slices are horizontally adjacent but vertically offset, place the transition slab on the lower side instead of adding a vertical patch column.
- Piers are only emitted for deck/support spans, not every ramp point.

This mirrors the old corridor planner principles without rolling back the whole old system.

### Continuous Road and Bridge Footprints

Add a RoadWeaver-style band rasterization step for current road planner structure output.

For a complete centerline and width:

1. Build consecutive centerline segments from adjacent points.
2. For each segment, scan a small bounding box expanded by the road half-width.
3. Include every `(x, z)` whose squared distance to the centerline segment is within `halfWidth^2`.
4. Assign each included block to the nearest centerline index.
5. Resolve its Y by interpolating the centerline target height.
6. Emit the block using the phase for that assigned index.

This rasterized footprint should be used by bridge emission and road-surface emission where turns or lateral displacement can create holes. Existing perpendicular ribbon logic can remain as a fallback, but the final surface set must not leave disconnected gaps between adjacent slices.

### Data Flow

The intended structure-layer flow is:

1. Existing pathfinding and node selection produce centerline points and bridge spans.
2. Terrain sampling ignores trees and natural noise when determining terrain and water support.
3. Bridge spans generate a full height profile and phase profile.
4. Road and bridge surfaces use band rasterization to build continuous footprints.
5. Footprint blocks get interpolated target Y values.
6. Ramp/deck state selection chooses full blocks or slabs based on the height profile.
7. A final adjacency repair pass ensures neighboring slices touch or overlap.
8. Build steps are emitted with stable ordering and existing build phases.

## Non-Goals

- No changes to automatic pathfinding algorithms.
- No changes to route fallback behavior.
- No worldgen mixins for tree prevention in this pass.
- No broad rollback of the current road planner rebuild.
- No unrelated UI, market, nation, carriage, or map changes.

## Tests

Add or update focused tests for:

- Tree and natural-noise states are ignored as terrain-bearing surfaces, including registry suffix fallbacks.
- A short deep water span remains a low arch bridge and does not create piers.
- A medium or long bridge has ramps with adjacent Y deltas no greater than one block and enough horizontal samples for half-step transitions.
- Ordinary long bridges do not use full navigable clearance unless classified as major/navigable.
- A major bridge still places piers down to ocean floor or terrain foundation.
- Turn and lateral-offset footprints are continuous: adjacent slices must touch or overlap, and rasterized band coverage contains the expected inside-corner and swept-area blocks.
- Bridge ramp footprints on width 5 or 7 remain connected across every adjacent pair.

Minimum verification command:

```powershell
.\gradlew.bat compileJava
```

If the existing test suite is available in this branch, run the relevant unit tests for `roadplanner.structure`, path post-processing, and construction heuristics as well.

## Acceptance Criteria

- Trees no longer raise road or bridge sampling to canopy/log height.
- Construction preview/build output does not show bridge ramp slabs floating or disconnected.
- Short/deep/narrow water produces a small low bridge rather than an over-raised pier bridge.
- Ordinary long bridges are visibly lower than the current `water + 5` default when no navigable clearance is needed.
- Major bridges still keep usable clearance and support columns reach the water floor or foundation.
- Turns, diagonal movement, and lateral shifts do not leave holes in the road or bridge deck.
- No pathfinding algorithm code is changed.
