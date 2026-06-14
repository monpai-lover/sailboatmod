# 马车/帆船到站卸货修复 + 显式卸货开关 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复马车到站不卸货的 bug，并给马车/帆船补齐统一的"到站是否卸货"显式开关——商单/调度发车始终卸货，手动发车按开关，默认卸货。

**Architecture:** 帆船已有 `autopilotAllowNonOrderAutoUnload` 机制（hasOrder→强制卸；非订单→看开关），但默认 false 且马车完全没接上（`setAllowNonOrderAutoUnload` 是空方法、`finishAutopilot` 无 allowUnload 判断）。本计划：(1) 马车补齐与帆船对称的开关字段+NBT+卸货判断；(2) 修马车 `selectCargoForEntries` 因 manifest 空规格抽不出货导致的不卸 bug（兜底全卸）；(3) 两端默认改为卸货；(4) 载具界面加显式开关 UI（坐在驿站/码头内可切换）。

**Tech Stack:** Java 17、Minecraft Forge 1.20.1、Forge SimpleChannel packet、NBT、JUnit 5。测试两种风格：(a) 纯函数行为测试（如 `CarriageEntityMovementTest` 的 `*ForTest` 钩子）；(b) 源码文本断言（`Files.readString` + `assertTrue(contains)`），用于约束 NBT/packet/UI 接线形态。

---

## 背景与约束（实现前必读）

**两个不同问题：**
1. **马车到站不卸货（真 bug）**：多站连运重构（commit `472750f`）后，`finishAutopilot`（`CarriageEntity:2248`）用 `selectCargoForEntries(pool, split.deliverHere())` 按 manifest 条目的 `itemStack`+`quantity` 精确抽货。但 `setPendingMarketDelivery`（`CarriageEntity:1578-1590`）设的 manifest 条目是 `itemStack=ItemStack.EMPTY, quantity=0` → `selectCargoForEntries`（`DockBlockEntity:1183` guard `itemStack().isEmpty() || quantity()<=0` → continue）抽不出任何货 → `deliverCargo` 空 → 货被 `loadCargo(pool)` 全装回车 → 到站不卸。旧逻辑"有货全卸"不暴露此问题。
2. **卸货开关缺失/不一致**：
   - 帆船有 `autopilotAllowNonOrderAutoUnload`（`SailboatEntity:199`，默认 **false**）、NBT（:864/:943）、setter（:1221）、卸货判断（:2089-2090 `boolean hasOrder=hasTransportOrder(manifest); boolean allowUnload=hasOrder||autopilotAllowNonOrderAutoUnload;`）、`hasTransportOrder`（:2163，manifest 有 purchaseOrderId/shippingOrderId 即商单）。
   - 马车 `setAllowNonOrderAutoUnload`（`CarriageEntity:1620-1621`）是**空方法体**，无字段、无 NBT、`finishAutopilot` 里**没有 allowUnload 判断**。

**用户决策（已确认）：**
- 到站**默认卸货**（修好后默认能卸）。
- **商单/调度发车 → 始终卸货**（`hasTransportOrder`==true）。
- **手动发车 → 看显式开关**（`unloadOnArrival`，默认 true）。
- 马车 + 帆船**都要**这个显式开关。
- UI：玩家坐在驿站/码头内操作载具时可切换。

**统一命名（本计划采用，避免帆船旧名歧义）：**
- 对外开关语义统一叫 **`unloadOnArrival`**（true=到站卸货，默认 true）。
- 帆船现有字段 `autopilotAllowNonOrderAutoUnload` 语义相同（true=非订单也卸）。本计划**不改帆船字段名**（避免大改 NBT 键），而是：(a) 把帆船默认值/setter 接到新 UI；(b) 马车新增 `unloadOnArrival` 字段，语义 = "非订单时是否自动卸"。两者语义对齐：`allowUnload = hasOrder || <该开关>`。

