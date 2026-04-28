# Road Planner Unified Build Pipeline Design

Date: 2026-04-28

## Goal

Unify all road planner route sources into one build pipeline so manual lines, Bezier curves, cross-water tools, endpoint routes, and auto-complete routes produce the same preview and the same final construction result.

The planner remains responsible for route design. The build backend becomes responsible for turning the route into `BuildStep` entries. Ghost preview, queued construction, cancellation, and rollback all consume the same compiled `BuildStep` list.

## Problems Being Fixed

- Manual and Bezier routes can fail to create real road structures while auto-complete routes work.
- Bridge preview can appear without the connected road, ramp, or landing sections.
- Road surface can be generated underwater when a normal road segment crosses water.
- Ghost preview can render many duplicated or clustered boxes, causing severe FPS drops.
- Preview output and actual build output can diverge because they are not guaranteed to share the same compiled result.
- New planner construction has a queue progress model but no fully wired construction progress bar.

## Chosen Approach: A + C Hybrid

Use one canonical planner route expansion step, then delegate construction to mature backends:

- `ROAD` and `TUNNEL` segments use the RoadWeaver/RoadBuilder-style road construction backend.
- `BRIDGE_MAJOR` uses the old pier bridge backend with land anchor, ramp up, bridge deck, ramp down, and land anchor semantics.
- `BRIDGE_SMALL` can use the lightweight bridge path or bridge backend depending on existing selector rules.
- Preview and actual construction both use the exact same `List<BuildStep>`.

## Route Flow

1. `RoadPlannerScreen` produces only route intent:
   - ordered nodes
   - segment types
   - start town anchor
   - destination town anchor
   - build settings
2. `RoadPlannerRouteExpander` normalizes the route:
   - resamples sparse segments
   - converts Bezier curves to continuous nodes
   - samples terrain height
   - detects water spans and large drops
   - inserts bridge landing topology where needed
   - prevents normal roads from staying underwater
3. `RoadPlannerBuildStepCompiler` compiles the expanded route:
   - groups consecutive segments by type
   - routes land/tunnel sections into RoadBuilder-style generation
   - routes major bridge sections into the old bridge backend
   - deduplicates build steps by position and phase where safe
4. The preview service stores the compiled steps.
5. Ghost preview renders the compiled steps.
6. Confirm build enqueues the same compiled steps.
7. Queue tick sends progress and supports cancellation rollback.

## Water and Bridge Rules

Normal road segments cannot place road surface under water. When expansion detects a water crossing:

- Small shallow crossings become `BRIDGE_SMALL` if bridge auto-conversion is allowed.
- Large or deep crossings become `BRIDGE_MAJOR`.
- If the active tool does not allow bridge conversion, the segment is marked blocked and the UI must show that the bridge tool is required.
- Bridge segments must extend to valid land anchor nodes on both sides.
- Major bridges must preserve node direction: ramp up follows the route direction from the entry land anchor, bridge deck follows the route, ramp down ends at the exit land anchor.

## Ghost Preview Rules

Ghost preview renders only visible final build steps and must not become a performance hazard.

Preview filtering:

- skip air-clearing steps unless explicitly debugging
- skip duplicate position/state/phase entries
- cap the number of boxes rendered per frame
- prioritize nearby boxes around the player/camera
- use different colors for road, bridge deck, ramp, pier, tunnel, and warning states

The preview renderer must not independently infer road geometry. It only displays the compiled build result.

## Construction Progress

The new road planner build queue must expose construction progress to the client.

Server behavior:

- tick active `RoadPlannerBuildControlService` queues on server tick
- execute a bounded number of build steps per tick
- periodically send `SyncRoadConstructionProgressPacket`
- clear progress when the job completes or is cancelled

Client behavior:

- render a real progress bar for active planner construction
- show percent, completed/total steps, and active state
- allow cancellation to run rollback using the existing ledger

## Validation

Automated validation should cover:

- manual two-node route compiles into road build steps
- Bezier-expanded route compiles into road build steps
- water-crossing road route does not create underwater road surface
- major bridge route creates ramp, deck, pier, and landing steps
- ghost preview uses the same build steps as confirmation
- build queue progress increases and sync entries can be generated

Manual validation should cover:

- bridge preview visually matches final build
- normal road no longer appears on the sea floor
- reopening planner does not duplicate preview nodes
- FPS remains stable with large preview routes
- progress bar appears after confirming construction

## Non-Goals

- Redesigning the whole planner UI again.
- Replacing the old bridge geometry algorithm.
- Adding new town claim rules beyond the existing endpoint/anchor validation.
- Changing unrelated nation or town construction systems.
