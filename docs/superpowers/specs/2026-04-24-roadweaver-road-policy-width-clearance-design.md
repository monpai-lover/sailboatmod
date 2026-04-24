# RoadWeaver-Style Road Policy, Width, and Clearance Fix Design

Date: 2026-04-24
Branch: `landroad_fix`

## Summary

Refactor the land-road planning pipeline enough to restore clear behavior for the two user-facing route options:

- `detour`: route around expensive crossings; only permit short conservative bridge exceptions.
- `bridge`: bridge-first crossing; do not silently degrade the bridge segment into detour-dominant A* routing.

At the same time, make road width and natural-obstacle handling behave like RoadWeaver's model:

- center path controls route direction only
- a dedicated rasterizer expands width into a continuous road footprint
- terrain sampling ignores tree/plant surface noise
- construction clears the full road footprint column above the road surface

This design fixes the observed cases where only `bridge` is shown in the UI, but the produced path mostly behaves like detour; where width collapses to one center line after rebuilding; where diagonal/curved road footprints have holes; and where trees/plants corrupt route height, preview, or construction.

## Current Root Causes

### 1. Bridge Option Contains Detour Logic

`BridgePlanner` currently builds a three-segment path:

1. land path from source to source shoreline
2. bridge path between shorelines
3. land path from target shoreline to target

The bridge middle segment starts as a straight line, but if it sees deep water it falls back to A* with a high water-depth cost. That fallback is a detour policy, not a bridge policy. As a result, a candidate labelled `bridge` can still be mostly detour.

### 2. Detour Option Can Be Hidden

The manual planner builds `detour` first and `bridge` second. The detour candidate is rejected when `maxWaterSpanLength(processed.bridgeSpans()) > 8`.

The problem is that `BridgeRangeDetector` now classifies both water gaps and land ravines into generic bridge spans. The detour filter's name says water, but it counts all spans. This can hide detour even when the user should still see a separate detour option.

### 3. Bridge/Detour Preview Labels Are Too Weak

The UI marks an option as bridge-backed if `bridgeSpans` is non-empty. That does not measure whether the route is actually bridge-dominant. A tiny bridge segment can label a mostly detour route as `bridge`.

### 4. Width Is Not Propagated Consistently

Several call sites use the default `PathPostProcessor.process(..., bridgeMinWaterDepth)` overload, which hardcodes half-width `1`. Manual detour preview also passes `3 / 2`, and candidate rebuild calls `RoadBuilder.buildRoad(...)` without upstream placements, forcing `RoadBuilder` to fall back to one-block center placements.

### 5. Road Footprints Are Not RoadWeaver-Style

A road center path should not directly become the block placement list. RoadWeaver expands width during post-processing and assigns blocks to path centers. This project has a `WidthRasterizer` helper, but it is not fully wired into the normal path-to-placement pipeline.

### 6. Build Steps Lose Segment-Growth Semantics

The current construction model is not RoadWeaver-style block/segment growth. `RoadBuilder` flattens land paving, bridge construction, and lighting into one global `BuildStep` queue, then `StructureConstructionManager` consumes that queue later. The queue itself is compatible with this mod's manual construction gameplay, but the queue is currently generated after key road semantics have already been weakened:

- `RoadData` returns build steps but does not preserve the full `RoadSegmentPlacement` list or a RoadWeaver-style `targetY` list.
- `RoadSegmentPaver` deduplicates road footprint cells by `x/z` globally, which can let one segment steal cells from another segment near turns and bridge heads.
- `RoadSegmentPaver` can replace the slope-limited placement Y with a terrain-clamped `surfaceY`, so the final placed column can diverge from the processed center path.
- Bridge steps are generated separately from land steps using `centerPath + bridgeSpans`, not from the same per-segment footprint model as the road deck approaching each bridge head.
- Rebuild paths that call `RoadBuilder.buildRoad(...)` without upstream placements collapse back toward center-line fallback behavior.

RoadWeaver's safer model is different: persisted road data keeps segment placements and target heights, then generation iterates segment-by-segment, computes each width block's Y by projecting against nearby center segments, clears the column, and places the road block immediately for that segment. The likely root cause of the observed offset/floating/bridge-head mismatch is this loss of segment-growth semantics, not merely the fact that construction is queued.

### 7. Vegetation Handling Is Split

