# Road Planner, Preview, and Carriage Overhaul Design

## Scope

This design covers the next confirmed pass across manual road planning, preview rendering, road centerline smoothing, road-side lighting, and carriage presentation/handling.

Included:

1. Manual road planning must use post-station waiting-area exits only.
2. Manual road planning must fail clearly if either town cannot provide a valid post-station route anchor.
3. Manual road preview ghosts must render as world-locked blocks without movement jitter.
4. Manual road preview must not show long hint lines; only true build ghosts and anchor highlights remain.
5. Road centerline smoothing must move beyond local corner patching into full-segment Bezier sampling.
6. Road structure generation should support road-side lighting inspired by RoadWeaver-style engineered roads.
7. Road construction should overwrite minor surface clutter such as small flowers and grass instead of leaving gaps.
8. Carriage wood choice remains `oak`, `spruce`, and `dark_oak`, but wood appearance should come from model coloring rather than full texture swaps.
9. Carriage movement should stop feeling like a sailboat while keeping the existing transport-route framework.

Not included:

- A full rewrite of the automatic structure road-hint system.
- Full carriage inheritance removal from `SailboatEntity`.
- Expanded wood variants beyond the three confirmed types.
- A decorative road prop ecosystem beyond first-pass lighting.

## Goals

- Make manual roads explicitly connect one town's post-station waiting area to another town's post-station waiting area.
- Remove silent fallback to town cores, claim edges, or other coarse anchor rules in manual road mode.
- Make the road preview feel like real temporary placed blocks in the world.
- Improve road curvature so long roads stop reading as stitched line segments with only local corner smoothing.
- Add restrained engineering details to roads, including consistent lighting placement.
- Ensure finished road placement clears minor decorative vegetation so the built surface reads as continuous.
- Make carriage material read as true wood variation on the model rather than item-like texture replacement.
- Restore meaning to carriage throttle/gears by introducing real land-vehicle speed behavior.

## Non-Goals

- No attempt to make every road generation system use the same strict post-station rule in this pass.
- No physically simulated horse traction system.
- No shader-heavy carriage rendering approach.
- No dynamic decorative clutter such as signs, fences, roadside barrels, or vegetation kits.

## Approaches Considered

### Approach A: Strict post-station planning plus focused renderer and vehicle upgrades

This approach keeps the current architecture but hardens the weak subsystems:

- Manual planner becomes strictly post-station-to-post-station.
- Preview renderer is corrected to use stable world-space-relative rendering.
- Existing Bezier centerline utility is upgraded instead of replaced by a foreign library.
- Carriage keeps route integration but gets an explicit land-motion layer.

Pros:

- Fixes the user-visible problems directly.
- Preserves working route, dock, and runtime construction systems.
- Lowest risk for the scope requested.

Cons:

- Still carries some inherited transport complexity from `SailboatEntity`.

### Approach B: Minimal bugfix pass

- Only force post-station anchors.
- Only fix preview jitter.
- Leave centerline smoothing and carriage handling mostly as-is.

Pros:

- Smallest code change.

Cons:

- Leaves the two major experience gaps in place: road smoothness and carriage feel.

### Approach C: Full subsystem rewrite

- Rewrite manual planner, preview, road geometry, and carriage movement into separated subsystems.

Pros:

- Cleanest long-term architecture.

Cons:

- Too large for the current pass.
- High integration risk.

### Recommendation

Use Approach A. It addresses the user's current complaints without expanding the rewrite beyond what can be safely verified in this repo.

## Design

### 1. Manual Road Planner Becomes Strict Post-Station Mode

`ManualRoadPlannerService` should treat post stations as a hard prerequisite for manual road creation.

- The planner must resolve valid post-station waiting-area exits on both towns before pathfinding.
- If either side lacks:
  - a registered post station in the relevant town claims,
  - a valid waiting-area zone,
  - a usable exit candidate,
  - or a traversable path between selected exits,
  the plan should fail with a specific reason.
- The current fallback to `resolveTownAnchor(...)` for town cores or boundary anchors should be removed from manual road mode.

Multiple stations remain supported:

- Each town may offer multiple candidate post stations.
- The planner should evaluate station pairs and choose the lowest-cost valid pair.
- Selection only happens among valid post-station-based candidates; no non-station fallback is allowed.

### 2. Blocked-Column Handling Must Whitelist Selected Stations

