# Market Web Server Tile Render Design

## Goal

Add a server-side terrain tile renderer for the market web map. The renderer fills the web map cache from server-side world data when chunks are already safe to read, while preserving the current lightweight map behavior:

- Web tile requests never block on terrain rendering.
- The renderer never force-loads chunks in phase one.
- Unknown areas remain black.
- Existing client-uploaded tiles remain a fallback, but server-rendered terrain has higher cache quality.

This design extends the existing `2026-06-13-market-web-map-design.md`. That earlier design prohibited server terrain rendering for the first web map version. This design intentionally changes that boundary by adding a controlled server renderer with strict safety rules.

## Confirmed Decisions

- Use a hybrid roadmap:
  - Phase one renders only chunks currently loaded in server memory.
  - Phase two can add Pl3xMap-style region file scanning from `.mca` data.
- Use chunk-level incremental rendering first, not full-tile redraw.
- Trigger rendering from both web tile requests and low-frequency server activity scans.
- Track tile source quality so better sources can replace weaker cached terrain.
- Existing red-water tiles without metadata are treated as legacy cache and may be overwritten by server-rendered chunks.

## Non-Goals

- Do not embed Pl3xMap.
- Do not replace the road planner map or claim map.
- Do not force-load chunks for web map rendering.
- Do not render synchronously inside HTTP tile requests.
- Do not make terrain tile pixels part of gameplay logic.
- Do not fix market commodity icon fallback in this feature; that is a separate market web bug.

## Architecture

The design follows the useful parts of Pl3xMap without copying its full world renderer.

Pl3xMap reference pattern:

- `RegionProcessor` queues scan work.
- `RegionScanTask` loads world data and runs renderers.
- `Renderer` samples terrain/fluid/color data.
- `TileImage` merges pixels into tile images and writes them to disk.
- Image color format is handled explicitly.

Sailboat implementation pattern:

- `MarketWebMapRenderService` owns lifecycle, queue, budget, and tick execution.
- `MarketWebMapRenderQueue` stores chunk render work with de-duplication and TTL.
- `MarketWebLoadedChunkTileRenderer` samples one loaded chunk into a 16x16 ARGB pixel block.
- `MarketWebMapTileMergeStore` merges that block into a 256x256 cached PNG tile and updates metadata.
- `MarketWebMapTileCache` remains the disk-facing tile cache, with extra metadata support.

This keeps the web map renderer separate from `RoadPlannerMapPreloadService`. The road planner preload service can force-load chunks for gameplay tools; the web renderer must not.

## Data Flow

### Web Request Trigger

1. Browser requests `/api/map/tile/lod_1/{tileX}/{tileZ}.png`.
2. Server checks the existing tile cache.
3. If the tile is missing, legacy, or lower quality, the server enqueues currently loaded chunks inside that tile.
4. The HTTP request immediately returns the cached PNG or `404`.
5. The frontend continues to draw black for missing tiles.
6. A later refresh can show the newly rendered tile once the queue processes it.

### Activity Trigger

1. On a low-frequency server tick, the service scans important loaded areas.
2. Candidate areas include online players, active transport entities, markets, docks, post stations, and town/nation cores.
3. The service enqueues only chunks that are already loaded.
4. The tick processor renders a small number of chunks per tick.

### Render and Merge

1. `MarketWebMapRenderService.tick(level)` claims a small global budget.
2. For each queued chunk, `MarketWebLoadedChunkTileRenderer` checks `level.getChunkSource().getChunk(chunkX, chunkZ, false)`.
3. If the chunk is not loaded, the task is skipped or retried later without writing cache data.
4. If loaded, the renderer samples 16x16 columns using server-side map sampling.
5. The merge store reads or creates the target 256x256 tile.
6. It writes the 16x16 subregion at the correct tile-local offset.
7. It atomically replaces the PNG and updates metadata.

## Tile Coordinates

