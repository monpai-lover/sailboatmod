# Nation Core Holo And Road Placement Design

## Summary

This design unifies nation/town core holo rendering and the entire manual road pipeline around a single placement result. The current system splits responsibility across unrelated code paths: the preview packet only carries a center polyline, the preview renderer only draws line segments, the runtime construction job advances by center-path index, and the holo renderers use inconsistent text rendering rules. The result is visible mismatch between preview, construction ghosts, placed blocks, progress reporting, and legacy resume behavior.

The new design introduces a single internal planning artifact, `RoadPlacementPlan`, that becomes the execution truth for manual planning preview, runtime construction, hammer acceleration, HUD progress, and road-job save recovery. The road search itself shifts from "shortest path with aggressive simplification" to "human-like route selection" that prefers walkable, dry, flatter, existing-road-adjacent terrain, records route decision nodes, and only then applies Bezier smoothing as a visual road-construction step. Bezier curves never replace route choice; they only make already-valid land segments look natural before rasterization and validation.

## Goals

- Make `NationCore` and `TownCore` holo text share one rendering model.
- Make road preview, construction ghosting, actual placement, hammer acceleration, and HUD progress all read from the same road plan.
- Replace path over-simplification with human-like route selection and conservative smoothing.
- Use Bezier curves only for visual road shaping after route decisions are made.
- Preserve existing `RoadNetworkRecord` road topology data while extending runtime construction data.
- Resume old in-progress road jobs automatically when safe to do so.

## Non-Goals

- Reworking NPC worker animation or path visualization.
- Redesigning the persistent world road graph model away from `RoadNetworkRecord`.
- Introducing decorative-only ghost blocks that never become real placed blocks.
- Building long ornamental bridges; bridge usage stays short, rare, and strictly budgeted.

## Confirmed Problems

### Holo Rendering

- `NationCoreBlockEntityRenderer` uses `Font.DisplayMode.NORMAL`, four outline passes, and forced color adjustment.
- `TownCoreBlockEntityRenderer` uses `Font.DisplayMode.SEE_THROUGH` with a background shadow.
- The mismatch produces color drift, doubled text, and cramped Chinese text spacing.
- `NationCore` also uses different `shouldRenderOffScreen()` and view-distance behavior than `TownCore`.

### Manual Road Preview

- `ManualRoadPlannerService` currently sends only `List<BlockPos> path` in `SyncRoadPlannerPreviewPacket`.
- `RoadPlannerPreviewRenderer` only draws a polyline with `RenderType.lines()`.
- The preview can never represent the actual placed road footprint, bridge supports, or final shoulder/edge blocks.

### Routing

- `RoadPathfinder` still behaves like a shortest-route biased grid search with a strong straight-line recovery path after A*.
- `simplifyPath()` and `smoothPath()` can collapse a route that originally detoured around water or blocked structures back into an unnaturally straight result.
- The current bridge logic is not modeled as "what a human would walk toward first"; it is only a local penalty.

### Runtime Construction

- `StructureConstructionManager` road jobs are keyed around `path + currentIndex`.
- Runtime ghosts are derived from a center-path slice rather than the final set of road blocks.
- Builder hammer acceleration only advances path index rather than the next concrete placement steps.
- Legacy save recovery only knows how to resume from path progress, not from a unified placement plan.

## Design Principles

### One Execution Truth

Any road the player previews, confirms, resumes, accelerates, or watches in progress must come from one source of truth. There can be helper projections of that truth for UI or packets, but there cannot be separate preview, placement, and progress models that each "approximate" the road in different ways.

### Human-Like Route Choice

Route finding should resemble how a human would choose a usable road:

- Prefer dry land over water.
- Prefer flatter terrain over steep or jagged climbs.
- Prefer broad, open, already-road-adjacent terrain over narrow obstacle squeezes.
- Accept a short bridge only when a land-only route failed.

The pathfinder should therefore prioritize walkability and comfort over pure geometric shortness.

### Bezier As Geometry Polish, Not Search

Bezier curves are part of the road-building geometry stage. The search stage still decides which way the route travels and where it turns. Bezier smoothing is applied only after the route nodes are chosen, only on eligible land segments, and only if the resulting rasterized path still passes all safety rules.

### Conservative Post-Processing

Post-processing may never introduce a route that would have been invalid during search. Any smoothing or simplification must preserve obstacle avoidance, bridge budgets, and terrain safety.

## Architecture

### 1. `CoreHoloRendererHelper`

A shared helper will own:

- camera billboard setup
- `SEE_THROUGH` text rendering
- shared background shadow color
- centered line layout
- 12 px line spacing
- off-screen and view-distance policy
- blank-line filtering

`NationCoreBlockEntityRenderer` and `TownCoreBlockEntityRenderer` become small content adapters that provide ordered lines and colors. `NationCore` no longer uses forced color correction or four outline passes. It renders:

