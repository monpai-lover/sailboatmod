# Tellus 網頁世界地圖 實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 在 Tellus 地球世界中，網頁地圖能縮放到整個地球：底層是真實世界底圖（A），中間是伺服器按需畫的 Tellus 方塊風格預測層（B），最上層照舊是已生成區塊的真實地形瓦片。

**Architecture：**
- **A（純前端）**：`marketweb/tellus-basemap.js` 負責投影、瓦片選擇與 GCJ-02 等純數學；`map.js` 用 Leaflet `GridLayer` 把供應商的 XYZ 瓦片裁切組合到方塊座標格線上。
- **B（附加模組）**：新瓦片端點按需排隊，由專用執行緒每像素取樣主模組的 `TerrainSurfaceSampler` 並上色。Tellus 專屬取樣在主模組 `compat/tellus/`。
- **真實地形**：現有瓦片不改格式；未渲染區域本來就是透明的。

**Tech Stack：** Java 21、NeoForge 21.1.259、JUnit 5；Leaflet（`marketweb/vendor/leaflet`）、Node 20+（`node:test`）；Tellus 0.8.3（compileOnly）。

**Spec：** `docs/superpowers/specs/2026-10-28-tellus-web-worldmap-design.md`

## Global Constraints

- 分支 `tellus-adaptation`，協定維持 **12**，`network/**` 不改。
- 網頁地圖程式在**附加模組**：`market/web/**` 與 `marketweb/**` 只打包進 `sailboatmod-marketweb` jar（`build.gradle` 既有規則，不改）。Tellus 專屬程式只在主模組 `compat/tellus/`；`route/terrain/` 與附加模組不得 import `com.yucareux.*`。
- 非 Tellus 世界：`/api/map/world` 回 `tellus:false`；前端不加 A／B 圖層、縮放限制照舊；行為與現在完全相同。
- B 層級 `z` = 0–11（每像素 2^z 格），瓦片 512×512 像素，格線與現有瓦片相同：瓦片 `(x, y)` 覆蓋方塊 `[x·512·2^z, (x+1)·512·2^z)`，z 方向以 `y` 同理。
- B 渲染：**專用單執行緒**、去重、後進先出、佇列上限 **256**（滿了丟最舊）；失敗像素透明；失敗超過一半不存檔。
- B 達標門檻：資料已下載時一張 512×512 瓦片 **≤ 30 秒**（約每次取樣 ≤ 100 µs）。`tellusTileMaxZoom` 取最後一個達標層級；都不達標時為 −1。
- A 來源：ESRI 衛星（預設）、高德街道（GCJ-02 修正，只在中國範圍）、OSM、OpenTopoMap；網址樣板與版權標示照規格第 5.1 節逐字使用。玩家選擇存 `localStorage`（讀寫包 try/catch）；連續失敗 **8** 次顯示「底圖來源無法連線，可切換來源」。
- Tellus 世界最小縮放 **2⁻¹¹** 像素／格；現有真實地形圖層只在 Leaflet 縮放 ≥ −4 時顯示。
- 方塊 ↔ Mercator：`mx = x·worldScale`、`my = −z·worldScale`；地球半徑 `6378137`。
- 語系：新增的 UI 文字放 `map.js` 的 `LABELS`（現有中英兩組）。
- 本機 Gradle：沿用 `$G` = `export JAVA_HOME=~/.local/jdk-21 PATH=~/.local/jdk-21/bin:$HOME/.nvm/versions/node/v22.23.3/bin:$PATH && ./gradlew.lf -PneoForge.disableRecompilation=true <tasks> --console=plain --no-daemon --no-parallel -Dorg.gradle.jvmargs="-Xmx1200m -XX:+UseSerialGC"`（需 Node 22 在 PATH）。
- 新增與修改的 `.java` 用 CRLF；commit 身分 `boyang <monpaicilik@gmail.com>`，訊息結尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。

## Review Focus

