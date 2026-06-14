# Market Web Map Logistics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the market web UI around the existing market chain, add a lightweight 2D world map from existing sampled minimap tiles, show market/nation overlays, and track only active logistics routes.

**Architecture:** Keep the current `MarketWebServer` HTTP server and `MarketWebService` market actions. Add read-only JSON endpoints for map tiles, market markers, territory overlays, flag thumbnails, and active shipment traces. The server must reuse already sampled road-planner/shared-map data and saved route data; unknown or unsampled map areas render black and must not trigger new server-side world rendering.

**Tech Stack:** Forge 1.20.1 Java 17, Gson JSON, existing `marketweb` static HTML/CSS/JS, existing road planner map tile classes, existing nation claim/flag data, existing land/water route and market shipping records.

---

## Scope Boundaries

- Do not replace the in-game market, road planner, dock, or post-station logic.
- Do not add 3D map rendering.
- Do not force-load chunks for the web map.
- Do not show completed shipments on the web map.
- Do not spoof Xaero, BlueMap, or Pl3xMap data contracts.
- Use Pl3xMap only as an architecture reference for tile/layer separation.

## Reference Notes

- `F:\Codex\Pl3xMap-3\webmap` and `F:\Codex\Pl3xMap-3\core\src\main\java\net\pl3x\map\core\renderer` show the split between tile assets and overlay markers.
- `F:\Codex\awesome-design-md-main\design-md\binance\DESIGN.md` is the better reference for dense market tables, product detail, and price panels.
- `F:\Codex\awesome-design-md-main\design-md\uber\DESIGN.md`, if present, should be used only for restrained logistics/map tracking patterns.

## File Structure

- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
  - Add `/api/map/tiles`, `/api/map/markets`, `/api/map/territories`, `/api/map/shipments`, and `/api/map/flags` handlers.
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
  - Compose market web JSON by delegating to map, territory, and logistics snapshot services.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileService.java`
  - Read existing sampled tile/cache state and return black/empty response for unknown tiles.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapSnapshot.java`
  - Immutable DTOs for tile metadata, market markers, territories, and active shipment traces.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebTerritoryLayerService.java`
  - Convert nation claims into fill/border overlay JSON using primary/secondary nation colors.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebFlagService.java`
  - Serve fixed-size flag metadata and thumbnails from the existing uploaded nation flag storage.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/logistics/MarketWebLogisticsTraceService.java`
  - Export active land/sea shipment traces from existing shipping orders and actual route definitions.
- Create: `src/main/resources/marketweb/map.js`
  - Lightweight 2D canvas renderer for sampled map tiles, market markers, territory overlay, and shipment polylines.
- Modify: `src/main/resources/marketweb/app.js`
  - Add the map route/view, state wiring, API calls, i18n strings, and active shipment refresh loop.
- Modify: `src/main/resources/marketweb/app.css`
  - Add dense market layout, map shell, fixed-size flag tooltip container, and logistics line styles.
- Modify: `src/main/resources/marketweb/index.html`
  - Load `map.js` and add a stable map root container.
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebTerritoryLayerServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/logistics/MarketWebLogisticsTraceServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebServiceMapTest.java`

---

