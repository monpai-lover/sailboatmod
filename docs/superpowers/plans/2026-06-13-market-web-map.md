# Market Web Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a lightweight 2D MAP page to the existing market web UI using server-cached minimap tiles, market markers, nation territory overlays, fixed flag tooltips, and active logistics traces.

**Architecture:** Keep the current `marketweb` single page app and `MarketWebServer` HTTP server. Add a dedicated map backend package that separates tile cache, market marker, territory, flag, and logistics trace responsibilities; tile requests only read cached PNG files and never render or load chunks. Add a client-to-server tile upload path so already-rendered in-game minimap tiles can be stored for the web map without affecting gameplay logic.

**Tech Stack:** Forge 1.20.1, Java 17, Gson, `SavedData`, Forge `SimpleChannel`, existing vanilla `HttpServer`, existing static `marketweb` HTML/CSS/JS, 2D Canvas.

---

## Scope Boundaries

- First version supports only `minecraft:overworld`.
- The web map is lightweight 2D, not 3D.
- Do not embed Pl3xMap, Leaflet, BlueMap, Xaero, or XaeroPlus.
- Do not call `ServerLevel#getChunk`, `ServerLevel#getChunkAt`, force-load APIs, terrain rasterizers, or road planner preload from web map tile requests.
- Unknown or uncached terrain renders black on the frontend.
- Uploaded map tiles are display-only cache artifacts and must not change claims, roads, markets, route planning, or permissions.
- Logistics map data shows only active shipments visible to the logged-in player's town or nation scope.
- Completed, delivered, failed, claimed, cancelled, or rollback-failed shipments are hidden.
- Existing market list, buy, sell, item resolve, chart, and dispatch behavior must remain unchanged.

## File Structure

- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapConstants.java`
  - Shared constants for overworld id, tile size, LOD, unknown color, cache directory, and upload limits.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapDtos.java`
  - Records used by Gson responses for snapshot, tiles, markets, territories, flags, and shipment traces.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCache.java`
  - Reads and writes PNG files under `world/data/sailboatmod_market_web_map/overworld/lod_1/{tileX}_{tileZ}.png`.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileUploadLimiter.java`
  - Per-player rate limiter for in-game tile uploads.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileUploadService.java`
  - Validates uploaded tile payloads and writes them through `MarketWebMapTileCache`.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapLayerService.java`
  - Builds snapshot, market markers, territory rectangles, and visible shipment traces.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebFlagService.java`
  - Resolves `NationFlagStorage` files for `/api/map/flags/{flagId}.png`.
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapJson.java`
  - Converts DTOs into `JsonObject` and `JsonArray` without mixing JSON building into layer services.
- Create: `src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceRecord.java`
  - Persistent route/progress snapshot for map display, separate from `ShippingOrder`.
- Create: `src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceSavedData.java`
  - Root-world `SavedData` keyed by shipping order id.
- Create: `src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceService.java`
  - Creates traces at dispatch time, updates progress/status, filters active traces, and serializes waypoints.
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/marketweb/MarketWebMapTileUploadPacket.java`
  - Client-to-server packet carrying validated tile pixels from already-rendered in-game minimap data.
- Create: `src/main/java/com/monpai/sailboatmod/client/marketweb/MarketWebMapTileUploadClient.java`
  - Client-side dedupe and rate gate before sending tile uploads.
- Create: `src/main/resources/marketweb/map.js`
  - 2D canvas renderer for terrain tiles, territory rectangles, market markers, tooltips, and shipment routes.
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
  - Add map HTTP contexts, `/map` SPA fallback, and static `/map.js` serving.
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
  - Add map service delegation methods; keep existing `listMarkets()` behavior unchanged.
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
  - Register `MarketWebMapTileUploadPacket` with `NetworkDirection.PLAY_TO_SERVER`.
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java`
  - Save server-generated tile packets into the web map cache before sending them to the client.
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClientMapTileCache.java`
  - After applying a valid received tile, offer it to `MarketWebMapTileUploadClient` for deduped upload.
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`
  - After client-side forced rendering saves a full tile, offer that tile to the upload client.
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`
  - When dispatch creates a `ShippingOrder`, create matching `ShippingTraceRecord` from the actual `RouteDefinition`.
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java`
  - Update trace status/progress when existing shipment status changes to arrived, delivered, claimed, or failed.
- Modify: `src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java`
  - Expose current autopilot route index/position through small public accessors for trace progress.
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
  - Expose current autopilot route index/position through small public accessors for trace progress.
- Modify: `src/main/resources/marketweb/index.html`
  - Load `/map.js`.
- Modify: `src/main/resources/marketweb/app.js`
  - Replace `renderMarketMapPlaceholder` with the live map root and route state wiring.
- Modify: `src/main/resources/marketweb/app.css`
  - Add map layout, glass panels, canvas sizing, fixed flag tooltip container, and reduced-motion-safe animations.
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCacheTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileUploadServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapLayerServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/logistics/ShippingTraceRecordTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/logistics/ShippingTraceServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/marketweb/MarketWebMapTileUploadPacketTest.java`

---

### Task 1: Backend Map Contracts And HTTP Routes

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapConstants.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapDtos.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapJson.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapJsonTest.java`

- [ ] **Step 1: Write the DTO JSON contract test**

