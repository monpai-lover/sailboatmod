# 运输派发重设计 — 第一阶段：数据模型基础 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为运输派发重设计打地基——新增 `TransportMode`/`Fulfillment`/`PickupMode` 枚举、给 `PurchaseOrder` 加履约方字段（NBT 向后兼容）、扩展 `MarketOverviewData`（OrderEntry/DispatchOption）并同步 packet 序列化，全程不改调度/执行/UI 逻辑。

**Architecture:** 所有 record 通过"保留旧参数构造器委托到新 canonical 构造器"实现向后兼容；枚举一律 `name()` 持久化 + `fromName` 容错解析；NBT/packet 缺字段时落默认值。地基阶段生产端（buildOverview）暂传默认值，真实填充留给后续阶段。

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5 (`org.junit.jupiter`, `./gradlew test`)。NBT 测试用裸 `CompoundTag`/`BlockPos`，无需 bootstrap Minecraft（参照 `RoadNetworkRecordTest`）。

**Spec:** `docs/superpowers/specs/2026-06-14-transport-dispatch-redesign-design.md`

---

## File Structure

新建（`src/main/java/com/monpai/sailboatmod/market/`）：
- `TransportMode.java` — LAND/WATER 枚举，抽象终端类型与（未来）路由服务选择
- `Fulfillment.java` — SELLER_SHIP/BUYER_PICKUP 枚举
- `PickupMode.java` — SYSTEM/MANUAL 枚举

修改：
- `market/PurchaseOrder.java` — 加 2 枚举字段 + 状态常量 + 11 参兼容构造器 + `withStatus` + NBT 向后兼容
- `market/MarketOverviewData.java` — OrderEntry 加 3 字段、DispatchOption 加 3 字段，各保留旧构造器
- `network/packet/OpenMarketScreenPacket.java` — write/read OrderEntries & DispatchOptions 同步新字段
- `block/entity/MarketBlockEntity.java` / `block/entity/DockBlockEntity.java` / `entity/SailboatEntity.java` — 12 个"复制订单改状态"调用点改用 `withStatus`/手填枚举，防履约信息丢失

测试（`src/test/java/com/monpai/sailboatmod/market/`）：
- `TransportModeTest.java`、`FulfillmentTest.java`、`PickupModeTest.java`、`PurchaseOrderTest.java`
- 扩展 `network/packet/OpenMarketScreenPacketTest.java`

---

## Task 1: TransportMode 枚举

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/TransportMode.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/TransportModeTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransportModeTest {
    @Test void fromNameTolerant() {
        assertEquals(TransportMode.LAND, TransportMode.fromName("land"));
        assertEquals(TransportMode.WATER, TransportMode.fromName("WATER"));
        assertEquals(TransportMode.DEFAULT, TransportMode.fromName(""));
        assertEquals(TransportMode.DEFAULT, TransportMode.fromName("garbage"));
        assertEquals(TransportMode.DEFAULT, TransportMode.fromName(null));
    }
    @Test void terminalKindMapping() {
        assertEquals(TransportTerminalKind.POST_STATION, TransportMode.LAND.terminalKind());
        assertEquals(TransportTerminalKind.PORT, TransportMode.WATER.terminalKind());
    }
    @Test void fromTerminalKind() {
        assertEquals(TransportMode.WATER, TransportMode.fromTerminalKind(TransportTerminalKind.PORT));
        assertEquals(TransportMode.LAND, TransportMode.fromTerminalKind(TransportTerminalKind.POST_STATION));
        assertNull(TransportMode.fromTerminalKind(TransportTerminalKind.AUTO));
        assertNull(TransportMode.fromTerminalKind(null));
    }
    @Test void routeServiceKey() {
        assertEquals("land", TransportMode.LAND.routeServiceKey());
        assertEquals("water", TransportMode.WATER.routeServiceKey());
    }
}
```

- [ ] **Step 2: 验证失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.TransportModeTest"`
Expected: 编译失败 / FAIL（`TransportMode` 不存在）

- [ ] **Step 3: 最小实现**

创建 `TransportMode.java`：