1. title: "国家核心"
2. nation name
3. optional war-status line only when active

Blank values and `-` are skipped instead of rendered.

### 2. `RoadPlacementPlan`

Add a new internal type, likely under `construction` or `nation.service`, containing:

- `centerPath`
- `sourceInternalAnchor`
- `sourceBoundaryAnchor`
- `targetBoundaryAnchor`
- `targetInternalAnchor`
- `ghostBlocks`
- `buildSteps`
- `bridgeRanges`
- `startHighlightPos`
- `endHighlightPos`
- `focusPos`

`ghostBlocks` must include final block states for all blocks that can really be placed during construction. `buildSteps` must be ordered so runtime construction, ghosts, hammer use, and progress all consume the same sequence.

### 3. `RoadPlacementPlanner`

This planner becomes the unified road-planning pipeline:

1. resolve town anchors
2. search human-like route nodes
3. perform conservative node simplification where safe
4. apply Bezier smoothing to eligible land segments
5. rasterize the curved centerline back to block columns
6. validate the rasterized centerline
7. derive final road geometry (`ghostBlocks`)
8. derive ordered construction steps (`buildSteps`)

The planner exposes:

- `planManualRoad(...)`
- `rebuildFromLegacyPath(...)`

Both return a `RoadPlacementPlan`.

### 4. `RoadConstructionRuntime`

The runtime road state remains managed by `StructureConstructionManager`, but the conceptual model changes from "path progress" to "placement plan progress". Runtime road jobs store:

- enough metadata to identify the road and owner
- the plan-derived center path
- plan-derived ghost blocks
- plan-derived build steps
- placed-step count
- source/target display names

Ghosts, progress percentage, hammer acceleration, and save persistence all read from this same runtime state.

## Routing Model

### Anchor Resolution

Each town resolves two anchors:

- `internalAnchor`: nearest usable in-town road node if available; otherwise a walkable core-adjacent position
- `boundaryAnchor`: the nearest exposed claim-edge land position in the target direction that is not blocked by structure footprint columns

The final route is planned in three stages:

1. source internal -> source boundary
2. source boundary -> target boundary
3. target boundary -> target internal

This prevents direct center-to-center line pulling and makes the road first exit and enter towns at sensible land edges.

### Human-Like Search Heuristics

The route search must strongly prefer how a person would choose to travel. Cost should reflect:

- water presence and water depth
- near-water exposure
- steep height jumps
- repeated climbing and descending
- tight turns
- blocked or cramped columns near buildings
- soft or poor ground

The search should reward:

- existing road adjacency
- flatter runs
- open terrain
- steady direction when the terrain remains good

The route result at this stage is a chain of decision nodes, not yet the final polished road.

### Two-Phase Water Policy

Main-route search happens in two phases:

1. land-only search, where water columns are forbidden
2. limited-bridge search, only if the land-only phase fails

Once a valid pure-land route is found within the search bounds, a bridge-using result must not replace it.

### Bridge Budget

Bridge use is tightly constrained:

- max contiguous bridge columns: 5
- max total bridge columns: 14
- max bridge share of final center path: 20%

Bridge segments are intentionally short, functional, and visually restrained. They are not smoothed into decorative arcs.

## Bezier Geometry Stage

### Role

Bezier smoothing is used to shape the visual road after route decisions exist. It does not replace route choice and does not create permission to cross hazards the search avoided.

### Eligible Segments

Only non-bridge land segments are eligible for Bezier smoothing. Bridge segments remain mostly straight.

### Required Validation

After generating Bezier control points and sampling the curve:

1. rasterize the curve to block columns
2. recompute surface placement
3. verify no new blocked structure columns are crossed
4. verify no new water exposure or bridge budget is introduced
5. verify no unsafe elevation change is introduced

If any validation fails, the planner falls back to the unsmoothed node-derived segment.

## Road Geometry Generation

### Centerline To Full Footprint

The planner must derive the full road block set from the final validated centerline, preserving the existing approximate 3-block road width unless local geometry requires narrower transitions.

Generated geometry may include:

- main road surface blocks
- shoulder or corner completion blocks
- bridge deck blocks
- required support blocks that will actually be placed

Decorative placeholders that will never be placed must not appear in the preview.

### Build Step Ordering

`buildSteps` are ordered for both visual coherence and runtime consistency:

1. primary road surface
2. shoulders and corner completion blocks
3. bridge support and bridge-specific completion blocks

Every step corresponds to a real final block placement. Runtime ghosts shrink by removing remaining steps from the tail of this ordered sequence.

## Packet And Client Changes

### `SyncRoadPlannerPreviewPacket`

Replace the path-only payload with a full preview payload that can represent:

- source town name
- target town name
- center path length or equivalent route stats if needed for overlay
- full `ghostBlocks`
- highlight/focus metadata
- confirmation state

