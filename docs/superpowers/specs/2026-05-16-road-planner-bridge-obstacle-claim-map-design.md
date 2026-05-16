# Road Planner Bridge, Obstacle, and Claim Map Repair Design

Date: 2026-05-16
Status: Approved for planning

## Scope

This design covers the current road planner and claim map regressions:

- Short, narrow water crossings are being built like tall pier bridges.
- Bridge auto-splitting can break one continuous bridge into road and bridge fragments when a tiny land strip or island appears inside the water crossing.
- Bridge previews can show bridge deck without usable entry and exit ramps.
- Road planning does not consistently avoid town and nation core blocks.
- Building avoidance is weak enough that generated roads can cut through or clip structure footprints.
- Road planner claim overlays draw per-chunk full borders instead of merged owner boundaries.
- Town and nation claim map overlays can be clipped by an invisible scroll/scissor boundary.
- Right-click editing for planned nodes/segments is no longer reachable because graph-edge context menus take priority.

The design keeps the user-facing bridge tool unified. Players choose "bridge", not "arch bridge", "small bridge", or "large bridge". The planner chooses the structure profile automatically from terrain and span geometry.

## Goals

- Make short crossings look like low bridges with a small rise, a deck, and a descent.
- Use tall pier bridges only for long or wide crossings.
- Treat short land interruptions inside a bridge span as part of that bridge span.
- Keep preview, confirmation, and actual build using the same bridge span classification.
- Prevent new roads from occupying protected core columns or structure footprints.
- Render claim overlays as merged regions, with only outer owner boundaries visible.
- Keep Town and Nation claim map overlays aligned with the map even when the page is scrolled.
- Restore planned-node right-click property editing without breaking existing built-road context menus.

## Non-Goals

- Do not add multiple bridge tools to the UI.
- Do not expose bridge profile selection to players in the context menu.
- Do not rewrite the whole road pathfinder.
- Do not change claim ownership rules or claim costs.
- Do not edit the current marketweb/SQLite packaging work.

## Bridge Profiles

The planner will classify bridge spans into internal profiles:

- `LOW_ARCH`: short water crossings. The shape is road, short upward ramp, low deck, short downward ramp, road. It has no large pier construction and should not lift the deck high above the nearby road.
- `LOW_BRIDGE`: medium crossings. It still uses modest entry and exit ramps and a low deck, but may hold a longer flat section than `LOW_ARCH`.
- `PIER_BRIDGE`: long or wide crossings. This is the tall bridge profile with higher deck clearance and grounded support piers.

The user-facing segment remains bridge. These profiles are internal construction decisions.

Default thresholds for planning:

- Water span up to 16 blocks: `LOW_ARCH`.
- Water span from 17 to 32 blocks: `LOW_BRIDGE`.
- Water span above 32 blocks, or total bridge span above the configured long-span threshold: `PIER_BRIDGE`.

Depth alone must not upgrade a short crossing to `PIER_BRIDGE`. Depth can influence clearance checks and pier/foundation sampling when a long bridge is already selected, but a narrow deep channel should still prefer `LOW_ARCH`.

## Bridge Span Normalization

Bridge splitting and normalization will treat a bridge as a continuous crossing, not as a strict alternating water/land sequence.

Rules:

- A short land interruption inside a bridge crossing is absorbed into the bridge if the dry run is 4 blocks or less.
- If a road segment is between bridge segments and is not the first or last segment of the overall bridge range, it is promoted back to bridge.
- Bridge ranges must include their land entry and land exit anchors so the builder has space to create ramps.
- If either bridge end lacks a valid land anchor, the planner reports a blocking issue instead of generating a broken bridge.

This prevents tiny islands or one-block land shelves from splitting a bridge deck into separate fragments.

## Bridge Geometry and Build Output

Bridge geometry should be generated from the normalized bridge range:

- `LOW_ARCH` uses a small deck height above water and nearby terrain. The ramp length is short but always present when height changes.
- `LOW_BRIDGE` uses a longer deck and moderate ramping.
- `PIER_BRIDGE` uses the existing large bridge shape, but pier foundations must sample actual solid ground or ocean floor instead of using a shallow fixed fallback such as water surface minus 8.

The implementation should align these paths:

- Client preview route expansion.
- Server build-step compilation.
- Ghost preview rendering.
- Actual construction queue output.

The intended result is that the same route that appears on the map or in ghost preview builds with the same bridge type and continuity.

## Core and Building Obstacles

Road planning will use a single obstacle mask for route generation and anchor selection.

Core exclusions:

- Town core and nation core columns use `RoadCoreExclusion.DEFAULT_RADIUS`, currently 3 blocks.
- The centerline and road footprint should not enter these columns.
- Existing road anchors may remain reusable only where the existing code already allows them, but new construction should not overwrite protected core columns.

