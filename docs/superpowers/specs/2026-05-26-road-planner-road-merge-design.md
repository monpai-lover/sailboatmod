# Road Planner Existing Road Merge Design

Date: 2026-05-26

## Goal

Add road-planner support for linking a planned route into nearby existing built roads, so players can reuse completed road networks instead of building duplicate parallel approach segments. The feature must support same-nation roads by default and optionally support allied or trade-partner roads, while preventing bridge segments from becoming merge anchors.

## Current Context

The road planner currently edits a local `RoadPlannerLinePlan`, expands it through `RoadPlannerRouteExpander`, sends preview requests through `RoadPlannerPreviewRequestPacket`, and registers completed roads through `RoadPlannerBuiltRoadRegistry` as `RoadNetworkRecord` entries. Existing built roads are stored in `NationSavedData.getRoadNetworks()`.

Demolition already has a server-authoritative selection flow:

- `RoadPlannerRoadDemolitionService` filters roads by dimension and management permission.
- `OpenRoadDemolitionSelectionPacket` sends server-approved entries to the client.
- `RoadPlannerDemolitionSelectionScreen` lets the player choose one entry.

Diplomacy already models usable relationships through `NationDiplomacyStatus.ALLIED` and `NationDiplomacyStatus.TRADE`, and existing manual town-link logic allows same-nation, allied, and trade-linked towns.

## Requirements

- When a player places road-planner nodes near an already built road, the planner can link the new route to an existing road node.
- Nearest valid candidate is selected first, but the player can switch between candidates.
- Candidate detection runs when placing nodes and after auto-complete, and final validation runs again before preview/build.
- Same-nation manageable roads are valid by default.
- Allied and trade-partner roads are valid only when the player enables the "other road node snapping" option.
- Other-nation linkable roads are drawn on the planning minimap with one shared "other road" color.
- Bridge segments cannot be linked into. This applies both to the current planned segment and to the candidate anchor on an existing built road.
- Building the new route must not duplicate existing-road segments. Only the connector from the planned route into the selected anchor is built.
- Server validation is authoritative; the client only displays and requests candidate choices.

## Non-Goals

- Do not redesign global road-network persistence.
- Do not rewrite automatic pathfinding.
- Do not add full per-segment metadata migration to all existing `RoadNetworkRecord` entries in this feature.
- Do not allow enemy, neutral, unrelated, or cross-dimension road linking.
- Do not allow bridge-to-road or road-to-bridge snap anchors.

## Approach

Use a server-side candidate query service with a small client-side selection state. The client can request nearby merge candidates for the current route endpoint, display the candidates on the minimap, and submit the selected candidate as part of the preview/build request. The server recomputes and validates the selected candidate before using it.

This keeps trust boundaries correct and follows the existing demolition-selection pattern without making the client responsible for permissions or diplomacy.

## Data Model

Add a lightweight candidate record for network packets and client state:

- `roadId`
- `anchorPos`
- `pathIndex`
- `distanceBlocks`
- `sourceName`
- `targetName`
- `ownerNationId`
- `relationship`
- `scope`

Use these enums:

- `RoadPlannerMergeScope`: `OWN_NATION`, `ALLIED_OR_TRADE`, `DISABLED`
- `RoadPlannerMergeRelationship`: `OWN`, `ALLIED`, `TRADE`

The candidate record is not persisted. Completed roads continue to persist as `RoadNetworkRecord`.

## Candidate Search

Add a server-side service named `RoadPlannerRoadMergeService` that accepts:

- player
- dimension
- probe position
- radius
- merge scope
- current planned segment type

The service filters `NationSavedData.getRoadNetworks()` by:

- same dimension
- valid path with at least two nodes
- relationship allowed by selected scope
- player can manage same-nation road, or road belongs to an allied/trade nation when the scope allows external links
- nearest existing `RoadNetworkRecord.path()` node within the snap radius
- candidate anchor is not classified as bridge
- current planned segment is not a bridge segment

Candidate sorting:

1. shortest distance to probe
2. own nation before allied/trade
3. lower road id for stable order

The snap radius is 12 blocks by default. The returned list is capped at 16 candidates.

The first implementation does not create projected split points in the middle of an existing segment. The connector must end at a real persisted road path node. This keeps route adjacency simple and prevents modifying the reused road.

## Relationship Rules

Own-nation roads:

- Allowed when the player can manage the road using the same permission scope as road demolition.
- OP remains allowed.

Allied/trade roads:

- Allowed only when the client requests `ALLIED_OR_TRADE`.
- `NationSavedData.getDiplomacy(playerNationId, road.nationId())` must resolve to `ALLIED` or `TRADE`.
- The feature does not grant demolition or ownership rights over the other road. It only allows a connector road to end at that road node.

Enemy, neutral, no relation, blank nation, and cross-dimension roads are rejected.

## Bridge Exclusion

Bridge linking is blocked in two places.

For the current planned segment:

- If the active tool or final segment type is `BRIDGE_SMALL`, `BRIDGE_MAJOR`, or water-crossing bridge output, do not request merge candidates and do not apply selected merge candidates.

For existing candidate anchors:

- Prefer a reliable segment-type source if one exists in generated preview/build metadata.
- Since `RoadNetworkRecord` currently stores only a path, fallback detection should reject anchors that look like bridge surfaces: water below, unsupported/elevated deck over air or water, or bridge-specific support/deck blocks around the anchor.
- If bridge classification is uncertain, reject the candidate. False negatives are safer than allowing road planners to attach to bridge decks.

