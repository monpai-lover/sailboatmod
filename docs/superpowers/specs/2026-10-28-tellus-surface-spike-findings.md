# Tellus 地表取樣效能 spike 結果（網頁世界地圖 B 層）

日期：2026-10-28
環境：本機 dedicated server，Tellus 0.8.3 地球世界（world_scale 30），可連網。
探針在 `spike/tellus-surface-probe` 分支（`TellusSurfaceProbe`，`/sailboat tellusmapprobe <x> <z> <zoom>`），不合併。

## 方法

在珠穆朗瑪峰出生點 `(322548, −108250)` 取 64×64 個取樣點：間距為瓦片像素間距 × 8，涵蓋一張 512×512 瓦片的範圍。每個 API 連跑兩次：第一次含下載（冷），第二次為已快取（熱）。再依每次取樣耗時外推整張瓦片的耗時。

## 結果（每次取樣耗時）

| API | zoom 0 熱／冷 | zoom 4 熱／冷 | zoom 8 熱／冷 |
|---|---|---|---|
| `TellusWorldgenSources.samplePreloadTerrain(x, z, settings, 公尺解析度)` | **3.6 µs** ／ 553 µs | **1.5 µs** ／ 7.7 ms | 5.6 ms ／ 159 ms |
| 上者＋`WaterSurfaceResolver.resolveFastWaterInfo`＋`resolveLodSurfaceBlock` | 370 µs ／ 1.57 ms | 20 分鐘仍未完成一輪（每個點都觸發下載） | — |
| `resolveLodSurfaceBlock(x, z, y, false)`（扣除高度取得） | 約 300–335 µs | — | — |
| `sampleLodSurfaceShape(x, z, 解析度)` | 1.9 µs | — | — |
| `getBaseHeight(WORLD_SURFACE_WG / OCEAN_FLOOR_WG)` | 0.4 µs | — | — |

輸出觀察：

- `samplePreloadTerrain`：在出生點回 `terrainHeight=394`、`coverClass=70`（雪／冰）、`land=true`，與 Tellus 出生點 y=395 一致；遠一點得到 `coverClass=30`（草地）等。這是正確的地形資料。
- `getBaseHeight` 在這裡回傳 **65**，與實際（394）不符，不可用。
- `sampleLodSurfaceShape` 回傳打包的兩個小整數（`hi`／`lo`，約 −2 到 5，是坡度一類的值），不是高度，單獨不夠用。
- `resolveLodSurfaceBlock` 在出生點回 `snow_block`，結果正確但慢。
- `resolveFastWaterInfo` 與 `resolveLodSurfaceBlock` 在遠景會對每個取樣點下載完整解析度的資料，遠景完全不可行。

## 判定（門檻：資料已下載時每張瓦片 ≤ 30 秒）

| 層級 | 單張瓦片（熱） | 判定 |
|---|---|---|
| 0（每像素 1 格） | 預載資料約 0.9 秒；加上 4×4 子網格的地表方塊，約 6 秒 | 達標 |
| 4（每像素 16 格） | 預載資料約 0.4 秒；加上子網格方塊估計約 6 秒 | 達標 |
| 8（每像素 256 格） | 約 1,476 秒 | **不達標** |

- **`tellusTileMaxZoom = 4`**：B 只覆蓋現有的 5 個層級，用預測補上沒生成的地方。更遠的層級（每像素 32 格以上）只顯示 A 的真實底圖。這正是規格第 7 節預先定好的自動降級。zoom 5–7 沒有實測，保守取 4。
- **第一次看某一區很慢**：冷資料在 zoom 0 約每張 145 秒，因為 Tellus 要下載。下載後會快取，同一區再看就快。期間前端先顯示 A。

## B 的取樣實作（Task 2 依此實作）

- **每像素**：`samplePreloadTerrain(x, z, settings, 2^zoom × worldScale)`，取 `terrainHeight`、`coverClass`、`land`。
- **是否為水**：`!land || coverClass == 80`（ESA WorldCover 80 = 永久水體）。
  - 海洋水面取海平面，水深 = `max(1, seaLevel − terrainHeight)`。
  - 陸上水體水深記為 1。
  - 不使用 `resolveFastWaterInfo`，它遠景會逐點下載，近景也慢。
- **地表方塊**：陸地像素用 `resolveLodSurfaceBlock(x, z, terrainHeight, false)`，但只在 **4×4 像素子網格**的格心取樣一次，同一格內共用。這樣保留使用者選定的「LOD 地表方塊」風格，攤提後約每像素 20 µs。快取鍵為子網格座標，由取樣器內部處理。
- 不使用 `getBaseHeight`（回傳錯誤值）與 `sampleLodSurfaceShape`（不是高度）。

## 事後修正（實際伺服器上的量測）

上面「zoom 4 加上子網格方塊估計約 6 秒」的估計是錯的。實際在伺服器上畫瓦片時，每像素 2–16 格的層級，`resolveLodSurfaceBlock` 每次查詢都會下載完整解析度的高程、陸地遮罩與 OSM 水體，冷資料一張瓦片超過 10 分鐘。

因此改為：只有層級 0 用 LOD 地表方塊；層級 1–4 依 ESA WorldCover 分類對應代表方塊（`CoverClassBlocks`）。

改後實測：冷資料上海 zoom 4 一張 18 秒、珠峰 zoom 0 一張 12 秒。

另外：陸地遮罩「未知」代表資料沒下載成功（Tellus 會靜默退回平坦預設值），現在當作取樣失敗，避免這種瓦片被存檔。
