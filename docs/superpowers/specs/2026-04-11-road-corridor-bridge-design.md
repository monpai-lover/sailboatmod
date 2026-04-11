# Road Corridor Bridge Design

Date: 2026-04-11

## Goal

Fix manual town-to-town road planning for wide rivers, large elevation differences, and sloped shorelines so that:

- preview and final construction always describe the same complete road
- roads fully connect both towns from anchor to anchor
- river crossings generate a coherent bridge plus approach ramps instead of disconnected fragments
- terrain conflicts are resolved by excavation/fill/support instead of leaving the road buried inside grass, dirt, or hillside blocks
- bridge structures preserve navigable main channels for boats

This design specifically targets the failure mode shown in the latest preview screenshot: a short node count and a visibly incomplete river crossing where the generated plan does not represent a continuous bridge-and-approach corridor.

## Non-Goals

- Reworking the entire global automatic road network planner
- Adding player-facing configuration UI for bridge style tuning
- Introducing multiple aesthetic bridge themes in this iteration
- Refactoring unrelated rendering or construction systems outside the road preview / road build flow

## Problem Summary

Current manual road planning has three coupled issues:

1. Pathfinding, preview generation, and construction do not share a single corridor-level representation.
2. River spans and shore approaches are inferred too late, so the preview can show a fragmentary or visually broken result even when a center path exists.
3. Terrain shaping and structure placement do not fully encode the approach geometry needed for wide rivers and steep banks.

The result is a broken user experience:

- preview nodes can exist without a full visible bridge/ramp connection
- river crossings may fail to form a complete bridge body
- approaches can look abrupt or disconnected
- road surfaces can intersect surrounding ground unnaturally

## Design Overview

Introduce a new corridor-level planning stage between route search and placement generation.

### Pipeline

1. `Route path`
   - Finds a centerline candidate between source and target anchors.
   - Responsible only for traversal and high-level route choice.

2. `RoadCorridorPlan`
   - New canonical road representation.
   - Expands the centerline into a complete traversable corridor with explicit bridge, approach, support, and clearance semantics.

3. `Preview / Build`
   - Preview packets and construction plans are both derived from the same `RoadCorridorPlan`.
   - No separate ad-hoc preview-only inference is allowed.

This makes preview and construction deterministic views of the same plan.

## New Core Model: `RoadCorridorPlan`

Create a new intermediate model named `RoadCorridorPlan` with enough information to fully describe both preview and construction.

### Required contents

- Full centerline from source town anchor to target town anchor
- Segment classification:
  - `TOWN_CONNECTION`
  - `LAND_APPROACH`
  - `BRIDGE_HEAD`
  - `NAVIGABLE_MAIN_SPAN`
  - `NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN`
- Longitudinal height profile for every centerline index
- Cross-section placement instructions for each corridor slice:
  - road surface
  - railings
  - railing lights
  - bridge pier / support candidates
  - excavation volume
  - fill/support volume
  - under-bridge light points
- Main-channel clearance metadata
- Validation result proving the corridor is continuously walkable/buildable

### Canonical rule

If a valid `RoadCorridorPlan` cannot be produced, preview must fail with a clear “cannot generate complete road plan” state rather than showing a partial fragment.

## River and Elevation Planning

### River span detection

When the selected centerline crosses water:

- detect the contiguous water span
- identify both banks
- identify stable bridgehead control points by retreating inland from unstable edge tiles

The bridge should not start or end directly on the first water-edge block if that would create abrupt anchor geometry.

### Bridgehead control points

For each bank:

- search inland for a stable terrain control point
- prefer blocks that provide enough deck/headroom and do not force immediate vertical jumps
- reject control points that would require a discontinuous connection back into the terrain

### Continuous longitudinal profile

Generate one continuous height profile across:

- left land approach
- left bridgehead transition
- bridge span
- right bridgehead transition
- right land approach

This profile must be valid as a whole. The bridge and ramps are not solved independently.

### Priority order

1. Make the corridor continuous end-to-end.
2. Preserve navigable water clearance.
3. Prefer longer, smoother approaches when possible.
4. Allow steeper ramps only when a smoother valid alternative cannot fit.

## Navigable Bridge Rules

### Main channel identification

Do not treat the entire river width as a navigation lane.

Instead, detect the best candidate main channel using the most navigable continuous band:

- deepest local water
- widest continuous interior band
- most central boat-usable passage

This becomes the protected main span.

### Clearance

- Minimum main-channel bridge underside clearance remains `water surface + 5`.
- No pier, fill, railing overhang, or low-hanging light may intrude into the protected main channel.

### Supports

Bridge supports are required, but they must not obstruct navigation.

- Supports may be placed:
  - on land
  - in shallow edge water
  - in non-main-channel water sections
- Supports may not be placed in the protected main channel.

If a river is too wide for a fully unsupported span:

- extend side support zones first
- increase bridge arch/body height as needed
- only reject the plan if a valid supported geometry still requires putting supports into the main channel