```java
package com.monpai.sailboatmod.market;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * 运输介质。第一阶段骨架：抽象终端类型与（未来的）路由服务选择。
 * 持久化/序列化一律用 {@link #name()}，绝不用 ordinal。
 */
public enum TransportMode {
    LAND,
    WATER;

    /** 存储值缺失/空白/无法识别时的默认（沿用旧 "PORT" 水运默认语义）。 */
    public static final TransportMode DEFAULT = WATER;

    public static TransportMode fromName(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        try {
            return TransportMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }

    public TransportTerminalKind terminalKind() {
        return this == LAND ? TransportTerminalKind.POST_STATION : TransportTerminalKind.PORT;
    }

    @Nullable
    public static TransportMode fromTerminalKind(@Nullable TransportTerminalKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case PORT -> WATER;
            case POST_STATION -> LAND;
            case AUTO -> null;
        };
    }

    /** 路由服务选择键。第一阶段只返回稳定字符串，不接入实际服务。 */
    public String routeServiceKey() {
        return this == LAND ? "land" : "water";
    }
}
```

> 落地前确认 `TransportTerminalKind` 的常量名确为 `PORT`/`POST_STATION`/`AUTO`（读 `market/TransportTerminalKind.java`）。若 switch 报"未覆盖所有值"，按实际枚举值补全。

- [ ] **Step 4: 验证通过**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.TransportModeTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/TransportMode.java src/test/java/com/monpai/sailboatmod/market/TransportModeTest.java
git commit -m "feat(market): add TransportMode enum (LAND/WATER) for dispatch abstraction"
```

---

## Task 2: Fulfillment / PickupMode 枚举

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/market/Fulfillment.java`
- Create: `src/main/java/com/monpai/sailboatmod/market/PickupMode.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/FulfillmentTest.java`, `.../PickupModeTest.java`

- [ ] **Step 1: 写失败测试**

`FulfillmentTest.java`:
```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FulfillmentTest {
    @Test void defaults() {
        assertEquals(Fulfillment.SELLER_SHIP, Fulfillment.DEFAULT);
        assertEquals(Fulfillment.SELLER_SHIP, Fulfillment.fromName(null));
        assertEquals(Fulfillment.SELLER_SHIP, Fulfillment.fromName(""));
        assertEquals(Fulfillment.SELLER_SHIP, Fulfillment.fromName("nonsense"));
    }
    @Test void parse() {
        assertEquals(Fulfillment.BUYER_PICKUP, Fulfillment.fromName("buyer_pickup"));
        assertEquals(Fulfillment.SELLER_SHIP, Fulfillment.fromName("SELLER_SHIP"));
    }
}
```

`PickupModeTest.java`:
```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PickupModeTest {
    @Test void defaults() {
        assertEquals(PickupMode.SYSTEM, PickupMode.DEFAULT);
        assertEquals(PickupMode.SYSTEM, PickupMode.fromName(null));
        assertEquals(PickupMode.SYSTEM, PickupMode.fromName("garbage"));
    }
    @Test void parse() {
        assertEquals(PickupMode.MANUAL, PickupMode.fromName("manual"));
        assertEquals(PickupMode.SYSTEM, PickupMode.fromName("SYSTEM"));
    }
}
```

- [ ] **Step 2: 验证失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.FulfillmentTest" --tests "com.monpai.sailboatmod.market.PickupModeTest"`
Expected: 编译失败（枚举不存在）

- [ ] **Step 3: 最小实现**

`Fulfillment.java`:
```java
package com.monpai.sailboatmod.market;

import java.util.Locale;
import javax.annotation.Nullable;

public enum Fulfillment {
    SELLER_SHIP,
    BUYER_PICKUP;

    public static final Fulfillment DEFAULT = SELLER_SHIP;

    public static Fulfillment fromName(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        try {
            return Fulfillment.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }
}
```

`PickupMode.java`:
```java
package com.monpai.sailboatmod.market;

import java.util.Locale;
import javax.annotation.Nullable;

public enum PickupMode {
    SYSTEM,
    MANUAL;

    public static final PickupMode DEFAULT = SYSTEM;

    public static PickupMode fromName(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        try {
            return PickupMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }
}
```