The packet becomes a projection of `RoadPlacementPlan`, not a separately calculated preview format.

### Client Preview State

`RoadPlannerClientHooks.PreviewState` should hold:

- source town name
- target town name
- preview ghost blocks
- focus/highlight metadata
- awaiting-confirmation state

The client renderer uses ghost blocks, not polyline-only rendering.

### Preview Rendering

`RoadPlannerPreviewRenderer` stops using `RenderType.lines()` for the route itself. It should render filled translucent ghost blocks and optional outline boxes using the same visual language as runtime construction ghosts. The planning overlay text remains, but the world preview becomes a faithful projection of final construction output.

## Runtime Construction Changes

### Runtime Job Model

`StructureConstructionManager.RoadConstructionJob` changes from:

- `path`
- `currentIndex`
- `progress`

to a plan-centric representation with:

- `centerPath`
- `ghostBlocks`
- `buildSteps`
- `placedStepCount`
- display metadata

### Progress

Progress percent becomes:

- `placedSteps / totalSteps`

This aligns progress with what has actually been built rather than with how far a cursor advanced along the centerline.

### Ghost Preview

The runtime road ghost is derived directly from remaining build steps. As construction advances, the visible ghost road shrinks from the final road footprint instead of merely highlighting the next centerline segment.

### Builder Hammer

Builder hammer right-clicking a road ghost consumes the next batch of `buildSteps`, not just one center-path index. This keeps hammer acceleration synchronized with both world placement and ghost reduction.

## Persistence And Legacy Resume

### `ConstructionRuntimeSavedData.RoadJobState`

Extend the saved road-job format to store the data needed for the new runtime model. New-format saves should preserve enough information to restore:

- road id
- owner
- center path
- ghost block positions and block-state payloads
- build-step order
- placed-step count

### Legacy Compatibility

Old road job saves that only contain `path` must still resume automatically when safe:

1. load the old path
2. rebuild a `RoadPlacementPlan` through `rebuildFromLegacyPath(...)`
3. regenerate `ghostBlocks` and `buildSteps`
4. scan the world to infer which steps are already complete
5. continue construction from the inferred `placedStepCount`

If the world has changed enough that a safe rebuild is impossible:

- keep already placed world blocks untouched
- drop only the invalid runtime road job
- log a clear reason for the failed resume

Unsafe guessing is explicitly disallowed.

## Failure Handling

- If no safe plan can be generated, do not preview or queue construction.
- If the player confirms a preview but the recomputed plan hash no longer matches, require re-preview and re-confirmation.
- If Bezier smoothing fails validation, fall back to the unsmoothed segment.
- If legacy resume cannot safely reconstruct a plan, discard only the runtime job and preserve world state.

## Testing Strategy

### Automated Tests

Add or extend tests for:

- human-like routing preference:
  - chooses pure land when a land route exists
  - allows a short bridge only when land search fails
  - avoids steeper terrain when a flatter route exists
- conservative post-processing:
  - smoothing never adds blocked columns
  - smoothing never increases bridge usage beyond budget
  - smoothing never introduces extra water crossings
- preview serialization:
  - full preview packet round-trips ghost blocks and confirmation state
- legacy recovery:
  - path-only road jobs rebuild to a valid runtime job when safe
  - placed-step inference resumes from already-built blocks
- holo layout:
  - shared helper filters blank lines and preserves intended colors

### Compilation Verification

- run `.\gradlew.bat compileJava`
- run `.\gradlew.bat build` if broader validation is needed

### In-Game Verification

- `NationCore` and `TownCore` holo text render consistently in Chinese without double text, color drift, or stray `-`
- manual road planning shows a complete translucent road footprint instead of a line strip
- the confirmed construction ghost matches the preview
- runtime ghost shrinkage and hammer acceleration match real block placement
- an old in-progress road job resumes after world reload when reconstruction is safe

## Risks And Mitigations

### Risk: Planner Scope Explosion

The planner now owns anchor resolution, routing, smoothing, geometry, and build-step derivation. To avoid an oversized god-object, the implementation should split responsibilities into focused helper units if a single file becomes unwieldy.

### Risk: Legacy Resume Mismatch

Old path-only saves may reconstruct to slightly different final geometry. The mitigation is to preserve route intent from the legacy path where practical, infer already-built steps from actual world blocks, and refuse only when the reconstruction is unsafe.

### Risk: Visual/Runtime Drift Reappearing

The mitigation is architectural: all preview, construction, hammer, and HUD logic must read from the same runtime plan projection rather than each computing independent approximations.

## Assumptions

- `NationCore` keeps the optional third line for war status and does not add leader or capital-town lines.
- Roads remain roughly 3 blocks wide.
- Bridges are short utility segments, not large prebuilt bridge templates.
- This work does not redesign NPC worker visible animation.
