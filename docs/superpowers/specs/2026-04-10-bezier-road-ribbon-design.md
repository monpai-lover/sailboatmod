# Bezier Road Ribbon Design

## Summary

This design upgrades manual roads from a smoothed block path into a curve-driven road ribbon that is visibly suitable for carriage travel. The road remains anchored to the existing manual road lifecycle, bridge, terrain shaping, rollback, and demolition systems, but its geometry pipeline is split into clearer stages: route nodes, curve centerline, road ribbon, and block realization.

The target outcome is that a player sees roads that:

- keep a stable `7` block main-road feel on straight sections
- turn with a smooth centerline instead of obvious polyline kinks
- widen naturally on the outside of sharper turns
- climb hills as continuous graded roadbeds instead of broken slab strips
- still support bridge shaping, cancellation, rollback, and one-click demolition

## Goals

- Upgrade the current road smoothing pipeline to a curve-first geometry pipeline.
- Produce a visually continuous road ribbon instead of assembling the road from fixed cross-shaped slices.
- Set the default manual road width to a `7` block carriage-capable standard.
- Allow sharper bends to widen automatically on the outside edge up to roughly `8-9` total blocks.
- Keep the current road lifecycle features compatible, including build, cancel, demolish, and persisted owned-block rollback.
- Preserve bridge and terrain shaping behaviors while feeding them better geometric inputs.

## Non-Goals

- Replacing the current road pathfinder with a new global planning system in this pass.
- Building a full control-point editor or interactive bezier authoring UI.
- Replacing existing bridge generation with a fully template-driven bridge system.
- Reworking carriage AI, carriage speed, or carriage materials in this design.
- Introducing a separate decorative-road preset system beyond what existing road styles already support.

## Current Problems

### Centerline smoothing

`RoadBezierCenterline` already improves raw route nodes, but the current output is still essentially a discretized path with Catmull-Rom-like smoothing and raster fallback. It helps the road avoid harsh right-angle turns, but the resulting road still reads as many small blockwise decisions rather than one continuous roadway.

### Width construction

`RoadGeometryPlanner` currently derives each road slice from a fixed local cross section around the center path. That works for narrow roads, but it produces visible artifacts on wider curves:

- turns still feel segmented
- shoulders are hard-coded rather than curve-aware
- widening logic is not tied to turn severity
- the road edge is not a true rounded ribbon boundary

### Vertical continuity

The current height profile smoothing is useful, but it still starts from centerline samples and then places road blocks around them. When terrain climbs too aggressively, the final road surface can still look like several independent placements rather than a single graded roadbed.

## Proposed Architecture

Keep the current manual-road flow and lifecycle semantics, but make the road geometry pipeline explicit and layered.

### 1. Route nodes

The existing pathfinder remains responsible for semantic routing:

- avoiding water when possible
- preferring gentler terrain
- honoring blocked columns and active-road constraints

This stage continues to answer, "where should the road go in principle?"

### 2. Curve centerline

`RoadBezierCenterline` becomes responsible for generating a stable curve centerline from the route nodes.

The upgraded centerline stage will:

- simplify route nodes into a smaller set of control-worthy turning points
- build piecewise cubic curve segments in `x/z`
- sample the curve by arc length rather than by raw control-point spacing
- keep bridge-restricted spans conservative when needed

The result is a dense, evenly sampled center trajectory that can drive both surface generation and vertical interpolation.

### 3. Road ribbon

`RoadGeometryPlanner` no longer treats each center point as a fixed plus-shaped slice. Instead, it interprets the centerline as a continuous 2D roadway:

- compute a tangent for each sampled center point
- derive a perpendicular normal
- offset left and right boundaries from the center using width rules
- rasterize the filled ribbon area into block columns

This stage answers, "what exact ground footprint belongs to the road?"

### 4. Block realization

Once the road ribbon columns are known, existing road systems still decide how to materialize them:

- final placement heights
- slab versus stairs selection
- support and embankment blocks
- flat versus arched bridge shaping
- owned-block tracking for rollback and demolition

This stage answers, "what blocks get placed, removed, or restored?"

## Centerline Design

### Horizontal curve model

The road should use a piecewise cubic centerline model rather than the current single-purpose smoothing pass.

Recommended behavior:

- keep endpoints fixed to the resolved station/wait-area anchors
- preserve path order from the route nodes
- allow control-point relaxation away from bridges and other constrained spans
- evaluate the curve densely enough that turns remain smooth after block rasterization

The practical implementation can still use a Catmull-Rom-derived cubic conversion internally if that is the safest fit for the existing code, but the design target is a stable cubic centerline sampled by distance, not by ad hoc node stepping.

### Arc-length resampling

The centerline must be re-sampled at near-constant travel intervals. This avoids one of the biggest visual weaknesses in the current pipeline: point density changes with raw segment shape, which later causes uneven road slices and awkward turn widening.

The new centerline output should therefore:

- track cumulative travel distance along the curve
- emit sampled center points at roughly uniform world-space spacing
- preserve start/end alignment with the original route endpoints

### Constraints

The current safety rules remain valid:

- do not silently cross blocked columns
- do not allow excessive vertical jumps between adjacent realized samples
- keep bridge-only sections conservative rather than aggressively curving over water

If a smoothed candidate violates these rules, the system may still fall back to a safer baseline path.

## Road Ribbon Design

### Default width

Manual roads should present as a `7` block carriage road by default. This is the standard straight-road footprint.

The ribbon stage should treat that width as the nominal width, not as a hard-coded set of neighboring offsets.

