# webmap 物流轨迹（手动发车 + 实时跟走）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让玩家手动发车的马车/帆船也在网页端地图显示物流轨迹，并让载具图标按实时坐标平滑跟走（后端 2 秒同步坐标 + 前端插值）。

**Architecture:** 复用现有 `ShippingTrace` 系统。手动发车时建一条 `manual` 标记的 Trace（id=`manual-<载具UUID>`，无 ShippingOrder）；`ShippingTraceRecord` 加 `currentX/currentZ/manual` 字段；载具航行 tick 每 2 秒（40 tick）把实时坐标写进 Trace；DTO/JSON 输出 `current`+`manual`；前端轮询 2 秒并在两次间插值平滑、按方向旋转图标、手动车异色。autopilot 结束时清理手动 Trace。

**Tech Stack:** Java 17、Minecraft Forge 1.20.1、NBT、Gson、JUnit 5、前端 vanilla JS canvas（`marketweb/map.js`）。测试两种风格：纯函数行为测试（NBT round-trip、isMapVisibleStatus、插值纯函数）+ 源码文本断言（tick 接入、清理钩子、前端字段）。

---

## 背景与约束（实现前必读）

**两个缺口（spec Context）：**
1. 手动发车不显示——`ShippingTraceService` 只在市场调度（有 ShippingOrder）时建 Trace；手动 autopilot 不建。
2. 位置非实时——Trace 无载具实时坐标，图标停在已完成路点，5 秒一跳。

**探查校准（与 spec 的现实差异，实现以此为准）：**
- `ShippingTraceRecord` 是 14 字段 record（`market/logistics/ShippingTraceRecord.java:12-25`），有 `withStatus`/`withProgress`/`save`/`load`。
- **现状连市场调度车的实时进度更新也没接**：`createOrUpdateTrace` 建了 Trace，但载具 tick 里**没有**调 `updateProgress`/`updateLivePosition`。所以"实时同步"对调度车和手动车**都要从头接**。
- 判据"有无订单"：载具字段 `autopilotShipmentShippingOrderId`（非空=订单车）；手动车该字段为空。
- 手动 trace id 用 `manual-<载具UUID>`，与订单 id（来自 ShippingOrder）不冲突，便于精确清理。

**已核实真实接口：**
- `ShippingTraceRecord(shippingOrderId, shipperUuid, dimensionId, transportMode, status, nationId, townId, sourceName, targetName, List<Vec3> waypoints, int completedPointCount, double progressRatio, long startedGameTime, long updatedGameTime)`。规范化构造器 + `withStatus(status,t)` / `withProgress(cnt,ratio,t)` / `save()` / `load(tag)`。
- `ShippingTraceSavedData.get(Level)`、`getTraces()`、`getTrace(id)`、`putTrace(rec)`、`removeTrace(id)`。
- `ShippingTraceService.createOrUpdateTrace(Level, ShippingOrder, RouteDefinition)`、`updateStatus(Level,id,status)`、`updateProgress(Level,id,cnt,ratio)`、`visibleFor(server,identity)`、`toDtos(list)`、`toDto(rec)`、`isMapVisibleStatus(status)`。状态可见集合：SAILING/IN_TRANSIT/ARRIVED。
- `MarketWebMapDtos.ShipmentTrace(shippingOrderId, label, transportMode, status, sourceName, targetName, List<Point> points, int completedPointCount, double progressRatio)`；`Point(double x, double z)`。
- `MarketWebMapJson.shipment(ShipmentTrace)→JsonObject`（:85-97）、`shipments(list)→JsonArray`、`points(List<Point>)`（:31-37）。
- 载具：`SailboatEntity.startAutopilotInternal()`（:1291-1333，末尾建 autopilot）、`initializeAutopilotShipmentContext(route)`（:1997-2017）、`stopAutopilot(boolean)`（:1346-1372）、`tick()`（:366+，autopilot 段 :393-427）、字段 `autopilotRoute`/`autopilotTargetIndex`/`autopilotShipmentShippingOrderId`、`position()`。`CarriageEntity` 对称：`startAutopilot`、`stopAutopilot`（:1517）、`finishAutopilot`（:2284）、`tickRailAutopilotDrive`（:525）。
- `TransportEntity` 接口：两载具共有 autopilot 控制方法。
- 现有测试：`ShippingTraceRecordTest`、`ShippingTraceServiceTest`（JUnit5 纯函数/源码断言风格；NBT round-trip 无需 Bootstrap）。

**测试命令：**
- 单类：`cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.logistics.ShippingTraceRecordTest`
- 全量：`cd sailboatmod && ./gradlew test`

> 前端 `marketweb/map.js` 的插值/视觉无 JS 测试框架，用**源码文本断言**（Java 测试 `Files.readString` 读 map.js 验证关键代码存在）。真实视觉留集成实测。

---

## File Structure（先锁定边界）

