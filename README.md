# Sailboat Vehicle Mod

Minecraft NeoForge 1.21.1 多人航海、運輸、國家與城鎮模組。包含帆船與馬車、自動航線、領土管理、市場與貨物配送、銀行國庫、居民、道路規劃及網頁市場。

## 執行環境

- Minecraft 1.21.1、NeoForge 21.1.259、Java 21。
- 必要外置前置：GeckoLib 4.9.3、LDLib2 2.2.42。客戶端與伺服器都需要安裝。
- 可選整合：Tellus 0.8.3 地球地形與航線、Create 6.0.10 列車物流、Vault 經濟及 BlueMap 地圖。
- 網路協定為 **17**；客戶端與伺服器請同步更新。

## 本次更新

本分支合併市場即時更新與實驗性空運接入：飛艇、旋翼與固定翼共用真實 Flight Control 導航終端，登記玩家自建跑道、停機坪及泊位，網頁顯示機型、高度、階段與機場設施。空運尚待三類機型實飛；離線航程未啟用，中途重啟需人工恢復。使用方式與限制見 [空運說明](docs/handoff/air-transport/README.md)。合併後網路協定為 17，客戶端與伺服器需一起更新。

fix_13 讓市場即時更新：遊戲內終端畫面開著時，其他玩家的成交、上下架與訂單進度會直接推送過來，變動的價格會閃爍提示；網頁商城改用 `/api/events` 即時推送。另新增求購出價的綠寶石換算、盤口深度、24 小時漲跌、自選 ★、拼音首字母搜尋（Ctrl+F）、訂單進度時間軸與倒數；網頁端另有防重複下單、ETag／gzip 快取壓縮、訂單追蹤與 Tellus 3D 地球。原始補丁的驗證記錄見 [fix_13 交付說明](docs/handoff/MARKET_LIVE_FIX_13.md)，合併版需以當前分支的協定與驗證為準。

fix_12 將路線顯示移到獨立物品「交通地圖」：右鍵開啟專用地圖，切換道路／陸運／航線，左鍵查看名稱、起終點與約略距離；中鍵拖動、滾輪縮放、指南針按鈕回到玩家位置。合成為四張紙＋指南針，物品 ID `sailboatmod:route_atlas`。Town、Nation 與道路規劃器已移除 fix_11 新增的路線圖層與入口；網頁地圖保留原有功能。詳見 [fix_12 交付說明](docs/handoff/ROUTE_ATLAS_FIX_12.md)。

fix_10 將 Tellus 預測底圖接入道路規劃、Town、Nation 共用地圖；真實地形逐步覆蓋預測，超長航線／道路採分段規劃和有限載入窗口。另新增零質量船舶／陸運註冊控制器，以原生動力控制接入現有物流。完整用法、1882 項回歸及遊戲內驗證範圍見 [fix_10 交付說明](docs/handoff/TELLUS_TRANSPORT_FIX_10.md)。Sable 2.0.6 在本次組合存在上游存檔重啟問題，物理載具穩定恢復及完整動力運輸仍未驗收；地圖功能可獨立使用。

在 fix_5 的 UI 與市場修復基線上，加入 Tellus 水路預測與前瞻繞行、Tellus 網頁地圖圖層、物流歷程、Create 列車顯示與道路預覽載入管理，並修正 1.21.1 相機朝向變更導致的全息浮字消失。

回傳實作與原作者的驗證範圍見 [Tellus 適配說明](docs/handoff/TELLUS_ADAPTATION_RETURN.md)。該文件中的測試數字及截圖屬回傳端記錄；整合時的修正與實際驗證另見 Git 提交說明。未安裝 Tellus/Create 時，對應整合保持關閉。

## 建置與測試

Windows：

```powershell
.\gradlew.bat test releaseMod --console=plain -PfixIndex=12
.\gradlew.bat runClient --console=plain
.\gradlew.bat runServer --console=plain
```

Linux/macOS 使用 `./gradlew`。網頁市場測試需要 Node 20+。Gradle wrapper 會下載其餘建置依賴；指定版本的 LDLib2 及編譯用 Tellus/Create JAR 已放在 `libs/`。後兩者使用 compileOnly，不會打入成品，也不會自動安裝到遊戲。

成品位於 `build/libs/`：主模組 `sailboatmod-<版本>-neoforge-1.21.1.jar`；可選伺服器網頁附加模組 `sailboatmod-marketweb-<版本>-neoforge-1.21.1.jar`。`fixIndex` 只為輸出名稱加修訂後綴。

## 安裝與開發

主模組、GeckoLib、LDLib2 放入客戶端及伺服器的 `mods`，移除各模組舊版本；網頁附加模組只裝於伺服器。需要 Tellus 或 Create 功能時，再安裝相應可選模組。

遊戲內 UI 已使用 LDLib2；正常建置不需要舊開發 init script。主題在 `src/main/resources/assets/sailboatmod/lss/`。開發入口見 [START_HERE.md](START_HERE.md)、[AGENTS.md](AGENTS.md) 與 [LDLib2 接入](docs/handoff/LDLIB2_INTEGRATION.md)。

舊 UI 驗證見 [fix_5 窗口排版報告](docs/handoff/UI_WINDOW_LAYOUT_2026-10-14.md)。編譯或單元測試成功不代表所有多人流程或遊戲畫面已驗證。
