# Market Wallet And Web Map Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a personal market wallet used by market purchases and buy orders, and add server-side web map repair rendering that can replace bad cached tiles without relying only on client uploads.

**Architecture:** Wallet state is stored per-player on the server and exposed to both in-game and web market views. Market actions spend or reserve wallet balance first, with explicit transfer paths between cash, wallet, and the existing nation/town treasury. Map repair extends the existing tile cache quality system with a server render version and a background scan queue that can progressively overwrite old client or stale server tiles.

**Tech Stack:** Forge 1.20.1, Java 17 source, Minecraft `SavedData`, existing market SQLite commodity tables, Elementa in-game screen, static market web HTML/CSS/JS.

---

### Task 1: Personal Market Wallet Ledger

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/wallet/MarketWalletAccount.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/wallet/MarketWalletSavedData.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/wallet/MarketWalletService.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/wallet/MarketWalletServiceTest.java`

- [ ] Write tests for deposit, withdraw, reserve, release, and reserved-balance spending.
- [ ] Implement immutable account record and `SavedData` persistence.
- [ ] Implement service helpers with no player-side dependencies for unit testing.

### Task 2: Market Settlement Uses Wallet

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/commodity/CommodityMarketService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/CreateBuyOrderPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/CancelBuyOrderPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/PurchaseMarketListingPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/wallet/MarketWalletSourceContractTest.java`

- [ ] Add source-contract tests that purchase, buy-order creation, and buy-order cancel call wallet services.
- [ ] Change listing purchase to withdraw wallet available balance.
- [ ] Change seller payout to deposit wallet balance, keeping pending credits as a fallback/manual claim path.
- [ ] Change buy-order creation to reserve wallet balance and store the reserved amount on the order.
- [ ] Change buy-order cancellation to release the reserved amount back to wallet.

### Task 3: Cash And Treasury Wallet Transfers

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/MarketWalletActionPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/wallet/MarketWalletTransferContractTest.java`

- [ ] Support `CASH_TO_WALLET`, `WALLET_TO_CASH`, `WALLET_TO_TREASURY`, `TREASURY_TO_WALLET`, and `CLAIM_CREDITS_TO_WALLET`.
- [ ] Reuse `GoldStandardEconomy` for cash/plugin/gold entity transfers.
- [ ] Reuse `NationTreasuryRecord.currencyBalance()` for treasury transfers.
- [ ] Require `MANAGE_TREASURY` only for treasury-to-wallet withdrawals.

### Task 4: Web And In-Game Wallet UI

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java`
- Modify: `src/main/resources/assets/sailboatmod/lang/en_us.json`
- Modify: `src/main/resources/assets/sailboatmod/lang/zh_cn.json`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Modify: `src/main/resources/marketweb/app.js`
- Modify: `src/main/resources/marketweb/app.css`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`

- [ ] Expose wallet available, reserved, pending credits, and treasury balance in overview/detail JSON.
- [ ] Put in-game wallet controls into the existing Finance page top row.
- [ ] Put web wallet metrics into the existing metric strip and add a compact wallet action panel.
- [ ] Keep layout changes small and avoid adding a new market page.

### Task 5: Server Web Map Repair Rendering

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileMetadata.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCache.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRegionScanService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebCommands.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapTileCacheTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRegionScanServiceTest.java`

- [ ] Add a `RENDER_VERSION` metadata field.
- [ ] Allow newer server render versions to overwrite stale `SERVER_LOADED_CHUNK` pixels.
- [ ] Add a background queue that scans region-file names and prioritizes regions near markets/spawn.
- [ ] Keep first implementation non-blocking: no forced chunk loads, no synchronous tile generation during web requests.
- [ ] Add commands to enqueue a scan, clear stale server tiles, and show queue status.

### Task 6: Verification

**Files:**
- Modify tests listed above.

- [ ] Run wallet unit tests.
- [ ] Run market web resource tests.
- [ ] Run map cache and region scan tests.
- [ ] Run `.\gradlew.bat compileJava`.

