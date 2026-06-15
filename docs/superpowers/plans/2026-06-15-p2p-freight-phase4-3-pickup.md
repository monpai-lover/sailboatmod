# 点对点货运 第4+3期：真人自提 + 自动自提（共享进-zone装货）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现买家自提两种模式——真人自提（货为买家锁定，买家亲自把车开进产地终端 zone 自动装货）与自动驾驶自提（系统驾驶买家的车空驶到产地、进 zone 自动装同一套货、再送回买家收货仓）。

**Architecture:** 两期共享一个核心：「载具进产地终端 zone → 自动装该买家的锁定货」。第4期建这个核心（货锁定 + 仓库按买家锁货 + 载具 tick 进-zone 检测 + 自动装货），真人自提靠玩家亲自开车触发它。第3期复用它：系统驾驶买家空车空驶到产地终端（autopilot 路由到产地）→ 进 zone 触发同一装货 → 装货后自动续发到买家收货仓。无买家空车则排队。

**Tech Stack:** Java 17、Minecraft Forge 1.20.1、NBT、JUnit 5。测试：纯函数行为测试（锁定判定、装货量计算、自提资格）+ 源码文本断言（仓库锁货 API、载具 tick 接线、调度分支）。

---

## 背景与约束（实现前必读）

**已完成**：第1期（PurchaseOrder 带 fulfillment=FulfillmentMode/targetWarehousePos）、第2期（TransportDispatchService 后台调度+排队，复用 tryAutoDispatchOrders）。

**本计划范围（用户确认）**：后端第4期（真人自提）+ 第3期（自动自提），**共享"进 zone 装货"机制**。UI（第5期）不在本计划。空驶用"路由到产地 + 复用进 zone 装货"，不另造两段路由模型。

**已核实现状**：
- 载具 tick：`SailboatEntity.tick()`（:368）、`CarriageEntity.tick()`（:460），大量用 `DockBlockEntity.isInsideDockZone(Vec3)`（zone 检测有基础）。
- `dispatchShipmentPlan`（`MarketBlockEntity`:1829）：**先 loadCargo 再发车**（假设货已在产地装好）——这是 SELLER_SHIP 路径，自提不走它的装货，而是"先空驶到产地、进 zone 再装"。
- 仓库：`TownWarehouseBlockEntity.extractMatchingStock(UUID ownerId, ItemStack template, int amount)`（:188）、`insertCargo`、`countMatchingStock`。**现状无"按买家锁定"机制**。
- `FulfillmentMode`（第1期）：REAL_PICKUP / AUTO_PICKUP / SELLER_SHIP；`needsVehicleDispatch()`=非 REAL_PICKUP。
- `isBoatAvailableForDispatch(boat, player)`（`DockBlockEntity`:1646）：player==null 时任意空闲车可派——**第3期要按 buyerUuid 过滤买家自己的车**，需加判据。
- `tryDispatchWaitingOrdersAuto`（`MarketBlockEntity`:1137）：第2期已加 needsVehicleDispatch 门控；AUTO_PICKUP 与 SELLER_SHIP 现状一视同仁——第3期要分流。
- `ShipmentManifestEntry`（含 recipientUuid/quantity/itemStack）、`PurchaseOrder.targetWarehousePos`（收货仓）。
- 现状到站卸货入仓：`finishAutopilot`/`receiveShipment`/`tryDeliverManifestEntryToWarehouse`——自提送达收货仓复用它（目的地用 targetWarehousePos）。

**spec 缺口（本计划新建）**：
- 第4期：PurchaseOrder 锁定字段 + 状态 PICKUP_LOCKED、`TownWarehouseBlockEntity` 按买家锁货 API、载具 tick 进-zone 装货检测、装货量计算。
- 第3期：买家车归属过滤、AUTO_PICKUP 调度分支（路由买家车到产地，复用进-zone 装货，装货后续发收货仓）。

**测试命令**：单类 `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupLockTest`；全量 `./gradlew test`。

> 注：仓库/载具运行时逻辑无法纯单测——核心判定（锁定资格、装货量、自提资格）抽纯函数单测；仓库 API、载具 tick 接线、调度分支用源码断言。真实自提行为留集成实测。

---

## File Structure

**新建（生产）：**
- `market/PickupLock.java` — 锁定相关纯逻辑（装货量计算、是否本买家锁货等纯函数）

