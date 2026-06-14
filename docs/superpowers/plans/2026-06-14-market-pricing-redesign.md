# 市场定价模型重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 去掉市场的动态库存定价公式，改为"挂单价由卖家定死 + 参考价（最近成交均价）做 ±50% 价格保护 + 指标全部派生"，根除批量上架崩盘与挂单价被库存反复改写两个 bug。

**Architecture:** 系统不再用 `basePrice × volatilityFactor^(-stock)` 驱动任何实际价格。挂单价上架时固定、永不被系统改写；新增"参考价 = 最近 N 笔成交均价（无记录回退 basePrice）"仅用于上架时的 ±50% 价格保护与建议价；"最低卖价"等指标改为扫描活跃挂单/聚合成交记录派生。上架不再 `+stock`，stock 不再参与定价。

**Tech Stack:** Java 17、Minecraft Forge 1.20.1、SQLite（commodity_trade_history 表）、JUnit 5。测试两种风格：(a) **纯函数行为测试**——直接断言静态方法返回值（见 `MarketPricePolicyTest`、`CommodityMarketServiceFundingTest`）；(b) **源码文本断言**——`Files.readString` + `assertTrue(source.contains(...))`，用于约束"某调用被删除/某方法被简化"这类跨调用形态。

---

## 背景与约束（实现前必读）

**根因（spec 第二部分锁定）：**
1. **批量上架崩盘**：`createListingFromDockStorage`（`MarketBlockEntity` ~:520）上架立即 `adjustCommoditySupply(+amount)` → stock 暴增 → `getStockPrice` 的 `basePrice × volFactor^(-stock) ≈ 0` → 价格窗口压崩 → 无法再上架。
2. **挂单价被反复改写**：`currentListingUnitPrice()`（`MarketBlockEntity:2066`）对"约束定价"商品每次成交/查看用动态价重算；成交后 `purchaseListingResolved`（~:620）用它重算回写 listing。

**用户决策（spec 关键决策表）：** 删动态公式 / 参考价=最近 N 笔成交均价（N=20，回退 basePrice）/ 价格保护参考价 ±50% / 挂单价卖家定死永不改写 / 最低卖价=当前最便宜活跃挂单 / 上架不 +stock。

**已核实的真实接口（写代码前的事实，行号可能因前序任务编辑而偏移，改前先 Read 锚点）：**
- `MarketPricePolicy.listingWindow(boolean constrained, int referenceUnitPrice, int requestedUnitPrice, int minListingPriceBp, int maxListingPriceBp)` → `ListingPriceWindow`（纯函数，`MarketPricePolicy.java:4-40`）。窗口 record 字段：`referenceUnitPrice, requestedUnitPrice, derivedPriceAdjustmentBp, minAllowedUnitPrice, maxAllowedUnitPrice, valid, constrained`。
- `MarketBlockEntity.currentListingUnitPrice(MarketListing, int)`（:2066-2077）：现含"约束/无约束"两套；调用点 240/241/294/508/620/2020。
- `MarketBlockEntity.currentCommodityUnitPrice(ItemStack, int, int fallback)`（:2047）：调 `quoteCommodity`。
- `MarketBlockEntity.listingPriceWindow(ItemStack, int, int)`（:2055）+ 常量 `MIN_LISTING_PRICE_BP=-1000`/`MAX_LISTING_PRICE_BP=1000`（~:81）。
- `MarketBlockEntity.adjustCommoditySupply(ItemStack, int)`（:2121）→ `COMMODITY_MARKET.adjustStock`。在 `createListingFromDockStorage` ~:520 调用。
- `MarketBlockEntity.applyCommodityDemand(...)`（:2132）→ `COMMODITY_MARKET.applyTrade(...)`：**保留**（写成交记录=参考价数据源）。
- `CommodityMarketService.estimateBaseUnitPrice(ItemStack)`（public static，:350）：basePrice 回退锚。
- `CommodityMarketRepository`：SQLite 表 `commodity_trade_history(commodity_key, trade_side, quantity, unit_price, total_price, ..., created_at)`，索引 `idx_trade_history_commodity_time (commodity_key, created_at DESC)`。现有查询方法风格见 `listTradeHistoryBuckets`。
- `CommodityTradeRecord`：字段含 `commodityKey, tradeSide, quantity, unitPrice, totalPrice, ..., createdAt`。
- `CommodityKeyResolver.resolve(ItemStack)` → commodityKey 字符串。
- `MarketListing`：record，含 `unitPrice`、`priceAdjustmentBp`；`market.putListing(MarketListing)`。

