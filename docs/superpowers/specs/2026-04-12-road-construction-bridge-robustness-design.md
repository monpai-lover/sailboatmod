# Road Construction And Bridge Robustness Design

Date: 2026-04-12

## Goal

Fix the remaining road-construction failures reported during manual town-to-town road building:

- roads can be buried inside terrain because natural blocks above the road are not reliably cleared
- slope segments can place stairs with the wrong orientation
- cancelling a build can leave behind support pillars and related bridge substructures
- manual road planning can fail to connect towns across water even when a bridge should be valid
- roadside lighting can fail to appear
- surface roads currently sit on top of terrain instead of replacing the terrain surface directly
- roads and all road-attached structures must stay outside a core exclusion zone

This iteration prioritizes functional correctness and predictable structure ownership over decorative variety.

## User Decisions

- Use the current corridor and geometry pipeline rather than replacing it with a full RoadWeaver-style system.
- Borrow ideas from `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury`, especially for above-column clearing, bridge transitions, and decoration placement, but do not import its architecture wholesale.
- Road surfaces should replace the ground surface directly on land.
- The forbidden placement radius around both town cores and nation cores is 5 horizontal blocks.
- Bridge routing must preserve boat passage under the bridge.
- Bridge crossings are defined by pier nodes in water, not by treating the whole water surface as a generic traversable road surface.
- Bridge deck height is measured relative to water surface height, with the bridge deck built at `water surface + 5`.
- Bridge piers carry lighting, and bridge railings also carry lighting.
- Rollback should remove only supports and related structures that were newly placed by the cancelled construction.
- Stair behavior should follow path direction and slope rather than using a fixed facing.

## Non-Goals

- Rebuilding the entire mod around RoadWeaver regional planning, highway hierarchies, or decoration subsystems
- Adding player-facing bridge configuration or core-radius configuration in this pass
- Changing unrelated road-network storage or UI flows
- Perfecting bridge visual style before route validity, ownership, and continuity are correct
- Allowing roads or bridge attachments to intentionally overlap core protection zones

## Problem Summary

The current code has several gaps across different stages of the same pipeline:

1. `RoadRouteNodePlanner` can exhaust its search without producing a valid bridge-assisted route even when a human would expect a short crossing to work.
2. `RoadCorridorPlanner` does not yet encode the bridge as a pier-driven system with a flat navigable deck, explicit railing lights, and strict attachment points.
3. `RoadGeometryPlanner` has partial stair semantics, but it is not the single source of truth for all slope-facing outcomes once terrain replacement and bridge transitions are involved.
4. `StructureConstructionManager` still behaves too much like a late patch layer. It clears too little headroom, places land roads above terrain rather than into it, and does not fully track every bridge/support/light placement for rollback.

These are not independent bugs. They are all symptoms of missing structure in the corridor-to-construction contract.

## Chosen Approach

Keep the current pipeline:

- `RoadRouteNodePlanner`
- `RoadCorridorPlanner`
- `RoadGeometryPlanner`
- `StructureConstructionManager`

Do not solve the reported issues with one-off placement-time patches.

Instead, extend the planning outputs so the corridor explicitly describes:

- land surface replacement positions
- bridge pier nodes and bridge deck height
- overhead clearance positions
- support ownership
- railing and pier lighting positions
- core exclusion constraints

Construction, preview, rollback, and validation should all consume the same richer plan.

## Routing And Core Exclusion

### Core exclusion rule

Town cores and nation cores define a hard exclusion square extending 5 blocks horizontally from the core block in `X/Z`.

No generated road-related block may be placed inside that exclusion area:

- land road surface
- bridge deck
- bridge piers
- support columns
- bridge railings
- roadside lights
- bridge lights
- bridgehead transition blocks

This rule must be applied during route sampling, not only during final placement, so the planner does not waste time on paths that can never be built.

### Bridge-aware routing model

Land routing remains terrain-column based.

Water crossing should no longer be modeled as "water columns become traversable road columns." That model is too loose and does not encode navigable bridge structure.

Instead, bridge routing becomes a hybrid graph:

- land anchor columns on each shore
- bridgehead transition anchors on valid shores
- pier candidate nodes in water
- bridge deck connections between consecutive pier nodes

Pier candidates must satisfy all of the following:

- the column is over water
- the local water surface is known
- a stable supporting floor exists below the water
- the candidate is outside the core exclusion zone
- the candidate can participate in a span that preserves under-bridge navigation

The bridge deck between pier nodes is built at `water surface + 5` and remains approximately flat across the main water crossing. Shore ramps connect land roads to the bridge deck before the main span begins.

### Planner changes

`RoadRouteNodePlanner` should keep the current land-first, bridge-second strategy, but bridge mode must search a constrained bridge graph rather than a generic water-enabled terrain grid.

Bridge search should:

- identify eligible shore entry and exit anchors
- generate pier-node candidates in the crossing corridor
- connect those nodes under explicit span rules
- preserve bridge length and bridge-share limits
- reject crossings that cannot provide navigable clearance or valid shore transitions

This keeps bridge generation intentional and prevents search exhaustion from exploring large amounts of irrelevant water surface.

## Corridor Model Extensions

`RoadCorridorPlan` should remain the source of truth for downstream build and preview logic, but its per-slice outputs must become stricter.

Each slice should explicitly encode:

- `surfacePositions`
- `excavationPositions`
- `clearancePositions`
- `supportPositions`
- `railingLightPositions`
- `pierLightPositions`
- whether the slice is terrain-replacing land, shore ramp, elevated bridge approach, flat bridge deck, or pure support structure

