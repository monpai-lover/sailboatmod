# Squaremap Market Web Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the market web map around a squaremap-style tile/layer architecture while preserving Sailboat market, nation, flag, wallet, and logistics behavior.

**Architecture:** The browser map becomes a Leaflet-based map shell with squaremap-style tile layers and overlay layer groups. The server map cache moves toward 512x512 region-backed tiles with zoom pyramid writes and dirty-chunk background rendering. Existing `/api/map/*` endpoints stay compatible during the migration so the market UI and public map access keep working.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Java `ImageIO`, existing `HttpServer`, static marketweb HTML/CSS/JS, local vendored Leaflet 1.9.4 assets adapted from squaremap's frontend pattern.

---

## File Structure

- Create `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCoordinate.java`
  - Holds tile coordinate conversion helpers for 512x512 squaremap-style region tiles and legacy 256x256 compatibility tiles.
- Create `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapPyramidWriter.java`
  - Writes one dirty chunk into base zoom and derived zoom PNGs using squaremap's "read existing image, patch pixels, atomic write" approach.
- Modify `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCache.java`
  - Keep legacy `lod_1` read/write API.
  - Add squaremap-style `readSquareTile(dimension, zoom, x, z)` and `mergeChunkPyramid(...)`.
- Modify `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java`
  - Process more chunks per tick without forcing synchronous work.
  - Coalesce dirty chunks by tile/region before writing.
  - Use `MarketWebMapPyramidWriter` for server-rendered chunks.
- Modify `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
  - Serve `GET /api/map/square/tile/{dimension}/{zoom}/{x}_{z}.png`.
  - Keep `GET /api/map/tile/lod_1/{x}/{z}.png` as compatibility.
- Create `src/main/resources/marketweb/vendor/leaflet/leaflet.js`
  - Vendored Leaflet runtime, local only, no CDN dependency.
- Create `src/main/resources/marketweb/vendor/leaflet/leaflet.css`
  - Vendored Leaflet styles with marker image URLs adjusted to local paths.
- Create `src/main/resources/marketweb/squaremap.js`
  - Leaflet map shell inspired by squaremap: CRS.Simple, coordinate conversion, double tile layer refresh, overlay groups.
- Modify `src/main/resources/marketweb/map.js`
  - Defer to `SailboatSquareMap` when available; keep old canvas map as fallback during migration.
- Modify `src/main/resources/marketweb/app.js`
  - Map tab renders the Leaflet host node and the existing side panel containers.
- Modify `src/main/resources/marketweb/app.css`
  - Add squaremap-style map layout, light glass panels, mobile performance mode, and Leaflet overrides.
- Modify `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`
  - Assert local Leaflet assets exist, no CDN is required, and map JS parses.
- Create `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCoordinateTest.java`
  - Locks coordinate math.
- Create `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapPyramidWriterTest.java`
  - Locks zoom pyramid writes and dirty chunk patching.

## Task 1: Tile Coordinate Model

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCoordinate.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCoordinateTest.java`

- [ ] **Step 1: Write the failing coordinate tests**

Add tests that make the new squaremap tile math explicit:

```java
package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketWebMapTileCoordinateTest {
    @Test
    void baseTileContainsThirtyTwoByThirtyTwoChunks() {
        MarketWebMapTileCoordinate.Tile tile = MarketWebMapTileCoordinate.baseTileForChunk(33, -1);

        assertEquals(1, tile.x());
        assertEquals(-1, tile.z());
        assertEquals(1, MarketWebMapTileCoordinate.localChunkX(33));
        assertEquals(31, MarketWebMapTileCoordinate.localChunkZ(-1));
    }

    @Test
    void blockLocalPositionMapsIntoBaseTilePixel() {
        assertEquals(16, MarketWebMapTileCoordinate.basePixelX(33, 0));
        assertEquals(511, MarketWebMapTileCoordinate.basePixelZ(-1, 15));
    }

    @Test
    void zoomTileScalesBaseTileLikeSquaremap() {
        MarketWebMapTileCoordinate.Tile scaled = MarketWebMapTileCoordinate.scaleTile(3, -2, 1);

        assertEquals(1, scaled.x());
        assertEquals(-1, scaled.z());
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.map.MarketWebMapTileCoordinateTest --console=plain
```

Expected: compile fails because `MarketWebMapTileCoordinate` does not exist.

- [ ] **Step 3: Implement the coordinate helper**

