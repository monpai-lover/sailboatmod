# Tellus 適配設計（子專案 A：地形服務＋水路自動航線）

日期：2026-10-28
基線：`sailboatmod 1.3.13-fix_5`（交接包 commit `5d2c69bb`，協定 12）
目標環境：Minecraft 1.21.1 / NeoForge 21.1.259 / Java 21；Tellus 0.8.3（可選）

## 1. 背景與目標

Tellus 的 Earth 世界不是靠噪聲生成，而是直接取得真實世界的高程與水域資料。現有水路自動航線在「區塊沒有存檔 NBT」時，只能把該區塊當成陸地繞開，或在主線程強制生成區塊（卡服）。長航線因此要嘛不準，要嘛很慢。

本設計讓自動航線在 Tellus 世界中：

1. **規劃時**：不生成任何區塊，靠「已存檔 NBT ＋ Tellus 預測」規劃整條航線。
2. **航行時**：邊往前開邊載入前方區塊，用真實方塊驗證；遇到預測與現實不符（玩家蓋了碼頭、堤壩、島），提前在局部繞開並接回原線，船不停。
3. **修正回寫**：航行中修正過的航段寫回保存的航線，下一趟直接走修正後的路。

成功標準：

- Tellus 世界中跨海長航線可在不強制生成區塊的情況下建立，主線程不出現長時間卡頓。
- 已建造區域（有 NBT）的判定結果與現有 NBT 模式逐位元一致。
- 航線上新增的障礙物會在船到達前被繞開，船不需要擱淺後才自救。
- 非 Tellus 世界、或未安裝 Tellus 時，行為完全不變；現有測試全部維持綠燈。

## 2. 範圍

整個 Tellus 適配拆成三個子專案，各自走「規格 → 計畫 → 實作」：

| 編號 | 子專案 | 本文件 |
|---|---|---|
| **A** | **Tellus 地形服務＋水路自動航線**（預測規劃、航行中驗證、局部重規劃、修正回寫） | **是** |
| B | 道路尋路（高度／坡度改用 Tellus，可選 OSM 道路／水域權重） | 否，之後另寫 |
| C | 地圖（道路規劃器地圖瓦片、領地地圖、碼頭小地圖改用 Tellus 資料繪製遠處） | 否，之後另寫 |

B、C 會沿用 A 建立的地形預測介面。

**不在範圍內**：馬車／道路航線、地圖繪製、TellusMinimap 整合、網頁市場、任何 UI 版面重構。

## 3. 已確認的決策

| 問題 | 決定 |
|---|---|
| 來源基線 | 使用 fix_5 交接包原始碼（`route/`、`roadplanner/`、`nation/service/`、`entity/` 與本地版本內容相同，僅換行不同） |
| Tellus 依賴方式 | **可選軟依賴**：只有安裝 Tellus 且主世界生成器是 `EarthChunkGenerator` 時才啟用 |
| 資料來源優先順序 | **混合模式**：區塊有存檔 NBT → 用現有 NBT 讀取（真實方塊）；沒有 NBT → 用 Tellus 預測；預測也拿不到 → 當陸地 |
| 預測與實際不符 | **局部重規劃**：前方發現障礙就在附近用真實方塊繞過，接回原航線，船不停 |
| Tellus 資料尚未下載 | **等待並顯示進度**：進度條顯示「下載地形 x/y」，共用上限 60 秒；逾時區塊當陸地繞開 |
| 建立航線畫面的模式頁籤 | **伺服器自動切換，UI 不變**：Tellus 世界一律用「NBT＋Tellus」單段模式，忽略頁籤；畫面加一行說明；協定維持 12 |
| 實作方式 | **方案 A**：預測走原版 `ChunkGenerator.getBaseColumn`；Tellus 專屬 API 只用於預取與「資料是否就緒」，隔離在 compat 套件 |
| 航行中修正是否回寫 | **回寫**：只回寫自動生成的水路航線 |

## 4. 關鍵發現（來自 Tellus 0.8.3 jar）

