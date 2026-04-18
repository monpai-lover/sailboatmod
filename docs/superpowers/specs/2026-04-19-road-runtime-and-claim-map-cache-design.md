# Road Runtime And Claim Map Cache Design

## Goal

Fix the current road system regressions and upgrade the land-road pipeline so roads prefer natural detours, but can fall back to terrain modification when necessary. At the same time, isolate claim-map/minimap cache data per world and dimension so world switches and dimension switches never leak stale preview data, while still allowing fast reload when returning to a previously visited map context.

## Scope

This design covers two linked areas:

1. Road runtime, pathfinding, bridge geometry, curve continuity, auto-construction recovery, and terrain modification.
2. Claim-map/minimap cache isolation across world changes and dimension changes.

This design does not cover broader UI polish for claim map progress messaging yet; that will be implemented after the road/runtime work stabilizes.

## Constraints And User Decisions

- Keep the existing road construction runtime, rollback flow, and ghost/build-step model.
- Keep manual and automatic construction; manual hammer/builder use accelerates construction rather than replacing the automated runtime.
- Bridge ends must always produce a touchdown sequence that returns the bridge to the terrain with a downhill run plus a platform, regardless of whether the endpoint is near ice, water, shoreline, shallow water, or ordinary land.
- Bridge turning segments must remain continuous; no deck/platform splitting at curve transitions.
- Land-road turning segments must also remain continuous; current Bezier/centerline behavior is not acceptable.
- Land-road pathfinding should primarily mimic the RoadWeaver approach:
  - prefer a natural detour first
  - if natural detour fails or is materially worse, allow terrain modification
  - support obstacle clearing, cut/fill, short water bridges, and tunnels
- Road paving should prefer replacing natural surface blocks instead of layering road blocks on top of untouched terrain where the road is intended to sit on grade.
- Automatic construction must recover from blocked steps by reordering or skipping permanently blocked actions, while surfacing the blocking reason to the player.
- Claim-map cache must be isolated per world/server and per dimension, and revisiting the same world/dimension should be able to reuse the previous cache bucket.

## Problem Summary

### Road And Bridge Issues

- Complex terrain frequently exhausts the current road node search even when a buildable route should exist.
- Current node search treats disconnected surface columns as fatal too early instead of allowing a later construction phase to solve them with clearing, short bridges, cut/fill, or tunnels.
- Bridge touchdown generation is too tightly clipped to the original bridge span, which can drop the final downhill/platform segment when the endpoint sits on ice or other shoreline transition columns.
- Bridge turns split because the current centerline-to-ribbon projection is continuous only along the center path, while deck/platform coverage is assembled from discrete independent slices.
- Land-road turns suffer the same failure mode, which points to a centerline/frame problem rather than a bridge-only bug.
- Automatic construction can stall permanently on a single blocked runtime action and stop advancing without a recoverable strategy or useful operator feedback.
- Rollback/dismantle does not always remove structural supports together with the corresponding deck/runtime-owned blocks.

### Claim Map Cache Issues

- Switching worlds can reuse stale minimap/claim-preview data from the previous world.
- Switching dimensions inside the same world can also show mismatched preview data.
- The current client cache has no strong world-context boundary, so pending/loading/ready state can outlive the context that produced it.

## Target Architecture

### 1. Road Planning Pipeline

Retain the current high-level runtime ownership:

- `StructureConstructionManager` remains the owner of jobs, runtime progression, rollback, and persisted road job state.
- `RoadPlacementPlan`, ghost blocks, build steps, and runtime save data remain the final execution contract.

Replace the road-planning internals with a stronger staged pipeline:

1. **Route search stage**
   - Search for a naturally traversable route first.
   - If no good natural route exists, search for a buildable route that allows terrain intervention.
2. **Path classification stage**
   - Annotate route segments as natural road, cut/fill roadbed, short bridge, long bridge/high bridge, or tunnel section.
3. **Height target stage**
   - Produce target deck/surface heights using a RoadWeaver-like smoothing and terrain adaptation model.
4. **Centerline/frame stage**
   - Produce a continuous centerline plus stable local frames for slice generation through turns.
5. **Geometry/corridor stage**
   - Generate closed road/bridge footprints, touchdown ramps, platforms, supports, and roadbed edits.
6. **Runtime execution stage**
   - Translate geometry and terrain edits into build steps that the existing runtime can execute, reorder, skip, rollback, and persist.

