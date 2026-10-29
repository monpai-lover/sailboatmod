# Tellus 水路自動航線 實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 在 Tellus 地球世界中，水路自動航線不生成區塊就能規劃（NBT＋Tellus 預測），航行中沿途驗證、提前局部繞障，並把修正寫回保存的航線。

**Architecture：** `RealBlockWaterMap.chunkAt()` 在 NBT 讀不到時改問 `TerrainPredictor`（原版 `ChunkGenerator.getBaseColumn`），Tellus 專屬的預取只放在 `compat/tellus/`。航行中由 `RouteLookaheadGuard` 載入前方走廊、檢查已載入航段、背景重規劃並拼接；修正以 `RoutePatch` 按內容比對寫回碼頭與船上的航線，碼頭未載入時暫存在 `RouteRepairSavedData`。

**Tech Stack：** Java 21、NeoForge 21.1.259（ModDevGradle）、Minecraft 1.21.1、JUnit 5；Tellus 0.8.3（compileOnly，可選）。

**Spec：** `docs/superpowers/specs/2026-10-28-tellus-adaptation-design.md`

## Global Constraints

- 基線 fix_5（`5d2c69bb`），協定維持 **12**；`network/**` 不改。
- Tellus 是**可選**依賴：`compileOnly`，不打包進正式 jar；`mods.toml` 宣告 `type="optional"`。
- 非 Tellus 世界或未安裝 Tellus 時，行為逐位元不變；不得刪除任何既有測試讓建置變綠。
- `route/terrain/`、`route/water/` 不得 import `com.yucareux.*`；只有 `compat/tellus/` 可以。
- 判水規則（waterlogged、`minecraft:water`、`minecraft:bubble_column`、`seagrass`、`tall_seagrass`、`kelp`、`kelp_plant`；表面掃描跳過 `OfflineChunkPalette.isDefaultSurfaceNoise`）逐位元不變。
- 數值：預取走廊兩側 **512** 格；預取 tile **32×32 區塊**；下載等待共用預算 **60 秒**；前瞻 **160** 格、左右各 **1** 區塊、每船上限 **40** 個額外區塊；每 **10** tick 檢查一次；BFS margin **64**（`maxCells` 20000）；A* margin **256**（`maxExpansions` 60000）；距障礙 **48** 格內停住等待；`MAX_AUTOPILOT_WAYPOINTS` = 256。
- 進度條階段文字沿用現有硬編碼簡體字串風格：`"下载地形 x/y"`。
- 語系：`en_us.json`（BOM＋CRLF）與 `zh_cn.json`（**簡體**）都要加鍵。
- Commit 身分：`git -c user.name=boyang -c user.email=monpaicilik@gmail.com commit`，訊息結尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。
- 本機 Gradle 指令（以下記為 `$G`）：先建立 LF 版 wrapper `tr -d '\r' < gradlew > gradlew.lf && chmod +x gradlew.lf`，並把 `gradlew.lf` 加進 `.git/info/exclude`；之後
  `export JAVA_HOME=~/.local/jdk-21 PATH=~/.local/jdk-21/bin:$PATH && ./gradlew.lf -PneoForge.disableRecompilation=true <tasks> --console=plain --no-daemon --no-parallel -Dorg.gradle.jvmargs="-Xmx1200m -XX:+UseSerialGC"`

## Review Focus

1. **已裝 Tellus 但世界用普通預設**（生成器不是 `EarthChunkGenerator`）→ 必須完全走舊流程，不能因為 mod 已載入就啟用。→ Task 5 `TellusWorldsTest.loadedModWithVanillaGeneratorIsNotTellus`。
2. **區塊有 NBT 但未達 FULL**（ProtoChunk，`OfflineChunkPalette.decode` 回空）→ 應落到 Tellus 預測，而不是當陸地。→ Task 4 `RealBlockWaterMapPredictorTest.nbtMissFallsThroughToPrediction`（null level 等同讀不到 NBT）。
3. **重規劃結果回來時船已卸載／到站／停駛** → 丟棄結果，不改航線、不寫回。→ Task 9 `RouteLookaheadGuardTest.resultIsDroppedWhenAutopilotStopped`。
4. **同一個修正被套用兩次**（船先套、碼頭載入時又套 pending）→ 第二次找不到 `before`，必須是 no-op。→ Task 10 `RoutePatchTest.applyingTwiceIsANoOp`。
5. **拼接後航點超過 256** → 拒絕拼接並升級成現有完整自救，不能截斷航線。→ Task 8 `RouteSplicerTest.rejectsSpliceOverWaypointCap`。

---

### Task 1：Tellus 執行期驗證 spike（丟棄式）

**Files：**
- Create（spike 分支，不合併）：`src/main/java/com/monpai/sailboatmod/route/terrain/spike/TellusProbeCommand.java`
- Create（保留）：`docs/superpowers/specs/2026-10-28-tellus-spike-findings.md`

**Interfaces：**
- Produces：findings 文件，回答規格第 9 節四個問題，並寫明 `EarthChunkGenerator.prefetchForArea(int, int, int, int, boolean, boolean, double)` 七個參數的實際意義（區塊或方塊座標、兩個 boolean、double）。Task 5 依此實作。

