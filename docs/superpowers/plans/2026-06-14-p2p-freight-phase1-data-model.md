# 点对点货运 第1期：数据模型 + 下单选模式/收货仓 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让买家下单时能选运输模式和收货仓库，订单把货发到买家选的收货仓而非卖家市场绑定的仓库——修掉审查发现的核心缺口。

**Architecture:** `PurchaseOrder` 加 `fulfillment`(FulfillmentMode 枚举) + `targetWarehousePos`(买家收货仓) 两字段；购买链路（游戏内 packet / 网页 / 方法签名）传入这两项；`purchaseListingResolved` 写订单时用买家选的 `targetWarehousePos` 替代硬编码的 `linkedDockPos`；新增"玩家可用收货仓候选"后端解析（供后续 UI 期下拉，本期先建后端 + 默认回退）。

**Tech Stack:** Java 17、Minecraft Forge 1.20.1、NBT、Forge SimpleChannel packet、JUnit 5。测试：纯函数行为测试（FulfillmentMode、PurchaseOrder NBT round-trip 无需 Bootstrap）+ 源码文本断言（下单链路接线、硬编码替换、候选解析存在）。

---

## 背景与约束（实现前必读）

**审查缺口（spec Context）**：现状 `MarketBlockEntity.purchaseListingResolved`（建 PurchaseOrder 处，约 :629-641）把 `targetDockPos` 硬编码为 `linkedDockPos`（卖家市场绑定的仓库），`PurchaseOrder` 无"买家收货地"字段 → A 在 B 店买货会发到 B 的仓库。本期修它。

**本期范围（spec 第1期）**：数据模型 + 下单选模式/收货仓。**只覆盖 `purchaseListing`（直接购买）路径**；求购单（BuyOrder）不建 PurchaseOrder、不在本期。UI 下拉渲染在第5期，本期建"收货仓候选后端解析" + 默认回退（无选择时回退买家默认仓，保证不退化）。

**已核实真实接口（当前代码）**：
- `PurchaseOrder`（market/PurchaseOrder.java）：record 11 字段 `(orderId, listingId, buyerUuid, buyerName, quantity, totalPrice, sourceDockPos, sourceDockName, targetDockPos, targetDockName, status)`；compact 构造器 sanitize 各字段；`save()`/`load(CompoundTag)` NBT；NBT 键 `OrderId/ListingId/BuyerUuid/BuyerName/Quantity/TotalPrice/SourceDockPos(asLong)/SourceDockName/TargetDockPos(asLong)/TargetDockName/Status`。
- `MarketBlockEntity.purchaseListing(String playerUuid, String playerName, @Nullable Player onlinePlayer, int listingIndex, int quantity)`（:559）；`purchaseListingById(..., String listingId, int quantity)`（:571）；`purchaseListingResolved(..., MarketListing listing, int quantity)`（:583）建 PurchaseOrder 于 :629-641（targetDockPos=linkedDockPos）。
- `PurchaseMarketListingPacket`：字段 `marketPos(BlockPos)/listingId(String)/quantity(int)`（:14-16）；handle 调 `purchaseListingById`（:44-50）。
- `MarketWebService.purchaseListing(MinecraftServer, MarketPlayerIdentity, String marketId, int listingIndex, int quantity)`（:284）。
- `NationSavedData.getMember(UUID)`→`NationMemberRecord{playerUuid, nationId, officeId}`（:638，**无 townId，需经 nation→towns 解析**）；`getTownsForNation(nationId)`→`List<TownRecord>`。
- `TownWarehouseRegistry.get(Level, String townId)`→`BlockPos`（:41，一 town 一仓）；`getAll(Level)`→`Set<BlockPos>`（:49）。
- `TownWarehouseBlockEntity.getTownId()`（:108）/`getTownName()`（:115）/`canAccessWarehouse(Player)`（:130）/`getDisplayName()`（:572）。
- `TransportTerminalKind{AUTO,PORT,POST_STATION}`（终端类型，**不是** fulfillment）。**fulfillment 全新建**。
- `MarketOverviewData`：record（market/MarketOverviewData.java）；`MarketBlockEntity.buildOverviewForIdentity`（:158）填充。