- `EarthChunkGenerator` 覆寫了原版的 `getBaseHeight(...)` 與 `getBaseColumn(x, z, LevelHeightAccessor, RandomState)`。
- `getBaseColumn` 內部呼叫 `resolveFastColumnHeights(...)`，並依 `ColumnHeights.hasWater()`／`waterSurface()` 在欄位中填入 `WATER_STATE`。因此**只用原版 API 就能拿到含水面的預測欄位**，核心程式不需要編譯期依賴 Tellus。
- Tellus 的高程／水域資料需要聯網下載並快取。未下載時 `getBaseColumn` 是「阻塞等待」還是「回傳預設值」目前**未知**，需在計畫第一步驗證（見第 9 節）。
- Tellus 另有 `WaterSurfaceResolver.prefetchRegionsForArea(...)`、`EarthChunkGenerator.prefetchForChunk(cx, cz)` 等預取 API，只在 compat 套件使用。

## 5. 架構：規劃階段

### 5.1 切入點

現有 NBT 模式在 `RealBlockWaterMap.chunkAt()` 中：區塊未快取 → 背景讀 NBT → 讀不到（磁碟上沒有）→ 填入 `allLand()`。

Tellus 適配只改這一步：**讀不到 NBT 時先問預測器**。兩階段雙向 A*、2×2 船寬校驗、貼岸懲罰、平滑、末端航點校驗、進度條全部沿用。

### 5.2 新增與修改的單元

| 單元 | 位置 | 職責 | 依賴 |
|---|---|---|---|
| `TerrainPredictor`（介面） | `route/terrain/` | `predictChunk(cx, cz)` 回傳 `Prediction`：`READY(ChunkColumns)`、`NOT_READY`、`FAILED` 三種結果；`ChunkColumns` 與 NBT 路徑相同（`water[256]`、`surfaceY[256]`） | 僅原版 |
| `GeneratorColumnPredictor` | `route/terrain/` | 用 `generator.getBaseColumn(x, z, level, randomState)` 取每欄最高非空氣方塊，再套共用判水規則 | 僅原版 |
| `WaterSurfaceRules` | `route/water/` | 從 `NbtChunkWaterReader` 抽出的判水規則（waterlogged、水、氣泡柱、海草、海帶），NBT、預測欄位、已載入區塊三處共用，**規則逐位元不變** | 僅原版 |
| `TellusWorlds` | `route/terrain/` | `isTellusLevel(level)`：`ModList.isLoaded("tellus")`、主世界、生成器類別名稱為 `com.yucareux.tellus.worldgen.EarthChunkGenerator`。以類別名稱比對，不觸發載入 Tellus 類別 | 原版＋ModList |
| `TellusCompat` | `compat/tellus/`（compileOnly） | 只負責：沿走廊預取、查詢區塊資料是否就緒、回報下載進度。只在 `isTellusLevel` 為真之後才被觸及 | Tellus 0.8.3 |
| `RealBlockWaterMap` | 修改 | 新增可選的 `TerrainPredictor`。`chunkAt` 順序：快取 → NBT → 預測 → （未就緒則在預算內等待）→ 陸地。統計新增 `predicted=`、`predictTimeout=` 計數 | — |
| `WaterAutoRouteService` | 修改 | Tellus 世界中忽略 `midMode`，一律走單段 NBT 路徑並掛上預測器；泊位解析也用同一張帶預測器的水圖；進度條新增「下載地形 x/y」階段 | — |
| `AutoRouteDockSelectionScreen` | 修改 | 客戶端已載入 Tellus 時，模式頁籤下方加一行說明：「Tellus 地球世界中，伺服器會自動使用真實地形＋既有方塊規劃」。中英語系各加一個鍵（zh_cn 用簡體） | — |

### 5.3 邊界

- 尋路演算法完全不知道 Tellus 存在，只是 `RealBlockWaterMap` 多了一個後備資料來源。
- 未安裝 Tellus 或非 Tellus 世界時，預測器為 `null`，行為逐位元不變。
- 封包、`RouteDefinition` 格式、協定版本不變。

### 5.4 資料流程（建立航線）

