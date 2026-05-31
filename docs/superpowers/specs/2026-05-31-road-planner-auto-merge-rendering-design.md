# Road Planner Auto Merge and RoadWeaver-Style Rendering Design

Date: 2026-05-31

## Context

The current road merge tool is usable but too manual. Players must repeatedly select merge nodes and confirm merge even when an existing connected road network already reaches the destination. The map overlay also draws roads as dense simple sampled lines with many node boxes, which makes reused roads hard to read. A long-standing minimap issue still appears after road construction: newly built road chunks can refresh into black background outside the road body.

RoadWeaver provides two useful reference patterns:

- Completed roads are treated as a graph with adjacency and reachability checks.
- Map rendering separates connection/status lines from detailed road polylines, applies LOD simplification, and uses solid/dashed line styles for state.

This design applies those patterns to the existing Sailboat road planner without replacing the whole planner pipeline.

## Confirmed Requirements

- Automatic merge must only accept a fully reachable existing-road path from the current planned route toward the destination.
- If multiple reachable paths exist, prefer the one whose first existing-road direction best follows the current planned route direction. Use connector distance and path length as tie-breakers.
- Switching to the merge tool should auto-scan and auto-select the best route.
- Players can click an existing road to override the automatic entry preference and re-run route resolution from that entry.
- Confirming merge highlights only the reused existing-road portion, not the newly built connector.
- If no fully reachable existing-road path exists, keep the existing manual multi-merge workflow available. Show a confirmation dialog with "continue manual merge" and "cancel".
- Rendering should move toward RoadWeaver's map style, including LOD polylines and state-aware line styles.
- The road build completion refresh must refresh the road footprint chunks while preserving valid existing map tile pixels until replacement tile data is safe.

## Non-Goals

- Do not remove the current manual merge flow.
- Do not require all old legacy roads to be migrated before the feature works.
- Do not rewrite the whole minimap/tile system.
- Do not make automatic merge build or modify existing roads. It only reuses built roads and builds the connector.

## Architecture

### Server-Side Auto Merge Resolver

Add a server-side resolver near the existing merge services, conceptually `RoadPlannerAutoMergeRouteService`.

Inputs:

- session id
- dimension id
- current route nodes
- current route segment types
- current endpoint
- destination town position
- merge scope
- optional manual entry preference from a clicked road or clicked path index

Outputs:

- result status: found, not found, invalid request, or scope blocked
- merge selection: road id, path index, anchor position, scope
- reused route display path from anchor toward destination
- one or more reused road spans when the path crosses multiple graph edges
- route edge ids and display labels for rendering/tooltips
- reason message for failure

The resolver should build a graph from `RoadGraphRepository` using only built graph edges in the requested dimension. Legacy `RoadNetworkRecord` overlays can be considered only when they can provide enough endpoint/path data to prove reachability. If legacy data cannot prove a full connected route, it remains available through manual merge fallback.

### Graph Search and Ranking

The graph search follows RoadWeaver's completed-highway reachability pattern, but uses weighted path search instead of only ring/path existence.

Steps:

1. Find candidate entry points near the current endpoint, respecting merge scope and excluding bridge-like current segments.
2. Find destination-side graph nodes or edge points near the destination town position.
3. Run shortest-path search between entry candidates and destination candidates.
4. Rank successful routes:
   - lowest first-turn angle from the current planned direction
   - shorter connector distance from current endpoint to anchor
   - shorter reused-road path length
   - stable road id/path index order for deterministic ties

If a manual entry preference is supplied, candidates on that road/edge are ranked first, but the route must still reach the destination.

### Client State and Interaction

Add a dedicated automatic merge state to `RoadPlannerScreen`, separate from `selectedMergeOverlay`:

- pending request id
- automatic result, if any
- selected result source: automatic or manual override
- reusable display path for highlight
- reusable shared spans for preview/submission
- failure status requiring manual fallback confirmation

Interaction flow:

1. Player switches to merge tool.
2. Client sends auto-merge route request.
3. On success, client selects the returned merge selection and displays the full existing-road reuse path.
4. Player can confirm merge immediately.
5. Player can click a road to re-run resolution using that road/node as preferred entry.
6. On failure, show a confirmation dialog:
   - continue manual merge: restore the existing candidate/node selection workflow
   - cancel: clear auto-merge state and leave the route unchanged

