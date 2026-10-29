# Tellus 網頁世界地圖設計

日期：2026-10-28
基線：分支 `tellus-adaptation`（已含 Tellus 子專案 A：水路自動航線）
相關：`2026-10-28-tellus-adaptation-design.md`（子專案 A）、`2026-10-28-tellus-spike-findings.md`

> **實作後修訂（2026-10-28）**：
> - B 層級只到 4（第 7 節 spike 結果）。
> - 層級 1–4 依土地覆蓋分類上色，層級 0 才用 LOD 地表方塊。原因見 `2026-10-28-tellus-surface-spike-findings.md` 的「事後修正」。
> - 此改動已列入回交說明，請使用者確認。

## 1. 背景與目標

網頁市場的地圖（可選附加模組 `sailboatmod-marketweb`）目前由伺服器把**已生成區塊**畫成方塊風格瓦片：

- 沒生成過的區塊只會是灰色。
- 最遠只能縮到每像素 16 格。

在 Tellus 地球世界裡，網頁地圖因此只看得到玩家去過的地方。

目標：在 Tellus 世界中，網頁地圖能縮放到**整個地球**，並且分三層顯示：

1. **A：真實世界底圖**：衛星圖或街道圖，前端直接載入。
2. **B：Tellus 預測層**：伺服器用 Tellus 資料畫出方塊風格的地形，任何地方都有。
3. **已生成區域照舊顯示真實方塊**：玩家蓋的東西不會被預測蓋掉。

成功標準：

- Tellus 世界的網頁地圖能一眼看到整個地球，放大到任何地方都有畫面。
- 已生成區域與預測區域的配色風格一致，真實方塊永遠在最上層。
- 非 Tellus 世界、或未安裝 Tellus 時，網頁地圖行為完全不變。
- 伺服器不因地圖請求明顯變慢：B 層只用一條專用執行緒，有佇列上限。

## 2. 已確認的決策

| 問題 | 決定 |
|---|---|
| 做法 | **C：A＋B**。先有真實底圖，再由伺服器補方塊風格的預測地形 |
| A 的來源 | **可切換，預設 ESRI 衛星圖**，另有高德街道圖、OSM、OpenTopoMap；高德要做 GCJ-02 偏移修正 |
| B 什麼時候畫 | **有人看才畫**：瓦片被請求且沒有快取時排入背景，畫好存檔 |
| B 的畫法 | **Tellus 的 LOD 地表方塊**，沿用現有網頁地圖的方塊配色與高度陰影 |
| B 的縮放範圍 | **所有層級**：現有 5 層裡沒生成的地方用預測補上，另外新增遠景層級，一直到全球 |
| 架構 | **獨立的預測圖層**：每像素取樣一點，放在真實地形瓦片下面（見第 4 節，取代了討論初期「逐區塊預測再混入現有金字塔」的想法） |

## 3. 關鍵發現

- **投影**：Tellus 用 Web Mercator。`EarthProjection`：
  - 經度方向：`blockX ↔ longitude` 為線性，每度 `111319.49 / worldScale` 格。
  - 緯度方向：`latToBlockZ` 用 `R·ln(tan(π/4 + φ/2)) / worldScale`。
  - 所以 `mercatorX = blockX × worldScale`、`mercatorY = −blockZ × worldScale`。
  - 驗證：spike 伺服器的 Tellus 出生點 `(322548, −108250)` 換算為北緯 27.99°、東經 86.92°，即珠穆朗瑪峰。
- **一般 XYZ 瓦片可以直接對齊**：OSM、ESRI 這類瓦片用的也是 Web Mercator，所以只差固定的縮放與平移。TellusMinimap 內建了二十多個這類來源。
- **網頁伺服器沒有 CSP**：瀏覽器可以直接載入外部瓦片。
- **網頁地圖在附加模組裡**：`market/web/**` 與 `marketweb/**` 不在主模組 jar。新增的網頁地圖程式放在附加模組；Tellus 專屬取樣放在主模組的 `compat/tellus/`，附加模組只透過介面呼叫。
- **逐欄預測太慢**：spike 實測 `getBaseColumn` 約 5 ms／欄。一張 512×512 的瓦片逐欄預測要約 20 分鐘，所以 B 不能以區塊為單位畫，必須每像素只取樣一點，而且要用更快的 API（第 7 節的 spike 決定）。

## 4. 架構

### 4.1 圖層（由下而上）

1. **A：真實世界底圖**（前端）。
2. **B：Tellus 預測層**（新）。層級 z = 0–11，每像素 2^z 格；每像素在中心取樣一點。
3. **現有真實地形瓦片**（5 層）。在 Tellus 世界中，**未生成區塊的像素改為透明**，原本是灰色。
4. **疊加資訊**：航線、碼頭、船、領地等，維持現有繪製方式。

