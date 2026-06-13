# Market Web Map Design

## Goal

Add a lightweight 2D MAP page to the existing market web UI. The map shows the overworld using cached minimap tiles created during normal in-game minimap rendering, plus market markers, nation territory overlays, fixed-size flag tooltips, and active logistics traces.

The web map must not render new world terrain, load chunks, or force-load chunks. Unknown areas stay black.

## Confirmed Decisions

- First version shows only `minecraft:overworld`.
- Use the current `marketweb` app and add a global MAP page.
- From a selected market detail, switching to MAP should focus that market.
- The world image uses cached tiles contributed while players render in-game minimaps.
- All normal logged-in players may contribute cached tiles.
- Uploaded tiles are display-only data and must not affect gameplay logic.
- Nation territories display even when the terrain tile under them is unknown/black.
- Territory fill uses the nation primary color; territory border uses the nation secondary color.
- Territory tooltip shows a fixed-size flag image container and nation name.
- Logistics traces show only active/in-progress shipments.
- Completed, delivered, failed, claimed, or cancelled shipments are hidden from the map.
- Logistics visibility is limited to the logged-in player's town/nation scope.
- Logistics lines use the actual shipment route; traveled segments are solid, remaining segments are dashed.

## Architecture

The design follows the useful part of Pl3xMap's architecture: keep terrain tiles, markers, shapes, and live overlays as separate layers. It does not embed Pl3xMap or Leaflet in the first version.

Layers:

1. Terrain tile layer
   - Reads server-side cached minimap tiles.
   - Unknown tiles render black on the client.
   - Tile requests never call `ServerLevel#getChunk`, `getChunkAt`, force loading, or terrain sampling.

2. Nation territory layer
   - Reads `NationSavedData.getAllClaims()`.
   - Converts each claim chunk into a world-space rectangle.
   - Reads colors and flag ids from `NationRecord`.

3. Market marker layer
   - Reads `MarketTerminalSavedData`.
   - Does not resolve live `MarketBlockEntity` by loading chunks.

4. Logistics trace layer
   - Reads active shipment trace records.
   - Filters by the viewer's town/nation membership.
   - Uses saved route waypoints and progress.

## Tile Cache Flow

The current in-game map pipeline already produces tile data through road planner and claim map rendering. The new flow adds a server-side cache copy:

1. Player opens or uses an in-game minimap view.
2. Client renders or receives a valid map tile.
3. Client submits the tile to the server.
4. Server validates the tile and stores it under the web map cache.
5. Web MAP requests the cached PNG tile.
6. Missing tiles are treated as black by the frontend.

Suggested cache path:

```text
world/data/sailboatmod_market_web_map/overworld/lod_1/{tileX}_{tileZ}.png
```

The cache is independent from gameplay data. It is only a visual artifact for the web UI.

## Tile Upload Validation

The server accepts a contributed tile only if all checks pass:

- The player is authenticated and currently online.
- Dimension is exactly `minecraft:overworld`.
- Tile coordinates and LOD are within configured bounds.
- Image size matches the expected tile size, initially 256x256.
- Payload is PNG or a known ARGB format produced by the mod client.
- Payload byte size is capped.
- Per-player upload rate is limited.
- Uploads are not used to mark claims, roads, markets, or visibility for gameplay.

Invalid uploads are ignored or rejected without disconnecting the player.

## Backend API

Add map endpoints to `MarketWebServer` and delegate data composition to dedicated services.

### `GET /api/map/snapshot`

Returns the current overworld map metadata:

- dimension id
- unknown color, always `#000000`
- known tile bounds
- layer revision numbers
- default focus point

### `GET /api/map/tile/{lod}/{tileX}/{tileZ}.png`

Reads a cached tile from disk. If missing, return `404`; the frontend draws black.

This endpoint must never generate terrain.

### `POST /api/map/tile`

Receives validated tile submissions from mod clients. This endpoint is for game clients, not the public web UI.

The first version accepts PNG tile payloads only. ARGB transport can be added later if needed.

### `GET /api/map/territories`

Returns claim rectangles with:

- chunk x/z
- nation id and name
- town id and name when available
- primary fill color
- secondary border color
- flag id

If nation data is missing, return a neutral fallback color and no flag id.

### `GET /api/map/markets`

Returns market markers from `MarketTerminalSavedData`:

- market id
- market name
- owner name
- owner uuid
- overworld position
- selected/focused state when applicable

Do not call `MarketWebService.resolveMarket()` for the map marker list because it currently loads chunks.

### `GET /api/map/shipments`

Returns active logistics traces visible to the current identity:

- shipment id
- source and target labels
- transport mode: port, post station, or other supported value
- carrier name
- route points
- completed point index or progress ratio
- status