1. **非 Tellus 世界**（含已裝 Tellus 但用普通預設）→ 前端不能出現底圖、來源選單或全球縮放，B 端點不渲染。→ Task 5 `worldInfoIsNotTellusWithoutTellusLevel`、Task 7 `nonTellusWorldAddsNoBasemap`。
2. **極端座標**：經度 ±180° 接縫、緯度超過 ±85.05°（Mercator 範圍外）、方塊座標為負 → 瓦片與裁切不能出錯或請求不存在的瓦片。→ Task 6 `tilesOutsideMercatorRangeAreSkipped`、`negativeBlockCoordinatesMapToWesternSouthernTiles`。
3. **高德在中國邊界附近**：境外不偏移、境內偏移量級正確。→ Task 6 `gcjOffsetIsZeroOutsideChina`、`gcjOffsetInBeijingHasTheKnownMagnitude`。
4. **B 瓦片大量失敗**（Tellus 下載失敗、取樣例外）→ 不存成永久壞瓦片。→ Task 3 `mostlyFailedTileIsNotWorthSaving`。
5. **快速縮放、平移產生大量請求** → 佇列不無限增長，最新的視野先畫。→ Task 4 `queueDropsOldestBeyond256`、`newestRequestRunsFirst`。

---

### Task 1：B 取樣效能 spike（丟棄式）

**Files：**
- Create（spike 分支 `spike/tellus-surface-probe`，不合併）：`src/main/java/com/monpai/sailboatmod/route/terrain/spike/TellusSurfaceProbe.java`
- Create（保留）：`docs/superpowers/specs/2026-10-28-tellus-surface-spike-findings.md`

**Interfaces：**
- Produces：findings 文件，寫明：
  - 選定的 Tellus API 組合（取得地表高度、地表方塊、是否水、水深）。
  - 每個候選 API 每次取樣的耗時（已下載／未下載）。
  - 若有 packed long 回傳值，寫出其位元格式。
  - 各層級 0–11 的預估單張瓦片耗時，以及據此決定的 `tellusTileMaxZoom`。

  Task 2 依此實作。

- [ ] **Step 1：** `git switch -c spike/tellus-surface-probe`。加 `/sailboat tellusmapprobe <x> <z> <zoom>`（op 2）。在新執行緒對候選 API 各取樣一張瓦片的網格（512×512，間距 2^zoom 格），記錄總耗時與前 5 個樣本內容：
  - `EarthChunkGenerator.sampleLodSurfaceShape(int, int, double)`
  - `resolveLodSurfaceBlock(int, int, int, boolean)`
  - `resolveLodSurface(Holder<Biome>, int, int, int, boolean)`
  - `TellusWorldgenSources.samplePreloadTerrain(int, int, settings, double)`
  - 以及 `getBaseColumn` 作為基準

  先以 `javap -c` 查 `TellusLodGenerator` 對 `sampleLodSurfaceShape` 回傳值的解碼方式。
- [ ] **Step 2：** 沿用上次的 run 目錄（`run-neoforge/`、`level-type=tellus:earth`、RCON 腳本）啟動伺服器。在出生點（珠穆朗瑪峰附近）與海岸（阿克拉 `-740, -20530`）各對 zoom 0、4、8、11 跑一次。每個點先跑一次預熱下載，再量第二次。
- [ ] **Step 3：** 寫 findings，對照門檻（≤ 30 秒／張）決定 API 與 `tellusTileMaxZoom`。**若 zoom 0 也不達標：停止並回報使用者**（B 整個停用，只剩 A）。
- [ ] **Step 4：** 回到 `tellus-adaptation`，只 commit findings（`docs: Tellus surface sampling spike findings`）。

---

### Task 2：地表取樣介面與 Tellus 實作