**测试命令：**
- 单类：`cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.MarketPricePolicyTest`
- 全量：`cd sailboatmod && ./gradlew test`
- Windows 若 `./gradlew` 不可用，用 `gradlew.bat test ...`。

**注意**：SQLite 相关逻辑很难在纯单测里跑（需真实 DB 连接），因此 `recentTradeAveragePrice` 这类 Repository 方法用**源码文本断言**验证 SQL 形态 + 在 `CommodityMarketService.referencePrice` 的回退分支用**纯函数**验证"无记录回退 basePrice"。真实 SQL 行为留集成实测。

---

## File Structure（先锁定边界）

**修改（生产代码）：**
- `market/MarketPricePolicy.java` — 新增 `referencePriceWindow(referencePrice, requestedUnitPrice)`：基于参考价 ±50% 的窗口（不再用 bp 的约束/无约束两套）；保留旧 `listingWindow` 暂不删（被其他处引用，本计划末尾改调用点）
- `market/commodity/CommodityMarketRepository.java` — 新增 `recentTradeAveragePrice(commodityKey, n)`（最近 N 笔成交均价 SQL）
- `market/commodity/CommodityMarketService.java` — 新增 `referencePrice(ItemStack/key)`（无记录回退 `estimateBaseUnitPrice`）；动态公式方法不再被实际价路径调用（保留方法体但只供历史图表，若无引用则标注废弃）
- `block/entity/MarketBlockEntity.java` — `currentListingUnitPrice`/`currentListingTotalPrice` 统一返回 listing 定死价；`listingPriceWindow` 改用参考价 ±50%；删 `createListingFromDockStorage` 的 `adjustCommoditySupply`；删 `purchaseListingResolved` 的成交回写重算；buildOverview 指标改派生
- `MarketPricePolicy` 价格保护常量集中：新增 `REFERENCE_PRICE_FLOOR_RATIO=0.5`、`REFERENCE_PRICE_CEIL_RATIO=1.5`

**新建（测试）：**
- `src/test/java/com/monpai/sailboatmod/market/MarketReferencePriceWindowTest.java`（纯函数：±50% 窗口）
- `src/test/java/com/monpai/sailboatmod/market/commodity/CommodityReferencePriceContractTest.java`（源码断言：recentTradeAveragePrice SQL + referencePrice 回退）
- `src/test/java/com/monpai/sailboatmod/market/PricingListingFixedPriceContractTest.java`（源码断言：currentListingUnitPrice 统一、回写删除、+stock 删除）

---

## Task 1: 参考价 ±50% 价格窗口（纯函数）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/MarketPricePolicy.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/MarketReferencePriceWindowTest.java`

- [ ] **Step 1: 写失败的纯函数测试**

新建 `src/test/java/com/monpai/sailboatmod/market/MarketReferencePriceWindowTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketReferencePriceWindowTest {
    @Test
    void priceWithinFiftyPercentBandIsAccepted() {
        // 参考价 100 → 允许区间 [50, 150]
        MarketPricePolicy.ListingPriceWindow w = MarketPricePolicy.referencePriceWindow(100, 120);
        assertTrue(w.valid());
        assertEquals(50, w.minAllowedUnitPrice());
        assertEquals(150, w.maxAllowedUnitPrice());
        assertEquals(120, w.requestedUnitPrice());
        assertEquals(100, w.referenceUnitPrice());
    }

    @Test
    void priceAboveCeilIsRejected() {
        MarketPricePolicy.ListingPriceWindow w = MarketPricePolicy.referencePriceWindow(100, 151);
        assertFalse(w.valid());
    }

    @Test
    void priceBelowFloorIsRejected() {
        MarketPricePolicy.ListingPriceWindow w = MarketPricePolicy.referencePriceWindow(100, 49);
        assertFalse(w.valid());
    }

    @Test
    void boundariesAreInclusive() {
        assertTrue(MarketPricePolicy.referencePriceWindow(100, 50).valid());
        assertTrue(MarketPricePolicy.referencePriceWindow(100, 150).valid());
    }

    @Test
    void nonPositiveReferenceFallsBackToMinimumOne() {
        // 参考价 <=0 时按 1 处理，避免区间塌缩为 [0,0]
        MarketPricePolicy.ListingPriceWindow w = MarketPricePolicy.referencePriceWindow(0, 1);
        assertEquals(1, w.referenceUnitPrice());
        assertTrue(w.minAllowedUnitPrice() >= 1);
        assertTrue(w.valid());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.MarketReferencePriceWindowTest`
