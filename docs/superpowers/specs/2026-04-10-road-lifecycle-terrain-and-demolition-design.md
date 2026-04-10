# Road Lifecycle Terrain And Demolition Design

## Summary

This design upgrades manual inter-town roads from a simple build-only feature into a full lifecycle system. Roads become unique town-pair links, terrain shaping becomes destructive and reconstructive enough to form continuous slopes instead of floating slabs or cliff-like breaks, bridge generation can automatically escalate to arched bridges when the span demands it, and the road planner item becomes the single operator tool for build, cancel, and demolish actions.

The target outcome is that a player can:

- build exactly one road between two towns
- get smoother uphill and downhill transitions because the road modifies the terrain under itself
- have long bridge spans automatically form an arched profile when appropriate
- cancel an in-progress road and fully roll it back
- look at one segment of a road and remove the entire matching road in one action

## Goals

- Enforce exactly one road connection between a given pair of towns in the same dimension.
- Change slope construction from mostly surface-following placement to terrain-shaped, progressive ramps.
- Allow road generation to choose between embankment, normal bridge, and arched bridge based on terrain and span.
- Make the road planner item the main control surface for build, cancel, and demolition actions.
- Support full rollback when a road construction job is canceled.
- Support whole-road demolition by selecting a single visible road segment.

## Non-Goals

- Replacing the existing road-network data model with a graph database or a new save format.
- Adding a separate management GUI for road lifecycle actions.
- Reworking carriage routing in this design pass.
- Supporting multiple parallel manual roads between the same pair of towns.

## Current Problems

### Terrain

The current geometry planner mostly places road blocks relative to the sampled centerline height. That improves visual continuity compared with fully flat placement, but it still leaves obvious breaks when the terrain rises too fast. The road surface can appear disconnected from the ground below it because the road is not reshaping enough of the underlying terrain volume.

### Road uniqueness

The current manual-road flow uses a deterministic `roadId` based on the pair edge key, but the planner path and scheduling flow still behave like a build action, not like lifecycle management. There is no explicit “this edge is already occupied by a finished or active road” rule surfaced to the planner UX.

### Bridges

The current bridge behavior is mostly “bridge material when the sampled surface is unsafe.” It does not distinguish short practical crossings from long spans that visually need an arched profile.

### Lifecycle

Road construction can start, but there is no integrated planner-tool flow for canceling a running job and rolling it back. There is also no user-facing whole-road demolition flow that lets the player point at one part of a road and remove the entire route.

## Proposed Architecture

Keep the existing `RoadNetworkRecord`, planner flow, and construction runtime persistence, but split the new behavior into focused road helpers.

### Core services

- `RoadLifecycleService`
  - Owns uniqueness checks for manual roads.
  - Owns cancel-and-rollback behavior for active road jobs.
  - Owns whole-road demolition for both finished and active roads.
- `RoadSelectionService`
  - Resolves a looked-at road block back to a `roadId`.
  - Finds the best matching road near the player hit result when multiple roads are nearby.
- `RoadTerrainShaper`
  - Produces terrain-edit build steps below and around the road surface.
  - Cuts into hills and fills depressions so the road becomes a continuous embanked slope instead of a floating strip.
- `RoadBridgePlanner`
  - Classifies each route segment as ground, embankment, flat bridge, or arched bridge.
  - Generates the vertical bridge profile and support rules for long spans.

### Existing classes that stay in control

- `ManualRoadPlannerService`
  - Remains the entry point for road-planner item behavior.
  - Gains mode-aware actions and unique-edge refusal logic.
- `StructureConstructionManager`
  - Remains the runtime scheduler and placement executor.
  - Delegates lifecycle decisions and geometry details to the new helper classes.
- `RoadGeometryPlanner`
  - Continues to produce the road surface envelope, but now consumes richer terrain and bridge decisions instead of doing all slope behavior itself.

## Data Model

### Road identity

Manual roads continue to use a deterministic pair-derived id:

- `manual|town:<A>-town:<B>` or equivalent sorted edge key form

That means the pair itself is still the natural unique key. The difference in this design is behavioral: if a manual road for that edge already exists, the planner will refuse to create another one until the existing one is canceled or demolished.

### Planner item state

The road planner item gains a lightweight mode state in NBT:

- `Mode = BUILD | CANCEL | DEMOLISH`
- confirmation state for destructive actions
- preview target road id when cancel/demolish is awaiting second confirmation

This keeps the planner as the only required user tool and avoids a separate screen for lifecycle actions.

### Runtime rollback inputs

The rollback path reuses the existing persisted `RoadPlacementPlan` and runtime job state where possible. If a road is still active, rollback uses the same plan that would have been used to finish building the road. If the road is already finished, demolition reconstructs a plan from the stored road record and derived geometry.

## Planner Interaction Design

### Modes

The road planner has three modes:

- `BUILD`
  - Current station-to-station preview and confirm flow.
- `CANCEL`
  - Targets the currently selected town pair.
  - If the matching road is actively building, preview the rollback and require a second confirmation.
- `DEMOLISH`
  - Uses the player’s looked-at block to resolve a road.
  - Highlights the entire matched road and requires a second confirmation before removal.

### Behavior by mode

#### BUILD

- If a matching road already exists or is being built, the planner refuses to create another one and reports that the towns are already connected.
- If no matching road exists, the current preview and confirm flow proceeds.

#### CANCEL

- The planner resolves the currently selected pair to its unique `roadId`.
- If no active construction job exists for that road, the planner reports that there is nothing to cancel.
- If an active job exists, the planner shows a rollback preview and a second confirm interaction.
- Confirming cancels the runtime job, removes the road record, and rolls back all already-placed road, bridge, lamp, and terrain-shaping blocks associated with the plan.

#### DEMOLISH