**spec 缺口（现状没有，本期新建）**：FulfillmentMode 枚举、PurchaseOrder 两字段、收货仓候选解析、下单链路两入参。

**测试命令**：单类 `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.FulfillmentModeTest`；全量 `./gradlew test`。

---

## File Structure

**新建（生产）：**
- `market/FulfillmentMode.java` — 三模式枚举 + 安全解析

**修改（生产）：**
- `market/PurchaseOrder.java` — 加 `fulfillment`/`targetWarehousePos` 字段 + 构造器 sanitize + save/load NBT 兼容
- `block/entity/MarketBlockEntity.java` — purchaseListing/purchaseListingById/purchaseListingResolved 加 `fulfillment`+`targetWarehousePos` 入参；建单用 targetWarehousePos 替代 linkedDockPos；新增收货仓候选解析方法
- `network/packet/PurchaseMarketListingPacket.java` — 加 fulfillment(String)/targetWarehousePos(BlockPos) 字段 + encode/decode + handle 传参
- `market/web/MarketWebService.java` — purchaseListing 加入参（网页端透传，缺省回退）

**新建（测试）：**
- `src/test/java/com/monpai/sailboatmod/market/FulfillmentModeTest.java`
- `src/test/java/com/monpai/sailboatmod/market/PurchaseOrderFieldsTest.java`
- `src/test/java/com/monpai/sailboatmod/market/P2pOrderWiringContractTest.java`

---