**Files：**
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/SurfaceSample.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/TerrainSurfaceSampler.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/TellusSurfaceSamplers.java`
- Create：`src/main/java/com/monpai/sailboatmod/compat/tellus/TellusSurfaceSampler.java`
- Test：`src/test/java/com/monpai/sailboatmod/route/terrain/TellusSurfaceSamplersTest.java`；若 Task 1 有位元格式，另加 `src/test/java/com/monpai/sailboatmod/route/terrain/LodShapeDecodeTest.java`

**Interfaces：**
- Consumes：`TellusWorlds.isTellusLevel`；Task 1 findings。
- Produces：
  - `record SurfaceSample(int surfaceY, BlockState surface, boolean water, int waterDepth)`；`waterDepth ≥ 0`，非水時為 0。
  - `interface TerrainSurfaceSampler { Optional<SurfaceSample> sample(int x, int z, int blocksPerSample); }`：失敗回 empty，不丟例外。
  - `TellusSurfaceSamplers.forLevel(ServerLevel) → Optional<TerrainSurfaceSampler>`：非 Tellus 世界、或建立時 `LinkageError`（記一次 WARN）→ empty。
  - `TellusSurfaceSamplers.worldScale(ServerLevel) → OptionalDouble`：讀 `EarthGeneratorSettings.worldScale()`，經 compat。
  - `TellusSurfaceSamplers.MAX_TILE_ZOOM`：Task 1 決定的值（int，−1 = 停用）。
  - 若 API 回傳 packed long：`static SurfaceShape decodeShape(long packed)` 放在 `route/terrain/`（純函式，可測）。

- [ ] **Step 1：寫失敗測試**
  - `forLevelIsEmptyForNullLevel`：`forLevel(null).isEmpty()`。
  - `worldScaleIsEmptyForNullLevel`。
  - `surfaceSampleRejectsNegativeDepth`：建構子以負水深建立時丟 `IllegalArgumentException`。
  - （有位元格式時）`decodeShapeMatchesProbeSamples`：用 Task 1 記錄的實際 packed 值與期望欄位。
- [ ] **Step 2：** `$G test --tests '*TellusSurfaceSamplersTest'` → FAIL。
- [ ] **Step 3：** 實作。`TellusSurfaceSampler` 依 findings 呼叫 Tellus；所有例外包成 empty。
- [ ] **Step 4：** 同指令 → PASS；`grep -rn "com.yucareux" src/main/java --include=*.java | grep -v /compat/tellus/` 只剩 `TellusWorlds.EARTH_GENERATOR` 字串。
- [ ] **Step 5：** Commit `feat: terrain surface sampler for Tellus map tiles`。

---

### Task 3：B 瓦片繪製

**Files：**
- Create：`src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebTellusTileRenderer.java`
- Test：`src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebTellusTileRendererTest.java`；`src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRegionImageTest.java`（加一個測試）

**Interfaces：**
- Consumes：`TerrainSurfaceSampler`、`SurfaceSample`（Task 2）；`MapBlockColors.colorFor(BlockState, int)`、`RoadMapRenderStyle.styleTerrainByDelta(int, int)`、`RoadMapRenderStyle.styleWater(int)`（既有）。
- Produces：
  - `TILE_PIXELS = 512`；`MAX_ZOOM = 11`。
  - `static long blockMinX(int zoom, int tileX)`，即 `(long) tileX * 512L << zoom`；`blockMinZ` 同理。
  - `record Rendered(int[] argb, int failures)`；`boolean worthSaving()` 為 `failures * 2 <= argb.length`。
  - `static Rendered render(TerrainSurfaceSampler sampler, int zoom, int tileX, int tileY)`：
    - 像素 `(px, py)` 取樣點 = `blockMinX + px·2^z + 2^z/2`（z 方向同理）。
    - 失敗像素 alpha = 0。
    - 陸地：`styleTerrainByDelta(colorFor(surface, fallback), delta)`，其中 `delta = round((h − hNorth) / max(1, 2^z / 4))`，讓遠景坡度明暗與近景接近；北鄰失敗或第一列 `delta = 0`。
    - 水：`styleWater(waterDepth)`。
  - `static byte[] png(Rendered)`：寫成 ARGB PNG。

- [ ] **Step 1：寫失敗測試**（假取樣器；需 Bootstrap）
  - `tileBoundsFollowTheSquareGrid`：`blockMinX(0, 1) == 512`、`blockMinX(3, -1) == -4096`、`blockMinX(11, 2) == 2L * 512 * 2048`。
  - `samplesPixelCenters`：zoom 2 時，像素 (0, 0) 的取樣點 = (`blockMinX + 2`, `blockMinZ + 2`)，以記錄呼叫的假取樣器驗證。
  - `waterUsesDepthStyle`：全水、深度 5 → 每個像素等於 `styleWater(5)`。
  - `flatLandHasNoShade`：全草地、同高 → 等於 `styleTerrainByDelta(colorFor(GRASS_BLOCK), 0)`。
  - `failedSamplesAreTransparent`：取樣器對 x < 某值回 empty → 那些像素 alpha = 0。
  - `mostlyFailedTileIsNotWorthSaving`：60% 失敗 → `worthSaving() == false`；40% → true。
  - `MarketWebMapRegionImageTest.unrenderedPixelsStayTransparent`：新建的 region image 未寫入的像素在輸出時 alpha = 0。這是特徵測試，證明真實地形層的空白本來就透明，可以露出 B 層。
- [ ] **Step 2：** `$G test --tests '*MarketWebTellusTileRendererTest' --tests '*MarketWebMapRegionImageTest'` → 新測試 FAIL（`unrenderedPixelsStayTransparent` 若直接 PASS 屬預期，因為它是特徵測試，在 ledger 記錄）。
- [ ] **Step 3：** 實作 renderer。
- [ ] **Step 4：** 同指令 → PASS。
- [ ] **Step 5：** Commit `feat: render Tellus prediction map tiles`。

---

### Task 4：B 瓦片快取與渲染佇列

**Files：**
- Create：`src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebTellusTileStore.java`
- Create：`src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebTellusTileQueue.java`
- Test：`src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebTellusTileStoreTest.java`、`MarketWebTellusTileQueueTest.java`

**Interfaces：**
- Consumes：`MarketWebTellusTileRenderer`（Task 3）。
- Produces：
  - `MarketWebTellusTileStore(Path root)`：
    - 瓦片路徑 `root/tellus/{z}/{x}_{y}.png`；`root` 沿用 `MarketWebMapTileCache.forServer` 的資料目錄（`data/sailboatmod_market_web_map`）。**與規格第 5.2 節不同**：規格寫 `data/sailboatmod_market/tellus_tiles/`，後者是市場資料庫目錄；網頁地圖既有快取在前者，放在一起才能一次清除。
    - `Optional<byte[]> read(int z, int x, int y)`；`void write(int z, int x, int y, byte[] png)`：先寫暫存檔再原子搬移。
    - `static MarketWebTellusTileStore forServer(MinecraftServer)`。
  - `MarketWebTellusTileQueue(int capacity, Executor worker)`：
    - `boolean request(int z, int x, int y, Runnable job)`：去重；回傳是否新排入。
    - 後進先出。
    - 超過 capacity 丟最舊的未執行請求。
    - `void shutdown()`。
    - `static MarketWebTellusTileQueue global()`：capacity 256、專用單執行緒 daemon，名稱 `MarketWeb-TellusTiles`。

- [ ] **Step 1：寫失敗測試**（`@TempDir`；以手動 Executor 控制執行時機）
  - `writesAndReadsTiles`；`missingTileIsEmpty`；`writeIsAtomic`：寫入途中不會讀到半個檔，以「目標檔案不存在直到搬移完成」驗證。
  - `duplicateRequestIsDropped`；`newestRequestRunsFirst`；`queueDropsOldestBeyond256`（排 300 個，執行後只有最新 256 個跑過）；`shutdownStopsAcceptingRequests`。
- [ ] **Step 2：** `$G test --tests '*MarketWebTellusTile*'` → FAIL。
- [ ] **Step 3：** 實作。
- [ ] **Step 4：** 同指令 → PASS。
- [ ] **Step 5：** Commit `feat: Tellus map tile store and render queue`。

---

### Task 5：伺服器端點

**Files：**
- Modify：`src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`（`squareMapTile` 附近新增方法）
- Modify：`src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
  - `createContext` 區塊（約 140–155 行）
  - `handleStatic` 加 `/tellus-basemap.js`
  - 新增兩個 handler，比照 `handleSquareMapTile`（571–597 行）