Expected: FAIL —— 编译失败，`referencePriceWindow` 不存在。

- [ ] **Step 3: 在 MarketPricePolicy 新增 referencePriceWindow + 常量**

在 `MarketPricePolicy.java` 类体内（`listingWindow` 方法之后、`applyPriceAdjustment` 之前）插入：

```java
    /** 价格保护：挂单价必须落在 [参考价 × 0.5, 参考价 × 1.5]。范围常数集中于此。 */
    public static final double REFERENCE_PRICE_FLOOR_RATIO = 0.5D;
    public static final double REFERENCE_PRICE_CEIL_RATIO = 1.5D;

    /**
     * 基于参考价的 ±50% 上架价格窗口。挂单价由卖家定死，本窗口只做"不太高也不太低"的保护。
     * 不再区分"约束/无约束"两套——所有商品统一按参考价 ±50%。
     */
    public static ListingPriceWindow referencePriceWindow(int referenceUnitPrice, int requestedUnitPrice) {
        int safeReference = Math.max(1, referenceUnitPrice);
        int safeRequested = Math.max(1, requestedUnitPrice);
        int minAllowed = Math.max(1, (int) Math.floor(safeReference * REFERENCE_PRICE_FLOOR_RATIO));
        int maxAllowed = Math.max(minAllowed, (int) Math.ceil(safeReference * REFERENCE_PRICE_CEIL_RATIO));
        boolean valid = requestedUnitPrice > 0
                && safeRequested >= minAllowed
                && safeRequested <= maxAllowed;
        return new ListingPriceWindow(
                safeReference,
                safeRequested,
                derivePriceAdjustmentBp(safeReference, safeRequested),
                minAllowed,
                maxAllowed,
                valid,
                true
        );
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.MarketReferencePriceWindowTest`
Expected: PASS

> 边界核对：参考价 100 → floor(100×0.5)=50、ceil(100×1.5)=150；49<50 拒、151>150 拒；50/150 含。参考价 0 → safeReference=1 → min=max(1,floor(0.5))=1、max=max(1,ceil(1.5))=2 → requested 1 在 [1,2] 内，valid。

- [ ] **Step 5: 跑既有定价政策测试防回归**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.MarketPricePolicyTest`
Expected: PASS（只新增方法，未改 `listingWindow`，旧测试不受影响）。

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/MarketPricePolicy.java src/test/java/com/monpai/sailboatmod/market/MarketReferencePriceWindowTest.java && git commit -m "feat(pricing): add reference-price +/-50% listing window"
```

---

## Task 2: 最近 N 笔成交均价查询（Repository SQL）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/commodity/CommodityMarketRepository.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/commodity/CommodityReferencePriceContractTest.java`

> SQLite 查询无法在纯单测跑，用源码断言锁定 SQL 形态（最近 N 笔、按时间倒序、AVG）。

