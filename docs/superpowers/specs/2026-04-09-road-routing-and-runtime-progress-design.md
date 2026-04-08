# Road Routing And Runtime Progress Design

## Context

The current road construction feature has three gaps:

1. Runtime road progress is still tied to the legacy `SyncRoadConstructionProgressPacket` flow and old road planner HUD, so nearby managers do not reliably see progress.
2. Manual road routing still behaves like a soft-preference terrain search. It can cross water too freely, it does not treat building obstruction as a hard constraint, and its start/end anchors are not strict enough about using town boundary land.
3. Short bridge support is needed, but only as a fallback to connect otherwise-valid land routes. The system must reject routes that effectively become long bridge corridors.

## Goals

- Roads should start and end on land near the boundary of the two towns involved.
- Route search should prefer land, avoid buildings, and only use short bridge spans as limited assistance.
- If a valid route would require excessive bridge length, planning should fail instead of creating a mostly-bridge road.
- Runtime road progress should be visible to nearby authorized players while holding `builder_hammer`, `constructor`, or `road_planner`.
- Runtime road ghost preview and road progress should describe the same active construction job set.

## Non-Goals

- No fully generic bridge generation system.
- No redesign of the user-facing "confirm road plan" interaction flow.
- No change to building construction routing or building placement rules outside the road obstruction checks needed for pathing.

## Recommended Approach

Use a constrained road planner:

- Anchor selection becomes explicit and boundary-aware.
- Pathfinding moves from "soft water penalty" to "land-first hard constraints with bounded bridge exceptions".
- Obstruction checks apply both during node expansion and during path simplification/smoothing.
- Runtime road progress visibility is aligned with the new runtime ghost preview audience instead of the legacy owner-only sync.

This keeps the existing road planning feature shape intact while fixing the actual behavior.

## Design

### 1. Town Anchors

For each town:

- Collect claims in the current dimension.
- Prefer an existing road node inside those claims if one exists and is near the side facing the other town.
- Otherwise choose a boundary land anchor on a claim edge that faces the target town.
- Reject anchors that sit on water, unstable terrain, or blocked low-clearance spots.
- Preserve the current fallback to a town core-adjacent surface only when no valid boundary land anchor exists.

This makes the road connect from "town edge land" instead of drifting toward arbitrary interior coordinates.

### 2. Land-First Pathfinding

Pathfinding should treat terrain in three categories:

- Valid land nodes: normal expansion targets.
- Bridge-eligible gap nodes: water-separated connectors that may be used only in short spans.
- Blocked nodes: building-obstructed or otherwise invalid targets.

Rules:

- Normal search expands only across valid land nodes.
- Water is not a normal traversable surface anymore.
- Bridge traversal is allowed only when the current expansion stays within configured bridge limits:
  - maximum contiguous bridge span
  - maximum total bridge length for the full road
- If the only possible route exceeds either bridge limit, path search fails.

This prevents "the whole road became a bridge" outcomes.

### 3. Building Obstruction Avoidance

A road candidate column should be rejected when any of the following are true:

- The road surface would occupy an already-solid built space.
- The road headroom is blocked.
- The candidate would cut through obvious constructed obstacles instead of open terrain.

The same obstruction validation must be re-applied in:

- direct expansion checks
- line-of-travel shortcut checks
- smoothing / path simplification

This avoids the current problem where a raw path or smoothing pass can still clip through structures.

### 4. Runtime Road Progress Visibility

Replace the owner-only visibility rule with the same audience used for runtime ghost previews:

- player is in the same level
- player is near the active road job
- player is holding `builder_hammer`, `constructor`, or `road_planner`
- player has permission to manage that construction context

Progress data should no longer depend on "original owner only" filtering.

### 5. Runtime UI Behavior

Runtime road progress should be shown together with runtime road ghost previews:

- The road ghost remains the primary world-space signal.
- Progress text should be available whenever the runtime road preview is visible.
- Builder hammer users should be able to see the nearby target road job's progress without switching back to the road planner-only HUD.

Implementation may reuse the existing overlay renderer or move progress text into the runtime construction preview renderer, but the display condition must match the runtime preview condition.

### 6. Legacy Packet Crash Guard

The already-identified packet string-length crash remains part of this work's acceptance criteria:

- The deployed jar must include the increased road/job id limits.
- Active road sync packets must not crash when road ids exceed 80 characters.

## Data Flow

1. Road plan request resolves source and target town anchors.
2. Pathfinding produces a constrained path with land-first expansion and bounded bridge segments.
3. Manual road confirmation schedules the construction job as before.
4. Server tick updates active road progress.
5. Nearby authorized players holding preview tools receive:
   - runtime road ghost preview entries
   - runtime road progress entries
6. Client renders:
   - road ghost blocks in world space
   - matching progress information in the runtime overlay path

## Error Handling

- If no valid boundary land anchor exists for either town, planning fails with a clear route failure result.
- If land routing fails and any bridge-assisted route exceeds limits, planning fails instead of degrading into a mostly-water route.
- If a smoothing step introduces an invalid obstruction or water crossing, that shortcut is rejected and the safer path is retained.

## Testing

Add focused tests for:

- long road/job ids still encoding without packet failure
- boundary anchor selection preferring land-facing claim edges
- path rejection when only long bridge corridors are available
- path acceptance for short bridge gaps within configured limits
- path rejection when buildings obstruct the straight route but acceptance when a valid detour exists
- runtime road progress audience selection matching the runtime preview rules

## Acceptance Criteria

- A planned road connects from land near one town boundary to land near the other town boundary.
- Roads avoid water when a land route exists.
- Short bridge segments are allowed only as bounded assistance.
- Roads do not path directly through buildings or blocked low-clearance structures.
- Nearby authorized players can see active road construction progress while holding `builder_hammer`, `constructor`, or `road_planner`.
- Deploying the rebuilt jar no longer crashes on long road ids in `SyncRoadConstructionProgressPacket`.