Terrain sampling, road paving, construction satisfaction, and road preview use different ideas of what counts as a tree, plant, snow layer, replaceable natural obstacle, or real terrain. RoadWeaver uses a clearer model: ignore surface noise while sampling and clear the column above the selected road surface when placing.

### 8. Road Demolition May Bypass Rollback

Manual road cancel/demolish currently removes the road network record directly in the planner service. A real rollback pipeline already exists in `StructureConstructionManager.demolishRoadById(...)`, but the manual planner demolition path must be verified to call that pipeline instead of only deleting metadata. Otherwise a road can disappear from records while physical road blocks remain in the world.

### 9. Short Crossings Can Float or Over-Ramp

Short water and ravine crossings currently have two visual failure modes:

- the bridge deck can be generated without a real land connection at one or both ends
- a very short crossing can receive slope/ramp treatment, making it look like stairs instead of a simple flat bridge

RoadWeaver has bridge range and transition adjustment, but for this project the short-span behavior should be stricter: short spans should be physically anchored to both land heads and should remain flat over the crossing.

## Goals

- Restore two genuinely different route options: detour-first and bridge-first.
- Prevent a `bridge` candidate from silently using detour-heavy routing as its primary bridge segment.
- Prevent detour filtering from treating every bridge span type as water.
- Preserve road width through planning, preview rebuilding, build-step generation, and final construction.
- Use continuous width rasterization to remove diagonal/turn gaps.
- Generate the queued construction steps from a RoadWeaver-style segment-growth model, so manual construction stays queued but the geometry is segment-stable.
- Unify plant/tree/snow cleanup policy across sampling and construction.
- Keep the existing short-span flat bridge feature, but isolate it from generic detour/bridge routing decisions.
- Verify road demolition/cancel behavior restores or removes world blocks through the rollback pipeline, not only metadata.
- Make short water/ravine crossings more reliable than RoadWeaver by enforcing real two-sided land anchoring and no-ramp flat decks for eligible short spans.

## Non-Goals

- No full rewrite of the road system.
- No rewrite of the UI screen flow.
- No broad terrain generation or chunk-carving rewrite.
- No removal of existing short-span flat bridge support.
- No change to unrelated nation, town, market, boat, or structure systems.

## Proposed Architecture

### 1. Route Policy Layer

Introduce an explicit route policy concept, either as a small enum or equivalent internal parameter:

- `DETOUR`
- `BRIDGE`

This policy is not just a UI label. It controls what pathfinding and bridge detection are allowed to do.

#### Detour Policy

Detour policy uses the normal pathfinder and avoids large water/large ravine crossings. It may keep only conservative short exceptions:

- `SHORT_SPAN_FLAT` bridge spans are allowed.
- Long `REGULAR_BRIDGE` spans are not allowed.
- Water spans and land ravine spans are evaluated separately.
- If detour is impossible, report detour as unavailable instead of converting it into a bridge path.

#### Bridge Policy

Bridge policy uses bridge-first crossing behavior:

- The water or ravine crossing segment should prefer a direct or near-direct bridge line.
- Deep water must not trigger a detour-dominant A* fallback inside `BridgePlanner.findBridgePath()`.
- If the straight bridge is impossible or unsafe, the bridge candidate should fail or produce a separate labelled fallback, not masquerade as `bridge`.
- Land approach segments may still use normal land A*, but the bridge segment itself must be bridge-dominant.

### 2. Candidate Metrics and UI Labels

Each candidate should carry route metrics in addition to the label:

- `optionKind`: `detour` or `bridge`
- `pathNodeCount`
- `bridgeSpanCount`
- `bridgeNodeCount`
- `bridgeCoverageRatio`
- `regularBridgeSpanCount`
- `shortFlatSpanCount`

The UI can still show a simple label, but the backend should stop using `!bridgeSpans.isEmpty()` as the definition of bridge. A route is bridge-backed only if the selected policy is bridge or if bridge coverage exceeds a clear threshold.

### 3. Bridge Span Semantics

Keep `BridgeSpanKind`, but use it in filtering and preview decisions:

- `SHORT_SPAN_FLAT`: short pierless bridge over small water gap or ravine.
- `REGULAR_BRIDGE`: true regular bridge requiring normal bridge handling.

Add or derive a gap category when needed:

