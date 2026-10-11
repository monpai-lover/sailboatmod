# 交接快照驗證狀態

驗證日期：2026-10-12。目標：Minecraft 1.21.1、NeoForge 21.1.259、Java 21；分支 `1.21.1-neoforge`。

- 完整 JUnit：**1,267 項，1,265 通過，2 失敗，0 error，0 skipped**。未排除任何測試。生產／測試 Java 全部可編譯；核心測試均通過。
- `releaseMod -x test` 另行驗證主模組及網頁 addon 打包。完整 `build`／`releaseMod` 仍會因下列 2 項網頁測試失敗；不能宣稱完整發行檢查全綠。
- 新增的座位高度、舊 ItemStack NBT、封包邊界與 SQLite 開發資源測試通過。座位修正曾驗證原遷移會將玩家坐姿降低 0.25 方塊。學校／工作站補上模型，牆面城鎮旗幟改用既有正確材質；7 個資源定義及引用均有效。
- 新的隔離 `run-neoforge/` 開發環境已完成客戶端模組載入、資源初始化及正常關閉；服務端完成啟動，SQLite schema 001–008 與資料庫初始化，網頁 addon 啟動。`GET /api/debug/version` 回傳 addon 1.3.13；`GET /api/markets` 回傳新測試世界的空列表。修正了開發環境缺少內嵌 SQLite 資源的問題。Gradle 沒有轉送服務端 console 輸入，檢查後已停止該測試程序；未驗證完整停服流程。
- 主模組／addon 的 NeoForge metadata、共用類及 nested classes、無重複 class／split package、8 個 JarJar 依賴、SQLite 惰性資源與 ZIP CRC 已核對。執行啟動檢查使用開發 module；尚未做正式 JAR 安裝後的多人遊戲、完整交易／運輸或既有世界升級測試。未操作使用者既有世界。
- `node --check` 的 `app.js`、`map.js` 通過。`node tools/check_marketweb_frontend.js` 仍有 2 項舊斷言失敗：期待 `SailboatMarketMap.mount` 與 `/api/map/tile/lod_1/`，目前前端使用新版 mount 接入及 Leaflet square tile URL。

## 留給 Claude 的 2 項 JUnit 失敗

| 測試 | 已確認原因與接手方向 |
| --- | --- |
| `MarketWebAppResourceTest.mapClientUsesSquaremapLeafletTileRenderer` | 舊斷言期待 `L.TileLayer.extend` 的 fetch/blob 自訂圖層；目前原始 HEAD 已使用不同的 Leaflet tile flow。重構時保留快取唯讀／未知瓦片不強載入的限制，重新驗證實際 renderer 契約。 |
| `MarketWebMapRegionImageTest.doesNotPublishPartialRegionPixelsBeforeAllChunksAreReady` | 原始 Forge HEAD 也失敗；現有 `writeDirty` 允許部分 region 像素發布，與測試「等完整 region」的假設衝突。依實際地圖流式渲染需求處理，不能直接刪斷言。 |

三項 CSS 測試原先因 Windows CRLF 與 LF 字串斷言不一致而誤報，現在只在測試讀取 CSS 時正規化換行；網頁 JavaScript／CSS／HTML 實作未重構。其內容與原始 Git HEAD 相同。其餘核心舊斷言／假世界 fixture 已對照原始 Forge 1.20.1、Java 17 與精確舊依賴重現：主要 92 項中 12 失敗；補充 75 項中 6 失敗，再更新測試以驗證目前已存在的行為。

網頁認證的 SavedData 競態仍交給 Claude：Minecraft server thread 上串行化資料存取與帳號唯一性檢查／寫入，PBKDF2 保留在工作執行緒。細節與核心修改邊界見 `CLAUDE_WEB_MARKET_HANDOFF.md`。

機器可讀結果在 `VERIFICATION_RESULTS.json`。本機診斷證據位於 `build/neoforge-final-verification-3.log`、`build/neoforge-server-smoke-2.log`、`build/neoforge-final-client-artifact-verification.log`、`build/baseline-verification/`；原始 log 與世界資料不進交接包。

ZIP 打包會核對清單、逐檔 SHA-256、ZIP CRC 與打包期間來源未變動；`HANDOFF_MANIFEST.json` 記錄來源分支、commit 與每檔雜湊。ZIP 完整性不等於所有遊戲行為通過。請先讀 `CLAUDE_WEB_MARKET_HANDOFF.md`，再接手網頁市場重構。