- Modify：`src/main/java/com/monpai/sailboatmod/market/web/MarketWebEvents.java`（伺服器停止時 `MarketWebTellusTileQueue.global().shutdown()`）
- Test：`src/test/java/com/monpai/sailboatmod/market/web/MarketWebTellusEndpointTest.java`

**Interfaces：**
- Consumes：Task 2–4。
- Produces：
  - `JsonObject MarketWebService.worldInfo(MinecraftServer)`：`{"tellus": bool, "worldScale": double, "tellusTileMaxZoom": int}`。非 Tellus 為 `false / 0 / -1`；Tellus 但取樣器不可用時 `tellusTileMaxZoom = -1`。
  - `byte[] MarketWebService.tellusTile(MinecraftServer, int z, int x, int y)`：
    - 非 Tellus、`z` 超出 `[0, tellusTileMaxZoom]` → null。
    - 有快取 → bytes。
    - 否則 `request(...)` 一個工作（render → 若 `worthSaving()` 則 write）並回 null。
    - 主線程只取 `ServerLevel` 與取樣器，渲染在佇列執行緒。
  - HTTP：
    - `GET /api/map/world` → 200 JSON，`Cache-Control: no-cache`。
    - `GET /api/map/tellus/tile/{z}/{x}_{y}.png` → 200 PNG（`public, max-age=86400, immutable`）或 404。
    - `GET /tellus-basemap.js` → 靜態檔。