- `WATER_GAP`
- `LAND_RAVINE_GAP`

If adding a new field is too invasive, compute water/land classification at filtering time from the span's sampled columns.

### 4. Width Propagation

Road width must be converted once into half-width and carried through all road generation entry points.

Required behavior:

- manual planner candidate generation uses selected or default `ManualRoadPlannerConfig.width()`
- `PathPostProcessor.process(...)` receives the correct half-width for every candidate
- `BridgePlanner.plan(...)` passes width into post-processing, not just into building
- `rebuildSelectedCandidate()` rebuilds placements using the selected width instead of relying on `RoadBuilder` center-line fallback
- `RoadBuilder` fallback path uses a width rasterizer if placements are absent

Width mapping:

- width `3` -> half-width `1`
- width `5` -> half-width `2`
- width `7` -> half-width `3`

### 5. RoadWeaver-Style Width Rasterization

Use or complete the existing `WidthRasterizer` as the single footprint generator.

Expected behavior:

- create a cross-section perpendicular to each path segment
- stitch adjacent cross-sections together
- fill diagonal and curved gaps
- assign footprint blocks to stable segment indices
- preserve slope-limited Y values from the processed path

The current `PathPostProcessor.rasterizeSegments(...)` may remain as the high-level placement builder, but the actual footprint generation should use the rasterizer rather than per-cell distance checks or center-only fallback.

### 6. RoadWeaver-Style Segment-Growth Queue Generation

Keep this mod's queued manual construction, but generate the queue from a RoadWeaver-style segment model instead of from disconnected land/bridge passes.

Required model:

- preserve `RoadSegmentPlacement` in `RoadData` so preview, rebuild, demolition, and construction can reference the same segment footprint
- derive a `targetY` list from the processed center path and keep it aligned one-to-one with segment centers
- generate build steps by iterating segments in order and then iterating that segment's width footprint
- compute every footprint block's final road Y through a projection/interpolation helper equivalent to RoadWeaver's `RoadHeightInterpolator.batchInterpolate(...)`
- emit clear/foundation/surface/deck steps for that segment before advancing to the next segment
- keep bridge and land construction on the same segment index and footprint model, with bridge policy deciding which step family a segment emits

The construction manager may still consume a flat `BuildStep` queue. The important change is that the queue is the serialized output of segment growth, not the source of geometry truth.

Deduplication rules:

- do not dedupe road cells only by `x/z` before segment ownership is resolved
- when two adjacent segments claim the same cell, choose by nearest projection distance and stable segment order
- preserve phase-specific steps at the same `x/y/z` when they represent different construction semantics
- skip exact duplicate final states only after ordering and dependencies are known

Height rules:

- the processed center path supplies target road heights
- width blocks use projected/interpolated target Y, not raw terrain Y
- terrain height is used for support depth and cut/clear extent, not to silently move the road surface off the target Y
- short flat bridge spans force one deck Y across the span and validated bridge-head segments

Bridge-head rules:

- land approach, bridge deck, and bridge-head support must be generated from adjacent segment indices
- a short-span bridge is invalid if the full-width head footprint cannot be anchored to land on both sides
- bridge and land queues must meet at matching footprint edges; no center-line-only handoff is allowed
### 7. RoadWeaver-Style Surface and Vegetation Policy

Create one shared classifier for road terrain decisions. It should cover the existing `RoadSurfaceHeuristics` responsibilities and align construction cleanup with it.

#### Sampling Rules

Ignored surface noise includes:

- leaves
- logs and natural wood variants
- grass, tall grass, ferns
- flowers, tall flowers, saplings
- vines and cave vines
- bamboo, sugar cane, berry bush, cactus where appropriate
- snow layers
- replaceable plant-like blocks

Ignored surface noise must not become road-bearing terrain height.

#### Construction Rules

For each road footprint column:

- place surface/foundation at the chosen road surface Y
- clear blocks from `surfaceY + 1` through `surfaceY + clearHeight`
- clear one extra plant/snow-like block at `surfaceY + clearHeight + 1` if needed
- never clear bedrock, barrier, or protected non-natural blocks as part of natural cleanup

This mirrors RoadWeaver's `AboveColumnClearer.clearAboveColumn()` behavior while adapting it to build-step generation instead of direct worldgen placement.

