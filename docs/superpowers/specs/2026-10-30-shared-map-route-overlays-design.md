# Shared map route overlays

User approval: 2026-10-30, “按推荐方案实施”. Applies to the existing RoadPlanner, Town and Nation game maps.

## Behavior

- Draw registered built roads, registered land transport routes and port sea routes over the existing actual/Tellus map, using gold, green and cyan with contrasting outlines. Provide independent ROAD/LAND/SEA layer switches and a legend.
- Hover shows the route name. An explicit route inspection mode enables click selection, highlight and details: name, kind, start, end and original route length. Normal territory selection and road editing retain their existing behavior when inspection is off; middle drag and wheel zoom continue in both modes.
- Lines use the same world-to-screen transform as terrain. Clip actual line segments, including a segment whose endpoints are both outside the viewport. Keep screen-space widths and hit tolerance stable across zoom; preserve disconnected clipped portions.
- Use bounded view requests and replies, zoom-sensitive display simplification, generation IDs and world/dimension checks. Report incomplete bounded results visibly; never join unrelated pieces or quietly imply complete coverage.

## Authority and persistence

The main mod owns the shared data service, independent of optional marketweb/BlueMap availability. Existing persisted road records/graph provide roads; BlueMapMarkerSavedData already stores registered unloaded port route geometry. Add the missing persisted station catalog of registered route geometry, refreshed by normal terminal lifecycle/route changes without triggering network-wide pathfinding. Keep unloaded snapshots, remove genuinely destroyed facilities, and migrate old facilities lazily when encountered.

The view includes published terminal/map route catalogs in the current dimension and retains applicable access checks. Do not read held route books, private vehicle missions, shipment inventories or recipient details. Viewing grants no edit, dispatch or transport-use authority. Display caches and display simplification never modify authoritative navigation waypoints.

Viewport work must not call force chunk loads, fetch remote block entities or generate route corridors. Freeze immutable catalog inputs on the server thread; bounded worker jobs may clip them and publish only the current owner's requested generation. Disconnect/server stop cancels stale work. Client logout/dimension changes clear route state and selection.

## Contracts

Shared model package: `com.monpai.sailboatmod.map.route`.

```java
enum MapRouteKind { ROAD, LAND, SEA }
record MapRoutePoint(double x, double z) {}
record MapRouteEntry(String id, MapRouteKind kind, String name, String fromName,
    String toName, double lengthBlocks, List<List<MapRoutePoint>> paths) {}
record MapRouteViewport(double minX, double minZ, double maxX, double maxZ,
    double pixelsPerBlock) {}
record MapRouteSnapshot(List<MapRouteEntry> routes, boolean truncated) {}
```

Coordinates travel as finite doubles, without packed BlockPos coordinate loss. Replies are capped at 128 entries and 4096 total points, with bounded strings/parts and a size below 128 KiB. Client/server payload codecs reject invalid dimensions, coordinates and counts before allocation. A new request supersedes old results; category switches filter current client data immediately.

## Validation and delivery

Regression-first tests cover crossing/clipping, negative and very large coordinates, discontinuities, zoom tolerance, hidden categories, selection, malformed payload bounds, stale/dimension rejection, saved station unload/removal and view permissions. Run the complete standard suite and release build; inspect all three actual game maps at GUI scales 2/3/4 in a copied world, with registered test geometry. Verify pan/zoom anchoring and inspection-mode isolation. Test fixtures are excluded from release JARs. Preserve the original world and unrelated local edits, package fix_11, integrate/push the default branch and verify its SHA. Existing upstream Sable restart limitations are unchanged and unrelated to this feature.
