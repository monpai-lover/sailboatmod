# RoadWeaver-Inspired Island Bridge Ramp Design

## Context

The current manual road planner still shows three user-visible failures:

1. Island-to-mainland connections can still stall in land-route attempts even when the target should clearly be handled as a bridge case.
2. Bridge approach ramps remain too steep and can create abrupt 1-block transitions that are hard to traverse.
3. Planning and shutdown stability are still fragile because routing, bridge generation, and placement retries are not bounded tightly enough.

The user wants the routing policy and bridge approach geometry to align more closely with the RoadWeaver reference while preserving the existing pier-node bridge model for long crossings. The specific requested behavior is:

1. If the target behaves like an island, allow only one short land probe before forcing bridge planning.
2. The short land probe must be bounded both by distance and by island/continuous-water signals.
3. Bridge ramps should use slab-first half-step elevation changes, stay straight while climbing or descending, and prefer long gentle ramps over short steep ones.
4. Flat road segments may turn, but ramp segments must never turn.

## Goals

1. Eliminate repeated island land-attempt loops during planning.
2. Make island routing converge quickly to bridge planning when land continuity is not immediately available.
3. Replace steep bridge approaches with straight, slab-first, low-slope ramps.
4. Preserve turn freedom on flat road segments while forbidding turns on active ramps.
5. Keep bridge construction ordered as supports first, deck second, decor last.
6. Bound planner work so route planning and world shutdown do not stall on long or failing searches.

## Non-Goals

1. This design does not replace the full road planner with RoadWeaver's complete architecture.
2. This design does not add new gameplay UI beyond using the existing planning-progress surface.
3. This design does not attempt a full rewrite of ordinary land-road curvature outside bridge-adjacent ramp logic.

## Reference Alignment

This design borrows two ideas from `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury`:

1. Height smoothing should be controlled by explicit run/rise constraints instead of ad hoc per-block jumps.
2. Route planning should reduce unnecessary terrain work through bounded sampling and staged search instead of retrying equivalent bad searches indefinitely.

The adaptation is intentionally selective. The current mod keeps its own bridge-pier construction model and world-integration rules.

## Architecture

The work is split into four bounded areas:

1. Island routing policy
2. Bridge ramp geometry
3. Bridge segment structure and placement ordering
4. Planning stability and cancellation

Each area should remain independently testable.

## 1. Island Routing Policy

### Problem

Current island handling can still spend too much time in land-route attempts, and failure often surfaces as generic missing-land output even when the route should have switched to bridge planning much earlier.

### Design

Island-aware routing becomes a strict state machine:

1. `TARGET_CLASSIFICATION`
2. `SHORT_LAND_PROBE`
3. `FORCED_BRIDGE_PLANNING`

If the target is classified as island-like, the planner enters island mode immediately.

### Island Classification

Island detection must remain feature-based rather than using a hardcoded distance threshold. The classifier should continue to derive the result from local terrain and connectivity signals such as:

1. surrounding water coverage
2. nearby stable land area
3. connectivity to larger mainland regions

The purpose is not to perfectly identify every island, but to detect when a normal land-first route search is a poor default.

### Short Land Probe

In island mode, the planner may perform exactly one short land probe:

1. It is attempted only once for the entire planning request.
2. It is bounded by a strict maximum search distance.
3. It terminates early if island-like or continuous-water signals are encountered again.
4. It is used only to catch trivial near-shore continuity, not to finish the full route.

### Forced Bridge Planning

If the short land probe does not resolve to an immediately usable land continuation, the planner switches to bridge planning and never returns to land mode during that same planning pass.

This rule is the primary safeguard against the current loop:

1. try land
2. fail
3. reset
4. try equivalent land search again

### Error Reporting

Failures from this flow should distinguish:

1. island land probe exhausted
2. bridge geometry invalid
3. no viable shore anchor
4. true continuous-land failure

This prevents bridge-worthy island routes from being mislabeled as generic missing-land errors.

## 2. Bridge Ramp Geometry

### Problem

Current bridge entries and exits can become too steep because bridge approaches are still treated too much like ordinary terrain-following road segments.

### Design

Bridge approach segments become a dedicated ramp type with separate geometry rules from flat roads and main bridge deck segments.

### Ramp Rules

1. A ramp locks a single horizontal direction once it starts.
2. Ramps may not turn horizontally while elevation is changing.
3. Any path correction or turn must happen in a flat pre-ramp or post-ramp segment.
4. A ramp begins with a slab-height transition instead of an immediate full-block rise.
5. Height progression should prefer half-step increments as long as continuity can be maintained.
6. Ramp profiles should be lengthened before they are steepened.

### Run/Rise Smoothing

The ramp profile should be derived from a run/rise constraint model inspired by RoadWeaver:

1. choose the target deck elevation
2. compute the minimum forward run required for a gentle climb
3. reject candidate bridgeheads that cannot fit the required straight ramp length

This means the bridge system does not "make do" with a steeper ramp when space is insufficient. It rejects the candidate and tries another bridge layout.

### Slab-First Progression

The visual and traversal profile should follow a slab-first progression:

1. start at ground level
2. transition into a half-step entry
3. continue through half-step-compatible ascent or descent
4. flatten into the bridge deck through a short settling zone near the top

