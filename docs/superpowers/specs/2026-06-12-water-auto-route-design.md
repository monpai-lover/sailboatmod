# Water Auto Route Design

Date: 2026-06-12

## Goal

Rebuild water auto-route generation so dock-to-dock routes are safe, permission-aware, and resistant to server stalls. A generated route must run from a valid water berth inside the source dock zone to a valid water berth inside the target dock zone. It must not use the dock block position as a water waypoint when the dock core is on shore.

The system must allow long-distance route discovery by loading unknown chunks, but only through a server tick task queue with hard budgets. It must not perform full long-distance water A* synchronously inside a network packet handler.

## Decisions

- Use a new mature water route stack instead of expanding the legacy `AutoRouteService.findWaterRoute` implementation.
- Both source and target docks must have a nonblank `townId` and `nationId`.
- Same-nation docks may create routes.
- Cross-nation routes are allowed only when the two nations are `ALLIED` or `TRADE`.
- Docks without town binding are excluded from the automatic route network.
- Main waterway pathing is strict: only `FluidTags.WATER` is navigable.
- Shore access is handled by resolving water berths inside each dock zone, not by allowing land waypoints.
- Route creation submits a server-side tick-budgeted task. The route is written only after the task succeeds.
- The first UI version stays lightweight: the dock target list filters by permission and berth availability, while full route calculation starts only after the player clicks create.

## Current Problems

The current `AutoRouteService` uses a simple synchronous 2D A*:

- It steps by 16 blocks and writes all waypoints at fixed `Y=63`.
- It treats any non-empty fluid as water instead of checking `FluidTags.WATER`.
- It does not reject land; it only adds a high cost, so routes can cross land.
- It does not check boat width, clearance, shore obstacles, or dock berth validity.
- It has a hard `1000` step cap and no structured failure reason.
- It runs during route creation instead of being budgeted across server ticks.

The post-station land route code already has a stronger route-resolution shape. Water routing should get its own equivalent service rather than sharing land-road semantics.

## Architecture

Add a dedicated water auto-route layer:

- `WaterAutoRouteService`: public server entry point for candidate checks, task submission, and successful route application.
- `WaterRoutePermissionService`: evaluates dock town/nation binding and diplomacy rules.
- `DockBerthResolver`: finds usable water berths inside a dock's configured zone.
- `WaterRouteTaskService`: owns pending water route tasks and advances them from the server tick.
- `WaterRoutePathfinder`: performs budgeted water A* over sampled water columns.
- `WaterColumnSampler`: samples water surface, width, clearance, and obstruction information.
- `WaterRouteResolution`: structured result object with success, path, berths, and failure reason.

Keep `RouteDefinition` and `DockBlockEntity.setRoutes(...)` as the storage and runtime route contract. Existing manual route books and old saved routes remain compatible.

`AutoRouteService` should become a compatibility facade. Existing call sites can migrate to `WaterAutoRouteService`, while old direct methods are marked as legacy and are no longer the normal route creation path.

## Permission Rules

For automatic water routes, both docks must satisfy:

- Source and target are normal port docks, not post stations.
- Source and target have nonblank `townId`.
- Source and target have nonblank `nationId`.
- If `nationId` matches, route creation is allowed.
- If `nationId` differs, `NationSavedData.getDiplomacy(sourceNation, targetNation)` must be `ALLIED` or `TRADE`.
- Neutral, enemy, missing diplomacy record, missing town, or missing nation all fail.

The server rechecks these rules when the player creates the route. The client candidate list is advisory only.

## Dock Berths

`DockBerthResolver` uses the existing dock zone:

- `DockBlockEntity.getZoneMinX()`
- `getZoneMaxX()`
- `getZoneMinZ()`
- `getZoneMaxZ()`
- `DockBlockEntity.isInsideDockZone(Vec3)`

It searches inside the configured zone for a water berth:

- The berth must stand on or above `FluidTags.WATER`.
- The berth must be far enough from the dock core to avoid collision with the block.
- The berth must have enough horizontal water footprint for a sailboat.
- The berth must have enough vertical clearance for the boat.
- Occupied berths should be avoided when possible.

The route path starts at the source berth and ends at the target berth. The dock block position is used for ownership, display, and storage only.

## Water Pathfinder

The water pathfinder operates on sampled water-surface nodes:

- Node identity is `(x, z, waterSurfaceY)`.
- Step size should start at 8 or 12 blocks and be configurable later.
- It expands 8 directions.
- Diagonal movement must sample the diagonal corridor so a route cannot cut through a land corner.
- Goal condition is reaching the target berth radius, not the target dock core.
- Main route nodes must be valid water columns.

`WaterColumnSampler` must reject:

- Land columns.
- Lava or any non-water fluid.
- Columns where the boat footprint is not water.
- Columns with insufficient overhead clearance.
- Columns outside loaded/allowed search bounds.

Cost should prefer robust routes:

- Base distance cost.
- Higher cost near shore to reduce beaching.
- Higher cost for low but passable clearance.
- Higher cost for sharp turns.
- Small goal bias near the target berth.

After a path is found:

- Remove redundant collinear points.
- Smooth straight segments only when the whole segment samples as passable water.
- Preserve source and target berths.
- Use sampled water-surface Y values instead of fixed `Y=63`.

## Task Budgeting

`WaterRouteTaskService` runs on server tick and owns all pending route searches.

Budgets must be explicit:

- Maximum tasks advanced per server tick.
- Maximum path nodes expanded per task per tick.
- Maximum chunk loads per task per tick.
- Maximum total chunk loads per task.
- Maximum total expanded nodes per task.
- Maximum horizontal search radius.
- Maximum task lifetime in ticks.

If a task hits a per-tick budget, it pauses until the next tick. If it hits a total budget or timeout, it fails with a structured reason. No partial route is saved.

