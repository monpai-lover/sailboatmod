# 市場即時同步、交易資訊與網頁商城升級：FIX 13 交付說明

本次讓遊戲內市場終端和網頁商城都改成即時更新，並補上交易資訊與物流追蹤。網路協定由 15 升為 **16**，客戶端與伺服器必須一起更新；網頁附加包仍只放伺服器。

## 遊戲內市場

### 即時同步

以前，市場畫面只在自己操作或重新開啟時才更新。其他玩家的成交、上下架、取消，以及物流推進的訂單狀態，都看不到。

現在所有會改變市場畫面的寫入都會遞增 `MarketChangeNotifier` 的修訂號：

- `MarketSavedData`：掛單、訂單、託管、運單、待領貨款
- 錢包：`MarketWalletSavedData`
- SQLite 求購單與成交紀錄：`CommodityMarketRepository`

`MarketLiveSync` 在每個伺服器 tick 檢查開著市場畫面（`MarketMenu`）的玩家。只要有人的資料舊於修訂號，就用新的 `MarketLiveUpdatePacket` 推送最新概覽。推送有兩條上限：

- 每 tick 最多重建一位玩家的概覽，因為概覽要查詢數十次 SQLite。
- 同一位玩家至少間隔一秒。

玩家自己操作的回覆也經由 `MarketLiveSync.sendOverview` 送出，所以不會緊接著再收到一次重複推送。

推送不是「回覆」：它不會解除玩家自己正在等待的操作，所以不會因為別人的變動而讓購買可以重送。客戶端收到後：

- 最低賣價有變動的商品，價格會閃一下綠色 ▲ 或紅色 ▼（3 秒）。
- 自己的訂單狀態一改變（例如變成「運輸中」），就跳出提示。

### 交易資訊

- **求購出價換算成綠寶石。** 以前顯示「+590 bp」。伺服器現在隨每本求購簿送出參考價（`CommodityBuyBook.referenceUnitPrice`），算法與建立求購單時凍結資金的參考價完全一致。詳情顯示「≈ 829」，滑鼠移上去可看到完整的「≈ 829（+300 bp）」（該欄只有約 50 GUI 像素寬）；求購列表顯示價格區間。
- **盤口分頁。** 商品頁新增此分頁：賣盤由低到高、買盤由高到低，同價合併，以累計深度條表示厚度。
- **24 小時漲跌。** 商品卡片在賣家數後面以紅綠色顯示，取自小時 K 線：最新收盤對比 24 小時內最早的收盤。
- **自選 ★。** 存在 `config/sailboatmod-ui.json`，所有終端共用，最多 256 項。工具列的 ★ 按鈕可以只看自選。
- **拼音首字母搜尋。** 輸入「zs」能找到「钻石」。JDK 的中文排序不是按拼音，所以改用 U+4E00–U+9FA5 的首字母表 `client/ui/market/pinyin-initials.txt`（以 pypinyin 0.53.0 的 `Style.FIRST_LETTER` 產生）。英文名稱照常以文字比對，不會被首字母誤配。
- **Ctrl+F。** 回到商品目錄並把游標放進搜尋框。

### 我的訂單

- 每筆訂單顯示進度：已付款 » 等待發貨 » 運輸中 » 已送達；真人自提則是 已付款 » 已鎖定自提 » 已送達。
- 排隊中的訂單每秒倒數預計時間。這只更新那一行文字，不會重建列表。

## 網頁商城

前端（`src/main/resources/marketweb`）：

- 輸入法組字、數量重設、重複送出等錯誤修正。
- 原生對話框。
- 畫面改用 Idiomorph 原地更新與事件委派。
- 圖表和地圖腳本改為按需載入，首屏從 827 KB 降到 441 KB。
- `/api/events` 即時推送：fetch 串流，斷線退避重連，並以 Last-Event-ID 補收事件。
- 交易資訊：K 線、熱力圖、盤口、拼音指令面板、自選。
- 訂單時間軸與倒數、在地圖上追蹤船隻。
- Tellus 世界的 MapLibre 3D 地球（5.24.0，只在切換到地球時下載）。

伺服器（`market/web`）：

- **`MarketWebStaticResponder`：** 靜態檔加上弱 ETag 與 `Cache-Control: no-cache`。檔案沒變就回 304，文字資源以 gzip 傳送，dev 模式仍是 `no-store`。API JSON 超過 1 KB 也改用 gzip，終端詳情約縮為十分之一。
- **`MarketWebIdempotency`：** 所有帶 `Idempotency-Key` 的市場寫入（購買、上架、求購、取消、錢包、跨終端購買）同一個鍵只執行一次。網路重送會拿到第一次的結果；同時到達的重複請求回 409。
- **`MarketWebEventHub`：** `/api/events` 不經過 `createContext` 的包裝，因為那個包裝在 handler 返回時就會關閉連線。每條串流由各自的虛擬執行緒寫出，不佔用請求執行緒池。伺服器把 JDK 的 `sun.net.httpserver.maxRspTime` 設為 120 秒，超過就強制關閉未寫完的回應，所以串流在第 100 秒主動送出 `rotate` 並正常結束，頁面立即重連且狀態不變。修訂號變動時，每秒最多送出一個 `market` 事件（`marketId` 為 `*`）；成交紀錄則送出 `trade` 事件給跑馬燈。

### 部署建議：HTTPS 與 CSP

登入 token 存在瀏覽器的 localStorage，最大的風險是 XSS。前端顯示玩家資料時都有跳脫，但仍建議再加兩層防護。

**HTTPS：** 在前面放一個反向代理。例如用 Caddy，下面這段就會自動申請憑證：

```
market.example.com {
    reverse_proxy 127.0.0.1:<marketweb 埠號>
}
```