### 2. Claim Map Cache Context Layer

Introduce a strong context key shared by all client-side claim-map state:

- world/server identity
- dimension id
- a session-safe map context discriminator for the active connection or singleplayer world

Every claim-map cache bucket, pending request, loading state, and terrain tile cache entry must be attached to that context key. World switches and dimension switches should invalidate the active view state for the old key, but not destroy persisted data for other keys.

## Detailed Design

### A. Land Pathfinding

#### A1. Search Strategy

Upgrade `RoadPathfinder` and `RoadRouteNodePlanner` from "surface-column traversability only" to "natural-path-first, construction-aware fallback".

The planner should run in two passes:

1. **Natural detour pass**
   - Strongly prefer contiguous terrain-following roads.
   - Penalize rough terrain, sharp turns, water adjacency, and steep columns.
   - Disallow terrain modification actions.
2. **Construction-aware pass**
   - Activated only when the natural pass fails or is materially worse than a buildable alternative.
   - Allows the route to claim columns that can later be cleared, cut, filled, tunneled, or bridged.

The pass result should explicitly record why a column is accepted:

- natural surface
- clearable obstacle
- cut/fill candidate
- short bridge candidate
- tunnel candidate
- long bridge candidate

This removes the current failure mode where a path is rejected solely because a column is not immediately walkable at search time.

#### A2. Terrain Modification Priority

The fallback planner must still prefer easier work:

1. clear obstacles
2. small fill / small cut
3. short bridge over small water
4. tunnel / pass-through mountain
5. long bridge / high bridge

This preserves the user requirement that the system should not blindly bulldoze terrain when a normal detour works.

#### A3. RoadWeaver-Inspired Behavior

Borrow the behavior model from RoadWeaver without replacing the entire current runtime:

- detour-first route selection
- buildable-terrain fallback
- explicit target-height smoothing
- classification of spans before actual placement
- carve/fill semantics performed during the construction phase

The mod should not copy RoadWeaver's full data model wholesale; instead, it should port the behaviors that improve passability while still producing the existing `RoadPlacementPlan` contract.

### B. Centerline And Turn Continuity

#### B1. Root Cause

Current centerline generation and ribbon projection can keep the center path continuous while allowing the outer deck/road surface to split at turns. This affects both:

- bridge deck turns
- ordinary land-road turns

That means the centerline/sample-frame logic is the shared fault line.

#### B2. New Rule

Slice generation must use continuous local frames along the centerline, not independent nearest-projection slices with no continuity guarantee across adjacent turn samples.

At a high level:

- each centerline sample provides tangent and lateral orientation
- adjacent frames participate in footprint closure
- turn segments use the union/closure of adjacent slices so the outer edge remains filled

This should be informed by the stable curve handling used in:

- `RoadWeaver` for road segment interpolation and smoothing
- `TongDaRailway` for elevated curved deck continuity

The key output requirement is simpler than the reference implementations:

- no visible split in curved elevated bridge platforms
- no missing outer road surface on curved land roads

### C. Bridge Touchdown And End Segments

#### C1. Hard Endpoint Rule

Any bridge endpoint must force a complete touchdown package:

- downhill ramp
- touchdown platform
- reconnect to terrain

This rule applies regardless of endpoint surroundings:

- ice
- water edge
- shore transition
- shallow water
- ordinary land

#### C2. Span Clipping Change

Touchdown segments must no longer be clipped strictly by the original bridge span range. The bridge endpoint recovery sequence is treated as mandatory bridge-tail geometry that extends until the deck is safely returned to the terrain.

#### C3. Turn-Aware Bridge Tail

If a bridge endpoint also turns, the touchdown ramp and platform must be generated under the same continuous-frame closure logic used for curved bridge decks so the bridge cannot be visually cut off at the shoreline or turn.

### D. Construction Runtime

#### D1. Runtime Action Model

Construction should be represented as ordered actions in categories that the runtime can execute, skip, rollback, and serialize:

- clear obstacle
- cut terrain
- fill roadbed / platform
- place surface / deck
- place support / structural pillar
- place decoration / railing / light

This allows the build system to treat terrain modification as first-class work rather than as an out-of-band side effect.

#### D2. Auto-Build Stall Recovery

The runtime must no longer halt indefinitely on one blocked step.

Required behavior:

- diagnose the currently blocked step category and reason
- reevaluate pending steps continuously during auto-build ticks
- skip or reorder permanently blocked steps where safe
- continue advancing the job whenever later steps are still valid
- notify the user why the job is partially blocked

