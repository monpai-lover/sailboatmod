# 点对点货运 第2期：后台调度 + 排队 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让卖家发货/自动自提订单在运力足够时由系统后台自动完成发货，运力不足时自动排队——无需玩家手动点发货。

**Architecture:** 现状自动发货链路（`tryAutoDispatchOrders` → `resolveDispatchTerminalPlan` → `dispatchShipmentPlan`）已完整：能选车、路由、装车、发车、改状态、建 Trace，且船马车经 AUTO 模式 + TransportEntity 接口已统一。**第2期唯一缺的是"后台定时触发"**——新增 `TransportDispatchService` 在 server tick（每15秒）遍历已加载市场、对待发订单调现成的 `tryAutoDispatchOrders(..., AUTO)`。排队天然成立：无车时该方法返回 false、订单留 WAITING_SHIPMENT、下轮重试。目的地已正确（第1期建单时 targetDockPos=买家收货仓）。

**Tech Stack:** Java 17、Minecraft Forge 1.20.1、Forge `TickEvent.ServerTickEvent`、JUnit 5。测试：纯函数行为测试（调度门控逻辑：哪些订单该后台发）+ 源码文本断言（tick 钩子接线、Service 调现成链路）。

---

## 背景与约束（实现前必读）

**现状已具备（复用，不重写）**：
- `MarketBlockEntity.tryAutoDispatchOrders(String shipperUuid, String shipperName, @Nullable Player player, BlockPos sourceDockPos, TransportTerminalKind terminalKind)`（:1032）——自动发货入口，AUTO 模式自动选最优终端+载具，**无车返回 false 且不改订单状态（天然排队）**。
- `tryDispatchWaitingOrdersAuto`（:1101）/ `resolveDispatchTerminalPlan`（:1183）/ `dispatchShipmentPlan`（:1790，装车+发车+改 status 为 "IN_TRANSIT"+建 Trace）。
- 船马车统一：`terminalsForTown(townId, terminalKind)`（:1644）、`TransportEntity` 接口、`DockBlockEntity.supportsTransportEntity`（港口只船、驿站只马车）、`availableDispatchBoats`/`isBoatAvailableForDispatch` 状态过滤。
- 目的地：第1期建单时 `targetDockPos == targetWarehousePos == 买家收货仓`；发货分组（:1073/1111）读 `order.targetDockPos()`——**已指向买家收货仓，无需改**。
- 订单状态：WAITING_SHIPMENT（待发）→ dispatchShipmentPlan 改 IN_TRANSIT。
- tick 钩子范式：`ServerEvents.onServerTick`（:65-96）低频计数器 `if (++counter >= N) { counter = 0; ... }`；`ServerLifecycleHooks.getCurrentServer()`。
- 订单查询：`MarketSavedData.getOpenOrdersForSourceDock(BlockPos sourceDockPos)`（:142，status==WAITING_SHIPMENT）。
- `FulfillmentMode`（第1期）：`needsVehicleDispatch()` = 非 REAL_PICKUP（②③需车进调度，①真人自提不进）。

**spec 缺口（第2期新建）**：后台定时触发——`TransportDispatchService` + tick 钩子。

**本期范围（YAGNI）**：
- **不新建 `TransportMode` 枚举**——现状 AUTO 模式 + TransportEntity 已统一船马车，新建是重复抽象。
- **不新建队列服务**——排队靠订单状态机已实现，队列展示是第5期 UI。
- **不改目的地解析**——第1期已让 targetDockPos=买家收货仓。
- 真人自提（REAL_PICKUP）不进后台调度（第4期处理）。

**关键设计：后台调度只处理"需车且非真人自提"的订单。** 后台对一个市场调 `tryAutoDispatchOrders` 前，先确认该市场有 WAITING_SHIPMENT 且 `needsVehicleDispatch()` 的订单——避免对纯真人自提订单空跑。`tryAutoDispatchOrders` 现状不区分 fulfillment，第2期加一道门控：只发 needsVehicleDispatch 的订单。

**性能约束**：遍历所有已加载市场每 tick 太贵——**每15秒（300 tick）一次**，且只扫已加载 level 的 block entities。

**测试命令**：单类 `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.DispatchEligibilityTest`；全量 `./gradlew test`。

> 注：`TransportDispatchService` 的遍历/发车依赖 MC 运行时（ServerLevel/BlockEntity），无法纯单测——用源码断言验证接线 + 把"订单是否该后台发"抽成纯函数单测。真实后台发货留集成实测。

---

## File Structure

