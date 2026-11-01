# Destination water infrastructure and monotonic voyage following

## Data and planning
- `WaterRouteNetworkSavedData`: per-dimension saved infrastructure, max 256 records / 262144 geometry vertices. No cargo, player state, or journey progress. Imports generated legacy routes from loaded port BEs; does not force-load all ports.
- `WaterRouteNetwork`: immutable background graph of existing geometric segments. Projects origin/destination onto existing edges, splits only at exact projected positions, supports reverse/subset and multiple routes. Different berths at one dock remain gaps, not invented water connections.
- Full route, reverse section, useful prefix/suffix extension, or merged routes are selected. All reused segments are revalidated against live/saved/Tellus real-first water. Every missing connector runs normal strict planning. Unusable cache falls back to ordinary planning. No safe route is saved merely because points are nearby.
- Geometry is intentionally not a voyage: a boat receives a private itinerary copy. Route-library edits do not mutate a moving boat's current itinerary.
- Reuse is not zero cost: current water checks, endpoint access, and local connector work remain. No guarantee of the globally shortest path.

## User and market dispatch
- PORT dispatch UI selects a vessel then a destination; storage cargo uses the same destination selector. Existing route-management/book functions remain for compatibility; POST_STATION is unchanged.
- New `DepartForDestinationPacket`: server checks boat UUID, origin proximity, carrier ownership/rental, registered target, terminal diplomacy, and rechecks after asynchronous planning. Cargo identity/count is snapshotted but not removed until success; existing cargo receipts and rental rollback remain.
- `WaterDestinationService`: market has bounded single-flight requests keyed by dimension/source/target; max8 active, backoff on failure, 10 minute timeout, shutdown cleanup. No cargo removal or payment while planning. The normal dispatcher retries when an itinerary exists. Automatic empty pickup legs use it too.
- Destination selection is dock based, not arbitrary mid-ocean click-to-sail. Legacy routes are gradually imported when relevant docks load, rather than a forced global startup scan.

## Tracking and map
- Monotonic segment projection and swept waypoint crossing, with loaded-only 2x2 near-field checks, replace exact-vertex chasing. A passed waypoint inside the corridor does not force a return orbit. No blind stalled-waypoint skip.
- Bounded arc-length lookahead follows the polyline; live checks reject shortcutting land. Sharp heading errors/corners reduce speed. Per boat live-cell checks cached per tick, capped512 unique cells; no chunk loading in this check.
- Passenger/server-authority policy retained; no client route steering. Actual passenger/vehicle packet and physics behavior still require game acceptance testing.
- Trace geometry follows the boat's active route including docking/rescue/splices, not an obsolete initial route; up to65536 points instead of2048. New oversized boat itineraries are rejected rather than displaying a truncated path. Actual tracked position remains distinct from the planned path and updates at the existing2s cadence.
- All boats' individual cargo-order traces receive identical geometry/position updates. Trace schema and HTTP endpoints unchanged; marketweb sources are unchanged; its artifact is also compiled as a compatibility check.

## Search and safety
- Includes the previously local adaptive progress budget; genuine progress buys bounded extra budget, not repetitive requests.
- Bidirectional meeting accepts an opposite already-rooted open node; immutable parent chains are checked for roots/cycles/long jumps before publishing, and downstream strict terrain validation remains.
- Reused geometry still respects current endpoint permissions. Shared navigation geometry is not permission to operate other players' boats or inventories.
- Protocol20: update server and clients together. Back up world before testing; new SavedData is additive, old routes/orders are not deleted.

## Validation / boundaries
- Pure deterministic graph/follower and existing pathfinding tests run locally with lightweight API stubs. This is not a running Minecraft client/server.
- CI compiles the entire project, runs selected water/terrain/follower/logistics/chat/UI tests, packages a full main JAR and addon, then runs pinned Tellus native integrations and an independently checked reverse interior reuse test. Final counts/status must come from the produced reports, not this plan.
- No live multiplayer or passenger physics or browser rendering acceptance has been performed here. Server restarts cancel running jobs; infrastructure persists but in-flight computation does not resume.