1. 主線程：解析兩端泊位（同樣用「NBT＋預測」水圖），顯示進度條。
2. 背景：`TellusCompat` 預取兩碼頭之間的直線走廊（兩側各 512 格，依 Tellus 區域分批），進度條顯示「下載地形 x/y」。A* 繞出走廊時，走廊外的區域在第 3 步按需預取。
3. 背景：A* 逐區塊呼叫 `chunkAt`：
   - NBT 命中 → 真實方塊。
   - NBT 未命中 → 預測；`NOT_READY` → 觸發該區預取並等待，所有等待共用 **60 秒**預算；預算用完後一律當陸地。
   - `FAILED` → 當陸地。
4. 背景：平滑、末端航點 NBT 校驗（同樣經過預測後備）。
5. 主線程：`applyCompletedRoute` 存入來源與目標碼頭（沿用現有流程）。

### 5.5 執行緒

- 預測只在背景工作執行緒呼叫（`WaterRouteTaskExecutor`）。Tellus 自己也在工作執行緒生成區塊；是否執行緒安全由第 9 節驗證確認。
- 預測結果與 NBT 結果一樣寫入既有的 `ConcurrentHashMap` 快取，寫一次不可變。

## 6. 架構：航行階段

### 6.1 現況

船航行時只強制載入自身周圍 2 個區塊、目標航點周圍 1 個區塊（`updateAutopilotChunkLoading`）。自救（`tryRerouteToNextNavigableWaypointAsync`）是被動的：擱淺卡住後才重規劃。

### 6.2 新單元：`RouteLookaheadGuard`

由 `SailboatEntity` 持有；只在 Tellus 世界且自動駕駛中啟用；每 10 tick 執行一次。

1. **沿航線往前載入**：在現有範圍之外，強制載入前方約 **160 格**的航線走廊（約 10 個區塊，左右各 1 區塊寬，每艘船上限 40 個額外區塊）。票據是非同步的，未生成區塊由 Tellus 在工作執行緒生成，主線程不等待。離開走廊的票據即時釋放；自動駕駛停止時全部釋放（沿用現有 `clearAutopilotForcedChunks` 的時機與到站寬限）。
2. **只檢查已載入完成的部分**：區塊已達 FULL 的航段，用真實方塊做 2×2 船寬校驗（`WaterSurfaceRules`，與 NBT 口徑相同）。尚未載入的航段跳過，下一輪再檢查。已檢查通過的航段記錄起來，不重複檢查；同一區塊有方塊變動時不追蹤，下一趟再驗。
3. **發現障礙 → 背景局部重規劃**：
   - 主線程：把已載入區塊的判水欄位快照直接放進一張新 `RealBlockWaterMap` 的快取（含預測後備），這樣尚未存檔的記憶體方塊也算數。
   - 背景第一嘗試：現有 `localRerouteNbt` BFS，margin 64。
   - 背景失敗時：256 格範圍內的小規模 A*，起點是障礙前最後一個通過校驗的航點，終點是障礙後第一個通過校驗的航點。
4. **拼接**：主線程只替換 `autopilotRoute` 中的那一段，修正 `autopilotTargetIndex`，船不停、不掉頭。若拼接時船已越過起點航點，改以船目前位置最近的可航格為起點重新拼接。
5. **重規劃尚未回來**：船距離障礙 48 格以內時減速到停住等待，不結束自動駕駛。
6. **仍然失敗**：交給現有的完整自救（重規劃到目的港）；再失敗才走現有的「求救＋暫停」。現有被動脫困邏輯保留不動，作為最後防線。
7. 同一時間每艘船最多一個重規劃任務，與現有 `asyncRerouteInFlight` 共用互斥，避免和被動自救互相覆蓋。

非 Tellus 世界不建立 guard，也不額外載入區塊。

## 7. 修正回寫

### 7.1 `RouteRepairService`

- **範圍**：只回寫**自動生成的水路航線**（現有 `isGeneratedWaterRoute` 判定）。手動錄製的航線、航線書物品不改。
- **修正內容** `RoutePatch`：航線名稱、起訖碼頭名稱、原航點區間 [i, j]、區間內原航點（比對用）、替換航點。
- **套用順序**：
  1. 船自己的 `routeCatalog` 立即更新。
  2. 來源碼頭的正向航線、目標碼頭的反向航線（修正段反轉後）更新，經 `DockBlockEntity.setRoutes`。
  3. 碼頭所在區塊未載入時，修正存入新的 `RouteRepairSavedData`，碼頭載入時套用後刪除。