**已核实真实接口：**
- 马车卸货：`CarriageEntity.finishAutopilot()`（:2248-2287）；`unloadAllCargo()`（:1667，清空 inventory 返回全部）；`loadCargo(List<ItemStack>)`；`getPendingShipmentManifest()`；`setPendingShipmentManifest(List)`（:1598）。
- 帆船卸货：`SailboatEntity.finishAutopilotAndUnloadAtDestination()`（:2079-2132）；`hasTransportOrder(manifest)`（:2163）；`setAllowNonOrderAutoUnload(boolean)`（:1221）；`autopilotAllowNonOrderAutoUnload`（:199）。
- 拆分：`DockBlockEntity.splitManifestByDestination(level, arrivalPos, manifest)`→`ManifestSplit{deliverHere,keepOnboard}`（:1129）；`selectCargoForEntries(pool, entries)`（:1177）；`ManifestSplit` 是 record。
- 马车 task kind：`CarriageEntity.TransportTaskKind {NONE,DISPATCH,MARKET_ORDER,RETURN,RECALL}`（:84-90）；当前任务字段 `transportTaskKind`（:271）。
- 开关 setter 接口：`setAllowNonOrderAutoUnload(boolean)` 两端都有（马车空实现、帆船实装）。
- packet 模式：`ControlAutopilotPacket`（帆船 autopilot 控制）；`AutopilotControlAction {START,PAUSE,RESUME,STOP,NEXT_ROUTE,PREV_ROUTE}`（:2618）。
- UI：`CarriageInfoScreen`、`SailboatInfoScreen`（载具信息界面）；`PostStationScreen`、`DockScreen`（终端界面）。
- 测试钩子风格：`CarriageEntity` 有大量 `static ... ForTest(...)` 暴露纯逻辑供单测。

**测试命令：**
- 单类：`cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.CarriageUnloadDecisionTest`
- 全量：`cd sailboatmod && ./gradlew test`

> 注意：`finishAutopilot` 整体依赖 MC 运行时（BlockEntity/Level），无法纯单测。因此把**卸货决策**抽成纯函数 `shouldUnloadNonOrderCargo(...)` 并用 `*ForTest` 钩子单测；卸货的"兜底全卸"用纯函数 `resolveDeliverCargo(...)` 单测；接线（NBT/packet/UI/默认值）用源码断言。真实到站行为留集成实测。

---

## File Structure（先锁定边界）

**修改（生产代码）：**
- `entity/CarriageEntity.java` — 新增 `unloadOnArrival` 字段（默认 true）+ NBT save/load + 实装 `setAllowNonOrderAutoUnload`；新增纯函数 `shouldUnloadNonOrderCargo`/`resolveDeliverCargo` + ForTest 钩子；`finishAutopilot` 接入卸货决策 + 兜底全卸
- `entity/SailboatEntity.java` — `autopilotAllowNonOrderAutoUnload` 默认改 true（:199）；卸货端同样加兜底全卸（与马车对称，防同类 manifest 空规格 bug）
- `entity/TransportEntity.java`（若 `setAllowNonOrderAutoUnload` 在此声明）— 确认接口签名（不改）
- `network/packet/SetUnloadOnArrivalPacket.java`（新建）— 客户端→服务端切换开关
- `network/ModNetwork.java` — 注册新 packet（末尾 `packetId++`）
- `client/screen/CarriageInfoScreen.java` + `client/screen/SailboatInfoScreen.java` — 加"到站卸货"开关按钮
- `resources/assets/sailboatmod/lang/zh_cn.json` + `en_us.json` — 开关文案

**新建（测试）：**
- `src/test/java/com/monpai/sailboatmod/entity/CarriageUnloadDecisionTest.java`（纯函数：卸货决策 + 兜底全卸）
- `src/test/java/com/monpai/sailboatmod/entity/UnloadOnArrivalWiringContractTest.java`（源码断言：NBT/默认值/packet/UI 接线）

---

## Task 1: 马车卸货决策纯函数（hasOrder || 开关）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Test: `src/test/java/com/monpai/sailboatmod/entity/CarriageUnloadDecisionTest.java`

> 把"是否卸货"抽成纯函数：商单（hasOrder）始终卸；非订单看开关。与帆船 `allowUnload = hasOrder || autopilotAllowNonOrderAutoUnload` 同构。

- [ ] **Step 1: 写失败的纯函数测试**

新建 `src/test/java/com/monpai/sailboatmod/entity/CarriageUnloadDecisionTest.java`：

```java
package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageUnloadDecisionTest {
    @Test
    void orderDrivenAlwaysUnloadsRegardlessOfSwitch() {
        // 商单/调度发车：始终卸货，开关关也卸
        assertTrue(CarriageEntity.shouldUnloadAtArrivalForTest(true, false),
                "order-driven delivery must unload even when the manual switch is off");
        assertTrue(CarriageEntity.shouldUnloadAtArrivalForTest(true, true));
    }

    @Test
    void manualFollowsTheSwitch() {
        // 手动发车（无订单）：看开关
        assertTrue(CarriageEntity.shouldUnloadAtArrivalForTest(false, true),
                "manual trip unloads when the switch is on");
        assertFalse(CarriageEntity.shouldUnloadAtArrivalForTest(false, false),
                "manual trip keeps cargo when the switch is off");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.CarriageUnloadDecisionTest`
Expected: FAIL —— `shouldUnloadAtArrivalForTest` 不存在（编译失败）。

- [ ] **Step 3: 加纯函数 + ForTest 钩子**