The current manual candidate cycling and per-node confirmation stays available after the fallback choice.

## Submission and Shared Spans

`previewSubmission()` should use the automatic result when present:

- build nodes include only the connector/new road section up to the merge anchor
- logical nodes include the connector plus the existing-road display path to destination
- shared spans include all reused existing-road spans returned by the resolver
- `RoadPlannerMergeSelection` remains compatible for the first anchor, but shared spans become the source of truth for multi-edge reuse

For a single-road reuse path, this is compatible with the current `END_MERGE` span behavior. For multi-edge reuse, submission must preserve multiple end-merge spans instead of compressing the result into one road id.

## Rendering Design

Move the route overlay drawing toward a RoadWeaver-style layered renderer. The implementation can start with small classes under the client road planner package rather than keeping all drawing in `RoadPlannerScreen`.

Layers:

1. Map tile base.
2. Existing road network layer:
   - draw visible road display paths using LOD simplification
   - draw fewer points at lower zoom
   - use relationship/status color as the base
3. Candidate/status layer:
   - hovered roads use a wider highlight
   - automatic reachable route uses selected solid stroke
   - manual candidate routes can use dashed stroke
4. Planned route layer:
   - newly planned connector remains the normal planned route style
   - bridge/water segment styles remain distinct
5. Node/anchor layer:
   - draw selected anchor, destination-side reused endpoint, hover node, and selected route nodes
   - stop drawing every existing-road node by default at normal zoom

Rendering utilities should adapt RoadWeaver's useful pieces directly where compatible:

- thick line drawing
- thick dashed line drawing
- clipped point drawing
- polyline LOD simplification based on view scale/grid step

The current hit tester can remain, but it should use the unsimplified or sufficiently detailed path data so LOD rendering does not make interaction less accurate.

## Map Refresh and Black-Tile Guard

Road construction completion should continue enqueueing refresh by actual road footprint chunks:

- build steps
- graph placements
- owned block positions when available
- center path only as a final fallback

The tile client/render side must not replace a valid old tile with an unsafe black or empty tile. A refreshed tile should only replace the current tile when it contains valid terrain/background data for the requested region. If refresh data is incomplete, keep the old tile and retry/request again. This matches the expected operation: after a road is built, refresh the chunks the road passes through, but never blank the non-road background while waiting for safe tile data.

## Network Packets

Add request/sync packets for auto merge resolution rather than overloading the existing candidate packet:

- request: current route state, destination, scope, optional preferred road id/path index
- response: status, merge selection, display path, shared spans, labels, request id

Keep `RoadPlannerMergeCandidateRequestPacket` and `RoadPlannerRoadOverlaySyncPacket` for the manual fallback and visual road overlay data.

## Error Handling

- Stale responses are ignored by request id and session id.
- If merge scope is disabled, do not auto-request.
- If the current segment is bridge-like, auto merge is skipped and the existing "bridge segment cannot merge" rule remains.
- If the destination has no nearby built graph node, return not found and offer manual fallback.
- If graph data changes before preview/submit, server-side validation should snap or reject the auto merge result consistently with current merge validation.

## Testing

Unit and behavior tests should cover:

- auto resolver finds a fully connected graph route to destination
- resolver rejects a road that gets closer but cannot reach destination
- direction-priority ranking beats shortest path when configured by user choice
- manual road click preference re-runs resolution and overrides the automatic entry when reachable
- auto failure exposes the manual fallback confirmation state
- confirming an auto merge appends only existing-road logical nodes and leaves build nodes as connector-only
- multi-edge reuse emits multiple shared spans
- overlay renderer state marks only existing reused roads as selected, not the connector
- LOD simplification keeps endpoints and enough path shape for rendering
- black/empty refreshed map tile data does not replace a valid existing tile

Minimum validation after implementation:

- `.\gradlew.bat compileJava`
- focused JUnit tests for resolver, screen behavior, packet round trips, rendering state, and tile replacement guard
- in-game check: build a road, open planner, confirm route chunks refresh without black background

## Open Decisions Fixed by This Spec

- Auto merge requires full existing-road reachability to destination.
- Ranking prefers natural continuation direction over pure shortest path.
- Confirmation highlights only the reused existing-road portion.
- Default behavior auto-selects, road click manually overrides.
- No auto route found shows a confirmation dialog and can fall back to the existing manual multi-merge flow.