**修改（生产代码）：**
- `market/logistics/ShippingTraceRecord.java` — 加 `currentX`/`currentZ`/`manual` 字段 + 构造器规范化 + `withStatus`/`withProgress` 透传 + 新增 `withLivePosition` + `save`/`load` NBT 兼容
- `market/logistics/ShippingTraceService.java` — 加 `createOrUpdateManualTrace(Level, vehicle)`、`updateLivePosition(Level, id, x, z)`、`removeTrace` 包装；`toDto` 输出 current/manual；手动 traceId 工具
- `market/web/map/MarketWebMapDtos.java` — `ShipmentTrace` 加 `current`(Point)、`manual`(boolean)
- `market/web/map/MarketWebMapJson.java` — `shipment(...)` 输出 current/manual
- `entity/SailboatEntity.java` — startAutopilot 后建手动 Trace（无订单时）；tick 每 40 tick 同步坐标+进度；stopAutopilot 清理手动 Trace
- `entity/CarriageEntity.java` — 同上（陆路）
- `entity/TransportEntity.java` — 加一个取实时坐标/路线/traceId 的统一接口方法（若需要，供 Service 用）
- `src/main/resources/marketweb/map.js` — 轮询 2 秒、插值补间、方向旋转、尾迹/脉动、手动车异色

**新建（测试）：**
- `src/test/java/com/monpai/sailboatmod/market/logistics/ShippingTraceLivePositionTest.java`（NBT round-trip current/manual + withLivePosition）
- `src/test/java/com/monpai/sailboatmod/market/web/map/ShipmentTraceDtoFieldsTest.java`（DTO/JSON 含 current/manual 源码断言）
- `src/test/java/com/monpai/sailboatmod/entity/ManualTraceWiringContractTest.java`（载具建/同步/清理手动 Trace 的源码断言）
- `src/test/java/com/monpai/sailboatmod/market/web/MapTraceInterpolationContractTest.java`（map.js 插值/视觉源码断言）

---