**CSP：** 在 `MarketWebServer.applySecurityHeaders` 裡加上這個標頭，也可以加在反向代理上：

```
Content-Security-Policy: default-src 'self'; script-src 'self' 'sha256-NHiIejQuKowXpgklS+4xYP6gD4RxoYUhZyRg3cETNpo='; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https://server.arcgisonline.com https://*.is.autonavi.com https://tile.openstreetmap.org https://a.tile.opentopomap.org; connect-src 'self' https://server.arcgisonline.com https://*.is.autonavi.com https://tile.openstreetmap.org https://a.tile.opentopomap.org; worker-src blob:; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'
```

這條 CSP 在瀏覽器裡逐頁實測：全部商品、瀏覽、K 線、指數、我的訂單、平面地圖、3D 地球、指令面板，都是 0 違規。也做了反向對照：拿掉雜湊，內嵌的主題腳本就被擋；拿掉 `worker-src blob:`，地球的 worker 就被擋。所以每一項都是必要的。

- `sha256-...` 對應 `index.html` 開頭設定主題的內嵌腳本。改了那段腳本就要重新計算（先把換行統一成 LF）：

  ```bash
  python3 -c "import re,hashlib,base64;s=re.search(r'<script>(.*?)</script>',open('index.html',encoding='utf-8',newline='').read(),re.S).group(1).replace('\r\n','\n');print('sha256-'+base64.b64encode(hashlib.sha256(s.encode()).digest()).decode())"
  ```

- `worker-src blob:` 是 MapLibre 建立 Web Worker 用的。
- 圖磚網域對應 `tellus-basemap.js` 的四個底圖來源；新增來源時要一起加進來。
- `style-src 'unsafe-inline'` 是因為範本裡有 `style="..."` 屬性（熱力圖方塊位置、顏色條等），不影響腳本。

`crypto.randomUUID` 只能在 HTTPS 或 localhost 上使用。純 HTTP 部署時，前端改用 `getRandomValues` 產生冪等鍵，兩種情況都能用。

### 上線後快速驗收

```bash
# 靜態檔：第一次 200，帶 ETag 與 gzip；帶 If-None-Match 再請求，回 304
curl -sI -H 'Accept-Encoding: gzip' http://HOST:PORT/app.js | grep -iE 'etag|cache-control|content-encoding'
curl -s -o /dev/null -w '%{http_code}\n' -H 'If-None-Match: <上面的 ETag>' http://HOST:PORT/app.js

# 即時推送：立刻收到 retry 與 connected，之後每 20 秒一個 ping，約 100 秒後收到 rotate
curl -N -H 'Authorization: Bearer <token>' http://HOST:PORT/api/events
```

在瀏覽器裡，頁首的狀態燈應該是綠色的「即時」，不是灰色的輪詢。在遊戲裡成交一筆，網頁應該在一秒內出現成交跑馬燈。

## 驗證

- **建置與單元測試：** `./gradlew test releaseMod --console=plain --no-parallel -PfixIndex=13` 成功。JUnit 共 1,964 個測試、429 個測試類別：0 失敗、0 錯誤、1 個略過（原本就略過）。原始碼基準（9af52a3）是 1,936 個，本次新增 28 個。
  - 新測試類別：`MarketLiveScheduleTest`、`MarketLiveUpdatePacketTest`、`MarketLiveUpdateTest`、`MarketTradingInfoTest`、`MarketWebStaticResponderTest`、`MarketWebIdempotencyTest`、`MarketWebEventHubTest`。
  - 擴充的測試：`UiPreferencesTest`、`OpenMarketScreenPacketTest`、`MarketWebAppResourceTest`、`WebFrontendContractTest`，以及由 JUnit 執行的 `tools/test_marketweb_motion.js`。
- **產物：** 主模組與 fix_12 一樣，只帶客戶端的地圖圖磚上傳類別，不含 `market/web` 伺服器類別與 `marketweb/` 資源。網頁附加包含三個新類別，以及 `market-runtime.js`、`market-globe.js` 和 MapLibre。
- **遊戲內原生畫面：** 在 Xvfb + Mesa llvmpipe 上跑真正的 NeoForge 客戶端，視窗 1920×1080。開發用 harness 掛載真正的 `MarketView`，跑四種組合：交易大廳主題 GUI 2、3、4（GUI 4 是窄版疊層）和帳本主題 GUI 2。每種組合都走一遍：即時更新閃爍、商品詳情、盤口、求購、我的訂單、訂單狀態提示、拼音搜尋。
  - 結果：28 項檢查全過，28 張截圖，LDLib2 的「UI layout is dirty」警告 0 次。
  - 截圖抓到三個問題，都已修正：24 小時漲跌壓在價格上，還造成 130 次佈局迴圈警告；最佳買價一欄太窄，「≈ 829」被截掉；訂單進度被拉成等寬欄位，「›」看起來像冒號。
- **網頁商城：** 用 mock 伺服器（Tellus 資料）和無頭 Chromium 跑 37 項端對端檢查，全部通過。建議的 CSP 逐頁實測 0 違規；另有一頁故意違規的反向對照，確實被擋下。
- **`maxRspTime`：** 設為 3 秒實測，未寫完的串流在第 4.0 秒被關閉，所以串流必須在逾時前輪替。

## 驗證邊界

- 沒有在真實多人伺服器上由多位真人同時操作。
- 遊戲內截圖使用合成資料：開發用 harness 直接掛載 `MarketView`，與先前驗證的「合成顯示快照」做法相同，不經伺服器選單。
- 網頁端是以 mock 伺服器和無頭 Chromium 驗證。
- 推送的伺服器負載（大量玩家同時開著市場）沒有實測；排程已把每 tick 的成本限制在最多一次概覽建立。