### 8. Preview Cleanup

Preview should not render every construction step as a normal yellow block.

Rules:

- AIR cleanup steps are construction metadata, not normal ghost road blocks.
- road surface/deck/ramp/railing/lights can still be shown.
- bridge ranges and navigable-water bridge ranges must be separated.
- short flat bridge ranges should not be reported as navigable-water bridge ranges unless they actually cross navigable water.

This reduces vertical tower-like ghost previews and makes green route vs yellow build preview easier to compare.

### 9. Road Demolition and Rollback

The road planner has two user-visible destructive actions:

- cancel/remove road record
- demolish an existing road in the world

The implementation must verify which mode each UI action represents. If the action is meant to remove constructed road blocks, it must route through `StructureConstructionManager.demolishRoadById(...)` so rollback states, owned blocks, active construction jobs, persisted road jobs, and road network removal stay synchronized.

Expected behavior:

- active road construction rollback uses existing persisted rollback states
- completed road demolition finds or restores the road placement plan
- physical road blocks are removed/restored in rollback order
- road network metadata is removed only after rollback has started or completed according to the existing construction lifecycle
- preview and user messages should distinguish metadata-only cancel from physical demolition

### 10. Short-Span Land Anchoring and Flat Deck Rule

Short crossings should be more conservative and more predictable than RoadWeaver's generic transition smoothing.

For `SHORT_SPAN_FLAT`:

- require a valid left/entry land head and right/exit land head
- each land head must have stable support under the full road width footprint, not just under the center node
- bridge deck Y is fixed to `max(entryLandY, exitLandY)`
- every bridge block from the first crossing column through the last crossing column uses the same deck Y
- no pier steps, no bridge ramp steps, and no slab slope are generated inside the short span
- the deck must overlap or directly touch both land-head footprints, so there is no one-block floating gap at either end
- if either land head cannot be validated, do not create a short flat bridge; fall back to detour or regular bridge policy

This intentionally differs from RoadWeaver's transition adjuster. RoadWeaver smooths bridge approaches; this project should treat very short water/ravine gaps as a flat plate connecting two verified land ledges.

## Data Flow

### Detour Candidate

1. Resolve source and target anchors.
2. Run normal land pathfinder.
3. Post-process with selected half-width and detour policy.
4. Detect bridge spans.
5. Keep only allowed short-flat spans; reject true long bridge spans.
6. Validate short-flat spans have two real land heads before accepting them.
7. Build road using processed placements and filtered spans.
8. Emit candidate labelled `detour`.

### Bridge Candidate

1. Resolve source and target anchors.
2. Find bridge anchors or shoreline anchors.
3. Build direct/near-direct bridge segment for the crossing.
4. Use normal land pathfinding only for approach segments.
5. Post-process with selected half-width and bridge policy.
6. For short water/ravine spans, validate two-sided land anchoring and force a flat no-ramp deck.
7. Build road using processed placements and bridge spans.
8. Emit candidate labelled `bridge` only if bridge policy stayed bridge-dominant.

### Rebuild After Config Change

1. Read selected candidate and selected `ManualRoadPlannerConfig`.
2. Re-run post-processing for that candidate's center path with the selected half-width.
3. Recompute placements and bridge spans under the candidate's policy.
4. Build road with processed placements and filtered spans.
5. Replace only that candidate in the cached preview list.


### Build Step Generation

1. Start from processed center path, segment placements, bridge spans, and selected width.
2. Build or preserve a `targetY` list aligned with segment centers.
3. For each segment, assign each footprint cell a final Y through projection/interpolation against nearby center segments.
4. Classify the segment as land, short-flat bridge, or regular bridge using the policy-filtered spans.
5. Emit that segment's cleanup, support, surface/deck, and decoration steps in local order.
6. Resolve overlaps with stable nearest-segment ownership before flattening to the final `BuildStep` queue.
7. Store enough placement ownership for preview and rollback to refer to the same footprint used by construction.
## Error Handling and Fallbacks