Create `MarketWebMapTileCoordinate` with:

```java
package com.monpai.sailboatmod.market.web.map;

public final class MarketWebMapTileCoordinate {
    public static final int BASE_TILE_SIZE = 512;
    public static final int CHUNK_SIZE = 16;
    public static final int CHUNKS_PER_BASE_TILE_AXIS = BASE_TILE_SIZE / CHUNK_SIZE;

    private MarketWebMapTileCoordinate() {
    }

    public static Tile baseTileForChunk(int chunkX, int chunkZ) {
        return new Tile(Math.floorDiv(chunkX, CHUNKS_PER_BASE_TILE_AXIS),
                Math.floorDiv(chunkZ, CHUNKS_PER_BASE_TILE_AXIS));
    }

    public static int localChunkX(int chunkX) {
        return Math.floorMod(chunkX, CHUNKS_PER_BASE_TILE_AXIS);
    }

    public static int localChunkZ(int chunkZ) {
        return Math.floorMod(chunkZ, CHUNKS_PER_BASE_TILE_AXIS);
    }

    public static int basePixelX(int chunkX, int localBlockX) {
        return localChunkX(chunkX) * CHUNK_SIZE + Math.floorMod(localBlockX, CHUNK_SIZE);
    }

    public static int basePixelZ(int chunkZ, int localBlockZ) {
        return localChunkZ(chunkZ) * CHUNK_SIZE + Math.floorMod(localBlockZ, CHUNK_SIZE);
    }

    public static Tile scaleTile(int baseTileX, int baseTileZ, int zoom) {
        int divisor = 1 << Math.max(0, zoom);
        return new Tile(Math.floorDiv(baseTileX, divisor), Math.floorDiv(baseTileZ, divisor));
    }

    public record Tile(int x, int z) {
    }
}
```

- [ ] **Step 4: Run the coordinate test and verify GREEN**

Run the same test command. Expected: PASS.

## Task 2: Fast Square Tile Pyramid Writer

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapPyramidWriter.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCache.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapPyramidWriterTest.java`

- [ ] **Step 1: Write failing pyramid tests**

Test that one 16x16 server chunk patches a 512x512 base tile and updates zoom 1:

```java
package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapPyramidWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void mergeChunkWritesBaseAndScaledTiles() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        int[] chunk = new int[16 * 16];
        Arrays.fill(chunk, 0xFF3F76C4);

        assertTrue(cache.mergeChunkPyramid("minecraft:overworld", 33, -1, chunk,
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 100L));

        byte[] baseBytes = cache.readSquareTile("minecraft:overworld", 0, 1, -1).orElseThrow();
        BufferedImage base = ImageIO.read(new ByteArrayInputStream(baseBytes));
        assertEquals(512, base.getWidth());
        assertEquals(512, base.getHeight());
        assertEquals(0xFF3F76C4, base.getRGB(16, 496));

        byte[] zoomBytes = cache.readSquareTile("minecraft:overworld", 1, 0, -1).orElseThrow();
        BufferedImage zoom = ImageIO.read(new ByteArrayInputStream(zoomBytes));
        assertEquals(512, zoom.getWidth());
        assertEquals(512, zoom.getHeight());
        assertEquals(0xFF3F76C4, zoom.getRGB(264, 504));
    }
}
```

- [ ] **Step 2: Run the pyramid test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.map.MarketWebMapPyramidWriterTest --console=plain
```

Expected: compile fails because `mergeChunkPyramid` and `readSquareTile` do not exist.

- [ ] **Step 3: Implement the writer**

Implement `MarketWebMapPyramidWriter` with:
- `MAX_ZOOM = 4` initially. This covers world-scale viewing without producing too many files.
- `mergeChunk(...)` loads or creates the base 512 PNG, writes the chunk pixels, then writes scaled zoom tiles.
- For speed, only touch zoom tiles whose source base tile changed.
- Use atomic writes like existing `MarketWebMapTileCache.writeBytesAtomically`.
- Skip byte-identical writes to reduce disk churn.

The implementation must use `BufferedImage.TYPE_INT_ARGB`, `setRGB`, and `getRGB`; it must not use `NativeImage` byte order.

- [ ] **Step 4: Add cache facade methods**

Add to `MarketWebMapTileCache`:

```java
public Optional<byte[]> readSquareTile(String dimensionId, int zoom, int tileX, int tileZ)
public boolean mergeChunkPyramid(String dimensionId, int chunkX, int chunkZ, int[] chunkPixels,
                                 MarketWebMapTileQuality quality, long nowMillis)
```

Store tiles under:

```text
<cache-root>/overworld/square/<zoom>/<x>_<z>.png
```

Keep existing `overworld/lod_1/<x>_<z>.png` behavior unchanged.

- [ ] **Step 5: Run tile cache tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.map.MarketWebMapTileCacheTest --tests com.monpai.sailboatmod.market.web.map.MarketWebMapPyramidWriterTest --console=plain
```

Expected: PASS.

## Task 3: Faster Render Queue Processing

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderQueue.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderQueueTest.java`

- [ ] **Step 1: Write failing queue coalescing test**

Add:

```java
@Test
void queuePollsMoreTasksWhenTheyBelongToSameBaseTile() {
    MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(512);
    for (int i = 0; i < 16; i++) {
        assertTrue(queue.enqueue("minecraft:overworld", i, 0, i));
    }

    List<MarketWebMapRenderQueue.Task> tasks = queue.pollCoalesced(4, 16, 20L);

    assertEquals(16, tasks.size(), "same tile work should batch together for faster PNG writes");
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.map.MarketWebMapRenderQueueTest --console=plain
```

Expected: compile fails because `pollCoalesced` does not exist.

- [ ] **Step 3: Implement coalesced polling**

Add:

```java
public synchronized List<Task> pollCoalesced(int minimumBudget, int sameTileBurstLimit, long nowMillis)
```

Behavior:
- Always poll at least `minimumBudget` existing FIFO tasks.
- If the first task belongs to a square base tile, also poll queued tasks from the same base tile up to `sameTileBurstLimit`.
- Do not return tasks from another dimension.
- Keep TTL pruning.

- [ ] **Step 4: Use coalesced polling in render service**

Change `MarketWebMapRenderService.processBudgeted`:
- Increase base budget from `CHUNKS_PER_TICK = 3` to `CHUNKS_PER_TICK = 8`.
- Add `SAME_TILE_BURST_LIMIT = 32`.
- Use `queue.pollCoalesced(CHUNKS_PER_TICK, SAME_TILE_BURST_LIMIT, nowMillis)`.
- Call both `mergeChunkArgb(...)` for legacy fallback and `mergeChunkPyramid(...)` for squaremap tiles.

This improves speed by rendering adjacent chunks in a tile-local burst and avoiding repeated PNG read/write cycles.

- [ ] **Step 5: Run queue/render tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.map.MarketWebMapRenderQueueTest --tests com.monpai.sailboatmod.market.web.map.MarketWebMapTileCacheTest --console=plain
```

Expected: PASS.

## Task 4: Square Tile HTTP Endpoint

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`

- [ ] **Step 1: Add failing endpoint resource test**

Add assertions:

```java
@Test
void squaremapTileEndpointIsPublicAndReadOnly() throws IOException {
    String source = Files.readString(MARKET_WEB_SERVER, StandardCharsets.UTF_8);

    assertTrue(source.contains("createContext(\"/api/map/square/tile\""),
            "server should expose a squaremap-style tile endpoint");
    String method = methodBody(source, "handleSquareMapTile", "handleMapFlag");
    assertFalse(method.contains("requireIdentity("),
            "squaremap tile PNGs must be public like existing map tiles");
    assertTrue(method.contains("\"GET\".equalsIgnoreCase"),
            "squaremap tile endpoint must be read-only");
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.MarketWebAppResourceTest --console=plain
```

Expected: fails because endpoint is absent.

- [ ] **Step 3: Implement endpoint**

Add route:

```java
createContext("/api/map/square/tile", this::handleSquareMapTile);
```

Parse:

```text
/api/map/square/tile/{dimension}/{zoom}/{x}_{z}.png
```

Map `dimension=overworld` to `minecraft:overworld` for browser-friendly URLs.

Service method:

```java
public byte[] squareMapTile(MinecraftServer server, String dimension, int zoom, int tileX, int tileZ)
```

It reads `MarketWebMapTileCache.readSquareTile(...)`; if missing, enqueue a non-blocking repair request for visible loaded chunks around that tile and return 404. It must not force-load chunks from the HTTP thread.