## Task 1: ShippingTraceRecord 加实时坐标 + manual 字段

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceRecord.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/logistics/ShippingTraceLivePositionTest.java`

- [ ] **Step 1: 写失败的 NBT round-trip 测试**

新建 `src/test/java/com/monpai/sailboatmod/market/logistics/ShippingTraceLivePositionTest.java`：

```java
package com.monpai.sailboatmod.market.logistics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShippingTraceLivePositionTest {
    private static ShippingTraceRecord sample() {
        return new ShippingTraceRecord(
                "manual-abc", "uuid-1", "minecraft:overworld", "PORT", "SAILING",
                "nation-1", "town-1", "Src", "Dst",
                List.of(new Vec3(0, 64, 0), new Vec3(10, 64, 10)),
                0, 0.0D, 100L, 100L,
                12.5D, 34.5D, true);
    }

    @Test
    void livePositionAndManualSurviveNbtRoundTrip() {
        ShippingTraceRecord rec = sample();
        CompoundTag tag = rec.save();
        ShippingTraceRecord loaded = ShippingTraceRecord.load(tag);

        assertEquals(12.5D, loaded.currentX(), 1.0E-9);
        assertEquals(34.5D, loaded.currentZ(), 1.0E-9);
        assertTrue(loaded.manual());
    }

    @Test
    void withLivePositionUpdatesCoordsAndTimeKeepsOtherFields() {
        ShippingTraceRecord rec = sample();
        ShippingTraceRecord moved = rec.withLivePosition(99.0D, 88.0D, 200L);

        assertEquals(99.0D, moved.currentX(), 1.0E-9);
        assertEquals(88.0D, moved.currentZ(), 1.0E-9);
        assertEquals(200L, moved.updatedGameTime());
        assertEquals("manual-abc", moved.shippingOrderId());
        assertTrue(moved.manual());
    }

    @Test
    void legacyNbtWithoutLiveFieldsDefaultsToFirstWaypointAndNotManual() {
        // 模拟旧记录：手动构造一个不含 CurrentX/Manual 的 tag
        ShippingTraceRecord rec = new ShippingTraceRecord(
                "ord-1", "uuid-1", "minecraft:overworld", "PORT", "SAILING",
                "", "", "Src", "Dst",
                List.of(new Vec3(5, 64, 7), new Vec3(10, 64, 10)),
                0, 0.0D, 100L, 100L,
                0.0D, 0.0D, false);
        CompoundTag tag = rec.save();
        tag.remove("CurrentX");
        tag.remove("CurrentZ");
        tag.remove("Manual");

        ShippingTraceRecord loaded = ShippingTraceRecord.load(tag);
        assertEquals(5.0D, loaded.currentX(), 1.0E-9, "legacy current falls back to first waypoint x");
        assertEquals(7.0D, loaded.currentZ(), 1.0E-9, "legacy current falls back to first waypoint z");
        assertFalse(loaded.manual());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.logistics.ShippingTraceLivePositionTest`
Expected: FAIL —— record 无 currentX/currentZ/manual 字段、无 withLivePosition（编译失败）。

- [ ] **Step 3: 改 record header + 构造器**

把 `ShippingTraceRecord.java` 的 record header（:12-25）末尾加 3 个字段（在 `updatedGameTime` 后）：

```java
public record ShippingTraceRecord(String shippingOrderId,
                                  String shipperUuid,
                                  String dimensionId,
                                  String transportMode,
                                  String status,
                                  String nationId,
                                  String townId,
                                  String sourceName,
                                  String targetName,
                                  List<Vec3> waypoints,
                                  int completedPointCount,
                                  double progressRatio,
                                  long startedGameTime,
                                  long updatedGameTime,
                                  double currentX,
                                  double currentZ,
                                  boolean manual) {
```

在规范化构造器体（:28-43）末尾 `updatedGameTime = ...` 之后加（current 缺省时回退首个 waypoint）：

```java
        if (currentX == 0.0D && currentZ == 0.0D && !waypoints.isEmpty()) {
            currentX = waypoints.get(0).x;
            currentZ = waypoints.get(0).z;
        }
```

> 说明：用 (0,0) 作"未设"哨兵——真实地图坐标极少恰好 (0,0)，且回退到首 waypoint 是安全初值。

- [ ] **Step 4: 改 withStatus/withProgress 透传新字段 + 加 withLivePosition**

把 `withStatus`（:45-49）和 `withProgress`（:51-55）的 `new ShippingTraceRecord(...)` 末尾补 `currentX, currentZ, manual`：

```java
    public ShippingTraceRecord withStatus(String nextStatus, long gameTime) {
        return new ShippingTraceRecord(shippingOrderId, shipperUuid, dimensionId, transportMode, nextStatus,
                nationId, townId, sourceName, targetName, waypoints, completedPointCount, progressRatio,
                startedGameTime, gameTime, currentX, currentZ, manual);
    }

    public ShippingTraceRecord withProgress(int nextCompletedPointCount, double nextProgressRatio, long gameTime) {
        return new ShippingTraceRecord(shippingOrderId, shipperUuid, dimensionId, transportMode, status,
                nationId, townId, sourceName, targetName, waypoints, nextCompletedPointCount, nextProgressRatio,
                startedGameTime, gameTime, currentX, currentZ, manual);
    }

    public ShippingTraceRecord withLivePosition(double nextX, double nextZ, long gameTime) {
        return new ShippingTraceRecord(shippingOrderId, shipperUuid, dimensionId, transportMode, status,
                nationId, townId, sourceName, targetName, waypoints, completedPointCount, progressRatio,
                startedGameTime, gameTime, nextX, nextZ, manual);
    }
```

- [ ] **Step 5: 改 save/load NBT**

在 `save()`（:80 `putLong("UpdatedGameTime", ...)` 之后、`return tag;` 之前）加：

```java
        tag.putDouble("CurrentX", currentX);
        tag.putDouble("CurrentZ", currentZ);
        tag.putBoolean("Manual", manual);
```

在 `load()`（:92-107 的 `new ShippingTraceRecord(...)`）末尾参数补（缺键回退：current=0 触发构造器回退首 waypoint，manual 缺省 false）：

```java
        return new ShippingTraceRecord(
                tag.getString("ShippingOrderId"),
                tag.getString("ShipperUuid"),
                tag.getString("DimensionId"),
                tag.getString("TransportMode"),
                tag.getString("Status"),
                tag.getString("NationId"),
                tag.getString("TownId"),
                tag.getString("SourceName"),
                tag.getString("TargetName"),
                points,
                tag.getInt("CompletedPointCount"),
                tag.getDouble("ProgressRatio"),
                tag.getLong("StartedGameTime"),
                tag.getLong("UpdatedGameTime"),
                tag.contains("CurrentX") ? tag.getDouble("CurrentX") : 0.0D,
                tag.contains("CurrentZ") ? tag.getDouble("CurrentZ") : 0.0D,
                tag.contains("Manual") && tag.getBoolean("Manual")
        );
```

- [ ] **Step 6: 跑测试确认通过 + 全 record/service 测试防回归**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.logistics.*`
Expected: PASS（新测试过；既有 `ShippingTraceRecordTest`/`ShippingTraceServiceTest` 不回归——若它们用旧构造器会编译失败，需在 Step 内同步把那些测试的 `new ShippingTraceRecord(...)` 补 3 个参数 `0.0D, 0.0D, false`）。

> 若既有测试编译失败：定位 `ShippingTraceRecordTest` 里的 `new ShippingTraceRecord(...)`，在末尾补 `, 0.0D, 0.0D, false`，再跑。

- [ ] **Step 7: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceRecord.java src/test/java/com/monpai/sailboatmod/market/logistics/ShippingTraceLivePositionTest.java && git commit -m "feat(webmap): ShippingTraceRecord adds live currentX/Z + manual flag (nbt-compatible)"
```

---

## Task 2: Service 加手动建 Trace + 实时坐标更新 + DTO 输出

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapDtos.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/map/ShipmentTraceDtoFieldsTest.java`

- [ ] **Step 1: 写失败的 DTO/源码断言测试**

新建 `src/test/java/com/monpai/sailboatmod/market/web/map/ShipmentTraceDtoFieldsTest.java`：

```java
package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentTraceDtoFieldsTest {
    @Test
    void shipmentTraceDtoHasCurrentAndManual() throws Exception {
        String dtos = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapDtos.java"));
        assertTrue(dtos.contains("Point current"),
                "ShipmentTrace should carry the live current position");
        assertTrue(dtos.contains("boolean manual"),
                "ShipmentTrace should carry the manual flag");
    }

    @Test
    void serviceExposesManualTraceAndLivePositionApis() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceService.java"));
        assertTrue(service.contains("createOrUpdateManualTrace("),
                "service should create a manual trace for hand-dispatched vehicles");
        assertTrue(service.contains("updateLivePosition("),
                "service should update the live position of a trace");
        assertTrue(service.contains("manualTraceId("),
                "service should derive a stable manual trace id from the vehicle uuid");
    }

    @Test
    void toDtoOutputsCurrentAndManual() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceService.java"));
        assertTrue(service.contains("trace.currentX()") && service.contains("trace.currentZ()"),
                "toDto should map the live current position");
        assertTrue(service.contains("trace.manual()"),
                "toDto should map the manual flag");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.web.map.ShipmentTraceDtoFieldsTest`
Expected: FAIL —— DTO 无 current/manual，service 无新方法。

- [ ] **Step 3: 改 ShipmentTrace DTO 加 current/manual**

把 `MarketWebMapDtos.java` 的 `ShipmentTrace` record（:44-56）改为（在 progressRatio 后加两字段）：

```java
    public record ShipmentTrace(String shippingOrderId,
                                String label,
                                String transportMode,
                                String status,
                                String sourceName,
                                String targetName,
                                List<Point> points,
                                int completedPointCount,
                                double progressRatio,
                                Point current,
                                boolean manual) {
        public ShipmentTrace {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }
```

> 若原 record 已有 compact 构造器做 points 防御拷贝，保留其内容，仅在 header 加两字段。

- [ ] **Step 4: 改 toDto 输出 current/manual**

先 Read `ShippingTraceService.toDto`（:106-125），把它构造 `new MarketWebMapDtos.ShipmentTrace(...)` 的末尾补：

```java
                new MarketWebMapDtos.Point(trace.currentX(), trace.currentZ()),
                trace.manual()
```

即在原有 `completedPointCount, progressRatio` 参数后追加这两项。

- [ ] **Step 5: 加 manualTraceId / createOrUpdateManualTrace / updateLivePosition**

先 Read `ShippingTraceService.createOrUpdateTrace`（:23-45）了解它如何构造 record（取 nationId/townId、gameTime）。在该类内新增：

```java
    /** 手动发车的稳定 trace id，与订单 id 不冲突。 */
    public static String manualTraceId(java.util.UUID vehicleUuid) {
        return "manual-" + (vehicleUuid == null ? "unknown" : vehicleUuid.toString());
    }

    /**
     * 为手动发车的载具建/更新一条 manual Trace。
     * @param waypoints 载具 autopilotRoute 路点
     * @param shipperUuid 发车玩家 uuid 字符串
     * @param nationId 发车玩家国家（用于可见性）
     * @param transportMode "PORT"(水) / "LAND"(陆)
     * @param status 可见状态（"SAILING"/"IN_TRANSIT"）
     */
    public static void createOrUpdateManualTrace(Level level, java.util.UUID vehicleUuid,
                                                 java.util.List<net.minecraft.world.phys.Vec3> waypoints,
                                                 String shipperUuid, String nationId,
                                                 String transportMode, String status,
                                                 double currentX, double currentZ) {
        if (level == null || level.isClientSide() || vehicleUuid == null
                || waypoints == null || waypoints.size() < 2) {
            return;
        }
        long gameTime = level.getGameTime();
        String id = manualTraceId(vehicleUuid);
        ShippingTraceRecord rec = new ShippingTraceRecord(
                id, shipperUuid, MarketWebMapConstants.OVERWORLD, transportMode, status,
                nationId == null ? "" : nationId, "", "手动", "手动",
                waypoints, 0, 0.0D, gameTime, gameTime, currentX, currentZ, true);
        ShippingTraceSavedData.get(level).putTrace(rec);
    }

    /** 写入载具实时坐标（调度车/手动车通用）。 */
    public static void updateLivePosition(Level level, String traceId, double x, double z) {
        if (level == null || level.isClientSide() || traceId == null || traceId.isBlank()) {
            return;
        }
        ShippingTraceSavedData data = ShippingTraceSavedData.get(level);
        ShippingTraceRecord trace = data.getTrace(traceId);
        if (trace != null) {
            data.putTrace(trace.withLivePosition(x, z, level.getGameTime()));
        }
    }

    /** 移除指定 trace（autopilot 结束清理用）。 */
    public static void removeTrace(Level level, String traceId) {
        if (level == null || level.isClientSide() || traceId == null || traceId.isBlank()) {
            return;
        }
        ShippingTraceSavedData.get(level).removeTrace(traceId);
    }
```

> 确认 `MarketWebMapConstants.OVERWORLD` 可用（record 构造器已用它）；确认 `Level`/`Vec3`/`UUID` 已 import 或用全限定名。`sourceName/targetName` 手动车暂用 "手动"（无订单终端名）。

- [ ] **Step 6: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.web.map.ShipmentTraceDtoFieldsTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

> 注意：DTO 加字段后，所有 `new MarketWebMapDtos.ShipmentTrace(...)` 构造点会编译失败。`toDto` 已在 Step 4 改。若 `MarketWebMapJson` 或别处也直接构造 ShipmentTrace，编译会暴露——Task 3 处理 JSON，其它构造点（若有）一并在此 Step 补 `current, manual` 参数。

- [ ] **Step 7: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceService.java src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapDtos.java src/test/java/com/monpai/sailboatmod/market/web/map/ShipmentTraceDtoFieldsTest.java && git commit -m "feat(webmap): manual trace + live position service apis, DTO carries current/manual"
```

---

## Task 3: JSON 输出 current/manual

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapJson.java`
- Test: 扩展 `ShipmentTraceDtoFieldsTest.java`

- [ ] **Step 1: 扩展测试**

在 `ShipmentTraceDtoFieldsTest.java` 追加：

```java
    @Test
    void jsonOutputsCurrentAndManual() throws Exception {
        String json = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapJson.java"));
        assertTrue(json.contains("\"manual\""),
                "shipment JSON should expose the manual flag");
        assertTrue(json.contains("\"current\""),
                "shipment JSON should expose the live current position");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.web.map.ShipmentTraceDtoFieldsTest`
Expected: FAIL —— JSON 无 current/manual。

- [ ] **Step 3: 改 MarketWebMapJson.shipment 输出新字段**

先 Read `MarketWebMapJson.shipment`（:85-97）。在它 `json.addProperty("progressRatio", ...)` 之后加：

```java
        json.addProperty("manual", shipment.manual());
        if (shipment.current() != null) {
            com.google.gson.JsonObject current = new com.google.gson.JsonObject();
            current.addProperty("x", shipment.current().x());
            current.addProperty("z", shipment.current().z());
            json.add("current", current);
        }
```

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.web.map.ShipmentTraceDtoFieldsTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapJson.java src/test/java/com/monpai/sailboatmod/market/web/map/ShipmentTraceDtoFieldsTest.java && git commit -m "feat(webmap): shipment JSON exposes current position and manual flag"
```

---

## Task 4: 帆船建/同步/清理手动 Trace

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java`
- Test: `src/test/java/com/monpai/sailboatmod/entity/ManualTraceWiringContractTest.java`

> 用源码断言验证接线（tick 同步、建/清理钩子调用存在）——载具行为依赖 MC 运行时，纯单测不可行。

- [ ] **Step 1: 写失败的接线断言测试**

新建 `src/test/java/com/monpai/sailboatmod/entity/ManualTraceWiringContractTest.java`：

```java
package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualTraceWiringContractTest {
    private static String sailboat() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
    }

    @Test
    void sailboatCreatesManualTraceWhenNoOrder() throws Exception {
        String src = sailboat();
        assertTrue(src.contains("createOrUpdateManualTrace("),
                "sailboat should create a manual trace when manually dispatched without an order");
    }

    @Test
    void sailboatSyncsLivePositionPeriodically() throws Exception {
        String src = sailboat();
        assertTrue(src.contains("updateLivePosition("),
                "sailboat tick should periodically push its live position to the trace");
        assertTrue(src.contains("TRACE_LIVE_SYNC_INTERVAL_TICKS"),
                "live sync should use a named interval constant (2s = 40 ticks)");
    }

    @Test
    void sailboatClearsManualTraceOnStop() throws Exception {
        String src = sailboat();
        assertTrue(src.contains("ShippingTraceService.removeTrace("),
                "sailboat should remove its manual trace when autopilot stops");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.ManualTraceWiringContractTest`
Expected: FAIL。

- [ ] **Step 3: 加常量 + tick 同步字段**

先 Read `SailboatEntity.java` 字段声明区（autopilotRoute 附近 :177）。加：

```java
    private static final int TRACE_LIVE_SYNC_INTERVAL_TICKS = 40; // 2s @20tps
    private int traceLiveSyncTicks = 0;
```

- [ ] **Step 4: startAutopilotInternal 末尾建手动 Trace**

先 Read `SailboatEntity.startAutopilotInternal`（:1291-1333）确认末尾 return true 前的位置、`autopilotShipmentShippingOrderId` 字段名、玩家国家怎么取。在 `return true;` 之前插入：

```java
        // webmap: 手动发车（无市场订单）建一条 manual 轨迹，供地图显示
        if (autopilotShipmentShippingOrderId == null || autopilotShipmentShippingOrderId.isBlank()) {
            com.monpai.sailboatmod.market.logistics.ShippingTraceService.createOrUpdateManualTrace(
                    level(), getUUID(), new java.util.ArrayList<>(autopilotRoute),
                    pendingShipperUuidForTrace(), nationIdForTrace(),
                    "PORT", "SAILING", getX(), getZ());
        }
```

> `pendingShipperUuidForTrace()` / `nationIdForTrace()`：若已有等价取值（如 `pendingShipperName`、驾驶玩家 uuid、国家解析）直接用；否则加两个 private helper。驾驶玩家 uuid 可从 `getControllingPassenger()` 取 `getUUID().toString()`；国家用现有 nation 解析（参考 createOrUpdateTrace 怎么取 nationId，或对 controlling player 查 `NationSavedData`）。**实现时按现状可得的最简方式取**，取不到时传 ""（仅影响同国可见，本人仍可见）。

- [ ] **Step 5: tick 周期同步实时坐标**

先 Read `SailboatEntity.tick()` 的 autopilot 段（:393-427），在确认 autopilot 激活且服务端的分支内加：

```java
            // webmap: 每 2 秒把实时坐标推给轨迹（订单车用订单 id，手动车用 manual id）
            if (++traceLiveSyncTicks >= TRACE_LIVE_SYNC_INTERVAL_TICKS) {
                traceLiveSyncTicks = 0;
                String traceId = (autopilotShipmentShippingOrderId == null || autopilotShipmentShippingOrderId.isBlank())
                        ? com.monpai.sailboatmod.market.logistics.ShippingTraceService.manualTraceId(getUUID())
                        : autopilotShipmentShippingOrderId;
                com.monpai.sailboatmod.market.logistics.ShippingTraceService.updateLivePosition(
                        level(), traceId, getX(), getZ());
            }
```

- [ ] **Step 6: stopAutopilot 清理手动 Trace**

先 Read `SailboatEntity.stopAutopilot(boolean)`（:1346-1372），在清理 autopilot 字段处加（仅清手动 trace；订单 trace 由订单生命周期管理）：

```java
        // webmap: 清理本载具的手动轨迹（订单轨迹不在此删）
        if (!level().isClientSide()) {
            com.monpai.sailboatmod.market.logistics.ShippingTraceService.removeTrace(
                    level(), com.monpai.sailboatmod.market.logistics.ShippingTraceService.manualTraceId(getUUID()));
        }
```

- [ ] **Step 7: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.ManualTraceWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 8: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java src/test/java/com/monpai/sailboatmod/entity/ManualTraceWiringContractTest.java && git commit -m "feat(webmap): sailboat creates/syncs/clears manual shipment trace"
```

---

## Task 5: 马车建/同步/清理手动 Trace（对称）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Test: 扩展 `ManualTraceWiringContractTest.java`

- [ ] **Step 1: 扩展接线断言**

在 `ManualTraceWiringContractTest.java` 追加：

```java
    @Test
    void carriageCreatesSyncsAndClearsManualTrace() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java"));
        assertTrue(src.contains("createOrUpdateManualTrace("),
                "carriage should create a manual trace when manually dispatched");
        assertTrue(src.contains("updateLivePosition("),
                "carriage should sync live position to the trace");
        assertTrue(src.contains("ShippingTraceService.removeTrace("),
                "carriage should clear its manual trace on stop");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.ManualTraceWiringContractTest`
Expected: FAIL（carriage 部分）。

- [ ] **Step 3: 马车加常量 + 同步字段**

先 Read `CarriageEntity.java` 字段区（autopilotRoute :239 附近）。加：

```java
    private static final int TRACE_LIVE_SYNC_INTERVAL_TICKS = 40; // 2s @20tps
    private int traceLiveSyncTicks = 0;
```

- [ ] **Step 4: 马车 startAutopilot 建手动 Trace**

先 Read `CarriageEntity.startAutopilot`（grep 定位其 return true 前 + 马车的订单 id 字段名，可能同为 `autopilotShipmentShippingOrderId` 或经 manifest 判断）。在发车成功末尾加（transportMode 用 "LAND"，status 用 "IN_TRANSIT"）：

```java
        // webmap: 手动发车建 manual 轨迹
        boolean carriageHasOrder = hasTransportOrder(getPendingShipmentManifest());
        if (!carriageHasOrder) {
            com.monpai.sailboatmod.market.logistics.ShippingTraceService.createOrUpdateManualTrace(
                    level(), getUUID(), new java.util.ArrayList<>(getAutopilotRoutePoints()),
                    carriageShipperUuidForTrace(), carriageNationIdForTrace(),
                    "LAND", "IN_TRANSIT", getX(), getZ());
        }
```

> `hasTransportOrder` 是卸货那轮已加的马车方法（判 manifest 有无订单）——复用它判手动。`getAutopilotRoutePoints()`：若马车暴露 autopilotRoute 的方式不同，用其实际取路点的方式（grep `autopilotRoute` 的 getter；无 getter 则直接用字段 `new ArrayList<>(autopilotRoute)`）。shipper uuid/nation helper 同帆船按现状最简取。

- [ ] **Step 5: 马车 tick 同步坐标**

先 Read `CarriageEntity.tickRailAutopilotDrive`（:525）或主 tick 的 autopilot 段，在服务端 autopilot 激活分支加：

```java
            if (++traceLiveSyncTicks >= TRACE_LIVE_SYNC_INTERVAL_TICKS) {
                traceLiveSyncTicks = 0;
                boolean hasOrder = hasTransportOrder(getPendingShipmentManifest());
                String traceId = hasOrder
                        ? carriageOrderTraceId()
                        : com.monpai.sailboatmod.market.logistics.ShippingTraceService.manualTraceId(getUUID());
                if (traceId != null && !traceId.isBlank()) {
                    com.monpai.sailboatmod.market.logistics.ShippingTraceService.updateLivePosition(
                            level(), traceId, getX(), getZ());
                }
            }
```

> `carriageOrderTraceId()`：马车订单车的 shippingOrderId 来源（grep 马车里订单 id 字段；若马车订单走 manifest 的 shippingOrderId，取 `getPendingShipmentManifest().get(0).shippingOrderId()`）。取不到则只同步手动车。

- [ ] **Step 6: 马车 stopAutopilot 清理**

先 Read `CarriageEntity.stopAutopilot`（:1517）。加：

```java
        if (!level().isClientSide()) {
            com.monpai.sailboatmod.market.logistics.ShippingTraceService.removeTrace(
                    level(), com.monpai.sailboatmod.market.logistics.ShippingTraceService.manualTraceId(getUUID()));
        }
```

- [ ] **Step 7: 跑测试确认通过 + 编译**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.entity.ManualTraceWiringContractTest && ./gradlew compileJava`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 8: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/test/java/com/monpai/sailboatmod/entity/ManualTraceWiringContractTest.java && git commit -m "feat(webmap): carriage creates/syncs/clears manual shipment trace"
```

---

## Task 6: 前端插值 + 实时跟走

**Files:**
- Modify: `src/main/resources/marketweb/map.js`
- Test: `src/test/java/com/monpai/sailboatmod/market/web/MapTraceInterpolationContractTest.java`

> 前端无 JS 测试框架，用 Java 源码断言验证关键代码存在；真实平滑效果留集成实测。

- [ ] **Step 1: 定位现状（只读）**

Run: `cd sailboatmod && grep -n "SHIPMENT_REFRESH_MS\|drawShipments\|shipmentIconPose\|state.shipments\|loadShipments" src/main/resources/marketweb/map.js`
Expected: 列出轮询常量、绘制、图标定位、数据加载点。**据实际行号/变量名调整下面的改动**。

- [ ] **Step 2: 写失败的源码断言测试**

新建 `src/test/java/com/monpai/sailboatmod/market/web/MapTraceInterpolationContractTest.java`：

```java
package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTraceInterpolationContractTest {
    private static String mapJs() throws Exception {
        return Files.readString(Path.of("src/main/resources/marketweb/map.js"));
    }

    @Test
    void pollIntervalIsTwoSeconds() throws Exception {
        assertTrue(mapJs().contains("SHIPMENT_REFRESH_MS = 2000"),
                "shipment polling should align to the 2s backend live sync");
    }

    @Test
    void usesCurrentPositionWithInterpolation() throws Exception {
        String js = mapJs();
        assertTrue(js.contains("shipment.current"),
                "front-end should consume the live current position");
        assertTrue(js.contains("function lerp") || js.contains("interpolate"),
                "front-end should interpolate between polls for smooth motion");
    }

    @Test
    void distinguishesManualVehicleColor() throws Exception {
        assertTrue(mapJs().contains("shipment.manual"),
                "front-end should style manual shipments distinctly");
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.web.MapTraceInterpolationContractTest`
Expected: FAIL。

- [ ] **Step 4: 轮询调到 2 秒**

`map.js:9`：

```javascript
const SHIPMENT_REFRESH_MS = 2000;
```

- [ ] **Step 5: 加插值 helper + 记录 prev/next current**

在 `loadShipments`（约 :1391）里，把新数据合并时为每条 shipment 记录插值起止：先 Read 确认 `state.shipments` 赋值处，改为保留上一帧 current 作为插值起点。在文件顶部 helper 区加：

```javascript
function lerp(a, b, t) {
  return a + (b - a) * Math.max(0, Math.min(1, t));
}
```

在 `loadShipments` 数据赋值处（`state.shipments = data.shipments || [];` 前）加：把旧 shipments 按 shippingOrderId 建索引，对每条新 shipment 写 `_fromX/_fromZ`（旧 current 或新 current）、`_toX/_toZ`（新 current）、`_lerpStartMs`（当前时间戳，由 args 传入或用一个递增帧计数——注意 map.js 是浏览器端可用 `performance.now()`）：

```javascript
  const prevById = {};
  for (const s of (state.shipments || [])) {
    prevById[s.shippingOrderId] = s;
  }
  const next = data.shipments || [];
  const now = performance.now();
  for (const s of next) {
    const prev = prevById[s.shippingOrderId];
    const cur = s.current || (s.points && s.points.length ? s.points[Math.min(s.completedPointCount || 0, s.points.length - 1)] : null);
    const from = prev && prev._toX !== undefined ? { x: prev._toX, z: prev._toZ } : cur;
    s._fromX = from ? from.x : (cur ? cur.x : 0);
    s._fromZ = from ? from.z : (cur ? cur.z : 0);
    s._toX = cur ? cur.x : s._fromX;
    s._toZ = cur ? cur.z : s._fromZ;
    s._lerpStartMs = now;
  }
  state.shipments = next;
```

- [ ] **Step 6: 绘制用插值位置 + 方向 + 手动异色**

先 Read `drawShipments`（:907-921）与 `shipmentIconPose`（:923-937）。把图标位置改为按插值计算（在 `drawShipments` 内每帧）：

```javascript
function shipmentLivePoint(shipment) {
  const t = (performance.now() - (shipment._lerpStartMs || 0)) / SHIPMENT_REFRESH_MS;
  return {
    x: lerp(shipment._fromX || 0, shipment._toX || 0, t),
    z: lerp(shipment._fromZ || 0, shipment._toZ || 0, t)
  };
}
```

在 `drawShipments` 里，载具图标位置改用 `shipmentLivePoint(shipment)`（世界坐标）→ `worldToScreen(...)`；朝向用 `_toX-_fromX, _toZ-_fromZ` 算 `Math.atan2`。手动车配色：

```javascript
    const isManual = !!shipment.manual;
    const doneColor = isManual ? "#f59e0b" : "#0ea5e9";   // 手动橙 / 调度青
    const pendColor = isManual ? "#b45309" : "#2563eb";
    drawRoute(ctx, points.slice(0, completed + 1), false, doneColor, 4);
    drawRoute(ctx, points.slice(completed), true, pendColor, 3);
```

> 图标尾迹/脉动：在图标绘制处加一个随时间脉动的半透明光晕圈（`ctx.globalAlpha` + `performance.now()` 正弦）。保持轻量。

- [ ] **Step 7: 让画面持续重绘（插值需要每帧）**

确认 map.js 有 requestAnimationFrame 渲染循环（grep `requestAnimationFrame`/`scheduleRender`）。若现状只在 loadShipments 后渲染一次，插值不会动——需让 shipment 在途时持续 `scheduleRender()`。在渲染循环里：只要存在 in-transit shipment，就 `requestAnimationFrame` 下一帧。**据现状渲染架构接入**（Read 后定）。

- [ ] **Step 8: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.web.MapTraceInterpolationContractTest`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
cd sailboatmod && git add src/main/resources/marketweb/map.js src/test/java/com/monpai/sailboatmod/market/web/MapTraceInterpolationContractTest.java && git commit -m "feat(webmap): smooth live-position interpolation + manual-vehicle styling"
```

---

## Task 7: 全量测试 + 构建 + 推送

**Files:** 无（验证任务）

- [ ] **Step 1: 全量测试**

Run: `cd sailboatmod && ./gradlew test`
Expected: PASS。重点：`ShippingTraceRecordTest`/`ShippingTraceServiceTest`（既有，确认 record 加字段后已同步补参数）、新增 4 个测试。

- [ ] **Step 2: 完整构建**

Run: `cd sailboatmod && ./gradlew build`
Expected: BUILD SUCCESSFUL，`build/libs/` 出 `-reobf.jar`。

- [ ] **Step 3: 提交剩余并推送（经代理 7897 + gh token）**

```bash
cd sailboatmod && git add -A && git commit -m "test(webmap): logistics trace verified" || echo "nothing to commit"
GH_TOKEN=$(gh auth token); REPO_PATH=$(git remote get-url origin | sed -E 's#https://([^/]*@)?github.com/##; s#\.git$##'); BRANCH=$(git rev-parse --abbrev-ref HEAD); HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 git push "https://x-access-token:${GH_TOKEN}@github.com/${REPO_PATH}.git" "$BRANCH"
```

---

## 集成实测（手动，单测覆盖不到地图/游戏内）

1. 手动发车马车 → 网页地图出现轨迹、图标实时跟走、手动色（橙）。
2. 手动发车帆船 → 同上（水路）。
3. 市场调度发车 → 轨迹照常（调度色 青）、图标也跟走（本次顺带接通调度车实时坐标）。
4. 载具到站/停止 → 手动轨迹从地图消失（不残留）。
5. 多辆同时在途 → 各自独立轨迹。
6. 未登录访客 → 看不到他人轨迹（visibleFor 本人/同国规则）。
7. 旧存档 Trace 加载不报错（NBT 缺 current/manual 回退）。

---

## Self-Review（已对照 spec 核对）

**Spec 覆盖：**
- A 手动发车建 Trace → Task 2（service）+ Task 4/5（载具触发）✓
- B 实时坐标同步（每2秒）→ Task 1（字段）+ Task 2（updateLivePosition）+ Task 4/5（tick 40 tick）✓
- C 可见性与清理 → 手动 Trace 发车标可见状态（Task 2）、stop 清理（Task 4/5）✓
- D 前端插值+视觉 → Task 6（2秒轮询、lerp、方向、手动异色、尾迹脉动）✓
- E 数据流 → 各 Task 串起 ✓
- 验证 → Task 7 + 集成实测 ✓

**与 spec 的现实修正（已在背景注明）：** 现状调度车实时进度也没接，本计划的 tick 同步（Task 4/5）对调度车+手动车都接通——这是 spec 意图的超集，不冲突。

**类型/签名一致性：** `currentX/currentZ/manual` 字段、`withLivePosition(x,z,t)`、`createOrUpdateManualTrace(...)`、`updateLivePosition(level,id,x,z)`、`manualTraceId(uuid)`、`removeTrace(level,id)`、DTO `current`(Point)/`manual`、JSON `"current"`/`"manual"`、前端 `_fromX/_toX/_lerpStartMs`/`lerp`/`shipmentLivePoint` 在各 Task 一致。

**无占位符：** 每步含完整代码；Task 4/5/6 因依赖现状字段名/渲染架构，明确要求"先 Read 定位再按实际调整"，并给确切模板与断言。

**已知不确定（如实标注）：** (1) 载具 shipper uuid/nationId 的最简取法依现状（取不到传 ""，只影响同国可见）；(2) 马车订单 traceId 来源依现状字段；(3) map.js 渲染循环接入（Task 6 Step 7）依现状架构——这三处实现时 Read 后定，断言只验关键调用存在不锁死细节。

**范围：** 本计划只覆盖 webmap 物流轨迹。运输派发（spec 第一部分）、其它独立。