- [ ] **Step 1：** `git switch -c spike/tellus-probe`；把 `/home/boyang/Desktop/Tellus-neoforge-1.21.1-0.8.3.jar` 複製到 `libs/compile-only/`；`build.gradle` 加 `compileOnly files('libs/compile-only/Tellus-neoforge-1.21.1-0.8.3.jar')`。
- [ ] **Step 2：** 寫 `TellusProbeCommand`（`/sailboat tellusprobe <x> <z>`，op 2）：在 `WaterRouteTaskExecutor` 背景執行緒依序 (a) 量測對未下載區域呼叫 `getBaseColumn` 的耗時與回傳的最高非空氣方塊／是否含水；(b) 呼叫 `prefetchForArea(...)` 並記錄 future 完成時間；(c) 完成後再呼叫 `getBaseColumn`，比較結果；(d) 對已生成區塊比較預測與 `level.getBlockState` 的表面方塊。全部用 LOGGER 輸出。
- [ ] **Step 3：** 準備 dedicated server：`run/mods/` 放 Tellus jar，`run/server.properties` 設 `level-type=tellus\:earth`，`eula=true`；`$G runServer`。在主控台對一個海岸座標與一個外海座標各跑一次 probe（伺服器需能上網）。
- [ ] **Step 4：** 在 `javap -c` 中確認 `prefetchForArea` 參數用法，與 probe 日誌一起寫成 findings 文件（四個問題各一段＋參數表）。
- [ ] **Step 5：** 判斷是否符合規格假設：可在背景執行緒呼叫、能以 future 判斷就緒。**若任一不符，停止並向使用者回報，不繼續 Task 2 之後。**
- [ ] **Step 6：** 切回 `tellus-adaptation`，只 commit findings 文件（`docs: Tellus runtime spike findings`）；spike 分支保留不合併。

---

### Task 2：共用判水規則 `WaterSurfaceRules`

**Files：**
- Create：`src/main/java/com/monpai/sailboatmod/route/water/WaterSurfaceRules.java`
- Modify：`src/main/java/com/monpai/sailboatmod/route/water/NbtChunkWaterReader.java`（`isWaterColumn`、`isWaterBlock`、`isAquaticPlantBlock` 改呼叫新類別）
- Test：`src/test/java/com/monpai/sailboatmod/route/water/WaterSurfaceRulesTest.java`

**Interfaces：**
- Produces：
  - `static boolean isNavigableSurface(String blockId, boolean waterlogged)`
  - `static boolean isNavigableSurface(BlockState state)`（以 `BuiltInRegistries.BLOCK.getKey` 取 id；有 `BlockStateProperties.WATERLOGGED` 且為 true 視為 waterlogged）
  - `static NbtChunkWaterReader.ChunkColumns scanChunk(ColumnStates states, int minY, int maxY)`，其中 `interface ColumnStates { BlockState at(int localX, int y, int localZ); }`：每欄由 `maxY-1` 往下找第一個非空氣（air／cave_air／void_air）且非 `isDefaultSurfaceNoise` 的方塊為表面；全空時 `water=false`、`surfaceY=-999`；索引 `localZ*16+localX`。

- [ ] **Step 1：寫失敗測試**（`@BeforeAll` 用 `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();`）
  - `idRulesMatchLegacyReader`：`water`、`bubble_column`、四種海草海帶 → true；`stone` waterlogged=false → false；`oak_slab` waterlogged=true → true。
  - `blockStateOverloadReadsWaterlogged`：`Blocks.OAK_SLAB.defaultBlockState().setValue(WATERLOGGED, true)` → true；`Blocks.SAND.defaultBlockState()` → false。
  - `scanSkipsSurfaceNoiseAndAir`：欄位自上而下 air、`short_grass`、`water`、`stone` → `water=true`、`surfaceY` 為水那格的 y。
  - `emptyColumnIsLandAtMinus999`：全 air → `water=false`、`surfaceY=-999`。
- [ ] **Step 2：** `$G test --tests '*WaterSurfaceRulesTest'` → FAIL（類別不存在）。
- [ ] **Step 3：** 實作 `WaterSurfaceRules`；`NbtChunkWaterReader.isWaterColumn` 改為 `WaterSurfaceRules.isNavigableSurface(blockId, waterlogged)`，刪掉兩個私有 helper。
- [ ] **Step 4：** `$G test --tests '*WaterSurfaceRulesTest' --tests 'com.monpai.sailboatmod.route.water.*'` → PASS。
- [ ] **Step 5：** Commit `refactor: share water surface rules between NBT and live chunks`。

---

### Task 3：地形預測介面與原版欄位預測器