Only statuses such as `SAILING`, `IN_TRANSIT`, and `ARRIVED` remain candidates. Terminal states such as `DELIVERED`, `CLAIMED`, `FAILED`, `FAILED_ROLLBACK`, and cancelled states are hidden.

### `GET /api/map/flags/{flagId}.png`

Serves existing nation flag PNGs through `NationFlagStorage`.

The frontend constrains display size, for example 96x48, with `object-fit: contain`.

## Logistics Trace Data

Existing `ShippingOrder` stores source, target, route name, ETA, transport mode, and status, but not enough route geometry for real web tracking.

Add a new `ShippingTraceRecord` instead of overloading `ShippingOrder`. `ShippingOrder` remains the commerce/order status record; `ShippingTraceRecord` is the map-specific route and progress snapshot.

Required persisted data:

- shipping order id
- dimension id, overworld for the first version
- transport mode
- source and target terminal positions
- route waypoints used at dispatch time
- total distance
- started time or game time
- last known carrier position
- completed route progress
- owner/town/nation scope used for filtering

At dispatch time, save the actual route:

- Sea shipments use the selected/generated dock route waypoints.
- Land shipments use the selected/generated post-station or road-network route waypoints.
- Manual routes use the recorded manual waypoints.
- Generated routes use the generated path waypoints.

Progress:

- Prefer live carrier position and active route progress when the carrier is loaded.
- If the carrier is unloaded, use last known progress and ETA as a conservative estimate.
- Do not mark a shipment complete based only on ETA; order status remains authoritative.

## Frontend Design

Use the existing liquid-glass market web visual direction, but keep the map as an operational tool rather than a landing page.

Layout:

- Left panel: layer toggles, market search/list, focused market summary.
- Center: large 2D canvas map with black unknown background.
- Right panel: selected object details and active logistics list.

Map interactions:

- Drag to pan.
- Wheel to zoom.
- Double click or action button to focus selected market.
- Layer toggles for terrain, territories, markets, and logistics.
- Hover tooltip for territory, market, and shipment line.
- Selected shipment highlights its route.

Default focus order:

1. Selected market from the market detail page.
2. Player's town or nation core.
3. Average center of known market markers.
4. World origin `(0, 0)`.

Refresh:

- Territory and market overlays refresh by revision or manual reload.
- Shipments refresh while MAP is visible, initially every 5 seconds.
- Tile images are not refreshed on the shipment timer.

## Permissions

Terrain tiles, market markers, and nation territories may be visible to logged-in market web users.

Logistics traces are restricted:

- A player can see shipments tied to their own town or nation.
- Nation/town managers and members use existing membership records from `NationSavedData`.
- Do not expose all server logistics by default.

## Error Handling

- Missing tile: frontend draws black.
- Missing nation for a claim: neutral fallback color and no flag.
- Missing flag image: show an empty fixed-size flag frame.
- Missing route geometry for an old shipment: hide it from route tracking or show a low-confidence fallback only if explicitly allowed later.
- API failure for one overlay does not prevent other layers from rendering.
- Invalid tile upload is rejected without affecting existing cached tiles.

## Tests And Verification

Backend tests:

- Unknown tile request does not call chunk loading or terrain sampling.
- Tile upload rejects non-overworld dimensions.
- Tile upload rejects wrong dimensions or oversized payloads.
- Territory layer uses nation primary color for fill and secondary color for border.
- Territory layer tolerates missing nation data.
- Market markers come from `MarketTerminalSavedData` without loading chunks.
- Shipment API hides completed/failed/delivered shipments.
- Shipment API filters by the viewer's town/nation membership.
- Shipment trace preserves route waypoints and progress split.

Frontend checks:

- MAP route renders with no selected market.
- MAP route focuses selected market when provided.
- Unknown background is black.
- Flag tooltip image remains fixed size.
- Solid/dashed logistics split is visually stable.
- Layer toggles do not resize the page.

Resource checks:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.*"
.\gradlew.bat processResources
.\gradlew.bat compileJava
```

## Out Of Scope For First Version

- Nether, End, or modded dimensions.
- 3D map rendering.
- Public unauthenticated map access.
- Server-side terrain rendering from web requests.
- Replacing road planner or claim map rendering.
- Full Pl3xMap or Leaflet integration.
- Completed shipment history playback.
- Gameplay decisions based on uploaded tile pixels.

## Open Implementation Notes

- The existing `SharedMapServerState` tracks rendered chunk indexes but not full pixels. It can help identify known coverage, but the web map still needs a dedicated server tile cache for pixels.
- The current client `RoadPlannerTileManager` stores complete cached PNG tiles on the client. The new upload path should reuse this format where practical.
- `MarketWebService.resolveMarket()` currently calls `level.getChunkAt(pos)`. MAP marker APIs must avoid that path.
- Existing `ShippingOrder` is not enough for route tracking; implementation should add a trace snapshot model instead of trying to infer all routes from route names.