- [ ] **Step 1：寫失敗測試**
  - `worldInfoIsNotTellusWithoutTellusLevel`：`worldInfo(null)` → `tellus=false`、`tellusTileMaxZoom=-1`。
  - `tellusTileIsNullOutsideTellus`：`tellusTile(null, 0, 0, 0) == null`。
  - `serverRegistersTellusRoutes`：讀 `MarketWebServer.java` 原始碼，含 `"/api/map/world"`、`"/api/map/tellus/tile"`、`"/tellus-basemap.js"`。
- [ ] **Step 2：** `$G test --tests '*MarketWebTellusEndpointTest'` → FAIL。
- [ ] **Step 3：** 實作。
- [ ] **Step 4：** `$G test --tests 'com.monpai.sailboatmod.market.web.*'` → PASS。
- [ ] **Step 5：** Commit `feat: web map world info and Tellus tile endpoints`。

---

### Task 6：前端純數學模組 `tellus-basemap.js`

**Files：**
- Create：`src/main/resources/marketweb/tellus-basemap.js`
- Modify：`src/main/resources/marketweb/index.html`（在 `/map.js` 之前加 `<script src="/tellus-basemap.js"></script>`）
- Create：`tools/test_tellus_basemap.js`（`node:test`）
- Test：`src/test/java/com/monpai/sailboatmod/market/web/TellusBasemapScriptTest.java`（比照 `MarketWebMotionTest` 執行 node）

**Interfaces：**
- Produces：
  - 全域 `window.SailboatTellusBasemap`；同時在 `module.exports` 可用時匯出，供 node 測試。
  - `SOURCES`：陣列 `{id, label: {en, zh}, url, subdomains, maxZoom, gcj02, attribution}`。順序 `esri`（預設）、`gaode`、`osm`、`opentopo`；網址與版權標示照規格 5.1 逐字。
  - `blockToMercator(x, z, scale) → {mx, my}`；`mercatorToBlock(mx, my, scale)`。
  - `blockToLatLon(x, z, scale) → {lat, lon}`；`latLonToBlock(lat, lon, scale)`。
  - `chooseXyzZoom(blocksPerPixel, scale, maxZoom) → int`：取 `round(log2(2π·6378137 / (256·blocksPerPixel·scale)))` 夾在 `[0, maxZoom]`。
  - `xyzCrops(bounds, scale, zoom, offsetMeters) → [{x, y, z, sx, sy, sw, sh, dx, dy, dw, dh}]`。`bounds = {minX, minZ, maxX, maxZ, widthPx, heightPx}`（方塊範圍與輸出畫布像素）。
    - `x` 依 `2^zoom` 取模，處理經度接縫。
    - `y` 超出 `[0, 2^zoom)` 的略過。
    - `offsetMeters = {dx, dy}` 為 GCJ 平移。
  - `outOfChina(lat, lon)`；`wgs84ToGcj02(lat, lon) → {lat, lon}`（標準公開公式，常數 `a = 6378245.0`、`ee = 0.00669342162296594323`）；`gcjOffsetMeters(lat, lon) → {dx, dy}`，境外為 `{0, 0}`。