**Files：**
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/Prediction.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/TerrainPredictor.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/PrefetchGate.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/GeneratorColumnPredictor.java`
- Test：`src/test/java/com/monpai/sailboatmod/route/terrain/GeneratorColumnPredictorTest.java`

**Interfaces：**
- Consumes：`WaterSurfaceRules.scanChunk`、`NbtChunkWaterReader.ChunkColumns`（Task 2）。
- Produces：
  - `record Prediction(Status status, NbtChunkWaterReader.ChunkColumns columns)`，`enum Status { READY, NOT_READY, FAILED }`，工廠 `ready(columns)`、`notReady()`、`failed()`。
  - `interface TerrainPredictor { Prediction predictChunk(int cx, int cz, long maxWaitMillis); PrefetchGate gate(); }`
  - `interface PrefetchGate { CompletableFuture<Void> request(int cx, int cz); List<CompletableFuture<Void>> requestCorridor(BlockPos a, BlockPos b, int sideBlocks); PrefetchGate ALWAYS_READY = ...; }`（`ALWAYS_READY` 一律回傳已完成的 future）
  - `GeneratorColumnPredictor(ColumnSource source, PrefetchGate gate, int minY, int maxY)`，`interface ColumnSource { NoiseColumn column(int x, int z); }`；`static GeneratorColumnPredictor forLevel(ServerLevel level, PrefetchGate gate)` 以 `level.getChunkSource().getGenerator().getBaseColumn(x, z, level, level.getChunkSource().randomState())` 建立。
  - `predictChunk`：`gate.request(cx, cz).get(maxWaitMillis, MILLISECONDS)` 逾時 → `notReady()`；任何例外（含 `LinkageError`）→ `failed()`；否則對 256 欄呼叫 `source.column` 並以 `scanChunk` 轉成 `ready(columns)`。

- [ ] **Step 1：寫失敗測試**（假 `ColumnSource` 回傳以 `new NoiseColumn(minY, states[])` 組成的欄位；需 Bootstrap）
  - `waterColumnBecomesWaterCell`：x≡3、z≡5 的欄位頂端為 water，其餘為 stone → `columns.water()[5*16+3]==true`，其他格 false。
  - `notReadyWhenGateTimesOut`：gate 回傳永不完成的 future、`maxWaitMillis=10` → `status()==NOT_READY`。
  - `failedWhenSourceThrows`：source 丟 `RuntimeException` → `FAILED`；丟 `NoSuchMethodError` → `FAILED`。
- [ ] **Step 2：** `$G test --tests '*GeneratorColumnPredictorTest'` → FAIL。
- [ ] **Step 3：** 實作四個檔案。
- [ ] **Step 4：** 同指令 → PASS。
- [ ] **Step 5：** Commit `feat: terrain predictor backed by ChunkGenerator.getBaseColumn`。

---

### Task 4：`RealBlockWaterMap` 預測後備與等待預算

**Files：**
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/PredictionBudget.java`
- Modify：`src/main/java/com/monpai/sailboatmod/route/water/RealBlockWaterMap.java`（`chunkAt` 約 371–389 行、`logLayerStats`、新增方法）
- Test：`src/test/java/com/monpai/sailboatmod/route/water/RealBlockWaterMapPredictorTest.java`

**Interfaces：**
- Consumes：`TerrainPredictor`、`Prediction`（Task 3）。
- Produces：
  - `PredictionBudget(long totalMillis, LongSupplier nowMillis)`、`long remainingMillis()`（下限 0）、`static PredictionBudget sixtySeconds()`（60 000 ms、`System::currentTimeMillis`）。
  - `RealBlockWaterMap withPredictor(TerrainPredictor predictor, PredictionBudget budget)`（回傳 this；predictor 為 null 時不變）
  - `void seedChunk(int cx, int cz, NbtChunkWaterReader.ChunkColumns columns)`（直接覆寫快取，給 Task 9 用）
  - `int predictedHits()`、`int predictTimeouts()`、`int predictFailures()`；`logLayerStats` 輸出加 `predicted= predictTimeout= predictFail=`。
  - `chunkAt` 按需路徑：快取 → `loadNbtOffThread` → 有 predictor 時 `predictChunk(cx, cz, budget.remainingMillis())`：READY 存入快取並計 predicted；NOT_READY 計 timeout、FAILED 計 failure，兩者都存 `allLand()`。無 predictor 時與現在完全相同。

- [ ] **Step 1：寫失敗測試**（`new RealBlockWaterMap(null, 63).enableOnDemand()`；null level 時 NBT 必讀不到）
  - `nbtMissFallsThroughToPrediction`：假 predictor 對 (0,0) 回傳全水 → `map.sample(5, 5).passable()`，`predictedHits()==1`。
  - `withoutPredictorMissIsLand`：不掛 predictor → `sample(5,5).passable()==false`，與舊行為相同。
  - `notReadyAndFailedAreLandAndCounted`：(0,0) NOT_READY、(1,0) FAILED → 兩處皆 blocked，`predictTimeouts()==1`、`predictFailures()==1`。
  - `budgetIsSharedAndDrains`：可控時鐘，budget 60 000；第一次預測時假 predictor 把時鐘推進 60 000 → 第二次呼叫收到的 `maxWaitMillis==0`。
  - `seededChunkWinsOverPrediction`：`seedChunk(0,0,全陸)` 後 predictor 不被呼叫。
- [ ] **Step 2：** `$G test --tests '*RealBlockWaterMapPredictorTest'` → FAIL。
- [ ] **Step 3：** 實作 `PredictionBudget` 與 `RealBlockWaterMap` 變更。
- [ ] **Step 4：** `$G test --tests '*RealBlockWaterMapPredictorTest' --tests 'com.monpai.sailboatmod.route.water.*'` → PASS。
- [ ] **Step 5：** Commit `feat: water map falls back to terrain prediction when NBT is missing`。