- [ ] **Step 4: Run endpoint tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.MarketWebAppResourceTest --console=plain
```

Expected: PASS.

## Task 5: Leaflet Assets And Map Shell

**Files:**
- Create: `src/main/resources/marketweb/vendor/leaflet/leaflet.js`
- Create: `src/main/resources/marketweb/vendor/leaflet/leaflet.css`
- Create: `src/main/resources/marketweb/squaremap.js`
- Modify: `src/main/resources/marketweb/index.html`
- Modify: `src/main/resources/marketweb/app.js`
- Modify: `src/main/resources/marketweb/app.css`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`

- [ ] **Step 1: Add failing frontend resource tests**

Add assertions:

```java
@Test
void marketMapUsesLocalLeafletAssetsWithoutCdn() throws IOException {
    String index = Files.readString(INDEX_HTML, StandardCharsets.UTF_8);
    String appJs = Files.readString(APP_JS, StandardCharsets.UTF_8);

    assertTrue(Files.isRegularFile(Path.of("src/main/resources/marketweb/vendor/leaflet/leaflet.js")));
    assertTrue(Files.isRegularFile(Path.of("src/main/resources/marketweb/vendor/leaflet/leaflet.css")));
    assertTrue(index.contains("vendor/leaflet/leaflet.css"));
    assertTrue(index.contains("vendor/leaflet/leaflet.js"));
    assertFalse(index.contains("unpkg.com"));
    assertFalse(index.contains("cdn.jsdelivr.net"));
    assertTrue(appJs.contains("data-square-map-root"));
}

@Test
void squareMapJavaScriptParsesInBrowserRuntime() throws Exception {
    Process process = new ProcessBuilder("node", "--check", "src/main/resources/marketweb/squaremap.js")
            .redirectErrorStream(true)
            .start();
    assertTrue(process.waitFor(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
    assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.MarketWebAppResourceTest --console=plain
```

Expected: fails because local Leaflet files and `squaremap.js` are absent.

- [ ] **Step 3: Vendor Leaflet locally**

Use Leaflet 1.9.4 files in `src/main/resources/marketweb/vendor/leaflet/`.

The files must be local resources bundled in the jar. Do not reference CDN URLs.

- [ ] **Step 4: Implement `squaremap.js` shell**

Expose:

```javascript
window.SailboatSquareMap = {
  mount(root, options) {},
  unmount() {},
  refresh() {}
};
```

Minimum behavior:
- Create `L.map(root.querySelector("[data-square-map]"), { crs: L.CRS.Simple, preferCanvas: true, attributionControl: false })`.
- Convert world coordinates with squaremap convention: `L.latLng(-z, x)`.
- Add two tile layers pointing at `/api/map/square/tile/overworld/{z}/{x}_{y}.png`.
- Swap layers on load for smooth refresh.
- Build layer groups for territories, markets, and shipments from existing JSON endpoints.
- Use `fetchOptionalJson("/api/map/shipments")` so guest users still see public terrain.

- [ ] **Step 5: Wire app.js map host**

Change the map tab host to include:

```html
<div class="square-map-root" data-square-map-root data-focused-market="...">
  <div class="square-map" data-square-map></div>
  <aside class="square-map-panel square-map-panel-left" data-square-map-left></aside>
  <aside class="square-map-panel square-map-panel-right" data-square-map-right></aside>
</div>
```

Keep the old canvas host as fallback only when `window.SailboatSquareMap` is missing.

- [ ] **Step 6: Run frontend parsing tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.MarketWebAppResourceTest --console=plain
```

Expected: PASS.

## Task 6: Sailboat Overlay Layers

**Files:**
- Modify: `src/main/resources/marketweb/squaremap.js`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapLayerService.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapLayerServiceTest.java`

- [ ] **Step 1: Add overlay frontend tests**

Assert `squaremap.js` contains:

```java
assertTrue(squareMapJs.contains("L.rectangle"));
assertTrue(squareMapJs.contains("fillColor"));
assertTrue(squareMapJs.contains("color: border"));
assertTrue(squareMapJs.contains("flagUrl"));
assertTrue(squareMapJs.contains("L.polyline"));
assertTrue(squareMapJs.contains("dashArray"));
assertTrue(squareMapJs.contains("drawVehicleIcon") || squareMapJs.contains("vehicleIcon"));
```

- [ ] **Step 2: Verify RED**

Run the app resource test. Expected: overlay assertions fail until implementation exists.

- [ ] **Step 3: Implement territory overlay**