- [ ] **Step 1：寫失敗測試**（`tools/test_tellus_basemap.js` 與 Java 包裝）
  - `everestSpawnMapsToEverest`：`blockToLatLon(322548, -108250, 30)` 與 (27.99, 86.92) 差 < 0.01°。
  - `roundTripBlockLatLon`：任意 5 點來回誤差 < 0.5 格。
  - `chooseXyzZoomMatchesScale`：`blocksPerPixel = 1`、scale 30 → `round(log2(40075016.69 / 7680)) = 12`；`blocksPerPixel = 2048` → 1；結果不超過 `maxZoom`。
  - `negativeBlockCoordinatesMapToWesternSouthernTiles`：方塊 (−1000, 1000) 在 zoom 3 落在 x < 4、y ≥ 4 的瓦片。
  - `tilesOutsideMercatorRangeAreSkipped`：涵蓋北緯 86° 以北的範圍不產生 `y < 0` 的 crop。
  - `antimeridianWraps`：跨 180° 的範圍產生 `x = 0` 與 `x = 2^z − 1` 兩側的 crop。
  - `gcjOffsetIsZeroOutsideChina`：東京 (35.68, 139.76)、阿克拉 (5.55, −0.2) → `{0, 0}`。
  - `gcjOffsetInBeijingHasTheKnownMagnitude`：北京 WGS84 (39.9087, 116.3975) 經 `wgs84ToGcj02` 後，緯度增加 0.0010–0.0020°、經度增加 0.0055–0.0070°（北京一帶已知的偏移量級；可抓出正負號或經緯對調的錯誤）。
  - `sourcesKeepRequiredAttribution`：每個來源的 `attribution` 非空，`esri` 為預設（第一個）。
- [ ] **Step 2：** `$G test --tests '*TellusBasemapScriptTest'` → FAIL。
- [ ] **Step 3：** 實作 `tellus-basemap.js` 並加 script 標籤。
- [ ] **Step 4：** `$G test --tests '*TellusBasemapScriptTest' --tests '*MarketWebAppResourceTest'` → PASS。
- [ ] **Step 5：** Commit `feat: Tellus basemap projection and tile math`。

---

### Task 7：前端圖層、來源選單與全球縮放

**Files：**
- Modify：`src/main/resources/marketweb/map.js`
  - 常數區（1–21 行）
  - `LABELS`（22 行起）
  - `initSquaremapTerrain`（325–359 行）
  - `syncSquaremapTerrain`（361–377 行）
  - 縮放夾取處（`MIN_ZOOM` 的使用，約 845、852 行）
  - 地圖控制按鈕區（約 581 行）
- Modify：`src/main/resources/marketweb/app.css`（來源選單與版權標示樣式）
- Test：`tools/test_tellus_basemap.js`（加測試）；`src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`（加測試）

**Interfaces：**
- Consumes：`window.SailboatTellusBasemap`（Task 6）；`/api/map/world`、`/api/map/tellus/tile`（Task 5）。
- Produces：
  - `state.world = {tellus, worldScale, tellusTileMaxZoom}`：初始化時讀取；失敗時視為 `tellus:false`。
  - 縮放下限函式 `minZoom()`：Tellus 為 `2^-11`，否則 `MIN_ZOOM`。所有夾取改用它，Leaflet `minZoom` 同步為 `log2(minZoom())`。
  - A 底圖：Leaflet `GridLayer`，`zIndex` 0。
    - `createTile` 以 `xyzCrops` 載入圖片並 `drawImage`。
    - 圖片快取上限 256 張。
    - 高德套 `gcjOffsetMeters`（以畫布瓦片中心點計算）。
    - 每次成功載入就把失敗計數歸零；連續失敗 8 次顯示提示。
  - B 預測層：`TileLayer`，`zIndex` 1；`getTileUrl` 指向 `/api/map/tellus/tile/{z}/{x}_{y}.png`，`z = clamp(-coords.z, 0, tellusTileMaxZoom)`。`tellusTileMaxZoom < 0` 時不加。
  - 現有真實地形層：`zIndex` 2，Leaflet 選項加 `minZoom: -MAX_TILE_ZOOM`。
  - 來源選單：地圖控制區的按鈕與下拉，選項來自 `SOURCES`；選擇存 `localStorage["sailboat.basemap"]`，讀寫失敗退回預設 `esri`；右下角版權標示隨來源更新。
  - 純函式（供 node 測試）：`basemapSourceFromStorage(storage) → id`、`shouldShowBasemap(world) → bool`；放在 `tellus-basemap.js`。

