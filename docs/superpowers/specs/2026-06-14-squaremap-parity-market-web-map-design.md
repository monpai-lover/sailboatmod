# Squaremap Parity Market Web Map Design

## Goal

Rebuild the market web map so its terrain rendering model is equivalent in architecture and performance discipline to squaremap, while preserving Sailboat-specific overlays and market workflows.

The map must keep these Sailboat features:

- Active logistics state and route tracking.
- Territory display with nation primary fill and secondary border.
- Uploaded nation flags in fixed-size UI containers.
- Market location markers and market detail selection.
- Public terrain, market, territory, and flag viewing without web login.

## Non-Goals

- Do not replace the existing market web app with squaremap's full web UI.
- Do not expose squaremap commands or configuration directly to players.
- Do not generate new terrain for the sake of map rendering.
- Do not use client-uploaded minimap tiles as a terrain source.
- Do not make HTTP tile requests perform synchronous world reads, chunk loads, or rendering.

## Recommended Approach

Use a squaremap-style terrain engine inside Sailboat:

- Minimal mixin/accessor layer for chunk map internals.
- Server-side immutable chunk snapshots.
- Background and full render workers with strict budgets.
- Region-backed 512x512 PNG tiles with a zoom pyramid.
- Separate Sailboat overlay layers for logistics, territories, flags, and markets.

This is preferred over directly embedding squaremap modules because Sailboat needs a market-specific web shell, custom overlays, and Forge/Arclight compatibility controls.

## Architecture

### Terrain Engine

Add a dedicated terrain rendering subsystem under `market/web/map`:

- `MarketWebMapRenderManager`
  - Owns render state.
  - Starts and stops background rendering.
  - Manages full render progress and cancellation.
  - Exposes queue status for `/marketweb map status`.

- `MarketWebChunkSnapshotProvider`
  - Produces immutable chunk snapshots.
  - Reads visible full chunks when already present.
  - Reads pending unload chunks through accessor support.
  - Reads saved chunk NBT before scheduling storage-backed loads.
  - Rejects chunks that are not already generated/full.

- `MarketWebMapImage`
  - Represents one 512x512 region-style image.
  - Patches dirty chunk pixels.
  - Saves base and zoom pyramid PNGs atomically.

- `MarketWebMapImageIOExecutor`
  - Single bounded image-write queue.
  - Applies backpressure when PNG writes lag behind render workers.

### Minimal Accessors

Implement the smallest accessor surface needed to match squaremap behavior:

- Access visible chunk holder by chunk key.
- Access pending unload chunk holders.
- Read chunk NBT through the server chunk storage path.
- Avoid direct mutation of chunk maps or ticket levels.

The accessor layer must be isolated in a small package so Arclight or Forge compatibility issues are easy to audit.

### Render Modes

`BackgroundRender`

- Runs periodically.
- Processes dirty chunks from region watcher, market vicinity, player vicinity, and explicit tile misses.
- Uses bounded chunk request count.
- Requeues unfinished work instead of blocking the server tick.

`FullRender`

- Scans `.mca` region files.
- Orders regions in a spiral or distance-based order from spawn/market center.
- Saves resumable progress.
- Can be paused, cancelled, and resumed.
- Never runs synchronously from HTTP.

`RadiusRender`

- Optional admin command for bounded area repaint.
- Useful for testing and repairing known corrupted map zones.

## Data Flow

1. Browser requests a terrain tile.
2. Server reads existing PNG bytes from cache.
3. If missing, server enqueues render work and returns 404/transparent placeholder behavior.
4. Render manager selects work within budget.
5. Snapshot provider reads visible, pending unload, or saved generated chunk data.
6. Worker thread colorizes immutable snapshots.
7. ImageIO queue patches and writes PNGs atomically.
8. Browser retries missing tiles with cache-versioned URLs.

## Performance Requirements

These are hard requirements:

- HTTP tile endpoint must not touch `ServerLevel`.
- HTTP tile endpoint must not call `getChunk`, `getChunkFuture`, `forceChunk`, or render code.
- Render workers must not read mutable world state directly.
- Server tick work must be limited to small snapshot acquisition batches.
- Full render must be resumable and cancellable.
- Image writes must be bounded and backpressured.
- No unbounded in-memory tile or chunk queues.
- No client map upload path may write into web map terrain cache.

Mobile browser requirements:

- Use 512 tile size.
- Use low `keepBuffer` on touch devices.
- Pause overlay refresh when `document.visibilityState !== "visible"`.
- Default side panels collapsed on touch devices.
- Respect `prefers-reduced-motion`.

## Terrain Color Model

The terrain colorizer keeps Sailboat's current block color table and fixes previous channel/softening bugs:

