# Bridge Road Transition Ramp Design

Date: 2026-05-19

## Summary

Bridge ramps can currently fail to meet the nearby road surface at the bridge heads. In the visible failure case, the bridge uphill ramp starts below the road, while a separate road surface remains above or beside it. The fix should let bridge emission absorb a small amount of land-side road as a transition ramp, while leaving the ordinary road up/down slope logic unchanged.

This design only changes bridge-side transition planning and bridge-vs-road overlap handling. It does not change pathfinding, autocomplete, node classification, ordinary road slope slab orientation, or the current road surface height smoothing.

## Current Problem

The Road Planner build/preview pipeline emits ordinary road and bridge geometry in separate passes:

1. `RoadSurfaceStepEmitter` emits all road spans.
2. `BridgeStructureEmitter` emits all bridge spans.
3. `RoadNodeStructureExpander` dedupes final `BuildStep`s by full `BlockPos`.

This creates two related issues at road/bridge boundaries:

- A bridge span starts at the first bridge-classified centerline point, which may be lower than the adjacent road approach.
- The dedupe layer only resolves exact `BlockPos` conflicts. If road and bridge occupy the same X/Z column at different Y values, both can survive, leaving an upper road slab and a lower bridge ramp in the same transition area.

Recent bridge fallback logic prevents one class of vertical cliff when legacy half-step pairing cannot fit, but it can preserve a low bridge profile rather than extending the ramp to the road approach. That keeps Y continuity inside the bridge profile but does not guarantee a clean bridge-to-road connection.

## Goals

- Bridge uphill/downhill ramps must connect to the adjacent road surface without a sunken first ramp step.
- The bridge transition may consume up to two land-side road samples on each bridge end.
- Normal bridge spans with enough room should continue using the legacy half-slab sequence:
  - uphill: `BOTTOM`, `TOP`, then Y+1
  - downhill: Y-1, then `TOP`, `BOTTOM`
- Short or steep bridge heads should prefer using absorbed land-side transition samples before falling back to a linear ramp profile.
- Road/bridge overlap in the transition area should resolve in favor of bridge ramp/deck blocks for the same X/Z columns.
- Ordinary road slope logic in `RoadSurfaceStepEmitter` must not be modified.

## Non-Goals

- No pathfinding or autocomplete changes.
- No changes to road node type assignment or bridge-node splitting.
- No changes to `RoadSurfaceStepEmitter` ramp slab orientation, because ordinary road up/down slope behavior is already fixed.
- No new bridge template assets.
- No global dedupe behavior changes outside road/bridge transition columns unless required for correctness.

## Proposed Architecture

Add bridge transition handling inside the bridge emission path.

### Transition Context

`BridgeStructureEmitter` should pass enough context to each bridge emission call to see neighboring road points:

- the full centerline
- the bridge span start/end indexes in that full centerline
- the same `RoadSpan` list used by the road emitter

The emitter will derive a local bridge emission input that can include up to two adjacent road samples on each side when those samples are road spans and are close enough in X/Z.

### Transition Profile Builder

Introduce a small bridge-side helper, for example `BridgeTransitionProfile`, with one job:

- Build an emission-only centerline for a bridge span.
- Mark which points are real bridge points and which are absorbed transition road points.
- Preserve source X/Z and target Y for absorbed road points so the ramp starts at the road surface height.

The canonical nodes, canonical segment types, and route sections do not change. This profile is only for preview/build block emission.

### Legacy Ramp Preservation

The bridge ramp profile should still use legacy half-step pairing when it can represent the required rise/fall with available samples.

When a normal bridge span lacks enough ramp samples:

1. Try adding transition samples from adjacent road spans.
2. Re-evaluate whether the legacy half-step sequence now fits.
3. If it fits, use the legacy sequence.
4. If it still does not fit, use the current profile-based fallback so there is no vertical cliff.

This keeps the older stable visual style for the common case and reserves fallback behavior for physically cramped cases.

### Bridge Wins In Transition Columns

When bridge transition samples overlap previously emitted road surfaces, final output should not leave two usable surfaces in the same X/Z column. Bridge ramp/deck should win over road surface for transition columns.

Preferred implementation:

- Record bridge transition X/Z columns during bridge emission.
- During final dedupe, if a road `SURFACE` or road `RAMP` shares an X/Z column with a bridge `RAMP` or `DECK` in the transition set, keep the bridge block and discard the road block.

This should be scoped to transition columns so normal road/bridge separation elsewhere is not disturbed.

## Data Flow

1. `RoadNodeStructureExpander.expand(...)` builds centerline and spans as it does now.
2. `RoadSurfaceStepEmitter.emit(...)` emits ordinary road steps unchanged.
3. `BridgeStructureEmitter.emit(...)` receives full centerline and bridge spans.
4. For each bridge span:
   - find up to two road samples before the span
   - find up to two road samples after the span
   - build an emission-only profile including those samples
   - plan bridge geometry over the expanded profile
   - emit ramp/deck/rail/support/light steps
   - tag or expose transition X/Z columns for scoped road-overlap removal
5. `RoadNodeStructureExpander` dedupes by `BlockPos` as before, plus scoped transition X/Z bridge priority if needed.
6. Preview blocks are derived from the deduped build steps as before.

## Edge Cases

- If the adjacent point is not road, do not absorb it.
- If the adjacent point is separated by a route break or null section, do not absorb it.
- If absorbing road samples would reverse direction or create duplicate X/Z samples, skip the bad sample.
- If only one side has road, only that side gets transition expansion.
- If a short bridge is already flat and connected, transition expansion should not create extra visual height changes.
- If both sides are steep and still cannot fit legacy half-step ramps after expansion, keep the fallback profile rather than introducing cliffs.

## Testing Plan

Add tests before implementation:

- A bridge uphill approach with adjacent road higher than the first bridge point emits a first transition ramp at the road surface Y.
- A bridge transition overlap does not leave road and bridge surfaces at different Y in the same X/Z column.
- A bridge with enough transition samples keeps the legacy `BOTTOM/TOP` uphill and `TOP/BOTTOM` downhill sequence.
- Existing ordinary road crest/up-down slope tests remain unchanged and passing.
- Existing bridge ramp/deck boundary tests remain passing.
- Full build still packages the jar.

## Risks

- Expanding bridge emission into road samples can conflict with road streetlights or railings near the bridge head. The transition priority should only remove road surface/ramp blocks, not unrelated streetlights unless they directly collide with bridge blocks.
- Bridge pier/support logic must use the expanded emission profile for ramp support positions, but pier planning should remain tied to actual deck bridge samples where possible.
- Wider roads and turns near bridge heads may expose X/Z overlap bugs. Tests should include at least one straight case first, and a turn case if the initial fix touches footprint ownership.

## Acceptance Criteria

- The visible bridge head no longer starts below the adjacent road surface.
- The bridge transition area has a single usable surface per X/Z column.
- Ordinary road slope behavior remains unchanged.
- Legacy bridge half-step visual style remains for bridge spans with enough samples.
- Focused bridge/road structure tests and `gradlew build` pass.