- [ ] **Step 4: 验证通过**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.FulfillmentTest" --tests "com.monpai.sailboatmod.market.PickupModeTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/Fulfillment.java src/main/java/com/monpai/sailboatmod/market/PickupMode.java src/test/java/com/monpai/sailboatmod/market/FulfillmentTest.java src/test/java/com/monpai/sailboatmod/market/PickupModeTest.java
git commit -m "feat(market): add Fulfillment and PickupMode enums"
```

---

## Task 3: PurchaseOrder 加字段 + 兼容构造器 + NBT 向后兼容

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/PurchaseOrder.java`（整文件替换）
- Test: `src/test/java/com/monpai/sailboatmod/market/PurchaseOrderTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.monpai.sailboatmod.market;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseOrderTest {
    private static PurchaseOrder sample(Fulfillment f, PickupMode p, String status) {
        return new PurchaseOrder("o1", "l1", "buuid", "Buyer", 4, 40,
                new BlockPos(1, 64, 2), "Src",
                new BlockPos(5, 64, 6), "Dst", status, f, p);
    }

    @Test void legacyConstructorDefaultsToSellerShipSystem() {
        PurchaseOrder o = new PurchaseOrder("o1", "l1", "u", "B", 1, 10,
                BlockPos.ZERO, "S", BlockPos.ZERO, "D", "PAID");
        assertEquals(Fulfillment.SELLER_SHIP, o.fulfillment());
        assertEquals(PickupMode.SYSTEM, o.pickupMode());
    }

    @Test void compactConstructorNullEnumsFallBackToDefault() {
        PurchaseOrder o = sample(null, null, "PAID");
        assertEquals(Fulfillment.SELLER_SHIP, o.fulfillment());
        assertEquals(PickupMode.SYSTEM, o.pickupMode());
    }

    @Test void saveLoadRoundTripPreservesNewFields() {
        PurchaseOrder o = sample(Fulfillment.BUYER_PICKUP, PickupMode.MANUAL, "PICKUP_LOCKED");
        PurchaseOrder back = PurchaseOrder.load(o.save());
        assertEquals(Fulfillment.BUYER_PICKUP, back.fulfillment());
        assertEquals(PickupMode.MANUAL, back.pickupMode());
        assertEquals("PICKUP_LOCKED", back.status());
        assertEquals(o, back);
    }

    @Test void legacyNbtMissingNewKeysLoadsDefaults() {
        CompoundTag tag = new CompoundTag();
        tag.putString("OrderId", "o1");
        tag.putString("ListingId", "l1");
        tag.putString("BuyerUuid", "u");
        tag.putString("BuyerName", "B");
        tag.putInt("Quantity", 4);
        tag.putInt("TotalPrice", 40);
        tag.putLong("SourceDockPos", BlockPos.ZERO.asLong());
        tag.putString("SourceDockName", "S");
        tag.putLong("TargetDockPos", BlockPos.ZERO.asLong());
        tag.putString("TargetDockName", "D");
        tag.putString("Status", "WAITING_SHIPMENT");

        PurchaseOrder back = PurchaseOrder.load(tag);
        assertEquals(Fulfillment.SELLER_SHIP, back.fulfillment());
        assertEquals(PickupMode.SYSTEM, back.pickupMode());
    }

    @Test void withStatusPreservesFulfillment() {
        PurchaseOrder o = sample(Fulfillment.BUYER_PICKUP, PickupMode.MANUAL, "WAITING_DISPATCH");
        PurchaseOrder shipped = o.withStatus("IN_TRANSIT");
        assertEquals("IN_TRANSIT", shipped.status());
        assertEquals(Fulfillment.BUYER_PICKUP, shipped.fulfillment());
        assertEquals(PickupMode.MANUAL, shipped.pickupMode());
    }
}
```

- [ ] **Step 2: 验证失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.PurchaseOrderTest"`
Expected: 编译失败（13 参构造器/`withStatus`/`fulfillment()` 不存在）

- [ ] **Step 3: 实现（整文件替换 PurchaseOrder.java）**