- Server PNG path uses ARGB through `BufferedImage`.
- Client NativeImage paths convert ARGB to native ABGR at the format boundary only.
- No whitening or liquid-glass effects are applied to terrain tile pixels.
- Water, grass, sand, snow, and foliage colors come from the shared map color table.
- Old cached tiles can be cleared with map cache commands.

## Sailboat Overlay Layers

Overlays are independent from terrain tiles and are refreshed through JSON APIs.

### Territories

- Draw claimed chunk polygons/rectangles above terrain.
- Fill uses nation primary color with alpha.
- Border uses nation secondary color.
- Internal borders between adjacent chunks with the same nation and town are suppressed.
- Tooltip shows fixed-size flag image, nation name, town name, and coordinates.
- Broken flag images hide browser broken-image UI and show a fallback frame.

### Markets

- Draw market markers at market terminal positions.
- Marker tooltip shows market name, town/nation, and coordinates.
- Click selects market and updates the right-side panel without leaving the map.

### Logistics

- Only active shipments appear on the map.
- Completed shipments are omitted.
- Route path is based on the actual dispatch route used by the shipment.
- Completed route segment is solid.
- Remaining route segment is dashed.
- Vehicle icon is directional.
- Land/post-station routes use a carriage-like icon.
- Water/dock routes use a sailboat-like icon.

### Flags

- Use existing uploaded flag storage.
- Serve flag images through the public map flag endpoint.
- Frontend containers have fixed width, height, and object-fit rules.

## Public Access and Auth

Public without login:

- Terrain tiles.
- Map snapshot/center metadata.
- Market markers.
- Territory overlays.
- Flag images.

Login may still be required for:

- Player-specific shipment visibility if route information is private.
- Wallet and market actions.
- Purchase/listing/buy-order operations.

If shipment endpoint returns 401 for guests, terrain and public overlays must still load.

## Commands

Keep and refine:

- `/marketweb map status`
- `/marketweb map scan`
- `/marketweb map repaintall`
- `/marketweb map clearall`
- `/marketweb map clearlegacy`
- `/marketweb map clearclientuploads`
- `/marketweb map clearstaleserver`

Add or refine:

- `/marketweb map fullrender start`
- `/marketweb map fullrender pause`
- `/marketweb map fullrender resume`
- `/marketweb map fullrender cancel`
- `/marketweb map radius <center> <radius>`

Command feedback must state queued regions/chunks, active workers, pending image writes, and current progress when available.

## Testing Strategy

Add tests before production changes for each behavior group:

- Accessor source tests:
  - Accessor package exists and is the only chunk-map internal boundary.
  - Web map render service no longer uses force-loading.

- Snapshot provider tests:
  - Visible chunk snapshot path.
  - Pending unload snapshot path guarded by accessor.
  - Saved generated chunk NBT path.
  - Non-generated chunk is skipped.

- Render manager tests:
  - Full render progress persistence.
  - Background queue budget limits.
  - Tile miss enqueues but does not render synchronously.

- Tile cache tests:
  - Atomic PNG write.
  - Zoom pyramid update.
  - No stale client upload overwrite.
  - Renderer version refresh.

- Frontend tests:
  - Local Leaflet assets only.
  - Squaremap-style tile endpoint is primary.
  - Territory, market, logistics, and flag layers exist.
  - Mobile performance controls exist.

- Build verification:
  - Targeted map tests.
  - `compileJava`.
  - `build -x test`.

## Rollout Plan

1. Stabilize terrain engine interfaces and tests.
2. Add minimal accessor layer.
3. Replace current partial render manager with squaremap-style render manager.
4. Implement resumable full render.
5. Keep existing square tile endpoint and Leaflet shell.
6. Harden Sailboat overlays.
7. Clear or invalidate old bad tile caches through renderer version and admin commands.
8. Build jars and test on a real Forge/Arclight server.

## Compatibility Risks

Arclight and other server patches may modify chunk internals. To reduce risk:

- Keep accessors minimal.
- Avoid redirect/injection mixins when accessor interfaces are enough.
- Do not alter chunk ticketing or distance manager behavior.
- Fail closed: if an accessor path is unavailable, skip that snapshot and keep the server running.
- Log warnings with rate limiting.

## Success Criteria

- Web map terrain no longer depends on client uploads.
- Already generated but currently unloaded areas can be rendered gradually.
- Server does not log repeated large tick lag from map rendering.
- Tile seams and broken partial tile artifacts are eliminated after cache clear/full render.
- Territory, flag, market, and active logistics overlays remain functional.
- Mobile map interaction is usable without excessive heat or stutter.
- Build produces the main mod jar and market web jar.
