# P2P 货运 Phase 5：下单弹窗 UI 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在客户端 Elementa 与网页端各加一个下单确认弹窗，让买家选运输模式（三选一）+ 收货地（下拉），并修掉危险回退、补模式可达性探测与卖家发货排队展示。

**Architecture:** 后端单点计算收货候选/可达性/排队，随 overview 下发，两端只读消费（同源 A1）。复用已就绪的 5-arg 网络包构造器与 6-arg web 重载（B1），只改调用方；可达性复用 `resolveDispatchTerminalPlan`（真路由），排队复用「无车订单留队列」的隐式队列，仅做显式化。后端核心逻辑与包编解码零改。

**Tech Stack:** Minecraft Forge 1.20.1 (Java 17)、Elementa（客户端 GUI）、原生 JS + 现有 CSS 设计系统（网页端）、JUnit 5（`Files.readString` 源码契约断言 + 纯逻辑单测）。

**基线事实（已核实，实现时勿臆测）：**
- 网络包 `src/main/java/com/monpai/sailboatmod/network/packet/PurchaseMarketListingPacket.java` **已有 5-arg 构造器**（行 24-31）+ encode/decode 写读 `fulfillment`/`targetWarehousePos`（行 33-48）。**本期不改此包**。
- `MarketScreen.sendBuy()` 在 `src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java:3442`，现用 3-arg 构造器（行 3453-3457）。
- footer buy 按钮在 `MarketScreen` 行 897 调 `sendBuy()`。
- 后端 `MarketBlockEntity`（`src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`）：
  - `receivingWarehouseCandidatesFor(String)`（行 606）**现状遍历 `nations.getTownsForNation(member.nationId())`**——是「整个 nation 所有 town 仓库」，需收窄。
  - `defaultReceivingWarehouseFor`（行 633）= 候选首个。
  - `resolveBuyerTargetWarehouse`（行 639）**含危险回退** `return preferred != null ? preferred : linkedDockPos;`（行 644）。
  - `warehouseDisplayNameFor(BlockPos, TownWarehouseBlockEntity)`（行 648）。
  - `resolveDispatchTerminalPlan(TownWarehouseBlockEntity sourceWarehouse, BlockPos targetWarehousePos, TransportTerminalKind terminalKind, Player player)`（行 1232）**遍历单一 kind**，不可达/无车返回 null。
  - `getLinkedWarehouse()`、`getOpenOrdersForSourceDock(BlockPos)` 已存在。
  - `purchaseListingById(...7-arg...)`（行 592）已被网络包调用。
- web `MarketWebService.purchaseListing(...6-arg...)`（`src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java:288`）**已就绪**，转调 `market().purchaseListing(...index 版 6-arg...)`。
- web `MarketWebServer` `/purchase` 路由在 `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java:307-314`，现 4-arg 调用；`intValue`/`stringValue` 辅助在行 1006/1028。
- web 买家订单序列化 `myOrders(MarketSavedData, String)` 在 `MarketWebService.java:752`，逐字段读 `PurchaseOrder`——**排队字段的 web 落点在这里**。
- web overview JSON 组装 `root.add*` 批在 `MarketWebService.java:192-251`。
- `MarketOverviewData`（`src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java`）：record 字段尾在行 57，紧凑构造器在 59-80，`OrderEntry` record 在 156-161。
- `ModNetwork`（`src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`）：`PROTOCOL_VERSION="8"`（行 112），`packetId++` 顺序注册（行 120 起），新包追加到 `register()` 末尾。
- `FulfillmentMode`（`src/main/java/com/monpai/sailboatmod/market/FulfillmentMode.java`）：`REAL_PICKUP / AUTO_PICKUP / SELLER_SHIP`，`fromString` 安全解析回退 SELLER_SHIP。
- lang 文件：`src/main/resources/assets/sailboatmod/lang/zh_cn.json` + `en_us.json`（两份都改）。
- 契约测试风格：`src/test/java/com/monpai/sailboatmod/market/PickupWiringContractTest.java` 用 `Files.readString(Path.of(...))` + `assertTrue(src.contains(...))`。
- 构建：`./gradlew build -x test` 出 jar；单测 `./gradlew test --tests "全限定类名"`（完整 `build` 会因 6 个无关 web map 测试中断）。
- 推送：见 memory `sailboatmod-push`（代理 7897 + openssl 后端 + gh token 内嵌 URL）。

**阶段顺序与依赖：**
- 阶段 A（后端数据层）→ 阶段 B（overview 下发字段）→ 阶段 C（探测往返）→ 阶段 D（排队显式化）→ 阶段 E（客户端弹窗）→ 阶段 F（网页弹窗）→ 阶段 G（i18n + 收尾构建推送）。
- E/F 依赖 A/B/C/D 的下发契约；G 依赖全部。

---

## 阶段 A：后端数据层（收货候选收窄 + 修危险回退）

### Task A1：新增 `WarehouseOption` record

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java`（在 `OrderEntry` record 后插入，约行 161 之后）

- [ ] **Step 1：写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/ReceivingWarehouseContractTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceivingWarehouseContractTest {
    private static String overviewData() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java"));
    }

    @Test
    void overviewHasWarehouseOptionRecord() throws Exception {
        String src = overviewData();
        assertTrue(src.contains("public record WarehouseOption(BlockPos pos, String displayName, String townName)"),
                "MarketOverviewData should declare a WarehouseOption record");
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest"`
Expected: FAIL — `overviewHasWarehouseOptionRecord` 断言失败（record 尚不存在）。

- [ ] **Step 3：加 record（最小实现）**

`MarketOverviewData.java`：确认顶部已 `import net.minecraft.core.BlockPos;`（已有，行 6）。在 `OrderEntry` record（行 161 `}` 之后）插入：

```java
    public record WarehouseOption(BlockPos pos, String displayName, String townName) {
    }
```

- [ ] **Step 4：运行测试确认通过**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest"`
Expected: PASS。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java src/test/java/com/monpai/sailboatmod/market/ReceivingWarehouseContractTest.java
git commit -m "feat(market): add WarehouseOption record to overview data"
```

---

### Task A2：收货候选收窄为当前 town 可写入仓库 + 产出带显示名的 `WarehouseOption`

将 `receivingWarehouseCandidatesFor`（返回 `List<BlockPos>`）的口径从「整个 nation 所有 town」收窄为「当前 town 内、该玩家可写入的仓库」，并新增产出 `WarehouseOption`（含显示名 + townName）的方法 `receivingWarehouseOptionsForViewer`。保留 `defaultReceivingWarehouseFor` 语义（候选首个）。

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`（行 606-636 区域）
- Test: `src/test/java/com/monpai/sailboatmod/market/ReceivingWarehouseContractTest.java`

- [ ] **Step 1：补契约测试（验签名存在 + 当前 town 口径）**

在 `ReceivingWarehouseContractTest` 加：

```java
    private static String marketBlockEntity() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
    }

    @Test
    void optionsMethodScopedToCurrentTown() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("receivingWarehouseOptionsForViewer("),
                "should expose receivingWarehouseOptionsForViewer producing WarehouseOption list");
        assertTrue(src.contains("List<MarketOverviewData.WarehouseOption>"),
                "options method should return WarehouseOption list");
        assertTrue(src.contains("getMember(") && src.contains(".townId()"),
                "candidates should resolve the viewer's own town id, not all nation towns");
    }
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest"`
Expected: FAIL — `optionsMethodScopedToCurrentTown` 失败。

- [ ] **Step 3：实现**

`MarketBlockEntity.java`，把 `receivingWarehouseCandidatesFor`（行 606-628）改为「当前 town 单仓」口径，并新增 `receivingWarehouseOptionsForViewer`。替换行 605-628 整块为：

```java
    /** 买家当前 town 内、其可写入的收货仓坐标列表（默认仓首位）。供下单回退与下拉同源。 */
    public java.util.List<BlockPos> receivingWarehouseCandidatesFor(String buyerUuid) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        if (level == null || level.isClientSide || buyerUuid == null || buyerUuid.isBlank()) {
            return out;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(buyerUuid.trim());
        } catch (IllegalArgumentException ex) {
            return out;
        }
        NationSavedData nations = NationSavedData.get(level);
        com.monpai.sailboatmod.nation.model.NationMemberRecord member = nations.getMember(uuid);
        if (member == null || member.townId() == null || member.townId().isBlank()) {
            return out;
        }
        BlockPos warehousePos = TownWarehouseRegistry.get(level, member.townId());
        if (warehousePos != null) {
            out.add(warehousePos);
        }
        return out;
    }

    /** 收货下拉同源数据：买家当前 town 可写入仓库，每项含显示名 + townName，默认仓首位。 */
    public java.util.List<MarketOverviewData.WarehouseOption> receivingWarehouseOptionsForViewer(String buyerUuid) {
        java.util.List<MarketOverviewData.WarehouseOption> out = new java.util.ArrayList<>();
        if (level == null || level.isClientSide) {
            return out;
        }
        NationSavedData nations = NationSavedData.get(level);
        TownWarehouseBlockEntity linked = getLinkedWarehouse();
        for (BlockPos pos : receivingWarehouseCandidatesFor(buyerUuid)) {
            String display = linked != null ? warehouseDisplayNameFor(pos, linked) : posLabel(pos);
            String townName = townNameForWarehouse(nations, pos);
            out.add(new MarketOverviewData.WarehouseOption(pos, display, townName));
        }
        return out;
    }

    private String posLabel(BlockPos pos) {
        return pos == null ? "" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private String townNameForWarehouse(NationSavedData nations, BlockPos pos) {
        if (level != null && level.getBlockEntity(pos) instanceof TownWarehouseBlockEntity w) {
            String townId = w.getTownId();
            TownRecord town = townId == null ? null : nations.getTown(townId);
            return town == null ? "" : town.townName();
        }
        return "";
    }
```

> `townNameForWarehouse` 直接用仓库的 `getTownId()` 查名。若 `NationSavedData` 无 `getTown(String)` 方法，实现时 grep `public TownRecord` 于 `NationSavedData.java` 确认真实查询方法名，按真实签名调用；townName 取不到时回退空串，不阻断。`receivingWarehouseOptionsForViewer` 里 `nations` 变量用于此查名。