### Land slices

Land slices replace the sampled terrain surface directly. The road should no longer float one block above the ground for ordinary land routing.

If a slope is gradual, the corridor should prefer slab-style transitions. If a slope is steep enough to require stair semantics, the slice should encode that explicitly so both preview and final build agree on orientation.

### Bridge slices

Bridge slices are anchored to pier nodes and bridgehead transitions.

Rules:

- main span deck height is `water surface + 5`
- the main span remains flat or near-flat across water
- support columns occur only at pier nodes or explicitly approved support spans
- bridge railings exist on both sides of the deck
- pier lights are attached at pier tops
- railing lights are placed at a fixed interval along the railings

Bridge support generation must not add random intermediate supports that would block boat passage beneath the intended navigable span.

## Terrain Replacement And Clearance

### Surface replacement

For non-bridge land road slices, the road surface block replaces the current surface block at the sampled terrain position.

This changes the land-road model from:

- "place road above terrain"

to:

- "replace terrain surface with road"

Benefits:

- smoother hill climbing
- fewer buried road surfaces
- more predictable stair/slab transitions
- better alignment with the user's expectation that the road cuts into the ground rather than hovering over it

### Clearance policy

`clearancePositions` must represent the actual headroom envelope needed for traversal, not just a single block above the deck.

Construction-time clearing should remove only:

- air-replaceable blocks
- liquids where appropriate for road placement
- natural terrain and vegetation that are explicitly classified as safe to clear

It should not remove:

- protected core blocks
- containers
- crafted structural blocks from players
- tile entities or valuable placed structures unless already covered by existing demolition rules

This follows the useful RoadWeaver idea of explicit above-column clearing without adopting its full system.

## Slope And Stair Semantics

Stair orientation must be derived from path direction and local height transition, not from a fixed default block state.

`RoadGeometryPlanner` should produce the canonical slope-facing result:

- facing follows the path tangent through the slice
- uphill and downhill determine stair facing orientation
- slab transitions are preferred when the profile does not require a full stair
- repeated slices in the same slope direction should remain visually continuous
- bridgeheads and shore ramps should use the same slope semantics as land ramps so the approach to the flat bridge deck does not flip orientation unexpectedly

The chosen stair or slab state must then flow unchanged into preview, placement, and rollback ownership.

## Lighting Model

Lighting must be explicit in the corridor output rather than inferred late.

### Land roads

Restore roadside lighting generation by populating real `railingLightPositions` or equivalent side-light placements for land slices where lights are desired.

### Bridges

Bridge lighting has two required components:

- pier-top lights on bridge piers
- railing-mounted lights at a fixed spacing along both sides of the bridge

These light placements are part of the corridor/build plan and therefore part of ownership and rollback.

## Ownership And Rollback

Rollback should operate on "what this build instance actually changed," not on a broad guess based on road category.

The build plan and construction manager must capture snapshots and ownership for all newly placed road-related blocks:

- replaced land surface blocks
- bridge deck blocks
- pier blocks
- support columns
- bridge railings
- roadside light supports
- bridge light supports
- light blocks themselves
- terrain cleared within the explicit excavation and clearance envelope

When cancelling or rolling back:

- remove newly placed supports, piers, railings, and lights from this build
- restore replaced terrain and cleared natural blocks from snapshot data
- do not remove pre-existing world structures that were not placed or modified by this build

This directly addresses the current defect where support pillars can survive rollback.

## Affected Code Areas

Primary files expected to change:

- `src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadTerrainShaper.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

Tests will likely need updates or additions in:

- `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java`
- new planner/corridor/rollback-focused test classes for bridge pier selection, core exclusion, and lighting ownership

## Validation Strategy

Add automated coverage for the following cases:

1. A short over-water town-to-town route succeeds by selecting bridge pier nodes and a flat deck at `water surface + 5`.
2. A route that would otherwise pass within 5 blocks of a town core or nation core is rejected or rerouted.
3. Land road construction replaces the surface terrain block rather than placing road blocks above it.
4. Natural terrain and vegetation above the road are cleared within the encoded clearance envelope.
5. Stair orientation follows slope direction for both uphill and downhill path segments.
6. Bridge plans produce both pier lights and railing lights.
7. Rolling back a cancelled bridge or elevated road removes newly added supports and restores prior terrain state.

Manual verification should still cover an in-game case where:

- two towns on opposite sides of water connect successfully
- the bridge remains navigable for boats underneath
- bridge railings and lights appear
- cancelling construction removes all newly added bridge supports

## Risks And Mitigations

- Bridge graph generation could become too restrictive and reject valid crossings.
  - Mitigation: keep bridge candidate generation local to the crossing corridor and cover multiple water widths in tests.
- Surface replacement could accidentally overwrite protected blocks.
  - Mitigation: restrict replacement to existing road-safe or terrain-safe categories and preserve snapshots for rollback.
- Expanded clearance could become too destructive.
  - Mitigation: clear only approved natural or replaceable materials, never arbitrary player structures.
- Ownership tracking could drift again if lighting/support placement remains partially implicit.
  - Mitigation: require all such positions to originate in the corridor/build plan and ban ad hoc placement outside owned records.

## Result

After this change, manual road generation should behave as one coherent system:

- land roads cut into terrain instead of hovering over it
- slopes orient correctly
- bridges are built from deliberate pier nodes with a navigable flat deck
- lights are part of the real build output
- core areas remain clear
- rollback removes the full set of newly created bridge and support structures