```java
package com.monpai.sailboatmod.market;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record PurchaseOrder(
        String orderId, String listingId, String buyerUuid, String buyerName,
        int quantity, int totalPrice, BlockPos sourceDockPos, String sourceDockName,
        BlockPos targetDockPos, String targetDockName, String status,
        Fulfillment fulfillment, PickupMode pickupMode
) {
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_WAITING_DISPATCH = "WAITING_DISPATCH";
    public static final String STATUS_WAITING_SHIPMENT = "WAITING_SHIPMENT";
    public static final String STATUS_PICKUP_LOCKED = "PICKUP_LOCKED";
    public static final String STATUS_IN_TRANSIT = "IN_TRANSIT";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_CLAIMED = "CLAIMED";

    public PurchaseOrder {
        orderId = sanitize(orderId);
        listingId = sanitize(listingId);
        buyerUuid = sanitize(buyerUuid);
        buyerName = sanitize(buyerName);
        quantity = Math.max(0, quantity);
        totalPrice = Math.max(0, totalPrice);
        sourceDockPos = sourceDockPos == null ? BlockPos.ZERO : sourceDockPos.immutable();
        sourceDockName = sanitize(sourceDockName);
        targetDockPos = targetDockPos == null ? BlockPos.ZERO : targetDockPos.immutable();
        targetDockName = sanitize(targetDockName);
        status = sanitize(status).isBlank() ? STATUS_PAID : sanitize(status);
        fulfillment = fulfillment == null ? Fulfillment.DEFAULT : fulfillment;
        pickupMode = pickupMode == null ? PickupMode.DEFAULT : pickupMode;
    }

    /** 向后兼容的 11 参构造器：旧调用方默认卖家发货 / 系统代开。 */
    public PurchaseOrder(String orderId, String listingId, String buyerUuid, String buyerName,
                         int quantity, int totalPrice, BlockPos sourceDockPos, String sourceDockName,
                         BlockPos targetDockPos, String targetDockName, String status) {
        this(orderId, listingId, buyerUuid, buyerName, quantity, totalPrice,
                sourceDockPos, sourceDockName, targetDockPos, targetDockName, status,
                Fulfillment.DEFAULT, PickupMode.DEFAULT);
    }

    /** 复制并替换状态，保留 fulfillment/pickupMode。 */
    public PurchaseOrder withStatus(String newStatus) {
        return new PurchaseOrder(orderId, listingId, buyerUuid, buyerName, quantity, totalPrice,
                sourceDockPos, sourceDockName, targetDockPos, targetDockName, newStatus,
                fulfillment, pickupMode);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("OrderId", orderId);
        tag.putString("ListingId", listingId);
        tag.putString("BuyerUuid", buyerUuid);
        tag.putString("BuyerName", buyerName);
        tag.putInt("Quantity", quantity);
        tag.putInt("TotalPrice", totalPrice);
        tag.putLong("SourceDockPos", sourceDockPos.asLong());
        tag.putString("SourceDockName", sourceDockName);
        tag.putLong("TargetDockPos", targetDockPos.asLong());
        tag.putString("TargetDockName", targetDockName);
        tag.putString("Status", status);
        tag.putString("Fulfillment", fulfillment.name());
        tag.putString("PickupMode", pickupMode.name());
        return tag;
    }

    public static PurchaseOrder load(CompoundTag tag) {
        return new PurchaseOrder(
                tag.getString("OrderId"),
                tag.getString("ListingId"),
                tag.getString("BuyerUuid"),
                tag.getString("BuyerName"),
                tag.getInt("Quantity"),
                tag.getInt("TotalPrice"),
                BlockPos.of(tag.getLong("SourceDockPos")),
                tag.getString("SourceDockName"),
                BlockPos.of(tag.getLong("TargetDockPos")),
                tag.getString("TargetDockName"),
                tag.getString("Status"),
                Fulfillment.fromName(tag.contains("Fulfillment") ? tag.getString("Fulfillment") : null),
                PickupMode.fromName(tag.contains("PickupMode") ? tag.getString("PickupMode") : null)
        );
    }

    public String toSummaryLine() {
        return String.format(Locale.ROOT, "%s | %s -> %s | x%d | %s",
                shortId(orderId), dockLabel(sourceDockName, sourceDockPos),
                dockLabel(targetDockName, targetDockPos), quantity, status);
    }

    private static String dockLabel(String name, BlockPos pos) {
        return name == null || name.isBlank() ? pos.toShortString() : name;
    }
    private static String shortId(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.length() <= 8 ? value : value.substring(0, 8);
    }
    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
```

