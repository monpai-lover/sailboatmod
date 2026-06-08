# Post Station Land Transport Network Design

Date: 2026-06-09

## Goal

Rework the post station system around reachable towns instead of manually selected routes. A post station should show all towns reachable by the built road network, including indirect routes such as A -> B -> C. Selecting C should create one complete A -> C route; B is only a pass-through point unless the player explicitly chooses B as the destination.

The same reachability rules must also drive market visibility and land dispatch. If two markets cannot reach each other by a valid port route or post station road route, their cross-town listings must not be visible to each other and cannot produce undeliverable orders.

## Decisions

- Post station destinations are displayed by town, not by individual station.
- If a town has multiple post stations, the system chooses the best target station by route score.
- Vehicles default to auto-return after arrival. The player can turn this off for a dispatch.
- Recall uses an actual generated return route. It never teleports the carriage.
- A route through intermediate towns is generated as one complete route and does not stop at intermediate towns.
- Road reachability uses built road graph routes first and allows bounded terrain connector fallback.
- Legacy route book and manual route management remain available on an advanced page.
- First version only recalls carriage entities currently loaded in a server level. It does not simulate offline or unloaded vehicle travel.

## Architecture

Add a server-side `LandTransportNetworkService` as the single source of truth for land transport reachability. Existing road graph and auto-route code remains useful, but post stations and markets should call this service instead of duplicating route discovery.

Primary inputs:

- `ServerLevel`
- Source town id or source `PostStationBlockEntity`
- Target town id, target warehouse, or target post station
- Route policy: built graph first, bounded terrain fallback allowed

Primary outputs:

- `ReachableTown`: target town id, display name, recommended target station, route distance, ETA, and route source.
- `LandRoutePlan`: source station, target station, complete waypoint list, distance, and pass-through town labels.
- `RouteAvailability`: reachable or unavailable, with a stable failure reason for UI and tests.

The service will:

- Query `RoadNetworkGraph` built edges for graph routing.
- Use existing `RoadAutoRouteService.resolveAutoRoutePreview` behavior as the route-generation foundation where possible.
- Preserve diplomacy and permissions from `RoadAutoRouteService.canCreateAutoRoute`: same nation is allowed; allied or trade diplomacy between nations is allowed; unrelated nations are blocked.
- Evaluate all candidate post station pairs between two towns and choose the lowest score. The score should prioritize route distance, then proximity to the relevant warehouse or market.

## Post Station UI

`PostStationScreen` should no longer be only a themed `DockScreen`. It should become a land-dispatch screen with four tabs:

- Destinations: reachable town list, distance, ETA, and selected route summary.
- Vehicles: local available carriages and loaded recall candidates.
- Dispatch: selected destination, selected carriage, and the auto-return toggle. Auto-return is enabled by default.
- Advanced: legacy route book import, manual route list, reverse route, and delete route.

Use a separate `PostStationScreenData` record instead of extending `DockScreenData`, so land-specific fields do not make the port UI more complex.

Server-side flow:

1. Opening the screen builds `PostStationScreenData` from `LandTransportNetworkService`.
2. Selecting a town sends a post station action packet with the destination index or town id.
3. Dispatch re-resolves the route server-side to avoid trusting stale client data.
4. The selected carriage receives a generated route catalog containing one route named like `A -> C`.
5. The carriage starts autopilot if it is available and inside the source station zone.

Empty states:

- No reachable town: show a clear no-road-network message.
- Reachable towns but no carriage: destinations remain visible, dispatch is unavailable with a no-vehicle message.
- Route generation fails after selection: do not move cargo, do not change orders, and show route unavailable.

## Carriage Task State

Carriages need land transport task state so arrival, return, and recall are explicit.

Add or persist these concepts:

- `homeStationPos`: station that dispatched the carriage.
- `destinationStationPos` and `destinationTownId`: current task destination.
- `autoReturnOnArrival`: default true, set from the post station UI or market dispatch.
- `dockedStationPos` and `dockedTownId`: where the carriage is currently parked.
- `transportTaskKind`: normal dispatch, market order, recall, or return.

Arrival behavior:

- If the carriage has cargo or market order manifest, unload into the target post station or town stockpile through existing shipment handling.
- If `autoReturnOnArrival` is true, generate and start a reverse route back to `homeStationPos`.
- If `autoReturnOnArrival` is false, stop autopilot and mark the carriage docked at the destination.
- A return trip stops at home and does not loop.
- A recall trip stops at the requesting station.

Recall rules:

- A carriage can be recalled only when it is alive, loaded, owned by the player, not currently autopiloting, not being manually driven, and has a known docked station or town.
- Recall calls `LandTransportNetworkService` to generate a route from the carriage's current docked station or town to the current station.
- Recall starts normal autopilot; it never teleports the entity.

If return or recall route generation fails, the carriage remains docked and recoverable. Cargo must not be lost.

## Market Visibility And Dispatch

Market listing visibility must use transport reachability.

Visibility rules:

- Same-town listings are always visible.
- Cross-town listings are visible only if at least one transport mode can deliver between the source and viewer towns.
- Port delivery uses existing port route availability.
- Land delivery calls `LandTransportNetworkService`.
- If neither port nor land delivery is available, the listing is omitted from the viewer's market overview.

Purchase and dispatch rules:

- The server rechecks reachability when a purchase order is created.
- `DispatchMarketOrderPacket` with `POST_STATION` uses `LandTransportNetworkService` to create the route plan. It must not require the source station to already have a saved direct route to the target station.
- `AUTO` dispatch can choose port or post station, but only among available modes.
- Market land dispatch defaults `autoReturnOnArrival` to true.
- A-B-C land shipment from A to C is a single A -> C carriage route; B is pass-through only.
- If reachability disappears after order creation but before dispatch, the order remains `WAITING_SHIPMENT`; cargo and listing reservations must not be consumed.

The existing `MarketBlockEntity.resolveDispatchTerminalPlan` and route preview logic should be simplified to consume service results instead of separately pairing terminals and trying to auto-create direct routes.

## Compatibility

- Existing post station `DockRoutes` and route books remain saved and usable from the advanced tab.
- Existing carriages without docked state get docked state the first time they are dispatched or when a post station finds them parked inside a station zone.
- Existing market orders keep their current statuses. New reachability checks affect new browsing, purchase, and dispatch operations.
- `DockScreen` remains the port UI. Land changes should not expand the water-port UI surface unless necessary.

## Testing

Service tests:

- A-C direct road makes C reachable from A.
- A-B-C road makes both B and C reachable from A.
- Selecting C on A-B-C produces a complete A -> C route.
- Unreachable towns are excluded when graph and terrain fallback fail.
- Multiple target stations choose the shortest valid plan.

Post station tests:

- Screen data includes reachable towns.
- Dispatch default has auto-return enabled.
- Canceling auto-return leaves the carriage docked at destination.
- Recall generates a return route and does not teleport.

Market tests:

- Unreachable cross-town listings are omitted.
- Land-reachable listings are visible and expose a `POST_STATION` dispatch option.
- A-B-C land reachability lets A buy and dispatch to C.
- Dispatch failure after route invalidation leaves orders waiting and cargo untouched.

Regression tests:

- Existing `RoadGraphRoutingServiceTest` and `CarriageRoutePlannerTest` continue to pass.
- Add coverage that bounded terrain fallback still works for station-to-road connectors.

## Out Of Scope

- Offline carriage simulation and unloaded chunk recall.
- Multi-stop deliveries that intentionally unload at intermediate towns.
- Teleport-based vehicle recovery.
- Replacing the port route UI.