**修改（生产）：**
- `market/PurchaseOrder.java` — 加 `lockedForBuyerUuid`(String) 字段（或复用 buyerUuid + status PICKUP_LOCKED）+ NBT；本计划用**状态 PICKUP_LOCKED + 现有 buyerUuid**，不加新字段（YAGNI：买家即 lockedFor）
- `market/FulfillmentMode.java` — 加 `isPickup()`（REAL_PICKUP 或 AUTO_PICKUP）辅助
- `block/entity/TownWarehouseBlockEntity.java` — 按买家锁货：标记/提取锁定货（用现有 owner 维度 + 锁定订单驱动，不引仓库新存储池——锁定靠"订单 PICKUP_LOCKED + 货在仓库"逻辑表达）
- `block/entity/MarketBlockEntity.java` — 下单 REAL_PICKUP/AUTO_PICKUP 时把订单设 PICKUP_LOCKED（产地锁货）；第3期 AUTO_PICKUP 调度分支
- `block/entity/DockBlockEntity.java` — 载具进 zone 回调 + 触发自动装锁定货；买家车归属过滤（isBoatAvailableForDispatch 加 buyerUuid）
- `entity/SailboatEntity.java` + `entity/CarriageEntity.java` — tick 里调 dock 的进-zone 检测（停靠时）

**新建（测试）：**
- `src/test/java/com/monpai/sailboatmod/market/PickupLockTest.java`（纯函数）
- `src/test/java/com/monpai/sailboatmod/market/PickupWiringContractTest.java`（源码断言：仓库锁货/tick/调度分支）

---

## 设计要点（先读，统领各 Task）

**锁定的表达（不引新存储池，YAGNI）**：货物物理上仍在产地仓库；"为买家 X 锁定"= 存在一条 `status==PICKUP_LOCKED && buyerUuid==X` 的 PurchaseOrder 指向该产地仓库。调度（第2期）已跳过非 needsVehicleDispatch；本计划进一步：**PICKUP_LOCKED 状态的订单不被任何发货逻辑动用**（真人自提货等买家来；自动自提货等买家车来）。装货时按"该买家在该产地的 PICKUP_LOCKED 订单"提货。

**进-zone 装货核心（两期共享）**：`DockBlockEntity.tryLoadPickupCargo(TransportEntity boat)` —— 当一辆车进入本终端 zone 且停稳，找"车主买家在本产地的 PICKUP_LOCKED 订单"，从仓库提货装车（量=车余量与锁货量取小），装上的订单转 IN_TRANSIT + 设 manifest。真人自提：玩家车触发，装完玩家自理。自动自提：系统车触发，装完续发收货仓。

---