- [ ] **Step 4: 验证通过 + 全量编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.PurchaseOrderTest"` Expected: PASS
Run: `./gradlew compileJava` Expected: BUILD SUCCESSFUL（13 个调用点里 12 个走 11 参兼容构造器、`load` 已是 13 参——全部编译通过）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/PurchaseOrder.java src/test/java/com/monpai/sailboatmod/market/PurchaseOrderTest.java
git commit -m "feat(market): add fulfillment/pickupMode to PurchaseOrder with NBT back-compat"
```

---

## Task 4: 复制订单调用点改用 withStatus（防履约信息丢失）

**为什么**：12 个"复制订单仅改状态"的调用点目前用 11 参构造器，会把 `fulfillment`/`pickupMode` 重置成默认。第一阶段虽无人设非默认值，但下游阶段一旦在下单点设 BUYER_PICKUP，订单每翻一次状态就丢履约信息。现在统一改掉，杜绝隐性 bug。

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`（约 :1097, :1796 复制改状态；:1898/:1911 split shipped/remainder）
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java`（约 :965, :1490, :2066, :2117 复制改状态；:1659/:1672 split）
- Modify: `src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java`（约 :2236 复制改状态）

> 行号以实际为准；用 `grep -n "new PurchaseOrder(" <file>` 定位。**不要改** `MarketBlockEntity` 的原创下单点（约 :630，`WAITING_SHIPMENT`）和 `PurchaseOrder.load` ——前者本就该用默认履约（下游阶段才设真值），后者已是 13 参。

- [ ] **Step 1: 定位所有复制点**

Run: `grep -n "new PurchaseOrder(" src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java`
逐个查看：凡是"基于一个已有 `order`、复制其全部字段、只改 status"的，记为待改点。

- [ ] **Step 2: 把"仅改状态"复制点改为 withStatus**

模式（示例，针对每个"复制改状态"点）：
```java
// 改前：
market.putPurchaseOrder(new PurchaseOrder(
        order.orderId(), order.listingId(), order.buyerUuid(), order.buyerName(),
        order.quantity(), order.totalPrice(), order.sourceDockPos(), order.sourceDockName(),
        order.targetDockPos(), order.targetDockName(), "IN_TRANSIT"));
// 改后：
market.putPurchaseOrder(order.withStatus(PurchaseOrder.STATUS_IN_TRANSIT));
```
对 split 的 shipped/remainder（数量/价格也变的点），不能用 `withStatus`，改用 13 参构造器并显式传 `order.fulfillment(), order.pickupMode()` 保留履约：
```java
new PurchaseOrder(order.orderId(), order.listingId(), order.buyerUuid(), order.buyerName(),
        shippedQty, shippedPrice, order.sourceDockPos(), order.sourceDockName(),
        order.targetDockPos(), order.targetDockName(), order.status(),
        order.fulfillment(), order.pickupMode());
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 全量测试回归**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.*"`
Expected: PASS（含已有 market 测试，确认没破坏）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java
git commit -m "refactor(market): preserve fulfillment via withStatus on order copies"
```

---

## Task 5: MarketOverviewData.OrderEntry 加字段 + 兼容构造器

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java`（OrderEntry record）
- Test: `src/test/java/com/monpai/sailboatmod/market/MarketOverviewDataEntryTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MarketOverviewDataEntryTest {
    @Test void orderEntryLegacyConstructorDefaults() {
        MarketOverviewData.OrderEntry e = new MarketOverviewData.OrderEntry(
                "o1", "Oak x4", "Src", "Dst", 4, "PAID", List.of());
        assertEquals("SELLER_SHIP", e.fulfillment());
        assertEquals("SYSTEM", e.pickupMode());
        assertEquals("", e.dispatchState());
    }
    @Test void orderEntryFullConstructorKeepsValues() {
        MarketOverviewData.OrderEntry e = new MarketOverviewData.OrderEntry(
                "o1", "Oak x4", "Src", "Dst", 4, "WAITING_DISPATCH", List.of(),
                "BUYER_PICKUP", "MANUAL", "queued");
        assertEquals("BUYER_PICKUP", e.fulfillment());
        assertEquals("MANUAL", e.pickupMode());
        assertEquals("queued", e.dispatchState());
    }
}
```