- [ ] **Step 1: 写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/commodity/CommodityReferencePriceContractTest.java`：

```java
package com.monpai.sailboatmod.market.commodity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommodityReferencePriceContractTest {
    @Test
    void repositoryQueriesRecentTradeAverageOrderedByTimeDesc() throws Exception {
        String repo = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/commodity/CommodityMarketRepository.java"));

        assertTrue(repo.contains("recentTradeAveragePrice("),
                "repository should expose recentTradeAveragePrice");
        // 最近 N 笔：按时间倒序取 N 条，对其 unit_price 求均值
        assertTrue(repo.contains("ORDER BY created_at DESC"),
                "recent-trade query should order by created_at DESC to take the latest N");
        assertTrue(repo.contains("LIMIT ?"),
                "recent-trade query should bound to the latest N trades via LIMIT");
        assertTrue(repo.contains("commodity_trade_history"),
                "query should read from the trade history table");
        assertTrue(repo.contains("unit_price"),
                "average should be computed over unit_price");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.commodity.CommodityReferencePriceContractTest`
Expected: FAIL —— 无 `recentTradeAveragePrice`。

- [ ] **Step 3: 新增 recentTradeAveragePrice 方法**

先 Read `CommodityMarketRepository.java` 确认 `connection()` 辅助方法名与现有查询风格（参考 `listTradeHistoryBuckets`），然后在该类内新增（紧邻 `appendTrade` 之后）：

```java
    /**
     * 最近 n 笔成交的单价均值（按成交时间倒序取 n 笔）。无成交记录返回 0（由 service 层回退 basePrice）。
     * 用子查询先取最近 n 笔，再对其 unit_price 求均值，避免对全表平均。
     */
    public int recentTradeAveragePrice(String commodityKey, int n) throws SQLException {
        int safeN = Math.max(1, n);
        try (PreparedStatement statement = connection().prepareStatement(
                """
                SELECT AVG(unit_price) AS avg_price FROM (
                    SELECT unit_price FROM commodity_trade_history
                    WHERE commodity_key = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                )
                """)) {
            statement.setString(1, commodityKey == null ? "" : commodityKey);
            statement.setInt(2, safeN);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    double avg = resultSet.getDouble("avg_price");
                    if (!resultSet.wasNull() && avg > 0) {
                        return (int) Math.round(avg);
                    }
                }
                return 0;
            }
        }
    }
```

> 若该文件未 import `java.sql.PreparedStatement` / `ResultSet` / `SQLException`，确认已有（`appendTrade` 已用，应已 import）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.commodity.CommodityReferencePriceContractTest`
Expected: PASS

- [ ] **Step 5: 编译验证（确保 SQL/语法无误）**

Run: `cd sailboatmod && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/commodity/CommodityMarketRepository.java src/test/java/com/monpai/sailboatmod/market/commodity/CommodityReferencePriceContractTest.java && git commit -m "feat(pricing): query recent-N trade average price from history"
```

---

## Task 3: referencePrice 服务方法（无记录回退 basePrice）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/commodity/CommodityMarketService.java`
- Test: 扩展 `src/test/java/com/monpai/sailboatmod/market/commodity/CommodityReferencePriceContractTest.java`

> service 层把"最近成交均价"和"回退 basePrice"组合。回退逻辑用源码断言锁定（DB 调用不可纯单测）。N 默认 20 定义为常量。

- [ ] **Step 1: 扩展契约测试**

在 `CommodityReferencePriceContractTest.java` 追加：

```java
    @Test
    void serviceReferencePriceFallsBackToBasePriceWhenNoTrades() throws Exception {
        String service = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/monpai/sailboatmod/market/commodity/CommodityMarketService.java"));

        assertTrue(service.contains("referencePrice("),
                "service should expose referencePrice");
        assertTrue(service.contains("recentTradeAveragePrice("),
                "referencePrice should source from recent trade average");
        assertTrue(service.contains("estimateBaseUnitPrice("),
                "referencePrice should fall back to base price when there are no trades");
        assertTrue(service.contains("REFERENCE_TRADE_SAMPLE_SIZE"),
                "recent-N sample size should be a named constant (default 20)");
        assertTrue(service.contains("= 20"),
                "default recent-N should be 20 per spec");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.commodity.CommodityReferencePriceContractTest`
Expected: FAIL —— 无 `referencePrice` / `REFERENCE_TRADE_SAMPLE_SIZE`。

- [ ] **Step 3: 新增 referencePrice 方法 + 常量**

先 Read `CommodityMarketService.java` 顶部确认 `repository` 字段名与 `ensureCommodity`/`estimateBaseUnitPrice` 用法。在类内合适处（靠近 `estimateBaseUnitPrice`）新增常量与方法：