## Task 1: 自提资格与装货量纯函数

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/PickupLock.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/PickupLockTest.java`

- [ ] **Step 1: 写失败的纯函数测试**

新建 `src/test/java/com/monpai/sailboatmod/market/PickupLockTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickupLockTest {
    @Test
    void loadAmountIsMinOfVehicleSpaceAndLockedQuantity() {
        assertEquals(20, PickupLock.loadableAmount(20, 64));  // 锁货20、车空间64 → 装20
        assertEquals(64, PickupLock.loadableAmount(200, 64)); // 锁货200、车空间64 → 装64
        assertEquals(0, PickupLock.loadableAmount(0, 64));
        assertEquals(0, PickupLock.loadableAmount(20, 0));
    }

    @Test
    void negativeInputsClampToZero() {
        assertEquals(0, PickupLock.loadableAmount(-5, 64));
        assertEquals(0, PickupLock.loadableAmount(20, -1));
    }

    @Test
    void isPickupOrderRecognizesLockedPickupStatus() {
        assertTrue(PickupLock.isPickupOrder("PICKUP_LOCKED", "REAL_PICKUP"));
        assertTrue(PickupLock.isPickupOrder("PICKUP_LOCKED", "AUTO_PICKUP"));
        assertFalse(PickupLock.isPickupOrder("WAITING_SHIPMENT", "SELLER_SHIP"));
        assertFalse(PickupLock.isPickupOrder("IN_TRANSIT", "REAL_PICKUP"));
    }

    @Test
    void ownsVehicleMatchesBuyerUuid() {
        assertTrue(PickupLock.vehicleBelongsToBuyer("uuid-a", "uuid-a"));
        assertFalse(PickupLock.vehicleBelongsToBuyer("uuid-a", "uuid-b"));
        assertFalse(PickupLock.vehicleBelongsToBuyer("uuid-a", null));
        assertFalse(PickupLock.vehicleBelongsToBuyer(null, "uuid-a"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupLockTest`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现 PickupLock**

新建 `src/main/java/com/monpai/sailboatmod/market/PickupLock.java`：

```java
package com.monpai.sailboatmod.market;

/** 买家自提（真人/自动）的锁定与装货纯逻辑。 */
public final class PickupLock {
    /** 自提锁定状态常量。 */
    public static final String STATUS_LOCKED = "PICKUP_LOCKED";

    private PickupLock() {
    }

    /** 实际可装量 = 锁定量与载具剩余空间取小（均非负）。 */
    public static int loadableAmount(int lockedQuantity, int vehicleSpace) {
        int locked = Math.max(0, lockedQuantity);
        int space = Math.max(0, vehicleSpace);
        return Math.min(locked, space);
    }

    /** 是否为待自提的锁定订单：状态 PICKUP_LOCKED 且 fulfillment 是自提（真人/自动）。 */
    public static boolean isPickupOrder(String status, String fulfillment) {
        if (status == null || !STATUS_LOCKED.equals(status.trim())) {
            return false;
        }
        FulfillmentMode mode = FulfillmentMode.fromString(fulfillment);
        return mode == FulfillmentMode.REAL_PICKUP || mode == FulfillmentMode.AUTO_PICKUP;
    }

    /** 载具是否属于该买家（按 uuid 匹配，任一为空则否）。 */
    public static boolean vehicleBelongsToBuyer(String vehicleOwnerUuid, String buyerUuid) {
        return vehicleOwnerUuid != null && buyerUuid != null
                && !vehicleOwnerUuid.isBlank()
                && vehicleOwnerUuid.equals(buyerUuid);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupLockTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/PickupLock.java src/test/java/com/monpai/sailboatmod/market/PickupLockTest.java && git commit -m "feat(freight): pickup lock pure logic (loadable amount, pickup-order, ownership)"
```

---

## Task 2: 下单自提订单设为 PICKUP_LOCKED（产地锁货）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/PickupWiringContractTest.java`

> REAL_PICKUP/AUTO_PICKUP 下单时，订单状态设为 PICKUP_LOCKED（不是 WAITING_SHIPMENT），表示"货在产地为该买家锁定"。这样第2期后台调度（只发 needsVehicleDispatch 的 WAITING_SHIPMENT）天然不动它；真人自提等买家来、自动自提等买家车来。

- [ ] **Step 1: 写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/PickupWiringContractTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PickupWiringContractTest {
    private static String marketBlockEntity() throws Exception {
        return Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
    }

    @Test
    void pickupOrdersAreCreatedLocked() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("PickupLock.STATUS_LOCKED") || src.contains("\"PICKUP_LOCKED\""),
                "pickup-mode purchases should create orders in PICKUP_LOCKED status");
        assertTrue(src.contains("FulfillmentMode.fromString(fulfillment)") || src.contains("FulfillmentMode.fromString(fulfillmentMode)"),
                "order status should branch on fulfillment mode");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest`
Expected: FAIL。

- [ ] **Step 3: 改建单状态按 fulfillment 分支**

先 Read `MarketBlockEntity.purchaseListingResolved` 建单处（第1期改过，grep `PurchaseOrder createdOrder = new PurchaseOrder(` 定位），把 status 参数从固定 `"WAITING_SHIPMENT"` 改为按 fulfillment 决定：自提→PICKUP_LOCKED，卖家发货→WAITING_SHIPMENT。在建单前加：

```java
        FulfillmentMode resolvedMode = FulfillmentMode.fromString(fulfillment);
        String orderStatus = (resolvedMode == FulfillmentMode.REAL_PICKUP || resolvedMode == FulfillmentMode.AUTO_PICKUP)
                ? PickupLock.STATUS_LOCKED
                : "WAITING_SHIPMENT";
```

把 `new PurchaseOrder(...)` 的 status 参数 `"WAITING_SHIPMENT"` 改成 `orderStatus`；`fulfillmentMode`（第1期已算的 `FulfillmentMode.fromString(fulfillment).name()`）保持。确认 `PickupLock`/`FulfillmentMode` 已 import（同包 market，MarketBlockEntity 在 block.entity 包，需 import 两者；第1期已 import FulfillmentMode，PickupLock 需加 import）。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/PickupWiringContractTest.java && git commit -m "feat(freight): pickup-mode purchases create PICKUP_LOCKED orders"
```

---

## Task 3: 进-zone 装货核心（MarketBlockEntity.tryLoadPickupCargo + DockBlockEntity 委托）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`（核心）
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java`（进-zone 委托）
- Test: 扩展 `PickupWiringContractTest.java`

> **核实修正（2026-06-15，执行中发现）**：原计划设想"从产地仓库提锁定货"——但核实发现**货在上架时已被物理扣出仓库**（`MarketBlockEntity:526` `warehouse.extractVisibleStorage(sellerId,...)`），上架后货只剩 `MarketListing` 记录。现状 SELLER_SHIP 发货是**按 listing 模板凭空生成货装车**（`buildShipmentPlanForWarehouseTarget:1799` `splitCargo(listing.itemStack(), qty)` → `boat.loadCargo`），全程不碰仓库。**所以自提装货必须走同一模型：按 `listing.itemStack()` 模板用 `splitCargo` 生成 + `splitOrderForShipment` 拆单，删掉 extractPickupCargo 提仓库货。** 风险点"仓库提锁定货归属"因此消失。
>
> **位置修正**：`splitCargo`/`splitOrderForShipment`/`PurchaseSplit`/`ShipmentOrderSelection` 都是 `MarketBlockEntity` 的 private，`DockBlockEntity` 够不着。故核心 `tryLoadPickupCargo` 放 **MarketBlockEntity**；`DockBlockEntity` 只做进-zone 检测后委托。
>
> 共享核心：一辆车进入产地终端 zone → 找"车主买家在本产地的 PICKUP_LOCKED 订单" → 按 listing 模板 splitCargo 生成货 + splitOrderForShipment 拆单装车（装不下留余量）→ 装上的订单转 IN_TRANSIT + 设 manifest。返回是否装了货（供第3期判断"装完续发"）。

- [ ] **Step 1: 扩展契约测试**

在 `PickupWiringContractTest.java` 追加：

```java
    @Test
    void marketHasPickupLoadCore() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("tryLoadPickupCargo("),
                "market should expose a pickup-cargo loading core shared by real/auto pickup");
        assertTrue(src.contains("PickupLock.isPickupOrder(") || src.contains("PickupLock.STATUS_LOCKED"),
                "pickup loading should match locked pickup orders");
        assertTrue(src.contains("splitCargo(") && src.contains("splitOrderForShipment("),
                "pickup loading should reuse the seller-ship split logic (template-based, not warehouse extract)");
    }

    @Test
    void dockDelegatesPickupLoadToMarket() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java"));
        assertTrue(src.contains("tryLoadPickupCargo("),
                "dock should expose an in-zone pickup trigger that delegates to the market core");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest`
Expected: FAIL。

- [ ] **Step 3: 实现 tryLoadPickupCargo（核心在 MarketBlockEntity）**

在 `MarketBlockEntity` 内新增核心方法。**复用现有 private**：`findListingById`、`splitCargo`、`splitOrderForShipment`、`PurchaseSplit`、`ShipmentManifestEntry`、`findMaxLoadableQuantity`（发货里用来算"车对该物品还能装多少"，:1788）。订单查询用 `MarketSavedData.getOrdersForBuyer(buyerUuid)`（:132）再按"本产地终端 + PICKUP_LOCKED"过滤。

```java
    /**
     * 进-zone 装货核心（真人自提=玩家车触发；自动自提=系统车触发）。
     * 找车主买家在本产地的 PICKUP_LOCKED 订单，按 listing 模板生成货装车（splitCargo + splitOrderForShipment 拆单，
     * 与 SELLER_SHIP 同一装货模型——货已在上架时扣出仓库，此处按模板兑现），装上的订单转 IN_TRANSIT + 设 manifest。
     * @param boat 进入本产地终端 zone 的载具
     * @return 是否装了货（供自动自提判断"装完续发收货仓"）
     */
    public boolean tryLoadPickupCargo(TransportEntity boat) {
        if (level == null || level.isClientSide || boat == null) {
            return false;
        }
        String buyerUuid = boat.getOwnerUuid();
        if (buyerUuid == null || buyerUuid.isBlank()) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        List<PurchaseOrder> lockedOrders = pickupOrdersForBuyerAtThisSource(market, buyerUuid);
        if (lockedOrders.isEmpty()) {
            return false;
        }
        List<ItemStack> cargo = new ArrayList<>();
        List<ShipmentManifestEntry> manifest = new ArrayList<>();
        List<ShipmentOrderSelection> selections = new ArrayList<>();
        for (PurchaseOrder order : lockedOrders) {
            MarketListing listing = findListingById(market, order.listingId());
            if (listing == null) {
                continue;
            }
            int shippable = findMaxLoadableQuantity(boat, cargo, listing.itemStack(), order.quantity());
            if (shippable <= 0) {
                if (!cargo.isEmpty()) {
                    break; // 车满了
                }
                continue;
            }
            PurchaseSplit split = splitOrderForShipment(market, order, shippable);
            if (split == null) {
                continue;
            }
            cargo.addAll(splitCargo(listing.itemStack(), split.shipped().quantity()));
            selections.add(new ShipmentOrderSelection(split.shipped(), split.remainder(), listing));
        }
        if (cargo.isEmpty() || selections.isEmpty()) {
            return false;
        }
        if (!boat.canLoadCargo(cargo) || !boat.loadCargo(cargo)) {
            return false;
        }
        // 装上的订单转 IN_TRANSIT、留余量、建 manifest —— 复用 SELLER_SHIP 的 selection→manifest 落库逻辑
        applyPickupSelections(market, selections, manifest, boat);
        boat.setPendingShipmentManifest(manifest);
        return true;
    }
```

> 需 Read 后落实的 helper（已确认现状 API 存在）：
> - `pickupOrdersForBuyerAtThisSource(market, buyerUuid)`：`market.getOrdersForBuyer(buyerUuid)` 过滤出 `PickupLock.isPickupOrder(order.status(), order.fulfillment())` 且 `order.sourceDockPos()`==本市场关联终端（Read 本市场怎么解析自己的产地终端，grep `linkedDockPos`/`sourceDock`；PurchaseOrder 有 `sourceDockPos()`，见 :2019）。
> - `applyPickupSelections(...)`：把每个 selection 的 dispatchOrder 转 IN_TRANSIT、remainderOrder 留库、加 ShipmentManifestEntry —— **直接抽取 `dispatchShipmentPlan` 的 selection 循环段（:1851 起）复用**，去掉里面 SELLER_SHIP 专有的 ShippingOrder/route 部分，只保留"订单转状态 + manifest"。Read :1844-1900 抽取。
> **本 Task 实现时先 Read `dispatchShipmentPlan` 的 selection→manifest 段（:1844 起）+ 本市场产地终端解析，再落地两个 helper。**

- [ ] **Step 4: DockBlockEntity 进-zone 委托**

`DockBlockEntity.tryLoadPickupCargo(TransportEntity)`：dock 无到市场的反指针，故广播给本维度所有市场（遍历 `MarketTerminalSavedData.get(level).entries()` 解析 `MarketBlockEntity`），委托其核心方法。只有"车主买家在该市场产地有 PICKUP_LOCKED 锁定货"的市场会真装货（市场侧按 sourceDockPos+buyerUuid 过滤，不误装）。装到货即停。

> **驿站和港口都覆盖（零额外代码）**：`PostStationBlockEntity extends DockBlockEntity`，故驿站自动继承此委托方法——港口（帆船）和驿站（马车）共用同一份。无需为驿站单独写委托。Task4 的载具 tick 触发才需 SailboatEntity/CarriageEntity 各一份。

- [ ] **Step 5: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java src/test/java/com/monpai/sailboatmod/market/PickupWiringContractTest.java && git commit -m "feat(freight): pickup-cargo loading core in market (template split, reuses seller-ship logic) + dock in-zone delegation"
```

---

## Task 4: 载具进-zone 触发装货（真人自提走通）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java` + `entity/CarriageEntity.java`
- Test: 扩展 `PickupWiringContractTest.java`

> 载具 tick 里：停稳（速度≈0 / 非 autopilot 行进中）且在某终端 zone 内 → 调该 dock 的 tryLoadPickupCargo。真人自提：玩家把车开进产地 zone 即触发。节流：仅停稳时检测，非每 tick 全扫。

- [ ] **Step 1: 扩展契约测试**

在 `PickupWiringContractTest.java` 追加：

```java
    @Test
    void vehiclesTriggerPickupLoadInZone() throws Exception {
        String sb = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
        String cr = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java"));
        assertTrue(sb.contains("tryLoadPickupCargo("), "sailboat tick should trigger in-zone pickup loading");
        assertTrue(cr.contains("tryLoadPickupCargo("), "carriage tick should trigger in-zone pickup loading");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest`
Expected: FAIL。

- [ ] **Step 3: 帆船 tick 加进-zone 装货检测**

先 Read `SailboatEntity.tick()`（:368）服务端段 + 找到"停稳且不在 autopilot 行进"的判据（grep 现有速度/autopilot 状态）。加一个低频检测（复用 traceLiveSync 那种计数器或每 N tick）：车停稳 + 不在 autopilot 主动行驶 + 有货舱空间 → 找所在终端 dock → 调 tryLoadPickupCargo。加 helper：

```java
    // webfreight: 停稳在终端 zone 时尝试装本买家的自提锁定货（真人自提/自动自提共用）
    private void tickPickupLoadDetection() {
        if (level().isClientSide || isAutopilotActive()) {
            return; // autopilot 行进中不在此装（自动自提到产地由其到站逻辑触发，见第3期）
        }
        com.monpai.sailboatmod.block.entity.DockBlockEntity dock = dockAtCurrentZone();
        if (dock != null) {
            dock.tryLoadPickupCargo(this);
        }
    }

    /** 当前所在终端 zone 的 dock（无则 null）。 */
    private com.monpai.sailboatmod.block.entity.DockBlockEntity dockAtCurrentZone() {
        // Read 现状：怎么由 position() 找所在 dock（DockRegistry.get(level) 遍历 + isInsideDockZone，或现有 findNearestDock）
        return null; // 占位——按现状实现
    }
```

> **`dockAtCurrentZone` 必须按现状实现**：Read `DockRegistry`/`PostStationRegistry` 怎么由坐标找 dock（grep `findNearest`/`isInsideDockZone` 的现有用法），返回包含本车 position 的 dock。在 `tick()` 服务端段末尾调 `tickPickupLoadDetection()`（低频：可复用现有计数器或每 20 tick）。

- [ ] **Step 4: 马车 tick 同样加**

`CarriageEntity.tick()`（:460）服务端段加对称的 `tickPickupLoadDetection()` + `dockAtCurrentZone()`（马车找驿站 PostStation，用 PostStationRegistry）。按现状实现。

- [ ] **Step 5: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/test/java/com/monpai/sailboatmod/market/PickupWiringContractTest.java && git commit -m "feat(freight): vehicles auto-load locked pickup cargo when parked in terminal zone (real pickup works)"
```

---

## Task 5: 自动自提调度——买家车归属过滤 + 空驶到产地

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java` + `DockBlockEntity.java`
- Test: 扩展 `PickupWiringContractTest.java`

> 自动自提（AUTO_PICKUP，PICKUP_LOCKED）订单：调度时找**买家自己**停靠在产地终端的空闲车，让它（系统驾驶）—— 但若车已在产地 zone，Task4 的进-zone 检测已会装货。本 Task 处理"车在产地终端但 autopilot 未动"的情形：后台调度发现买家车在产地 → 直接走 Task3 装货 + 续发收货仓。**按用户决策"复用进 zone 装货"，自动自提 = 路由买家车到产地（若不在）→ 进 zone 装货 → 续发**。本期范围：买家车**已在产地终端**时自动装货续发；车不在产地的空驶留作增量（先保证车在产地的自动自提走通）。

> 说明：用户选"完整空驶两段"但又选"复用进 zone 装货"——两者结合的最小可行：第3期本 Task 先做"买家车在产地→自动装货续发"（复用 Task3+Task4），"空驶到产地"作为 Task6（路由买家车去产地）。

- [ ] **Step 1: 扩展契约测试**

在 `PickupWiringContractTest.java` 追加：

```java
    @Test
    void autoPickupDispatchUsesBuyerOwnedVehicle() throws Exception {
        String dock = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java"));
        assertTrue(dock.contains("availableBuyerVehiclesForPickup(") || dock.contains("PickupLock.vehicleBelongsToBuyer("),
                "auto-pickup should select the buyer's own vehicles");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest`
Expected: FAIL。

- [ ] **Step 3: 加买家车过滤**

在 `DockBlockEntity` 加：

```java
    /** 本终端 zone 内、属于该买家、空闲空载的载具（自动自提选车用）。 */
    public java.util.List<com.monpai.sailboatmod.entity.TransportEntity> availableBuyerVehiclesForPickup(String buyerUuid) {
        java.util.List<com.monpai.sailboatmod.entity.TransportEntity> out = new java.util.ArrayList<>();
        if (buyerUuid == null || buyerUuid.isBlank()) {
            return out;
        }
        for (com.monpai.sailboatmod.entity.TransportEntity boat : getNearbySailboatsForPickup()) {
            if (com.monpai.sailboatmod.market.PickupLock.vehicleBelongsToBuyer(boat.getOwnerUuid(), buyerUuid)
                    && boat.isTransportAlive() && !boat.isAutopilotActive() && !boat.hasCargo()
                    && isInsideDockZone(boat.transportPosition())) {
                out.add(boat);
            }
        }
        return out;
    }
```

> `getNearbySailboatsForPickup()`：复用现有枚举本终端 zone 内载具的方式（Read `getNearbySailboats`，它需 Player 参数——加一个无 Player 版或传 null 的枚举）。Read 后用现状最简枚举。

- [ ] **Step 4: AUTO_PICKUP 调度分支（车在产地→装货续发）**

> 因 Task4 的进-zone 检测已覆盖"买家车停在产地终端 zone 自动装货"——AUTO_PICKUP 的"车已在产地"情形**已自动工作**（系统不需额外动作，车停那 tick 检测就装了）。但装货后要"自动续发到收货仓"——而 Task3 装货后只 setPendingShipmentManifest，没发车。本 Step 让 `MarketBlockEntity.tryLoadPickupCargo` 对 AUTO_PICKUP 装货后**自动 startAutopilot 到收货仓**。

在 `MarketBlockEntity.tryLoadPickupCargo`（Task3）装货成功后（`boat.setPendingShipmentManifest(manifest)` 之后）加：若装的订单是 AUTO_PICKUP，则路由载具到 targetWarehousePos 并 startAutopilot（复用 SELLER_SHIP 的发车路由逻辑）。Read SELLER_SHIP 怎么设路线发车（`dispatchShipmentPlan` 的路由+startAutopilot 段），抽出"把已装货载具发往目标仓"的逻辑供复用：

```java
        boat.setPendingShipmentManifest(manifest);
        // 自动自提：装完自动发往买家收货仓；真人自提：玩家自理，不自动发
        if (anyAutoPickup(selections)) {
            startAutopilotToBuyerWarehouse(boat, selections);
        }
        return true;
```

> `anyAutoPickup`/`startAutopilotToBuyerWarehouse` 按现状实现（**均在 MarketBlockEntity**，能用全部 private 路由逻辑）：前者查 selections 对应订单 fulfillment 是否含 AUTO_PICKUP；后者复用 `dispatchShipmentPlan` 的"设路线（陆/水按 mode）+ startAutopilot 到 targetWarehousePos"逻辑（Read 发车段抽取，目的地用 `selections` 里订单的 `targetWarehousePos()`）。真人自提（REAL_PICKUP）不调，玩家自理。

- [ ] **Step 5: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/PickupWiringContractTest.java && git commit -m "feat(freight): auto-pickup loads buyer vehicle in zone + auto-departs to receiving warehouse"
```

---

## Task 6: 自动自提空驶——买家车不在产地时路由过去

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java` + `market/logistics/TransportDispatchService.java`
- Test: 扩展 `PickupWiringContractTest.java`

> 后台调度时，对 AUTO_PICKUP 的 PICKUP_LOCKED 订单：若买家车不在产地终端但在别处终端 → 系统驾驶它**空驶到产地终端**（autopilot 路由到产地，不装货）。到产地 zone 后 Task4 的进-zone 检测自动装货 + Task5 续发收货仓。无买家空闲车则排队。

- [ ] **Step 1: 扩展契约测试**

在 `PickupWiringContractTest.java` 追加：

```java
    @Test
    void autoPickupDeadheadsBuyerVehicleToSource() throws Exception {
        String market = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        assertTrue(market.contains("dispatchAutoPickup") || market.contains("deadheadBuyerVehicle"),
                "auto-pickup dispatch should route the buyer vehicle to the source terminal");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest`
Expected: FAIL。

- [ ] **Step 3: AUTO_PICKUP 后台调度分支**

> 第2期 `runBackgroundAutoDispatch` 只发 WAITING_SHIPMENT。AUTO_PICKUP 订单是 PICKUP_LOCKED——不在第2期范围。本 Task 在 `runBackgroundAutoDispatch`（或新增 `runBackgroundAutoPickup`）里加：扫本市场 AUTO_PICKUP 的 PICKUP_LOCKED 订单 → 对每个，找买家空闲车（先产地、否则其它终端）→ 若车不在产地，路由它空驶到产地终端（autopilot 到产地，空车）→ 到产地后 Task4 自动装货续发。无车排队。

在 MarketBlockEntity 加 `dispatchAutoPickup`：

```java
    /** 自动自提后台分支：买家车空驶到产地（不在产地时），到产地后进-zone 自动装货续发。 */
    private void dispatchAutoPickup(MarketSavedData market) {
        if (level == null || level.isClientSide || linkedDockPos == null) {
            return;
        }
        for (PurchaseOrder order : market.getOrdersForBuyer("")) { // 见下：用本市场 AUTO_PICKUP 锁定订单
            // 占位——Read 后用本市场该买家 AUTO_PICKUP+PICKUP_LOCKED 订单的查询
        }
    }
```

> **本 Task 是本计划最重的一步，明确按现状实现**：
> - 取本市场 AUTO_PICKUP+PICKUP_LOCKED 订单（Read MarketSavedData 订单查询）。
> - 找买家空闲车：先产地终端（`availableBuyerVehiclesForPickup`，Task5），有则不用空驶（Task4 已会装）；无则扫买家在其它终端的车（Read 现有跨终端枚举或限定买家最近终端，按 spec"扫描收敛"）。
> - 空驶：让买家车 autopilot 路由到产地终端（复用 setLandTransportTask/setRouteCatalog+startAutopilot 到产地坐标，**空车不 loadCargo**）。
> - 无车：不动（订单留 PICKUP_LOCKED，下轮重试=排队）。
> 在 `runBackgroundAutoDispatch` 末尾加 `dispatchAutoPickup(market)` 调用。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/main/java/com/monpai/sailboatmod/market/logistics/TransportDispatchService.java src/test/java/com/monpai/sailboatmod/market/PickupWiringContractTest.java && git commit -m "feat(freight): auto-pickup deadheads buyer vehicle to source terminal when not present"
```

---

## Task 7: 全量测试 + 构建 + 推送

**Files:** 无（验证任务）

- [ ] **Step 1: 全量测试**

Run: `cd sailboatmod && ./gradlew test`
Expected: PASS。重点：PickupLockTest、PickupWiringContractTest、既有发货/购买/调度测试（PICKUP_LOCKED 不影响 SELLER_SHIP 路径）。

- [ ] **Step 2: 完整构建**

Run: `cd sailboatmod && ./gradlew build`
Expected: BUILD SUCCESSFUL，出 `-reobf.jar`。

- [ ] **Step 3: 提交剩余 + 推送（经代理 7897 + gh token）**

```bash
cd sailboatmod && git add -A && git commit -m "test(freight): phase 4+3 pickup verified" || echo "nothing to commit"
GH_TOKEN=$(gh auth token); REPO_PATH=$(git remote get-url origin | sed -E 's#https://([^/]*@)?github.com/##; s#\.git$##'); BRANCH=$(git rev-parse --abbrev-ref HEAD); HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 git push "https://x-access-token:${GH_TOKEN}@github.com/${REPO_PATH}.git" "$BRANCH"
```

---

## 集成实测（手动，单测覆盖不到仓库/载具运行时）

1. **真人自提**：A 买货选真人自提 → 货在产地仓为 A 锁定（订单 PICKUP_LOCKED，不被后台发）→ A 开自己的车进产地终端 zone 停稳 → 自动装锁定货 → A 自行驾驶离开（货在车上，玩家自理）。
2. **真人自提装不下**：A 车容量 < 锁货量 → 只装满车的量，剩余留仓库继续锁定。
3. **自动自提（车在产地）**：A 买货选自动自提，A 的空车已停在产地终端 → 系统自动装货 + 发往 A 收货仓。
4. **自动自提（车不在产地）**：A 车停在别的终端 → 系统驾驶空驶到产地 → 进 zone 装货 → 送 A 收货仓。
5. **自动自提无车**：A 无空闲车 → 订单排队（PICKUP_LOCKED），A 停一辆空车 → 下轮自动处理。
6. **不误装他人货**：B 的车进 zone 不装 A 的锁定货。
7. 马车（驿站）/帆船（港口）各验真人+自动自提。
8. SELLER_SHIP 不回归（仍后台自动发货）。

---

## Self-Review（对照 spec 第3/4期）

**覆盖**：真人自提货锁定（Task2 PICKUP_LOCKED）+ 进 zone 自动装货（Task3 核心 + Task4 载具触发）✓；装不下留仓库（Task1 loadableAmount + Task3）✓；只装本买家货（Task3 vehicleBelongsToBuyer）✓；自动自提买家车（Task5 归属过滤）+ 装货续发收货仓（Task5）+ 空驶到产地（Task6）✓；无车排队（Task6 不动=留 PICKUP_LOCKED）✓；船马车统一（Task3 核心在 DockBlockEntity，两载具 tick 都调）✓；验证（Task7）✓。

**类型/签名一致**：`PickupLock.loadableAmount/isPickupOrder/vehicleBelongsToBuyer/STATUS_LOCKED`、`tryLoadPickupCargo(boat)`、`availableBuyerVehiclesForPickup(buyerUuid)`、`dispatchAutoPickup`、`tickPickupLoadDetection`/`dockAtCurrentZone` 各 Task 一致。

**无占位符（已知例外，如实标注）**：Task3/4/5/6 含较多"Read 现状后落实 helper"——因仓库取货归属、载具找所在 dock、跨终端买家车枚举、空驶路由发车这些**强依赖现状 API**，无法在不读代码时写死。每处都给了骨架 + 明确"Read 哪个现状 + 用它实现"。这是本计划诚实的复杂度：第3/4期是运行时重逻辑，纯逻辑部分（Task1）已单测，接线/执行部分靠 Read+源码断言+集成实测。

**范围**：本计划只后端真人自提+自动自提。UI（第5期）独立。空驶按"路由到产地+复用进zone装货"（用户决策），非独立两段路由模型。

**风险提示**：~~Task3（仓库提锁定货的归属）~~ **已在执行中核实并消除**——货上架时已扣出仓库，自提装货与 SELLER_SHIP 同走"按 listing 模板 splitCargo 生成"，不碰仓库，Task3 降级为纯复用。剩余最易卡处为 **Task6（空驶路由 + 跨终端找买家车）**——执行时若 Read 发现现状 API 不支持，应停下与用户确认，而非硬凑。另：Task3 的核心已从 DockBlockEntity 改放 **MarketBlockEntity**（因 splitCargo/splitOrderForShipment 是其 private），DockBlockEntity 仅进-zone 委托。