The current root-cause risk is that building blocking may invalidate the chosen station exits before routing begins.

- `collectBlockedRoadColumns(...)` currently blocks placed structures broadly.
- Manual planning should add an unblock/whitelist phase once the chosen source and target stations are known.
- The whitelist should include:
  - selected station base column,
  - waiting-area footprint columns,
  - selected exit columns,
  - any immediate route handoff cells needed to leave the waiting area cleanly.

This ensures the manual planner does not self-sabotage by treating the selected station as an obstacle.

### 3. Manual Road Preview Must Be World-Locked

`RoadPlannerPreviewRenderer` should render the road preview as if temporary blocks are already placed in the world.

- Render positions must be computed relative to the exact camera `Vec3`, not an integer `BlockPos` camera origin.
- Movement, head rotation, and jump motion should produce only normal perspective change, not full-block snapping or drift.
- The preview should remain stable until the plan itself changes.

Preview content should be reduced to:

- actual ghost road blocks,
- start highlight,
- end highlight,
- focus highlight.

The long line-based preview hints should not appear in manual road planning at all.

Failure behavior:

- If plan creation fails, the preview must be cleared immediately.
- No stale preview should remain visible after failure or target changes.

### 4. Road Centerline Smoothing Upgrades to Full-Segment Bezier Sampling

The current `RoadBezierCenterline` smooths corners locally. That is not sufficient for long roads.

The new version should:

- simplify route nodes into a stable control-point chain,
- derive full-segment Bezier spans rather than only local corner replacement,
- sample points along the entire curve using approximately even spacing,
- rasterize those samples back to safe terrain columns,
- keep the safety gates already required by this codebase.

Reference influence:

- `TwkBezierTemplate` is useful as a reference for control-point-driven template generation.
- `BezierMaker` is useful as a reference for full-curve sampling rather than isolated corner interpolation.
- Neither reference should be copied literally; the implementation must stay compatible with current terrain validation and Minecraft block-grid constraints.

Validation rules remain:

- no unsafe bridge expansion,
- no blocked columns,
- no non-contiguous jumps,
- no water exposure regression,
- no excessive vertical discontinuity.

### 5. Road Structure Gains Lighting as a Planned Layer

Road generation should expand from surface-only output to an engineered road assembly:

- travel band,
- shoulders,
- support/foundation,
- lighting nodes.

Lighting design:

- Use fixed road-side lamps, not low ground lights.
- Prefer placement near:
  - post-station exits,
  - long straight segments,
  - major bends,
  - bridge approaches,
  - terrain transition points.
- Avoid placement on:
  - narrow bridge bodies,
  - the middle of steep stair-ramp runs,
  - blocked columns,
  - places that interfere with existing structures or trees when avoidable.

Implementation shape:

- Add a focused `RoadLightingPlanner`.
- Input: centerline, geometry slices, bridge ranges, and surface/support choices.
- Output: lamp placement descriptors or extra build steps.
- Integrate these into `StructureConstructionManager` so lighting is built with the road rather than as a second system.

This first pass should stay practical: a consistent lamp standard, not a decoration framework.

### 6. Road Surface Placement Should Clear Minor Vegetation

Road creation should not leave holes or visual noise because of trivial surface clutter.

- Road build steps should treat minor replaceable decoration as removable surface clutter.
- At minimum this includes common small plants such as:
  - grass,
  - flowers,
  - ferns,
  - similar replaceable non-structural surface plants.
- When a road surface, shoulder, support, or lamp foundation is placed, these clutter blocks should be overwritten automatically.
- This should stay narrow in scope:
  - overwrite minor surface decoration,
  - do not silently delete solid structures, logs, walls, chests, or other meaningful blocks.

The rule is that a valid road footprint should build a continuous engineered surface, while still respecting real obstacles.

### 7. Carriage Appearance Moves from Texture Swap to Model Wood Coloring

Current carriage wood support changes whole textures. That reads like an item-skin swap rather than material variation.

Target behavior:

- Keep `CarriageWoodType` with the three approved variants: `oak`, `spruce`, `dark_oak`.
- Replace full-body texture switching with a shared base material plus wood-tone coloring for wood-designated model regions.
- Non-wood parts such as metal, rope, and wheel hardware should keep stable base texture treatment.

Expected implementation boundary:

- Keep wood type as synced/persisted entity state.
- Change model/render lookup so the wood type provides tint/material parameters instead of alternative full textures.
- If the current model format makes per-bone tinting awkward, the acceptable fallback is a shared texture atlas plus explicit colorized wood masks, but still not full texture replacement per type.

### 8. Carriage Motion Becomes a Dedicated Land-Vehicle Layer

The carriage should keep the current autopilot, station, cargo, and routing integration, but movement resolution must stop behaving like a damped sailboat.

Design rules:

- Throttle/gear states must map to meaningful target speed tiers.
- Acceleration must feel grounded:
  - slower launch,
  - clear ramp into cruise,
  - believable off-road drag,
  - stronger slowdown uphill,
  - sharper loss when leaving finished roads.
- Turning should meaningfully cost speed at higher velocity.
- On-road bonus should remain, but act as an enhancement to carriage travel, not as the only source of movement.

Implementation boundary:

- Route/autopilot keeps producing movement intent.
- A dedicated carriage motion layer translates that intent into final horizontal motion.
- Manual driving and autopilot should both pass through the same carriage movement rules so the feel is consistent.

This preserves transport infrastructure while making the vehicle itself land-native.

## Data Flow

### Manual Road Planning

1. Player selects target town.
2. Planner resolves source/target town post stations inside valid claims.
3. Planner builds waiting-area exit candidates for each station.
4. Planner chooses the lowest-cost valid station pair.
5. Planner unblocks the chosen station/waiting-area/exit columns.
6. Route search runs between the chosen exits.
7. Full-segment Bezier smoothing refines the centerline.
8. Geometry planner widens the road and emits surface/support build steps.
9. Lighting planner emits lamp placements.
10. Preview packet sends only true ghost blocks and highlight anchors.

### Manual Preview Rendering

1. Client receives ghost blocks and highlight anchors.
2. Renderer subtracts precise camera position from each world coordinate.
3. Filled ghost boxes and outlines render in stable world-relative positions.
4. Player movement changes only the camera perspective, not preview anchoring.

### Carriage Presentation and Motion

1. Item/entity keeps one of three wood types.
2. Renderer maps wood type to wood-region material tint.
3. Route or player input produces carriage intent.
4. Carriage motion layer computes target speed from gear and terrain state.
5. Final motion is applied with road bonus, drag, turning, and slope rules.

## Files Expected to Change

- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/PostStationRoadAnchorHelper.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- `src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java`
- `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
- `src/main/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerPreviewPacket.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadBezierCenterline.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadLightingPlanner.java` (new)
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- `src/main/java/com/monpai/sailboatmod/entity/CarriageWoodType.java`
- `src/main/java/com/monpai/sailboatmod/client/model/CarriageEntityModel.java`
- `src/main/java/com/monpai/sailboatmod/client/model/CarriageItemModel.java`
- `src/main/java/com/monpai/sailboatmod/client/renderer/CarriageEntityRenderer.java`
- relevant tests under `src/test/java/com/monpai/sailboatmod/...`

## Error Handling

- Manual road planning should return explicit failure messages when post stations are missing or unusable.
- Preview should fail closed: no valid plan means no ghosts.
- Bezier smoothing should fall back to the unsmoothed safe baseline when a curved candidate violates safety constraints.
- Lighting placement should skip invalid lamp positions rather than blocking the whole road plan.
- Unknown carriage wood types still fall back to oak.

## Testing and Verification

Required automated checks:

- unit coverage for strict post-station anchor resolution and no-fallback behavior,
- unit coverage for selected-station unblock logic,
- unit coverage for upgraded Bezier centerline validity and smoother long-span output,
- unit coverage for lighting placement heuristics,
- unit coverage for carriage gear/speed behavior helpers where practical,
- `./gradlew.bat compileJava`,
- `./gradlew.bat test`,
- `./gradlew.bat build`.

Recommended in-game checks:

- create a manual road between two towns with valid post stations and verify the route starts/ends at waiting-area exits,
- attempt planning where one side lacks a valid post station and verify a clear failure message,
- move while previewing and confirm ghosts remain visually anchored to the world,
- confirm no long hint lines appear in manual road preview,
- inspect a long curved route and confirm the centerline is visibly smoother,
- inspect lamp placement on straights, bends, and station exits,
- compare carriage hand-driving on road vs off-road and verify gears now produce clear speed tiers,
- confirm oak/spruce/dark_oak carriages differ by wood material appearance rather than whole-texture swap.