```java
package com.monpai.sailboatmod.market.web.map;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapJsonTest {
    @Test
    void snapshotJsonContainsStableLayerKeys() {
        MarketWebMapDtos.Snapshot snapshot = new MarketWebMapDtos.Snapshot(
                "minecraft:overworld",
                "#000000",
                256,
                1,
                new MarketWebMapDtos.Point(0.0D, 0.0D),
                7L,
                11L
        );

        JsonObject json = MarketWebMapJson.snapshot(snapshot);

        assertEquals("minecraft:overworld", json.get("dimensionId").getAsString());
        assertEquals("#000000", json.get("unknownColor").getAsString());
        assertEquals(256, json.get("tileSize").getAsInt());
        assertEquals(1, json.get("lod").getAsInt());
        assertTrue(json.has("defaultFocus"));
        assertEquals(7L, json.get("territoryRevision").getAsLong());
        assertEquals(11L, json.get("marketRevision").getAsLong());
    }
}
```

- [ ] **Step 2: Run the focused failing test**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.MarketWebMapJsonTest"
```

Expected: fails because `MarketWebMapDtos` and `MarketWebMapJson` do not exist.

- [ ] **Step 3: Add constants and DTO records**

Create `MarketWebMapConstants.java`:

```java
package com.monpai.sailboatmod.market.web.map;

public final class MarketWebMapConstants {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String UNKNOWN_COLOR = "#000000";
    public static final int TILE_SIZE = 256;
    public static final int LOD_BLOCKS_PER_PIXEL = 1;
    public static final int MAX_TILE_BYTES = 512 * 1024;
    public static final int MAX_UPLOADS_PER_PLAYER_PER_MINUTE = 24;
    public static final String CACHE_DATA_DIR = "sailboatmod_market_web_map";

    private MarketWebMapConstants() {
    }
}
```

Create `MarketWebMapDtos.java`:

```java
package com.monpai.sailboatmod.market.web.map;

import java.util.List;

public final class MarketWebMapDtos {
    public record Point(double x, double z) {}

    public record Snapshot(String dimensionId,
                           String unknownColor,
                           int tileSize,
                           int lod,
                           Point defaultFocus,
                           long territoryRevision,
                           long marketRevision) {}

    public record MarketMarker(String marketId,
                               String marketName,
                               String ownerName,
                               String ownerUuid,
                               String dimensionId,
                               int x,
                               int y,
                               int z,
                               String townId,
                               String townName) {}

    public record Territory(String nationId,
                            String nationName,
                            String townId,
                            String townName,
                            String flagId,
                            int chunkX,
                            int chunkZ,
                            int fillRgb,
                            int borderRgb) {}

    public record FlagMeta(String flagId, String url, int width, int height) {}

    public record ShipmentTrace(String shippingOrderId,
                                String label,
                                String transportMode,
                                String status,
                                String sourceName,
                                String targetName,
                                List<Point> points,
                                int completedPointCount,
                                double progressRatio) {}