真實方塊在預測之上，所以不需要瓦片品質分級或金字塔混合，也不改動現有瓦片快取格式。

### 4.2 單元

| 單元 | 位置 | 職責 |
|---|---|---|
| `TerrainSurfaceSampler`（介面） | 主模組 `route/terrain/` | `Optional<SurfaceSample> sample(int x, int z, int blocksPerSample)`。`SurfaceSample` 包含地表高度、地表方塊 `BlockState`、是否為水、水深 |
| `TellusSurfaceSampler` | 主模組 `compat/tellus/` | 用 Tellus 的 LOD／預覽 API 實作，具體呼叫由 spike 決定；只在 Tellus 世界建立 |
| `TellusSurfaceSamplers.forLevel(ServerLevel)` | 主模組 `route/terrain/` | 非 Tellus 世界回傳空；`LinkageError` 時記一次 WARN 並回傳空 |
| `/api/map/world` | 附加模組 `MarketWebServer` | 回傳 `{tellus, worldScale, tellusTileMaxZoom}`，`tellusTileMaxZoom` 為 −1 代表 B 不可用 |
| `/api/map/tellus/tile/{z}/{x}_{y}.png` | 附加模組 `MarketWebServer` | B 瓦片：有快取就回傳；沒有就排入佇列並回 404 |
| `MarketWebTellusTileRenderer` | 附加模組 `market/web/map/` | 每像素取樣、上色、高度陰影、輸出 PNG |
| `MarketWebTellusTileQueue` | 附加模組 `market/web/map/` | 專用單執行緒；去重；後進先出；上限 256，滿了丟最舊 |
| 未知像素透明 | 附加模組（現有上色與快照） | Tellus 世界中未生成區塊的像素 alpha = 0；其他世界照舊為灰色 |
| 前端底圖與預測層 | 附加模組 `marketweb/map.js` | A 的 `GridLayer`、B 的瓦片層、來源選單、版權標示、全球縮放、GCJ-02 修正 |

## 5. 資料流程

### 5.1 前端

1. 載入地圖時呼叫 `/api/map/world`。`tellus:false` 時完全不加 A／B 圖層，縮放限制照舊。
2. **A 底圖**用 Leaflet `GridLayer`，每張畫布瓦片由供應商的 XYZ 瓦片裁切、縮放組合而成，因為兩邊的格線不對齊：
   - 方塊座標 → Mercator：`mx = x·worldScale`、`my = −z·worldScale`。
   - 依目前比例選最接近的 XYZ 層級，上限依各來源的最大層級。
   - 圖片在記憶體快取。
3. **來源清單**：

   | 來源 | 網址樣板 | 座標系 | 版權標示 |
   |---|---|---|---|
   | ESRI 衛星（預設） | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | WGS84 | Esri |
   | 高德街道 | `https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}`（s = 1–4） | GCJ-02 | 高德地圖／AutoNavi |
   | OSM | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | WGS84 | © OpenStreetMap contributors |
   | OpenTopoMap | `https://a.tile.opentopomap.org/{z}/{x}/{y}.png` | WGS84 | © OpenTopoMap (CC-BY-SA) |

   玩家選擇以 `localStorage` 記住；讀寫失敗時退回預設。
4. **GCJ-02 修正**：對每張畫布瓦片的中心點用 WGS84→GCJ-02 公式算出偏移（公尺），把裁切來源平移這個量。只在中國範圍內（標準公式的 `outOfChina` 判定）生效。
5. **全球縮放**：Tellus 世界的最小縮放降到約 2⁻¹¹ 像素／格。地球寬 `2π·6378137 / worldScale` 格，worldScale 30 時約 134 萬格。
6. **B 預測層**：Leaflet `TileLayer` 指向 `/api/map/tellus/tile/...`，層級 z 依目前比例選擇。z 超過 `tellusTileMaxZoom` 時不請求。404 時沿用現有瓦片的退避重試。

### 5.2 伺服器：B 瓦片

1. 請求 `z`、`x`、`y`。瓦片 `(x, y)` 在層級 z 覆蓋方塊範圍 `[x·512·2^z, (x+1)·512·2^z)`，z 方向同理。
2. 快取位置：`<世界>/data/sailboatmod_market/tellus_tiles/{z}/{x}_{y}.png`。命中就直接回傳，長快取。
3. 沒命中就排入 `MarketWebTellusTileQueue` 並回 404。
4. 繪製：對 512×512 像素各取樣 `sample(px, pz, 2^z)`。
   - 地表方塊用現有 `MapBlockColors` 配色；水用現有水色並依水深加深。
   - 高度陰影用西北方向光，依相鄰像素的高度差計算，與現有瓦片外觀一致。