**新建（生产）：**
- `market/logistics/TransportDispatchService.java` — 后台调度：遍历已加载市场、门控筛选需车订单、调现成 tryAutoDispatchOrders

**修改（生产）：**
- `block/entity/MarketBlockEntity.java` — 加一个供后台调用的入口 `runAutoDispatchForBackground()`（封装"扫待发需车订单 → 调 tryAutoDispatchOrders(AUTO)"），并加调度门控（只发 needsVehicleDispatch 订单）
- `ServerEvents.java` — onServerTick 加每15秒的调度计数器，调 `TransportDispatchService`

**新建（测试）：**
- `src/test/java/com/monpai/sailboatmod/market/DispatchEligibilityTest.java`（纯函数：订单是否该后台发）
- `src/test/java/com/monpai/sailboatmod/market/logistics/BackgroundDispatchWiringContractTest.java`（源码断言：Service + tick 接线）

---

## Task 1: 调度资格纯函数（哪些订单后台发）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/logistics/TransportDispatchService.java`（本任务先建类 + 纯函数）
- Test: `src/test/java/com/monpai/sailboatmod/market/DispatchEligibilityTest.java`

> 把"一个订单是否该被后台自动发车"抽成纯函数：status==WAITING_SHIPMENT 且 fulfillment 需车（②AUTO_PICKUP/③SELLER_SHIP）。真人自提(①)和已发/已交付的订单不发。

- [ ] **Step 1: 写失败的纯函数测试**

新建 `src/test/java/com/monpai/sailboatmod/market/DispatchEligibilityTest.java`：

```java
package com.monpai.sailboatmod.market;

import com.monpai.sailboatmod.market.logistics.TransportDispatchService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchEligibilityTest {
    @Test
    void sellerShipWaitingOrderIsBackgroundDispatchable() {
        assertTrue(TransportDispatchService.isBackgroundDispatchable("WAITING_SHIPMENT", "SELLER_SHIP"));
    }

    @Test
    void autoPickupWaitingOrderIsBackgroundDispatchable() {
        assertTrue(TransportDispatchService.isBackgroundDispatchable("WAITING_SHIPMENT", "AUTO_PICKUP"));
    }

    @Test
    void realPickupIsNotBackgroundDispatchable() {
        assertFalse(TransportDispatchService.isBackgroundDispatchable("WAITING_SHIPMENT", "REAL_PICKUP"));
    }

    @Test
    void nonWaitingStatusIsNotDispatchable() {
        assertFalse(TransportDispatchService.isBackgroundDispatchable("IN_TRANSIT", "SELLER_SHIP"));
        assertFalse(TransportDispatchService.isBackgroundDispatchable("CLAIMED", "SELLER_SHIP"));
        assertFalse(TransportDispatchService.isBackgroundDispatchable("PAID", "SELLER_SHIP"));
    }

    @Test
    void blankOrNullSafe() {
        assertFalse(TransportDispatchService.isBackgroundDispatchable(null, "SELLER_SHIP"));
        assertFalse(TransportDispatchService.isBackgroundDispatchable("WAITING_SHIPMENT", null));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.DispatchEligibilityTest`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 建 TransportDispatchService + 纯函数**

新建 `src/main/java/com/monpai/sailboatmod/market/logistics/TransportDispatchService.java`：

```java
package com.monpai.sailboatmod.market.logistics;

import com.monpai.sailboatmod.market.FulfillmentMode;

/** 后台运输调度：定时让需车订单（卖家发货/自动自提）自动发车，无车排队。 */
public final class TransportDispatchService {
    /** 待发状态常量（与 PurchaseOrder 一致）。 */
    public static final String STATUS_WAITING = "WAITING_SHIPMENT";

    private TransportDispatchService() {
    }

    /** 一个订单是否该被后台自动发车：待发状态 + fulfillment 需车（②③，非真人自提）。 */
    public static boolean isBackgroundDispatchable(String status, String fulfillment) {
        if (status == null || fulfillment == null) {
            return false;
        }
        if (!STATUS_WAITING.equals(status.trim())) {
            return false;
        }
        return FulfillmentMode.fromString(fulfillment).needsVehicleDispatch();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.DispatchEligibilityTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/logistics/TransportDispatchService.java src/test/java/com/monpai/sailboatmod/market/DispatchEligibilityTest.java && git commit -m "feat(freight): background dispatch eligibility (waiting + needs-vehicle)"
```

---

## Task 2: 市场后台发货入口（门控 + 调现成链路）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/logistics/BackgroundDispatchWiringContractTest.java`

