# Tellus 0.8.3 執行期驗證結果（spike）

日期：2026-10-28
環境：本機 dedicated server（`level-type=tellus:earth`、world_scale 30），Tellus 0.8.3，可連網。
探針程式碼在 `spike/tellus-probe` 分支（`TellusProbeCommand`，`/sailboat tellusprobe <x> <z>`），不合併。

## 1. 資料未下載時 `getBaseColumn` 的行為

**阻塞等待下載，然後回傳真實資料**，不會回傳預設值。

| 測點 | 首次（冷）呼叫 | 同區後續 256 欄 | 結果 |
|---|---|---|---|
| (0, 0)，外海 | 13 370 ms | 1 143 ms | 水面 y=64，海床 y=-27 |
| (-740, -20460)，阿克拉外海淺灘 | 8 819 ms | 1 576 ms | 水面 y=64，海床 y=63 |

結論：可以在背景執行緒直接呼叫；最壞情況是單次呼叫被下載卡住數秒。我們的 60 秒預算只管「等待預取 future」，`getBaseColumn` 自身的下載時間不受預算限制，但只發生在背景執行緒。

## 2. 非主線程呼叫是否安全

8 條執行緒各 256 欄同時呼叫：兩次測試分別 7 480 ms、6 924 ms 完成，**0 個錯誤**。可以在 `WaterRouteTaskExecutor` 上平行呼叫。

## 3. 預取與就緒判斷

`EarthChunkGenerator.prefetchForArea(int minX, int minZ, int maxX, int maxZ, boolean includeRoads, boolean includeBuildings, double previewResolutionMeters)`，回傳 `CompletableFuture<Void>`。

| 參數 | 意義（由 bytecode 與 Distant Horizons 整合的呼叫方確認） | 我們傳入 |
|---|---|---|
| minX, minZ, maxX, maxZ | **方塊**座標，內部自動取 min/max | tile 的方塊範圍 |
| includeRoads | 一併預取 OSM 道路 | `false` |
| includeBuildings | 一併預取 OSM 建築 | `false` |
| previewResolutionMeters | 預覽解析度；≤0 或非有限值時 cell size = 1（完整解析度） | `0.0` |

- 呼叫本身立即返回（0–10 ms），future 在下載完成後完成：512×512 方塊區域分別 10.6 s、8.9 s。
- 預取完成後，區內欄位呼叫降到 26 ms（第二測點）～1.7 s（第一測點仍有部分延遲載入）。
- 若 Tellus 處於 cache-only、預取停用或沒有預取執行緒，會回傳**已完成**的 future；這種情況下「就緒」不代表已下載，`getBaseColumn` 會自己阻塞下載。行為仍正確，只是進度條不準。

結論：以 future 完成與否判斷就緒，符合規格假設。

## 4. 預測與生成後方塊的一致性

在測點生成真實區塊，對 256 欄比較「預測欄位頂端是否為水」與「實際 WORLD_SURFACE 頂端是否為水」：

| 區塊 | 一致 | 預測水格 | 實際水格 |
|---|---|---|---|
| (0, 0) 外海 | 256/256 | 256 | 256 |
| (-47, -1279) 淺灘 | 256/256 | 256 | 256 |
| (-47, -1284) 淺灘 | 256/256 | 256 | 256 |
| (-47, -1288) **海岸線** | 256/256 | 82 | 82 |

海岸線區塊水陸各半，預測與實際逐格一致。預測欄位的陸地頂端是 stone（沒有表土與植被），對判水沒有影響。

## 5. 其他觀察

- 維度高度：dimension type minY=-368、height=800；生成器 minY=-64、height=528；海平面 64。`getBaseColumn` 用的是 level 的建築高度，掃描範圍要用 `level.getMinBuildHeight()`／`getMaxBuildHeight()`。
- 比例：world_scale 30（約 30 m／格，每緯度約 3 700 格）。

## 判定

四項都符合規格假設，Task 2 起照計畫執行。對計畫的影響：

- Task 5 `TellusPrefetchGate.fetchTile`：`prefetchForArea(tileMinBlockX, tileMinBlockZ, tileMaxBlockX, tileMaxBlockZ, false, false, 0.0D)`。
- Task 3 `GeneratorColumnPredictor`：minY/maxY 取 level 的建築高度。
