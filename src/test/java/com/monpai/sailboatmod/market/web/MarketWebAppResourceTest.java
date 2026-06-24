package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebAppResourceTest {
    private static final Path APP_JS = Path.of("src/main/resources/marketweb/app.js");
    private static final Path MAP_JS = Path.of("src/main/resources/marketweb/map.js");
    private static final Path APP_CSS = Path.of("src/main/resources/marketweb/app.css");
    private static final Path INDEX_HTML = Path.of("src/main/resources/marketweb/index.html");
    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final Path MARKET_WEB_SERVER = Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java");
    private static final Path MARKET_WEB_SERVICE = Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java");
    private static final Path MARKET_WEB_COMMANDS = Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebCommands.java");
    private static final Path MOD_CONFIG = Path.of("src/main/java/com/monpai/sailboatmod/ModConfig.java");
    private static final Path CREATE_BUY_ORDER_PACKET = Path.of("src/main/java/com/monpai/sailboatmod/network/packet/CreateBuyOrderPacket.java");
    private static final Path CANCEL_BUY_ORDER_PACKET = Path.of("src/main/java/com/monpai/sailboatmod/network/packet/CancelBuyOrderPacket.java");

    @Test
    void appJavaScriptParsesInBrowserRuntime() throws Exception {
        Process process = new ProcessBuilder("node", "--check", APP_JS.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS),
                "node --check timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
    }

    @Test
    void chineseLocaleContainsReadableCoreLabels() throws IOException {
        String appJs = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertFalse(appJs.contains("\u935c\u3127\u568e\u752f\u50da"),
                "Chinese locale still contains mojibake for online market");
        assertFalse(appJs.matches("(?s).*[\u6722\u7a22\u9722].*"),
                "Chinese locale still contains damaged Chinese characters from mojibake cleanup");
        assertFalse(appJs.contains("\u4e22\u4e2a"),
                "Chinese locale should not contain accidental wording from mojibake recovery");
        assertTrue(appJs.contains("language_label: \"\u8bed\u8a00\""),
                "Chinese locale should expose a readable language label");
        assertTrue(appJs.contains("bind_account: \"\u7ed1\u5b9a\u8d26\u6237\""),
                "Chinese locale should expose a readable bind account action");
        assertTrue(appJs.contains("guest_mode_ready: \"\u8bbf\u5ba2\u6a21\u5f0f"),
                "Chinese locale should explain guest mode in Chinese");
    }

    @Test
    void mapTileEndpointIsPublicCacheReadOnly() throws IOException {
        String source = Files.readString(MARKET_WEB_SERVER, StandardCharsets.UTF_8);
        int start = source.indexOf("private void handleMapTile");
        int end = source.indexOf("private void handleMapFlag", start);
        assertTrue(start >= 0 && end > start, "handleMapTile method should be present");
        String method = source.substring(start, end);

        assertFalse(method.contains("requireIdentity("),
                "cached map tile PNGs should be readable without market web login");
        assertTrue(method.contains("\"GET\".equalsIgnoreCase"),
                "map tile endpoint should remain read-only");
        assertTrue(method.contains("service.mapTile"),
                "map tile endpoint should only read cached tile bytes");
    }

    @Test
    void mapPublicLayerEndpointsDoNotRequireLogin() throws IOException {
        String source = Files.readString(MARKET_WEB_SERVER, StandardCharsets.UTF_8);

        assertFalse(methodBody(source, "handleMapSnapshot", "handleMapMarkets").contains("requireIdentity("),
                "map snapshot should be public so guest users can center the tile map");
        assertFalse(methodBody(source, "handleMapMarkets", "handleMapTerritories").contains("requireIdentity("),
                "market markers should be public map metadata");
        assertFalse(methodBody(source, "handleMapTerritories", "handleMapShipments").contains("requireIdentity("),
                "territory overlays should be public map metadata");
        assertFalse(methodBody(source, "handleMapFlag", "handleStatic").contains("requireIdentity("),
                "territory flag images should render in public map tooltips");
        assertTrue(methodBody(source, "handleMapFlag", "handleStatic").contains("getRawPath()"),
                "flag endpoint should parse raw paths so encoded flag ids are not split by decoded slashes");
        assertTrue(methodBody(source, "handleMapFlag", "handleStatic").contains("URLDecoder.decode"),
                "flag endpoint should decode encoded flag id path segments before storage lookup");
    }

    @Test
    void mapClientKeepsPublicMapWhenShipmentEndpointRequiresLogin() throws IOException {
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);

        assertTrue(mapJs.contains("fetchOptionalJson(\"/api/map/shipments\""),
                "shipments should be optional so a guest 401 cannot black out public tiles");
        assertFalse(mapJs.contains("fetchJson(\"/api/map/shipments\")"),
                "initial and refresh shipment fetches must not throw through the public map loader");
    }

    @Test
    void mapWorkspaceHasCollapsibleSidePanels() throws IOException {
        String appJs = Files.readString(APP_JS, StandardCharsets.UTF_8);
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);
        String appCss = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        assertTrue(appJs.contains("data-map-panel-toggle=\"left\""),
                "left map panel should expose a collapse button");
        assertTrue(appJs.contains("data-map-panel-toggle=\"right\""),
                "right map panel should expose a collapse button");
        assertTrue(mapJs.contains("togglePanel("),
                "map.js should wire panel collapse controls");
        assertTrue(appCss.contains(".market-map-workspace.map-panel-left-collapsed"),
                "left collapsed layout should give space back to the map");
        assertTrue(appCss.contains(".market-map-workspace.map-panel-right-collapsed"),
                "right collapsed layout should give space back to the map");
    }

    @Test
    void mapJavaScriptParsesInBrowserRuntime() throws Exception {
        Process process = new ProcessBuilder("node", "--check", MAP_JS.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS),
                "node --check timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
    }

    @Test
    void mapChineseLocaleContainsReadableLabels() throws IOException {
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);

        assertTrue(mapJs.contains("terrain: \"地形\""),
                "map Chinese locale should render readable terrain text");
        assertTrue(mapJs.contains("territories: \"领地\""),
                "map Chinese locale should render readable territory text");
        assertFalse(mapJs.matches("(?s).*[\u935c\u3127\u568e\u752f\u50da].*"),
                "map Chinese locale still contains mojibake");
    }

    @Test
    void mapDrawsPixelAlignedTilesAndExternalTerritoryBordersOnly() throws IOException {
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);

        assertTrue(mapJs.contains("drawPixelAlignedTile("),
                "canvas tile layer should draw tiles on integer device pixels to avoid seams");
        assertTrue(mapJs.contains("TILE_OVERDRAW_PIXELS"),
                "canvas tile layer should overdraw adjacent tiles slightly so scaled images cannot expose black seams");
        assertTrue(mapJs.contains("hasAdjacentTerritory("),
                "territory overlay should detect neighbor chunks");
        assertFalse(mapJs.contains("ctx.strokeRect(screen.x + 0.5"),
                "territory overlay must not draw a grid line around every owned chunk");
    }

    @Test
    void mapClientUsesMobilePerformanceBudget() throws IOException {
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);
        String appCss = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        assertTrue(mapJs.contains("function isMobileMapDevice("),
                "map client should detect touch/mobile devices");
        assertTrue(mapJs.contains("MOBILE_TILE_CACHE_LIMIT"),
                "mobile map should keep fewer decoded tile images in memory");
        assertTrue(mapJs.contains("function visibleTilePadding("),
                "mobile map should reduce extra off-screen tile prefetch");
        assertTrue(mapJs.contains("function canvasPixelRatio("),
                "mobile map should cap canvas DPR separately from desktop");
        assertTrue(mapJs.contains("map-performance-lite"),
                "map root should expose a class for CSS performance fallbacks");
        assertTrue(appCss.contains(".map-performance-lite .map-canvas-shell::after"),
                "runtime mobile mode should disable expensive map glass overlays");
        assertTrue(appCss.contains("@media (hover: none), (pointer: coarse)"),
                "CSS should also disable expensive map effects for touch browsers before JS runs");
    }

    @Test
    void mapTilesUseRefreshKeyAndServerOffersFullCacheReset() throws IOException {
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);
        String server = Files.readString(MARKET_WEB_SERVER, StandardCharsets.UTF_8);
        String commands = Files.readString(MARKET_WEB_COMMANDS, StandardCharsets.UTF_8);

        assertTrue(mapJs.contains("function tileRefreshKey("),
                "map client should derive a stable refresh key for tile image URLs");
        assertTrue(mapJs.contains("?v=${encodeURIComponent(tileRefreshKey())}"),
                "map tile image URLs need a cache-busting render/snapshot version query");
        assertTrue(server.contains("\"Cache-Control\", \"no-cache, max-age=0\""),
                "tile endpoint should require revalidation after map cache resets");
        assertTrue(commands.contains("clearall"),
                "admin map commands should expose a full tile cache clear for bad current-version server tiles");
        assertTrue(commands.contains("clearAllTiles()"),
                "clearall command should delete all cached tile PNG/metadata pairs");
    }

    @Test
    void mapRepaintAllDoesNotForceLoadRegionChunks() throws IOException {
        String commands = Files.readString(MARKET_WEB_COMMANDS, StandardCharsets.UTF_8);

        assertFalse(commands.contains("enqueueRegionForceRepairScan"),
                "repaintall must not force-load large region ranges from the server thread");
        assertTrue(commands.contains("enqueueRegionRepairScan"),
                "repaintall should only queue already-loaded chunks for immediate repaint");
    }

    @Test
    void mapClientUsesSquaremapStyleTileEndpoint() throws IOException {
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);
        String server = Files.readString(MARKET_WEB_SERVER, StandardCharsets.UTF_8);

        assertTrue(mapJs.contains("/api/map/square/tile/overworld/"),
                "terrain tile layer should use squaremap-style 512 tile endpoint");
        assertTrue(mapJs.contains("function activeTileZoom("),
                "client should choose a square tile zoom level instead of stretching one LOD everywhere");
        assertTrue(mapJs.contains("const TILE_SIZE = 512"),
                "terrain tile grid should use squaremap-style 512px base tiles");
        assertTrue(mapJs.contains("MISSING_TILE_RETRY_MS"),
                "missing square tiles should be retried after the backend render queue has time to fill them");
        assertTrue(server.contains("createContext(\"/api/map/square/tile\""),
                "server should expose the square tile endpoint");
        assertFalse(mapJs.contains("/api/map/tile/lod_1/${tileX}/${tileZ}.png"),
                "old 256 lod_1 endpoint should not be the primary terrain source");
    }

    @Test
    void mapClientUsesSquaremapLeafletTileRenderer() throws IOException {
        String index = Files.readString(INDEX_HTML, StandardCharsets.UTF_8);
        String appJs = Files.readString(APP_JS, StandardCharsets.UTF_8);
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);
        String appCss = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        assertTrue(Files.isRegularFile(Path.of("src/main/resources/marketweb/vendor/leaflet/leaflet.js")),
                "Leaflet runtime should be vendored locally");
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/marketweb/vendor/leaflet/leaflet.css")),
                "Leaflet CSS should be vendored locally");
        assertTrue(index.contains("/vendor/leaflet/leaflet.css"));
        assertTrue(index.contains("/vendor/leaflet/leaflet.js"));
        assertTrue(appJs.contains("data-square-map"),
                "map host should expose a dedicated squaremap/Leaflet terrain container");
        assertTrue(appJs.contains("window.SailboatSquareMap || window.SailboatMarketMap"),
                "app should prefer the squaremap renderer while preserving fallback compatibility");
        assertTrue(mapJs.contains("L.map(state.mapElement"),
                "map client should create a Leaflet map for terrain rendering");
        assertTrue(mapJs.contains("crs: L.CRS.Simple"),
                "Leaflet map should use squaremap's simple CRS world projection");
        assertTrue(mapJs.contains("L.TileLayer.extend"),
                "terrain layer should use squaremap's custom fetch/blob tile layer pattern");
        assertTrue(mapJs.contains("URL.revokeObjectURL"),
                "blob tile URLs should be revoked after load like squaremap");
        assertTrue(mapJs.contains("errorTileUrl"),
                "missing squaremap tiles should render as transparent placeholders instead of broken images");
        assertFalse(mapJs.contains("fetch(this.getTileUrl(coords), { cache: \"no-store\" })"),
                "squaremap tile fetches should allow normal browser caching and rely on the render-version query");
        assertTrue(appCss.contains(".squaremap-leaflet"),
                "Leaflet terrain container needs scoped CSS below the overlay canvas");
    }

    @Test
    void mapClientUsesSquaremapDoubleBufferedTileLayers() throws IOException {
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);

        assertTrue(mapJs.contains("leafletLayers"),
                "squaremap terrain should keep two Leaflet tile layers for seamless redraws");
        assertTrue(mapJs.contains("function switchSquaremapLayer("),
                "inactive squaremap tile layer should be promoted only after it loads");
        assertTrue(mapJs.contains("setZIndex"),
                "squaremap layer swapping should use z-index instead of clearing the visible layer");
        assertTrue(mapJs.contains("function redrawSquaremapTerrain("),
                "tile refresh should redraw the inactive layer rather than flashing the active layer");
    }

    @Test
    void marketWebJarCanBundleGeneratedMinecraftIconAssets() throws IOException {
        String build = Files.readString(BUILD_GRADLE, StandardCharsets.UTF_8);

        assertTrue(build.contains("bundleMarketWebMinecraftAssets"),
                "build should expose a task for copying vanilla icon assets into generated resources");
        assertTrue(build.contains("marketWebMinecraftAssetsSource"),
                "build should allow selecting the vanilla asset source path with a project property");
        assertTrue(build.contains("assets/minecraft/**"),
                "market web jar should include generated vanilla minecraft assets when present");
        assertTrue(build.contains("marketweb-minecraft-assets"),
                "local vanilla asset staging directories should be documented in the build script");
    }

    @Test
    void mapFlagsAndCommodityIconsRecoverFromMissingImages() throws IOException {
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);
        String appJs = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertTrue(mapJs.contains("data-map-flag-image"),
                "territory tooltip flag images should be detectable for load-error fallback");
        assertTrue(mapJs.contains("hideBrokenFlag"),
                "broken territory flag images should hide instead of showing browser broken-image UI");
        assertFalse(appJs.contains("cached?.status !== \"batch-missing\""),
                "batch-missing icon responses should still allow the single icon endpoint probe");
    }

    @Test
    void webMapDoesNotUseClientUploadedTiles() throws IOException {
        String uploadClient = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/marketweb/MarketWebMapTileUploadClient.java"), StandardCharsets.UTF_8);
        String uploadService = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileUploadService.java"), StandardCharsets.UTF_8);

        assertTrue(uploadClient.contains("CLIENT_UPLOADS_ENABLED = false"),
                "client road-planner tiles should not be uploaded into the web map cache");
        assertTrue(uploadService.contains("CLIENT_UPLOADS_ENABLED = false"),
                "server should reject client tile uploads from older clients too");
        assertTrue(uploadService.contains("SERVER_SYNC_TILES_ENABLED = false"),
                "server road-planner preload tiles should not write into the squaremap web cache");
        assertTrue(uploadService.contains("return Result.DISABLED;"),
                "disabled map upload paths should report disabled instead of silently writing stale tiles");
    }

    @Test
    void serverWebMapUsesSquaremapStyleBackgroundRenderingWithoutForceLoading() throws IOException {
        String renderService = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java"), StandardCharsets.UTF_8);

        assertFalse(renderService.contains("ForgeChunkManager"),
                "web map rendering must not force-load chunks");
        assertFalse(renderService.contains("forceChunk"),
                "web map rendering must not call forceChunk");
        assertFalse(renderService.contains("forcedChunks"),
                "the old force-load chunk queue should be removed");
        assertTrue(renderService.contains("MarketWebSquareMapRenderer"),
                "terrain rendering should go through the squaremap-style background renderer");
        assertTrue(renderService.contains("addRegionCursor"),
                "tile misses and region changes should enqueue region cursors instead of scanning full regions immediately");
    }

    @Test
    void serverWebMapUsesGeneratedRegionSnapshotsLikeSquaremap() throws IOException {
        String snapshot = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapChunkSnapshot.java"), StandardCharsets.UTF_8);
        String renderService = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java"), StandardCharsets.UTF_8);
        String renderManager = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderManager.java"), StandardCharsets.UTF_8);
        String queue = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderQueue.java"), StandardCharsets.UTF_8);

        assertTrue(snapshot.contains("captureGenerated"),
                "web map needs a generated-chunk snapshot path");
        assertTrue(snapshot.contains("readGeneratedChunkTag"),
                "generated snapshot path should inspect saved chunk data before accepting work");
        assertTrue(snapshot.contains("MarketWebMapNbtChunkSnapshotReader.capture"),
                "generated snapshot path should render generated chunk NBT offline instead of skipping it");
        assertFalse(snapshot.contains("getChunkFuture"),
                "web map generated snapshots must not schedule chunk loads from map rendering");
        assertFalse(snapshot.contains("ChunkStatus.EMPTY"),
                "web map generated snapshots must not use chunk futures as a storage read shortcut");
        assertFalse(snapshot.contains(".join()"),
                "generated snapshot reads should not block the server tick waiting for disk IO");
        // 崩服根因修复:后台 decode 共享 CompoundTag 的路径已删,生成区块改为主线程 probe + force-load 采样(squaremap 模式)。
        assertTrue(snapshot.contains("captureGeneratedViaForce"),
                "generated chunks must be sampled on the main thread via a force-load, never decoded off-thread");
        assertTrue(snapshot.contains("ForgeChunkManager.forceChunk"),
                "force-load is the only safe way to materialize a generated chunk before main-thread sampling");
        assertFalse(renderService.contains("pendingGeneratedReads"),
                "off-thread generated NBT read queue must be removed (it decoded shared CompoundTags off-thread and crashed the server)");
        assertFalse(renderService.contains("enqueueGeneratedRead"),
                "render service must not enqueue async generated chunk NBT reads anymore");
        assertFalse(renderService.contains("thenApplyAsync"),
                "render service must never decode chunk NBT off the server thread");
        assertFalse(renderService.contains("generatedDecodeExecutor"),
                "the off-thread generated decode executor must be removed");
        assertTrue(renderManager.contains("resolveGenerated"),
                "unloaded chunks must be probed on the main thread for a generated status before force-loading");
        assertTrue(renderManager.contains("captureGeneratedViaForce"),
                "generated unloaded chunks must be force-loaded and sampled on the main thread");
        assertTrue(renderManager.contains("getString(\"Status\")"),
                "the generated probe must read only the Status field, never decode the whole chunk tag");
        assertTrue(renderManager.contains("GENERATED_SAMPLES_PER_TICK"),
                "main-thread force-load sampling must be throttled per tick so a full render cannot stall the server");
        assertTrue(queue.contains("MarketWebMapTileQuality quality"),
                "render tasks should preserve whether work came from loaded chunks or region scans");
        assertTrue(renderService.contains("MarketWebMapTileQuality.SERVER_REGION_SCAN"),
                "region cursors should enqueue server_region_scan work so it can overwrite stale loaded/client tiles");
        assertTrue(renderService.contains("squareTileCursors"),
                "missing square tiles should register bounded tile-local cursors instead of only checking currently loaded chunks");
    }

    @Test
    void serverWebMapHasSquaremapStyleManagersAndRenderStatusApi() throws IOException {
        String renderService = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java"), StandardCharsets.UTF_8);
        String renderManager = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderManager.java"), StandardCharsets.UTF_8);
        String imageIo = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebSquareMapImageIOExecutor.java"), StandardCharsets.UTF_8);
        String server = Files.readString(MARKET_WEB_SERVER, StandardCharsets.UTF_8);
        String service = Files.readString(MARKET_WEB_SERVICE, StandardCharsets.UTF_8);
        String commands = Files.readString(MARKET_WEB_COMMANDS, StandardCharsets.UTF_8);
        String config = Files.readString(MOD_CONFIG, StandardCharsets.UTF_8);

        assertTrue(Files.isRegularFile(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapSnapshotManager.java")),
                "web map needs a Squaremap-style snapshot manager with FIFO cache and request dedupe");
        assertTrue(Files.isRegularFile(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRegionImage.java")),
                "web map needs a 512x512 region image model instead of repeated per-chunk PNG merging");
        assertTrue(Files.isRegularFile(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRegionRenderer.java")),
                "web map needs a region renderer that keeps continuous lastY height shading");
        assertTrue(Files.isRegularFile(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderManager.java")),
                "web map needs a render lifecycle manager for full/radius/background jobs");
        assertTrue(Files.isRegularFile(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapDirtyChunkQueue.java")),
                "web map needs a persistent dirty chunk queue like squaremap");
        assertTrue(Files.isRegularFile(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapDirtyRegionQueue.java")),
                "web map needs a persistent dirty region queue so region watcher events do not expand into 1024 chunks immediately");
        assertTrue(Files.isRegularFile(Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapSpiralChunkIterator.java")),
                "radius render should use a center-out chunk spiral instead of rendering whole region bounding boxes");
        assertTrue(renderService.contains("MarketWebMapRenderManager"),
                "tick service should delegate long-running Squaremap-style jobs to the render manager");
        assertTrue(server.contains("createContext(\"/api/map/render/status\""),
                "server should expose a public read-only render status endpoint");
        assertTrue(server.contains("handleMapRenderStatus"),
                "server should handle render status separately from tile reads");
        assertTrue(service.contains("mapRenderStatus("),
                "service should expose render status JSON");
        assertTrue(commands.contains("fullrender"),
                "admin commands should expose fullrender");
        assertTrue(commands.contains("radiusrender"),
                "admin commands should expose radiusrender");
        assertTrue(commands.contains("arearender"),
                "admin commands should expose arearender");
        assertTrue(commands.contains("borderrender"),
                "admin commands should expose borderrender");
        assertTrue(commands.contains("pause") && commands.contains("resume") && commands.contains("cancel"),
                "admin commands should expose pause/resume/cancel for long renders");
        assertTrue(commands.contains("Dirty regions/chunks") && commands.contains("currentRegionX()"),
                "admin render status should expose dirty queue and current cursor diagnostics");
        assertTrue(renderService.contains("loadDirtyChunks") && renderService.contains("saveDirtyChunks"),
                "render service should persist dirty chunk state across restarts");
        assertTrue(renderService.contains("markRegionDirty"),
                "region watcher events should feed the persistent dirty chunk queue");
        assertTrue(renderManager.contains("writeDirty"),
                "region output should write partial images instead of waiting for all 1024 chunks");
        assertTrue(renderManager.contains("squareSpiral"),
                "radiusrender should enqueue precise spiral chunks rather than a region bounding box");
        assertTrue(renderManager.contains("RenderJob.full(regions)") && renderManager.contains("regionIndex") && renderManager.contains("localChunkIndex"),
                "fullrender should persist a streaming region/local chunk cursor instead of retaining every chunk coordinate");
        assertFalse(renderManager.contains("RenderJob.full(chunksForRegions(regions))"),
                "fullrender must not eagerly expand all region files into one huge chunk list");
        assertTrue(renderManager.contains("dirtyRegions"),
                "dirty region state should be tracked separately from dirty chunks");
        assertTrue(renderManager.contains("failedChunks"),
                "render status should expose failed snapshot/read attempts for long-running diagnostics");
        assertTrue(renderManager.contains("currentRegionX") && renderManager.contains("currentRegionZ") && renderManager.contains("currentLocalChunk"),
                "render status should expose the current fullrender cursor");
        assertTrue(renderManager.contains("marketWebMaxTrackedRegionStates"),
                "tracked in-memory partial region states should be bounded by config");
        assertTrue(imageIo.contains("marketWebImageIoBacklogWarningThreshold"),
                "ImageIO backlog warning threshold should be configurable");
        assertTrue(config.contains("webMapRenderThreads"),
                "config should expose webMapRenderThreads");
        assertTrue(config.contains("webMapSnapshotCacheSize"),
                "config should expose webMapSnapshotCacheSize");
        assertTrue(config.contains("webMapMaxActiveSnapshotRequests"),
                "config should expose webMapMaxActiveSnapshotRequests");
        assertTrue(config.contains("webMapBackgroundMaxChunksPerInterval"),
                "config should expose webMapBackgroundMaxChunksPerInterval");
        assertTrue(config.contains("webMapBackgroundIntervalTicks"),
                "config should expose webMapBackgroundIntervalTicks");
        assertTrue(config.contains("webMapBiomeColorsEnabled"),
                "config should expose webMapBiomeColorsEnabled");
        assertTrue(config.contains("webMapPartialRegionFlushChunks"),
                "config should expose webMapPartialRegionFlushChunks");
        assertTrue(config.contains("webMapMaxTrackedRegionStates"),
                "config should expose webMapMaxTrackedRegionStates");
        assertTrue(config.contains("webMapMaxDirtyChunks"),
                "config should expose webMapMaxDirtyChunks");
        assertTrue(config.contains("webMapDirtyRegionChunksPerInterval"),
                "config should expose webMapDirtyRegionChunksPerInterval");
        assertTrue(config.contains("webMapProgressSaveIntervalChunks"),
                "config should expose webMapProgressSaveIntervalChunks");
        assertTrue(config.contains("webMapImageIoBacklogWarningThreshold"),
                "config should expose webMapImageIoBacklogWarningThreshold");
        assertTrue(config.contains("webMapSnapshotTasksPerTick"),
                "config should expose webMapSnapshotTasksPerTick");
        assertTrue(config.contains("webMapSameTileBurstLimit"),
                "config should expose webMapSameTileBurstLimit");
        assertFalse(renderService.contains("getChunkFuture"),
                "render service must not use chunk futures that can force chunk loads");
        assertFalse(renderService.contains("forceChunk"),
                "render service must not force-load chunks");
    }

    @Test
    void demandWorkspaceUsesFocusedBuyOrderLayoutOnly() throws IOException {
        String appJs = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertTrue(appJs.contains("function renderDemandOnlyPage("),
                "standalone demand workspace should have a focused renderer");
        assertTrue(appJs.contains("state.activeProductTab === \"demand\"")
                        && appJs.contains("renderDemandOnlyPage(commodity, canAct"),
                "demand tab should bypass the full commodity buying layout");
        assertTrue(appJs.contains("demand-only-grid"),
                "demand page should use a compact two-card layout");
    }

    @Test
    void mapSelectionDetailShowsFixedTerritoryFlagFrame() throws IOException {
        String mapJs = Files.readString(MAP_JS, StandardCharsets.UTF_8);
        String appCss = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        assertTrue(mapJs.contains("map-detail-flag-frame"),
                "territory detail panel should render a fixed flag frame");
        assertTrue(mapJs.contains("territory.flagUrl ? `<img data-map-flag-image"),
                "territory detail panel should use the same flagUrl as map tooltips");
        assertTrue(mapJs.contains("bindFlagFallbacks(state.detailPanel)"),
                "detail panel flag should register the broken-image fallback");
        assertTrue(appCss.contains(".map-detail-flag-frame"),
                "detail flag frame needs fixed CSS sizing");
        assertTrue(appCss.contains(".map-detail-flag-frame img"),
                "detail flag image should be constrained inside the fixed frame");
    }

    @Test
    void expandedAccessToggleKeepsReadableTextContrast() throws IOException {
        String appCss = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        assertTrue(appCss.contains(".access-toggle[aria-expanded=\"true\"] strong,\n.access-toggle[aria-expanded=\"true\"] .hero-chip-label"),
                "expanded login toggle needs an explicit final text color rule for contrast on light glass backgrounds");
    }

    @Test
    void goodsCardsUseLiquidGlassFlowWithReducedMotionFallback() throws IOException {
        String appCss = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        assertTrue(appCss.contains(".goods-card::after,\n.map-side-panel::after"),
                "commodity cards should share the animated liquid-glass refraction layer");
        assertTrue(appCss.contains(".goods-card > *,\n.map-side-panel > *"),
                "commodity card content should stay above the liquid-glass overlay");
        assertTrue(appCss.contains(".goods-card::after {\n    opacity: 0.16;"),
                "commodity cards need a restrained liquid-glass opacity so text and icons remain readable");
        assertTrue(appCss.contains(".goods-card::after,\n    .map-side-panel::after"),
                "commodity card liquid-glass animation should be disabled by prefers-reduced-motion");
    }

    @Test
    void marketDetailExposesAndDisplaysWalletBalance() throws IOException {
        String service = Files.readString(MARKET_WEB_SERVICE, StandardCharsets.UTF_8);
        String appJs = Files.readString(APP_JS, StandardCharsets.UTF_8);
        String indexHtml = Files.readString(INDEX_HTML, StandardCharsets.UTF_8);
        String appCss = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        assertTrue(service.contains("root.addProperty(\"walletBalance\""),
                "market detail JSON should keep walletBalance as the available market-wallet balance for compatibility");
        assertTrue(service.contains("root.addProperty(\"walletAvailableBalance\""),
                "market detail JSON should expose available market-wallet balance explicitly");
        assertTrue(service.contains("root.addProperty(\"walletReservedBalance\""),
                "market detail JSON should expose reserved market-wallet balance");
        assertTrue(service.contains("root.addProperty(\"treasuryBalance\""),
                "market detail JSON should expose the linked town/nation treasury balance");
        assertTrue(service.contains("root.addProperty(\"cashBalance\""),
                "market detail JSON should expose cash/plugin balance separately from market wallet");
        assertTrue(service.contains("GoldStandardEconomy.getBalance"),
                "cashBalance should still use the physical/Vault gold-standard balance");
        assertTrue(appJs.contains("wallet_balance: \"My wallet\""),
                "English locale should label the wallet as the player's own, not a shared market account");
        assertTrue(appJs.contains("wallet_balance: \"\u6211\u7684\u94b1\u5305\""),
                "Chinese locale should label the wallet as the player's own, not a shared market account");
        assertTrue(appJs.contains("wallet_balance_hint"),
                "the UI should explain purchases and buy orders use this wallet");
        assertTrue(appJs.contains("wallet_transfer_placeholder: \"输入转账金额\""),
                "Chinese wallet transfer input should use a clear placeholder instead of a bare number");
        assertTrue(appJs.contains("placeholder=\"${escapeHtml(t(\"wallet_transfer_placeholder\"))}\""),
                "wallet transfer amount input should show the localized description as placeholder text");
        assertFalse(appJs.contains("placeholder=\"100\""),
                "wallet transfer amount input should not show an unexplained bare 100 placeholder");
        assertTrue(appJs.contains("metricBox(t(\"wallet_balance\"), number(detail.walletBalance))"),
                "market overview metrics should render walletBalance");
        assertTrue(indexHtml.contains("id=\"wallet-dock\""),
                "wallet controls should live in the top login area instead of the market detail body");
        assertTrue(appJs.contains("walletDock: document.querySelector(\"#wallet-dock\")"),
                "app should keep a handle to the topbar wallet dock");
        assertTrue(appJs.contains("function renderTopbarWallet("),
                "topbar wallet should have a dedicated compact renderer");
        assertTrue(appJs.contains("marketWebWalletCollapsed"),
                "wallet collapsed state should persist across page refreshes");
        assertFalse(appJs.contains("${renderWalletTransferPanel(detail, canAct)}"),
                "market detail should not insert the transfer panel as a large body block");
        assertTrue(appCss.contains(".wallet-dock"),
                "topbar wallet needs dedicated compact layout CSS");
        assertTrue(appCss.contains(".wallet-dock-panel[hidden]"),
                "wallet transfer controls should be collapsible in the topbar");
        assertTrue(appCss.contains(".wallet-dock-card {\n    position: relative;\n    display: grid;\n    overflow: hidden;"),
                "topbar wallet should render as one connected compact card instead of a detached popover");
        assertTrue(appCss.contains(".wallet-dock-panel {\n    position: static;"),
                "expanded wallet controls should stay connected to the wallet card");
        assertFalse(appCss.contains(".wallet-dock-panel {\n    position: absolute;"),
                "expanded wallet controls should not float as a detached absolute panel");
        assertTrue(appCss.contains(".wallet-dock .wallet-dock-toggle {"),
                "wallet toggle needs a scoped override so global button liquid styles cannot turn it into a blue block");
        assertTrue(appCss.contains(".wallet-dock .wallet-dock-buttons button {"),
                "wallet transfer buttons need compact scoped styles instead of the large global button treatment");
        assertTrue(appJs.contains("postMarketAction(\"/wallet/transfer\""),
                "wallet transfer buttons should post to the transfer endpoint");
    }

    @Test
    void buyOrderCreationAndCancelUseFundedWalletPath() throws IOException {
        String service = Files.readString(MARKET_WEB_SERVICE, StandardCharsets.UTF_8);
        String createPacket = Files.readString(CREATE_BUY_ORDER_PACKET, StandardCharsets.UTF_8);
        String cancelPacket = Files.readString(CANCEL_BUY_ORDER_PACKET, StandardCharsets.UTF_8);

        assertTrue(service.contains("MarketWalletService.reserve("),
                "web buy order creation must reserve market-wallet balance");
        assertTrue(service.contains("createReservedBuyOrder("),
                "web buy order creation must persist the reserved wallet amount on the order");
        assertTrue(service.contains("cancelBuyOrderForBuyerReturningOrder("),
                "web buy order cancel must load the canceled order before refunding reserved balance");
        assertTrue(service.contains("MarketWalletService.releaseReserved("),
                "web buy order cancel must refund reserved balance to the owner");
        assertTrue(createPacket.contains("MarketWalletService.reserve("),
                "in-game buy order packet must reserve market-wallet balance");
        assertTrue(createPacket.contains("createReservedBuyOrder("),
                "in-game buy order packet must use the same reserved creation path as web");
        assertTrue(cancelPacket.contains("cancelBuyOrderForBuyerReturningOrder("),
                "in-game buy order cancel packet must load the canceled order before refunding");
        assertTrue(cancelPacket.contains("MarketWalletService.releaseReserved("),
                "in-game buy order cancel packet must refund reserved balance");
    }

    private static String methodBody(String source, String methodName, String nextMethodName) {
        int start = source.indexOf("private void " + methodName);
        int end = source.indexOf("private void " + nextMethodName, start);
        assertTrue(start >= 0 && end > start, methodName + " method should be present");
        return source.substring(start, end);
    }
}