> 在 MarketBlockEntity 加 `runBackgroundAutoDispatch()`：扫本市场绑定仓库的 WAITING_SHIPMENT 订单，若存在 isBackgroundDispatchable 的订单，则调现成 `tryAutoDispatchOrders(..., AUTO)` 发车（卖家身份用订单卖家/系统）。无车则现成链路返回 false、订单留队列。

- [ ] **Step 1: 写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/logistics/BackgroundDispatchWiringContractTest.java`：

```java
package com.monpai.sailboatmod.market.logistics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundDispatchWiringContractTest {
    @Test
    void marketHasBackgroundAutoDispatchEntry() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        assertTrue(src.contains("public void runBackgroundAutoDispatch()"),
                "market should expose a background auto-dispatch entry");
        assertTrue(src.contains("TransportDispatchService.isBackgroundDispatchable("),
                "background dispatch should gate on dispatch eligibility");
        assertTrue(src.contains("tryAutoDispatchOrders("),
                "background dispatch should reuse the existing auto-dispatch chain");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.logistics.BackgroundDispatchWiringContractTest`
Expected: FAIL。

- [ ] **Step 3: 加 runBackgroundAutoDispatch**

先 Read `MarketBlockEntity.java` 确认：`getLinkedWarehouse()`、`linkedDockPos`、`MarketSavedData.getOpenOrdersForSourceDock` 的实际取订单方式、`tryAutoDispatchOrders` 签名（shipperUuid/shipperName/player/sourceDockPos/terminalKind）、是否已 import `TransportDispatchService`/`FulfillmentMode`/`TransportTerminalKind`。在类内新增：

```java
    /** 后台自动发货入口（由 TransportDispatchService 定时调用）：发本市场所有需车的待发订单。 */
    public void runBackgroundAutoDispatch() {
        if (level == null || level.isClientSide || linkedDockPos == null) {
            return;
        }
        TownWarehouseBlockEntity warehouse = getLinkedWarehouse();
        if (warehouse == null) {
            return;
        }
        MarketSavedData market = MarketSavedData.get(level);
        // 是否存在"需车的待发订单"——没有就不空跑调度
        boolean hasDispatchable = false;
        for (PurchaseOrder order : market.getOpenOrdersForSourceDock(linkedDockPos)) {
            if (TransportDispatchService.isBackgroundDispatchable(order.status(), order.fulfillment())) {
                hasDispatchable = true;
                break;
            }
        }
        if (!hasDispatchable) {
            return;
        }
        // 复用现成自动发货链路（AUTO 模式自动选港口/驿站、船/马车统一）；无车则内部 return false、订单留队列。
        tryAutoDispatchOrders("", "", null, linkedDockPos, TransportTerminalKind.AUTO);
    }
```

> 关键确认（Read 后定）：
> - `getOpenOrdersForSourceDock(linkedDockPos)` 是否返回本市场待发订单（子代理称按 status==WAITING_SHIPMENT 过滤）。若实际方法名/语义不同，用实际的"列本市场待发 PurchaseOrder"方法。
> - `tryAutoDispatchOrders` 现状 player 参数可为 null（后台无玩家）——确认其内部对 player==null 安全（载具过滤 `isBoatAvailableForDispatch(boat, null)` 现状允许 null player → 走"非玩家拥有也可派"分支或可租）。若 player==null 导致选不到车，Step 内调整为传订单卖家身份或放宽过滤——**先 Read `isBoatAvailableForDispatch`/`availableDispatchBoats` 对 null player 的处理再定**。
> - shipperUuid/shipperName 传 "" 表示系统发货；若 tryAutoDispatchOrders 依赖非空 shipper，传订单的 sellerUuid。

- [ ] **Step 4: 加调度门控（tryAutoDispatchOrders 只发需车订单）**

为避免后台把"真人自提"订单也发了，先 Read `tryDispatchWaitingOrdersAuto`（:1101）的分组循环（:1106-1111，`byTargetWarehouse` 收集订单处）。在收集订单的过滤里加 fulfillment 门控——只收 `isBackgroundDispatchable` 的订单（或 needsVehicleDispatch）：

在 `byTargetWarehouse.computeIfAbsent(...)` 之前的循环条件里加（与现有 `if (order.sourceDockPos().equals(order.targetDockPos())) continue;` 并列）：

```java
            if (!FulfillmentMode.fromString(order.fulfillment()).needsVehicleDispatch()) {
                continue; // 真人自提不进后台调度
            }
```

> 这一道门控同时保护手动 `dispatchOrder` 路径不误发真人自提货——合理。Read 确认 `tryDispatchWaitingOrders`（指定终端版 :1062）的同样分组处（:1108-1111）也加同样门控。

- [ ] **Step 5: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.logistics.BackgroundDispatchWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/logistics/BackgroundDispatchWiringContractTest.java && git commit -m "feat(freight): market background auto-dispatch entry + needs-vehicle gate"
```

---

## Task 3: 遍历已加载市场（TransportDispatchService.globalTick）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/logistics/TransportDispatchService.java`
- Test: 扩展 `BackgroundDispatchWiringContractTest.java`

> globalTick：遍历 server 各 level 的已加载 block entities，对每个 MarketBlockEntity 调 runBackgroundAutoDispatch。源码断言验证（运行时遍历不可纯单测）。

- [ ] **Step 1: 扩展契约测试**

在 `BackgroundDispatchWiringContractTest.java` 追加：

```java
    @Test
    void serviceHasGlobalTickIteratingMarkets() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/logistics/TransportDispatchService.java"));
        assertTrue(src.contains("public static void globalTick("),
                "service should expose a globalTick entry");
        assertTrue(src.contains("runBackgroundAutoDispatch()"),
                "globalTick should drive each market's background dispatch");
        assertTrue(src.contains("getAllLevels()") || src.contains("MarketBlockEntity"),
                "globalTick should iterate loaded markets");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.logistics.BackgroundDispatchWiringContractTest`
Expected: FAIL。

- [ ] **Step 3: 加 globalTick**

先 Read 一个现有"遍历 loaded block entities"的范例（grep `getAllBlockEntities`/`blockEntityTickers`/`getLoadedChunks` 在项目里怎么用；若无现成范例，用 `ServerLevel` 的 chunk source 遍历）。在 TransportDispatchService 加：

```java
    /** 后台调度总入口：遍历各 level 已加载市场，逐个发其待发需车订单。 */
    public static void globalTick(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.level.block.entity.BlockEntity be : collectLoadedMarkets(level)) {
                if (be instanceof com.monpai.sailboatmod.block.entity.MarketBlockEntity market) {
                    market.runBackgroundAutoDispatch();
                }
            }
        }
    }

    /** 收集某 level 已加载的 MarketBlockEntity。 */
    private static java.util.List<net.minecraft.world.level.block.entity.BlockEntity> collectLoadedMarkets(
            net.minecraft.server.level.ServerLevel level) {
        java.util.List<net.minecraft.world.level.block.entity.BlockEntity> out = new java.util.ArrayList<>();
        // 遍历已加载 chunk 的 block entities，挑出 MarketBlockEntity
        for (net.minecraft.world.level.chunk.LevelChunk chunk : loadedChunks(level)) {
            for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                if (be instanceof com.monpai.sailboatmod.block.entity.MarketBlockEntity) {
                    out.add(be);
                }
            }
        }
        return out;
    }
```

> **`loadedChunks(level)` 的实现按现状定**：Forge 1.20.1 `ServerLevel` 取已加载 chunk 的方式（如 `level.getChunkSource().chunkMap` 或 `ServerChunkCache`）需 Read 项目里现有用法或 Forge API 确认。若项目已有"遍历市场/dock 的注册表"（如 DockRegistry 有按 level 的位置集合），**优先用注册表**：grep 是否有 `MarketRegistry`/市场位置集合——若市场也像 dock 一样注册了位置，直接遍历位置 + `level.getBlockEntity(pos)`，比扫 chunk 高效。**本步先 Read 确认最简可靠的遍历方式再写**；若只有逐 chunk，用上面的 chunk 遍历。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.logistics.BackgroundDispatchWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/logistics/TransportDispatchService.java src/test/java/com/monpai/sailboatmod/market/logistics/BackgroundDispatchWiringContractTest.java && git commit -m "feat(freight): TransportDispatchService.globalTick iterates loaded markets"
```

---

## Task 4: tick 钩子（每15秒触发）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/ServerEvents.java`
- Test: 扩展 `BackgroundDispatchWiringContractTest.java`

- [ ] **Step 1: 扩展契约测试**

在 `BackgroundDispatchWiringContractTest.java` 追加：

```java
    @Test
    void serverTickDrivesDispatchEvery15Seconds() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/ServerEvents.java"));
        assertTrue(src.contains("TransportDispatchService.globalTick("),
                "server tick should drive background dispatch");
        assertTrue(src.contains("dispatchTickCounter"),
                "dispatch should run on its own low-frequency counter");
        assertTrue(src.contains(">= 300"),
                "dispatch counter should fire every 15s (300 ticks)");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.logistics.BackgroundDispatchWiringContractTest`
Expected: FAIL。

- [ ] **Step 3: 加调度计数器**

先 Read `ServerEvents.java`（:65-96）确认计数器声明区与 onServerTick 内 `server != null` 块的结构。在计数器声明区加：

```java
    private static int dispatchTickCounter;
```

在 onServerTick 的 `if (server != null) { ... }` 块内（与 loanTickCounter/cleanupTickCounter 并列）加：

```java
            if (++dispatchTickCounter >= 300) { // 15s @20tps
                dispatchTickCounter = 0;
                com.monpai.sailboatmod.market.logistics.TransportDispatchService.globalTick(server);
            }
```

> 与现有 `if (++loanTickCounter >= 1200)` 同模式。确认 import 或用全限定名。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.logistics.BackgroundDispatchWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/ServerEvents.java src/test/java/com/monpai/sailboatmod/market/logistics/BackgroundDispatchWiringContractTest.java && git commit -m "feat(freight): drive background dispatch from server tick every 15s"
```

---

## Task 5: 全量测试 + 构建 + 推送

**Files:** 无（验证任务）

- [ ] **Step 1: 全量测试**

Run: `cd sailboatmod && ./gradlew test`
Expected: PASS。重点：DispatchEligibilityTest、BackgroundDispatchWiringContractTest、既有 dispatch/购买测试（fulfillment 门控不应破坏既有手动发货——既有订单 fulfillment 默认 SELLER_SHIP，needsVehicleDispatch=true，仍可发）。

- [ ] **Step 2: 完整构建**

Run: `cd sailboatmod && ./gradlew build`
Expected: BUILD SUCCESSFUL，`build/libs/` 出 `-reobf.jar`。

- [ ] **Step 3: 提交剩余 + 推送（经代理 7897 + gh token）**

```bash
cd sailboatmod && git add -A && git commit -m "test(freight): phase-2 background dispatch verified" || echo "nothing to commit"
GH_TOKEN=$(gh auth token); REPO_PATH=$(git remote get-url origin | sed -E 's#https://([^/]*@)?github.com/##; s#\.git$##'); BRANCH=$(git rev-parse --abbrev-ref HEAD); HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 git push "https://x-access-token:${GH_TOKEN}@github.com/${REPO_PATH}.git" "$BRANCH"
```

---

## 集成实测（手动，单测覆盖不到后台 tick/发车）

1. A 在 B 店买货选③卖家发货 → **不点发货**，等≤15秒 → 系统自动发车，货运向 A 收货仓。
2. 产地无空闲车 → 订单留"待发"，停一辆空车在产地终端 → 下一轮（≤15秒）自动发车（排队验证）。
3. 选①真人自提的订单 → 后台**不**自动发车（门控验证）。
4. 马车（陆路驿站）与帆船（水路港口）各验一遍自动发车（船马车统一）。
5. 多市场同时有待发订单 → 各自后台发（globalTick 遍历）。
6. 性能：开服空跑，确认每15秒一次调度无明显卡顿。

---

## Self-Review（对照 spec 第2期）

**覆盖**：后台定时调度（Task3 globalTick + Task4 tick 钩子）✓；卖家发货/自动自提自动发车（Task2 复用 tryAutoDispatchOrders）✓；排队（无车 return false + 订单留 WAITING_SHIPMENT，现成行为）✓；船马车统一（复用 AUTO 模式 + terminalsForTown，未新建抽象）✓；真人自提不进调度（Task1 资格 + Task2 门控）✓；验证（Task5）✓。

**类型/签名一致**：`isBackgroundDispatchable(status, fulfillment)`、`runBackgroundAutoDispatch()`、`globalTick(server)`、`dispatchTickCounter`/`>= 300`、`FulfillmentMode.needsVehicleDispatch` 各 Task 一致。

**无占位符**：每步含完整代码；Task2/3 因依赖现状（getOpenOrdersForSourceDock 语义、tryAutoDispatchOrders 对 null player 的处理、loaded chunk/市场遍历方式），明确要求"先 Read 确认实际签名/最简遍历再写"，并给确切方法体与断言。

**已知不确定（如实标注）**：(1) `getOpenOrdersForSourceDock` 实际取本市场待发订单的语义以 Read 为准；(2) `tryAutoDispatchOrders` 对 player==null 能否选到车——Read `isBoatAvailableForDispatch` 后定（可能需传卖家身份）；(3) 遍历已加载市场的最简方式（注册表 vs 逐 chunk）以 Read 为准，优先注册表。

**范围**：本期只后台调度 + 排队。自动自提的"买家车去产地"细节（第3期）、真人自提（第4期）、UI（第5期）独立。不新建 TransportMode 枚举/队列服务（YAGNI，复用现状）。