5. 失敗取樣的像素為透明。失敗超過一半的瓦片不存檔，下次請求會重畫。
6. 預測地形不會變，瓦片不需要失效機制。

## 6. 錯誤處理

| 情況 | 處理 |
|---|---|
| 非 Tellus 世界或未裝 Tellus | `tellus:false`，前端不加 A／B；未知像素維持灰色。行為與現在完全相同 |
| A 底圖瓦片載入失敗（無網路、被封鎖、供應商故障） | 該塊留空，其他圖層照常；連續失敗達 8 次時顯示「底圖來源無法連線，可切換來源」 |
| Tellus API 不相容 | 取樣器不建立，`tellusTileMaxZoom = −1`，前端只顯示 A |
| 單一像素取樣失敗 | 該像素透明；整張失敗超過一半則不存檔 |
| 佇列已滿 | 丟最舊的請求；前端重試時會重新排入 |
| spike 判定太慢的層級 | `tellusTileMaxZoom` 設為最後一個達標的層級，更遠的層級只顯示 A |

## 7. 第一步：效能 spike

在本機 Tellus 伺服器載入真實的 Tellus 0.8.3：

1. 對每個候選 API 量測每次取樣的耗時與輸出內容：`sampleLodSurfaceShape`、`resolveLodSurfaceBlock`、`resolveLodSurface`、`samplePreloadTerrain`，以及 `getBaseColumn` 作為基準。分別測已下載和未下載的區域，以及各層級對應的取樣解析度。
2. 確認能否得到地表高度、地表方塊、是否為水、水深；必要時組合兩個 API。
3. 達標門檻：**一張 512×512 瓦片 30 秒內完成**，約每次取樣 ≤ 100 µs（資料已下載的情況）。
4. 依結果決定 `TellusSurfaceSampler` 的實作與 `tellusTileMaxZoom`。若最近層級也達不到門檻，B 整個停用，本功能只剩 A 加上透明的未知區域。結果寫成 spike 結論文件；與本設計假設不符時先回報使用者。

## 8. 測試

**Java 單元測試**

- B 瓦片座標 ↔ 方塊範圍。
- 像素上色與高度陰影（假取樣器）。
- 失敗超過一半時不存檔。
- 佇列去重、後進先出、上限 256。
- `/api/map/world` 在 Tellus／非 Tellus 世界的回傳值。
- 未知像素只在 Tellus 世界透明。

**前端單元測試**（Node，沿用現有網頁 JS 測試的方式）

- 方塊 ↔ Mercator：出生點 `(322548, −108250)` 應落在珠穆朗瑪峰（27.99°N、86.92°E，誤差 < 0.01°）。
- XYZ 瓦片選擇與裁切矩形。
- WGS84→GCJ-02 公式對照已知參考點；中國境外偏移為 0。
- 非 Tellus 世界不建立 A／B 圖層。

**視覺驗證**（瀏覽器面板，截圖放入回交包）

- 全球縮放畫面；放大後真實地形在 B 層之上。
- 高德與 ESRI 在中國海岸線對齊。

## 9. 共用核心與相容性

- **主模組**：新增 `route/terrain/TerrainSurfaceSampler`、`SurfaceSample`、`TellusSurfaceSamplers`，以及 `compat/tellus/TellusSurfaceSampler`。不改封包，協定仍為 12。
- **附加模組**：
  - `MarketWebServer` 新增兩個端點。
  - 新增 B 瓦片繪製器與佇列。
  - 現有上色在 Tellus 世界把未知像素改為透明。
  - `map.js` 新增圖層與來源選單。
- **存檔**：新增 `tellus_tiles/` 快取目錄，可隨時刪除重建。
- **舊快取**：已存在的 Tellus 世界舊瓦片裡，未知像素仍是灰色，要等該瓦片重畫才會變透明。回交說明會寫明如何清除舊快取。

## 10. 不在範圍內

- 道路規劃器地圖、領地地圖、碼頭小地圖（子專案 C）。
- TellusMinimap 遊戲內小地圖整合。
- 自架瓦片伺服器、API key 管理。
- 地名搜尋、導航。

## 11. 風險

- **B 的效能**：由第 7 節 spike 先行驗證，不達標就自動降級。
- **外部瓦片使用條款**：OSM 官方伺服器禁止大量使用；ESRI、高德有各自條款。本功能只供玩家個人瀏覽並標示來源；大型伺服器應改用自架或付費來源（不在範圍內）。
- **依賴 Tellus 內部 LOD API**：集中在 `compat/tellus/`，`LinkageError` 時降級為只有 A。