Representative blockage reasons:

- occupied target
- invalid headroom
- failed obstacle clear
- missing support dependency
- step already satisfied by world state
- runtime ownership mismatch
- worker/path access issue

#### D3. Rollback / Dismantle Ownership

Supports, roadbed fill, cleared obstacles that were replaced by runtime-owned blocks, and bridge structure blocks must all share ownership metadata compatible with rollback/dismantle so removing a bridge does not leave orphan supports behind.

### E. Surface Replacement Rules

Ordinary roads should no longer default to floating on top of the natural surface.

When a road is intended to run on grade:

- replace the natural top block where appropriate
- normalize shallow bumps with fill/cut before laying the final road surface
- reserve elevated construction only for actual bridge/high-road/touchdown structures

Natural-surface replacement targets include:

- grass blocks
- dirt-like terrain
- mud
- sand / gravel-like natural surface

The exact replaceable block policy should remain conservative enough to avoid destroying obviously man-made surfaces unless the road planner has explicitly classified the area as modifiable roadbed.

### F. Claim Map Cache Isolation

#### F1. Context Key

Introduce a map cache context key used by:

- `TerrainColorClientCache`
- `TownClientHooks`
- `NationClientHooks`
- screen-side preview state
- pending viewport requests
- persisted terrain preview cache

The key must include:

- world/server identity
- dimension id
- connection/session discriminator robust enough to avoid stale carryover between worlds with overlapping metadata

#### F2. Switching Behavior

On world switch or dimension switch:

- active screen/runtime state must immediately stop reading the old cache bucket
- pending/loading/queued state tied to the old key must be invalidated for the active UI
- persisted buckets for old contexts remain available for future reuse

#### F3. Reuse Behavior

When the player returns to the same world and dimension:

- the matching cache bucket can be reused immediately
- old preview data may be shown only if its context key matches exactly
- refresh logic can still request newer data and mark the bucket loading/incomplete as needed

## File-Level Impact

Expected primary files:

- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadBezierCenterline.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- `src/main/java/com/monpai/sailboatmod/client/cache/TerrainColorClientCache.java`
- `src/main/java/com/monpai/sailboatmod/client/TownClientHooks.java`
- `src/main/java/com/monpai/sailboatmod/client/NationClientHooks.java`
- `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`

Expected support files/tests:

- road runtime and geometry tests under `src/test/java/com/monpai/sailboatmod/construction/`
- runtime/rollback tests under `src/test/java/com/monpai/sailboatmod/nation/service/`
- claim-map cache isolation tests under `src/test/java/com/monpai/sailboatmod/client/` and `src/test/java/com/monpai/sailboatmod/network/packet/`

## Verification Requirements

### Road/Bridge

- Complex terrain no longer frequently fails with `search_exhausted` when a buildable route exists.
- The planner prefers natural detours when reasonable, and only falls back to terrain modification when the natural route fails or is materially worse.
- Curved bridge decks remain continuous.
- Curved land roads remain continuous.
- Bridge endpoints always generate downhill + platform + terrain reconnect.
- Terrain-clearing, cut/fill, tunnel, and short-bridge actions execute through the runtime.
- Auto-build no longer stalls silently at a fixed percentage.
- When a job is partially blocked, the player receives the blocking reason.
- Rollback/dismantle removes supports and runtime-owned structural blocks together.

### Claim Map Cache

- Switching worlds never displays the previous world's cached minimap/claim preview.
- Switching dimensions never cross-contaminates preview data.
- Returning to a previous world/dimension can reuse its cache bucket.

## Risks

- The road pipeline changes are coupled: pathfinding, centerline generation, corridor geometry, and runtime sequencing all influence each other.
- Over-porting RoadWeaver concepts without adapting them to the existing runtime contract could create a second incompatible planning model.
- Cache context identity must be chosen carefully to avoid both false sharing and unnecessary cache fragmentation.

## Recommended Implementation Order

1. Diagnose and harden the auto-build runtime so stalls become observable and recoverable.
2. Fix mandatory bridge endpoint touchdown generation.
3. Fix curve continuity by upgrading centerline/frame generation.
4. Upgrade pathfinding to natural-first with construction-aware fallback.
5. Integrate terrain-modification actions into runtime execution and rollback.
6. Add claim-map cache context isolation and persisted bucket reuse.