- [ ] **Step 4：运行确认通过**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest"`
Expected: PASS。

- [ ] **Step 5：编译校验**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL（无未解析符号；如 `getTown` 名不符，按真实名修正后再编译）。

- [ ] **Step 6：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/ReceivingWarehouseContractTest.java
git commit -m "feat(market): scope receiving warehouses to current town + WarehouseOption list"
```

---

### Task A3：移除 `resolveBuyerTargetWarehouse` 的 linkedDock 危险回退

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java:639-645`
- Test: `src/test/java/com/monpai/sailboatmod/market/ReceivingWarehouseContractTest.java`

- [ ] **Step 1：补契约测试（验不再回退 linkedDockPos）**

在 `ReceivingWarehouseContractTest` 加：

```java
    @Test
    void targetResolveNoLongerFallsBackToLinkedDock() throws Exception {
        String src = marketBlockEntity();
        int idx = src.indexOf("private BlockPos resolveBuyerTargetWarehouse(");
        assertTrue(idx >= 0, "resolveBuyerTargetWarehouse should exist");
        int end = src.indexOf('}', src.indexOf('{', idx));
        String body = src.substring(idx, end);
        assertTrue(!body.contains("linkedDockPos"),
                "resolveBuyerTargetWarehouse must not fall back to linkedDockPos");
        assertTrue(body.contains("return null") || body.contains("return preferred"),
                "no-warehouse case should resolve to null, not the seller dock");
    }
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest"`
Expected: FAIL — `targetResolveNoLongerFallsBackToLinkedDock`（当前 body 含 `linkedDockPos`）。

- [ ] **Step 3：实现**

把 `resolveBuyerTargetWarehouse`（行 639-645）替换为：

```java
    /** 下单目的地解析：买家选的收货仓 → 买家默认收货仓 → null（不再回退卖家源仓）。 */
    private BlockPos resolveBuyerTargetWarehouse(String buyerUuid, @Nullable BlockPos chosen) {
        if (chosen != null && !chosen.equals(BlockPos.ZERO)) {
            return chosen;
        }
        return defaultReceivingWarehouseFor(buyerUuid);
    }
```

> 影响面核查：`purchaseListingResolved`（行 702）用其返回值 `receivingWarehouse` 再去 `warehouseDisplayNameFor(receivingWarehouse, warehouse)`（行 719）。`receivingWarehouse` 现可能为 null——实现时读 702-725 段，若 null 会 NPE，则在 702 后加：`if (receivingWarehouse == null) { return false; }`（②③ 模式无收货仓时两端 UI 已置灰拦截，后端再兜底拒单）。

- [ ] **Step 4：运行确认通过 + 编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest"`
Run: `./gradlew compileJava`
Expected: 测试 PASS；编译 SUCCESSFUL。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/ReceivingWarehouseContractTest.java
git commit -m "fix(market): drop dangerous linkedDock fallback in target warehouse resolution"
```

---

## 阶段 B：`MarketOverviewData` 下发字段（同源契约）

### Task B1：overview 追加 `receivingWarehouseOptions` + `canChooseReceiving` 字段

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java`（record 字段尾行 57、紧凑构造器 59-80）
- Test: `src/test/java/com/monpai/sailboatmod/market/ReceivingWarehouseContractTest.java`

- [ ] **Step 1：补契约测试**

```java
    @Test
    void overviewCarriesReceivingFields() throws Exception {
        String src = overviewData();
        assertTrue(src.contains("List<WarehouseOption> receivingWarehouseOptions"),
                "overview record should carry receivingWarehouseOptions");
        assertTrue(src.contains("boolean canChooseReceiving"),
                "overview record should carry canChooseReceiving");
    }
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest"`
Expected: FAIL — `overviewCarriesReceivingFields`。

- [ ] **Step 3：实现**

`MarketOverviewData.java`：在 record 字段列表尾（行 57 `analyticsSeries` 后，`) {` 前）加两字段——把行 57

```java
        List<MarketAnalyticsSeries> analyticsSeries
```

改为

```java
        List<MarketAnalyticsSeries> analyticsSeries,
        List<WarehouseOption> receivingWarehouseOptions,
        boolean canChooseReceiving
```

在紧凑构造器末尾（行 79 `analyticsSeries = ...;` 后、行 80 `}` 前）加：

```java
        receivingWarehouseOptions = receivingWarehouseOptions == null ? List.of() : List.copyOf(receivingWarehouseOptions);
        canChooseReceiving = receivingWarehouseOptions != null && !receivingWarehouseOptions.isEmpty();
```

- [ ] **Step 4：运行确认通过**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest"`
Expected: PASS。但 `compileJava` 此时会失败——所有 `new MarketOverviewData(...)` 调用点缺两个参数。下一步修。

- [ ] **Step 5：修所有构造点**

Run: `grep -rn "new MarketOverviewData(" src/main/java`（实现时执行）。对每个调用点，在末参 `analyticsSeries` 后补 `, receivingWarehouseOptions, canChooseReceiving` 两实参：
- **`MarketBlockEntity.buildOverviewForIdentity`（行 170 起）是单点填充处**：在该方法构造 overview 前算 `var receivingOptions = receivingWarehouseOptionsForViewer(playerUuid);`，构造末补 `receivingOptions, !receivingOptions.isEmpty()`。
- **`buildOverview(Player)`（行 162）**：若它转调 `buildOverviewForIdentity`，则无需改；若独立构造，同样补 `receivingWarehouseOptionsForViewer(player.getUUID().toString())` 与其 `!isEmpty()`。实现时读 162-175 段确认转调关系。
- 其它任何 `new MarketOverviewData(` 调用点（如测试桩/空 overview）：补 `List.of(), false`。

- [ ] **Step 6：编译校验**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/ReceivingWarehouseContractTest.java
git commit -m "feat(market): deliver receiving warehouse options via overview (single-source)"
```

---

### Task B2：overview 网络编解码补 `WarehouseOption` 字段

`MarketOverviewData` 经网络包同步到客户端。找到其编解码处补两新字段，否则客户端拿不到。

**Files:**
- Modify: overview 编解码所在文件（实现时 `grep -rln "marketName" src/main/java/com/monpai/sailboatmod/network` 定位，通常是 `OpenMarketScreenPacket.java` 或专用 codec）

- [ ] **Step 1：定位编解码**

Run: `grep -rln "analyticsSeries" src/main/java/com/monpai/sailboatmod/network`（实现时执行）找到读写 overview 的 encode/decode；确认其逐字段 write/read 模式（List 字段用 `writeVarInt(size)` + 循环）。

- [ ] **Step 2：补 encode**

在 `analyticsSeries` 写入后，追加 `receivingWarehouseOptions` 写入（仿现有 List 字段模式）：

```java
buffer.writeVarInt(data.receivingWarehouseOptions().size());
for (MarketOverviewData.WarehouseOption opt : data.receivingWarehouseOptions()) {
    buffer.writeBlockPos(opt.pos());
    PacketStringCodec.writeUtfSafe(buffer, opt.displayName(), 128);
    PacketStringCodec.writeUtfSafe(buffer, opt.townName(), 128);
}
buffer.writeBoolean(data.canChooseReceiving());
```

- [ ] **Step 3：补 decode**

在对应 read 处，`analyticsSeries` 读完后追加：

```java
int receivingCount = buffer.readVarInt();
java.util.List<MarketOverviewData.WarehouseOption> receivingOptions = new java.util.ArrayList<>(receivingCount);
for (int i = 0; i < receivingCount; i++) {
    BlockPos optPos = buffer.readBlockPos();
    String optName = buffer.readUtf(128);
    String optTown = buffer.readUtf(128);
    receivingOptions.add(new MarketOverviewData.WarehouseOption(optPos, optName, optTown));
}
boolean canChooseReceiving = buffer.readBoolean();
```

并把 `receivingOptions, canChooseReceiving` 作为新增两实参传入 decode 末尾的 `new MarketOverviewData(...)`。

- [ ] **Step 4：编译校验**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/network
git commit -m "feat(net): serialize receiving warehouse options in market overview packet"
```

---

### Task B3：web overview JSON 下发 `canChooseReceiving` + `receivingWarehouseOptions`

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`（`root.add*` 批，行 192-251 区域 + 新辅助方法）
- Test: `src/test/java/com/monpai/sailboatmod/market/MarketWebContractTest.java`（新建）

- [ ] **Step 1：写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/MarketWebContractTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebContractTest {
    private static String webService() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
    }
    private static String webServer() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java"));
    }

    @Test
    void overviewJsonHasReceivingFields() throws Exception {
        String src = webService();
        assertTrue(src.contains("\"canChooseReceiving\""),
                "web overview JSON should include canChooseReceiving");
        assertTrue(src.contains("\"receivingWarehouseOptions\""),
                "web overview JSON should include receivingWarehouseOptions array");
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.MarketWebContractTest"`
Expected: FAIL — `overviewJsonHasReceivingFields`。

- [ ] **Step 3：实现**

`MarketWebService.java`，在 `root.add*` 批（如行 231 `employmentRate` 之后）加：

```java
        root.addProperty("canChooseReceiving", overview.canChooseReceiving());
        root.add("receivingWarehouseOptions", receivingWarehouseOptions(overview));
```

并在 `sourceOrders` 方法旁新增辅助：

```java
    private JsonArray receivingWarehouseOptions(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (MarketOverviewData.WarehouseOption opt : overview.receivingWarehouseOptions()) {
            JsonObject json = new JsonObject();
            BlockPos pos = opt.pos();
            json.addProperty("pos", pos.getX() + "," + pos.getY() + "," + pos.getZ());
            json.addProperty("displayName", opt.displayName());
            json.addProperty("townName", opt.townName());
            out.add(json);
        }
        return out;
    }
```

> 确认顶部已 import `net.minecraft.core.BlockPos`、`com.google.gson.JsonArray`；缺则补。

- [ ] **Step 4：运行确认通过 + 编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.MarketWebContractTest"`
Run: `./gradlew compileJava`
Expected: PASS + SUCCESSFUL。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java src/test/java/com/monpai/sailboatmod/market/MarketWebContractTest.java
git commit -m "feat(web): expose receiving warehouse options in overview JSON"
```

---

### Task B4：web `/purchase` 路由读 `fulfillment` + `targetWarehouse`（4-arg → 6-arg）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java:307-314` + 新增 `parseWarehousePos` 辅助
- Test: `src/test/java/com/monpai/sailboatmod/market/MarketWebContractTest.java`