### Task 1: Web Map API Contracts

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapSnapshot.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebServiceMapTest.java`

- [ ] **Step 1: Write JSON contract tests**

Create tests that assert the shape of the new map API root:

```java
@Test
void mapSnapshotContainsStableLayerKeys() {
    JsonObject snapshot = MarketWebServiceMapTestFixtures.emptyMapSnapshotJson();

    assertThat(snapshot.has("tiles")).isTrue();
    assertThat(snapshot.has("markets")).isTrue();
    assertThat(snapshot.has("territories")).isTrue();
    assertThat(snapshot.has("shipments")).isTrue();
    assertThat(snapshot.has("unknownColor")).isTrue();
    assertThat(snapshot.get("unknownColor").getAsString()).isEqualTo("#000000");
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.MarketWebServiceMapTest"
```

Expected: fail because the snapshot DTO and helper do not exist.

- [ ] **Step 3: Add DTO records**

Create `MarketWebMapSnapshot.java` with:

```java
package com.monpai.sailboatmod.market.web.map;

import net.minecraft.core.BlockPos;
import java.util.List;

public record MarketWebMapSnapshot(
        String dimensionId,
        String unknownColor,
        List<TileRef> tiles,
        List<MarketMarker> markets,
        List<Territory> territories,
        List<ShipmentTrace> shipments) {

    public record TileRef(String dimensionId, int tileX, int tileZ, boolean sampled, String url) {}

    public record MarketMarker(String marketId, String name, String ownerName, String townName, String dimensionId, BlockPos pos, boolean loaded) {}

    public record Territory(String nationId, String nationName, String flagId, int chunkX, int chunkZ, int fillRgb, int borderRgb) {}

    public record ShipmentTrace(String shipmentId, String label, String mode, String status, List<Point> points, int completedPointCount) {}

    public record Point(double x, double z) {}
}
```

- [ ] **Step 4: Add route placeholders returning empty layers**

Wire `MarketWebServer` handlers for `/api/map/markets`, `/api/map/territories`, `/api/map/shipments`, and `/api/map/tiles`. The first pass may return empty arrays and `unknownColor: "#000000"` but must require the same session identity rules as `/api/markets` for private logistics data.

- [ ] **Step 5: Run the API contract test**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.MarketWebServiceMapTest"
```

Expected: pass.

---

### Task 2: Lightweight Tile Reuse

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileServiceTest.java`

- [ ] **Step 1: Write tests for unknown and sampled tiles**

```java
@Test
void unknownTileDoesNotRequestWorldRender() {
    FakeTileSource source = new FakeTileSource(false);
    MarketWebMapTileService service = new MarketWebMapTileService(source);

    MarketWebMapTileService.TileResponse response = service.tile("minecraft:overworld", 10, -4);

    assertThat(response.sampled()).isFalse();
    assertThat(response.pngBytes()).isEmpty();
    assertThat(source.renderRequestCount()).isZero();
}

@Test
void sampledTileReturnsCachedPixels() {
    FakeTileSource source = FakeTileSource.withPng(new byte[] { 1, 2, 3 });
    MarketWebMapTileService service = new MarketWebMapTileService(source);

    MarketWebMapTileService.TileResponse response = service.tile("minecraft:overworld", 1, 2);

    assertThat(response.sampled()).isTrue();
    assertThat(response.pngBytes()).containsExactly(1, 2, 3);
}
```

- [ ] **Step 2: Implement a tile source adapter**

The service must read from the existing road planner/shared map cache. Prefer existing classes in `com.monpai.sailboatmod.roadplanner.map` and `com.monpai.sailboatmod.client.map` where possible. Do not call `ServerLevel#getChunk`, `getChunkAt`, or any force-load API from this service.

- [ ] **Step 3: Serve black unknown tiles on the frontend**

`map.js` should draw black rectangles for `sampled === false` and only draw image data for `sampled === true`.

- [ ] **Step 4: Verify no force-load path exists**

Run:

```powershell
rg -n "getChunkAt|getChunk\\(|force|load" src/main/java/com/monpai/sailboatmod/market/web/map
```

Expected: no `getChunkAt`, `getChunk(`, or force-load calls in the web map tile service.

---

### Task 3: Market Markers And Territory Overlay

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebTerritoryLayerService.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebFlagService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Modify: `src/main/resources/marketweb/map.js`
- Modify: `src/main/resources/marketweb/app.css`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebTerritoryLayerServiceTest.java`

- [ ] **Step 1: Test nation color mapping**

```java
@Test
void territoryUsesNationPrimaryFillAndSecondaryBorder() {
    NationRecord nation = NationTestFixtures.nation("n1", "Crimea", 0x3366CC, 0xFFCC33, "flag-crimea");
    NationClaimRecord claim = NationTestFixtures.claim("minecraft:overworld", 3, 4, "n1", "town1");

    MarketWebMapSnapshot.Territory territory = MarketWebTerritoryLayerService.entryForTest(claim, nation);

    assertThat(territory.fillRgb()).isEqualTo(0x3366CC);
    assertThat(territory.borderRgb()).isEqualTo(0xFFCC33);
    assertThat(territory.flagId()).isEqualTo("flag-crimea");
}
```

- [ ] **Step 2: Test fixed flag metadata**

```java
@Test
void flagTooltipUsesFixedContainerSize() {
    MarketWebFlagService.FlagMeta meta = new MarketWebFlagService.FlagMeta("flag-crimea", "/api/map/flags/flag-crimea", 96, 48);

    assertThat(meta.width()).isEqualTo(96);
    assertThat(meta.height()).isEqualTo(48);
}
```

- [ ] **Step 3: Implement territory collection**

Use `NationSavedData#getAllClaims()` and nation lookup. Each claim becomes one chunk rectangle. Tooltip fields must include `nationName`, `flagId`, and flag URL. When nation data is missing, return a neutral fallback color and empty flag id instead of throwing.

- [ ] **Step 4: Implement frontend territory layer**

`map.js` draws each claim as:

```js
ctx.fillStyle = rgbToCss(territory.fillRgb, 0.32);
ctx.strokeStyle = rgbToCss(territory.borderRgb, 0.9);
ctx.fillRect(x, z, chunkSize, chunkSize);
ctx.strokeRect(x, z, chunkSize, chunkSize);
```

Tooltip flag images must be constrained by CSS:

```css
.map-tooltip-flag {
  width: 96px;
  height: 48px;
  object-fit: contain;
  background: #111;
}
```

- [ ] **Step 5: Run territory tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.MarketWebTerritoryLayerServiceTest"
```

Expected: pass.

---

### Task 4: Active Logistics Trace Layer

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/web/logistics/MarketWebLogisticsTraceService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Modify: `src/main/resources/marketweb/map.js`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/logistics/MarketWebLogisticsTraceServiceTest.java`

- [ ] **Step 1: Test completed shipments are hidden**

```java
@Test
void completedShipmentsAreNotExportedToMap() {
    ShippingOrder completed = ShippingOrderTestFixtures.completed("s1");
    ShippingOrder active = ShippingOrderTestFixtures.inTransit("s2");

    List<MarketWebMapSnapshot.ShipmentTrace> traces = MarketWebLogisticsTraceService.tracesForTest(List.of(completed, active));

    assertThat(traces).extracting(MarketWebMapSnapshot.ShipmentTrace::shipmentId).containsExactly("s2");
}
```

- [ ] **Step 2: Test solid and dashed split**

```java
@Test
void traceKeepsCompletedPointCountForSolidLineSplit() {
    MarketWebMapSnapshot.ShipmentTrace trace = MarketWebLogisticsTraceService.traceForTest(
            "s1",
            List.of(new Point(0, 0), new Point(16, 0), new Point(32, 0)),
            2
    );

    assertThat(trace.completedPointCount()).isEqualTo(2);
}
```

- [ ] **Step 3: Implement route extraction**

For sea shipments, read the route definition currently attached to the dock/boat shipping order. For land shipments, read the post-station or road-network route chosen by dispatch. If a route is manually recorded, use the saved waypoints; if it was automatically generated, use the generated route waypoints.

- [ ] **Step 4: Implement frontend trace rendering**

`map.js` renders active route progress:

```js
drawPolyline(ctx, trace.points.slice(0, trace.completedPointCount), {
  stroke: "#f5c542",
  dash: []
});
drawPolyline(ctx, trace.points.slice(Math.max(0, trace.completedPointCount - 1)), {
  stroke: "#f5c542",
  dash: [8, 8]
});
```

- [ ] **Step 5: Add refresh cadence**

Refresh shipment traces every 5 seconds while the map view is visible. Do not refresh tile images on this cadence.

- [ ] **Step 6: Run logistics tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.logistics.MarketWebLogisticsTraceServiceTest"
```

Expected: pass.

---

### Task 5: Market Web UI Redesign And Integration

**Files:**
- Create: `src/main/resources/marketweb/map.js`
- Modify: `src/main/resources/marketweb/index.html`
- Modify: `src/main/resources/marketweb/app.js`
- Modify: `src/main/resources/marketweb/app.css`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebServiceMapTest.java`

- [ ] **Step 1: Add route state for the map**

Extend `PAGE_ROUTES` in `app.js`:

```js
const PAGE_ROUTES = new Set(["browse", "inventory", "sell", "buy", "chart", "index", "map"]);
```

- [ ] **Step 2: Add map shell markup**

`index.html` should include:

```html
<script src="/map.js" defer></script>
```

`app.js` should render a map view inside the existing `market-detail` container when `state.activeProductTab === "map"`.

- [ ] **Step 3: Keep the design dense and operational**

Use a two-column operational layout:

- left: market list, filters, selected market summary
- center: map canvas
- right: active logistics list and selected marker details

Avoid a landing-page hero. Avoid decorative map cards. Keep controls compact and scan-friendly.

- [ ] **Step 4: Add accessible map controls**

Use icon buttons with tooltips for zoom, recenter, layer visibility, and refresh. Text labels belong in tooltips or side panel headings, not inside oversized buttons.

- [ ] **Step 5: Run resource packaging check**

Run:

```powershell
.\gradlew.bat processResources
```

Expected: `map.js`, `app.js`, `app.css`, and `index.html` are included under `build/resources/main/marketweb`.

- [ ] **Step 6: Run full Java validation**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.*"
.\gradlew.bat compileJava
```

Expected: both commands pass.

---

## Acceptance Checklist

- [ ] Web map uses existing sampled minimap/shared-map tile data.
- [ ] Unknown unsampled tiles are black and do not trigger chunk load or render work.
- [ ] Market positions are visible on the map.
- [ ] Nation claims render with primary fill and secondary border.
- [ ] Territory tooltip shows fixed-size flag image and nation name.
- [ ] Only in-progress shipments appear on the map.
- [ ] Completed shipments are hidden from the logistics layer.
- [ ] Shipment path uses the actual land or sea route selected for dispatch.
- [ ] Travelled route segment is solid; remaining segment is dashed.
- [ ] Frontend remains lightweight 2D and does not add 3D rendering.
- [ ] Existing market buy/sell/listing/dispatch endpoints keep working.

## Verification Commands

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.*"
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.*"
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.logistics.*"
.\gradlew.bat processResources
.\gradlew.bat compileJava
```
