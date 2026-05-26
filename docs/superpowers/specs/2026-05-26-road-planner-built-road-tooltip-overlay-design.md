# Road Planner Built Road Tooltip Overlay Design

Date: 2026-05-26

## Goal

Show built roads on the road planner minimap as an information layer, and show a tooltip when the cursor hovers a built road. The tooltip should identify the road, its route name, length, creator, and creation time while preserving the existing server-authoritative road merge and bridge exclusion rules.

## Current Context

The road planner already has a built-road overlay channel:

- `RoadPlannerRoadOverlayRequestPacket` asks the server for roads visible in the current minimap region.
- `RoadPlannerRoadOverlaySyncPacket` returns capped road entries with `roadId`, relationship, and path points.
- `RoadPlannerScreen.renderSyncedRoadOverlays` draws those paths before the current planned route.
- `RoadPlannerRoadMergeService.visibleRoadOverlays` filters roads by dimension, nation relationship, permissions, and region.

The current overlay data is enough to draw roads, but it does not include display metadata. `RoadNetworkRecord` stores `roadId`, nation/town scope, dimension, two endpoint structure ids, path, `updatedAt`, and `sourceType`. It does not yet store creator identity or a separate creation timestamp.

## Requirements

- The road planner minimap can display already built roads in the visible region.
- Own roads and externally visible roads use distinct colors.
- Hovering near a built road line shows a tooltip.
- Tooltip fields:
  - road name, preferably `A - B`
  - length in blocks
  - creator
  - creation time
- New roads should persist creator UUID, creator name, and created-at time.
- Existing roads without creator metadata should remain valid and display fallback values.
- Bridge roads may be shown for context, but bridge nodes and bridge segments remain invalid merge anchors.
- Tooltip data is server-provided; the client only performs local hover hit testing against already-synced overlay paths.
- The terrain tile cache and LOD cache must not store relationship-dependent road metadata.

## Non-Goals

- Do not rewrite road merge candidate selection.
- Do not change automatic pathfinding.
- Do not make tooltip hover request the server every frame.
- Do not migrate all old roads through a destructive save rewrite.
- Do not add editing controls to the tooltip in this feature.
- Do not make bridge decks valid merge targets.

## Recommended Approach

Extend the existing road overlay packet and renderer. The server already knows which roads are visible and allowed for the player, so it should also attach display metadata to each overlay entry. The client renders the line, hit-tests the mouse against the synced line segments, and draws a small tooltip for the closest hit.

This avoids a second asynchronous "hover details" request path and keeps the map tile cache independent from dynamic road ownership and diplomacy data.

## Overlay Visibility

Built-road display should be treated as an information layer, separate from whether automatic merge snapping is enabled.

Use these practical rules:

- When the planner is open, request road overlays for the current minimap viewport.
- Own manageable roads are visible by default.
- Allied and trade-partner roads are visible when the planner is in the existing `ALLIED_OR_TRADE` road-linking scope.
- If the user disables road overlays explicitly in a future UI toggle, no overlay request is sent.
- If snapping is disabled but overlays are still enabled, roads can still be displayed and hovered, but no merge candidate is selected.

This keeps visual awareness useful without making "show roads" imply "snap to roads."

## Server Data Model

Extend `RoadNetworkRecord` with optional creation metadata:

- `creatorUuid`
- `creatorName`
- `createdAt`

Persistence:

- Save fields as `CreatorUuid`, `CreatorName`, and `CreatedAt`.
- Loading old records:
  - missing `creatorUuid` becomes empty string
  - missing `creatorName` becomes empty string
  - missing `createdAt` falls back to existing `UpdatedAt`
  - expose whether the record is legacy-derived if needed for tooltip text

New planner-built roads should populate the fields in `RoadPlannerBuiltRoadRegistry.register` from `CompletedRoadBuild.ownerId()`. If the player is online, store the current game profile name. If the player is offline or unavailable, store the UUID and an empty creator name.

Existing manual or auto roads created by older code remain readable without migration.

## Road Display Metadata

Add a server-side overlay DTO derived from `RoadNetworkRecord`:

- `roadId`
- `relationship`
- `path`
- `displayName`
- `lengthBlocks`
- `creatorName`
- `creatorUuid`
- `createdAt`
- `legacyMetadata`

`displayName` resolution:

1. If `structureAId` and `structureBId` resolve to town names, use `Town A - Town B`.
2. If one endpoint is a `roadnode:<roadId>:<index>` anchor, label it as a road connection.
3. If endpoints are planner anchors, use compact coordinate labels or `Planner Road`.
4. If no useful endpoint names exist, fall back to the road id.