## Approach Ramp Rules

### Bridgehead transition

Each bridge end must include an explicit transition segment:

- the bridge does not terminate directly into flat ground
- a short transition buffer must exist between bridge deck and terrain-following approach

### Ramp shaping

Approach ramps connect the bridgehead buffer to each town anchor.

Rules:

- prefer extended ramps with smoother grade changes
- permit steeper grade only when necessary to preserve end-to-end continuity
- forbid single-step discontinuities that make the route visually or physically broken

### Continuity validation

A candidate approach is valid only if:

- every consecutive slice can connect to the next with a buildable deck progression
- no segment requires a sudden jump that would break preview or construction
- the resulting corridor remains navigable on foot across the full span

## Terrain Conflict Resolution

### Excavation-first deck clearance

Before placing the final road surface:

- clear the required deck space
- clear headroom above the deck
- clear lateral terrain intrusions where hillsides overlap the corridor

### Fill and support

After deck clearance:

- fill below the road where land approaches need support
- place bridge supports or piers where the corridor profile requires elevated structure

### Hard rule

The system must never choose “leave the road embedded in terrain” as an acceptable result.

If the terrain intersects the planned roadbed:

- excavate if safe and outside protected channel constraints
- otherwise invalidate the candidate and choose a different corridor

## Lighting Rules

### Railing lighting

Water bridge segments must have railing-integrated lighting on both sides.

- Lights attach to railings or railing posts
- spacing should be regular
- preview must show the railing lights directly

### Pier lighting

Non-main-channel piers may carry hanging or attached lights to illuminate water below.

- allowed on piers outside the protected navigation zone
- not allowed where the light would intrude into boat clearance for the main channel

### Under-bridge illumination goal

Use railing spill light plus pier/bridgehead support lights to illuminate the water under the bridge and reduce hostile spawning.

### Preview parity

All lighting that will actually be built must appear in preview.

No hidden post-confirmation light generation is allowed.

## Preview and Construction Behavior

### Preview rules

The preview must always render:

- the full bridge body
- both land approaches
- bridgehead transitions
- railings
- railing lights
- piers/supports
- pier lights where valid

If the corridor is incomplete, preview must fail instead of rendering a misleading partial plan.

### Construction rules

Construction must consume the same `RoadCorridorPlan` used by preview:

- same deck geometry
- same support placements
- same excavation/fill volumes
- same lights

No additional construction-only inference is allowed except runtime placement ordering.

## Failure Handling

Reject a corridor candidate if any of the following are true:

- no continuous bridge + approach profile can be formed
- required supports would intrude into the protected main channel
- terrain conflicts cannot be resolved without burying the road or blocking navigation
- preview would otherwise be forced to show only a fragment of the real construction

When rejected:

- retry with different bridgehead control points if available
- retry with a different route candidate if available
- otherwise report that a complete road plan cannot be generated

## Implementation Scope

### New components

- `RoadCorridorPlan`
- corridor profile generator
- main-channel detector
- bridge support planner with protected-channel exclusions
- unified preview/build conversion from corridor slices

### Existing components to adapt

- `ManualRoadPlannerService`
  - produce or reject `RoadCorridorPlan`
- `StructureConstructionManager.createRoadPlacementPlan(...)`
  - consume corridor slices instead of inferring structure from a loose centerline alone
- road preview packet generation
  - serialize full corridor-derived preview blocks
- bridge / lighting helpers
  - work against corridor segment classifications

## Testing Strategy

### 1. Corridor continuity tests

Add tests for wide-river plus elevation-difference scenes validating:

- full source-to-target corridor exists
- preview-derived blocks and build-derived blocks cover the same complete span
- approaches and bridge are continuous

### 2. Main-channel preservation tests

Validate that:

- no piers exist in the protected main span
- no hanging lights intrude into the main-channel clearance envelope

### 3. Support coverage tests

Validate that:

- large bridges are not fully unsupported
- supports appear in land/shallow/non-main-channel zones as expected

### 4. Terrain clearance tests

Validate that:

- road deck/headroom is excavated where slopes intrude
- no final corridor slice leaves the road embedded in topsoil or hillside blocks

### 5. Preview parity tests

Validate that preview and construction are generated from the same corridor representation and stay structurally equivalent.

## Acceptance Criteria

The work is complete when all of the following are true:

- In a scene with one town near height 0, another near height 10, and an ~8-block-wide river between them, preview shows one continuous bridge-plus-approach corridor.
- Confirmed construction produces the same full structure that preview showed.
- The bridge preserves a navigable main channel with `water + 5` underside clearance.
- The bridge has supports, but none obstruct the protected navigation zone.
- The bridge uses railing lighting, and allowed side piers may carry under-bridge lights.
- Roads do not terminate mid-span, bury into terrain, or visibly disconnect at banks.

## Recommended Implementation Direction

Implement this as one focused feature branch worth of work centered on introducing `RoadCorridorPlan` and making it the single source of truth for both preview and build output.