在 `CarriageEntity.java` 类内（靠近其他静态逻辑方法，如 `hasCargo` 之后）新增：

```java
    /** 卸货决策：商单（hasOrder）始终卸；非订单看 unloadOnArrival 开关。与帆船 allowUnload 同构。 */
    private static boolean shouldUnloadAtArrival(boolean hasOrder, boolean unloadOnArrival) {
        return hasOrder || unloadOnArrival;
    }

    static boolean shouldUnloadAtArrivalForTest(boolean hasOrder, boolean unloadOnArrival) {
        return shouldUnloadAtArrival(hasOrder, unloadOnArrival);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.CarriageUnloadDecisionTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/test/java/com/monpai/sailboatmod/entity/CarriageUnloadDecisionTest.java && git commit -m "feat(transport): carriage unload decision = hasOrder || unloadOnArrival switch"
```

---

## Task 2: 兜底全卸纯函数（修 manifest 空规格抽不出货）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java`
- Test: 扩展 `src/test/java/com/monpai/sailboatmod/entity/CarriageUnloadDecisionTest.java`

> 根因：manifest 条目 itemStack 为空时 `selectCargoForEntries` 抽不出货。修法：新增 `resolveDeliverCargo`——当本站该投递的条目里存在"无物品规格"（itemStack 空/quantity 0）的条目时，回退为"把池里的货全投递"；否则按精确抽取（保多站连运）。放在 DockBlockEntity（与 splitManifestByDestination/selectCargoForEntries 同处，可纯单测）。

- [ ] **Step 1: 扩展测试（覆盖兜底与精确两种）**

在 `CarriageUnloadDecisionTest.java` 追加（需要的 import 在文件顶部补 `java.util.List`、`net.minecraft.world.item.ItemStack`、`com.monpai.sailboatmod.market.ShipmentManifestEntry`、`com.monpai.sailboatmod.block.entity.DockBlockEntity`、`net.minecraft.world.item.Items`、`net.minecraft.SharedConstants`、`net.minecraft.server.Bootstrap`、`org.junit.jupiter.api.BeforeAll`）：

```java
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void deliverCargoFallsBackToWholePoolWhenManifestEntryHasNoItemSpec() {
        // manifest 条目无物品规格（itemStack 空/quantity 0，setPendingMarketDelivery 的情况）
        java.util.List<com.monpai.sailboatmod.market.ShipmentManifestEntry> deliverHere = java.util.List.of(
                new com.monpai.sailboatmod.market.ShipmentManifestEntry(
                        "", net.minecraft.world.item.ItemStack.EMPTY, "po-1", "so-1", "uuid", "buyer", 0));
        java.util.List<net.minecraft.world.item.ItemStack> pool = new java.util.ArrayList<>(java.util.List.of(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 64)));

        java.util.List<net.minecraft.world.item.ItemStack> deliver =
                com.monpai.sailboatmod.block.entity.DockBlockEntity.resolveDeliverCargo(pool, deliverHere);

        assertFalse(deliver.isEmpty(), "no-spec manifest entry should fall back to delivering the whole pool");
        assertTrue(pool.isEmpty(), "fallback delivery should drain the pool");
    }

    @Test
    void deliverCargoUsesExactSelectionWhenManifestHasItemSpec() {
        // manifest 条目有完整物品规格（多站连运调度）→ 精确抽取，不动其它货
        java.util.List<com.monpai.sailboatmod.market.ShipmentManifestEntry> deliverHere = java.util.List.of(
                new com.monpai.sailboatmod.market.ShipmentManifestEntry(
                        "l1", new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 16),
                        "po-2", "so-2", "uuid", "buyer", 16));
        java.util.List<net.minecraft.world.item.ItemStack> pool = new java.util.ArrayList<>(java.util.List.of(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 64)));

        java.util.List<net.minecraft.world.item.ItemStack> deliver =
                com.monpai.sailboatmod.block.entity.DockBlockEntity.resolveDeliverCargo(pool, deliverHere);

        int delivered = deliver.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
        assertTrue(delivered == 16, "exact selection should deliver only the manifest quantity");
        assertFalse(pool.isEmpty(), "remaining cargo should stay in the pool for onward legs");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.CarriageUnloadDecisionTest`
Expected: FAIL —— `DockBlockEntity.resolveDeliverCargo` 不存在。

- [ ] **Step 3: 在 DockBlockEntity 新增 resolveDeliverCargo**

在 `DockBlockEntity.java` 的 `selectCargoForEntries`（:1177-1189）之后新增：