- [ ] **Step 1：补契约测试**

```java
    @Test
    void purchaseRouteReadsFulfillmentAndWarehouse() throws Exception {
        String src = webServer();
        assertTrue(src.contains("stringValue(body, \"fulfillment\""),
                "/purchase should read fulfillment from body");
        assertTrue(src.contains("parseWarehousePos("),
                "/purchase should parse targetWarehouse into BlockPos");
    }
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.MarketWebContractTest"`
Expected: FAIL — `purchaseRouteReadsFulfillmentAndWarehouse`。

- [ ] **Step 3：实现**

`MarketWebServer.java`，把 `/purchase` 分支（行 307-314）的 4-arg 调用替换为 6-arg：

```java
            if (path.size() == 4 && "purchase".equals(path.get(3))) {
                ok = callOnServerThread(() -> service.purchaseListing(
                        minecraftServer,
                        identity,
                        marketId,
                        intValue(body, "listingIndex", -1),
                        intValue(body, "quantity", 1),
                        stringValue(body, "fulfillment", "SELLER_SHIP"),
                        parseWarehousePos(stringValue(body, "targetWarehouse", ""))));
            } else if (path.size() == 4 && "listings".equals(path.get(3))) {
```

> 现有 `stringValue` 是单参（行 1028 `stringValue(JsonObject, String)`）。需新增带默认值的二参重载 + `parseWarehousePos`。在 `intValue`（行 1006）旁加：

```java
    private static String stringValue(JsonObject body, String key, String fallback) {
        String v = stringValue(body, key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static net.minecraft.core.BlockPos parseWarehousePos(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new net.minecraft.core.BlockPos(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
```

- [ ] **Step 4：运行确认通过 + 编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.MarketWebContractTest"`
Run: `./gradlew compileJava`
Expected: PASS + SUCCESSFUL。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java src/test/java/com/monpai/sailboatmod/market/MarketWebContractTest.java
git commit -m "feat(web): /purchase reads fulfillment + targetWarehouse (6-arg)"
```

---

## 阶段 C：模式可达性探测往返

### Task C1：后端 `probeFulfillmentModes` 探测核心

返回三布尔：③ 卖家发货可达、② 自动自提可达、① 真人自提（恒 true）。复用 `resolveDispatchTerminalPlan`，对 PORT 与 POST_STATION 各探一次取或。

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`（在 `resolveDispatchTerminalPlan` 行 1289 后新增）
- Test: `src/test/java/com/monpai/sailboatmod/market/ReceivingWarehouseContractTest.java`

- [ ] **Step 1：补契约测试**

```java
    @Test
    void probeModesExistsAndReusesPlanner() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("probeFulfillmentModes("),
                "market should expose probeFulfillmentModes for mode reachability");
        int idx = src.indexOf("probeFulfillmentModes(");
        int methodStart = src.indexOf('{', idx);
        int methodEnd = src.indexOf("\n    }", methodStart);
        String body = src.substring(methodStart, methodEnd);
        assertTrue(body.contains("resolveDispatchTerminalPlan("),
                "probe should reuse resolveDispatchTerminalPlan for reachability");
        assertTrue(body.contains("TransportTerminalKind.PORT") && body.contains("TransportTerminalKind.POST_STATION"),
                "probe should try both terminal kinds");
    }
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest"`
Expected: FAIL — `probeModesExistsAndReusesPlanner`。

- [ ] **Step 3：实现**

在 `resolveDispatchTerminalPlan`（行 1289 `}` 后）新增。`record` 放 `MarketOverviewData` 还是内联三 boolean？为最小化跨文件改动，返回一个本地嵌套 record：

```java
    /** 三模式可达性：③卖家发货、②自动自提、①真人自提（恒可达）。供两端弹窗置灰。 */
    public ModeReachability probeFulfillmentModes(String buyerUuid, @Nullable BlockPos targetWarehousePos, @Nullable Player player) {
        boolean realPickup = true; // 玩家自己开去，恒可达
        if (level == null || level.isClientSide || targetWarehousePos == null || targetWarehousePos.equals(BlockPos.ZERO)) {
            return new ModeReachability(false, false, realPickup);
        }
        TownWarehouseBlockEntity sellerWarehouse = getLinkedWarehouse();
        boolean sellerShip = sellerWarehouse != null && (
                resolveDispatchTerminalPlan(sellerWarehouse, targetWarehousePos, TransportTerminalKind.PORT, player) != null
                || resolveDispatchTerminalPlan(sellerWarehouse, targetWarehousePos, TransportTerminalKind.POST_STATION, player) != null);
        // ② 自动自提：买家有车的源仓——本期源仓同为卖家 linked 仓（买家空驶去装），可达性判据同 ③ 的路网/航线存在性。
        boolean autoPickup = sellerShip;
        return new ModeReachability(sellerShip, autoPickup, realPickup);
    }

    public record ModeReachability(boolean sellerShip, boolean autoPickup, boolean realPickup) {
    }
```

> 设计说明：spec §2.5 区分「②买家有车的 town 到收货仓」。本期 ②③ 路网/航线可达性判据相同（同一对终端的真路由存在性），故 `autoPickup = sellerShip`；②是否真有买家空闲车由后台派发兜底，不在 UI 置灰判据内（避免 UI 误锁可下单的合法单）。若后续需细分，再扩展。
> 确认 `TransportTerminalKind` 已 import（同文件已用，无需加）。

- [ ] **Step 4：运行确认通过 + 编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest"`
Run: `./gradlew compileJava`
Expected: PASS + SUCCESSFUL。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/ReceivingWarehouseContractTest.java
git commit -m "feat(market): probeFulfillmentModes reusing dispatch planner for reachability"
```

---

### Task C2：客户端探测往返包 `ProbeFulfillmentModesPacket`

请求（client→server）带 `marketPos` + `listingId` + `targetWarehousePos`；回包（server→client）带三布尔。客户端缓存进 `MarketScreen` 状态字段。

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/ProbeFulfillmentModesPacket.java`（请求）
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/ProbeFulfillmentModesResultPacket.java`（回包）
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`（`register()` 末尾追加两条）
- Modify: `src/main/java/com/monpai/sailboatmod/client/hooks/MarketClientHooks.java`（接收回包写状态）— 实现时 grep 确认真实接收落点
- Test: `src/test/java/com/monpai/sailboatmod/market/ProbeWiringContractTest.java`（新建）