For every `Territory` DTO:
- Draw chunk bounds with `L.rectangle`.
- Fill color = nation primary color with alpha.
- Stroke color = nation secondary color.
- Do not draw internal borders where adjacent claim belongs to same nation and town.
- Tooltip HTML includes fixed flag frame and escaped nation/town names.

- [ ] **Step 4: Implement market overlay**

For every market:
- Use an `L.circleMarker` or small local icon.
- Tooltip shows market name, town, X/Z.
- Click selects market and updates the right panel.

- [ ] **Step 5: Implement logistics overlay**

For every active shipment:
- Completed route uses solid line.
- Pending route uses dashed line.
- Place directional vehicle icon at current progress.
- Use sailboat-like shape for water/sea/boat modes and carriage-like shape for land/post station modes.

- [ ] **Step 6: Run overlay tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.MarketWebAppResourceTest --tests com.monpai.sailboatmod.market.web.map.MarketWebMapLayerServiceTest --console=plain
```

Expected: PASS.

## Task 7: Performance Pass

**Files:**
- Modify: `src/main/resources/marketweb/squaremap.js`
- Modify: `src/main/resources/marketweb/app.css`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`

- [ ] **Step 1: Add frontend performance assertions**

Assert:

```java
assertTrue(squareMapJs.contains("preferCanvas: true"));
assertTrue(squareMapJs.contains("updateWhenIdle"));
assertTrue(squareMapJs.contains("keepBuffer"));
assertTrue(squareMapJs.contains("document.visibilityState"));
assertTrue(appCss.contains("@media (hover: none), (pointer: coarse)"));
assertTrue(appCss.contains("prefers-reduced-motion"));
```

- [ ] **Step 2: Verify RED**

Run app resource tests and confirm missing assertions fail.

- [ ] **Step 3: Implement performance controls**

Frontend:
- Tile layer options: `tileSize: 512`, `updateWhenIdle: true`, `keepBuffer: 1` mobile and `2` desktop.
- Pause marker/shipment refresh when `document.visibilityState !== "visible"`.
- Use CSS `prefers-reduced-motion` to disable glass refraction and panel animation.
- On touch devices, default both side panels collapsed.

Backend:
- Keep HTTP tile endpoint read-only.
- Render service uses coalesced queue and square tile burst writes.
- Do not force-load from HTTP request path.
- Region watcher dirty chunks remain capped.

- [ ] **Step 4: Run performance tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.MarketWebAppResourceTest --tests com.monpai.sailboatmod.market.web.map.MarketWebMapRenderQueueTest --console=plain
```

Expected: PASS.

## Task 8: Final Verification And Jar

**Files:**
- No new source files unless earlier tasks require fixes.

- [ ] **Step 1: Run targeted tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test --tests com.monpai.sailboatmod.market.web.MarketWebAppResourceTest --tests com.monpai.sailboatmod.market.web.map.* --console=plain
```

Expected: all selected tests pass.

- [ ] **Step 2: Compile Java**

Run:

```powershell
.\gradlew.bat compileJava --console=plain
```

Expected: exit 0.

- [ ] **Step 3: Build jars**

Run:

```powershell
.\gradlew.bat build -x test --console=plain
```

Expected:
- `build/libs/sailboatmod-1.3.9-all.jar`
- `build/libs/sailboatmod-marketweb-1.3.9.jar`

- [ ] **Step 4: Manual browser checks**

Open the market web page and verify:
- Map tab loads without login.
- Terrain tiles render with no red water and no visible seams.
- Pan/zoom remains smooth on desktop.
- Mobile/touch mode starts with side panels collapsed.
- Territory fill/border uses primary/secondary nation colors.
- Territory tooltip shows fixed-size flag image.
- Market markers click into the detail panel.
- Active logistics show solid completed route, dashed remaining route, and directional carriage/sailboat icon.
- Existing market browse, buy, buy-order, wallet, and chart tabs still work.

## Self-Review Notes

- The plan keeps existing market APIs and UI features intact.
- The squaremap-style endpoint is additive, so old `map.js` can stay as fallback during migration.
- Tile speed is addressed at three levels: 512 base tiles, zoom pyramid cache, and coalesced same-tile chunk polling.
- No CDN dependency is allowed; Leaflet must be bundled locally in the market web jar.
- HTTP tile reads never force-load chunks; rendering stays in the server tick/background queue.