- [ ] **Step 1：寫失敗測試**
  - node：
    - `storedSourceIsRestored`：`{getItem: () => "gaode"}` → `"gaode"`。
    - `brokenStorageFallsBackToEsri`：`getItem` 丟例外 → `"esri"`。
    - `unknownStoredSourceFallsBackToEsri`。
    - `nonTellusWorldAddsNoBasemap`：`shouldShowBasemap({tellus:false})` 為 false，`shouldShowBasemap(null)` 也為 false。
  - Java `MarketWebAppResourceTest.mapJsWiresTellusLayers`：`map.js` 含：
    - `"/api/map/world"`、`"/api/map/tellus/tile/"`
    - `SailboatTellusBasemap.xyzCrops(`、`shouldShowBasemap(`
    - `minZoom: -MAX_TILE_ZOOM`
    - 失敗提示的 LABELS 鍵
- [ ] **Step 2：** `$G test --tests '*TellusBasemapScriptTest' --tests '*MarketWebAppResourceTest'` → FAIL。
- [ ] **Step 3：** 實作 `map.js`、`app.css` 與兩個純函式。
- [ ] **Step 4：** 同指令 → PASS；另跑 `$G test --tests 'com.monpai.sailboatmod.market.web.*'` → PASS（`MarketWebAppResourceTest` 中 JS 語法解析測試必須通過）。
- [ ] **Step 5：** Commit `feat: Tellus world map layers, source picker and global zoom`。

---

### Task 8：整體驗證、視覺檢查與回交

**Files：**
- Modify：`docs/handoff/TELLUS_ADAPTATION_RETURN.md`（新增「網頁世界地圖」一節）
- 產出：截圖（放在 `docs/handoff/tellus-worldmap/`）、回交 ZIP

- [ ] **Step 1：** `$G test releaseMod -PfixIndex=5` → BUILD SUCCESSFUL；記錄 suites／tests 數字。
  - 主 jar 無 `com/yucareux` 與 `marketweb/`。
  - 附加模組 jar 含 `marketweb/tellus-basemap.js`。
- [ ] **Step 2：** 啟動 Tellus Earth 伺服器（需把附加模組 jar 放進 `run-neoforge/mods/`，沿用現有方式）。用瀏覽器面板開 `http://localhost:11450`，檢查並各截一張圖：
  - 全球縮放畫面。
  - 珠穆朗瑪峰出生點附近放大，真實地形在 B 之上。
  - 切到高德，看中國海岸線（例如上海 31.23°N、121.47°E）是否與 ESRI 對齊。
  - 非 Tellus 世界的地圖與原本相同。

  網頁登入如需帳號，使用 dev 伺服器的測試帳號流程（只在 localhost）。
- [ ] **Step 3：** 更新回交說明：
  - 變更內容。
  - 共用核心變更：主模組新增檔案；附加模組端點與前端檔案。
  - 外部瓦片使用條款提醒。
  - 舊快取說明：已存在的 Tellus 世界舊瓦片若有灰色未知欄，可刪除 `data/sailboatmod_market_web_map` 讓它重畫。
  - 實測數字與未驗證項目。
- [ ] **Step 4：** Commit `docs: Tellus web world map return notes`。最終整體審查與修正完成後才打包 ZIP（`git archive`），記錄 SHA-256。