```java
    /** 参考价取样：最近 N 笔成交（spec 决策 N=20，可调）。 */
    public static final int REFERENCE_TRADE_SAMPLE_SIZE = 20;

    /**
     * 参考价 = 最近 N 笔成交均价；无成交记录时回退到基准价（estimateBaseUnitPrice）作初始锚。
     * 仅用于上架价格保护与建议价，不驱动任何实际成交价。
     */
    public int referencePrice(ItemStack itemStack) {
        int fallback = Math.max(1, estimateBaseUnitPrice(itemStack));
        if (itemStack == null || itemStack.isEmpty()) {
            return fallback;
        }
        try {
            String commodityKey = CommodityKeyResolver.resolve(itemStack);
            int avg = repository.recentTradeAveragePrice(commodityKey, REFERENCE_TRADE_SAMPLE_SIZE);
            return avg > 0 ? avg : fallback;
        } catch (SQLException exception) {
            return fallback;
        }
    }
```

> 确认 `CommodityKeyResolver` 已 import；`repository` 为该类持有的 `CommodityMarketRepository` 字段名（Read 确认实际字段名，可能叫 `repository`）。`estimateBaseUnitPrice` 是 static，可直接调。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.commodity.CommodityReferencePriceContractTest`
Expected: PASS

- [ ] **Step 5: 编译验证**

Run: `cd sailboatmod && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/commodity/CommodityMarketService.java src/test/java/com/monpai/sailboatmod/market/commodity/CommodityReferencePriceContractTest.java && git commit -m "feat(pricing): referencePrice from recent trades with basePrice fallback"
```

---

## Task 4: 挂单价定死（currentListingUnitPrice/TotalPrice 统一）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`（:2066-2092）
- Test: `src/test/java/com/monpai/sailboatmod/market/PricingListingFixedPriceContractTest.java`

> spec §1/§4：`currentListingUnitPrice` 统一返回 `listing.unitPrice()`，删"约束定价"动态重算分支。`currentListingTotalPrice` 同理用定死单价 × 数量。