```java
    /**
     * 决定本站实际投递的货：
     * 若 deliverHere 里存在"无物品规格"的条目（itemStack 空或 quantity<=0，如手动发车 setPendingMarketDelivery
     * 设的占位 manifest），无法精确抽取 → 兜底把整个 pool 全投递（pool 清空）；
     * 否则按 selectCargoForEntries 精确抽取（保多站连运：只卸本站该投递的量，其余留车）。
     */
    public static List<ItemStack> resolveDeliverCargo(List<ItemStack> pool, List<ShipmentManifestEntry> deliverHere) {
        if (pool == null || pool.isEmpty() || deliverHere == null || deliverHere.isEmpty()) {
            return new ArrayList<>();
        }
        boolean hasUnspecifiedEntry = false;
        for (ShipmentManifestEntry entry : deliverHere) {
            if (entry == null) {
                continue;
            }
            if (entry.itemStack() == null || entry.itemStack().isEmpty() || entry.quantity() <= 0) {
                hasUnspecifiedEntry = true;
                break;
            }
        }
        if (hasUnspecifiedEntry) {
            List<ItemStack> all = new ArrayList<>(pool);
            pool.clear();
            return all;
        }
        return selectCargoForEntries(pool, deliverHere);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.CarriageUnloadDecisionTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java src/test/java/com/monpai/sailboatmod/entity/CarriageUnloadDecisionTest.java && git commit -m "fix(transport): deliver whole pool when manifest entry lacks item spec"
```

---

## Task 3: 马车 unloadOnArrival 字段 + NBT + 实装 setter

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Test: `src/test/java/com/monpai/sailboatmod/entity/UnloadOnArrivalWiringContractTest.java`

> 马车补齐字段（默认 true=卸货）、NBT 持久化、实装 `setAllowNonOrderAutoUnload`（接到该字段）。

- [ ] **Step 1: 写失败的接线断言测试**

新建 `src/test/java/com/monpai/sailboatmod/entity/UnloadOnArrivalWiringContractTest.java`：

```java
package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UnloadOnArrivalWiringContractTest {
    private static String carriage() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java"));
    }

    @Test
    void carriageHasUnloadOnArrivalFieldDefaultTrue() throws Exception {
        String src = carriage();
        assertTrue(src.contains("private boolean unloadOnArrival = true"),
                "carriage should have unloadOnArrival defaulting to true (unload by default)");
    }

    @Test
    void carriagePersistsUnloadOnArrivalInNbt() throws Exception {
        String src = carriage();
        assertTrue(src.contains("\"UnloadOnArrival\""),
                "carriage should save/load unloadOnArrival under an NBT key");
        assertTrue(src.contains("putBoolean(\"UnloadOnArrival\", unloadOnArrival)"),
                "carriage should write unloadOnArrival to NBT");
        assertTrue(src.contains("unloadOnArrival = ") && src.contains("getBoolean(\"UnloadOnArrival\")"),
                "carriage should read unloadOnArrival from NBT");
    }

    @Test
    void carriageSetterIsWiredNotEmpty() throws Exception {
        String src = carriage();
        assertTrue(src.contains("this.unloadOnArrival = allow")
                        || src.contains("unloadOnArrival = allow"),
                "setAllowNonOrderAutoUnload must assign the field, not be an empty stub");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.UnloadOnArrivalWiringContractTest`
Expected: FAIL —— 无字段/NBT/实装。

- [ ] **Step 3a: 加字段**

先 Read `CarriageEntity.java` 字段声明区（:239-271 附近，找 `transportTaskKind` 声明那块），在该区域加：

```java
    private boolean unloadOnArrival = true;
```

- [ ] **Step 3b: 实装 setter**

把空的 `setAllowNonOrderAutoUnload`（:1620-1621）替换为：

```java
    @Override
    public void setAllowNonOrderAutoUnload(boolean allow) {
        this.unloadOnArrival = allow;
    }
```

补一个 getter 供 UI/逻辑用（紧随其后）：

```java
    public boolean isUnloadOnArrival() {
        return unloadOnArrival;
    }
```

- [ ] **Step 3c: NBT save/load**

先 Read 马车的 `addAdditionalSaveData`/`readAdditionalSaveData`（grep `putBoolean`/`getBoolean` 定位），在 save 方法里加：

```java
        tag.putBoolean("UnloadOnArrival", unloadOnArrival);
```

在 load 方法里加（兼容旧存档：缺键时默认 true）：

```java
        unloadOnArrival = !tag.contains("UnloadOnArrival") || tag.getBoolean("UnloadOnArrival");
```

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.UnloadOnArrivalWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/test/java/com/monpai/sailboatmod/entity/UnloadOnArrivalWiringContractTest.java && git commit -m "feat(transport): carriage unloadOnArrival field + nbt + wired setter (default unload)"
```

---

## Task 4: 马车 finishAutopilot 接入卸货决策 + 兜底全卸

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`（`finishAutopilot` :2248-2287）
- Test: 扩展 `UnloadOnArrivalWiringContractTest.java`