The first phase uses the existing tile shape:

- Tile blocks: 256x256.
- Tile pixels: 256x256.
- Chunks per tile axis: 16.
- Pixels per chunk: 16x16.
- LOD: `lod_1` only.
- Dimension: `minecraft:overworld` only.

For a chunk:

- `tileX = floorDiv(chunkX, 16)`
- `tileZ = floorDiv(chunkZ, 16)`
- `localChunkX = floorMod(chunkX, 16)`
- `localChunkZ = floorMod(chunkZ, 16)`
- `pixelOffsetX = localChunkX * 16`
- `pixelOffsetZ = localChunkZ * 16`

## Sampling Rules

The phase-one renderer reuses the existing road map server sampling concepts where practical:

- Use `ServerLevel`.
- Check the chunk is already loaded with `getChunk(chunkX, chunkZ, false)`.
- Sample each x/z column from the loaded chunk area.
- Use `Heightmap.Types.MOTION_BLOCKING_NO_LEAVES` for surface lookup.
- Detect water and water depth.
- Use `MapColor` and the existing `RoadMapColorizer` style for terrain readability.
- Output Java ARGB pixels only.

The renderer must not call:

- `ServerLevel#getChunkAt`
- `getChunk(..., true)`
- `ForgeChunkManager.forceChunk`
- any helper that implicitly loads chunks

Client `NativeImage` upload remains the only path that performs ABGR-to-ARGB conversion. Server rendering stores ARGB directly.

## Queue and Budget

Queue unit: `(dimensionId, chunkX, chunkZ)`.

Queue behavior:

- Deduplicate by queue key.
- Reject unsupported dimensions.
- Reject tasks outside configured bounds if bounds are later added.
- Drop stale tasks after TTL.
- Cap total queued tasks.
- Prefer chunks near active web requests and players.

Default budget target:

- Process 2-4 loaded chunks per server tick globally.
- Apply a per-level cap so multiple dimensions do not monopolize work.
- Use a separate low-frequency enqueue scan, such as every 20-100 ticks.

The exact numbers can be config-backed later. The implementation should expose constants first and keep them conservative.

## Cache Metadata

Each tile gets metadata next to the PNG. A compact JSON file is sufficient for phase one.

Suggested path:

```text
world/data/sailboatmod_market_web_map/overworld/lod_1/{tileX}_{tileZ}.json
```

Metadata fields:

- `version`
- `dimensionId`
- `lod`
- `tileX`
- `tileZ`
- `updatedAtMillis`
- `quality`
- `chunkSources`
- `coverageMask` or per-chunk source map

Quality order:

1. `server_region_scan`
2. `server_loaded_chunk`
3. `client_upload`
4. `legacy_unknown`
5. `unknown`

Overwrite rules:

- `server_region_scan` may overwrite all lower quality sources.
- `server_loaded_chunk` may overwrite `legacy_unknown`, `unknown`, and `client_upload`.
- `client_upload` may fill `unknown` and missing cache only.
- A lower quality source may not overwrite a higher quality chunk subregion.

Existing PNG files with no metadata are treated as `legacy_unknown`.

## Red-Water Cache Repair

The red-water issue is handled by quality migration instead of assuming the old PNG is valid.

Rules:

- Existing no-metadata tiles are considered legacy.
- Loaded chunk rendering may overwrite legacy tile subregions.
- Client uploads no longer red/blue swap for future uploads, but old client-written PNGs still need replacement.
- A repair command should be provided:
  - `/marketwebmap repaircache` marks or rewrites metadata so no-metadata tiles become `legacy_unknown`.
  - `/marketwebmap clearlegacy` can delete no-metadata PNGs after explicit admin action.

The default behavior should not delete cache files automatically.

## Tile Merge Store

The merge store reads the current cached PNG if it exists and is valid. If missing, it starts from a transparent or black 256x256 image. If the existing PNG is corrupt:

- If metadata is missing or low quality, recreate the tile and continue.
- If metadata is high quality, skip overwrite and log a warning.

Writes are atomic:

1. Decode or create tile image.
2. Apply 16x16 subregion.
3. Write PNG to a temp file.
4. Write metadata to a temp file.
5. Atomically replace the PNG.
6. Atomically replace metadata.

The web frontend already draws black under missing or transparent pixels, so the renderer should prefer transparent for unknown areas inside partial tiles. This avoids writing fake terrain.

## Trigger Sources

### HTTP Tile Request

`MarketWebService.mapTile(...)` or the server handler should call a non-blocking enqueue helper before returning:

- If cache is missing or legacy, enqueue loaded chunks for that tile.
- The enqueue helper must not render immediately.
- It must check current loaded state before queueing, or the renderer must check again before sampling.

### Player Nearby Scan

Every configured interval:

- For each online player in the overworld, enqueue loaded chunks within a small radius.
- Suggested first radius: 3 chunks.

### Important Point Scan

Every longer interval:

- Enqueue loaded chunks near market terminals.
- Enqueue loaded chunks near docks/post stations when available from saved terminal registries.
- Enqueue loaded chunks near town/nation cores.

This keeps important map areas populated without requiring a user to pan the web map first.

## Phase Two: Region File Scan

The phase-two design should mirror Pl3xMap more closely:

- Scan `.mca` region files off the server tick thread.
- Decode chunk NBT from region files without loading Minecraft chunks.
- Render full tiles or region tile segments.
- Write metadata with quality `server_region_scan`.
- Use a region modified-state tracker to avoid rescanning unchanged region files.

Phase two should not be implemented until phase one is stable.

## API Behavior

Tile endpoint behavior stays simple:

- `GET /api/map/tile/lod_1/{tileX}/{tileZ}.png`
- Returns cached PNG if present.
- Returns `404` if missing.
- Enqueues background render opportunities.
- Never waits for rendering.
- Never loads chunks for the request.

Snapshot metadata can later include tile revision or cache quality counts, but this is not required for phase one.

## Error Handling

- Missing chunk: skip and keep existing cache.
- Unsupported dimension: reject enqueue.
- Sampling exception: skip chunk, log low-frequency warning.
- PNG decode failure: recreate only when metadata allows overwrite.
- Metadata decode failure: treat as `legacy_unknown`.
- Queue overflow: drop oldest or lowest priority work.
- Server shutdown: clear runtime queue and finish no new writes.

## Tests

Required backend tests:

- Loaded chunk renderer never force-loads chunks.
- Missing/unloaded chunk produces no write.
- A rendered 16x16 chunk subregion merges into the correct tile coordinates.
- Merging a subregion does not alter other tile pixels.
- `server_loaded_chunk` overwrites `client_upload`.
- `client_upload` does not overwrite `server_loaded_chunk`.
- No-metadata PNG is treated as `legacy_unknown`.
- Service-rendered ARGB blue water stays blue in PNG output.
- HTTP tile request enqueues work but does not synchronously render.
- Queue de-duplicates repeated requests for the same chunk.
- Queue budget limits processed chunks per tick.

Suggested verification commands:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.*"
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.map.*"
.\gradlew.bat compileJava
.\gradlew.bat build
```

## Implementation Notes

- Use existing `RoadMapTileSpec` constants to avoid tile-size drift.
- Keep `MarketWebMapTileCache.encodePng(...)` as ARGB.
- Keep `MarketWebMapTileCache.encodeNativeImagePng(...)` only for client-uploaded NativeImage data.
- `MarketWebMapTileUploadService.acceptServerGeneratedTile(...)` can become one source feeding the metadata-aware cache.
- Existing client uploads should be downgraded to `client_upload` quality when metadata support is introduced.
- The existing `SharedMapServerState` can keep marking rendered chunks, but it is not a pixel store and cannot replace tile metadata.