## Task 1: FulfillmentMode 枚举

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/FulfillmentMode.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/FulfillmentModeTest.java`

- [ ] **Step 1: 写失败的纯函数测试**

新建 `src/test/java/com/monpai/sailboatmod/market/FulfillmentModeTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FulfillmentModeTest {
    @Test
    void parsesKnownValues() {
        assertEquals(FulfillmentMode.REAL_PICKUP, FulfillmentMode.fromString("REAL_PICKUP"));
        assertEquals(FulfillmentMode.AUTO_PICKUP, FulfillmentMode.fromString("AUTO_PICKUP"));
        assertEquals(FulfillmentMode.SELLER_SHIP, FulfillmentMode.fromString("SELLER_SHIP"));
    }

    @Test
    void caseInsensitiveAndTrimmed() {
        assertEquals(FulfillmentMode.SELLER_SHIP, FulfillmentMode.fromString(" seller_ship "));
    }

    @Test
    void unknownOrBlankDefaultsToSellerShip() {
        assertEquals(FulfillmentMode.SELLER_SHIP, FulfillmentMode.fromString(null));
        assertEquals(FulfillmentMode.SELLER_SHIP, FulfillmentMode.fromString(""));
        assertEquals(FulfillmentMode.SELLER_SHIP, FulfillmentMode.fromString("garbage"));
    }

    @Test
    void needsVehicleDispatchFlag() {
        // ②③ 需车进调度；① 真人自提不进调度
        assertEquals(false, FulfillmentMode.REAL_PICKUP.needsVehicleDispatch());
        assertEquals(true, FulfillmentMode.AUTO_PICKUP.needsVehicleDispatch());
        assertEquals(true, FulfillmentMode.SELLER_SHIP.needsVehicleDispatch());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.FulfillmentModeTest`
Expected: FAIL（类不存在，编译失败）。

- [ ] **Step 3: 实现 FulfillmentMode**

新建 `src/main/java/com/monpai/sailboatmod/market/FulfillmentMode.java`：

```java
package com.monpai.sailboatmod.market;

import java.util.Locale;

/** 运输履约模式：真人自提 / 自动驾驶自提 / 卖家发货。 */
public enum FulfillmentMode {
    REAL_PICKUP,
    AUTO_PICKUP,
    SELLER_SHIP;

    /** 安全解析：未知/空白回退 SELLER_SHIP；大小写不敏感、去空格。 */
    public static FulfillmentMode fromString(String value) {
        if (value == null) {
            return SELLER_SHIP;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (FulfillmentMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return SELLER_SHIP;
    }

    /** ②③ 需调度配车；① 真人自提不进调度（货锁定等玩家亲自来）。 */
    public boolean needsVehicleDispatch() {
        return this != REAL_PICKUP;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.FulfillmentModeTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/FulfillmentMode.java src/test/java/com/monpai/sailboatmod/market/FulfillmentModeTest.java && git commit -m "feat(freight): FulfillmentMode enum (real-pickup/auto-pickup/seller-ship)"
```

---

## Task 2: PurchaseOrder 加 fulfillment + targetWarehousePos

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/PurchaseOrder.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/PurchaseOrderFieldsTest.java`

- [ ] **Step 1: 写失败的 NBT round-trip 测试**

新建 `src/test/java/com/monpai/sailboatmod/market/PurchaseOrderFieldsTest.java`：

```java
package com.monpai.sailboatmod.market;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PurchaseOrderFieldsTest {
    private static PurchaseOrder sample(String fulfillment, BlockPos target) {
        return new PurchaseOrder(
                "ord-1", "lst-1", "buyer-uuid", "Buyer",
                64, 768,
                new BlockPos(10, 64, 20), "SrcDock",
                new BlockPos(30, 64, 40), "TargetDock",
                "WAITING_DISPATCH",
                fulfillment, target);
    }

    @Test
    void newFieldsSurviveNbtRoundTrip() {
        PurchaseOrder order = sample("SELLER_SHIP", new BlockPos(100, 64, 200));
        PurchaseOrder loaded = PurchaseOrder.load(order.save());

        assertEquals("SELLER_SHIP", loaded.fulfillment());
        assertEquals(new BlockPos(100, 64, 200), loaded.targetWarehousePos());
        assertEquals("buyer-uuid", loaded.buyerUuid());
        assertEquals("WAITING_DISPATCH", loaded.status());
    }

    @Test
    void legacyNbtWithoutNewFieldsDefaults() {
        // 旧订单 NBT 缺 Fulfillment/TargetWarehousePos：fulfillment 回退 SELLER_SHIP，收货仓回退 targetDockPos
        PurchaseOrder order = sample("SELLER_SHIP", new BlockPos(100, 64, 200));
        CompoundTag tag = order.save();
        tag.remove("Fulfillment");
        tag.remove("TargetWarehousePos");

        PurchaseOrder loaded = PurchaseOrder.load(tag);
        assertEquals("SELLER_SHIP", loaded.fulfillment());
        // 收货仓缺省时回退到 targetDockPos（兼容旧发货路径）
        assertEquals(loaded.targetDockPos(), loaded.targetWarehousePos());
    }

    @Test
    void blankFulfillmentSanitizesToSellerShip() {
        PurchaseOrder order = sample("", new BlockPos(1, 64, 1));
        assertEquals("SELLER_SHIP", order.fulfillment());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PurchaseOrderFieldsTest`
Expected: FAIL（PurchaseOrder 无 fulfillment()/targetWarehousePos()，构造器参数不匹配，编译失败）。

- [ ] **Step 3: 改 PurchaseOrder record header + 构造器**

PurchaseOrder.java record header 在 `status` 后加两字段：

```java
public record PurchaseOrder(
        String orderId,
        String listingId,
        String buyerUuid,
        String buyerName,
        int quantity,
        int totalPrice,
        BlockPos sourceDockPos,
        String sourceDockName,
        BlockPos targetDockPos,
        String targetDockName,
        String status,
        String fulfillment,
        BlockPos targetWarehousePos
) {
```

compact 构造器末尾（现有 sanitize 之后）加：

```java
        fulfillment = FulfillmentMode.fromString(fulfillment).name();
        targetWarehousePos = targetWarehousePos == null || targetWarehousePos.equals(BlockPos.ZERO)
                ? targetDockPos
                : targetWarehousePos.immutable();
```

> 说明：fulfillment 经 FulfillmentMode 规范化（空白→SELLER_SHIP）；targetWarehousePos 缺省（null/ZERO）时回退 targetDockPos，保证旧订单与未选收货仓的订单仍有有效目的地。确认文件已 import `BlockPos`（现有字段已用，应已 import）；FulfillmentMode 同包无需 import。

- [ ] **Step 4: 改 save() / load()**

save() 在 `tag.putString("Status", status);` 之后加：

```java
        tag.putString("Fulfillment", fulfillment);
        tag.putLong("TargetWarehousePos", targetWarehousePos.asLong());
```

load() 的 `new PurchaseOrder(...)` 末尾（status 参数后）加两参（缺键回退：fulfillment→SELLER_SHIP via 构造器，targetWarehousePos→ZERO 触发构造器回退 targetDockPos）：

```java
                tag.getString("Status"),
                tag.contains("Fulfillment") ? tag.getString("Fulfillment") : "",
                tag.contains("TargetWarehousePos") ? BlockPos.of(tag.getLong("TargetWarehousePos")) : BlockPos.ZERO
        );
```

- [ ] **Step 5: 跑测试确认通过 + 修既有构造点**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PurchaseOrderFieldsTest`
Expected: 先可能编译失败——所有 `new PurchaseOrder(...)` 旧调用（11 参）会缺 2 参。grep 定位：`grep -rn "new PurchaseOrder(" src/main src/test`，每处末尾补 `, "SELLER_SHIP", BlockPos.ZERO`（ZERO 触发回退 targetDockPos）。`PurchaseOrder.load` 内部那处已在 Step 4 改。修完再跑，Expected: PASS。

- [ ] **Step 6: 全量编译确认无遗漏构造点**

Run: `cd sailboatmod && ./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL（若仍报 PurchaseOrder 构造参数不符，按报错行补 2 参）。

- [ ] **Step 7: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/PurchaseOrder.java src/test/java/com/monpai/sailboatmod/market/PurchaseOrderFieldsTest.java && git add -u && git commit -m "feat(freight): PurchaseOrder carries fulfillment + buyer targetWarehousePos (nbt-compatible)"
```

---

## Task 3: 收货仓候选解析（后端）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/P2pOrderWiringContractTest.java`

> 解析"买家可用收货仓"：玩家 → 其 nation 的 towns → 每 town 的仓库 → 过滤可访问。供后续 UI 期下拉用；本期建后端方法 + 一个"默认收货仓"helper（下单未选时回退）。用源码断言验证方法存在（涉及 NationSavedData/Registry 运行时，难纯单测）。

- [ ] **Step 1: 写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/P2pOrderWiringContractTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class P2pOrderWiringContractTest {
    private static String marketBlockEntity() throws Exception {
        return Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
    }

    @Test
    void resolvesBuyerReceivingWarehouseCandidates() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("receivingWarehouseCandidatesFor("),
                "should resolve a buyer's available receiving warehouses");
        assertTrue(src.contains("defaultReceivingWarehouseFor("),
                "should resolve a buyer's default receiving warehouse for fallback");
        assertTrue(src.contains("getTownsForNation(") || src.contains("getMember("),
                "candidate resolution should go through the buyer's nation/town");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.P2pOrderWiringContractTest`
Expected: FAIL（无这两个方法）。

- [ ] **Step 3: 加候选解析方法**

先 Read `MarketBlockEntity.java` 确认已 import `NationSavedData`/`NationMemberRecord`/`TownRecord`/`TownWarehouseRegistry`/`TownWarehouseBlockEntity`/`UUID`（多数应已用）。在类内新增（靠近其他 warehouse 相关方法）：

```java
    /** 买家可用的收货仓库坐标列表：经其 nation 的各 town → town 仓库，过滤可访问。 */
    public java.util.List<BlockPos> receivingWarehouseCandidatesFor(String buyerUuid) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        if (level == null || level.isClientSide || buyerUuid == null || buyerUuid.isBlank()) {
            return out;
        }
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(buyerUuid.trim());
        } catch (IllegalArgumentException ex) {
            return out;
        }
        com.monpai.sailboatmod.nation.data.NationSavedData nations =
                com.monpai.sailboatmod.nation.data.NationSavedData.get(level);
        com.monpai.sailboatmod.nation.model.NationMemberRecord member = nations.getMember(uuid);
        if (member == null || member.nationId() == null || member.nationId().isBlank()) {
            return out;
        }
        for (com.monpai.sailboatmod.nation.model.TownRecord town : nations.getTownsForNation(member.nationId())) {
            BlockPos warehousePos = com.monpai.sailboatmod.dock.TownWarehouseRegistry.get(level, town.townId());
            if (warehousePos != null && !out.contains(warehousePos)) {
                out.add(warehousePos);
            }
        }
        return out;
    }

    /** 买家默认收货仓：候选列表第一个；无则返回 null（下单回退由调用方处理）。 */
    @Nullable
    public BlockPos defaultReceivingWarehouseFor(String buyerUuid) {
        java.util.List<BlockPos> candidates = receivingWarehouseCandidatesFor(buyerUuid);
        return candidates.isEmpty() ? null : candidates.get(0);
    }
```

> `getTownsForNation` / `TownRecord.townId()` / `TownWarehouseRegistry.get` 按子代理核实存在；若签名不符（如 getTownsForNation 不存在），grep `getTownsForNation`/`getTownAt`/`TownWarehouseRegistry` 用实际方法替换——本步要求**先 Read 确认实际签名再写**。`@Nullable` 已在该文件使用。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.P2pOrderWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/P2pOrderWiringContractTest.java && git commit -m "feat(freight): resolve buyer receiving-warehouse candidates via nation/town"
```

---

## Task 4: 下单链路传 fulfillment + targetWarehousePos，建单用 targetWarehousePos

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`（purchaseListing/ById/Resolved + 建单处）
- Test: 扩展 `P2pOrderWiringContractTest.java`

> 核心修缺口：建 PurchaseOrder 时用买家选的 targetWarehousePos（未选则回退 defaultReceivingWarehouseFor，再无则现状 linkedDockPos），并写 fulfillment。

- [ ] **Step 1: 扩展契约测试**

在 `P2pOrderWiringContractTest.java` 追加：

```java
    @Test
    void purchaseOrderUsesBuyerWarehouseNotHardcodedLinkedDock() throws Exception {
        String src = marketBlockEntity();
        // 建单时目的地用买家收货仓（带回退），不再无条件用 linkedDockPos
        assertTrue(src.contains("resolveBuyerTargetWarehouse("),
                "order creation should resolve the buyer's chosen/default receiving warehouse");
        assertTrue(src.contains("FulfillmentMode.fromString("),
                "order creation should record the fulfillment mode");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.P2pOrderWiringContractTest`
Expected: FAIL。

- [ ] **Step 3: 改 purchaseListing/ById/Resolved 签名加两入参**

把三个方法签名末尾加 `String fulfillment, @Nullable BlockPos targetWarehousePos`：

```java
    public boolean purchaseListing(String playerUuid, String playerName, @Nullable Player onlinePlayer,
                                   int listingIndex, int quantity,
                                   String fulfillment, @Nullable BlockPos targetWarehousePos) {
        // ...原体不变，末尾调用 purchaseListingResolved 时透传两参...
        return purchaseListingResolved(playerUuid, playerName, onlinePlayer, listing, quantity,
                fulfillment, targetWarehousePos);
    }
```

`purchaseListingById` 同样加两参并透传。`purchaseListingResolved` 加两参。**保留旧签名作重载**（避免改爆所有调用方）：

```java
    public boolean purchaseListing(String playerUuid, String playerName, @Nullable Player onlinePlayer,
                                   int listingIndex, int quantity) {
        return purchaseListing(playerUuid, playerName, onlinePlayer, listingIndex, quantity,
                "SELLER_SHIP", null);
    }
```

（`purchaseListingById` 同样加旧签名重载，默认 SELLER_SHIP + null。）

- [ ] **Step 4: 加 resolveBuyerTargetWarehouse helper + 改建单**

在 MarketBlockEntity 加：

```java
    /** 下单目的地解析：买家选的收货仓 → 买家默认收货仓 → 现状 linkedDockPos（最终回退）。 */
    private BlockPos resolveBuyerTargetWarehouse(String buyerUuid, @Nullable BlockPos chosen) {
        if (chosen != null && !chosen.equals(BlockPos.ZERO)) {
            return chosen;
        }
        BlockPos preferred = defaultReceivingWarehouseFor(buyerUuid);
        return preferred != null ? preferred : linkedDockPos;
    }
```

`purchaseListingResolved` 里建 PurchaseOrder（:629-641 处）改为：

```java
        BlockPos receivingWarehouse = resolveBuyerTargetWarehouse(safePlayerUuid, targetWarehousePos);
        String mode = FulfillmentMode.fromString(fulfillment).name();
        PurchaseOrder createdOrder = new PurchaseOrder(
                market.nextId(),
                listing.listingId(),
                safePlayerUuid,
                safePlayerName,
                amount,
                total,
                listing.sourceDockPos(),
                listing.sourceDockName(),
                receivingWarehouse,
                warehouseDisplayNameFor(receivingWarehouse),
                "WAITING_SHIPMENT",
                mode,
                receivingWarehouse
        );
```

> targetDockPos 与 targetWarehousePos 都设为 receivingWarehouse（统一目的地）。`warehouseDisplayNameFor(BlockPos)`：若该处有 TownWarehouseBlockEntity 取其 getDisplayName().getString()，否则用现状 `warehouse.getDisplayName().getString()` 的取法——**Read 建单处上下文，沿用现有取名方式，目标改为 receivingWarehouse 那个仓的名**。若无现成 helper，内联：`level.getBlockEntity(receivingWarehouse) instanceof TownWarehouseBlockEntity w ? w.getDisplayName().getString() : warehouse.getDisplayName().getString()`。

- [ ] **Step 5: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.P2pOrderWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL（旧签名重载保证现有调用不断）。

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/P2pOrderWiringContractTest.java && git commit -m "fix(freight): order targets buyer's receiving warehouse (not seller's linked dock) + records fulfillment"
```

---

## Task 5: 购买 packet + 网页端透传两入参

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/PurchaseMarketListingPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`
- Test: 扩展 `P2pOrderWiringContractTest.java`

- [ ] **Step 1: 扩展契约测试**

在 `P2pOrderWiringContractTest.java` 追加：

```java
    @Test
    void purchasePacketAndWebCarryFulfillmentAndWarehouse() throws Exception {
        String packet = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/network/packet/PurchaseMarketListingPacket.java"));
        String web = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));

        assertTrue(packet.contains("fulfillment") && packet.contains("targetWarehousePos"),
                "purchase packet should carry fulfillment + target warehouse");
        assertTrue(web.contains("purchaseListingById(") || web.contains("purchaseListing("),
                "web purchase should reach the listing purchase path");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.P2pOrderWiringContractTest`
Expected: FAIL（packet 无新字段）。

- [ ] **Step 3: 改 PurchaseMarketListingPacket 加字段**

先 Read `PurchaseMarketListingPacket.java` 全文。加字段 + 构造器 + encode/decode + handle 传参。字段区加：

```java
    private final String fulfillment;
    private final BlockPos targetWarehousePos;
```

构造器加两参赋值。encode 加（在现有写之后；BlockPos 用 writeBlockPos，null 用 BlockPos.ZERO 占位）：

```java
        buffer.writeUtf(packet.fulfillment == null ? "SELLER_SHIP" : packet.fulfillment);
        buffer.writeBlockPos(packet.targetWarehousePos == null ? net.minecraft.core.BlockPos.ZERO : packet.targetWarehousePos);
```

decode 加对应读：

```java
        String fulfillment = buffer.readUtf();
        net.minecraft.core.BlockPos targetWarehousePos = buffer.readBlockPos();
```

并把 decode 的 `new PurchaseMarketListingPacket(...)` 传上这两参。handle 里 `purchaseListingById(...)` 调用末尾加 `, packet.fulfillment, packet.targetWarehousePos`。

> ZERO 在服务端 `resolveBuyerTargetWarehouse` 会被当"未选"回退默认——语义正确。确认 PacketStringCodec/writeUtf 用法与该文件其他 packet 一致。

- [ ] **Step 4: 改 MarketWebService.purchaseListing 透传**

`MarketWebService.purchaseListing` 加两入参（网页端本期可先传默认，UI 选择留第5期）：

```java
    public boolean purchaseListing(MinecraftServer server, MarketPlayerIdentity identity, String marketId,
                                   int listingIndex, int quantity,
                                   String fulfillment, net.minecraft.core.BlockPos targetWarehousePos) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        return resolved != null && identity != null
                && resolved.market().purchaseListing(
                identity.playerUuidString(), identity.playerName(), identity.onlinePlayer(),
                listingIndex, quantity, fulfillment, targetWarehousePos);
    }
```

保留旧签名重载（默认 SELLER_SHIP + null）：

```java
    public boolean purchaseListing(MinecraftServer server, MarketPlayerIdentity identity, String marketId,
                                   int listingIndex, int quantity) {
        return purchaseListing(server, identity, marketId, listingIndex, quantity, "SELLER_SHIP", null);
    }
```

> 网页端 HTTP handler 现状调旧签名→走默认，不破坏。第5期再让网页 UI 传真实选择。

- [ ] **Step 5: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.P2pOrderWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/network/packet/PurchaseMarketListingPacket.java src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java src/test/java/com/monpai/sailboatmod/market/P2pOrderWiringContractTest.java && git commit -m "feat(freight): purchase packet + web carry fulfillment/target warehouse (default fallback)"
```

---

## Task 6: 全量测试 + 构建 + 推送

**Files:** 无（验证任务）

- [ ] **Step 1: 全量测试**

Run: `cd sailboatmod && ./gradlew test`
Expected: PASS。重点：FulfillmentModeTest、PurchaseOrderFieldsTest、P2pOrderWiringContractTest、以及所有用到 PurchaseOrder 构造器的既有测试（Task 2 Step 5 已补 2 参）。

- [ ] **Step 2: 完整构建**

Run: `cd sailboatmod && ./gradlew build`
Expected: BUILD SUCCESSFUL，`build/libs/` 出 `-reobf.jar`。

- [ ] **Step 3: 提交剩余 + 推送（经代理 7897 + gh token）**

```bash
cd sailboatmod && git add -A && git commit -m "test(freight): phase-1 data model + order routing verified" || echo "nothing to commit"
GH_TOKEN=$(gh auth token); REPO_PATH=$(git remote get-url origin | sed -E 's#https://([^/]*@)?github.com/##; s#\.git$##'); BRANCH=$(git rev-parse --abbrev-ref HEAD); HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 git push "https://x-access-token:${GH_TOKEN}@github.com/${REPO_PATH}.git" "$BRANCH"
```

---

## 集成实测（手动，单测覆盖不到 town/仓库运行时）

1. A 在 B 店买货、选收货仓 X（X 是 A 的 town 仓）→ PurchaseOrder.targetWarehousePos = X（不是 B 的 linkedDockPos）。
2. A 未选收货仓 → 回退 A 默认收货仓；A 无 town 仓 → 回退 linkedDockPos（不退化、不报错）。
3. 网页端购买（本期走默认 SELLER_SHIP + 默认仓）→ 订单正常建。
4. 旧存档 PurchaseOrder 加载不报错（NBT 缺 Fulfillment/TargetWarehousePos 回退）。

> 注：本期只到"订单正确记录买家收货仓"。货真正发到该仓由第2期（调度）+ 现有发货路径用 targetWarehousePos 解析目的地完成——第2期会让发货读 targetWarehousePos。

---

## Self-Review（对照 spec 第1期）

**覆盖**：FulfillmentMode（Task1）✓；PurchaseOrder 加字段（Task2）✓；收货仓候选解析（Task3）✓；建单用 targetWarehousePos 修硬编码 + 记 fulfillment（Task4）✓；packet/web 传参（Task5）✓；验证（Task6）✓。

**类型/签名一致**：`FulfillmentMode.fromString/needsVehicleDispatch`、PurchaseOrder 新字段 `fulfillment(String)/targetWarehousePos(BlockPos)`、`receivingWarehouseCandidatesFor/defaultReceivingWarehouseFor/resolveBuyerTargetWarehouse`、packet/web 两入参 + 旧签名重载——各 Task 一致。

**无占位符**：每步含完整代码；Task3/4 因依赖现状 nation/town/registry 签名与建单上下文，明确要求"先 Read 确认实际签名再写"，并给确切方法体与断言。

**已知不确定（如实标注）**：(1) `getTownsForNation`/`TownRecord.townId`/`TownWarehouseRegistry.get` 实际签名以 Read 为准；(2) 建单处取仓名的现有方式以 Read 为准；(3) 收货仓"可访问=可收货"权限本期用"属于买家 nation 的 town 仓"近似，精确权限留后续。

**范围**：本期只数据模型 + 下单记录目的地（直接购买路径）。调度发货（第2期）、自动自提（第3期）、真人自提（第4期）、UI 下拉渲染（第5期）独立。求购单不在本期。