> 把 Task 1（决策）+ Task 2（兜底全卸）接进 finishAutopilot：先判 `shouldUnloadAtArrival(hasOrder, unloadOnArrival)`，不卸则保留所有货直接停；卸则用 `resolveDeliverCargo` 取该投递的货。

- [ ] **Step 1: 扩展接线断言**

在 `UnloadOnArrivalWiringContractTest.java` 追加：

```java
    @Test
    void finishAutopilotUsesUnloadDecisionAndFallbackDelivery() throws Exception {
        String src = carriage();
        assertTrue(src.contains("shouldUnloadAtArrival("),
                "finishAutopilot should gate unloading on the unload decision");
        assertTrue(src.contains("hasTransportOrder("),
                "carriage should detect order-driven trips to force unload");
        assertTrue(src.contains("DockBlockEntity.resolveDeliverCargo("),
                "finishAutopilot should use the whole-pool fallback delivery");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.UnloadOnArrivalWiringContractTest`
Expected: FAIL —— finishAutopilot 还没接入。

- [ ] **Step 3a: 加 hasTransportOrder（马车版，对齐帆船）**

马车没有 `hasTransportOrder`，在 `CarriageEntity.java` 内新增（紧邻 `shouldUnloadAtArrival`，复用 ShipmentManifestEntry 的订单 id 判断，与 `SailboatEntity:2163` 同逻辑）：

```java
    private static boolean hasTransportOrder(List<ShipmentManifestEntry> manifest) {
        if (manifest == null || manifest.isEmpty()) {
            return false;
        }
        for (ShipmentManifestEntry entry : manifest) {
            if (entry == null) {
                continue;
            }
            String purchaseOrderId = entry.purchaseOrderId();
            String shippingOrderId = entry.shippingOrderId();
            if ((purchaseOrderId != null && !purchaseOrderId.isBlank())
                    || (shippingOrderId != null && !shippingOrderId.isBlank())) {
                return true;
            }
        }
        return false;
    }
```

> 确认 `ShipmentManifestEntry` 与 `java.util.List` 已 import（finishAutopilot 已用 manifest，应已 import）。

- [ ] **Step 3b: 改 finishAutopilot 卸货块**

把 `finishAutopilot`（:2248-2287）里 `if (destination != null) { ... }` 卸货块（即 :2251-2276）替换为：

```java
        if (destination != null) {
            dockedStationPos = destination.getBlockPos().immutable();
            dockedTownId = DockTownResolver.resolveTownForArrival(level(), destination.getBlockPos());

            List<ShipmentManifestEntry> manifest = getPendingShipmentManifest();
            boolean hasOrder = hasTransportOrder(manifest);
            boolean unload = shouldUnloadAtArrival(hasOrder, unloadOnArrival);

            if (unload) {
                // 按目的地拆分运单：只卸"目的地==本站"的货，其余留车继续运（多站连运）。
                List<ItemStack> allCargo = unloadAllCargo();
                DockBlockEntity.ManifestSplit split = DockBlockEntity.splitManifestByDestination(
                        level(), destination.getBlockPos(), manifest);
                List<ItemStack> pool = new ArrayList<>(allCargo);
                // 兜底全卸：manifest 条目无物品规格时投递整池，否则精确抽取。
                List<ItemStack> deliverCargo = DockBlockEntity.resolveDeliverCargo(pool, split.deliverHere());
                if (!pool.isEmpty()) {
                    loadCargo(pool); // 留车货物退回库存
                }
                if (!deliverCargo.isEmpty()) {
                    destination.receiveShipment(this, getAutopilotRouteName(), pendingShipperName, "-", destination.getDockName(),
                            System.currentTimeMillis(), 0L, 0.0D, deliverCargo, split.deliverHere());
                }
                setPendingShipmentManifest(split.keepOnboard()); // 剪枝：移除已交付条目

                // 仍有未送达运单 → 自动开往下一站，逐站连运。
                if (!split.keepOnboard().isEmpty()
                        && destination instanceof PostStationBlockEntity here
                        && tryStartLandLegToNextStation(here, split.keepOnboard())) {
                    return;
                }
            }
            // unload==false：手动发车 + 开关关 → 不卸货，货留车，玩家自理。
        }
```

