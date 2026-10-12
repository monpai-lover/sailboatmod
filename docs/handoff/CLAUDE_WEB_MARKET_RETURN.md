# Claude 網頁商城回交說明

對應 `CLAUDE_WEB_MARKET_HANDOFF.md`。本次回交只涵蓋**網頁商城前端的視覺與版面重構**；交接文件中的認證競態修正**尚未處理**（見最後一節）。

## 修改範圍

僅以下檔案有變動，全部位於交接文件允許直接修改的範圍：

| 檔案 | 變動 |
| --- | --- |
| `src/main/resources/marketweb/app.css` | 整份重寫為「Trading Hall」視覺系統：深色／亮色兩組 token、物品格（inventory slot）、篩選側欄、動效。 |
| `src/main/resources/marketweb/index.html` | 頁框重排：區段分頁移到頂欄 `#workspace-nav`、終端列表改為頂欄下拉 `#terminal-panel`、新增主題切換 `#theme-toggle` 與載入前套用主題的腳本。原有靜態 DOM ID 全部保留。 |
| `src/main/resources/marketweb/app.js` | 只改渲染與 UI 狀態：主題切換、終端下拉、`renderMarketStrip`、`renderCatalogShelf` 側欄版型、商品頁購買框、`playViewMotion` 動效控制、圖表顏色改讀 CSS token。購買／掛單／錢包／登入／地圖的請求與交易邏輯未改。 |
| `tools/check_marketweb_frontend.js` | 原本鎖定舊「液態玻璃」外觀的 3 項斷言，改為鎖定新設計系統、主題可切換且持久化、動效只在變化時播放。其餘斷言未動。 |
| `src/test/java/.../market/web/MarketWebAppResourceTest.java` | 原 `goodsCardsUseLiquidGlassFlowWithReducedMotionFallback` 改為 `goodsCardsUseRaritySlotsWithReducedMotionFallback`，斷言改為物品格與稀有度著色；reduced-motion 與「卡片不加裝飾覆蓋層」的限制保留。 |

**共享核心：無修改。** 沒有動 `build.gradle`、`ModConfig`、`network/**`、`market/**`（`web` 以外）、`client/marketweb/**` 等任何共享檔案，也沒有動 `market/web/**` 的 Java。

## API／持久化相容性

- HTTP 路由、JSON 欄位、錯誤碼、NBT、SQLite：**無任何變動**。
- 新增的靜態資源：無（沒有新增 JS/CSS/圖檔，不需要改靜態路由或 addon JAR 內容）。
- 新增 `localStorage` 鍵：`marketWebTheme`（`light` / `dark`；未設定時跟隨系統）。既有鍵 `marketWebSessionToken`、`marketWebLocale`、`marketWebBrowseOrderView`、`marketWebAccessPanelOpenV2`、`marketWebWalletCollapsed` 等語意不變。
- 錢包仍是頂欄中「同一張相連卡片」展開（遵守既有 `marketDetailExposesAndDisplaysWalletBalance` 測試的產品決策），不是浮動 popover。

## 已執行的檢查

本機環境**沒有 Java**，因此 Gradle／JUnit 測試**未執行**。實際執行的：

```text
$ node --check src/main/resources/marketweb/app.js      # 通過
$ node --check src/main/resources/marketweb/map.js      # 通過
$ node tools/check_marketweb_frontend.js
marketweb frontend check failed: MAP page must mount the live canvas module
marketweb frontend check failed: map.js must request cached map tiles only
```

上述 2 項是交接時既有的 baseline 失敗（`VALIDATION.md` 已記載，map.js 已改用 `window.SailboatSquareMap || window.SailboatMarketMap` 與 `/api/map/square/tile/...`），本次未處理也未刪除。

另以腳本逐一比對所有讀取 `app.js`／`app.css`／`index.html` 的 Java 合約測試中 `assertTrue/assertFalse(x.contains("..."))` 斷言，結果 **0 項失敗**。這是原始碼層級比對，不能取代實際 `./gradlew test`。

在 mock server（`node tools/marketweb_mock_server.js 8797`）上以瀏覽器驗證：深色／亮色切換與持久化、終端下拉（搜尋、切換、外部點擊／Esc 關閉）、分類巢狀子分類、商品頁購買框按鈕路由、分頁高亮、地圖頁版型、手機寬度版型、動效只在路由／篩選／狀態訊息變化時播放（搜尋輸入不重播）。

## 需要 Codex 注意／接合

1. **換行符**：交接快照中這三個前端檔是 CRLF，回交版本是 LF。`git diff` 會顯示整檔變動，請用 `git diff --ignore-cr-at-eol` 檢視實際差異（實際約 +3.4k / −5.1k 行，主要是 CSS 重寫）。保留 LF 是因為多項 Java 合約測試以含 `\n` 的多行字串比對 CSS，CRLF 檔案會使其失敗。
2. **請在有 Java 21 的環境執行**交接文件列出的測試，特別是 `WebFrontendContractTest`、`MarketWebAppResourceTest`、`market/wallet/*Test`。
3. **字型**：數字使用系統字型堆疊（Windows 為 Bahnschrift、macOS 為 DIN Alternate），未打包字型檔。
4. **Mock 限制**：mock 沒有全服掛單、地圖瓦片與物品圖示端點，因此「全部商品」為空、地圖無瓦片、物品格顯示字母替代圖示；正式伺服器上會正常顯示。
5. 本包的 `HANDOFF_MANIFEST.json` 中 `source.branch` / `baseCommit` 指的是 Claude 本機工作用的 Git 倉庫（以交接 ZIP 為 baseline 建立），不是 Codex 的倉庫。

## 尚未處理

- **認證競態（`MarketWebAuthManager` 與 SavedData 跨執行緒存取、`bindAccount` 同名綁定競態）未修正**，也沒有新增並行回歸測試。交接文件「認證競態：已確認，尚未修正」一節的要求仍全部待辦。