- [ ] **Step 2: 验证失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.MarketOverviewDataEntryTest"`
Expected: 编译失败（10 参构造器/`fulfillment()` 不存在）

- [ ] **Step 3: 实现（改 OrderEntry record）**

读 `MarketOverviewData.java` 找到 `OrderEntry`（约 :156），替换为：
```java
    public record OrderEntry(String orderId, String label, String sourceDockName, String targetDockName,
                             int quantity, String status, List<DispatchOption> dispatchOptions,
                             String fulfillment, String pickupMode, String dispatchState) {
        public OrderEntry {
            dispatchOptions = dispatchOptions == null ? List.of() : List.copyOf(dispatchOptions);
            fulfillment = fulfillment == null ? Fulfillment.DEFAULT.name() : fulfillment;
            pickupMode = pickupMode == null ? PickupMode.DEFAULT.name() : pickupMode;
            dispatchState = dispatchState == null ? "" : dispatchState;
        }

        /** 向后兼容的 7 参构造器（旧调用方/测试）。 */
        public OrderEntry(String orderId, String label, String sourceDockName, String targetDockName,
                          int quantity, String status, List<DispatchOption> dispatchOptions) {
            this(orderId, label, sourceDockName, targetDockName, quantity, status, dispatchOptions,
                    Fulfillment.DEFAULT.name(), PickupMode.DEFAULT.name(), "");
        }
    }
```
> 若原 OrderEntry 已有紧凑构造器做 `dispatchOptions` 防御，保留其内容，仅追加新字段的默认化。确认 `MarketOverviewData` 已 import `java.util.List`（若 OrderEntry 用了它必已 import）。

- [ ] **Step 4: 验证通过 + 全量编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.MarketOverviewDataEntryTest"` Expected: PASS
Run: `./gradlew compileJava` Expected: BUILD SUCCESSFUL（生产端 `buildOverview` 与 packet `readOrderEntries` 仍调 7 参兼容构造器）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java src/test/java/com/monpai/sailboatmod/market/MarketOverviewDataEntryTest.java
git commit -m "feat(market): add fulfillment/pickupMode/dispatchState to OrderEntry"
```

---

## Task 6: MarketOverviewData.DispatchOption 加字段 + 兼容构造器

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java`（DispatchOption record）
- Test: 扩展 `src/test/java/com/monpai/sailboatmod/market/MarketOverviewDataEntryTest.java`

- [ ] **Step 1: 追加失败测试**

在 `MarketOverviewDataEntryTest` 加：
```java
    @Test void dispatchOptionLegacyConstructorDefaults() {
        MarketOverviewData.DispatchOption d = new MarketOverviewData.DispatchOption(
                "PORT", "Port", "Boat-1", "Route-A", "SrcT", "DstT", 100, 20, true, "ready", "detail");
        assertEquals("", d.transportMode());
        assertEquals(0, d.carpoolCount());
        assertEquals("", d.handoverSummary());
    }
    @Test void dispatchOptionFullConstructorKeepsValues() {
        MarketOverviewData.DispatchOption d = new MarketOverviewData.DispatchOption(
                "PORT", "Port", "Boat-1", "Route-A", "SrcT", "DstT", 100, 20, true, "ready", "detail",
                "WATER", 3, "pickup@A,B");
        assertEquals("WATER", d.transportMode());
        assertEquals(3, d.carpoolCount());
        assertEquals("pickup@A,B", d.handoverSummary());
    }
```

- [ ] **Step 2: 验证失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.MarketOverviewDataEntryTest"`
Expected: 编译失败（14 参构造器不存在）

