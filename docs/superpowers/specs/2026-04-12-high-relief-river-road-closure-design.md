# High Relief River Road Closure Design

Date: 2026-04-12

## Goal

Fix the remaining manual road generation failure mode where two towns with significant elevation difference and an intervening river produce a technically found route but a poor built result:

- bridge and land approaches do not visually or physically connect
- approach slices can be cut apart at bridgeheads
- road surfaces can end up embedded inside grass, dirt, mud, or shoreline terrain
- preview and final construction can still appear continuous in some areas while the actual traversable corridor is not

This iteration prioritizes functional continuity over aesthetics:

- the road must be continuously buildable and walkable from town A to town B
- short elevated approach viaducts are preferred over forcing a terrain-hugging climb near the river
- clearing obstructing grass/dirt/mud terrain is allowed when needed to preserve continuity

## Non-Goals

- Tuning decorative bridge style variants
- Reworking global non-manual road generation outside the shared corridor/build pipeline
- Adding player-facing bridge configuration
- Perfecting bridge aesthetics before continuity and terrain clearance are correct

## Problem Summary

After the corridor/preview unification work, the remaining defects are no longer route-search failures. They are corridor completeness failures:

1. Adjacent corridor slices can still produce a bridgehead or approach shape that looks connected in aggregate but is not actually closed into one traversable road surface.
2. The corridor currently does not encode enough explicit terrain-clearing information, so roads can be generated inside surrounding grass, dirt, path, mud, or sloped banks.
3. High-delta river crossings still rely too much on per-slice ribbon interpolation, so the land approach, elevated approach, and main span can each be reasonable in isolation while their junctions remain weak.

The result is exactly the behavior the user described: the route exists, but the finished road is visually poor, discontinuous, or partially buried.

## Chosen Approach

Continue with the corridor-as-source-of-truth architecture.

Do not solve the remaining issue by adding a late construction-time patch layer.

Instead:

- extend `RoadCorridorPlan` so it explicitly models elevated approach segments, bridgehead closure constraints, and required terrain excavation
- derive preview, build steps, supports, lighting, and owned terrain edits only from the corridor
- validate continuity at the corridor stage and reject any plan that cannot produce a closed traversable result

This preserves the current architectural direction and avoids reintroducing preview/build drift.

## Corridor Model Extensions

### New segment semantics

Expand corridor segment classification so it can distinguish:

- `LAND_APPROACH`
- `APPROACH_RAMP`
- `ELEVATED_APPROACH`
- `BRIDGE_HEAD`
- `NAVIGABLE_MAIN_SPAN`
- `NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN`

`APPROACH_RAMP` means a terrain-connected ascent/descent segment.

`ELEVATED_APPROACH` means the road has deliberately transitioned into a supported viaduct before the main river span, typically because forcing the road to stay terrain-hugging would create a broken, buried, or excessively abrupt approach.

### New per-slice outputs

Each corridor slice must explicitly encode:

- `surfacePositions`
- `excavationPositions`
- `clearancePositions`
- `supportPositions`
- `railingLightPositions`
- `pierLightPositions`

Rules:

- `surfacePositions` define the walkable road deck for that slice
- `excavationPositions` define blocks that must be removed or replaced because they occupy the roadbed
- `clearancePositions` define airspace/headroom blocks that must be cleared above or beside the road so the route is not embedded in terrain
- supports and lights remain explicit so preview and construction stay identical

## Height Profile and Segment Generation

### Unified longitudinal profile

The full corridor height profile must be generated as one continuous system across:

- land road
- rising approach ramp
- elevated approach
- protected river main span
- descending approach
- destination land road

The generator must not solve bridge and approaches independently and then hope the interfaces line up.

### Preferred crossing shape

When towns have a large elevation difference and a river lies between them, the preferred result is:

1. terrain-connected local road
2. short elevated approach viaduct near the riverbank
3. raised main span with navigation clearance
4. elevated exit approach on the far bank if needed
5. reconnect to local terrain-following road

This is preferred over forcing a last-second steep bank climb immediately at the waterline.

### Bridgehead closure rule

Every bridgehead must satisfy all of the following:

- the last approach slice and first bridgehead slice share a continuous walkable overlap
- the bridgehead slice and first main-span slice share a continuous walkable overlap
- consecutive slice center heights stay within allowed walking/build transition thresholds
- no bridgehead edge may terminate into a visible or physical gap

If a candidate bridgehead does not close cleanly, the corridor planner must expand or reshape the adjacent approach slices rather than leaving construction to patch it later.

### Surface overlap rule

For every adjacent slice pair:

- their walkable surfaces must intersect or connect through a one-step valid progression
- if ribbon widening or turning causes the two slices to diverge, the planner must enlarge the weaker slice footprint until continuity is restored

This rule is the direct guard against “the route exists but the actual road surface is cut apart.”

## Terrain Clearance Rules

### Allowed terrain edits

To preserve continuity, the road generator may clear or replace obstructing terrain in and around the road corridor, including:

- grass block
- dirt
- coarse dirt
- dirt path
- mud
- rooted or similarly soft surface cover
- shallow bank material overlapping the deck or headroom envelope

These blocks may be removed, replaced by road foundation, or cleared to air depending on whether the position belongs to roadbed support or clearance volume.

### Hard clearance rule

The road must not remain partially buried.

If terrain intersects:

- the walkable road surface
- the required deck volume below the traveler
- the minimum headroom/side clearance envelope

then the corridor must either:

- add excavation/clearance positions, or
- invalidate that corridor shape and choose a different one

### Functional priority

If there is tension between preserving untouched shoreline terrain and producing a closed traversable road, the road wins for the allowed soft terrain classes listed above.

## Preview and Construction Contract

The shared contract becomes:

- preview ghosts are derived from corridor-derived placement artifacts
- build steps are derived from the same artifact set
- owned terrain edits include excavation and clearance positions from the corridor
- no later system may invent missing bridgehead closure, missing support placement, or missing excavation heuristically

If the corridor does not contain enough information to build a correct road, that is a corridor bug and must be fixed there.

## Validation Rules

A manual road plan is usable only if:

- it has non-empty build steps
- it has a valid corridor plan
- each slice index is ordered and complete
- every adjacent slice pair passes the surface overlap rule
- every bridgehead passes the bridgehead closure rule
- every required clearance/excavation position is materialized into placement artifacts

If any of these fail, preview must fail rather than showing a misleading partial result.

## Testing Strategy

### Unit tests

Add focused failing tests first for:

- a `0 -> 10` elevation crossing with an `8`-block river gap that must produce:
  - rising elevated approach
  - protected main span
  - descending exit approach
  - continuous adjacent-slice closure
- terrain-overlap cases where grass/dirt/mud blocks intersect the roadbed and must generate excavation/clearance positions
- bridgehead closure cases where a naive ribbon would leave a gap

### Integration tests

Add a production-path test that exercises `StructureConstructionManager.createRoadPlacementPlan(...)` and verifies:

- `corridorPlan()` is valid
- `ghostBlocks()` represent one continuous road corridor
- required excavation/clearance positions are reflected in owned/build artifacts

### Regression expectation

The specific reproduced scenario the user described should result in:

- road from low town to high town stays continuous
- river crossing becomes a high-clearance arched bridge with elevated approaches
- no road surface remains inserted inside shoreline grass/dirt/mud

## Implementation Notes

The intended next implementation plan should be broken into:

1. corridor model extension
2. continuity/bridgehead closure generation
3. excavation and clearance generation
4. placement artifact derivation updates
5. integration tests through the real placement builder

This keeps the fix scoped around one source of truth while directly targeting the remaining functional failures.