- [ ] **Step 1：写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/ProbeWiringContractTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeWiringContractTest {
    @Test
    void probePacketsRegistered() throws Exception {
        String net = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/ModNetwork.java"));
        assertTrue(net.contains("ProbeFulfillmentModesPacket"),
                "request packet should be registered in ModNetwork");
        assertTrue(net.contains("ProbeFulfillmentModesResultPacket"),
                "result packet should be registered in ModNetwork");
    }

    @Test
    void requestPacketCallsProbe() throws Exception {
        String pkt = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/network/packet/ProbeFulfillmentModesPacket.java"));
        assertTrue(pkt.contains("probeFulfillmentModes("),
                "request handler should call market.probeFulfillmentModes");
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ProbeWiringContractTest"`
Expected: FAIL（包文件/注册不存在）。

- [ ] **Step 3：创建请求包**

`src/main/java/com/monpai/sailboatmod/network/packet/ProbeFulfillmentModesPacket.java`：

```java
package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class ProbeFulfillmentModesPacket {
    private final BlockPos marketPos;
    private final String listingId;
    private final BlockPos targetWarehousePos;

    public ProbeFulfillmentModesPacket(BlockPos marketPos, String listingId, BlockPos targetWarehousePos) {
        this.marketPos = marketPos;
        this.listingId = listingId == null ? "" : listingId;
        this.targetWarehousePos = targetWarehousePos == null ? BlockPos.ZERO : targetWarehousePos;
    }

    public static void encode(ProbeFulfillmentModesPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        PacketStringCodec.writeUtfSafe(buffer, packet.listingId, 64);
        buffer.writeBlockPos(packet.targetWarehousePos);
    }

    public static ProbeFulfillmentModesPacket decode(FriendlyByteBuf buffer) {
        return new ProbeFulfillmentModesPacket(
                buffer.readBlockPos(),
                buffer.readUtf(64),
                buffer.readBlockPos());
    }

    public static void handle(ProbeFulfillmentModesPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            MarketBlockEntity.ModeReachability r = market.probeFulfillmentModes(
                    player.getUUID().toString(), packet.targetWarehousePos, player);
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ProbeFulfillmentModesResultPacket(packet.listingId, r.sellerShip(), r.autoPickup(), r.realPickup()));
        });
        context.setPacketHandled(true);
    }
}
```

- [ ] **Step 4：创建回包**

`src/main/java/com/monpai/sailboatmod/network/packet/ProbeFulfillmentModesResultPacket.java`：

```java
package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.hooks.MarketClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ProbeFulfillmentModesResultPacket {
    private final String listingId;
    private final boolean sellerShip;
    private final boolean autoPickup;
    private final boolean realPickup;

    public ProbeFulfillmentModesResultPacket(String listingId, boolean sellerShip, boolean autoPickup, boolean realPickup) {
        this.listingId = listingId == null ? "" : listingId;
        this.sellerShip = sellerShip;
        this.autoPickup = autoPickup;
        this.realPickup = realPickup;
    }

    public static void encode(ProbeFulfillmentModesResultPacket packet, FriendlyByteBuf buffer) {
        PacketStringCodec.writeUtfSafe(buffer, packet.listingId, 64);
        buffer.writeBoolean(packet.sellerShip);
        buffer.writeBoolean(packet.autoPickup);
        buffer.writeBoolean(packet.realPickup);
    }

    public static ProbeFulfillmentModesResultPacket decode(FriendlyByteBuf buffer) {
        return new ProbeFulfillmentModesResultPacket(
                buffer.readUtf(64),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean());
    }

    public static void handle(ProbeFulfillmentModesResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                MarketClientHooks.applyProbeResult(packet.listingId, packet.sellerShip, packet.autoPickup, packet.realPickup)));
        context.setPacketHandled(true);
    }
}
```

- [ ] **Step 5：注册两包**

`ModNetwork.java`，在 `register()` 末尾（最后一个 `registerMessage` 后、方法 `}` 前）追加，仿现有块写法（用 `packetId++`、`NetworkDirection`）：

```java
        CHANNEL.registerMessage(
                packetId++,
                ProbeFulfillmentModesPacket.class,
                ProbeFulfillmentModesPacket::encode,
                ProbeFulfillmentModesPacket::decode,
                ProbeFulfillmentModesPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(
                packetId++,
                ProbeFulfillmentModesResultPacket.class,
                ProbeFulfillmentModesResultPacket::encode,
                ProbeFulfillmentModesResultPacket::decode,
                ProbeFulfillmentModesResultPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
```

> 实现时读现有某条 `registerMessage`（如行 123-128）确认其 import 与 `NetworkDirection` 写法（可能已静态 import），照抄风格；确认顶部已 import 两新包类（同包则无需）。

- [ ] **Step 6：在 `MarketClientHooks` 加 `applyProbeResult` 暂存**

`grep -n "class MarketClientHooks" src/main/java/com/monpai/sailboatmod/client/hooks/MarketClientHooks.java`（实现时执行）。加静态字段 + 方法，把结果转交当前打开的 `MarketScreen`：

```java
    public static void applyProbeResult(String listingId, boolean sellerShip, boolean autoPickup, boolean realPickup) {
        net.minecraft.client.gui.screens.Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen instanceof com.monpai.sailboatmod.client.screen.MarketScreen market) {
            market.onProbeResult(listingId, sellerShip, autoPickup, realPickup);
        }
    }
```

> `MarketScreen.onProbeResult` 在 Task E3 定义；此处先建方法签名引用，E3 补实现。若此时编译报 `onProbeResult` 未定义，可先在 `MarketScreen` 加空桩 `public void onProbeResult(String id, boolean a, boolean b, boolean c) {}`，E3 再填。

- [ ] **Step 7：运行确认通过 + 编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ProbeWiringContractTest"`
Run: `./gradlew compileJava`
Expected: PASS + SUCCESSFUL（含 `onProbeResult` 空桩）。

- [ ] **Step 8：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/network/packet/ProbeFulfillmentModesPacket.java src/main/java/com/monpai/sailboatmod/network/packet/ProbeFulfillmentModesResultPacket.java src/main/java/com/monpai/sailboatmod/network/ModNetwork.java src/main/java/com/monpai/sailboatmod/client/hooks/MarketClientHooks.java src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java src/test/java/com/monpai/sailboatmod/market/ProbeWiringContractTest.java
git commit -m "feat(net): probe fulfillment modes request/result packets"
```

---

### Task C3：web `/probe-modes` 端点

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java`（路由分支区）
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`（新增 `probeFulfillmentModes` 服务方法）
- Test: `src/test/java/com/monpai/sailboatmod/market/MarketWebContractTest.java`

- [ ] **Step 1：补契约测试**

```java
    @Test
    void probeModesRouteExists() throws Exception {
        String server = webServer();
        assertTrue(server.contains("\"probe-modes\""),
                "web server should route /probe-modes");
        String service = webService();
        assertTrue(service.contains("probeFulfillmentModes("),
                "web service should expose probeFulfillmentModes");
    }
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.MarketWebContractTest"`
Expected: FAIL — `probeModesRouteExists`。

- [ ] **Step 3：service 加 `probeFulfillmentModes`**

`MarketWebService.java`，在 `purchaseListing` 6-arg（行 288-301）后加。它需返回可序列化结果——返回 `JsonObject` 最省：

```java
    public JsonObject probeFulfillmentModes(MinecraftServer server, MarketPlayerIdentity identity, String marketId,
                                            int listingIndex, net.minecraft.core.BlockPos targetWarehousePos) {
        JsonObject json = new JsonObject();
        ResolvedMarket resolved = resolveMarket(server, marketId);
        boolean sellerShip = false;
        boolean autoPickup = false;
        boolean realPickup = true;
        if (resolved != null && identity != null) {
            MarketBlockEntity.ModeReachability r = resolved.market().probeFulfillmentModes(
                    identity.playerUuidString(), targetWarehousePos, identity.onlinePlayer());
            sellerShip = r.sellerShip();
            autoPickup = r.autoPickup();
            realPickup = r.realPickup();
        }
        json.addProperty("sellerShip", sellerShip);
        json.addProperty("autoPickup", autoPickup);
        json.addProperty("realPickup", realPickup);
        return json;
    }
```

> 确认 import `com.monpai.sailboatmod.block.entity.MarketBlockEntity`；缺则补。`probeFulfillmentModes` 后端按 buyerUuid 探测，不需要 listingIndex 参与可达性（源仓取自 market linked 仓），listingIndex 仅为 API 对称保留——实现时若 `probeFulfillmentModes` 完全不用 listingIndex，可不传，保留 web 端 body 字段即可。

- [ ] **Step 4：server 加路由**

`MarketWebServer.java`，在 `/purchase` 分支旁加（POST，返回 JSON）。读现有返回 JSON 的端点（如某 `mapSnapshot`/`marketDetail` 响应写法）确认本服务器「POST 返回 JsonObject」的真实写法后照此接：

```java
            } else if (path.size() == 4 && "probe-modes".equals(path.get(3))) {
                JsonObject probe = callOnServerThreadJson(() -> service.probeFulfillmentModes(
                        minecraftServer,
                        identity,
                        marketId,
                        intValue(body, "listingIndex", -1),
                        parseWarehousePos(stringValue(body, "targetWarehouse", ""))));
                sendJson(exchange, 200, probe);
                return;
```

> `callOnServerThreadJson` / `sendJson` 是占位名——实现时 grep 本文件看真实的「在服务器线程取 JSON 并响应」工具方法（可能 `callOnServerThread` 返回 boolean 不适用，需照 `marketDetail` 的 GET 响应路径找到 JSON 响应 helper，或直接 `service.probe...` 同步调用后 `writeJson`）。务必按真实 helper 接，勿臆造。

- [ ] **Step 5：运行确认通过 + 编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.MarketWebContractTest"`
Run: `./gradlew compileJava`
Expected: PASS + SUCCESSFUL。

- [ ] **Step 6：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java src/test/java/com/monpai/sailboatmod/market/MarketWebContractTest.java
git commit -m "feat(web): /probe-modes endpoint for fulfillment reachability"
```

---

## 阶段 D：排队显式化（仅卖家发货）

### Task D1：`OrderEntry` + 买家订单 JSON 补 `queuePosition`/`queueEtaSeconds`

排队对买家可见的落点：客户端经 overview `OrderEntry`、web 经 `myOrders`。本任务把字段加到两处下发契约 + 计算位次。

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java`（`OrderEntry` record 行 156-161）
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`（构造 `OrderEntry` 处填值 + 队列计算）
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`（`myOrders` 行 752 补字段）
- Test: `src/test/java/com/monpai/sailboatmod/market/QueueContractTest.java`（新建）

- [ ] **Step 1：写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/QueueContractTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueContractTest {
    @Test
    void orderEntryHasQueueFields() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java"));
        assertTrue(src.contains("int queuePosition") && src.contains("int queueEtaSeconds"),
                "OrderEntry should carry queuePosition + queueEtaSeconds");
    }

    @Test
    void webMyOrdersEmitsQueueFields() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
        assertTrue(src.contains("\"queuePosition\"") && src.contains("\"queueEtaSeconds\""),
                "web myOrders should emit queue fields");
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.QueueContractTest"`
Expected: FAIL（两断言）。

- [ ] **Step 3：`OrderEntry` 加字段**

`MarketOverviewData.java`，把 `OrderEntry`（行 156-161）改为：

```java
    public record OrderEntry(String orderId, String label, String sourceDockName, String targetDockName, int quantity, String status,
                             int queuePosition, int queueEtaSeconds,
                             List<DispatchOption> dispatchOptions) {
        public OrderEntry {
            dispatchOptions = dispatchOptions == null ? List.of() : List.copyOf(dispatchOptions);
        }
    }
```

- [ ] **Step 4：修所有 `new OrderEntry(` 构造点 + 算队列**

Run: `grep -rn "new MarketOverviewData.OrderEntry(\|new OrderEntry(" src/main/java`（实现时执行）。在每个构造点的 `status` 与 `dispatchOptions` 之间插入两实参。对卖家发货排队单算真实位次，其余传 `0, 0`。

在 `MarketBlockEntity` 组装 `orderEntries` 的循环里（实现时 grep `new MarketOverviewData.OrderEntry(` 定位，约在 buildOverview 系列方法内），先按货源稳定排序求位次：

```java
// 在组装 orderEntries 前：对本货源 SELLER_SHIP 待发单稳定排序求位次
java.util.List<PurchaseOrder> sellerShipQueue = new java.util.ArrayList<>();
for (PurchaseOrder o : market.getOpenOrdersForSourceDock(linkedDockPos)) {
    if (FulfillmentMode.fromString(o.fulfillmentMode()) == FulfillmentMode.SELLER_SHIP
            && "WAITING_SHIPMENT".equals(o.status())) {
        sellerShipQueue.add(o);
    }
}
sellerShipQueue.sort(java.util.Comparator.comparing(PurchaseOrder::orderId));
// 构造每个 OrderEntry 时：
int queuePos = sellerShipQueue.indexOf(order) >= 0 ? sellerShipQueue.indexOf(order) + 1 : 0;
int queueEta = queuePos > 0 ? queuePos * AVG_DISPATCH_SECONDS_PER_ORDER : 0;
```

加常量（类顶部静态区）：

```java
    private static final int AVG_DISPATCH_SECONDS_PER_ORDER = 60; // 单货源单轮粗估派发耗时
```

> `PurchaseOrder` 是否有 `fulfillmentMode()` 访问器：实现时 `grep -n "fulfillmentMode\|public String mode" src/main/java/com/monpai/sailboatmod/market/PurchaseOrder.java` 确认真实访问器名；若名为别的（如 `mode()`），按真实名用。`order` 变量为当前循环订单——确保位次按同一 `PurchaseOrder` 实例 `indexOf` 命中（同实例引用）；若 overview 的 OrderEntry 不直接持有 PurchaseOrder，则用 `orderId` 匹配求位次。

- [ ] **Step 5：web `myOrders` 补字段**

`MarketWebService.java` `myOrders`（行 752-778），在 `json.addProperty("status", order.status());`（行 765）后加排队计算 + 输出。`myOrders` 直接读 `PurchaseOrder`，需在此重算位次（与上同源逻辑），或从 overview 取——为同源，抽一个共享静态工具 `MarketQueue.position(marketData, sourceDockPos, order)`。最小实现：在 `myOrders` 内按该订单源仓重算：

```java
            int queuePosition = 0;
            int queueEtaSeconds = 0;
            if (com.monpai.sailboatmod.market.FulfillmentMode.fromString(order.fulfillmentMode())
                    == com.monpai.sailboatmod.market.FulfillmentMode.SELLER_SHIP
                    && "WAITING_SHIPMENT".equals(order.status())) {
                java.util.List<PurchaseOrder> q = new java.util.ArrayList<>();
                for (PurchaseOrder o : marketData.getOpenOrdersForSourceDock(order.sourceDockPos())) {
                    if (com.monpai.sailboatmod.market.FulfillmentMode.fromString(o.fulfillmentMode())
                            == com.monpai.sailboatmod.market.FulfillmentMode.SELLER_SHIP
                            && "WAITING_SHIPMENT".equals(o.status())) {
                        q.add(o);
                    }
                }
                q.sort(java.util.Comparator.comparing(PurchaseOrder::orderId));
                int idx = -1;
                for (int k = 0; k < q.size(); k++) {
                    if (q.get(k).orderId().equals(order.orderId())) { idx = k; break; }
                }
                if (idx >= 0) {
                    queuePosition = idx + 1;
                    queueEtaSeconds = queuePosition * 60;
                }
            }
            json.addProperty("queuePosition", queuePosition);
            json.addProperty("queueEtaSeconds", queueEtaSeconds);
```

> 确认 `MarketSavedData.getOpenOrdersForSourceDock(BlockPos)` 可用（已存在，见基线）。`order.sourceDockPos()` 访问器实现时确认。

- [ ] **Step 6：运行确认通过 + 编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.QueueContractTest"`
Run: `./gradlew compileJava`
Expected: PASS + SUCCESSFUL。

- [ ] **Step 7：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java src/test/java/com/monpai/sailboatmod/market/QueueContractTest.java
git commit -m "feat(market): explicit seller-ship queue position + rough ETA on orders"
```

---

### Task D2：overview 包编解码补 `OrderEntry` 两新字段

`OrderEntry` 经 overview 网络包同步，需在编解码补两 int，否则客户端反序列化错位。

**Files:**
- Modify: overview 编解码文件（同 Task B2 定位处，`OrderEntry` 的 write/read 段）

- [ ] **Step 1：定位 `OrderEntry` 编解码**

在 Task B2 定位的同一 codec 文件里，找 `OrderEntry` 的 write（写 `orderId/label/sourceDockName/targetDockName/quantity/status` + dispatchOptions）与对应 read。

- [ ] **Step 2：补 encode**

在 `status` 写入后、`dispatchOptions` 写入前，插入：

```java
buffer.writeVarInt(entry.queuePosition());
buffer.writeVarInt(entry.queueEtaSeconds());
```

- [ ] **Step 3：补 decode**

对应位置（读完 `status`、读 dispatchOptions 前）插入：

```java
int queuePosition = buffer.readVarInt();
int queueEtaSeconds = buffer.readVarInt();
```

并把这两个变量按新参数顺序传入 `new MarketOverviewData.OrderEntry(...)`（在 `status` 与 `dispatchOptions` 之间）。

- [ ] **Step 4：编译校验**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/network
git commit -m "feat(net): serialize order queue position + eta in overview packet"
```

---

## 阶段 E：客户端 Elementa 下单弹窗

### Task E1：新增弹窗状态字段 + `openBuyModal()`

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java`（状态字段区行 101-156；`sendBuy` 区 3442）

- [ ] **Step 1：加状态字段**

在 `MarketScreen` 状态字段区（`buyQtyValue` 附近，约行 115）加：

```java
    private boolean showBuyModal = false;
    private String buyFulfillment = "SELLER_SHIP";
    private BlockPos buyReceivingPos = null;
    private boolean modeSellerShipReachable = false;
    private boolean modeAutoPickupReachable = false;
    private boolean modeRealPickupReachable = true;
    private String probeListingId = "";
```

> 确认 `MarketScreen` 已 import `net.minecraft.core.BlockPos`；缺则补。

- [ ] **Step 2：加 `openBuyModal()`**

在 `sendBuy()`（行 3442）旁加：

```java
    private void openBuyModal() {
        if (isSelectedListingOwnedByViewer()) {
            applyNotice(Component.translatable("screen.sailboatmod.market.self_buy_denied").getString(), false);
            rebuildUi();
            return;
        }
        MarketOverviewData.ListingEntry listing = selectedListing();
        if (listing == null) {
            return;
        }
        showBuyModal = true;
        buyFulfillment = "SELLER_SHIP";
        buyReceivingPos = data.receivingWarehouseOptions().isEmpty()
                ? null : data.receivingWarehouseOptions().get(0).pos();
        modeSellerShipReachable = false;
        modeAutoPickupReachable = false;
        modeRealPickupReachable = true;
        probeListingId = listing.listingId();
        requestProbe();
        rebuildUi();
    }

    private void requestProbe() {
        MarketOverviewData.ListingEntry listing = selectedListing();
        if (listing == null) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new ProbeFulfillmentModesPacket(
                data.marketPos(),
                listing.listingId(),
                buyReceivingPos != null ? buyReceivingPos : BlockPos.ZERO));
    }
```

> 确认 import `com.monpai.sailboatmod.network.packet.ProbeFulfillmentModesPacket`。

- [ ] **Step 3：编译校验**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java
git commit -m "feat(client): buy modal state + openBuyModal + probe request"
```

---

### Task E2：footer buy 按钮改为开弹窗 + 5-arg `sendBuy`

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java`（行 897 触发点；`sendBuy` 行 3442-3458）
- Test: `src/test/java/com/monpai/sailboatmod/market/BuyModalContractTest.java`（新建）

- [ ] **Step 1：写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/BuyModalContractTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuyModalContractTest {
    private static String marketScreen() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java"));
    }

    @Test
    void sendBuyUsesFiveArgPacket() throws Exception {
        String src = marketScreen();
        int idx = src.indexOf("private void sendBuy()");
        int end = src.indexOf("\n    }", idx);
        String body = src.substring(idx, end);
        assertTrue(body.contains("buyFulfillment"),
                "sendBuy should pass selected fulfillment mode");
        assertTrue(body.contains("buyReceivingPos"),
                "sendBuy should pass chosen receiving warehouse pos");
    }

    @Test
    void footerOpensModal() throws Exception {
        String src = marketScreen();
        assertTrue(src.contains("openBuyModal()"),
                "footer buy should open the modal, not send directly");
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.BuyModalContractTest"`
Expected: FAIL（两断言）。

- [ ] **Step 3：改触发点**

把行 897 的 `sendBuy();` 改为 `openBuyModal();`。

- [ ] **Step 4：改 `sendBuy` 为 5-arg**

把 `sendBuy()`（行 3452-3457 的发包）改为：

```java
        rememberScrollState();
        ModNetwork.CHANNEL.sendToServer(new PurchaseMarketListingPacket(
                data.marketPos(),
                listing.listingId(),
                parsePositive(buyQtyValue, 1),
                buyFulfillment,
                buyReceivingPos != null ? buyReceivingPos : BlockPos.ZERO));
        showBuyModal = false;
```

- [ ] **Step 5：运行确认通过 + 编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.BuyModalContractTest"`
Run: `./gradlew compileJava`
Expected: PASS + SUCCESSFUL。

- [ ] **Step 6：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java src/test/java/com/monpai/sailboatmod/market/BuyModalContractTest.java
git commit -m "feat(client): footer opens buy modal; sendBuy passes mode + warehouse"
```

---

### Task E3：渲染弹窗（模式三选一 + 收货下拉）+ 回包刷新 + ESC

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java`（`rebuildUi` 行 362 / overlay 行 390；`handleEscape` 行 326；`onProbeResult` 桩）

- [ ] **Step 1：实现 `onProbeResult`**

把 Task C2 的空桩替换为：

```java
    public void onProbeResult(String listingId, boolean sellerShip, boolean autoPickup, boolean realPickup) {
        if (!showBuyModal || listingId == null || !listingId.equals(probeListingId)) {
            return;
        }
        modeSellerShipReachable = sellerShip;
        modeAutoPickupReachable = autoPickup;
        modeRealPickupReachable = realPickup;
        rebuildUi();
    }
```

- [ ] **Step 2：渲染弹窗层**

在 `rebuildUi()` 主面板构建后、`overlay` 之上加 `if (showBuyModal) { buildBuyModal(); }`。新增 `buildBuyModal()`，用既有 helper（`createPanel`/`createText`/`createInput`/`createDropdownOption`/`createButton`，签名见 memory `sailboatmod-client-ui-elementa`）：

```java
    private void buildBuyModal() {
        // 全屏半透明遮罩吃背景点击
        UIBlock scrim = new UIBlock(new java.awt.Color(0, 0, 0, 140));
        scrim.setX(new PixelConstraint(0)).setY(new PixelConstraint(0))
                .setWidth(new RelativeConstraint(1f)).setHeight(new RelativeConstraint(1f))
                .setChildOf(getWindow());
        scrim.onMouseClick((c, e) -> Unit.INSTANCE);

        MarketOverviewData.ListingEntry listing = selectedListing();
        UIRoundedRectangle card = createPanel(scrim, /*x*/0, /*y*/0, 460, 320, 14, PANEL_COLOR);
        // 居中（用既有居中约束写法；实现时照本文件其它居中面板的 CenterConstraint）
        int y = 16;
        createText(card, 20, y, Component.translatable("screen.sailboatmod.market.buy.modal.title").getString(), 1.2f, TEXT_COLOR);
        y += 28;
        if (listing != null) {
            createText(card, 20, y, listing.itemName() + "  x" + parsePositive(buyQtyValue, 1), 1.0f, MUTED_COLOR);
            y += 24;
        }
        // 数量输入
        createInput(card, 20, y, 120, 20, /*…按既有 createInput 签名…*/);
        y += 30;
        // 运输模式三选一
        boolean canReceive = data.canChooseReceiving();
        y = buildModeRow(card, y, "SELLER_SHIP", "screen.sailboatmod.market.buy.modal.mode.seller_ship", canReceive && modeSellerShipReachable, canReceive);
        y = buildModeRow(card, y, "AUTO_PICKUP", "screen.sailboatmod.market.buy.modal.mode.auto_pickup", canReceive && modeAutoPickupReachable, canReceive);
        y = buildModeRow(card, y, "REAL_PICKUP", "screen.sailboatmod.market.buy.modal.mode.real_pickup", modeRealPickupReachable, true);
        // 收货下拉（非真人自提才显示）
        if (!"REAL_PICKUP".equals(buyFulfillment)) {
            if (data.receivingWarehouseOptions().isEmpty()) {
                createText(card, 20, y, Component.translatable("screen.sailboatmod.market.buy.modal.receiving.none").getString(), 0.9f, WARNING_COLOR);
                y += 22;
            } else {
                for (MarketOverviewData.WarehouseOption opt : data.receivingWarehouseOptions()) {
                    boolean sel = opt.pos().equals(buyReceivingPos);
                    createDropdownOption(card, 20, y, 420, opt.displayName(), sel, () -> {
                        buyReceivingPos = opt.pos();
                        requestProbe();
                        runPreservingScroll(this::rebuildUi);
                        return Unit.INSTANCE;
                    });
                    y += 22;
                }
            }
        }
        // 取消 / 确认
        createButton(card, 20, 280, 120, 24, Component.translatable("screen.sailboatmod.market.buy.modal.cancel").getString(), true, false, () -> {
            showBuyModal = false;
            rebuildUi();
            return Unit.INSTANCE;
        });
        boolean confirmEnabled = "REAL_PICKUP".equals(buyFulfillment) || canReceive;
        createButton(card, 320, 280, 120, 24, Component.translatable("screen.sailboatmod.market.buy.modal.confirm").getString(), confirmEnabled, true, () -> {
            sendBuy();
            return Unit.INSTANCE;
        });
    }

    private int buildModeRow(UIRoundedRectangle card, int y, String mode, String labelKey, boolean reachable, boolean hasWarehouse) {
        boolean isPickup = "REAL_PICKUP".equals(mode);
        boolean enabled = isPickup ? reachable : (hasWarehouse && reachable);
        boolean selected = mode.equals(buyFulfillment);
        createDropdownOption(card, 20, y, 420, Component.translatable(labelKey).getString(), selected, () -> {
            if (enabled) {
                buyFulfillment = mode;
                runPreservingScroll(this::rebuildUi);
            }
            return Unit.INSTANCE;
        });
        if (!enabled && !isPickup) {
            String reasonKey = !hasWarehouse
                    ? "screen.sailboatmod.market.buy.modal.mode.need_warehouse"
                    : "screen.sailboatmod.market.buy.modal.mode.unreachable";
            createText(card, 360, y + 4, Component.translatable(reasonKey).getString(), 0.7f, WARNING_COLOR);
        }
        return y + 24;
    }
```

> 这是**结构示意**，实现时务必：(1) 按本文件真实的 `createPanel`/`createInput`/`createDropdownOption`/`createButton`/`createText` 形参顺序与返回类型逐一对齐（grep 各 helper 定义行：createPanel 4069、createText 4098、createInput 4156、createButton 4180、createDropdownOption 1444）；(2) 颜色常量 `PANEL_COLOR`/`TEXT_COLOR`/`MUTED_COLOR`/`WARNING_COLOR` 用本文件已定义的真实常量名（grep `static final.*Color` 确认，名不符则替换）；(3) 居中用本文件其它弹层/面板已有的 `CenterConstraint` 写法；(4) 数量 `createInput` 绑定 `buyQtyValue` 的回调照既有数量输入写法。**不得照抄占位**。

- [ ] **Step 3：ESC 优先关弹窗**

`handleEscape()`（行 326）最前面加：

```java
        if (showBuyModal) {
            showBuyModal = false;
            rebuildUi();
            return true;
        }
```

> 按 `handleEscape` 真实返回类型调整（若返回 void 则去掉 `true` 并 `return;`；grep 其签名确认）。

- [ ] **Step 4：编译校验 + 客户端冒烟**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL。
（运行期验证留到阶段 G 的手测清单；此处仅保证编译通过。）

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java
git commit -m "feat(client): render buy modal with mode selector, receiving dropdown, ESC, probe refresh"
```

---

### Task E4：客户端「我的订单」排队展示

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java`（渲染订单列表处）

- [ ] **Step 1：定位订单行渲染**

Run: `grep -n "orderEntries()\|OrderEntry" src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java`（实现时执行）找到渲染「我的订单/订单行」的循环。

- [ ] **Step 2：追加排队文案**

在该订单行 `createText` 之后，加：

```java
        if (order.queuePosition() > 0) {
            int mins = Math.max(1, order.queueEtaSeconds() / 60);
            createText(rowParent, qx, qy,
                    Component.translatable("screen.sailboatmod.market.buy.modal.queue.position",
                            order.queuePosition(), mins).getString(),
                    0.8f, MUTED_COLOR);
        }
```

> `rowParent`/`qx`/`qy`/颜色常量按该循环真实变量名与布局对齐。

- [ ] **Step 3：编译校验**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java
git commit -m "feat(client): show seller-ship queue position + eta in my orders"
```

---

## 阶段 F：网页端下单弹窗

### Task F1：purchase 按钮改开弹窗 + modal 组件 + 探测刷新

**Files:**
- Modify: `src/main/resources/marketweb/app.js`（purchase 按钮 click 约行 1655-1660；新建 `openPurchaseModal`）
- Test: `src/test/java/com/monpai/sailboatmod/market/WebFrontendContractTest.java`（新建，断言 app.js 文本契约）

- [ ] **Step 1：写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/WebFrontendContractTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFrontendContractTest {
    private static String appJs() throws Exception {
        return Files.readString(Path.of("src/main/resources/marketweb/app.js"));
    }

    @Test
    void hasPurchaseModalAndProbe() throws Exception {
        String src = appJs();
        assertTrue(src.contains("openPurchaseModal"),
                "app.js should define openPurchaseModal");
        assertTrue(src.contains("/probe-modes"),
                "app.js should POST /probe-modes for mode reachability");
        assertTrue(src.contains("fulfillment") && src.contains("targetWarehouse"),
                "purchase POST should send fulfillment + targetWarehouse");
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.WebFrontendContractTest"`
Expected: FAIL。

- [ ] **Step 3：改 purchase 按钮 + 加 modal**

`app.js`：把 purchase 按钮 click（约行 1655，现直发 `postMarketAction("/purchase", {listingIndex, quantity})`）改为 `openPurchaseModal(listingIndex)`。新增（沿用现有 `escapeHtml`/`t()`/`number()`/`postMarketAction`/`state` 习惯）：

```javascript
function openPurchaseModal(listingIndex) {
  const listing = (state.overview && state.overview.listings || [])[listingIndex];
  if (!listing) return;
  const options = (state.overview && state.overview.receivingWarehouseOptions) || [];
  const canReceive = !!(state.overview && state.overview.canChooseReceiving);
  let fulfillment = 'SELLER_SHIP';
  let targetWarehouse = options.length ? options[0].pos : '';
  const modes = { sellerShip: false, autoPickup: false, realPickup: true };

  const scrim = document.createElement('div');
  scrim.className = 'purchase-modal-scrim';
  scrim.innerHTML = renderPurchaseModal(listing, options, canReceive, fulfillment, targetWarehouse, modes);
  document.body.appendChild(scrim);

  function close() { scrim.remove(); }
  function refresh() {
    scrim.querySelector('.purchase-modal-card').outerHTML =
        renderPurchaseModalCard(listing, options, canReceive, fulfillment, targetWarehouse, modes);
    bind();
  }
  async function probe() {
    try {
      const res = await postMarketAction('/probe-modes', { listingIndex, targetWarehouse });
      if (res && typeof res === 'object') {
        modes.sellerShip = !!res.sellerShip;
        modes.autoPickup = !!res.autoPickup;
        modes.realPickup = res.realPickup !== false;
        refresh();
      }
    } catch (e) { /* 探测失败保持全置灰，不阻断 ① */ }
  }
  function bind() {
    scrim.addEventListener('click', (e) => { if (e.target === scrim) close(); });
    scrim.querySelectorAll('[data-mode]').forEach(el => el.addEventListener('click', () => {
      if (el.getAttribute('data-disabled') === '1') return;
      fulfillment = el.getAttribute('data-mode');
      refresh();
    }));
    const sel = scrim.querySelector('[data-receiving]');
    if (sel) sel.addEventListener('change', () => { targetWarehouse = sel.value; probe(); });
    const cancel = scrim.querySelector('[data-cancel]');
    if (cancel) cancel.addEventListener('click', close);
    const confirm = scrim.querySelector('[data-confirm]');
    if (confirm) confirm.addEventListener('click', async () => {
      const qty = parseInt(scrim.querySelector('[data-qty]').value, 10) || 1;
      const ok = await postMarketAction('/purchase', { listingIndex, quantity: qty, fulfillment, targetWarehouse });
      if (ok) close();
    });
  }
  bind();
  probe();
}
```

> `renderPurchaseModal`/`renderPurchaseModalCard` 在 Step 4 定义。`postMarketAction` 现有签名实现时确认：若它只返回 boolean，则 `/probe-modes` 需另用底层 `fetch`（grep `async function postMarketAction` 看其实现，照其 fetch 模式写一个返回 JSON 的 `postJson`）。务必按真实 `postMarketAction` 行为接，prob 端点要拿到 JSON body。

- [ ] **Step 4：加渲染函数**

```javascript
function renderPurchaseModal(listing, options, canReceive, fulfillment, targetWarehouse, modes) {
  return '<div class="purchase-modal-card">' +
      renderPurchaseModalInner(listing, options, canReceive, fulfillment, targetWarehouse, modes) +
      '</div>';
}
function renderPurchaseModalCard(listing, options, canReceive, fulfillment, targetWarehouse, modes) {
  return renderPurchaseModal(listing, options, canReceive, fulfillment, targetWarehouse, modes);
}
function renderPurchaseModalInner(listing, options, canReceive, fulfillment, targetWarehouse, modes) {
  const row = (mode, key, reachable) => {
    const disabled = mode === 'REAL_PICKUP' ? !reachable : !(canReceive && reachable);
    const reason = !canReceive ? t('mode_need_warehouse') : t('mode_unreachable');
    return '<div class="purchase-modal-mode' + (fulfillment === mode ? ' is-selected' : '') +
        (disabled ? ' is-disabled' : '') + '" data-mode="' + mode + '" data-disabled="' + (disabled ? '1' : '0') + '">' +
        '<span>' + escapeHtml(t(key)) + '</span>' +
        (disabled && mode !== 'REAL_PICKUP' ? '<small>' + escapeHtml(reason) + '</small>' : '') +
        '</div>';
  };
  let html = '<h3>' + escapeHtml(t('buy_modal_title')) + '</h3>';
  html += '<div class="purchase-modal-item">' + escapeHtml(listing.itemName || '') + ' · ' + number(listing.unitPrice || 0) + '</div>';
  html += '<input class="purchase-modal-qty" data-qty type="number" min="1" value="' +
      ((state.settings && state.settings.defaultPurchaseQuantity) || 1) + '">';
  html += row('SELLER_SHIP', 'mode_seller_ship', modes.sellerShip);
  html += row('AUTO_PICKUP', 'mode_auto_pickup', modes.autoPickup);
  html += row('REAL_PICKUP', 'mode_real_pickup', modes.realPickup);
  if (fulfillment !== 'REAL_PICKUP') {
    if (!options.length) {
      html += '<div class="purchase-modal-warning">' + escapeHtml(t('receiving_none')) + '</div>';
    } else {
      html += '<label class="purchase-modal-label">' + escapeHtml(t('receiving_label')) + '</label>';
      html += '<select class="purchase-modal-receiving" data-receiving>' +
          options.map(o => '<option value="' + escapeHtml(o.pos) + '"' +
              (o.pos === targetWarehouse ? ' selected' : '') + '>' +
              escapeHtml(o.displayName + (o.townName ? ' · ' + o.townName : '')) + '</option>').join('') +
          '</select>';
    }
  }
  html += '<div class="purchase-modal-actions">' +
      '<button class="secondary" data-cancel>' + escapeHtml(t('cancel')) + '</button>' +
      '<button data-confirm>' + escapeHtml(t('confirm')) + '</button></div>';
  return html;
}
```

> `state.overview` / `state.settings` / `listing.itemName` / `listing.unitPrice` 字段名实现时按 app.js 真实 overview 结构核对（grep 现有 listing 渲染处的字段名，如 `unitPrice`/`itemName` 真实拼写）。

- [ ] **Step 5：运行确认通过**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.WebFrontendContractTest"`
Expected: PASS。

- [ ] **Step 6：提交**

```bash
git add src/main/resources/marketweb/app.js src/test/java/com/monpai/sailboatmod/market/WebFrontendContractTest.java
git commit -m "feat(web): purchase modal with mode selector + receiving dropdown + probe"
```

---

### Task F2：网页弹窗 CSS（复用现有设计令牌）

**Files:**
- Modify: `src/main/resources/marketweb/app.css`（新增 `.purchase-modal-*` 类）

- [ ] **Step 1：核对设计令牌**

Run: `grep -n -- "--accent\|--panel\|--radius-lg\|--shadow\|--line-strong\|--muted\|--text\|--warning" src/main/resources/marketweb/app.css`（实现时执行）确认真实变量名（spec 视觉规格表假设 `--accent`/`--radius-lg`/`--shadow`/`--line-strong`/`--panel`/`--muted`/`--text`/`--warning`；名不符则用真实名）。

- [ ] **Step 2：加样式**

按 spec §4.3 视觉规格表，追加（变量名以 Step 1 实测为准）：

```css
.purchase-modal-scrim {
  position: fixed; inset: 0; z-index: 1000;
  display: flex; align-items: center; justify-content: center;
  background: rgba(15, 20, 27, 0.42);
  backdrop-filter: blur(8px) saturate(1.1);
  animation: purchaseModalFade 120ms ease-out;
}
@keyframes purchaseModalFade { from { opacity: 0; } to { opacity: 1; } }
.purchase-modal-card {
  width: min(460px, calc(100vw - 32px));
  background: var(--panel);
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  padding: 22px 24px;
  animation: purchaseModalRise 140ms ease-out;
}
@keyframes purchaseModalRise { from { transform: translateY(8px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
.purchase-modal-card h3 { margin: 0 0 6px; color: var(--text); border-left: 3px solid var(--accent); padding-left: 10px; }
.purchase-modal-item { color: var(--muted); font-variant-numeric: tabular-nums; margin-bottom: 14px; }
.purchase-modal-qty { width: 120px; margin-bottom: 14px; }
.purchase-modal-mode {
  display: flex; align-items: center; justify-content: space-between;
  padding: 9px 12px; margin-bottom: 6px; cursor: pointer;
  border: 1px solid transparent; border-radius: 10px;
  background: var(--panel-soft, rgba(255,255,255,0.04));
  transition: background 140ms, border-color 140ms;
}
.purchase-modal-mode.is-selected { background: var(--accent-soft, rgba(255,127,39,0.16)); border-color: var(--accent); color: var(--accent-strong, var(--accent)); }
.purchase-modal-mode.is-disabled { opacity: 0.45; cursor: not-allowed; }
.purchase-modal-mode small { color: var(--warning, #d9822b); font-size: 11px; }
.purchase-modal-label { display: block; color: var(--muted); font-size: 12px; margin: 8px 0 4px; }
.purchase-modal-receiving { width: 100%; margin-bottom: 14px; }
.purchase-modal-warning { color: var(--warning, #d9822b); font-size: 12px; margin: 8px 0 14px; }
.purchase-modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 8px; }
```

- [ ] **Step 3：编译校验（CSS 无编译，验 jar 打包资源）**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL（CSS 不参与编译，确保 Java 侧未被波及）。

- [ ] **Step 4：提交**

```bash
git add src/main/resources/marketweb/app.css
git commit -m "style(web): purchase modal styles reusing existing design tokens"
```

---

### Task F3：网页「我的订单」排队展示

**Files:**
- Modify: `src/main/resources/marketweb/app.js`（渲染 `myOrders` 处）

- [ ] **Step 1：定位订单渲染**

Run: `grep -n "myOrders\|queuePosition" src/main/resources/marketweb/app.js`（实现时执行）找到渲染我的订单列表的函数。

- [ ] **Step 2：追加排队文案**

在订单行渲染里，状态后加：

```javascript
      (order.queuePosition > 0
        ? '<span class="order-queue">' + escapeHtml(t('queue_position')
            .replace('%1', order.queuePosition)
            .replace('%2', Math.max(1, Math.floor(order.queueEtaSeconds / 60)))) + '</span>'
        : '')
```

> `t('queue_position')` 文案含 `%1`/`%2` 占位（中文「排第 %1 位 · 约 %2 分钟」）。按 app.js 现有 `t()` 是否支持占位替换调整——若 `t()` 不支持参数，就在此处手动 `.replace`，如上。

- [ ] **Step 3：测试 + 提交**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.WebFrontendContractTest"`
Expected: PASS（不回归）。

```bash
git add src/main/resources/marketweb/app.js
git commit -m "feat(web): show seller-ship queue position + eta in my orders"
```

---

## 阶段 G：i18n + 纯逻辑测试 + 构建推送

### Task G1：客户端 lang + 网页 `t()` 文案（双语）

**Files:**
- Modify: `src/main/resources/assets/sailboatmod/lang/zh_cn.json`
- Modify: `src/main/resources/assets/sailboatmod/lang/en_us.json`
- Modify: `src/main/resources/marketweb/app.js`（`t()` 字典 zh-CN / en-US）
- Test: `src/test/java/com/monpai/sailboatmod/market/LangContractTest.java`（新建）

- [ ] **Step 1：写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/LangContractTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangContractTest {
    private static final String[] KEYS = {
        "screen.sailboatmod.market.buy.modal.title",
        "screen.sailboatmod.market.buy.modal.mode.seller_ship",
        "screen.sailboatmod.market.buy.modal.mode.auto_pickup",
        "screen.sailboatmod.market.buy.modal.mode.real_pickup",
        "screen.sailboatmod.market.buy.modal.receiving.label",
        "screen.sailboatmod.market.buy.modal.receiving.none",
        "screen.sailboatmod.market.buy.modal.mode.need_warehouse",
        "screen.sailboatmod.market.buy.modal.mode.unreachable",
        "screen.sailboatmod.market.buy.modal.queue.position",
        "screen.sailboatmod.market.buy.modal.confirm",
        "screen.sailboatmod.market.buy.modal.cancel",
    };

    @Test
    void bothLangFilesHaveAllKeys() throws Exception {
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        for (String key : KEYS) {
            assertTrue(zh.contains("\"" + key + "\""), "zh_cn.json missing " + key);
            assertTrue(en.contains("\"" + key + "\""), "en_us.json missing " + key);
        }
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.LangContractTest"`
Expected: FAIL（key 缺失）。

- [ ] **Step 3：补 zh_cn.json**

在 `zh_cn.json` 现有 `screen.sailboatmod.market.*` 块旁加（保持 JSON 合法、逗号正确）：

```json
  "screen.sailboatmod.market.buy.modal.title": "确认下单",
  "screen.sailboatmod.market.buy.modal.mode.seller_ship": "卖家发货",
  "screen.sailboatmod.market.buy.modal.mode.auto_pickup": "自动自提",
  "screen.sailboatmod.market.buy.modal.mode.real_pickup": "真人自提",
  "screen.sailboatmod.market.buy.modal.receiving.label": "收货地",
  "screen.sailboatmod.market.buy.modal.receiving.none": "无可用收货仓库",
  "screen.sailboatmod.market.buy.modal.mode.need_warehouse": "无可用收货仓库",
  "screen.sailboatmod.market.buy.modal.mode.unreachable": "无可达航线/道路",
  "screen.sailboatmod.market.buy.modal.queue.position": "排第 %s 位 · 约 %s 分钟",
  "screen.sailboatmod.market.buy.modal.confirm": "确认",
  "screen.sailboatmod.market.buy.modal.cancel": "取消",
```

- [ ] **Step 4：补 en_us.json**

```json
  "screen.sailboatmod.market.buy.modal.title": "Confirm Order",
  "screen.sailboatmod.market.buy.modal.mode.seller_ship": "Seller ships",
  "screen.sailboatmod.market.buy.modal.mode.auto_pickup": "Auto pickup",
  "screen.sailboatmod.market.buy.modal.mode.real_pickup": "Self pickup",
  "screen.sailboatmod.market.buy.modal.receiving.label": "Receiving",
  "screen.sailboatmod.market.buy.modal.receiving.none": "No usable warehouse",
  "screen.sailboatmod.market.buy.modal.mode.need_warehouse": "No usable warehouse",
  "screen.sailboatmod.market.buy.modal.mode.unreachable": "No reachable lane/road",
  "screen.sailboatmod.market.buy.modal.queue.position": "Queue #%s · ~%s min",
  "screen.sailboatmod.market.buy.modal.confirm": "Confirm",
  "screen.sailboatmod.market.buy.modal.cancel": "Cancel",
```

- [ ] **Step 5：补 app.js `t()` 字典**

`grep -n "zh-CN\|zhCN\|en-US\|enUS\|const I18N\|translations" src/main/resources/marketweb/app.js`（实现时执行）定位 `t()` 字典两语言块，各加：

```javascript
// zh-CN
buy_modal_title: '确认下单',
mode_seller_ship: '卖家发货',
mode_auto_pickup: '自动自提',
mode_real_pickup: '真人自提',
receiving_label: '收货地',
receiving_none: '无可用收货仓库',
mode_need_warehouse: '无可用收货仓库',
mode_unreachable: '无可达航线/道路',
queue_position: '排第 %1 位 · 约 %2 分钟',
confirm: '确认',
cancel: '取消',
// en-US
buy_modal_title: 'Confirm Order',
mode_seller_ship: 'Seller ships',
mode_auto_pickup: 'Auto pickup',
mode_real_pickup: 'Self pickup',
receiving_label: 'Receiving',
receiving_none: 'No usable warehouse',
mode_need_warehouse: 'No usable warehouse',
mode_unreachable: 'No reachable lane/road',
queue_position: 'Queue #%1 · ~%2 min',
confirm: 'Confirm',
cancel: 'Cancel',
```

> 若 `confirm`/`cancel` 已存在则复用、勿重复键。

- [ ] **Step 6：运行确认通过**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.LangContractTest"`
Expected: PASS。

- [ ] **Step 7：提交**

```bash
git add src/main/resources/assets/sailboatmod/lang/zh_cn.json src/main/resources/assets/sailboatmod/lang/en_us.json src/main/resources/marketweb/app.js src/test/java/com/monpai/sailboatmod/market/LangContractTest.java
git commit -m "i18n: buy modal strings (client lang + web dict, bilingual)"
```

---

### Task G2：纯逻辑单测（收货收窄 / 目的地解析 / 排队位次）

源码契约断言已覆盖结构；本任务补可脱 MC 运行时的纯逻辑断言。`receivingWarehouseOptionsForViewer`/`resolveBuyerTargetWarehouse`/`probeFulfillmentModes` 依赖 `level`，难以纯单测——把**排队位次**这段纯算法抽成静态工具再单测它（最有价值、零 MC 依赖）。

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/SellerShipQueue.java`（抽出位次/ETA 纯函数）
- Modify: `MarketBlockEntity` + `MarketWebService` 改调用该工具（消除 Task D1 的重复逻辑，DRY）
- Test: `src/test/java/com/monpai/sailboatmod/market/SellerShipQueueTest.java`（新建）

- [ ] **Step 1：写失败的纯逻辑测试**

新建 `src/test/java/com/monpai/sailboatmod/market/SellerShipQueueTest.java`：

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SellerShipQueueTest {
    @Test
    void positionIsOneBasedAndStable() {
        List<String> queue = List.of("ord-1", "ord-2", "ord-3");
        assertEquals(1, SellerShipQueue.positionOf(queue, "ord-1"));
        assertEquals(2, SellerShipQueue.positionOf(queue, "ord-2"));
        assertEquals(0, SellerShipQueue.positionOf(queue, "absent"));
    }

    @Test
    void etaScalesWithPosition() {
        assertEquals(0, SellerShipQueue.etaSeconds(0));
        assertEquals(60, SellerShipQueue.etaSeconds(1));
        assertEquals(180, SellerShipQueue.etaSeconds(3));
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.SellerShipQueueTest"`
Expected: FAIL（类不存在）。

- [ ] **Step 3：实现工具类**

`src/main/java/com/monpai/sailboatmod/market/SellerShipQueue.java`：

```java
package com.monpai.sailboatmod.market;

import java.util.List;

/** 卖家发货排队的纯算法：位次（1-based，缺席 0）与粗估 ETA。无 MC 依赖，便于单测。 */
public final class SellerShipQueue {
    public static final int AVG_DISPATCH_SECONDS_PER_ORDER = 60;

    private SellerShipQueue() {
    }

    public static int positionOf(List<String> orderedQueueIds, String orderId) {
        if (orderedQueueIds == null || orderId == null) {
            return 0;
        }
        int idx = orderedQueueIds.indexOf(orderId);
        return idx < 0 ? 0 : idx + 1;
    }

    public static int etaSeconds(int position) {
        return position <= 0 ? 0 : position * AVG_DISPATCH_SECONDS_PER_ORDER;
    }
}
```

- [ ] **Step 4：运行确认通过**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.SellerShipQueueTest"`
Expected: PASS。

- [ ] **Step 5：DRY —— 让 D1 两处改用此工具**

把 Task D1 在 `MarketBlockEntity` 与 `MarketWebService.myOrders` 内联的位次/ETA 计算，替换为先构建有序 `List<String>`（按 `orderId` 排序的卖家发货待发单 id），再 `SellerShipQueue.positionOf(ids, order.orderId())` + `SellerShipQueue.etaSeconds(pos)`。删除内联的 `* 60` 魔数与重复排序逻辑。

- [ ] **Step 6：运行全部新测 + 编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.*"`
Run: `./gradlew compileJava`
Expected: 全 PASS + SUCCESSFUL。

- [ ] **Step 7：提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/SellerShipQueue.java src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java src/test/java/com/monpai/sailboatmod/market/SellerShipQueueTest.java
git commit -m "refactor(market): extract SellerShipQueue pure helper + unit tests (DRY)"
```

---

### Task G3：全量构建 + 推送

**Files:** 无新增；产出 jar + 推送。

- [ ] **Step 1：跑全部 Phase 5 新测**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.ReceivingWarehouseContractTest" --tests "com.monpai.sailboatmod.market.MarketWebContractTest" --tests "com.monpai.sailboatmod.market.ProbeWiringContractTest" --tests "com.monpai.sailboatmod.market.QueueContractTest" --tests "com.monpai.sailboatmod.market.BuyModalContractTest" --tests "com.monpai.sailboatmod.market.WebFrontendContractTest" --tests "com.monpai.sailboatmod.market.LangContractTest" --tests "com.monpai.sailboatmod.market.SellerShipQueueTest" --tests "com.monpai.sailboatmod.market.PickupWiringContractTest"`
Expected: 全 PASS。

- [ ] **Step 2：出 jar**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL；产物 `build/libs/sailboatmod-1.3.9-reobf.jar`。

> 完整 `./gradlew build` 会因 6 个无关 web map 测试中断——本期用 `-x test` 出 jar，新测已在 Step 1 单独验过。

- [ ] **Step 3：提交剩余 + 推送**

```bash
git add -A
git commit -m "build: P2P freight Phase 5 buy modal jar" || echo "nothing to commit"
GH_TOKEN=$(gh auth token)
REPO_PATH=$(git remote get-url origin | sed -E 's#https://([^/]*@)?github.com/##; s#\.git$##')
BRANCH=$(git rev-parse --abbrev-ref HEAD)
git -c http.proxy=http://127.0.0.1:7897 -c http.sslBackend=openssl push "https://x-access-token:${GH_TOKEN}@github.com/${REPO_PATH}.git" "$BRANCH"
```

Expected: 推送成功（见 memory `sailboatmod-push`：必须代理 7897 + openssl 后端 + token 内嵌 URL）。

---

## 手测清单（实现后人工验，对应 spec §5.3）

- [ ] A 在 B 店买货 → ③卖家发货 + 选收货仓 → 货到 A 的收货仓（核心缺口验证）。
- [ ] ②自动自提：空车去源仓 → 装货 → 回收货仓。
- [ ] ①真人自提：货物锁定，等真人驾车装载。
- [ ] 切换收货仓 → 模式可选性实时刷新；选到无可达航线/道路的仓 → ②③ 置灰提示，①仍可选。
- [ ] ③卖家发货且卖家无空闲车 → 订单进队列，「我的订单」显示「排第 N 位 · 约 X 分钟」；卖家车空出 → 自动派发、位次消失。
- [ ] 马车走陆路、帆船走水路各验一遍。
- [ ] 钱货结算：A 付款、B 收款、货入 A 的仓库。
- [ ] 网页弹窗亮/暗主题各看一遍，视觉与现有界面一致。

## 范围边界（YAGNI，对应 spec）

- 不改网络包编解码核心、不改后端三模式核心逻辑与现有调度/可达性算法（只新增探测/排队的查询与展示包装）。
- 不做收货仓跨 town / 跨 nation 选择——本期仅当前 town 可写入仓库。
- 自动自提载具不让玩家手选站点/车辆——后端自动分流。
- 排队可视化仅 ③卖家发货；②自动自提不显示位次/ETA。
- ETA 为粗估（位次 × 单轮均耗常量），不做按实际路程精算。
- 网页弹窗复用现有设计令牌，不发明新视觉体系。
- 不引入 UI 自动化测试框架（沿用源码契约 + 纯逻辑单测）。