- [ ] **Step 3: 实现（改 DispatchOption record）**

读 `MarketOverviewData.java` 找到 `DispatchOption`（约 :166），替换为：
```java
    public record DispatchOption(String terminalKind, String terminalLabel, String carrierName, String routeName,
                                 String sourceTerminalName, String targetTerminalName, int distanceMeters, int etaSeconds,
                                 boolean available, String availability, String detail,
                                 String transportMode, int carpoolCount, String handoverSummary) {
        public DispatchOption {
            transportMode = transportMode == null ? "" : transportMode;
            handoverSummary = handoverSummary == null ? "" : handoverSummary;
            carpoolCount = Math.max(0, carpoolCount);
        }

        /** 向后兼容的 11 参构造器（旧调用方/测试）。 */
        public DispatchOption(String terminalKind, String terminalLabel, String carrierName, String routeName,
                              String sourceTerminalName, String targetTerminalName, int distanceMeters, int etaSeconds,
                              boolean available, String availability, String detail) {
            this(terminalKind, terminalLabel, carrierName, routeName, sourceTerminalName, targetTerminalName,
                    distanceMeters, etaSeconds, available, availability, detail, "", 0, "");
        }
    }
```
> 若原 DispatchOption 已有紧凑构造器，保留并追加新字段默认化。

- [ ] **Step 4: 验证通过 + 全量编译**

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.MarketOverviewDataEntryTest"` Expected: PASS
Run: `./gradlew compileJava` Expected: BUILD SUCCESSFUL（MarketBlockEntity 全部 7 个 DispatchOption 构造点、packet `readDispatchOptions` 仍走 11 参）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java src/test/java/com/monpai/sailboatmod/market/MarketOverviewDataEntryTest.java
git commit -m "feat(market): add transportMode/carpoolCount/handoverSummary to DispatchOption"
```

---

## Task 7: OpenMarketScreenPacket 同步新字段（packet round-trip）

**为什么**：packet 是纯位置流（无 tag 名），新字段不同步 write/read 会过网丢失，且字段顺序必须与 record canonical 顺序严格对齐。

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenMarketScreenPacket.java`（`writeOrderEntries`/`readOrderEntries`/`writeDispatchOptions`/`readDispatchOptions`）
- Test: 扩展 `src/test/java/com/monpai/sailboatmod/network/packet/OpenMarketScreenPacketTest.java`

- [ ] **Step 1: 写失败测试**

在 `OpenMarketScreenPacketTest` 加一个用**完整构造器**（14 参 DispatchOption + 10 参 OrderEntry，非默认值）的 round-trip：
```java
    @Test
    void roundTripPreservesDispatchAndFulfillmentFields() {
        MarketOverviewData.DispatchOption option = new MarketOverviewData.DispatchOption(
                "PORT", "Port", "Boat-1", "Route-A", "SrcT", "DstT", 100, 20, true, "ready", "detail",
                "WATER", 3, "pickup@A,B");
        MarketOverviewData.OrderEntry order = new MarketOverviewData.OrderEntry(
                "o1", "Oak x4", "Src", "Dst", 4, "WAITING_DISPATCH", java.util.List.of(option),
                "BUYER_PICKUP", "MANUAL", "queued");
        // 用现有测试同样的 encode→decode 路径（参照本测试类既有写法），断言 decoded order 等于 order。
        // 关键断言：
        // assertEquals(order, decodedOrder);
        // assertEquals("WATER", decodedOrder.dispatchOptions().get(0).transportMode());
        // assertEquals("BUYER_PICKUP", decodedOrder.fulfillment());
    }
```
> 落地时按本测试类既有的 encode/decode 调用方式补全中段（用 `FriendlyByteBuf` 或测试已有 helper）。

- [ ] **Step 2: 验证失败**

Run: `./gradlew test --tests "com.monpai.sailboatmod.network.packet.OpenMarketScreenPacketTest"`
Expected: FAIL（decoded 缺新字段 → assertEquals 不等）

- [ ] **Step 3: 实现 — write/read 同步追加（顺序与 record 对齐）**