> 关键差异 vs 现状：(1) 加 `unload` 门控；(2) `selectCargoForEntries` 换成 `resolveDeliverCargo`（兜底全卸）。其余（停靠反馈、延迟返航、stopAutopilot）保留 :2277-2286 不动。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.UnloadOnArrivalWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/test/java/com/monpai/sailboatmod/entity/UnloadOnArrivalWiringContractTest.java && git commit -m "fix(transport): carriage finishAutopilot gates unload + whole-pool fallback"
```

---

## Task 5: 帆船默认卸货 + 兜底全卸对齐

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java`
- Test: 扩展 `UnloadOnArrivalWiringContractTest.java`

> 帆船 `autopilotAllowNonOrderAutoUnload` 默认 false → 改 true（与用户"默认卸货"一致）；卸货端 `selectCargoForEntries` 换 `resolveDeliverCargo`（同马车，修同类空规格 bug）。

- [ ] **Step 1: 扩展接线断言**

在 `UnloadOnArrivalWiringContractTest.java` 追加：

```java
    @Test
    void sailboatDefaultsToUnloadAndUsesFallbackDelivery() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
        assertTrue(src.contains("autopilotAllowNonOrderAutoUnload = true"),
                "sailboat should default to unload-on-arrival (true)");
        assertTrue(src.contains("DockBlockEntity.resolveDeliverCargo("),
                "sailboat unload should use the whole-pool fallback too");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.UnloadOnArrivalWiringContractTest`
Expected: FAIL —— 帆船默认仍 false、仍用 selectCargoForEntries。

- [ ] **Step 3a: 默认值改 true**

`SailboatEntity.java:199` 字段声明：

```java
    private boolean autopilotAllowNonOrderAutoUnload = true;
```

> 同时检查 :1192 和 :2041 两处 `autopilotAllowNonOrderAutoUnload = false;` 的重置——这些是"开始新 autopilot/清理时重置为默认"。把这两处也改为 `= true;` 以保持"默认卸货"语义一致（Read 确认上下文是重置到默认，不是业务性置 false）。NBT load（:943）保持读存档值不变。

- [ ] **Step 3b: 卸货端换 resolveDeliverCargo**

`SailboatEntity.java:2107` 把：

```java
            List<ItemStack> deliverCargo = DockBlockEntity.selectCargoForEntries(pool, split.deliverHere());
```

替换为：

```java
            List<ItemStack> deliverCargo = DockBlockEntity.resolveDeliverCargo(pool, split.deliverHere());
```

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.UnloadOnArrivalWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java src/test/java/com/monpai/sailboatmod/entity/UnloadOnArrivalWiringContractTest.java && git commit -m "fix(transport): sailboat defaults to unload + whole-pool fallback delivery"
```

---

## Task 6: 切换开关的 packet

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/SetUnloadOnArrivalPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Test: 扩展 `UnloadOnArrivalWiringContractTest.java`

> 客户端 UI → 服务端切换开关。沿用项目 packet 三件套 encode/decode/handle，handle 里对玩家当前乘坐的载具调 `setAllowNonOrderAutoUnload`。

- [ ] **Step 1: 扩展接线断言**

在 `UnloadOnArrivalWiringContractTest.java` 追加：

```java
    @Test
    void unloadTogglePacketIsRegisteredAndWired() throws Exception {
        String packet = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/network/packet/SetUnloadOnArrivalPacket.java"));
        String network = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/network/ModNetwork.java"));

        assertTrue(packet.contains("public static void encode(")
                        && packet.contains("public static void decode(")
                        && packet.contains("public static void handle("),
                "packet must follow encode/decode/handle triple");
        assertTrue(packet.contains("setAllowNonOrderAutoUnload("),
                "packet handle should toggle the vehicle unload switch");
        assertTrue(network.contains("SetUnloadOnArrivalPacket.class"),
                "packet must be registered in ModNetwork");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.UnloadOnArrivalWiringContractTest`
Expected: FAIL —— packet 文件不存在。

- [ ] **Step 3a: 创建 packet**

先 Read 一个现有简单 packet（如 `ControlAutopilotPacket.java`）确认 import、`NetworkEvent.Context`、`ServerPlayer` 获取方式、以及载具公共接口（开关 setter 声明在 `TransportEntity` 还是各实体）。新建 `src/main/java/com/monpai/sailboatmod/network/packet/SetUnloadOnArrivalPacket.java`：

```java
package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.TransportEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SetUnloadOnArrivalPacket {
    private final int entityId;
    private final boolean unloadOnArrival;

    public SetUnloadOnArrivalPacket(int entityId, boolean unloadOnArrival) {
        this.entityId = entityId;
        this.unloadOnArrival = unloadOnArrival;
    }

    public static void encode(SetUnloadOnArrivalPacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.entityId);
        buffer.writeBoolean(packet.unloadOnArrival);
    }

    public static SetUnloadOnArrivalPacket decode(FriendlyByteBuf buffer) {
        return new SetUnloadOnArrivalPacket(buffer.readInt(), buffer.readBoolean());
    }

    public static void handle(SetUnloadOnArrivalPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            if (sender.level().getEntity(packet.entityId) instanceof TransportEntity vehicle) {
                vehicle.setAllowNonOrderAutoUnload(packet.unloadOnArrival);
            }
        });
        context.setPacketHandled(true);
    }
}
```

> 若 `setAllowNonOrderAutoUnload` 不在 `TransportEntity` 接口上（grep 确认），改为分别 instanceof `CarriageEntity`/`SailboatEntity` 调用。先 Read `TransportEntity.java` 确认该方法是否在接口里。

- [ ] **Step 3b: 注册 packet**

先 Read `ModNetwork.java` 的 `register()` 末尾确认 `packetId` 递增模式，在最后一个 `packetId++` 之后加（照现有行格式）：

```java
        CHANNEL.registerMessage(packetId++, SetUnloadOnArrivalPacket.class,
                SetUnloadOnArrivalPacket::encode, SetUnloadOnArrivalPacket::decode, SetUnloadOnArrivalPacket::handle);
