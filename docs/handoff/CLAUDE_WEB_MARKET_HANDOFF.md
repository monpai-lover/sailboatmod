# Claude 網頁商城重構交接

此交接包用於 **Minecraft 1.21.1 / NeoForge 網頁商城重構**。Codex 負責主模組遷移及後續整合，Claude 負責網頁商城的前端、HTTP 介面層及其認證問題。遷移分支 `1.21.1-neoforge` 已推送並設為 GitHub 預設分支。ZIP 的來源 commit 與逐檔內容以 `HANDOFF_MANIFEST.json` 為準；這是開發原始碼快照，不含 Git 歷史，也不是正式發行檔。

## 版本與工作邊界

以 `gradle.properties`、`build.gradle`、`settings.gradle` 及 `HANDOFF_MANIFEST.json` 為準：

| 項目 | 交接目標 |
| --- | --- |
| 分支 | `1.21.1-neoforge` |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.259` |
| Java | `21` |
| Gradle wrapper | `9.2.1` |
| Gradle plugin | `net.neoforged.moddev` `2.0.148` |
| 主模組 ID | `sailboatmod` |
| 網頁 addon ID | `sailboatmarketweb` |
| 模組版本 | `1.3.13`；最終以屬性檔為準 |

根目錄 `AGENTS.md`、`CLAUDE.md`、`README.md` 已更新為 NeoForge 1.21.1 / Java 21；`README.txt` 仍是歷史 Forge 1.20.1 說明。不要依照舊文件將本分支改回 Forge。現在已有 `src/test/java` 與 JUnit 5；不要假設專案沒有測試。

可以直接修改：

- `src/main/resources/marketweb/**`：HTML、CSS、原生 JavaScript、UI 設定、品牌圖與隨附前端 vendor。
- `src/main/java/com/monpai/sailboatmod/market/web/**`：HTTP、認證、JSON 轉換、addon lifecycle、網頁地图及圖示服務。
- `src/marketweb/resources/**`：addon metadata。
- `tools/check_marketweb_frontend.js`、`tools/marketweb_mock_server.js`，以及上述範圍對應測試。

下列是共享核心，修改前先和 Codex 的遷移工作協調，提交時把這些修改單獨列出：

- `build.gradle`、`gradle.properties`、`settings.gradle`，`SailboatMod.java`、`ModConfig.java`。
- `network/**`、`compat/**`、`client/marketweb/**` 與 `client/MarketClientHooks.java`。
- `block/entity/MarketBlockEntity.java`、`TownWarehouseBlockEntity.java`、`DockBlockEntity.java`、`PostStationBlockEntity.java`。
- `market/**` 中 `web` 以外的訂單、錢包、SQLite、定價、物流、終端資料與服務。
- `nation/**`、`dock/**`、`route/**`、`entity/**`、`roadplanner/**`、`economy/**`、`integration/**`。
- `market/MarketPlayerIdentity.java`、`market/logistics/DebugRouteNodeCache.java`、`market/map/MarketWebMapConstants.java`、`market/map/MarketWebMapDtos.java`：是 **main JAR 共用資料類**，核心物流與 web addon 均使用；不要在 addon 重複封裝，修改契約與 nested record 須協調。

完整 Java 原始碼放在 ZIP 內是為了能編譯、追蹤依賴及理解行為；不代表把整個版本遷移交給 Claude。先保留現在的交易語意，再拆分前端狀態／渲染／請求及 HTTP 路由／JSON 轉換等責任。若要更換框架或引入打包工具，必須同步處理資源路由、快取、addon JAR 內容與現有驗證，不能只交付框架原始碼。

## 從這些入口開始

路徑下述 Java package 均位於 `src/main/java/com/monpai/sailboatmod/`。

| 檔案 | 責任及相依 |
| --- | --- |
| `market/web/SailboatMarketWebAddon.java` | `@Mod("sailboatmarketweb")` 入口。 |
| `market/web/MarketWebEvents.java` | server started/stopped 啟停 HTTP 與 region watcher；server tick 推進背景地圖；註冊命令。 |
| `market/web/MarketWebServer.java` | JDK `HttpServer`、路由、Bearer session、靜態檔案／dev mode、HTTP 錯誤、圖示／地图端點、`callOnServerThread`。 |
| `market/web/MarketWebService.java` | HTTP 身分轉成主模組呼叫，建立 market/listing/wallet/order JSON；不是獨立交易引擎。 |
| `market/MarketPlayerIdentity.java` | UUID、名字、可為 null 的線上玩家。離線已登入者仍有 UUID；訪客 UUID 為 null。 |
| `market/web/MarketWebAuthManager.java` | 固定遊戲 token、帳號綁定／密碼登入、記憶體 session、PBKDF2。下節有尚未修正的競態。 |
| `market/web/MarketWebAccountSavedData.java` | 帳號 NBT、username/UUID 雙索引。 |
| `market/web/MarketWebTokenSavedData.java` | 遊戲 token NBT；目前是玩家固定 token，不能因舊設定註解誤改成一次性 token。 |
| `market/web/MarketWebCommands.java` | `/marketweb` 與 `/webmarket`；token、reload、version、地图背景渲染／修復及 debugroute。 |
| `market/web/MarketWebIconService.java` | 物品模型／材質圖示與快取，可能讀取世界目錄中的下載素材。 |
| `market/web/map/MarketWebMapLayerService.java`、`market/map/MarketWebMapDtos.java`、`market/web/map/MarketWebMapJson.java` | 终端、領土、旗幟、物流軌跡 DTO 與 JSON；共用 DTO 由主模組提供。 |
| `market/web/map/MarketWebMapRenderService.java`、`MarketWebMapRenderManager.java` | 地圖快照、佇列、離線 region 讀取、背景渲染管理。 |
| `market/web/map/MarketWebMapTileCache.java`、`MarketWebMapPyramidWriter.java` | LOD／square 磁碟瓦片、metadata、縮放金字塔。HTTP 查詢不得強載入／生成世界區塊。 |

前端全部位於 `src/main/resources/marketweb/`，目前無 npm build：

- `index.html` 是頁面容器與穩定 DOM ID；先載入 Lightweight Charts、Leaflet，再載入 `map.js`、`app.js`。
- `app.js` 是大型全域脚本。入口狀態 `state`、`els`，包含雙語字典、登入、API、商品／庫存／求購、圖表、錢包、訂單和頁面路由。`renderDetail` / `renderCommodityDetailPage` / `renderMyOrdersPage` / `mountMarketMapIfNeeded` 是主要渲染入口；`escapeHtml` 保護動態 HTML。
- `app.css` 包含桌面／手機佈局、Safari 模式、图表及地图样式。
- `map.js` 封裝地圖，公開 `window.SailboatMarketMap`，也以 `window.SailboatSquareMap` 作別名；使用 Leaflet／canvas、瓦片、領土／市場／旗幟和配送軌跡；mount/unmount 時處理輪詢及監聽器。
- `config.json` 是 UI 預設值；HTTP 監聽、dev mode、地图效能設定在 Java `ModConfig`，兩者不要混用。
- `vendor/leaflet/**`、`lightweight-charts.standalone.production.js` 是離線可用的 vendor 資源。

前端目前以 `localStorage` 的 `marketWebSessionToken` 儲存 session，`marketWebLocale`、`marketWebBrowseOrderView`、`marketWebAccessPanelOpenV2` 等儲存 UI 偏好。拆分時保留既有登入及偏好相容性，避免 reload 後重複監聽、地图輪詢或圖表實例洩漏。

## 認證競態：已確認，尚未修正

**這份交接中的 `MarketWebAuthManager` 尚未修正 HTTP 執行緒與 SavedData 的資料競態。** `MarketWebServer.handleTokenLogin` 與 `handlePasswordLogin` 直接從 HTTP worker 呼叫 auth；auth 讀取 `MinecraftServer.overworld().getDataStorage()`、token SavedData 及 account SavedData。account 的 `LinkedHashMap` 及 SavedData dirty/save lifecycle 沒有提供同步保護。

`bindAccount` 先讀取 username 是否占用，再執行 PBKDF2，最後 `data.upsert(entry)`。兩個不同玩家並行綁定相同 normalized username，都可能通過前置檢查；後寫入者在 `upsert` 中移除原本帳號 UUID 索引，造成覆蓋或帳號遺失。只把兩個 map 改成 `ConcurrentHashMap` 無法修正這個 check-then-write 問題。

Claude 應在保留既有 auth JSON／錯誤碼及 NBT 結構下修正：

1. token／帳號 SavedData 的取得、讀取、修改、dirty 標記均透過 Minecraft server thread 序列化，不從 HTTP worker 直接存取世界或 DataStorage。
2. 從 server thread 取得不可變帳號或 token 快照，再在 HTTP／專用 worker 執行 PBKDF2；不要將 120,000 次 PBKDF2 或 HTTP 等待搬到遊戲 tick。
3. 綁定回到 server thread 時，在同一段原子操作中重新驗證 token 身分、username 唯一性、player 原帳號及建立時間，再 upsert；重名失敗回傳既有 `username_taken`。
4. 密碼驗證完成後，在發行 session 前重新驗證帳號仍與快照一致，避免綁定／改密碼競態使舊憑證登入新帳號。
5. 不在 server thread join 等待需要同一 server thread 完成的工作；現有 `callOnServerThread` 有 `isSameThread()` 分支，可作為模式參考。確認停服時 pending 工作不會留下永遠等待的 HTTP 請求。
6. 增加真正的並行回歸測試：不同 UUID 綁定同 username 僅一個成功、雙索引一致、同玩家重綁、綁定與登入並行、停服／儲存時無跨執行緒 SavedData 存取；驗證 PBKDF2 在遊戲執行緒外執行。

既有密碼演算法為 `PBKDF2WithHmacSHA256`，salt 16 bytes、迭代 120,000、key 256 bits；username 3–24 個小寫字母／數字／底線，password 6–72 字元。session 目前存在記憶體中，期限七天。`webLoginTokenTtlMinutes` 現行傳入固定 token 的發行方法，卻沒有實際套用 TTL；此為現狀說明，不要在重構中自行改 token 壽命。

## HTTP／JSON 契約

完整內容以 `MarketWebServer` 路由與 `MarketWebService` JSON 為準。一般 JSON 錯誤為 `{ok:false,errorCode,message}`，成功操作可能回傳更新後 market detail，或 `{ok:true}`。需登入的端點使用 `Authorization: Bearer <sessionToken>`，無效／缺少身分回 401；部分只讀端點接受訪客。

| 方法與路徑 | 重點 |
| --- | --- |
| `POST /api/auth/token-login` | `{token,username,password}`；未填 username/password 是 token 登入，填任一則走綁定。成功有 `sessionToken,playerUuid,playerName,accountUsername,accountBound,newlyBound`。 |
| `POST /api/auth/password-login` | `{username,password}`；支援離線玩家帳號。 |
| `GET /api/session/me` | 驗證 session；回玩家身分、online、accountBound／Username、resource/icon version。 |
| `GET /api/debug/version` | addon／resource／icon 版本，用來確認瀏覽器与伺服器資源。 |
| `GET /api/markets`、`GET /api/markets/{marketId}` | 访客可讀；列表包在 `markets`。marketId 由 `encodeMarketId` 生成，包含 dimension 與 BlockPos 編碼，視為不透明字串。 |
| `GET /api/listings`、`GET /api/listings/commodity?key=...` | 全服聚合、來源城鎮與可購買性；commodity key 如 `minecraft:diamond` 經 query encoding 傳送。 |
| `POST /api/listings/{listingId}/purchase` | `{executorMarketId,quantity,fulfillment,targetWarehouse}`；不能把全域 listingId 與終端 listingIndex 混用。 |
| `POST /api/markets/{id}/purchase` | `{listingIndex,quantity,fulfillment,targetWarehouse}`；預設 `SELLER_SHIP`，後端重新驗證。 |
| `POST /api/markets/{id}/probe-modes` | `{listingIndex,targetWarehouse}`；回 `sellerShip,autoPickup,realPickup`。 |
| `POST /api/markets/{id}/listings` | `{storageIndex,quantity,unitPrice,sellerNote}`；unitPrice 是實際整數單價。 |
| `POST /api/markets/{id}/listings/{listingId}/cancel` | 拆分及購買後用 listingId 執行取消。 |
| `GET /api/markets/{id}/minecolonies-warehouse`、`POST /api/markets/{id}/minecolonies-listing` | 項目查詢；建立 body 為 `{colonyId,itemId,quantity,unitPrice,sellerNote}`，維持反射整合／權限。 |
| `POST /api/markets/{id}/buy-orders`、`.../buy-orders/{orderId}/cancel` | 建立 `{commodityKey,quantity,minPriceBp,maxPriceBp}`，bp 與實際 UI 單價的轉換不得改定價語意。 |
| `POST /api/markets/{id}/purchase-orders/{orderId}/cancel` | 我的訂單取消與退款，保留相關資格與狀態檢查。 |
| `POST /api/markets/{id}/dispatch/retry` | `{orderIndex,terminalType}`；使用核心配送／終端判定。 |
| `POST /api/markets/{id}/credits/claim`、`.../wallet/transfer` | transfer body `{action,amount}`，amount 是整數 ledger 金額。 |
| `GET /api/items/resolve?itemId=...` | 解析任意已註冊物品，回預覽／建議價。 |
| `GET /api/icons?commodityKey=...`、`GET /api/icons/batch?...` | 批次 missing 必須允許單圖 probing 與穩定 fallback。 |
| `GET /api/map/snapshot?focusedMarketId=...`、`.../markets`、`.../territories` | 地圖快照、標記及領土；只讀訪客模式。 |
| `GET /api/map/shipments` | 需要登入，依該身分過濾物流及手動航線。 |
| `GET /api/map/tile/lod_1/{x}_{z}.png`、`.../square/tile/overworld/{zoom}/{x}_{z}.png` | 讀取既有快取瓦片；未知瓦片不觸發強載入。 |
| `GET /api/map/flags/{flagId}`、`GET /api/map/render/status` | 旗幟與背景渲染進度，實際 URL 分段解析以原碼為準。 |

頁面直接 URL `/browse`、`/inventory`、`/sell`、`/buy`、`/demand`、`/chart`、`/index`、`/map`、`/all`、`/my_orders` 都由 HTTP 層回傳 `index.html`。若新拆分為多個 JavaScript/CSS 檔，需更新靜態路由，確保 vendor 子目錄和新資源可以由正式 JAR 讀到，不僅在 mock server 中可用。

market detail 要保留的分組：市場與 `canManage`；linkedDock 及 linkedWarehouse 相容欄位；wallet available/reserved/total、cash、treasury、pendingCredits；`canChooseReceiving` / `receivingWarehouseOptions`；`storageEntries,listings,buyOrderEntries,myOrders,sourceOrders,shippingEntries`；`priceCharts,commodityBuyBooks,candleSeries,impactSnapshots,analyticsSeries,chartCapabilities`。新增欄位可相容擴充，刪除／改名需同步 consumer 與測試。

## 交易與資料約束

- 所有購買、掛單、取消、配送、收款與帳務由 server 重新驗證。不能把 UI 的 `canManage`、`purchasable`、probe 結果視為授權，也不能用客戶傳入的 UUID／名字建立身分。
- `MarketBlockEntity` 是終端操作入口，`MarketSavedData` 管 listings／purchase／shipping／pending credits；`market/commodity/CommodityMarketService`、`market/db/**` 管 SQLite 商品／求購／歷史；`market/wallet/MarketWalletService` 管玩家錢包。不要另建 web-only 餘額或交易系統。
- `GoldStandardEconomy`／`VaultEconomyBridge` 可用時接經濟 provider，沒有 Vault 時走核心金本位／倉庫路徑；保留 null/true/false 三態語意。充值必須先真實扣款再入錢包；提款給付失敗要準確退回，不能重試重複入帳。
- 访客不能取得個人餘額、現金、國庫權限、pending credits 或我的訂單；現有 JSON 將相關數值設為 0，`myOrders` 為空。離線已認證 UUID 不能被當成訪客，也不能依名字挪用其他帳戶。
- 國庫提款須 `NationService.hasPermission(..., MANAGE_TREASURY)`；market owner、城鎮／倉庫 ownership、收貨權限由共用服務判斷。
- `market/terminal/TerminalVisibility` 與 `MarketReachability`／配送服務處理碼頭／驛站可達性；聚合掛單可以顯示來源與不可買原因，但購買時仍須重新驗證 viewer town 與 executor terminal。
- `FulfillmentMode` 的 `SELLER_SHIP` / `AUTO_PICKUP` / `REAL_PICKUP`、目的倉庫、pickup lock、seller queue、取消／退款資格與配送狀態保持原核心語意。購買快照不能因前端篩選或排序誤用舊 listingIndex。
- 地圖物流 DTO 保留 `current,points,completedPointCount,progressRatio,transportMode,status,manual` 等欄位；不把任意玩家的私人物流暴露給訪客。地图 layer 不得破壞 region 非阻塞讀取、unknown 色、renderer metadata、cache 優先級及背景 budget。
- NBT 持久化須保留現有 data name／欄位及 Registry provider 相容層；SQLite schema 若需要變動，沿 `MarketSchemaManager` 追加 migration，另與 Codex 協調。不能要求用戶刪除原世界或数据库才可使用。
- `ModNetwork`、`CopyMarketWebTokenPacket`、`network/packet/marketweb/MarketWebMapTileUploadPacket` 和 `client/marketweb/**` 在主模組，addon 使用它們；遷移中的 NeoForge 網路相容層不能由前端重構改回舊 Forge SimpleChannel。

## 配置與建置

共同設定在 `ModConfig.COMMON_SPEC` 的 `[market]`：`webEnabled=true`、`webBindHost="0.0.0.0"`、`webPort=11450`、`webDevMode=false`、`webDevRoot=""`，以及 `webMap*` 背景渲染配置。主模組註冊 common config。開發時可將 webDevRoot 指向本地 `src/main/resources/marketweb`，但不要將個人絕對路徑或真實伺服器設定提交。

Windows PowerShell 在解壓後的 `sailboatmod` 根目錄：

```powershell
.\gradlew.bat compileJava
.\gradlew.bat marketWebJar
.\gradlew.bat releaseMod
```

其他系統對應 `./gradlew`。需要 Java 21 與首次 Gradle／NeoForge／Maven 下載的網路；ZIP 不含依賴快取。

`marketWebJar` **不是獨立 sourceSet 編譯**：它依賴 `classes`、`processMarketWebResources`，從完整 main output 挑出 `com/monpai/sailboatmod/market/web/**`、`marketweb/**`、`assets/minecraft/**`，再加入 `src/marketweb/resources` 生成的 metadata。主 JAR 完整排除 `market/web/**` 與 `marketweb/**`。四組共用資料類及其 nested classes 已移至核心 `market`、`market/logistics`、`market/map` package，僅由主 JAR 提供。addon 的 Java package 保持 `market/web/**`，避免 Java module split-package。核心 `ShippingTrace` 等依賴這些類，未安裝 addon 的主模組也必須能載入。共用 terminal SavedData 在主模組 `market/terminal`。拆分前後均檢查兩個 JAR 的實際內容。

`bundleMarketWebMinecraftAssets` 會從 `-PmarketWebMinecraftAssetsSource=<path>` 或預設 `src/generated/marketweb-minecraft-assets` 選取 Minecraft icon 模型／材質，輸出到 build generated resources。此快取素材不在交接 ZIP，缺少時可正常建置並使用圖示 fallback；要驗證完整遊戲物品圖示需另準備對應 1.21.1 素材，不要用舊 1.20.1 快取替代。該 task 不代表 package 會包含本機下載目錄或世界資料。

`releaseMod` 依賴 `build` 與 `marketWebJar`，輸出 `build/libs/`；目標名稱為 `sailboatmod-<version>-neoforge-1.21.1.jar` 和 `sailboatmod-marketweb-<version>-neoforge-1.21.1.jar`，額外 JarJar 產物以 task 輸出為準。`-PfixIndex=1` 會加入 `-fix_1`。NeoForge 分支不要沿用 README 中 SRG `-reobf.jar` 的假設。正式伺服器需安裝主模組及 addon，保留 metadata 的主模組版本相依。

## 驗證與交付

先記錄初始 baseline，再修改；交接包不保證歷史 source-contract 測試已全部適配遷移。任何既有失敗要區分 loader 遷移、過度精確原碼字串匹配、真實行為回歸，不能以刪測試掩蓋問題。

```powershell
node --check src/main/resources/marketweb/app.js
node --check src/main/resources/marketweb/map.js
node tools/check_marketweb_frontend.js
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.*"
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.marketweb.*" --tests "com.monpai.sailboatmod.client.marketweb.*" --tests "com.monpai.sailboatmod.BuildPackagingTest"
.\gradlew.bat releaseMod
```

重構時重要測試有 `WebFrontendContractTest`、`MarketWebContractTest`、`CancelOrderWebContractTest`、`NoWarehouseDeliveryContractTest`、`ReceivingWarehouseContractTest`、`market/wallet/*Test`、`market/web/*Test`、`market/web/map/*Test`、`BuildPackagingTest`。其中不少測試讀原始碼檢查字串；拆分檔案時可更新定位／斷言，但必須保留同樣業務限制，並為認證競態補行為測試。

前端視覺與互動預覽：

```powershell
node tools/marketweb_mock_server.js 8787
```

瀏覽器開 `http://127.0.0.1:8787/`；若占用，脚本會找後續可用 port。mock 使用假帳號／行情，未完整模擬聚合列表、地圖、權限、真實扣款與配送；preview 成功不能替代 Java／真實 server 驗證。

在測試世界的真實 NeoForge server 確認：訪客瀏覽／個人資訊遮蔽、遊戲 `/marketweb token` 綁定、離線密碼登入、同 username 並行綁定、終端／聚合購買、三種 fulfillment／收貨倉庫、掛單／求購／取消退款、錢包轉移成功與 rollback、國庫權限、地图缺瓦片／領土／本人物流、reload／頁面直接 URL／手機／Safari、停服後無 worker 或輪詢洩漏。這些操作只在新測試世界執行。

Claude 回交時提供：修改範圍與共享核心清單、API／持久化相容性說明、實際執行的檢查及其輸出、仍有的 baseline 失敗、認證競態回歸證據、需要 Codex 接合的修改。不要把 production world、token、密碼雜湊、SQLite DB、cookies、真實玩家資料或伺服器日志加入回交檔。

## 交接包內容與重現

`tools/package_web_market_handoff.py` 使用明確白名單，包入：根建置／wrapper／說明／授權檔；`gradle/wrapper/**`；完整 `src/main/java/**`、`src/test/java/**`、`src/test/resources/**`、`src/main/resources/**`、`src/marketweb/resources/**`；存在時的 `src/generated/resources/**`；本交接資料夾；兩支 frontend tool；打包脚本；與網頁市場／地图／物流及 NeoForge 遷移有關的既有 spec／plan。

`src/main/resources` 全部僅數 MB，保留已整理的 NPC 小材質以免編譯後資源或測試缺檔。根目錄龐大的 `NPCskins/` 生成素材不包含。`.git/`、`.gradle/`、`build/`、`run*/`、`.worktrees/`、`logs/`、`.claude/`、`.superpowers/`、根 `assets/` / `pixel/` / `Structure/` / `libs/`、下載的 `marketweb-minecraft-assets/`、世界、資料庫、個人設定、環境檔與秘鑰均不在白名單。可用無下載圖示 fallback 開發 UI。

完成遷移檢查、暫停所有會修改上述來源的工作後，由 Codex 執行：

```powershell
python tools/package_web_market_handoff.py --list
python tools/package_web_market_handoff.py
```

脚本於 `build/handoff/` 的新暫存目錄 staging，在 `artifacts/sailboatmod-web-market-handoff-1.21.1-neoforge.zip` 輸出交接包及 `.sha256`；ZIP 內 `sailboatmod/HANDOFF_MANIFEST.json` 記錄 branch／base commit、白名單、每檔 bytes／SHA-256。使用固定 ZIP 時間與排序，使同一內容可重現；交付前會核對 ZIP 清單、內容雜湊、CRC，並重新檢查來源未在打包中途改動。ZIP 內檔案清單與 manifest 是實際包內容的權威紀錄。ZIP 的 CRC／SHA-256 驗證只證明檔案完整，不代表遊戲編譯或行為測試已通過。