`writeOrderEntries`：在写完 `writeDispatchOptions(buffer, entry.dispatchOptions())` 后追加：
```java
            PacketStringCodec.writeUtfSafe(buffer, entry.fulfillment(), 32);
            PacketStringCodec.writeUtfSafe(buffer, entry.pickupMode(), 32);
            PacketStringCodec.writeUtfSafe(buffer, entry.dispatchState(), 48);
```
`readOrderEntries`：改为 10 参 canonical 构造（在 `readDispatchOptions(buffer)` 后追加三读）：
```java
            entries.add(new MarketOverviewData.OrderEntry(
                    buffer.readUtf(64), buffer.readUtf(192), buffer.readUtf(64), buffer.readUtf(64),
                    buffer.readVarInt(), buffer.readUtf(48), readDispatchOptions(buffer),
                    buffer.readUtf(32), buffer.readUtf(32), buffer.readUtf(48)));
```
> 注意：上面各 `readUtf(N)` 的长度/类型必须**与现有 read 完全一致**——只在末尾追加 3 个新读。若现有 read 用的是 `buffer.readVarInt()` 读数量等，照抄现状，勿改既有读法。

`writeDispatchOptions`：写完 `detail` 后追加：
```java
            PacketStringCodec.writeUtfSafe(buffer, option.transportMode(), 16);
            buffer.writeVarInt(option.carpoolCount());
            PacketStringCodec.writeUtfSafe(buffer, option.handoverSummary(), 96);
```
`readDispatchOptions`：改为 14 参 canonical（末尾追加三读 `buffer.readUtf(16), buffer.readVarInt(), buffer.readUtf(96)`）。

- [ ] **Step 4: 验证通过 + 全量测试**

Run: `./gradlew test --tests "com.monpai.sailboatmod.network.packet.OpenMarketScreenPacketTest"` Expected: PASS
Run: `./gradlew compileJava` Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/monpai/sailboatmod/network/packet/OpenMarketScreenPacket.java src/test/java/com/monpai/sailboatmod/network/packet/OpenMarketScreenPacketTest.java
git commit -m "feat(net): sync new OrderEntry/DispatchOption fields through OpenMarketScreenPacket"
```

---

## Task 8: 第一阶段收尾 — 全量编译 + 跳过测试构建 jar

**Files:** 无新增

- [ ] **Step 1: 全量编译 + 测试**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test --tests "com.monpai.sailboatmod.market.*" --tests "com.monpai.sailboatmod.network.packet.OpenMarketScreenPacketTest"`
Expected: PASS（本阶段新增/相关测试全绿）

- [ ] **Step 2: 构建 jar（跳过仓库既有的 6 个无关失败测试）**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL，产物 `build/libs/sailboatmod-1.3.9-reobf.jar`

- [ ] **Step 3: 推送（按用户规则 build+push）**

```bash
git -c credential.helper='!gh auth git-credential' -c http.proxy=http://127.0.0.1:7897 push origin feature/road-planner-rebuild
```
> 代理偶发 schannel 握手失败，失败重试即可（见 memory `sailboatmod_push`）。

---

## Self-Review 结论

- **Spec 覆盖**：本计划覆盖 spec 的"数据模型变更（PurchaseOrder 加字段、TransportMode 抽象、MarketOverviewData 扩展、NBT/packet 兼容）"——即实施分期第 1 阶段。调度逻辑/执行对接/自提/UI 属后续阶段，不在本计划。
- **占位符**：所有代码步骤均含完整代码；Task 7 测试中段标注"按本测试类既有写法补全"是因 encode/decode helper 需就地参照，已给出关键断言。
- **类型一致**：枚举名 `Fulfillment`/`PickupMode`/`TransportMode`、字段名 `fulfillment`/`pickupMode`/`dispatchState`/`transportMode`/`carpoolCount`/`handoverSummary` 全程一致；`withStatus` 签名一致；状态常量 `STATUS_*` 一致。
- **关键风险**：Task 4（withStatus 防履约丢失）是必做项，否则下游阶段引入隐性 bug；Task 7 的 packet 字段顺序对齐是唯一需逐字段核对处，round-trip 测试为安全网。