    private MarketWebMapDtos() {
    }
}
```

- [ ] **Step 4: Add JSON builders**

Create `MarketWebMapJson.java` with explicit field names:

```java
package com.monpai.sailboatmod.market.web.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class MarketWebMapJson {
    public static JsonObject snapshot(MarketWebMapDtos.Snapshot snapshot) {
        JsonObject json = new JsonObject();
        json.addProperty("dimensionId", snapshot.dimensionId());
        json.addProperty("unknownColor", snapshot.unknownColor());
        json.addProperty("tileSize", snapshot.tileSize());
        json.addProperty("lod", snapshot.lod());
        json.add("defaultFocus", point(snapshot.defaultFocus()));
        json.addProperty("territoryRevision", snapshot.territoryRevision());
        json.addProperty("marketRevision", snapshot.marketRevision());
        return json;
    }

    public static JsonObject point(MarketWebMapDtos.Point point) {
        JsonObject json = new JsonObject();
        json.addProperty("x", point == null ? 0.0D : point.x());
        json.addProperty("z", point == null ? 0.0D : point.z());
        return json;
    }

    public static JsonArray points(java.util.List<MarketWebMapDtos.Point> points) {
        JsonArray out = new JsonArray();
        for (MarketWebMapDtos.Point point : points == null ? java.util.List.<MarketWebMapDtos.Point>of() : points) {
            out.add(point(point));
        }
        return out;
    }

    private MarketWebMapJson() {
    }
}
```

- [ ] **Step 5: Wire HTTP routes without data loading**

Modify `MarketWebServer.startInternal()` to add map contexts before the root static context:

```java
createContext("/api/map/snapshot", this::handleMapSnapshot);
createContext("/api/map/markets", this::handleMapMarkets);
createContext("/api/map/territories", this::handleMapTerritories);
createContext("/api/map/shipments", this::handleMapShipments);
createContext("/api/map/tile", this::handleMapTile);
createContext("/api/map/flags", this::handleMapFlag);
```

Add `/map` to the SPA fallback:

```java
if ("/browse".equals(path) || "/inventory".equals(path) || "/sell".equals(path)
        || "/buy".equals(path) || "/chart".equals(path) || "/index".equals(path)
        || "/map".equals(path)) {
    writeStatic(exchange, "marketweb/index.html", "text/html; charset=utf-8");
    return;
}
```

Add `/map.js` static serving:

```java
if ("/map.js".equals(path)) {
    writeStatic(exchange, "marketweb/map.js", "application/javascript; charset=utf-8");
    return;
}
```

Each `/api/map/*` handler must call `requireIdentity(exchange)` before returning data. Tile PNG requests also require identity so cached world imagery is not public by default.

- [ ] **Step 6: Run contract and resource checks**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.MarketWebMapJsonTest"
.\gradlew.bat processResources
```

Expected: both pass.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/market/web src/main/resources/marketweb src/test/java/com/monpai/sailboatmod/market/web/map
git commit -m "Add market web map API contracts"
```

---

### Task 2: Server Tile Cache And Safe Tile Reads

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCache.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCacheTest.java`

- [ ] **Step 1: Write tile cache tests**

```java
package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapTileCacheTest {
    @TempDir
    Path tempDir;

    @Test
    void missingTileReturnsEmptyWithoutCreatingFiles() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);

        Optional<byte[]> missing = cache.readPng("minecraft:overworld", 1, -2);

        assertFalse(missing.isPresent());
        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/1_-2.png")));
    }

    @Test
    void storesAndReadsPngBytes() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3};

        assertTrue(cache.writePng("minecraft:overworld", 3, 4, png));

        assertArrayEquals(png, cache.readPng("minecraft:overworld", 3, 4).orElseThrow());
    }
}
```

- [ ] **Step 2: Run the focused failing test**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.MarketWebMapTileCacheTest"
```

Expected: fails because `MarketWebMapTileCache` does not exist.

- [ ] **Step 3: Implement path-safe cache reads and writes**

`MarketWebMapTileCache` must normalize only the supported dimension:

```java
private Path tilePath(String dimensionId, int tileX, int tileZ) {
    if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
        return null;
    }
    return root.resolve("overworld")
            .resolve("lod_1")
            .resolve(tileX + "_" + tileZ + ".png")
            .normalize();
}
```

`writePng` must reject:

```java
if (pngBytes == null || pngBytes.length <= 8 || pngBytes.length > MarketWebMapConstants.MAX_TILE_BYTES) return false;
if (!isPng(pngBytes)) return false;
```

`readPng` must only call `Files.readAllBytes(path)` when the normalized path starts with `root.normalize()`.

- [ ] **Step 4: Add server-generated tile persistence**

Modify `RoadPlannerMapPreloadService.sendTile(ServerPlayer player, RoadPlannerMapTileSyncPacket packet)`:

```java
SharedMapServerState.markRendered(packet);
MarketWebMapTileUploadService.acceptServerGeneratedTile(player.server, packet);
ModNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
```

`acceptServerGeneratedTile` converts `packet.argbPixels()` to a PNG and writes it to the cache. This path is safe because the server already produced the tile for an existing in-game map request; no extra chunks are loaded.

- [ ] **Step 5: Serve cached PNG tiles**

Implement `handleMapTile` for:

```text
GET /api/map/tile/lod_1/{tileX}/{tileZ}.png
```

Behavior:

- Require a valid identity.
- Reject any LOD other than `lod_1` with `404`.
- If cache misses, return `404` and do not create a black PNG server-side.
- If cache hits, set `Content-Type: image/png` and `Cache-Control: public, max-age=300`.

- [ ] **Step 6: Verify no tile read path can load chunks**

Run:

```powershell
rg -n "getChunkAt|getChunk\\(|force|Force|Preload|render|Raster" src/main/java/com/monpai/sailboatmod/market/web/map
```

Expected: no chunk loading, force loading, preload, or terrain raster calls in `MarketWebMapTileCache`, `MarketWebMapLayerService`, or HTTP tile handlers.

- [ ] **Step 7: Run tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.MarketWebMapTileCacheTest"
.\gradlew.bat compileJava
```

Expected: both pass.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/market/web/map src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java src/test/java/com/monpai/sailboatmod/market/web/map
git commit -m "Add market web map tile cache"
```

---

### Task 3: Client Tile Upload From Already Rendered Minimap Tiles

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/marketweb/MarketWebMapTileUploadPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/marketweb/MarketWebMapTileUploadClient.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileUploadLimiter.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileUploadService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClientMapTileCache.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/marketweb/MarketWebMapTileUploadPacketTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileUploadServiceTest.java`

- [ ] **Step 1: Write packet round-trip test**

```java
package com.monpai.sailboatmod.network.packet.marketweb;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketWebMapTileUploadPacketTest {
    @Test
    void packetRoundTripsTilePayload() {
        int[] pixels = new int[] {0xFF000000, 0xFFFFFFFF};
        boolean[] mask = new boolean[] {true, false};
        MarketWebMapTileUploadPacket original = new MarketWebMapTileUploadPacket(
                "minecraft:overworld", MapLod.LOD_1, 2, -3, 1, 2, pixels, mask);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        MarketWebMapTileUploadPacket.encode(original, buffer);
        MarketWebMapTileUploadPacket decoded = MarketWebMapTileUploadPacket.decode(buffer);

        assertEquals("minecraft:overworld", decoded.dimensionId());
        assertEquals(MapLod.LOD_1, decoded.lod());
        assertEquals(2, decoded.tileX());
        assertEquals(-3, decoded.tileZ());
        assertArrayEquals(pixels, decoded.argbPixels());
        assertArrayEquals(mask, decoded.coverageMask());
    }
}
```

- [ ] **Step 2: Write upload validation tests**

```java
@Test
void rejectsNonOverworldUpload() {
    MarketWebMapTileUploadService.Result result = MarketWebMapTileUploadService.validateForTest(
            "minecraft:the_nether", MapLod.LOD_1, 0, 0, 256, 256, new int[256 * 256], new boolean[256 * 256]);

    assertEquals(MarketWebMapTileUploadService.Result.REJECTED_DIMENSION, result);
}

@Test
void rejectsWrongPixelDimensions() {
    MarketWebMapTileUploadService.Result result = MarketWebMapTileUploadService.validateForTest(
            "minecraft:overworld", MapLod.LOD_1, 0, 0, 128, 128, new int[128 * 128], new boolean[128 * 128]);

    assertEquals(MarketWebMapTileUploadService.Result.REJECTED_SIZE, result);
}
```

- [ ] **Step 3: Implement `MarketWebMapTileUploadPacket`**

Packet fields:

```java
String dimensionId;
MapLod lod;
int tileX;
int tileZ;
int pixelWidth;
int pixelHeight;
int[] argbPixels;
boolean[] coverageMask;
```

Decode must reject oversized arrays before allocation:

```java
int count = buffer.readVarInt();
if (count < 0 || count > MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE) {
    throw new IllegalArgumentException("Invalid market web map tile pixel count: " + count);
}
```

Handle on server:

```java
context.enqueueWork(() -> {
    ServerPlayer sender = context.getSender();
    if (sender != null) {
        MarketWebMapTileUploadService.acceptClientUpload(sender, packet);
    }
});
context.setPacketHandled(true);
```

- [ ] **Step 4: Register the packet**

Add imports in `ModNetwork.java` and register after `RoadPlannerMapTileSyncPacket`:

```java
CHANNEL.registerMessage(
        packetId++,
        MarketWebMapTileUploadPacket.class,
        MarketWebMapTileUploadPacket::encode,
        MarketWebMapTileUploadPacket::decode,
        MarketWebMapTileUploadPacket::handle,
        Optional.of(NetworkDirection.PLAY_TO_SERVER)
);
```

- [ ] **Step 5: Implement server validation and rate limiting**

`MarketWebMapTileUploadService.acceptClientUpload(ServerPlayer player, MarketWebMapTileUploadPacket packet)` must:

- Return `REJECTED_PLAYER` when `player` or `player.server` is null.
- Return `REJECTED_DIMENSION` unless dimension is exactly `minecraft:overworld`.
- Return `REJECTED_LOD` unless `lod == MapLod.LOD_1`.
- Return `REJECTED_SIZE` unless width and height are both `256`.
- Return `REJECTED_PIXELS` unless `argbPixels.length == 256 * 256`.
- Return `REJECTED_MASK` unless coverage mask is empty or `256 * 256`; empty mask is treated as full coverage.
- Return `RATE_LIMITED` when a player sends more than `24` accepted upload attempts per minute.
- Return `STORED` only after the PNG is written.

- [ ] **Step 6: Implement client-side dedupe**

Create `MarketWebMapTileUploadClient` with a bounded recent key set:

```java
private static String key(RoadPlannerMapTileSyncPacket packet) {
    return packet.dimensionId() + "|" + packet.lod().name() + "|" + packet.tileX() + "|" + packet.tileZ() + "|" + Arrays.hashCode(packet.coverageMask());
}
```

Only send when:

- `Minecraft.getInstance().getConnection()` is not null.
- Dimension is `minecraft:overworld`.
- LOD is `LOD_1`.
- Pixel dimensions are `256 x 256`.
- The recent key has not been sent in the last 60 seconds.

- [ ] **Step 7: Hook already-rendered tile sources**

Modify `RoadPlannerClientMapTileCache.applyToDefaultCache(packet)`:

```java
SharedMapClientState.defaultState().applyTileDelta(packet);
MarketWebMapTileUploadClient.offer(packet);
```

Modify `RoadPlannerTileManager` where client-side forced rendering saves a full `RoadPlannerTile`. Create an upload packet from the saved tile's ARGB data and call `MarketWebMapTileUploadClient.offer(packet)`. This hook must run after local cache save so failed uploads never break the in-game map.

- [ ] **Step 8: Run upload tests and compile**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.marketweb.MarketWebMapTileUploadPacketTest"
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.MarketWebMapTileUploadServiceTest"
.\gradlew.bat compileJava
```

Expected: all pass.

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/network src/main/java/com/monpai/sailboatmod/client src/main/java/com/monpai/sailboatmod/market/web/map src/test/java/com/monpai/sailboatmod
git commit -m "Upload cached minimap tiles for market web map"
```

---

### Task 4: Territory, Flag, And Market Marker Layers

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapLayerService.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebFlagService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapLayerServiceTest.java`

- [ ] **Step 1: Write pure mapping tests**

```java
@Test
void territoryUsesNationPrimaryAndSecondaryColors() {
    NationRecord nation = new NationRecord("crimea", "Crimea", "CRM", 0x2255AA, 0xF2C14E,
            UUID.fromString("00000000-0000-0000-0000-000000000001"), 1L, "port", "minecraft:overworld", 0L, "crimea_flag");
    TownRecord town = new TownRecord("port", "crimea", "Crimea Port",
            UUID.fromString("00000000-0000-0000-0000-000000000002"), 1L, "minecraft:overworld", 0L, "town_flag", "european");
    NationClaimRecord claim = new NationClaimRecord("minecraft:overworld", 10, -4, "crimea", "port",
            "member", "member", "member", "member", "member", "member", "member", 100L, NationClaimRecord.SOURCE_MANUAL);

    MarketWebMapDtos.Territory dto = MarketWebMapLayerService.toTerritoryForTest(claim, nation, town);

    assertEquals(0x2255AA, dto.fillRgb());
    assertEquals(0xF2C14E, dto.borderRgb());
    assertEquals("crimea_flag", dto.flagId());
    assertEquals("Crimea", dto.nationName());
    assertEquals("Crimea Port", dto.townName());
}
```

```java
@Test
void marketMarkerIdMatchesMarketWebEncodingWithoutResolvingChunks() {
    MarketTerminalSavedData.MarketTerminalEntry entry = new MarketTerminalSavedData.MarketTerminalEntry(
            "minecraft:overworld", new BlockPos(12, 64, -30), "North Market", "uuid-1", "Alice");

    MarketWebMapDtos.MarketMarker marker = MarketWebMapLayerService.toMarketMarkerForTest(entry);

    assertEquals(MarketWebService.encodeMarketId("minecraft:overworld", new BlockPos(12, 64, -30)), marker.marketId());
    assertEquals("North Market", marker.marketName());
    assertEquals(12, marker.x());
    assertEquals(-30, marker.z());
}
```

- [ ] **Step 2: Implement territory collection**

`MarketWebMapLayerService.territories(MinecraftServer server)` must:

- Read `NationSavedData.get(server.overworld())`.
- Iterate `data.getAllClaims()`.
- Keep only `claim.dimensionId().equals("minecraft:overworld")`.
- Resolve `NationRecord nation = data.getNation(claim.nationId())`.
- Resolve `TownRecord town = data.getTown(claim.townId())`.
- Use fallback fill `0x5B6573`, fallback border `0xD7DEE8`, fallback names `claim.nationId()` and `claim.townId()` when records are missing.
- Sort by `chunkZ`, then `chunkX`, then `nationId` for stable JSON.

- [ ] **Step 3: Implement market marker collection without chunk loading**

`MarketWebMapLayerService.markets(MinecraftServer server)` must read only:

```java
MarketTerminalSavedData.get(server.overworld()).entries()
```

It must not call `MarketWebService.listMarkets()`, `MarketWebService.resolveMarket()`, `level.getChunkAt(pos)`, or `level.getBlockEntity(pos)`.

- [ ] **Step 4: Implement flag serving**

`MarketWebFlagService.readFlag(ServerLevel overworld, String flagId)` must:

- Return empty when `flagId` is blank.
- Use `NationFlagStorage.resolveFlagPath(overworld, flagId)`.
- Return empty when the file is missing.
- Return `image/png` bytes without resizing server-side.

The frontend handles fixed display size with CSS.

- [ ] **Step 5: Add HTTP endpoints**

Handlers return:

- `GET /api/map/territories` -> `{ "territories": [...] }`
- `GET /api/map/markets` -> `{ "markets": [...] }`
- `GET /api/map/flags/{flagId}.png` -> PNG or `404`

All require identity.

- [ ] **Step 6: Run tests and chunk-load scan**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.MarketWebMapLayerServiceTest"
rg -n "resolveMarket|getChunkAt|getChunk\\(|getBlockEntity" src/main/java/com/monpai/sailboatmod/market/web/map src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java
```

Expected: tests pass; `MarketWebMapLayerService` has no chunk/block entity lookup.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/market/web src/test/java/com/monpai/sailboatmod/market/web/map
git commit -m "Add market web territory and marker layers"
```

---

### Task 5: Shipping Trace Persistence And Visibility Filtering

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceRecord.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceSavedData.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java`
- Modify: `src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java`
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/logistics/ShippingTraceRecordTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/logistics/ShippingTraceServiceTest.java`

- [ ] **Step 1: Write record NBT round-trip test**

```java
@Test
void traceRecordRoundTripsWaypointsAndProgress() {
    ShippingTraceRecord record = new ShippingTraceRecord(
            "ship-1", "minecraft:overworld", "PORT", "SAILING",
            "crimea", "port", "source", "target",
            List.of(new Vec3(0.5, 64.0, 0.5), new Vec3(16.5, 64.0, 16.5)),
            1, 0.5D, 100L, 200L);

    ShippingTraceRecord loaded = ShippingTraceRecord.load(record.save());

    assertEquals("ship-1", loaded.shippingOrderId());
    assertEquals("minecraft:overworld", loaded.dimensionId());
    assertEquals(2, loaded.waypoints().size());
    assertEquals(1, loaded.completedPointCount());
    assertEquals(0.5D, loaded.progressRatio(), 0.0001D);
}
```

- [ ] **Step 2: Write active status filter test**

```java
@Test
void activeFilterHidesTerminalStatuses() {
    assertTrue(ShippingTraceService.isMapVisibleStatus("SAILING"));
    assertTrue(ShippingTraceService.isMapVisibleStatus("IN_TRANSIT"));
    assertTrue(ShippingTraceService.isMapVisibleStatus("ARRIVED"));
    assertFalse(ShippingTraceService.isMapVisibleStatus("DELIVERED"));
    assertFalse(ShippingTraceService.isMapVisibleStatus("CLAIMED"));
    assertFalse(ShippingTraceService.isMapVisibleStatus("FAILED"));
    assertFalse(ShippingTraceService.isMapVisibleStatus("FAILED_ROLLBACK"));
    assertFalse(ShippingTraceService.isMapVisibleStatus("CANCELLED"));
}
```

- [ ] **Step 3: Implement `ShippingTraceRecord`**

Fields:

```java
String shippingOrderId;
String dimensionId;
String transportMode;
String status;
String nationId;
String townId;
String sourceName;
String targetName;
List<Vec3> waypoints;
int completedPointCount;
double progressRatio;
long startedGameTime;
long updatedGameTime;
```

Constructor rules:

- Blank dimension becomes `minecraft:overworld`.
- Blank status becomes `CREATED`.
- Waypoints are copied and limited to 2048 points.
- `completedPointCount` is clamped to `[0, waypoints.size()]`.
- `progressRatio` is clamped to `[0.0, 1.0]`.

- [ ] **Step 4: Implement saved data**

`ShippingTraceSavedData` mirrors existing `MarketSavedData` style:

```java
private static final String DATA_NAME = "sailboatmod_shipping_traces";
private final Map<String, ShippingTraceRecord> traces = new LinkedHashMap<>();
```

Expose:

```java
public void putTrace(ShippingTraceRecord trace)
public ShippingTraceRecord getTrace(String shippingOrderId)
public List<ShippingTraceRecord> getTraces()
public void removeTrace(String shippingOrderId)
```

- [ ] **Step 5: Create traces at dispatch**

In `MarketBlockEntity.dispatchShipmentPlan(...)`, after each `ShippingOrder` is created and before `market.putShippingOrder(shippingOrder)`, call:

```java
ShippingTraceService.createOrUpdateTrace(level, shippingOrder, route, plan.landPlan());
```

Trace route must use the exact `RouteDefinition route = routeForShipment(sourceDock, plan)` already selected for dispatch. If `route` is null or has fewer than two waypoints, do not create a trace for that order.

- [ ] **Step 6: Update status and progress from existing lifecycle points**

In `DockBlockEntity` where existing code writes `ARRIVED`, `DELIVERED`, and `CLAIMED`, call:

```java
ShippingTraceService.updateStatus(level, shippingOrderId, "ARRIVED");
ShippingTraceService.updateStatus(level, shippingOrderId, "DELIVERED");
ShippingTraceService.updateStatus(level, shippingOrderId, "CLAIMED");
```

In failure paths where existing code writes `FAILED` or `FAILED_ROLLBACK`, call the same method with that status.

For live loaded carriers, expose from `SailboatEntity` and `CarriageEntity`:

```java
public List<Vec3> getMarketWebActiveRoutePoints()
public int getMarketWebActiveRouteTargetIndex()
```

These accessors must return copied data and never mutate autopilot internals.

- [ ] **Step 7: Implement visibility filtering**

`ShippingTraceService.visibleFor(MinecraftServer server, MarketPlayerIdentity identity)` must:

- Return empty when `server` or `identity` is null.
- Read `NationSavedData.get(server.overworld()).getMember(identity.playerUuid())`.
- If the player is a nation member, include traces with matching `nationId`.
- Also include traces where `ShippingOrder.shipperUuid()` equals `identity.playerUuidString()`.
- Exclude every trace whose status is not map-visible.
- Exclude traces with fewer than two waypoints.

- [ ] **Step 8: Run logistics tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.logistics.*"
.\gradlew.bat compileJava
```

Expected: both pass.

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/market/logistics src/main/java/com/monpai/sailboatmod/block/entity src/main/java/com/monpai/sailboatmod/entity src/test/java/com/monpai/sailboatmod/market/logistics
git commit -m "Track active shipment routes for market web map"
```

---

### Task 6: MAP Frontend Canvas And Liquid Glass Layout

**Files:**
- Create: `src/main/resources/marketweb/map.js`
- Modify: `src/main/resources/marketweb/index.html`
- Modify: `src/main/resources/marketweb/app.js`
- Modify: `src/main/resources/marketweb/app.css`
- Test: `tools/check_marketweb_frontend.js`

- [ ] **Step 1: Add script loading**

In `index.html`, load `map.js` after `app.js` or before the closing `</body>` with `defer`:

```html
<script src="/app.js" defer></script>
<script src="/map.js" defer></script>
```

Keep existing IDs unchanged so existing market functions remain wired.

- [ ] **Step 2: Replace the reserved MAP render with a stable root**

Replace `renderMarketMapPlaceholder(detail, canAct, canManage)` body with:

```js
return `
  <section class="market-map-workspace" data-market-map-root data-focused-market="${escapeHtml(detail?.marketId || "")}">
    <aside class="map-side-panel map-side-panel-left">
      <div class="map-panel-section">
        <p class="section-kicker">${escapeHtml(t("map_tab"))}</p>
        <h2>${escapeHtml(t("map_live_title"))}</h2>
      </div>
      <div class="map-layer-toggles" data-map-layer-toggles></div>
      <div class="map-market-list" data-map-market-list></div>
    </aside>
    <div class="map-canvas-shell">
      <canvas class="market-map-canvas" data-market-map-canvas width="1280" height="720"></canvas>
      <div class="map-floating-toolbar" data-map-toolbar></div>
      <div class="map-tooltip" data-map-tooltip hidden></div>
    </div>
    <aside class="map-side-panel map-side-panel-right">
      <div class="map-selection-detail" data-map-selection-detail></div>
      <div class="map-shipment-list" data-map-shipment-list></div>
    </aside>
  </section>
`;
```

Add i18n keys:

- `map_live_title`
- `map_layer_terrain`
- `map_layer_territories`
- `map_layer_markets`
- `map_layer_shipments`
- `map_focus_market`
- `map_no_shipments`
- `map_unknown_tile`

- [ ] **Step 3: Implement `map.js` module boundary**

`map.js` should expose one global object:

```js
window.SailboatMarketMap = {
  mount(root, options) {},
  unmount() {},
  refresh() {}
};
```

`app.js` calls `SailboatMarketMap.mount(root, { focusedMarketId, locale, apiBase: "" })` after `els.marketDetail.innerHTML` is set and `state.activeProductTab === "map"`.

- [ ] **Step 4: Implement data loading**

`map.js` loads:

```js
const [snapshot, markets, territories, shipments] = await Promise.all([
  fetchJson("/api/map/snapshot"),
  fetchJson("/api/map/markets"),
  fetchJson("/api/map/territories"),
  fetchJson("/api/map/shipments")
]);
```

Shipment traces refresh every 5 seconds while mounted:

```js
shipmentTimer = window.setInterval(loadShipments, 5000);
```

Do not refresh tile images inside `loadShipments()`.

- [ ] **Step 5: Implement canvas rendering**

Rendering order:

1. Fill full canvas black.
2. Draw cached terrain tile images for visible tile coordinates.
3. Draw territory rectangles with fill alpha and border.
4. Draw shipment traces with solid completed segment and dashed remaining segment.
5. Draw market markers.
6. Draw hover/selection highlight.

Unknown tile draw rule:

```js
ctx.fillStyle = "#000";
ctx.fillRect(screenX, screenY, tileScreenSize, tileScreenSize);
```

Tile URL:

```js
`/api/map/tile/lod_1/${tileX}/${tileZ}.png`
```

When the image request returns 404, keep the black tile and cache that miss for the current mount.

- [ ] **Step 6: Implement interactions**

Support:

- Drag to pan using pointer events.
- Wheel to zoom, clamped to `[0.25, 8]`.
- Double click focused marker to center and zoom.
- Layer toggles for terrain, territory, market, shipment.
- Hover tooltip for territory, market, and shipment.
- Click market marker to fill the right detail panel.

Use `transform` and `opacity` for animations only. Do not use hover scale that changes layout size.

- [ ] **Step 7: Add map CSS**

CSS requirements:

```css
.market-map-workspace {
  display: grid;
  grid-template-columns: minmax(220px, 280px) minmax(0, 1fr) minmax(240px, 320px);
  gap: 14px;
  min-height: 720px;
}

.map-canvas-shell {
  position: relative;
  min-width: 0;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.32);
  background: rgba(255, 255, 255, 0.66);
  backdrop-filter: blur(18px) saturate(1.25);
}

.market-map-canvas {
  display: block;
  width: 100%;
  height: 100%;
  background: #000;
}

.map-tooltip-flag-frame {
  width: 96px;
  height: 48px;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: rgba(15, 23, 42, 0.92);
}

.map-tooltip-flag-frame img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}
```

Responsive rules:

- At `max-width: 980px`, right panel moves below the canvas.
- At `max-width: 720px`, left panel becomes a compact top strip and canvas height stays at least `420px`.
- No text or buttons may overflow their containers at `375px`.

- [ ] **Step 8: Add frontend checks**

Extend `tools/check_marketweb_frontend.js` to verify:

- `map.js` exists.
- `app.js` contains `SailboatMarketMap.mount`.
- `index.html` loads `/map.js`.
- `app.css` contains `.market-map-workspace`, `.market-map-canvas`, and `.map-tooltip-flag-frame`.
- No replacement character `�` exists in `index.html`, `app.js`, or `app.css`.

- [ ] **Step 9: Run frontend/resource checks**

```powershell
node tools/check_marketweb_frontend.js
.\gradlew.bat processResources
```

Expected: both pass.

- [ ] **Step 10: Commit**

```powershell
git add src/main/resources/marketweb tools/check_marketweb_frontend.js
git commit -m "Add market web map frontend"
```

---

### Task 7: Integrated Map API, Snapshot Focus, And Verification

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
- Modify: `src/main/resources/marketweb/app.js`
- Modify: `src/main/resources/marketweb/map.js`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapLayerServiceTest.java`

- [ ] **Step 1: Add default focus logic test**

```java
@Test
void defaultFocusUsesAverageMarketPositionWhenNoSelectedMarketExists() {
    List<MarketWebMapDtos.MarketMarker> markets = List.of(
            new MarketWebMapDtos.MarketMarker("a", "A", "Alice", "uuid-a", "minecraft:overworld", 0, 64, 0, "", ""),
            new MarketWebMapDtos.MarketMarker("b", "B", "Bob", "uuid-b", "minecraft:overworld", 32, 64, 16, "", "")
    );

    MarketWebMapDtos.Point focus = MarketWebMapLayerService.defaultFocusForTest(markets, null, null);

    assertEquals(16.0D, focus.x(), 0.0001D);
    assertEquals(8.0D, focus.z(), 0.0001D);
}
```

- [ ] **Step 2: Implement focus order**

`MarketWebMapLayerService.defaultFocus(...)` uses:

1. Selected market id passed from web query or active detail.
2. Player town or nation core from `NationSavedData`.
3. Average of market marker positions.
4. `(0, 0)`.

- [ ] **Step 3: Keep selected market when switching from detail to MAP**

In `app.js`, when `workspaceButton("map", ...)` is clicked while `state.detail` exists, pass `state.detail.marketId` to the map root as `data-focused-market`. `map.js` reads that value and centers the marker after `markets` load.

- [ ] **Step 4: Add final API wiring methods**

`MarketWebService` exposes:

```java
public JsonObject mapSnapshot(MinecraftServer server, MarketPlayerIdentity identity, String focusedMarketId)
public JsonArray mapMarkets(MinecraftServer server, MarketPlayerIdentity identity)
public JsonArray mapTerritories(MinecraftServer server, MarketPlayerIdentity identity)
public JsonArray mapShipments(MinecraftServer server, MarketPlayerIdentity identity)
```

These methods delegate to `MarketWebMapLayerService` and `MarketWebMapJson`.

- [ ] **Step 5: Run focused verification**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.*"
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.logistics.*"
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.marketweb.*"
node tools/check_marketweb_frontend.js
```

Expected: all pass.

- [ ] **Step 6: Run full compile/resource verification**

```powershell
.\gradlew.bat processResources
.\gradlew.bat compileJava
```

Expected: both pass.

- [ ] **Step 7: Manual in-game verification**

Run:

```powershell
.\gradlew.bat runClient
```

Manual checks:

- Open an in-game minimap or road/claim map that renders overworld tiles.
- Open market web and navigate to `/map`.
- Unknown areas are black.
- Newly rendered overworld minimap tiles eventually appear in web map tile positions.
- Nation claims render even over black unknown tiles.
- Territory fill uses nation primary color and border uses secondary color.
- Tooltip flag frame stays `96x48` visually and does not resize the tooltip.
- Selected market detail page -> MAP centers that market.
- Active sea and land shipments render solid traveled segment and dashed remaining segment.
- Delivered/claimed/failed shipments disappear after refresh.

- [ ] **Step 8: Final scan for prohibited tile behavior**

```powershell
rg -n "getChunkAt|getChunk\\(|force|Force|Preload|captureChunk|render" src/main/java/com/monpai/sailboatmod/market/web
```

Expected: no prohibited calls inside map tile HTTP read path or map marker/territory services. Existing non-map market detail code may still contain `resolveMarket()` and `getChunkAt(pos)`.

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod src/main/resources/marketweb src/test/java/com/monpai/sailboatmod tools/check_marketweb_frontend.js
git commit -m "Integrate market web map layers"
```

---

## Acceptance Checklist

- [ ] `/map` loads the existing market web app.
- [ ] `/map.js` is served by `MarketWebServer`.
- [ ] `GET /api/map/snapshot` returns overworld metadata and black unknown color.
- [ ] `GET /api/map/tile/lod_1/{tileX}/{tileZ}.png` reads cached PNG only.
- [ ] Missing tile returns `404`; frontend renders black.
- [ ] Tile read path never loads or force-renders chunks.
- [ ] Server-generated road/claim map tile packets are saved into the web tile cache.
- [ ] Client-side already-rendered minimap tiles can upload to the server cache with dedupe and rate limiting.
- [ ] Non-overworld tile uploads are rejected.
- [ ] Wrong tile dimensions are rejected.
- [ ] Market markers come from `MarketTerminalSavedData` and do not call `resolveMarket()`.
- [ ] Territory layer uses `NationRecord.primaryColorRgb()` for fill.
- [ ] Territory layer uses `NationRecord.secondaryColorRgb()` for border.
- [ ] Territory tooltip shows nation name and fixed-size flag frame.
- [ ] Missing flag file produces a stable empty flag frame.
- [ ] Active shipments use `ShippingTraceRecord` route waypoints, not inferred route names.
- [ ] Delivered, claimed, failed, rollback-failed, and cancelled shipments are hidden.
- [ ] Shipment visibility is limited to player shipment ownership or matching nation membership.
- [ ] Traveled logistics segment is solid.
- [ ] Remaining logistics segment is dashed.
- [ ] Shipment refresh does not reload tile images.
- [ ] MAP page uses compact operational layout and does not feel like a landing page.
- [ ] Hover effects do not resize cards, panels, or the canvas.
- [ ] Reduced motion users do not receive large animation transitions.
- [ ] Existing market buy/sell/list/chart/item-resolve functions still work.

## Verification Commands

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.web.map.*"
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.logistics.*"
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.marketweb.*"
node tools/check_marketweb_frontend.js
.\gradlew.bat processResources
.\gradlew.bat compileJava
```

## Self-Review

- Spec coverage:
  - Overworld-only map: covered by constants, snapshot, upload validation, and tile path rules.
  - Existing marketweb app and global MAP page: covered by Tasks 1, 6, and 7.
  - Selected market -> MAP focus: covered by Task 7.
  - Pl3xMap-like layer architecture without embedding Pl3xMap: covered by separate tile, marker, territory, and shipment services plus `map.js` layer order.
  - No server terrain render or chunk loading: covered by Task 2 scans and market marker service rule.
  - Unknown areas black: covered by Task 2 tile miss and Task 6 canvas rule.
  - Client-created server cache from rendered minimap tiles: covered by Task 3 client upload and Task 2 server-generated tile save.
  - Player tile upload validation and rate limiting: covered by Task 3.
  - Uploaded tiles are visual-only: enforced by file cache service boundaries; no gameplay service reads uploaded pixels.
  - Nation territory over black background: covered by Task 6 render order.
  - Primary fill, secondary border, flag tooltip: covered by Task 4 and Task 6.
  - Active logistics only and scope filtering: covered by Task 5.
  - Actual route waypoints and solid/dashed split: covered by Task 5 and Task 6.
- Filler scan:
  - No disallowed filler entries are present.
  - Every task has concrete files, tests, commands, and expected outcomes.
- Type consistency:
  - DTO names are consistently under `MarketWebMapDtos`.
  - Tile upload uses `MarketWebMapTileUploadPacket` and `MarketWebMapTileUploadService`.
  - Logistics map state uses `ShippingTraceRecord`, `ShippingTraceSavedData`, and `ShippingTraceService`.
  - Frontend integration uses one global `window.SailboatMarketMap`.