```

> 严格照该文件现有 `registerMessage` 的实际写法（参数顺序/CHANNEL 变量名）改，不要照搬上面的占位写法——以 Read 到的现有行为准。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.UnloadOnArrivalWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/network/packet/SetUnloadOnArrivalPacket.java src/main/java/com/monpai/sailboatmod/network/ModNetwork.java src/test/java/com/monpai/sailboatmod/entity/UnloadOnArrivalWiringContractTest.java && git commit -m "feat(transport): packet to toggle vehicle unloadOnArrival"
```

---

## Task 7: 载具界面卸货开关 UI（马车 + 帆船）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/CarriageInfoScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/SailboatInfoScreen.java`
- Modify: `src/main/resources/assets/sailboatmod/lang/zh_cn.json` + `en_us.json`
- Test: 扩展 `UnloadOnArrivalWiringContractTest.java`

> 在马车/帆船信息界面加"到站卸货"开关按钮，点击发 `SetUnloadOnArrivalPacket`。先 Read 两个 Screen 现有按钮模式（如何 addRenderableWidget、如何拿当前载具/entityId、如何发 packet）后照着加。

- [ ] **Step 1: 定位现状（只读）**

Run: `cd sailboatmod && grep -n "addRenderableWidget\|Button.builder\|ModNetwork\|sendToServer\|getId()\|Component.translatable" src/main/java/com/monpai/sailboatmod/client/screen/CarriageInfoScreen.java | head -20`
Expected: 列出现有按钮/发包模式。**据此调整下面的按钮代码以匹配实际 API**（Screen 是否持有 entity 引用、发包用哪个 ModNetwork 方法）。

- [ ] **Step 2: 扩展接线断言**

在 `UnloadOnArrivalWiringContractTest.java` 追加：

```java
    @Test
    void infoScreensExposeUnloadToggle() throws Exception {
        String carriageScreen = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/client/screen/CarriageInfoScreen.java"));
        String sailboatScreen = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/client/screen/SailboatInfoScreen.java"));

        assertTrue(carriageScreen.contains("SetUnloadOnArrivalPacket"),
                "carriage info screen should send the unload-toggle packet");
        assertTrue(sailboatScreen.contains("SetUnloadOnArrivalPacket"),
                "sailboat info screen should send the unload-toggle packet");
        assertTrue(carriageScreen.contains("screen.sailboatmod.vehicle.unload_on_arrival"),
                "carriage screen should use the localized unload-toggle label");
    }
```

- [ ] **Step 3: 加开关按钮（两端）**

> 以下为模板，**必须按 Step 1 Read 到的实际 Screen API 调整**（按钮构造、坐标、如何取 entityId、发包方法名）。在 `CarriageInfoScreen` 的 `init()`（或等价初始化）里加一个开关按钮，文案用 `Component.translatable("screen.sailboatmod.vehicle.unload_on_arrival")`，点击时读当前开关状态取反并发包：

```java
        // 到站卸货开关：点击切换并同步服务端
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                net.minecraft.network.chat.Component.translatable("screen.sailboatmod.vehicle.unload_on_arrival"),
                button -> {
                    boolean next = !currentUnloadOnArrival();
                    com.monpai.sailboatmod.network.ModNetwork.sendToServer(
                            new com.monpai.sailboatmod.network.packet.SetUnloadOnArrivalPacket(vehicleEntityId(), next));
                    setCurrentUnloadOnArrival(next);
                }).bounds(unloadToggleX(), unloadToggleY(), unloadToggleWidth(), 20).build());
```