`lengthBlocks` should be computed from the path as horizontal 3D segment length rounded to the nearest whole block. It should not use only node count.

`creatorName`:

- Prefer the persisted creator name.
- If missing but UUID is online, the server may resolve the current player name.
- Otherwise use an empty string and let the client display `Unknown`.

`createdAt`:

- Prefer persisted `createdAt`.
- For legacy roads, use `updatedAt` as fallback and set `legacyMetadata = true`.

## Packet Changes

Extend `RoadPlannerRoadOverlaySyncPacket.Entry` with metadata fields:

- `displayName`
- `lengthBlocks`
- `creatorName`
- `creatorUuid`
- `createdAt`
- `legacyMetadata`

Keep existing bounds:

- cap roads to the current maximum
- cap path points to the current maximum
- cap strings through `RoadPlannerPacketCodec`

Round-trip tests should cover legacy fallback fields and string/list caps.

## Client Rendering

Road drawing remains in `RoadPlannerScreen.renderSyncedRoadOverlays`.

Use stable colors:

- own roads: current own-road overlay color
- allied or trade roads: current shared-road overlay color
- selected merge anchor: existing stronger marker
- hover road: draw one extra highlight pass over the hovered road path

The tooltip should render after map overlays and before or with existing GUI foreground content so it appears above the minimap.

Tooltip content:

- first line: road display name
- second line: `Length: N blocks`
- third line: `Creator: name` or `Creator: Unknown`
- fourth line: `Created: formatted time`
- optional suffix for legacy time: `Created: old road data`

Use existing Minecraft GUI tooltip rendering where possible, rather than a custom large panel.

## Hover Hit Testing

Add a small client helper for map-space hit testing:

- Input: mouse X/Y, map rect, `RoadPlannerMapView`, overlay entries.
- Convert each path segment to screen coordinates.
- Compute point-to-segment distance in screen pixels.
- Select the closest segment within a small threshold, for example 5 pixels.
- Ignore entries with fewer than two path points.
- Return the closest overlay entry and segment index.

This should be deterministic and testable without Minecraft rendering.

If multiple roads overlap, choose:

1. selected merge road if present
2. closest screen distance
3. own relationship before external relationship
4. stable road id order

## Interaction With Merge Snapping

The tooltip overlay does not change merge validation.

Rules that remain unchanged:

- candidate search is server-authoritative
- current bridge segment cannot merge
- existing bridge-like anchors cannot merge
- selected merge anchor must be revalidated before preview/build
- stale or tampered selected anchors are cleared

The tooltip may show bridge roads for context, but bridge anchors are not highlighted as selectable merge anchors.

## Error Handling

Server-side:

- If player, level, nation, dimension, or region is invalid, return an empty overlay list.
- If metadata cannot be resolved, return safe fallback strings.
- If a path is too long, send only capped points as today.

Client-side:

- Missing or blank names render as fallback text.
- Missing creator renders `Unknown`.
- Zero or negative timestamps render `Unknown`.
- Empty path entries are skipped for drawing and hit testing.
- Tooltip is hidden when the mouse leaves the minimap.

## Testing

Unit tests:

- `RoadNetworkRecord` saves and loads new creator metadata.
- Old NBT without creator fields loads and falls back to updated time.
- `RoadPlannerBuiltRoadRegistry` stores creator UUID/name/time for new planner-built roads.
- Overlay service resolves `A - B` names from town endpoints.
- Overlay service computes length from path segment distances.
- Overlay sync packet round-trips new metadata fields and caps strings/lists.
- Client hover hit tester selects the nearest road segment.
- Hover hit tester ignores roads outside threshold.
- Tie-breaking prefers selected merge road, then closest, then own roads, then stable id.

Manual tests:

- Open the road planner near existing own roads and verify they draw on the minimap.
- Hover an own road and verify tooltip name, length, creator, and time.
- Enable allied/trade scope and verify partner roads draw in the other-road color.
- Disable snapping and verify roads can still display while no merge candidate is selected.
- Hover old roads and verify fallback creator/time text is acceptable.
- Hover near overlapping roads and verify the nearest or selected road is shown.
- Verify bridge roads display for context but still cannot be selected as merge anchors.

## Implementation Boundaries

Keep the work in focused slices:

- road record metadata persistence
- overlay service metadata projection
- overlay packet expansion
- client hover hit testing
- minimap highlight and tooltip rendering
- focused tests

Do not touch road pathfinding, bridge ramp construction, construction placement, terrain tile rendering, or map LOD invalidation for this feature.