Building exclusions:

- Each placed structure contributes its footprint plus a one-block margin as blocked columns.
- The pathfinder should treat these blocked columns as hard obstacles.
- Anchor selection should avoid placing approach anchors inside the footprint or its margin.
- Final build artifacts should still be filtered as a last safety net, but routing should avoid the obstacle before build output is created.

## Pathfinding Integration

The server-side auto-complete pathfinder currently uses terrain costs but does not consistently receive core/building exclusions. The design adds an obstacle-aware route runner for road planner auto-complete:

- Build an obstacle mask from `NationSavedData` for the active dimension.
- Include town cores, nation cores, and placed structures.
- Pass that mask into the pathfinding/cost layer.
- Reject or heavily penalize blocked nodes before they are added to the open set.

This is intentionally scoped. It does not replace the whole pathfinder; it adds a shared blocked-column predicate that the existing A* runner can call.

## Claim Overlay Borders

Claim overlays should render as merged owner regions:

- Adjacent chunks with the same owner must not draw their shared internal border.
- Outer boundaries still draw with the owner secondary color.
- Start and destination overlays in the road planner follow the same rule, using their role-specific colors.
- Selection/current-chunk markers remain separate overlays because they are interaction highlights, not ownership boundaries.

The existing Town and Nation claim pages already contain owner-neighbor checks. The road planner claim overlay renderer should use the same concept rather than drawing every chunk as an isolated rectangle.

## Claim Map Clipping

Town and Nation claim maps are rendered inside scrollable pages. The map must use one consistent screen-space rectangle for:

- Base tile rendering.
- Claim fill rendering.
- Merged owner boundaries.
- Current chunk and selected chunk markers.
- Area selection markers.
- Tooltip hit testing.
- Force-render request bounds.

The page scroll offset should be applied once at the page level, then all claim map drawing should operate in the same coordinate space. Any temporary scissor used by flag previews or other widgets must be fully disabled before map drawing continues. The map overlay must not be clipped by a stale scissor rectangle from another panel.

## Right-Click Planned Node Editing

Right-click behavior on the road planner map should use this priority:

1. If a context menu is already open, handle that menu first.
2. If right-click hits a planned node or planned segment, open a planned-route context menu.
3. If no planned route target is hit, try the built-road graph edge menu.
4. If neither is hit, close any stale context menu and return to normal map interaction.

The planned-route menu supports:

- Set as road.
- Set as bridge.
- Set as tunnel.

It should not expose built-road-only actions such as rename road, demolish edge, demolish branch, connect town, or view ledger.

The existing built-road graph edge menu remains unchanged for built roads.

## Error Handling

- If bridge normalization cannot find land anchors, the route remains unconfirmed and reports a bridge anchor issue.
- If obstacle masking blocks all possible routes, auto-complete returns a clear failure message instead of falling back to a path through cores or buildings.
- If claim map data is missing for a visible chunk, the base map can keep its fallback tile color, but claim overlay coordinates must remain stable.
- If right-click hits both a planned route and a built graph edge, planned route editing wins because the user is actively editing the draft.

## Tests

Add or update focused tests:

- `RoadPlannerWaterCrossingSplitterTest`: tiny land islands inside a water crossing remain bridge.
- `RoadPlannerBridgeSegmentNormalizerTest`: internal road nodes between bridge segments are promoted back to bridge.
- Bridge geometry tests: short crossings produce low arch ramps and no pier profile; long crossings produce pier bridge output.
- Build compiler tests: piers for pier bridges reach sampled bottom, while low arch bridges do not create tall pier columns.
- Pathfinding tests: core radius 3 and structure footprint plus margin are treated as blocked.
- Road planner context menu tests: right-clicking a planned node opens the planned-route property menu before the built-road graph menu.
- `RoadPlannerClaimOverlayRendererTest`: adjacent same-owner chunks suppress internal borders.
- Town and Nation claim map view tests: scrolled map hit testing and overlay rectangles share the same screen rect.

Minimum verification commands after implementation:

- `.\gradlew.bat compileJava`
- Targeted JUnit tests for the modified road planner and claim map classes
- `.\gradlew.bat build` if the touched surface includes build-step generation or packaging-sensitive paths

## Implementation Boundaries

Likely touched areas:

- `client/roadplanner` bridge splitting, segment normalization, context menu, and claim overlay renderer.
- `roadplanner/structure` bridge geometry and build-step compiler integration.
- `roadplanner/service` build control/compilation bridge output.
- `road/pathfinding` or a small adapter around it for blocked-column support.
- `nation/service` obstacle collection helpers where existing core and structure data already lives.
- `client/screen/town` and `client/screen/nation` claim map coordinate handling.

Avoid touching:

- Marketweb and SQLite packaging files currently dirty in the worktree.
- Upstream reference directories outside `sailboatmod`.