- The planner ray-traces from the player view.
- `RoadSelectionService` searches nearby road records and derived coverage to find which road owns the looked-at segment.
- The entire route is highlighted.
- Confirming removes the whole road and clears any runtime job that still exists.

## Uniqueness Rules

Exactly one road may exist between the same two towns in the same dimension.

The planner rejects new build previews when any of these are true:

- a `RoadNetworkRecord` already exists with the same pair edge key
- an active road job exists with that same `roadId`

Auto-generated internal structure roads remain separate because they use different structure ids and source types. This rule applies specifically to manual town-to-town roads.

## Terrain Shaping Design

### Target profile

The road path no longer simply samples the current surface and places slabs above it. Instead, the system derives a target longitudinal profile:

- road height changes are clamped over distance
- abrupt cliff transitions are spread over multiple centerline samples
- the final profile is interpreted as the desired top of the roadbed, not just the top of the visible slab

### Cut and fill

For each road slice:

- if the terrain is above the target roadbed, the shaper cuts terrain down inside the roadbed envelope
- if the terrain is below the target roadbed, the shaper fills terrain upward with support and embankment blocks
- if the terrain is only slightly below target, the shaper prefers gradual embankment over visible suspended road blocks

This changes the visual result from “surface blocks laid on top” to “the hill itself was graded into a road.”

### Steep transitions

The preferred order is:

1. smooth earthwork ramp
2. supported embankment
3. short stair-like fallback only where geometry cannot stay drivable

The design intentionally makes stair fallback rare, because the target is a carriage-capable road, not a pedestrian trail.

## Bridge And Arch Logic

### Classification

`RoadBridgePlanner` classifies route spans using:

- span length
- unsupported columns under the path
- water or void coverage
- elevation difference between span endpoints
- whether a ground ramp would exceed the allowed slope profile

### Flat bridge

Use a normal bridge when:

- the gap is short
- the crossing is mostly practical rather than scenic
- the endpoints are already close to the desired roadbed height

### Arched bridge

Use an arched bridge when:

- the unsupported span is long enough
- the middle of the span drops materially below the endpoints
- or a direct flat crossing would force ugly ramps at the bridge entries

The arched bridge profile has:

- rising approaches
- a crown near the middle
- descending approaches
- bridge supports or abutments at stable edges

The X/Z curve still follows the road centerline. The arch only alters the Y profile.

## Construction And Rollback Semantics

### Build

Building a road now places a richer sequence of steps:

- terrain cut/fill preparation
- support or embankment placement
- bridge or arch structure placement where required
- visible road surface placement
- roadside lamps and associated supports

### Cancel active construction

Canceling an active road:

1. resolves the active road job
2. stops further scheduling for that `roadId`
3. computes the set of already-placed steps from the persisted runtime plan
4. removes placed road-owned blocks in reverse-safe order
5. deletes the road job state and road network record

This is a full rollback, not a pause.

### Demolish finished road

Demolishing a finished road:

1. resolves the clicked segment to a `roadId`
2. derives or restores the road placement plan
3. removes all owned blocks for that road
4. deletes the road record

The removal path uses road-owned geometry and not just visible surface blocks, so lamps, bridge members, and embankment-added blocks are removed too.

## Ownership Rules For Demolition

Road demolition must not indiscriminately destroy surrounding terrain. Each generated block is classified:

- road surface block
- road support/embankment block
- bridge structural block
- road lamp block

Only blocks that match the road’s generated ownership set are eligible for rollback or demolition. Natural terrain outside the generated footprint is left alone. This keeps “replace the ground under the road” from turning into “erase the mountain.”

## Error Handling

The planner should clearly report these cases:

- towns already have a connecting road
- no active construction exists to cancel
- the looked-at block is not part of a known road
- the road cannot be reconstructed for demolition
- the rollback plan is partially blocked by foreign blocks

Blocked rollback or demolition should fail safely with a user-visible message rather than removing whatever happens to match loosely.

## Testing Strategy

### Unit tests

- uniqueness checks reject duplicate manual roads on the same pair
- terrain shaper converts abrupt height jumps into multi-step graded profiles
- terrain shaper chooses cut versus fill correctly
- bridge planner chooses flat bridge for short spans and arched bridge for long unsupported spans
- road selection resolves a clicked segment back to the correct road id
- cancel flow removes active road state and marks all generated blocks for rollback
- demolish flow removes the full road tied to the selected segment

### Regression tests

- existing manual road planning still uses strict post-station waiting-area exits
- preview rendering stays world-locked
- road ghost/build-step persistence still restores correctly after save/load

### Manual in-game checks

- build one road between two towns, then verify a second build attempt is refused
- start a road, wait for partial progress, cancel it, and verify all placed pieces disappear
- build a road over mixed hills and verify the ground under the slope is reshaped
- build over water or a ravine and verify short spans remain practical bridges while long spans become arches
- point at a finished road segment in demolish mode and verify the whole route is highlighted and removed

## File Impact

Expected primary files:

- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadPlacementPlan.java`
- `src/main/java/com/monpai/sailboatmod/nation/model/RoadNetworkRecord.java`
- `src/main/java/com/monpai/sailboatmod/nation/data/NationSavedData.java`

Expected new focused helpers:

- `src/main/java/com/monpai/sailboatmod/construction/RoadTerrainShaper.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadLifecycleService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadSelectionService.java`

Expected tests:

- terrain shaping tests
- bridge classification tests
- lifecycle cancel/demolish tests
- duplicate-road rejection tests

## Rollout Notes

This design intentionally layers onto the current system rather than replacing it. The new helpers should be introduced first, then wired into planner and runtime code. That keeps save compatibility and lets the road lifecycle features reuse the existing job persistence path instead of inventing a second road runtime model.
