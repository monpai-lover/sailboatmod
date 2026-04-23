# Short-Span Flat Bridge Design

Date: 2026-04-23

## Summary

Add a new road bridge classification for small gaps: `short_span_flat_bridge`.

This bridge type exists to fix the visible bad cases in small canyons, ravines, and narrow water crossings:

- no hanging half-road over small gaps
- no large-pier bridge treatment for tiny crossings
- no changes to existing sea, large-water, or long-bridge behavior

The bridge deck stays flat, uses no piers, takes the higher endpoint elevation, and lets the lower side ramp up to it.

## Problem

The current road stack behaves like a terrain-following pathfinder with bridge construction added later. That creates two visible problems in small crossings:

- bridge detection is not unified, so a segment can be treated differently in post-processing and construction
- small water gaps and short land chasms often get the wrong treatment: ground path clipping, awkward suspended road, or a larger bridge style than desired

The current large-water and sea bridge behavior must remain unchanged.

## Goals

- Add a bridge mode that specifically handles short canyon and small-water crossings.
- Use a flat bridge deck with no piers for crossings up to 12 blocks wide.
- Apply the same rule across all land-road generation that uses the shared `road` system.
- Keep existing large-water, sea, and long-bridge logic intact.
- Fail conservatively: if the short-span classifier is unsure, fall back to current behavior.

## Non-Goals

- No rewrite of the main pathfinder.
- No full vertical-profile redesign.
- No change to sea bridge or large bridge rules.
- No new flood-fill water classifier in this iteration.
- No attempt to support unrealistic long pierless bridges.
- No broad tunnel-system redesign.

## User-Confirmed Behavior

- Primary target: small canyons and small water surfaces should use a short flat bridge with no piers.
- Large water, sea, and existing long-bridge behavior must not be modified.
- Pure land gaps are eligible, not just water.
- Flat bridge elevation uses the higher side of the two endpoints.
- Maximum short-span width is 12 blocks.
- This should apply across all routes built through the shared land-road system, not just one entry point.

## Design Overview

Introduce a new classification layer between path post-processing and bridge construction.

Instead of treating every detected bridge span as the same type, classify each candidate gap as one of:

- `SHORT_SPAN_FLAT`
- `REGULAR_BRIDGE`

`SHORT_SPAN_FLAT` has higher priority than the normal bridge branch, but only inside a tightly limited rule set.

If a candidate does not clearly satisfy the short-span rules, it must remain a normal bridge candidate or a normal ground segment.

## Classification Rules

### Candidate Gap

Scan the center path for consecutive columns that require unsupported crossing behavior.

A candidate gap is a continuous segment where either of these conditions is true:

- `water_gap`: the column is water and meets the existing minimum water-depth threshold
- `land_gap`: the column is not water, but the planned road elevation is at least 3 blocks above the support landing height

Support landing height is:

- ocean floor for water columns
- terrain height for land columns

### Short-Span Eligibility

A candidate gap becomes `SHORT_SPAN_FLAT` only if all of the following are true:

- effective horizontal span is `<= 12` blocks
- the segment is either a small water gap or a short land gap
- the segment does not show sea or large-water characteristics
- endpoint elevation difference is `<= 4` blocks

### Small Water Gap Rules

A water candidate is eligible only when:

- contiguous water crossing width is `<= 12`
- nearby biome signals do not indicate ocean-scale water
- both sides reconnect to stable land within a short distance
- cheap lateral width sampling shows a narrow crossing, not a broad body of water

This is intentionally conservative. If the water body looks broad, open, or sea-like, the short-span rule does not trigger.

### Short Land Gap Rules

A non-water candidate is eligible only when:

- contiguous unsupported land-gap width is `<= 12`
- at least 2 columns in the segment satisfy the land-gap threshold
- the two ends can still be joined with a reasonable low-side ramp

## Elevation Rules

For `SHORT_SPAN_FLAT`:

- `bridgeY = max(entryY, exitY)`
- every bridge-deck column in the classified span uses `bridgeY`
- the higher side connects directly
- the lower side ramps upward into the flat deck

This keeps the bridge visibly flat and avoids mid-span sagging or terrain-following deck noise.