- [ ] **Step 1: 写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/PricingListingFixedPriceContractTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingListingFixedPriceContractTest {
    private static String marketBlockEntity() throws Exception {
        return Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
    }

    @Test
    void listingUnitPriceIsFixedAndNotRecomputedFromQuote() throws Exception {
        String source = marketBlockEntity();
        // currentListingUnitPrice 不得再调 quoteCommodity 重算挂单价
        assertTrue(source.contains("private int currentListingUnitPrice(MarketListing listing, int quantity)"),
                "currentListingUnitPrice should still exist");
        // 标记：方法体统一返回定死单价
        assertTrue(source.contains("// pricing: listing price is fixed by the seller, never recomputed"),
                "currentListingUnitPrice must be marked as returning the seller-fixed price");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PricingListingFixedPriceContractTest`
Expected: FAIL —— 无该标记注释。

- [ ] **Step 3: 简化 currentListingUnitPrice 与 currentListingTotalPrice**

把 `MarketBlockEntity.java` 的 `currentListingUnitPrice`（:2066-2077）整体替换为：

```java
    private int currentListingUnitPrice(MarketListing listing, int quantity) {
        // pricing: listing price is fixed by the seller, never recomputed from stock/quote
        if (listing == null) {
            return 0;
        }
        return Math.max(1, listing.unitPrice());
    }
```

把 `currentListingTotalPrice`（:2079-2092）整体替换为：

```java
    private int currentListingTotalPrice(MarketListing listing, int quantity) {
        // pricing: total is the seller-fixed unit price times quantity, no dynamic recompute
        if (listing == null) {
            return 0;
        }
        return safeTotalPrice(listing.unitPrice(), quantity);
    }
```

> `quantity` 参数保留（调用点签名不变，最小化改动）；`safeTotalPrice`（:2106）已存在。`applyPriceAdjustment`/`derivePriceAdjustmentBp`/`hasConstrainedListingPrice` 暂保留（Task 5/6 处理其余引用后，若彻底无引用再清理）。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PricingListingFixedPriceContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/PricingListingFixedPriceContractTest.java && git commit -m "fix(pricing): listing unit/total price is seller-fixed, never recomputed"
```

---

## Task 5: 上架价格窗口改用参考价 ±50%

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`（`listingPriceWindow` :2055-2064）
- Test: 扩展 `PricingListingFixedPriceContractTest.java`

> spec §3：`listingPriceWindow` 不再用 `currentCommodityUnitPrice`（动态价）+ bp 约束，改用 `referencePrice` + `referencePriceWindow`。

- [ ] **Step 1: 扩展契约测试**

在 `PricingListingFixedPriceContractTest.java` 追加：

```java
    @Test
    void listingWindowUsesReferencePriceBand() throws Exception {
        String source = marketBlockEntity();
        // 上架窗口基于参考价 ±50%，不再基于动态报价
        assertTrue(source.contains("MarketPricePolicy.referencePriceWindow("),
                "listingPriceWindow should use the reference-price band");
        assertTrue(source.contains("COMMODITY_MARKET.referencePrice("),
                "listingPriceWindow should source the reference price from the service");
        assertFalse(source.contains("MarketPricePolicy.listingWindow("),
                "old bp-based listingWindow must no longer be called");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PricingListingFixedPriceContractTest`
Expected: FAIL —— 现状仍调 `MarketPricePolicy.listingWindow`。

- [ ] **Step 3: 改 listingPriceWindow**

先 Read 确认 `COMMODITY_MARKET` 字段名（`MarketBlockEntity` 持有的 `CommodityMarketService` 实例，grep `COMMODITY_MARKET` 确认）。把 `listingPriceWindow`（:2055-2064）整体替换为：

```java
    private ListingPriceWindow listingPriceWindow(ItemStack stack, int quantity, int requestedUnitPrice) {
        // pricing: protection band is reference-price +/-50%, not dynamic-quote bp
        int referenceUnitPrice = COMMODITY_MARKET.referencePrice(stack);
        return MarketPricePolicy.referencePriceWindow(referenceUnitPrice, requestedUnitPrice);
    }
```

> `referencePrice(ItemStack)` 是 Task 3 新增的实例方法（非 static），故用 `COMMODITY_MARKET.referencePrice(stack)`。`quantity` 参数保留（调用点 241/508 签名不变）。`MIN_LISTING_PRICE_BP`/`MAX_LISTING_PRICE_BP` 常量此后可能无引用——Step 5 编译若报 unused 不会失败（Java 允许 unused private 常量），暂留，最终清理任务统一删。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PricingListingFixedPriceContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/PricingListingFixedPriceContractTest.java && git commit -m "fix(pricing): listing window uses reference-price +/-50% band"
```

---

## Task 6: 删除成交回写 listing 价 + 上架 +stock

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`（成交回写 ~:620、上架 ~:520）
- Test: 扩展 `PricingListingFixedPriceContractTest.java`

> spec §4/§6：成交后不再用 `currentListingUnitPrice` 重算回写 listing；`createListingFromDockStorage` 删 `adjustCommoditySupply`（上架不 +stock）。`applyCommodityDemand`（成交写记录）**保留**。

- [ ] **Step 1: 扩展契约测试**

在 `PricingListingFixedPriceContractTest.java` 追加：

```java
    @Test
    void resaleKeepsFixedPriceAndListingDoesNotBumpStock() throws Exception {
        String source = marketBlockEntity();

        // 成交回写 listing 时用 listing.unitPrice() 保持定死价，而非 currentListingUnitPrice 重算
        assertTrue(source.contains("// pricing: keep the seller-fixed unit price on resale"),
                "purchase resolution must keep the fixed listing price (no recompute write-back)");

        // 上架不再 +stock：createListingFromDockStorage 不得调 adjustCommoditySupply
        assertTrue(source.contains("// pricing: listing no longer bumps stock"),
                "createListingFromDockStorage must not adjust commodity supply on listing");

        // 成交仍写交易记录（参考价数据源）保留
        assertTrue(source.contains("applyCommodityDemand("),
                "purchases must still record trades for reference-price sourcing");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PricingListingFixedPriceContractTest`
Expected: FAIL —— 无两条标记注释。

- [ ] **Step 3a: 改成交回写（~:615-629）**

先 Read `MarketBlockEntity.java` 的 :610-635 确认 `purchaseListingResolved` 里 `market.putListing(new MarketListing(...))` 的精确内容。把其中第 5 个参数 `currentListingUnitPrice(listing, 1)` 改为 `listing.unitPrice()`，并在该 `new MarketListing(` 上一行加标记注释。即：

```java
        // pricing: keep the seller-fixed unit price on resale
        market.putListing(new MarketListing(
                listing.listingId(),
                listing.sellerUuid(),
                listing.sellerName(),
                listing.itemStack(),
                listing.unitPrice(),
                Math.max(0, listing.availableCount() - amount),
                listing.reservedCount() + amount,
                listing.sourceDockPos(),
                listing.sourceDockName(),
                listing.townId(),
                listing.nationId(),
                listing.priceAdjustmentBp(),
                listing.sellerNote()
        ));
```

> 严格按 Read 到的实际字段顺序改，只把 `currentListingUnitPrice(listing, 1)` 一处换成 `listing.unitPrice()` 并加注释；其余参数照抄现状。

- [ ] **Step 3b: 删上架 +stock（~:518-521）**

先 Read `createListingFromDockStorage` 里 `adjustCommoditySupply(listed, amount);` 的精确行（grep `adjustCommoditySupply` 定位调用点，非定义处）。把该调用行替换为标记注释：

```java
            // pricing: listing no longer bumps stock (stock no longer drives price)
```

> 只删 `createListingFromDockStorage` 内的那一处 `adjustCommoditySupply(...)` 调用；保留 `adjustCommoditySupply` 方法定义（:2121，可能其他处仍用或后续清理）。确认删除后该方法局部变量 `listed`/`amount` 仍被后续 `putListing` 使用，不会变成 unused。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PricingListingFixedPriceContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/PricingListingFixedPriceContractTest.java && git commit -m "fix(pricing): no resale price recompute, no listing stock bump"
```

---

## Task 7: 指标派生（最低卖价 = 最便宜活跃挂单）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`（buildOverview 指标区）
- Test: 扩展 `PricingListingFixedPriceContractTest.java`

> spec §5：最低卖价改为扫描该商品当前活跃挂单取最低 `unitPrice`；参考价用 `referencePrice`。先 Read buildOverviewForIdentity 的指标填充段（代理报告指 ~:240/364-376 区与 MarketOverviewData 字段），确认现有"最低卖价/参考价"字段名后再改。

- [ ] **Step 1: 定位现状（只读，不改）**

先 grep 确认指标字段来源：

Run: `cd sailboatmod && grep -n "lowestAsk\|lowestSell\|最低卖价\|referencePrice\|suggestedUnitPrice\|currentCommodityUnitPrice" src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`
Expected: 列出指标填充点。**据实际字段名调整以下断言与改法**——若现状无独立"最低卖价"字段（指标可能在 `MarketOverviewData.ListingEntry` 或 commodity 摘要里），则本任务范围收敛为：把"建议价/参考价"的来源从 `currentCommodityUnitPrice`（动态）改为 `COMMODITY_MARKET.referencePrice`。

- [ ] **Step 2: 扩展契约测试**

在 `PricingListingFixedPriceContractTest.java` 追加：

```java
    @Test
    void overviewSuggestedPriceUsesReferenceNotDynamicQuote() throws Exception {
        String source = marketBlockEntity();
        // buildOverview 的建议价/参考价来源改为 referencePrice，不再用 currentCommodityUnitPrice 动态报价
        assertTrue(source.contains("COMMODITY_MARKET.referencePrice("),
                "overview suggested/reference price should come from referencePrice");
        assertFalse(source.contains("currentCommodityUnitPrice("),
                "dynamic-quote currentCommodityUnitPrice must no longer be used for listing price suggestions");
    }
```

- [ ] **Step 3: 改建议价来源 + 删 currentCommodityUnitPrice 调用**

把 `buildOverviewForIdentity` :240 的：

```java
                    int suggestedUnitPrice = currentCommodityUnitPrice(stack, 1, CommodityMarketService.estimateBaseUnitPrice(stack));
```

替换为：

```java
                    int suggestedUnitPrice = COMMODITY_MARKET.referencePrice(stack);
```

`currentCommodityUnitPrice`（:2047-2053）此时若无其他引用则删除整个方法（grep 确认无引用后删）；若仍被引用则保留。

> 这样 `currentCommodityUnitPrice`（唯一动态价入口）从挂单建议路径移除。`quoteCommodity` 若仅剩历史/图表用途则保留。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PricingListingFixedPriceContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/PricingListingFixedPriceContractTest.java && git commit -m "fix(pricing): overview suggested price derives from reference price"
```

---

## Task 8: 全量测试 + 构建 + 推送

**Files:** 无（验证任务）

- [ ] **Step 1: 全量测试**

Run: `cd sailboatmod && ./gradlew test`
Expected: PASS（全绿）。重点关注 `MarketPricePolicyTest`、`CommodityMarketServiceFundingTest`、新增 3 个定价测试、以及任何依赖旧动态价行为的既有测试——若有失败，判断是"旧测试断言了被废弃的动态价行为"（应更新测试反映新意图）还是"我引入回归"（应修代码）。

- [ ] **Step 2: 编译 + 完整构建出 jar**

Run: `cd sailboatmod && ./gradlew build`
Expected: BUILD SUCCESSFUL，`build/libs/` 生成 `-reobf.jar`。

- [ ] **Step 3: 提交剩余改动并推送**

```bash
cd sailboatmod && git add -A && git commit -m "test(pricing): full pricing redesign verified" || echo "nothing to commit"
```

推送按 sailboatmod 方式（经代理 7897 + gh token）：

```bash
GH_TOKEN=$(gh auth token); REPO_PATH=$(git remote get-url origin | sed -E 's#https://([^/]*@)?github.com/##; s#\.git$##'); BRANCH=$(git rev-parse --abbrev-ref HEAD); HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 git push "https://x-access-token:${GH_TOKEN}@github.com/${REPO_PATH}.git" "$BRANCH"
```

---

## 集成实测（手动，单测无法覆盖 SQLite/游戏内）

实现完成后请游戏内验证（spec 定价验证项）：
1. **批量上架 2000 个不崩**、仍可继续上架（原 stock 压崩已根除）。
2. 成交后**已有挂单价不变**；参考价随成交移动。
3. 上架价**超参考价 ±50% 被拒**并提示。
4. **最低卖价**显示当前最便宜活跃挂单。
5. 购买/成交流程可用；钱包/结算不受影响。

---

## Self-Review（已对照 spec 第二部分核对）

**Spec 覆盖：**
- §1 删动态公式 / currentListingUnitPrice 统一 → Task 4（统一返回定死价）+ Task 7（移除动态价建议入口）✓
- §2 参考价=最近 N 笔成交均价（回退 basePrice）→ Task 2（SQL）+ Task 3（service 回退）✓
- §3 价格保护 ±50% → Task 1（窗口）+ Task 5（接入上架）✓
- §4 挂单价定死 / 删成交回写 → Task 4 + Task 6（删回写）✓
- §5 指标派生 → Task 7 ✓
- §6 上架不 +stock（applyCommodityDemand 保留）→ Task 6 ✓
- 验证 → Task 8 + 集成实测清单 ✓

**类型/签名一致性：** `referencePriceWindow(int,int)`、`recentTradeAveragePrice(String,int)`、`referencePrice(ItemStack)`、`REFERENCE_TRADE_SAMPLE_SIZE`、`ListingPriceWindow` record 字段在各任务间一致。`COMMODITY_MARKET`/`repository` 字段名在 Step 3 要求先 Read 确认。

**无占位符：** 每个代码步骤含完整代码或精确改法；Task 6/7 因依赖现状字段顺序，明确要求"先 Read 锚点再按实际改"，并给出确切的替换目标行。

**已知留待清理（非 spec 要求，YAGNI）：** `MIN/MAX_LISTING_PRICE_BP` 常量、`MarketPricePolicy.listingWindow` 旧方法、`adjustCommoditySupply` 方法定义、`currentCommodityUnitPrice`（若 Task 7 后无引用）——这些在全部接入新逻辑后若确认无引用可统一删；本计划不强制删（保留不影响正确性，删错会引入回归）。

**范围说明：** 本计划只覆盖 spec 第二部分（定价）。运输派发（第一部分）、钱包显示层 UI 不在本计划。