### Outside-turn widening

Sharper turns should widen on the outside edge only. This better matches carriage turning behavior and preserves visual directionality.

Recommended width behavior:

- straight and gentle turns: remain at nominal `7` block width
- medium turns: widen outer edge by `1` block
- sharper turns: widen outer edge enough to approach `8-9` total blocks

The widening should be driven by local curve severity, not by a binary "corner or not" rule.

### Inside edge behavior

The inside edge should generally not shrink aggressively. Over-shrinking creates spikes, narrow choke points, and brittle rasterization.

Instead:

- keep the inside boundary mostly stable
- round off the edge through ribbon fill and cleanup
- remove single-block protrusions after rasterization

### Rasterization model

The ribbon should be rasterized as an area, not as repeated independent slices.

Conceptually:

1. evaluate successive centerline samples
2. treat each pair of samples as a small swept road segment
3. fill block columns whose centers lie within the segment width envelope
4. assign those columns back to nearby center samples for downstream placement ordering

This follows the useful part of the RoadWeaver approach while keeping compatibility with the current build-step model.

### Edge cleanup

After the raw ribbon footprint is rasterized, run a small topology cleanup pass to:

- remove isolated one-block spikes
- fill one-block holes inside otherwise continuous road surface
- reduce jagged turn edges

The cleanup must remain conservative so it does not expand the road into unrelated terrain.

## Vertical Profile Design

Horizontal curvature and vertical grade should be handled separately.

### Continuous grade profile

The `y` profile should come from a road-grade planner rather than directly inheriting whichever local terrain block sits under each final ribbon column.

Recommended process:

1. sample original terrain heights along the centerline
2. derive a continuous target grade along traveled distance
3. clamp grade changes to carriage-appropriate limits
4. feed that target profile into road-surface and terrain-shaping logic

This borrows the useful idea behind TongDaWay: first produce a continuous target height progression, then realize blocks from that profile.

### Slope realization

Once the target grade is known:

- central travel bands may use stairs on sustained climbing sections
- transition regions may use slabs where needed
- shoulders and side columns should follow the same target grade rather than snapping independently to local terrain

That keeps the whole road surface behaving like one engineered slope plane.

### Terrain shaping

The existing terrain shaping system remains in place, but it now receives a better target roadbed:

- cut where terrain is too high
- fill where terrain is too low
- create gradual embankments under wider curved roads

The widened ribbon footprint must be included in owned-block accounting so rollback remains complete.

## Bridge And Grade Integration

Bridges remain part of the vertical-profile system rather than becoming a separate geometry universe.

### Bridge continuity

Existing `RoadBridgePlanner` classification stays relevant:

- `NONE`
- `FLAT`
- `ARCHED`

But the bridge entries and exits should be treated as parts of the same continuous road-grade profile.

### Approach ramps

For bridge spans, the system should:

- raise road grade into the bridge deck
- maintain the selected bridge profile across the unsupported span
- lower the road grade back into terrain after the bridge

This avoids the current risk that a bridge deck and adjacent slope look like unrelated placements.

### Curve conservatism over bridges

Bridge spans should allow less aggressive lateral smoothing and widening than normal terrain road, because:

- ownership footprint is more expensive
- supports and abutments become harder to reconcile
- sharp lateral widening over unsupported voids looks structurally implausible

## Compatibility

This design intentionally preserves current road-lifecycle semantics.

### Must remain compatible

- manual road build flow
- unique road identity between the same two towns
- active-job persistence
- cancel rollback
- finished-road demolition
- owned-block restoration after reload

### Data compatibility

No new save system should be required for the first implementation pass. The geometry may produce richer footprints, but the road still resolves to:

- center-path-like sequencing for build order
- owned block sets for rollback
- derived geometry for reconstruction

## Testing Strategy

### Unit tests

Add or extend tests to cover:

- arc-length centerline resampling keeps spacing stable through curves
- curved roads preserve correct start and end columns
- nominal width remains stable on straight sections
- sharper turns widen only on the outside edge
- raster cleanup removes spikes without collapsing valid width
- curved slope segments keep continuous grade without obvious vertical discontinuities
- owned block sets include widened shoulders and filled turn areas

### Regression tests

Preserve existing behavior around:

- lifecycle cancel and demolish flows
- bridge profile selection
- terrain shaping rollback
- persisted runtime plan restoration

### Manual in-game checks

- build a long straight road and confirm it reads as a stable `7` block main road
- build a shallow curve and confirm the width stays visually controlled
- build a sharp curve and confirm the outside edge widens naturally
- build a road over rolling terrain and confirm slope continuity
- build a curved approach into a bridge and confirm the transition is smooth
- cancel and demolish a widened curved road and confirm all owned blocks are cleaned up

## File Impact

Expected primary files:

- `src/main/java/com/monpai/sailboatmod/construction/RoadBezierCenterline.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadPlacementPlan.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

Expected tests:

- `src/test/java/com/monpai/sailboatmod/construction/RoadBezierCenterlineTest.java`
- `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`
- road lifecycle regression tests that validate widened footprints remain removable

## Rollout Notes

This design should be implemented as a geometry upgrade, not a total road-system rewrite.

Recommended rollout order:

1. upgrade centerline generation and resampling
2. introduce ribbon rasterization with stable `7` block width
3. add outside-turn widening
4. integrate widened footprints with grade, bridge, and terrain shaping
5. extend owned-block accounting and regression tests

That sequence delivers visible wins early while keeping the existing lifecycle and persistence model intact.