Chunk loading is allowed for automatic route discovery, but only through these task budgets. Packet handlers must not force-load all candidate route chunks.

## Network Flow

Target list flow:

1. Client clicks the dock auto-route button.
2. `RequestAutoRouteDocksPacket` reaches the server.
3. Server scans `DockRegistry`.
4. Server filters out the source dock, post stations, missing block entities, invalid ownership data, failed diplomacy, and docks without a usable berth.
5. Server returns available target docks to the existing selection screen.

Create flow:

1. Client chooses a target and sends `CreateAutoRoutePacket`.
2. Server rechecks source and target docks.
3. Server rechecks permission and berth resolution.
4. Server submits a `WaterRouteTask`.
5. Player immediately receives a "route calculation started" message.
6. `WaterRouteTaskService` advances the task over server ticks.
7. On success, the source dock receives the generated `RouteDefinition`.
8. On failure, the requester receives a localized failure message.

Duplicate source-target requests while a task is pending should return `already_pending`.

## Route Output

A successful generated route should include:

- Name like `Auto Water: <source dock> -> <target dock>`.
- Waypoints from source berth to target berth.
- `authorName` and `authorUuid` from the requesting player when available.
- Correct route length.
- `startDockName` and `endDockName` from the two dock block entities.

The generated route is appended to the source dock's route list and selected. Existing stored routes remain untouched.

## Failure Reasons

Use stable failure reasons for messages and tests:

- `MISSING_SOURCE_DOCK`
- `MISSING_TARGET_DOCK`
- `INVALID_TERMINAL_KIND`
- `MISSING_TOWN`
- `MISSING_NATION`
- `NO_PERMISSION`
- `NO_SOURCE_BERTH`
- `NO_TARGET_BERTH`
- `RANGE_EXCEEDED`
- `NO_WATER_PATH`
- `CHUNK_BUDGET_EXCEEDED`
- `NODE_BUDGET_EXCEEDED`
- `TIMEOUT`
- `ALREADY_PENDING`

Each reason should have `zh_cn` and `en_us` localization entries. The player must get a clear message instead of a generic create failure.

## UI

Keep the first implementation small:

- Existing dock auto-route selection screen remains.
- Candidate list shows only targets that pass permission and berth checks.
- Clicking create closes or disables the action and tells the player the route is calculating.
- Completion and failure are sent by system message.

Do not redesign the full dock UI in this feature. A future UI pass can add pending route status, progress, and visible failure states in the list.

## Compatibility

- Existing manual route books keep working.
- Existing saved dock routes keep working.
- Existing sailboat autopilot keeps consuming `RouteDefinition`.
- Existing market and dispatch behavior can use newly generated routes without a new route model.
- Post station and carriage land routing stay separate.
- No cross-dimension water routes in this version.

If old code calls `AutoRouteService.canCreateAutoRoute`, it should delegate to the new permission and berth validation where practical. If old code calls synchronous `findWaterRoute`, keep the method only as legacy compatibility and avoid using it from packets.

## Testing

Permission tests:

- Missing source or target `townId` fails.
- Missing source or target `nationId` fails.
- Same nation succeeds.
- Allied nation succeeds.
- Trade nation succeeds.
- Neutral, enemy, and missing diplomacy fail.

Berth tests:

- Dock core on shore but zone contains water berth succeeds.
- Zone with no water berth fails.
- Berth avoids the dock core exclusion radius.
- Berth rejects non-water fluid.
- Berth rejects insufficient clearance.

Pathfinder tests:

- Continuous water finds a path.
- Land barrier blocks the path.
- Lava or non-water fluid is rejected.
- Low bridge or blocked clearance rejects a column.
- Diagonal movement cannot cut through land corners.
- Smoothing preserves passable water and keeps both berths.

Task service tests:

- Per-tick node budget pauses work.
- Per-tick chunk budget pauses work.
- Total chunk budget failure returns `CHUNK_BUDGET_EXCEEDED`.
- Total node budget failure returns `NODE_BUDGET_EXCEEDED`.
- Timeout returns `TIMEOUT`.
- Duplicate source-target pending request returns `ALREADY_PENDING`.
- Successful task appends exactly one route to the source dock.

Packet tests:

- `RequestAutoRouteDocksPacket` excludes missing town/nation targets.
- `RequestAutoRouteDocksPacket` excludes no-berth targets.
- `CreateAutoRoutePacket` submits a task instead of synchronously pathing.
- `CreateAutoRoutePacket` reports structured failure reasons.

Regression tests:

- Existing route storage still round-trips through `RouteNbtUtil`.
- Existing sailboat autopilot route catalog accepts generated water route waypoints.
- Existing post station route tests continue to pass.

## Manual Verification

Manual in-game checks:

1. Put dock cores on shore with water inside their dock zones. Confirm auto-route candidates appear.
2. Put a dock with no water in its zone. Confirm it is excluded.
3. Create a same-nation route over open water. Confirm route is generated after a short calculation and saved on the source dock.
4. Create a cross-nation allied route. Confirm it succeeds.
5. Create a neutral or enemy route. Confirm it is refused.
6. Try a route blocked by land. Confirm no route is saved and the failure reason is clear.
7. Try a long route through unloaded chunks. Confirm the server stays responsive and the task either succeeds or fails by budget.
8. Assign a sailboat to the generated route and confirm it travels between the two berth areas.

## Out Of Scope

- Full dock screen redesign.
- Dynamic avoidance of other moving boats while underway.
- Offline or unloaded boat simulation.
- Cross-dimension routes.
- Integration with road planner map rendering.
- Rewriting sailboat autopilot unless generated water-surface Y values expose a compatibility problem.
- Replacing manual route books.