- If detour cannot avoid a long crossing, detour candidate becomes unavailable.
- If bridge cannot create a bridge-dominant crossing, bridge candidate becomes unavailable or is labelled as a fallback explicitly.
- If both fail, show path failed.
- If width is invalid, normalize through `ManualRoadPlannerConfig.normalized(...)`.
- If vegetation cleanup encounters protected blocks, skip natural cleanup and let normal construction satisfaction decide whether the step is blocked.
- If a short crossing cannot prove both land anchors and full-width support, it is not eligible for `SHORT_SPAN_FLAT`.
- If segment placements and `targetY` cannot be aligned one-to-one, reject the candidate instead of generating a best-effort offset queue.
- If physical road demolition cannot find an active job, persisted job, or restorable placement plan, report demolition unavailable instead of deleting only the metadata record.

## Testing Strategy

Add or update focused tests around the changed logic.

### Candidate Policy Tests

- Detour rejects long regular bridge spans but keeps short flat spans.
- Bridge candidate does not use detour A* for deep water crossing.
- Bridge label requires bridge policy, not just non-empty bridge spans.

### Width Tests

- Rebuilt candidate with width `5` or `7` produces multi-block road footprint.
- `RoadBuilder` fallback uses rasterizer instead of center-only placements.
- Shallow diagonal road footprint includes previously missing cells.

### Segment-Growth Queue Tests

- Build steps are emitted in segment order for land approaches, bridge heads, and bridge decks.
- Width footprint Y values are interpolated from `targetY` rather than clamped to raw terrain height.
- Overlapping cells near turns choose stable nearest-segment ownership instead of arbitrary `x/z` first-writer wins.
- Bridge and land steps meet at matching full-width bridge-head footprints.

### Vegetation Tests

- Surface sampling ignores leaves/logs/grass/snow as road-bearing terrain.
- Paver emits clear steps over the whole road footprint.
- Protected blocks are not treated as natural cleanup.

### Preview Tests

- AIR cleanup steps are not included as normal visible ghost blocks.
- Navigable-water bridge ranges exclude non-water short flat ravines.

### Short-Span Anchor Tests

- Short water gap with stable land on both sides produces one flat deck Y and no ramp steps.
- Short ravine with stable land on both sides produces one flat deck Y and no ramp steps.
- Missing land head on either side rejects `SHORT_SPAN_FLAT` instead of producing floating bridge blocks.
- Full-width support is checked at bridge heads, not only center-line support.

### Demolition Tests

- Manual road demolition calls the rollback/demolition pipeline instead of only removing the road network record.
- Active road construction can be rolled back without orphaning metadata.
- Completed road demolition removes/restores owned road blocks and clears the road network at the correct lifecycle point.

## Verification Commands

Use focused checks first, then compile and package:

```powershell
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.road.*"
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false compileJava
.\gradlew.bat --% -Dnet.minecraftforge.gradle.check.certs=false build -x test
```

Full test suite may still contain unrelated existing failures. Those should be reported separately unless they directly cover this change.

## Rollout Order

1. Add route policy and candidate metrics.
2. Fix bridge planner so bridge does not fallback to detour-dominant A*.
3. Fix detour filtering to use span kind/category correctly.
4. Propagate selected width through all planning and rebuild paths.
5. Wire `WidthRasterizer` into post-processing and RoadBuilder fallback.
6. Generate `BuildStep` queues from RoadWeaver-style segment growth with aligned placements and `targetY`.
7. Centralize natural surface/clearance classification.
8. Enforce short-span land anchors and no-ramp flat deck construction.
9. Wire manual road demolition through the existing rollback pipeline and test it.
10. Filter preview ghost blocks and split bridge range types.
11. Run focused tests, `compileJava`, and `build -x test`.

## Acceptance Criteria

- UI can show both `detour` and `bridge` when both are valid.
- If only `bridge` is shown, its route is bridge-dominant rather than detour-dominant.
- Changing width to `5` or `7` visibly expands road footprint in preview and construction.
- Diagonal and curved roads do not leave width holes.
- The queued construction steps reproduce a RoadWeaver-style segment-growth footprint: stable segment ownership, interpolated width Y, and matching land/bridge heads.
- Trees, leaves, grass, and snow no longer distort road height or remain embedded in the road corridor.
- Preview no longer shows AIR cleanup as confusing vertical ghost structures.
- Short-span flat bridges remain pierless and flat for eligible small gaps.
- Short-span flat bridges physically touch validated land on both sides and do not generate ramp/slab slope blocks.
- Manual road demolition removes or rolls back constructed road blocks through the construction manager instead of only deleting the saved road record.