- **比對保護**：套用前確認航線仍存在、名稱相同、[i, j] 的原航點逐點相同；不符（中途被重新規劃或刪除）就丟棄修正，不覆蓋。
- 重新計算 `routeLengthMeters`；修正段的 `waypointMetas` 標為 RAW（沿用現有先例，僅 debug 工具使用）。
- 存檔層面新增 `RouteRepairSavedData`；`RouteDefinition` 格式與封包不變。

## 8. 錯誤處理與降級

| 情況 | 處理 |
|---|---|
| 未安裝 Tellus／非 Tellus 世界 | 整個功能關閉，行為不變 |
| Tellus API 不相容（版本變動造成 `LinkageError`） | 捕捉一次並記 WARN；預取與進度停用，預測照常（走原版 API）；若原版 API 也失敗則當陸地 |
| 預測丟出例外 | 該區塊記為 `FAILED` 當陸地，日誌計數 |
| 下載逾時（60 秒預算用完） | 未就緒區塊當陸地；進度條與日誌列出 `predictTimeout` 數量 |
| 航行中重規劃失敗 | 現有完整自救 → 現有求救＋暫停 |
| 修正比對不符 | 丟棄修正，記 INFO |

## 9. 驗證 spike（計畫第一步）

在開發環境載入真實 Tellus 0.8.3 jar，建立 Earth 世界，確認：

1. 資料尚未下載時，`getBaseColumn` 是阻塞等待還是回傳預設值；耗時多少。
2. 在非主線程呼叫 `getBaseColumn` 是否安全。
3. 預取與「資料是否就緒」可用的 API（候選：`prefetchRegionsForArea`、`prefetchForChunk`、`TerrainPreloadPackage.Sample.landMaskKnown`）。
4. 預測水面與生成後實際方塊的一致程度（抽樣比較若干海岸區塊）。

若結果與本文件假設不符（例如無法判斷就緒、或不能在工作執行緒呼叫），先回報使用者再調整設計，不自行改方向。

## 10. 測試

**單元測試**

- 預測欄位 → 判水規則（以假 `NoiseColumn` 驅動），與 NBT 判水結果一致。
- `RealBlockWaterMap` 後備順序：NBT → 預測 → 等待 → 陸地；60 秒預算以可注入時鐘測試。
- `TellusWorlds` 偵測：未載入 Tellus、非主世界、其他生成器都回傳 false。
- `RouteLookaheadGuard` 的航段拼接與 `targetIndex` 修正（含船已越過起點的情況）。
- `RoutePatch` 正向／反向套用、比對不符拒絕、碼頭未載入延後套用。
- 既有水路、金錢、貨物、權限、封包測試全部保持綠燈，不刪任何測試。

**建置**：`./gradlew test releaseMod -PfixIndex=5`；確認正式 jar 不含 Tellus 類別，且未安裝 Tellus 時可正常啟動。

**遊戲內（使用者在 Windows 驗證）**

1. Tellus 地球世界中，在兩個相距數千格的海岸城鎮建立跨海峽航線，觀察進度條的下載階段與完成時間，伺服器不卡頓。
2. 航線中途用石頭蓋一道牆，開船，確認船在到達前繞開並接回原線。
3. 再開一趟，確認走的是修正後的航線（不再偵測同一障礙）。
4. 未安裝 Tellus 的普通世界建立航線，結果與 fix_5 相同。

## 11. 共用核心變更（交回時需列明）

- `build.gradle`：新增 Tellus 0.8.3 為 `compileOnly`（本地 jar，放 `libs/compile-only/`），**不打包進正式 jar**。
- `mods.toml`：新增 `tellus` 為 `optional` 依賴（只宣告順序，不強制安裝）。
- `network/**`：不變，協定維持 12。
- 存檔：新增 `RouteRepairSavedData`。

## 12. 風險

- **Tellus 資料下載行為未知**：由第 9 節 spike 先行驗證。
- **前方載入增加伺服器負擔**：每艘船上限 40 個額外區塊，只在 Tellus 世界、自動駕駛中啟用。
- **Tellus 後續版本 API 變動**：預測只靠原版介面；Tellus 專屬呼叫集中在 `compat/tellus/` 並有 `LinkageError` 保護。