---

### Task 5：Tellus 偵測、compat 預取與建置設定

**Files：**
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/TellusWorlds.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/TiledPrefetchGate.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/terrain/TellusTerrain.java`
- Create：`src/main/java/com/monpai/sailboatmod/compat/tellus/TellusPrefetchGate.java`
- Add：`libs/compile-only/Tellus-neoforge-1.21.1-0.8.3.jar`
- Modify：`build.gradle`（dependencies 區塊，`compileOnly files(...)` 旁）、`src/main/resources/META-INF/neoforge.mods.toml`（末端新增 dependency）
- Test：`src/test/java/com/monpai/sailboatmod/route/terrain/TellusWorldsTest.java`、`src/test/java/com/monpai/sailboatmod/route/terrain/TiledPrefetchGateTest.java`；在 `src/test/java/com/monpai/sailboatmod/BuildPackagingTest.java` 加一個測試

**Interfaces：**
- Consumes：`PrefetchGate`、`GeneratorColumnPredictor.forLevel`（Task 3）；Task 1 findings 的 `prefetchForArea` 參數意義。
- Produces：
  - `TellusWorlds.EARTH_GENERATOR = "com.yucareux.tellus.worldgen.EarthChunkGenerator"`；`static boolean isTellusGenerator(String generatorClassName, boolean tellusLoaded, boolean overworld)`；`static boolean isTellusLevel(ServerLevel level)`（`ModList.get()` 為 null 時視為未載入）。
  - `abstract class TiledPrefetchGate implements PrefetchGate`：`TILE_CHUNKS = 32`；`static long tileKey(int cx, int cz)`（以 `Math.floorDiv(c, 32)`）；`requestCorridor` 沿 a→b 每 256 格取樣，加入離取樣點 `sideBlocks` 範圍內的所有 tile，去重後逐一 `request`；同一 tile 只呼叫一次 `protected abstract CompletableFuture<Void> fetchTile(int tileX, int tileZ)`，future 快取在 `ConcurrentHashMap`。
  - `TellusPrefetchGate(ServerLevel level)`（compat）：`fetchTile` 把 tile 轉成 findings 規定的座標後呼叫 `((EarthChunkGenerator) generator).prefetchForArea(...)`。
  - `TellusTerrain.forLevel(ServerLevel level) → Optional<TerrainPredictor>`：非 Tellus 世界回傳空；是 Tellus 時建立 `new TellusPrefetchGate(level)`，若丟 `LinkageError` 則記一次 WARN 並改用 `PrefetchGate.ALWAYS_READY`；回傳 `GeneratorColumnPredictor.forLevel(level, gate)`。
  - mods.toml：`[[dependencies.${mod_id}]] modId="tellus" type="optional" versionRange="[0.8.3,)" ordering="AFTER" side="BOTH"`。

- [ ] **Step 1：寫失敗測試**
  - `TellusWorldsTest`：`(EARTH_GENERATOR, true, true)` → true；`loadedModWithVanillaGeneratorIsNotTellus`：`("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", true, true)` → false；`(EARTH_GENERATOR, false, true)` → false；`(EARTH_GENERATOR, true, false)` → false。
  - `TiledPrefetchGateTest`（以計數子類別實作 `fetchTile`）：`tileKey(-1, 0) != tileKey(0, 0)`、`tileKey(31, 31) == tileKey(0, 0)`；同一區塊 `request` 兩次只 `fetchTile` 一次；`requestCorridor((0,63,0), (2048,63,0), 512)` 涵蓋 tile x 0..4 與 z -1..0，且每個 tile 只 fetch 一次。
  - `BuildPackagingTest.releaseJarDoesNotBundleTellus`：在現有測試讀取正式 jar 的方式下，斷言沒有 `com/yucareux/` 開頭的項目。
- [ ] **Step 2：** `$G test --tests '*TellusWorldsTest' --tests '*TiledPrefetchGateTest'` → FAIL。
- [ ] **Step 3：** 加入 jar 與 `build.gradle`、`mods.toml` 設定，實作四個類別。
- [ ] **Step 4：** `$G test --tests '*TellusWorldsTest' --tests '*TiledPrefetchGateTest' --tests '*BuildPackagingTest'` → PASS；`grep -rn "com.yucareux" src/main/java --include=*.java | grep -v "/compat/tellus/"` 除 `TellusWorlds.EARTH_GENERATOR` 字串外無結果。
- [ ] **Step 5：** Commit `feat: optional Tellus detection and prefetch gate`。

---

### Task 6：建立航線流程接上 Tellus

**Files：**
- Modify：`src/main/java/com/monpai/sailboatmod/route/water/WaterAutoRouteService.java`（`submitAutoRoute` 約 103–179 行；`runSingleSegmentNbt` 約 287–371 行）
- Test：`src/test/java/com/monpai/sailboatmod/route/water/WaterAutoRouteServiceTest.java`（新增測試）

**Interfaces：**
- Consumes：`TellusTerrain.forLevel`（Task 5）、`withPredictor`、`PredictionBudget.sixtySeconds()`（Task 4）、`PrefetchGate.requestCorridor`（Task 3）。
- Produces：
  - `static WaterMidMode effectiveMode(WaterMidMode requested, boolean tellusLevel)`：Tellus → `NBT`，否則回傳 requested。
  - `static String terrainDownloadStage(int done, int total)` → `"下载地形 " + done + "/" + total`。
  - `runSingleSegmentNbt` 多一個參數 `@Nullable TerrainPredictor predictor, PredictionBudget budget`；非 null 時：先 `predictor.gate().requestCorridor(sourceBerth, targetBerth, 512)`，迴圈等待 future 完成（每完成一個 `bar.update(5 * done / total, terrainDownloadStage(done, total))`，等待時間從 budget 扣），（future 清單為空時跳過這段，例如 `ALWAYS_READY`）再建立 `new RealBlockWaterMap(level, seaY).enableOnDemand().withPredictor(predictor, budget)`；之後流程不變。
  - `submitAutoRoute`：一開始取 `Optional<TerrainPredictor> tellus = TellusTerrain.forLevel(level)`，`midMode = effectiveMode(midMode, tellus.isPresent())`；泊位探測水圖 `berthProbeMap` 也掛同一個 predictor 與 budget（每次請求新建一個 budget，泊位與航線共用）。

- [ ] **Step 1：寫失敗測試**：`tellusForcesSingleSegmentNbt`（`effectiveMode(NOISE, true)==NBT`、`effectiveMode(HYBRID, true)==NBT`、`effectiveMode(NOISE, false)==NOISE`）；`terrainStageText`（`terrainDownloadStage(3, 10)` 等於 `"下载地形 3/10"`）。
- [ ] **Step 2：** `$G test --tests '*WaterAutoRouteServiceTest'` → FAIL。
- [ ] **Step 3：** 實作上述變更；非 Tellus 路徑傳 `null` predictor，程式碼路徑與現在相同。
- [ ] **Step 4：** `$G test --tests 'com.monpai.sailboatmod.route.*'` → PASS。
- [ ] **Step 5：** Commit `feat: auto water routes use NBT plus Tellus prediction in Earth worlds`。

---

### Task 7：建立航線畫面說明文字

**Files：**
- Modify：`src/main/java/com/monpai/sailboatmod/client/screen/AutoRouteDockSelectionScreen.java`（模式頁籤建立處，約 75–77 行之後）
- Modify：`src/main/resources/assets/sailboatmod/lang/en_us.json`、`zh_cn.json`（`screen.sailboatmod.ui.route_mode.hybrid` 之後）
- Test：`src/test/java/com/monpai/sailboatmod/route/water/TellusHintLangTest.java`

**Interfaces：**
- Produces：語系鍵 `screen.sailboatmod.ui.route_mode.tellus_hint`
  - en：`In a Tellus Earth world the server always plans with real terrain plus existing blocks.`
  - zh_cn：`在 Tellus 地球世界中，服务器会自动使用真实地形＋既有方块规划。`
- 畫面：`ModList.get() != null && ModList.get().isLoaded("tellus")` 時，在頁籤下方加 `Ui.text(Component.translatable(key), "sm-muted")`，寬度跟頁籤列相同、允許換行（用現有 `Ui.paragraph`）。頁籤與封包不變。

- [ ] **Step 1：寫失敗測試**：讀兩個 json（en_us 去 BOM），斷言 en_us 與 zh_cn 的值分別**等於**上面兩個字串。
- [ ] **Step 2：** `$G test --tests '*TellusHintLangTest'` → FAIL。
- [ ] **Step 3：** 加鍵（保留 en_us 的 BOM 與 CRLF）與畫面文字。
- [ ] **Step 4：** `$G test --tests '*TellusHintLangTest' --tests 'com.monpai.sailboatmod.client.*'` → PASS。
- [ ] **Step 5：** Commit `feat: explain automatic Tellus routing on the route screen`。

---

### Task 8：前瞻檢查、繞行計算與拼接（純邏輯）

**Files：**
- Create：`src/main/java/com/monpai/sailboatmod/route/water/lookahead/LoadedChunkWaterColumns.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/water/lookahead/RouteLookahead.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/water/lookahead/LookaheadReroute.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/water/lookahead/RouteSplicer.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/repair/RoutePatch.java`（只有 record 與 `reversed()`；套用邏輯在 Task 10）
- Test：`src/test/java/com/monpai/sailboatmod/route/water/lookahead/RouteLookaheadTest.java`、`RouteSplicerTest.java`、`LookaheadRerouteTest.java`

**Interfaces：**
- Consumes：`WaterSurfaceRules.scanChunk`（Task 2）、`RealBlockWaterMap.seedChunk`、`hullClear`、`segmentHullClear`、`localRerouteNbt`（既有）、`RuntimeWaterAStar.findPath`（既有）。
- Produces：
  - `LoadedChunkWaterColumns.capture(LevelChunk chunk) → NbtChunkWaterReader.ChunkColumns`（用 `scanChunk`，minY/maxY 取 chunk 的建築高度）。
  - `RouteLookahead.corridorChunks(List<Vec3> route, int targetIndex, Vec3 boat, int lookaheadBlocks, int sideChunks, int cap) → Set<Long>`：從船位沿 route[targetIndex..] 累積 160 格，對每段做 supercover 取區塊並外擴 `sideChunks`，依距離先後加入，到 `cap` 為止。
  - `record Obstacle(int blockedSegment, int anchorBefore, int anchorAfter)`；`record Scan(int checkedUpTo, Optional<Obstacle> obstacle)`。
  - `RouteLookahead.scan(List<Vec3> route, int fromIndex, int checkedUpTo, int lookaheadBlocks, RealBlockWaterMap map, LongPredicate chunkLoaded, int halfWidth) → Scan`：從 `max(fromIndex, checkedUpTo)` 起逐段，段的所有區塊都已載入才檢查；`segmentHullClear` 通過就推進 `checkedUpTo`；第一個不通過的段 i：`anchorBefore` 為 ≤i 中最後一個 `hullClear` 的航點，`anchorAfter` 為 >i 中第一個 `hullClear` 且所在區塊已載入的航點；找不到 `anchorAfter` 時回傳 `obstacle=empty`、`checkedUpTo` 停在 i（等更多區塊載入）。
  - `LookaheadReroute.compute(RealBlockWaterMap map, BlockPos a, BlockPos b, int halfWidth) → List<BlockPos>`：`map.localRerouteNbt(a, b, halfWidth, 64, 20000)`，空則 `RuntimeWaterAStar.findPath(map, a, b, halfWidth, 256, 60000)`，再做貪婪拉直（從 i 找最遠的 j 使 `segmentHullClear(path[i], path[j])`）；失敗回傳空 list。
  - `record RoutePatch(List<Vec3> before, List<Vec3> after)`，`RoutePatch reversed()`。
  - `record Spliced(List<Vec3> route, int targetIndex, RoutePatch patch)`；`RouteSplicer.splice(List<Vec3> route, Obstacle o, List<BlockPos> replacement, int targetIndex, int maxWaypoints) → Optional<Spliced>`：替換 `route[anchorBefore..anchorAfter]`；替換段首尾強制使用原 `route[anchorBefore]`、`route[anchorAfter]` 的 Vec3，中間點為 `(x+0.5, y, z+0.5)`；`targetIndex <= anchorBefore` 時不變；`== anchorBefore+1` 時新 target 為 `anchorBefore+1`；`> anchorBefore+1` 或結果超過 `maxWaypoints` 時回傳 empty。`patch.before` = 原 `route[anchorBefore..anchorAfter]`，`patch.after` = 替換段。

- [ ] **Step 1：寫失敗測試**（`RealBlockWaterMap(null, 63)`，以 `seedChunk` 建立水陸地圖）
  - `RouteLookaheadTest.scanSkipsUnloadedSegments`：段落跨未載入區塊 → `obstacle` 空、`checkedUpTo` 停在該段前。
  - `RouteLookaheadTest.scanFindsWallAndAnchors`：x 0..255、z 0..63 全水（16×4 區塊），在 x=100 豎一道寬 1 格、z 從 0 到 40 的陸牆，航線 (0,20)→(64,20)→(128,20)→(192,20) → `blockedSegment==1`、`anchorBefore==1`、`anchorAfter==2`。
  - `RouteLookaheadTest.corridorIsCappedAt40`：直線長航線、`cap=40` → `size()==40`，且包含船所在區塊前方的第一個區塊。
  - `LookaheadRerouteTest.routesAroundShortWall`：同上地圖，`compute((64,63,20),(128,63,20),1)` 非空、首尾為 a、b、每段 `segmentHullClear`。
  - `RouteSplicerTest.splicesAndKeepsTarget`、`targetJustPastAnchorMovesToReplacement`、`targetBeyondAnchorRejects`、`rejectsSpliceOverWaypointCap`（250 點航線插入 10 點 → empty）、`patchCarriesExactOriginalSlice`。
- [ ] **Step 2：** `$G test --tests 'com.monpai.sailboatmod.route.water.lookahead.*'` → FAIL。
- [ ] **Step 3：** 實作五個檔案。
- [ ] **Step 4：** 同指令 → PASS。
- [ ] **Step 5：** Commit `feat: route lookahead scan, reroute and splice`。

---

### Task 9：`RouteLookaheadGuard` 接入帆船

**Files：**
- Create：`src/main/java/com/monpai/sailboatmod/route/water/lookahead/RouteLookaheadGuard.java`
- Modify：`src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java`（`updateAutopilotChunkLoading` 4023–4082 行；自動駕駛指令計算處約 2317–2350 行；`startAutopilotInternal` 1836 行起；`stopAutopilot` 1942 行起）
- Test：`src/test/java/com/monpai/sailboatmod/route/water/lookahead/RouteLookaheadGuardTest.java`、`src/test/java/com/monpai/sailboatmod/entity/LookaheadGuardWiringContractTest.java`

**Interfaces：**
- Consumes：Task 8 全部、`TellusWorlds.isTellusLevel`、`TellusTerrain.forLevel`（Task 5）、`RealBlockWaterMap.withPredictor/seedChunk`（Task 4）。
- Produces：
  - `interface Host { List<Vec3> route(); int targetIndex(); Vec3 position(); boolean autopilotActive(); boolean claimRerouteSlot(); void releaseRerouteSlot(); void applySplice(Spliced spliced); void escalateToFullRescue(); void onPatch(RoutePatch patch); }`
  - `RouteLookaheadGuard(Host host, LongFunction<NbtChunkWaterReader.ChunkColumns> loadedColumns, Supplier<RealBlockWaterMap> mapFactory, Executor background, Executor mainThread)`
  - `Set<Long> extraChunks()`（`RouteLookahead.corridorChunks(..., 160, 1, 40)`）；`void tick(long gameTime)`（`gameTime % 10 != 0` 直接返回）；`boolean shouldHold()`（有重規劃進行中，且船與 `route[obstacle.blockedSegment()]` 的水平距離 ≤48 格）；`void reset()`。
  - tick 流程：建 map → 把所有 `loadedColumns` 非 null 的走廊區塊 `seedChunk` → `scan` → 有障礙且 `claimRerouteSlot()` 成功 → 背景 `LookaheadReroute.compute` → 主線程回呼：`!autopilotActive()` 則丟棄並釋放；空路徑 → `escalateToFullRescue()`；`splice` 為 empty（船已越過）→ 以 `map.nearestHullWater(boatX, boatZ, 1, 16)` 為新起點重算一次，仍失敗才升級；成功 → `applySplice`、`onPatch(spliced.patch())`；最後 `releaseRerouteSlot()`。
  - `SailboatEntity`：新欄位 `@Nullable RouteLookaheadGuard lookaheadGuard`；`startAutopilotInternal` 成功時若 `TellusWorlds.isTellusLevel` 才建立；`stopAutopilot`／到站時 `reset` 並設 null；`updateAutopilotChunkLoading` 把 `extraChunks()` 併入 `requiredChunks` 後呼叫 `tick`；`claimRerouteSlot` 即現有 `asyncRerouteInFlight`；自動駕駛指令在 `shouldHold()` 時回傳 `EngineGear.STOP`；`escalateToFullRescue` 呼叫現有 `tryRerouteAndResumeAsync()`；`applySplice` 替換 `autopilotRoute` 並設 `autopilotTargetIndex`、重設 `autopilotNoProgressTicks`、`autopilotLastTargetDistance`；`onPatch` 在本任務是空實作（不寫回），Task 10 再接上 `RouteRepairService`。
  - `loadedColumns`：`serverLevel.getChunkSource().getChunkNow(cx, cz)` 非 null 時 `LoadedChunkWaterColumns.capture`。
  - `mapFactory`：`new RealBlockWaterMap(level, seaY).enableOnDemand()`，再掛 `TellusTerrain.forLevel` 的 predictor 與新的 `PredictionBudget.sixtySeconds()`。

- [ ] **Step 1：寫失敗測試**（假 Host、`Runnable::run` 當兩個 executor）
  - `detectsWallAndAppliesSplice`：同 Task 8 的牆地圖 → tick 後 `applySplice` 被呼叫一次、`onPatch` 一次、slot 被釋放。
  - `onlyOneRerouteAtATime`：`claimRerouteSlot` 回 false → `LookaheadReroute` 不執行、不 splice。
  - `resultIsDroppedWhenAutopilotStopped`：背景 executor 先暫存任務，執行前把 `autopilotActive` 設 false → 不 splice、不 patch、slot 釋放。
  - `escalatesWhenNoDetour`：牆封死整張圖 → `escalateToFullRescue` 一次。
  - `holdsWithin48BlocksWhilePending`：背景任務未執行時船位距障礙 40 格 → `shouldHold()==true`；60 格 → false。
  - `ticksOnlyEvery10`：`tick(3)` 不呼叫 `loadedColumns`。
  - `LookaheadGuardWiringContractTest`（讀 `SailboatEntity.java` 原始碼）：含 `lookaheadGuard.extraChunks()`、`lookaheadGuard.tick(`、`lookaheadGuard.shouldHold()`、`TellusWorlds.isTellusLevel(`。
- [ ] **Step 2：** `$G test --tests '*RouteLookaheadGuardTest' --tests '*LookaheadGuardWiringContractTest'` → FAIL。
- [ ] **Step 3：** 實作 guard 與 `SailboatEntity` 接線。
- [ ] **Step 4：** `$G test --tests 'com.monpai.sailboatmod.route.*' --tests 'com.monpai.sailboatmod.entity.*'` → PASS。
- [ ] **Step 5：** Commit `feat: sailboats verify Tellus routes ahead and detour before obstacles`。

---

### Task 10：修正回寫 `RouteRepairService`

**Files：**
- Create：`src/main/java/com/monpai/sailboatmod/route/repair/RouteRepairService.java`
- Create：`src/main/java/com/monpai/sailboatmod/route/repair/RouteRepairSavedData.java`
- Modify：`src/main/java/com/monpai/sailboatmod/route/repair/RoutePatch.java`（加套用邏輯）
- Modify：`src/main/java/com/monpai/sailboatmod/route/water/WaterAutoRouteService.java`（`isGeneratedWaterRoute` 由 private 改 public static）
- Modify：`src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java`（新增 `public int getSelectedRouteIndex()`；`onLoad` 伺服器端呼叫 `RouteRepairService.applyPending`）
- Modify：`src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java`（Task 9 的 `onPatch` 實作）
- Test：`src/test/java/com/monpai/sailboatmod/route/repair/RoutePatchTest.java`、`RouteRepairSavedDataTest.java`

**Interfaces：**
- Consumes：`RoutePatch`（Task 8）、`RouteDefinition`、`WaypointMeta`、`DockBlockEntity.setRoutes/getRoutesForMap`（既有）。
- Produces：
  - `Optional<RouteDefinition> RoutePatch.applyTo(RouteDefinition route)`：非自動水路航線 → empty；在 waypoints 中找 `before` 的**唯一**連續出現（`Vec3.equals`）則替換為 `after`；否則找 `reversed().before` 的唯一出現並替換為 `reversed().after`；都沒有或出現多次 → empty。`routeLengthMeters` 依新航點重算；若原 `waypointMetas.size()==waypoints.size()`，替換段的 metas 改為 `new WaypointMeta(WaypointMeta.SEGMENT_MID, WaypointMeta.ORIGIN_RAW)`，其餘保留；否則 metas 維持空。其他欄位不變。
  - `static List<RouteDefinition> RouteRepairService.applyToRoutes(List<RouteDefinition> routes, RoutePatch patch)`：逐一 `applyTo`，回傳新清單（無變更時回傳原清單內容）。
  - `static void RouteRepairService.submit(ServerLevel level, RoutePatch patch, @Nullable BlockPos... dockPositions)`：每個碼頭位置若 `level.isLoaded(pos)` 且是 `DockBlockEntity` → `setRoutes(applyToRoutes(...), getSelectedRouteIndex())`；否則 `RouteRepairSavedData.get(level).enqueue(dimensionId, pos, patch)`。
  - `static void RouteRepairService.applyPending(ServerLevel level, BlockPos pos, DockBlockEntity dock)`：取出該碼頭所有 pending，依序套用，清除後 `setDirty()`。
  - `RouteRepairSavedData`（`DATA_NAME = "sailboatmod_route_repairs"`，比照 `TownDemandSavedData.get(Level)` 的 Factory 寫法）：`enqueue(String dim, BlockPos pos, RoutePatch)`、`List<RoutePatch> drain(String dim, BlockPos pos)`；每碼頭上限 32 筆，超過丟最舊；NBT 以每個 Vec3 三個 double 存。
  - `SailboatEntity.onPatch`：先 `routeCatalog` 套用 `applyToRoutes` 並 `updateRouteSyncData()`，再 `RouteRepairService.submit(level, patch, routeDockPos, findAutopilotDestinationDockPos())`。

- [ ] **Step 1：寫失敗測試**
  - `RoutePatchTest.appliesForward`、`appliesReversedToReturnRoute`、`rejectsManualRoute`（名稱不以 `Auto-` 或 `Water Auto: ` 開頭）、`rejectsWhenSliceChanged`、`rejectsAmbiguousSlice`、`applyingTwiceIsANoOp`、`recomputesLength`、`replacedMetasAreRaw`、`metasStayEmptyWhenMisaligned`。
  - `RouteRepairSavedDataTest.roundTripsThroughNbt`、`drainRemovesEntries`、`capsAt32PerDock`。
- [ ] **Step 2：** `$G test --tests 'com.monpai.sailboatmod.route.repair.*'` → FAIL。
- [ ] **Step 3：** 實作上述變更。
- [ ] **Step 4：** `$G test --tests 'com.monpai.sailboatmod.route.*' --tests 'com.monpai.sailboatmod.entity.*' --tests 'com.monpai.sailboatmod.block.*'` → PASS。
- [ ] **Step 5：** Commit `feat: write lookahead detours back to auto water routes`。

---

### Task 11：完整驗證與交回

**Files：**
- Create：`docs/handoff/TELLUS_ADAPTATION_RETURN.md`
- 產出：`/home/boyang/Desktop/online market/sailboatmod-tellus-adaptation-return-1.21.1-neoforge.zip`

- [ ] **Step 1：** `$G test releaseMod -PfixIndex=5` → BUILD SUCCESSFUL，記錄 suites／tests／failures 數字。
- [ ] **Step 2：** `unzip -l build/libs/<主 jar> | grep -c yucareux` → 0。
- [ ] **Step 3：** 啟動驗證：`run/mods/` 不放 Tellus、普通世界 `$G runServer` → 出現 `Done (` 且無 sailboatmod 例外；再放入 Tellus 並用 `level-type=tellus\:earth` 重跑 → 同樣成功；兩次後都 `stop`。
- [ ] **Step 4：** 寫 `TELLUS_ADAPTATION_RETURN.md`（繁體中文）：變更摘要、共用核心變更（`build.gradle`、`mods.toml`、新存檔 `RouteRepairSavedData`、`DockBlockEntity`／`SailboatEntity`／`WaterAutoRouteService` 修改，協定仍 12）、spike 結論、實際執行的測試與數字、未驗證項目、使用者 Windows 遊戲內驗證清單（規格第 10 節四項）。
- [ ] **Step 5：** Commit `docs: Tellus adaptation return notes`；`git archive --format=zip --prefix=sailboatmod/ HEAD -o "/home/boyang/Desktop/online market/sailboatmod-tellus-adaptation-return-1.21.1-neoforge.zip"`；確認 zip 內沒有 `run/`、`build/`、`.gradle/`、`gradlew.lf`；記錄 SHA-256。
