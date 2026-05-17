# Road Planner Two-Stage Pathfinding Diagnostics Design

## Context

The road planner auto-complete UI lets the player choose one of four algorithms: Basic A*, Bidirectional A*, Gradient Descent, and Potential Field. Recent testing showed that all choices produce routes that are visually close to a direct line between the two towns.

Historical comparison points to the pathfinding runner rather than the Potential Field implementation itself. The older working version used a two-stage pipeline:

1. A coarse route search.
2. A high precision Potential Field refinement along a corridor around the coarse route.

The current runner was simplified to a single selected algorithm pass. That removed the high precision Potential Field refinement that previously produced better terrain-following paths.

## Goals

- Restore the two-stage pathfinding pipeline for road planner auto-complete.
- Keep the current algorithm selection UI.
- Add server-side diagnostics that make it clear whether the selected algorithm ran, failed, was rejected by obstacle checks, or was later reduced by post-processing.
- Preserve the current fallback behavior when the runner returns no route. This spec does not change fallback policy.

## Non-Goals

- Do not redesign the road planner UI.
- Do not remove the algorithm selection menu.
- Do not tune every pathfinding cost weight in this change.
- Do not disable the existing straight-line interpolation fallback.
- Do not change bridge construction geometry.

## Design

`RoadPlannerAutoCompleteRequestPacket` continues to send the selected algorithm index to the server. The server converts that index to a `PathfindingConfig.Algorithm` and passes it into `RoadPlannerPathfinderRunnerFactory.serverService(...)`.

The runner will build one service with this route flow:

1. Adjust start and destination away from blocked core or structure columns when needed.
2. Build a route obstacle mask from nation, town core, and placed structure data.
3. Run the selected algorithm as the coarse stage using normal precision.
4. If the coarse stage succeeds and passes obstacle validation, build a corridor cache around the coarse path.
5. Run a fine stage with `PotentialFieldPathfinder`, `aStarStep = 4`, and high sampling precision inside the corridor.
6. Return the fine path if it succeeds and passes obstacle validation.
7. Return the coarse path if the fine path fails or is rejected.
8. Return an empty list only if the coarse stage fails or is rejected.

This restores the old Potential Field behavior while still letting the selected algorithm influence the coarse route.

## Diagnostics

Diagnostics will use the mod logger from the server-side auto-complete path. Each auto-complete request should log one compact line at info level with:

- selected algorithm
- original start and destination
- adjusted route start and destination
- coarse success flag, node count, and failure reason
- fine success flag, node count, and failure reason
- final node count
- whether the coarse path was rejected by the obstacle mask
- whether the fine path was rejected by the obstacle mask
- maximum lateral deviation from the original start-to-destination line

`RoadPlannerAutoCompleteService` will also log whether it used the runner path or straight interpolation fallback, plus raw and post-processed node counts. This distinguishes four cases:

- algorithm never ran
- algorithm ran but failed
- algorithm ran but was rejected by obstacle validation
- algorithm path was returned but post-processing reduced it

## Data Flow

Client:

1. Player clicks auto-complete.
2. Player chooses an algorithm in the context menu.
3. Client sends `RoadPlannerAutoCompleteRequestPacket`.

Server:

1. Packet handler resolves the algorithm.
2. `RoadPlannerPathfinderRunnerFactory` runs coarse and fine stages.
3. `RoadPlannerAutoCompleteService` merges manual nodes, post-processes the path, classifies segments, and returns a result packet.

Client:

1. Result packet applies nodes to the planner.
2. Existing preview and build expansion continue to operate on the returned nodes.

## Testing

Add focused unit tests for:

- `serverService` uses the selected algorithm for the coarse stage and Potential Field for the fine stage through a test seam.
- Fine-stage success returns the refined path.
- Fine-stage failure falls back to the coarse path.
- Coarse-stage failure returns an empty runner path so the existing auto-complete fallback behavior can still run.
- Diagnostics helpers calculate lateral deviation correctly for straight and curved paths.
- Auto-complete service reports runner path versus interpolation fallback without changing current fallback semantics.

Existing tests for auto-complete, obstacle masks, and pathfinding implementations should continue to pass.

## Risks

- Restoring corridor-constrained refinement may increase server CPU work per auto-complete request.
- If the coarse path is already too straight, the fine stage can only improve within the corridor. This is acceptable for this change because it restores historical behavior first.
- Existing post-processing can still simplify a path aggressively. Diagnostics will expose that case for a follow-up fix if needed.

## Acceptance Criteria

- Selecting Potential Field uses a two-stage route with a high precision Potential Field fine stage.
- Selecting Basic A*, Bidirectional A*, or Gradient Descent uses that algorithm in the coarse stage and Potential Field in the fine stage.
- Logs identify whether a route came from fine path, coarse fallback, or straight interpolation fallback.
- Build and relevant auto-complete/pathfinding tests pass.