## Architecture Changes

### 1. Bridge Detection and Classification

Primary file:
[BridgeRangeDetector.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgeRangeDetector.java)

Current behavior only returns `BridgeSpan` ranges.

Change:

- detect continuous candidate gaps
- classify each gap as `SHORT_SPAN_FLAT` or `REGULAR_BRIDGE`
- return classified span data instead of forcing every detected gap into the same bridge type

Recommended shape:

- add a span kind enum or equivalent typed field
- preserve current `BridgeSpan` data needed by existing large-bridge code

### 2. Shared Upstream/Downstream Bridge Data

Primary file:
[RoadBuilder.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/road/construction/road/RoadBuilder.java)

Current behavior redetects bridge spans during construction.

Change:

- if classified bridge spans are already available from upstream processing, use them directly
- only use the old redetection path as a compatibility fallback

Reason:

- avoids post-process vs construction disagreement
- stops short-span classification from being overwritten by later generic bridge detection

### 3. Short Flat Bridge Construction Branch

Primary file:
[BridgeBuilder.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilder.java)

Add a dedicated build branch for `SHORT_SPAN_FLAT`:

- place flat bridge deck at `bridgeY`
- do not create piers
- generate only the deck, railings, and low-side connection ramp
- do not route through the current long-bridge deck/pier partitioning path

Existing `REGULAR_BRIDGE` behavior remains unchanged.

### 4. Post-Processing Stability Near Bridge Heads

Primary file:
[PathPostProcessor.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/road/pathfinding/post/PathPostProcessor.java)

Do not redesign the whole vertical profile in this task.

Do add two targeted constraints:

- short-span flat bridge columns are height-clamped to the chosen flat deck height
- a small buffer around both bridge heads is excluded from relaxation and spline drift that would reintroduce sagging, edge dip, or terrain snapping

## Data Model Recommendation

The implementation should stop treating all bridge spans as equivalent.

Recommended additions:

- `BridgeSpanKind`
  - `SHORT_SPAN_FLAT`
  - `REGULAR_BRIDGE`
- classified span data that includes:
  - start index
  - end index
  - water surface Y
  - support floor Y
  - span kind
  - chosen deck Y if precomputed

This keeps bridge policy explicit instead of scattering span decisions across detector, post-process, and builder code.

## Failure and Fallback Rules

This feature must fail conservatively.

If any of these checks fail, do not force a short flat bridge:

- uncertain sea or large-water classification
- span width exceeds 12 blocks
- endpoints differ too much to support a clean short ramp
- bridge head geometry becomes unstable after processing
- candidate detection is ambiguous

Fallback behavior:

- keep the segment on existing bridge or detour logic
- never allow short-span flat bridge failure to corrupt the rest of the route

## Testing Strategy

Acceptance is visual first, not architectural first.

Required validation scenarios:

1. Small water crossing
- a narrow stream or river gap up to 12 blocks wide
- expected result: flat bridge, no piers

2. Small canyon crossing
- a short unsupported land ravine up to 12 blocks wide
- expected result: flat bridge, no piers

3. Uneven endpoints
- one side higher than the other
- expected result: bridge deck uses the higher side, lower side ramps up cleanly

4. Large water crossing
- more than 12 blocks or clearly broad water
- expected result: unchanged current long-bridge or detour behavior

5. Sea or large-water biome
- expected result: unchanged current sea-bridge behavior

Recommended automated coverage:

- bridge classification unit tests
- short-span build output tests
- regression test confirming that regular bridge handling is unchanged for long spans

## Risks

- If the small-water filter is too loose, the new classifier may steal spans from the existing large-bridge path.
- If the short-land-gap rule is too aggressive, ordinary terrain undulations may be over-bridged.
- If the builder still redetects spans independently, the new classifier will not be trustworthy.

## Rollout Notes

This is a scoped behavior fix, not a full road-system redesign.

The implementation should prefer the minimum set of changes that:

- introduce explicit short-span classification
- preserve current large-bridge behavior
- improve visible output for small canyons and narrow water crossings

The long-term vertical-profile redesign can still happen later, but this feature must stand on its own without requiring that larger rewrite first.