This avoids creating broken ramp, floating, or duplicate bridge transitions.

## Client Flow

`RoadPlannerScreen` owns the selection state:

- current merge scope
- list of current candidates
- selected candidate index
- selected candidate id or anchor
- whether a selected candidate was explicitly accepted

Triggers:

- after placing a non-bridge node
- after auto-complete applies a non-bridge final segment
- before opening the build-settings screen or submitting preview

When candidates arrive:

- nearest candidate becomes selected by default
- status line shows the selected road and distance
- player can cycle candidates
- player can disable snapping for the current route

If the selected candidate becomes invalid before preview, the server rejects it and the client clears the selection.

## UI

Add a compact toggle to the road-planner toolbar or settings strip:

- `Own roads`
- `Own + ally/trade`
- `Off`

Behavior:

- Default is `Own roads`.
- Switching to `Own + ally/trade` enables automatic snapping to allied and trade roads.
- `Off` disables candidate requests and clears selected candidates.

Add a compact action for candidate switching:

- If multiple candidates exist, a "next merge point" action cycles through them.
- A cancel action clears the selected candidate while leaving route nodes intact.

Minimap rendering:

- Own built roads keep the existing built-road color.
- Allied and trade candidate roads use one shared "other road" color.
- Selected anchor uses a stronger highlight marker.
- Rejected bridge anchors are not drawn as link candidates.

Road overlay data is synced separately from terrain pixels. The client requests visible built-road overlays for the current planner viewport, and the server returns only permission/diplomacy-allowed road path samples:

- own manageable roads when scope is `OWN_NATION` or `ALLIED_OR_TRADE`
- own plus allied/trade roads when scope is `ALLIED_OR_TRADE`
- no road overlays when scope is `DISABLED`

This avoids baking relationship-dependent road colors into the terrain tile cache and keeps LOD cache invalidation separate from the new overlay feature.

## Preview And Build Flow

Preview requests should include the selected merge candidate identity, not just a client-edited endpoint. The server then:

1. Recomputes candidates near the submitted endpoint.
2. Confirms the selected `roadId` and `pathIndex` still match a valid candidate.
3. Snaps the route endpoint to the validated anchor.
4. Expands and previews/builds only the new connector path.

The actual `BuildStep` list must not include blocks from the reused existing road beyond the anchor. The selected existing road stays untouched.

On build completion, `RoadPlannerBuiltRoadRegistry` registers the new road with an endpoint anchor that references the merge target. Use these anchor string forms:

- `planner:start:x,y,z`
- `roadnode:<roadId>:<pathIndex>`

This gives route resolvers enough information to treat the connector and existing road as connected while preserving old records.

## Routing Impact

Existing routing helpers already use `RoadNetworkRecord.path()` to build adjacency. The implementation must ensure that the new connector's final path node exactly equals the selected existing-road path node. That shared `BlockPos` is the primary compatibility mechanism.

If a later route resolver needs stronger semantics, it can read the `roadnode:<roadId>:<pathIndex>` anchor, but this feature should not depend on a large routing rewrite.

## Packets

Add packet flow similar to demolition selection:

- client to server: merge candidate request
- server to client: merge candidate list
- client to server: road overlay request for the current minimap viewport
- server to client: filtered road overlay paths with relationship class
- preview request: selected merge candidate, merge scope

The packet records must cap list sizes and string lengths similarly to existing road-planner packet codecs.

## Error Handling

Server rejection cases:

- no player
- no nation
- no permission or invalid diplomacy
- road not found
- wrong dimension
- candidate too far from endpoint
- candidate classified as bridge
- current segment is bridge

Client behavior:

- show a short status message
- clear the invalid candidate
- keep the player's planned route nodes unchanged
- allow preview/build without merge if the base route is otherwise valid

## Testing

Unit tests should cover:

- own-nation candidates are returned and sorted by distance
- allied/trade candidates are hidden in `OWN_NATION` mode and visible in `ALLIED_OR_TRADE` mode
- enemy/neutral/no-relation roads are rejected
- cross-dimension roads are rejected
- bridge current segment suppresses candidate search
- bridge-looking existing anchors are rejected
- candidate cycling keeps a stable selected candidate
- preview/build validation rejects stale or tampered selected candidates
- snapped connector path ends exactly on the existing road path node
- completed road registration stores a shared endpoint and does not duplicate existing-road blocks
- overlay sync returns own roads in own-only mode and adds allied/trade roads only when external snapping is enabled
- overlay sync does not alter or invalidate terrain tile cache entries

Manual test cases:

- Plan near a same-nation road and verify nearest auto-selection.
- Switch between two nearby same-nation candidates.
- Enable allied/trade snapping and verify other roads appear in a distinct color.
- Disable snapping and verify no candidates appear.
- Try to snap from a bridge tool segment and verify no candidate appears.
- Try to snap onto an existing bridge deck and verify it is not selectable.
- Build a connector and verify carriage/trade routing can continue through the existing road network.

## Implementation Boundaries

Implement this feature in small units:

- server candidate service
- packet DTOs and registration
- client selection state and toolbar actions
- minimap candidate rendering
- preview/build validation and endpoint snapping
- completed-road anchor registration
- tests

Avoid touching unrelated pathfinding, bridge ramp generation, construction animation, or minimap LOD cache behavior.