The same rule applies symmetrically on descent.

### Flat-Road vs Ramp Separation

Flat segments may still turn and follow normal route-shaping rules. Ramps are not allowed to inherit that freedom. This explicit separation avoids the current failure mode where a path both climbs and bends, creating poor geometry and awkward traversal.

## 3. Bridge Segment Structure and Placement Ordering

### Segment Types

Bridge routes should be materialized as five ordered segment classes:

1. flat bridgehead approach
2. straight entry ramp
3. level bridge main span
4. straight exit ramp
5. flat reconnection segment

Only segment classes 1 and 5 may turn.

### Long vs Short Crossings

This design is compatible with the existing split between short and long bridges:

1. short crossings may still use a pure arch style without interior piers
2. long crossings continue to use pier-node-driven support placement

The ramp rules apply to both forms, because the user requirement is about walkable entry and exit behavior, not only about main-span structure.

### Placement Ordering

Construction ordering must remain fixed:

1. supports and piers first
2. traversable ramp and deck blocks second
3. railings, lights, and decorative blocks last

This prevents decorative occupancy from interfering with structural placement.

### Attempt-Once Forward Placement

Each forward placement unit should be consumed after its first execution attempt:

1. a placement action is attempted once
2. its result may be logged as success or imperfect placement
3. it is not re-queued into the same forward build loop

The planner may still discard an invalid bridge candidate and choose a different plan, but it must not retry the same forward placement unit indefinitely.

## 4. Planning Stability and Cancellation

### Segmented Search

Long routes should be broken into bounded planning stages rather than solved as one large search:

1. coarse route selection
2. island-vs-land decision
3. bridge candidate generation where needed
4. local bridge-detail refinement

This keeps bridge logic focused on the segments that actually need it.

### Sampling and Caching

Borrow the RoadWeaver-style idea of stable sampling before final geometry:

1. sample candidate corridors at a lower-resolution planning stage
2. reuse sampled terrain facts within the planning pass
3. smooth heights from those sampled anchors instead of reacting to every local terrain fluctuation

This reduces repeated work and helps the bridge deck stay visually stable.

### Budgeting

Every planning phase should have explicit limits:

1. land-probe attempt count
2. land-probe maximum distance
3. bridge candidate count
4. per-phase node/search budget
5. subdivision depth for segmented routing

If a budget is exhausted, the planner must fail fast with a specific reason instead of silently restarting equivalent work.

### Shutdown and Cancellation

Async planning tasks must check cancellation regularly and stop cleanly on:

1. world unload
2. game shutdown
3. player cancellation
4. stale planning requests replaced by newer ones

The progress UI should reflect real stage transitions only and should be cleared when the task is cancelled or invalidated.

## Data Model and Behavioral Changes

Likely changes include:

1. island-routing state in the manual planner request flow
2. explicit ramp segment classification in bridge/corridor plans
3. ramp geometry metadata that records direction, target height, and run/rise constraints
4. bounded-attempt bookkeeping for land probing and placement execution
5. richer failure reasons for island and bridge-specific failures

## Testing Strategy

All production behavior should be covered by targeted tests before implementation is considered complete.

### Routing Tests

Add tests proving:

1. island-like targets perform only one short land probe
2. land probing stops on distance budget
3. land probing stops on renewed island/water signals
4. failed island probes transition exactly once into bridge planning

### Ramp Geometry Tests

Add tests proving:

1. ramp segments are straight in plan view
2. ramps use slab-first entry/exit transitions
3. high deck elevations produce longer ramps instead of steeper ramps
4. insufficient straight space rejects the candidate bridgehead instead of generating a steep ramp

### Construction Tests

Add tests proving:

1. supports are emitted before deck blocks
2. deck blocks are emitted before decor blocks
3. each forward placement unit is consumed after one attempt

### Stability Tests

Add tests proving:

1. equivalent island land probes are not retried repeatedly in one plan request
2. segmented planning respects per-phase budgets
3. cancellation stops async planning before shutdown hangs occur

## Risks

1. More conservative ramp rules may reject bridgeheads that previously produced ugly but technically buildable bridges.
2. Stronger island forcing could over-prefer bridges if the island classifier is too aggressive.
3. Longer ramps require more horizontal footprint near shorelines.
4. Bounded search budgets can surface failures earlier, so failure messaging must be precise enough to remain debuggable.

## Rollout Plan

1. Add failing tests for island short-probe routing behavior.
2. Add failing tests for straight slab-first ramp generation.
3. Implement explicit ramp classification and run/rise smoothing.
4. Enforce bridge placement ordering and attempt-once execution semantics where still missing.
5. Add planning budgets and cancellation checkpoints.
6. Validate with compile/test passes and an in-game bridge route on island-to-mainland terrain.

## Success Criteria

The change is successful when:

1. island routes no longer loop in repeated land attempts
2. bridge planning takes over promptly after one bounded land probe
3. bridge ramps are visibly longer, straighter, and less steep
4. slab-first entry and exit transitions are present on generated ramps
5. ramp segments never turn while changing elevation
6. world shutdown no longer hangs on stale route-planning tasks