> `currentUnloadOnArrival()`/`vehicleEntityId()`/坐标方法是占位——替换为 Screen 实际持有的载具引用（如 `this.carriage.isUnloadOnArrival()`、`this.carriage.getId()`）与现有布局常量。`ModNetwork.sendToServer` 替换为该文件其它发包用的实际方法。`SailboatInfoScreen` 同样处理（用 `this.sailboat`）。

- [ ] **Step 4: 加 lang 文案（两端语言）**

`zh_cn.json` 加：

```json
    "screen.sailboatmod.vehicle.unload_on_arrival": "到站卸货",
```

`en_us.json` 加：

```json
    "screen.sailboatmod.vehicle.unload_on_arrival": "Unload on arrival",
```

- [ ] **Step 5: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.UnloadOnArrivalWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/client/screen/CarriageInfoScreen.java src/main/java/com/monpai/sailboatmod/client/screen/SailboatInfoScreen.java src/main/resources/assets/sailboatmod/lang/zh_cn.json src/main/resources/assets/sailboatmod/lang/en_us.json src/test/java/com/monpai/sailboatmod/entity/UnloadOnArrivalWiringContractTest.java && git commit -m "feat(transport): unload-on-arrival toggle in carriage/sailboat info screens"
```

---

## Task 8: 全量测试 + 构建 + 推送

**Files:** 无（验证任务）

- [ ] **Step 1: 全量测试**

Run: `cd sailboatmod && ./gradlew test`
Expected: PASS。重点关注 `CarriageEntityMovementTest`、新增两个测试、以及任何依赖旧卸货行为的既有测试。

- [ ] **Step 2: 完整构建出 jar**

Run: `cd sailboatmod && ./gradlew build`
Expected: BUILD SUCCESSFUL，`build/libs/` 生成 `-reobf.jar`。

- [ ] **Step 3: 提交剩余并推送（经代理 7897 + gh token）**

```bash
cd sailboatmod && git add -A && git commit -m "test(transport): unload fix + switch verified" || echo "nothing to commit"
GH_TOKEN=$(gh auth token); REPO_PATH=$(git remote get-url origin | sed -E 's#https://([^/]*@)?github.com/##; s#\.git$##'); BRANCH=$(git rev-parse --abbrev-ref HEAD); HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 git push "https://x-access-token:${GH_TOKEN}@github.com/${REPO_PATH}.git" "$BRANCH"
```

---

## 集成实测（手动，单测无法覆盖到站行为）

1. **马车商单派发** → 到站自动卸货（修好 bug）。
2. **马车手动发车 + 开关开** → 到站卸货；**开关关** → 到站不卸、货留车。
3. **帆船**同样两种场景。
4. **多站连运**（带完整物品规格 manifest）仍正确逐站只卸本站货。
5. 旧存档载具加载后默认卸货（NBT 缺键回退 true）。

---

## Self-Review

**需求覆盖：**
- 马车到站不卸 bug（manifest 空规格）→ Task 2（兜底全卸）+ Task 4（接入）✓
- 显式卸货开关，马车+帆船 → Task 3（马车字段/NBT/setter）+ Task 5（帆船默认）+ Task 6（packet）+ Task 7（UI）✓
- 商单始终卸 / 手动看开关 → Task 1（决策 hasOrder||switch）+ Task 4（hasTransportOrder 接入）✓
- 默认卸货 → Task 3（马车默认 true）+ Task 5（帆船默认 true）✓
- 帆船同类空规格 bug → Task 5（resolveDeliverCargo 对齐）✓
- 验证 → Task 8 + 集成实测 ✓

**类型/签名一致性：** `shouldUnloadAtArrival(boolean,boolean)`、`resolveDeliverCargo(List,List)`、`hasTransportOrder(List)`、`unloadOnArrival` 字段、`setAllowNonOrderAutoUnload(boolean)`、`SetUnloadOnArrivalPacket` 在各任务一致。NBT 键统一 `"UnloadOnArrival"`（马车）；帆船沿用旧键 `"AutopilotAllowNonOrderAutoUnload"` 不改（避免破坏存档）。

**无占位符：** 每步含完整代码；Task 6/7 因依赖现有 packet/Screen API，明确要求"先 Read 现有模式再按实际调整"，并给出确切模板与接线断言。

**已知 UI 不确定性（如实标注）：** Task 7 的 Screen 按钮 API（addRenderableWidget 签名、entity 引用、发包方法）依项目实际，Step 1 先 Read 定位；接线断言只验"含 SetUnloadOnArrivalPacket + lang key"，不锁死按钮坐标。

**范围：** 本计划只覆盖卸货修复 + 开关。定价（第二部分）、运输派发（第一部分）独立。
