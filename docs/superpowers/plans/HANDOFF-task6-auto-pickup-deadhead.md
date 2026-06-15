# 交接：Task 6 — 自动自提空驶到产地（AUTO_PICKUP deadhead）

> 本文是 clear 后的续作指引。计划原文：`docs/superpowers/plans/2026-06-15-p2p-freight-phase4-3-pickup.md`（Task 6 + Task 7）。
> 任务追踪：#5「Task6 自动自提-买家车空驶到产地」= in_progress；#6「Task7 全量测试+构建+推送」= pending。

## 当前状态（重要：代码无法编译）

文件 `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`：

- ✅ `runBackgroundAutoDispatch`（行 1048）已在 `hasDispatchable` 早返回**之前**调用 `dispatchAutoPickup(market)`（行 1058）。位置正确：自提单非 WAITING_SHIPMENT，不会被下方 SELLER_SHIP 链路认领，必须独立先跑。
- ✅ `dispatchAutoPickup(MarketSavedData market)`（行 2064）已写好：取 `getLinkedWarehouse()`，用 `market.getPickupOrdersForSourceDock(linkedDockPos)` 过滤 `FulfillmentMode.fromString(order.fulfillment()) == FulfillmentMode.AUTO_PICKUP` 且 buyerUuid 非空，收集进 `LinkedHashSet<String> buyers`，逐个调 `deadheadBuyerVehicleToSource(sourceWarehouse, buyerUuid)`。
- ✅ `deadheadBuyerVehicleToSource(TownWarehouseBlockEntity, String buyerUuid)`（行 2092）已写好：遍历 `List.of(TransportTerminalKind.PORT, TransportTerminalKind.POST_STATION)`，`terminalsForTown(townId, kind)` 取产地终端；若任一产地终端 `availableBuyerVehiclesForPickup(buyerUuid)` 非空 → 跳过该 kind（Task4 就地装货接手）；否则调 `deadheadFromRemoteTerminals(sourceTerminals, buyerUuid, kind)`。

### ❌ 仍缺（导致不编译）

1. **`deadheadFromRemoteTerminals(List<DockBlockEntity> sourceTerminals, String buyerUuid, TransportTerminalKind kind)`** — 被 `deadheadBuyerVehicleToSource` 行 2111 调用但**方法体不存在**。
2. **`planDeadheadRoute(DockBlockEntity fromTerminal, DockBlockEntity toTerminal, TransportTerminalKind kind)`** — 返回 `DeadheadRoute`。
3. **`private record DeadheadRoute(...)`** — 见下。
4. **`import java.util.LinkedHashSet;`** — `dispatchAutoPickup` 用到，当前**未 import**（`java.util.Set` 已在行 74 import）。

插入位置：`deadheadBuyerVehicleToSource` 方法（结束于约行 2113，`}` 后）与 `pickupOrdersForBuyerAtThisSource`（行紧随其后）之间。`DeadheadRoute` record 可放在 `DispatchTerminalPlan` record（约行 2236+ 偏移后）附近。

## 待写方法的精确设计（API 已全部核实）

### `deadheadFromRemoteTerminals`
- 枚举所有终端：`Set<BlockPos> all = kind == POST_STATION ? PostStationRegistry.get(level) : DockRegistry.get(level);`
- 对每个 `pos`：`level.getBlockEntity(pos) instanceof DockBlockEntity remote` 才继续。
- **跳过类型不符**：PORT 时 `remote instanceof PostStationBlockEntity` 跳过；POST_STATION 时 `!(remote instanceof PostStationBlockEntity)` 跳过。（与 `terminalsForTown` 行 1702/1705 同款过滤）
- **跳过产地终端本身**：`sourceTerminals.stream().anyMatch(t -> t.getBlockPos().equals(remote.getBlockPos()))` → 跳过。
- 取该远端终端上买家空闲空车：`List<TransportEntity> vehicles = remote.availableBuyerVehiclesForPickup(buyerUuid);` 空则跳过。
- 对每个产地终端 `sourceTerminal`（遍历 sourceTerminals）：`DeadheadRoute route = planDeadheadRoute(remote, sourceTerminal, kind);` 若非 null → 调 `routeLoadedVehicleToTarget(vehicles.get(0), remote, route.generatedRoute(), route.landPlan(), route.routeIndex(), null)`；成功（返回 true）则 **return**（一次只空驶一辆，下个 tick 再处理其余）。

### `planDeadheadRoute(fromTerminal, toTerminal, kind)` → `DeadheadRoute` 或 null
有向规划「from 终端 → to 终端（=产地终端）」，**不能复用 `resolveDispatchTerminalPlan`**（它要求 source 终端有空闲船、且自行挑终端）。

- **POST_STATION 分支**：
  - 校验 `fromTerminal instanceof PostStationBlockEntity fromStation`、`toTerminal instanceof PostStationBlockEntity toStation`、`level instanceof ServerLevel serverLevel`，否则 return null。
  - `LandTransportNetworkService service = new LandTransportNetworkService();`
  - `RouteAvailability avail = service.planRouteToTown(serverLevel, service.stationRef(level, fromStation), toStation.getTownId() 对应 townId, ALLOW_TERRAIN_FALLBACK_FOR_POST_STATION_DISPATCH);`
    - ⚠ `planRouteToTown` 第 3 参是 **targetTownId（String）**。产地终端的 townId 用 `DockTownResolver.resolveTownForArrival(level, toStation.getBlockPos(), toStation.getTownId())`（与 resolveDispatchTerminalPlan 用 `targetWarehouse.getTownId()` 一致语义；这里目标是产地终端所属 town）。**更稳妥**：直接传 `sourceWarehouse.getTownId()`——但本方法签名没有 warehouse。建议改用 toStation 解析出的 townId。
  - `if (!avail.reachable() || avail.plan() == null) return null;`
  - `LandRoutePlan landPlan = avail.plan();`
  - 校验落点：`if (!landPlan.targetStationPos().equals(toStation.getBlockPos())) return null;`
  - `return new DeadheadRoute(-1, landPlan.route(), landPlan);`
- **PORT 分支**：
  - `int routeIndex = fromTerminal.findRouteIndexByDestinationDock(toTerminal.getBlockPos(), toTerminal.getDockName());`
  - `if (routeIndex < 0) return null;`
  - `return new DeadheadRoute(routeIndex, null, null);`

### `DeadheadRoute` record
```java
private record DeadheadRoute(int routeIndex,
                             @Nullable RouteDefinition generatedRoute,
                             @Nullable LandTransportNetworkService.LandRoutePlan landPlan) {}
```

## 已核实的关键 API 签名（出处行号为本次会话所见）

- `routeLoadedVehicleToTarget(TransportEntity boat, DockBlockEntity sourceDock, @Nullable RouteDefinition generatedRoute, @Nullable LandTransportNetworkService.LandRoutePlan landPlan, int routeIndex, @Nullable Player player)` 返回 boolean（行 1933）。空车场景安全：generatedRoute!=null 走驿站 `setLandTransportTask`+`startAutopilotFromRouteStart`；否则走 `assignLoadedBoatToRouteIndex`。失败会清货/清交付（空车无害）。
- `DockBlockEntity.availableBuyerVehiclesForPickup(String buyerUuid)` → `List<TransportEntity>`（已在 DockBlockEntity，Task5 提交 82e1ae5；用 `PickupLock.vehicleBelongsToBuyer`）。
- `terminalsForTown(String townId, TransportTerminalKind)` → `List<DockBlockEntity>`（行 1692，按 kind 过滤 PostStationBlockEntity，用 DockTownResolver.resolveTownForArrival）。
- `MarketSavedData.getPickupOrdersForSourceDock(BlockPos)` → `List<PurchaseOrder>`（已加，MarketSavedData.java:143；过滤 PickupLock.isPickupOrder）。
- `LandTransportNetworkService.planRouteToTown(Object level, @Nullable StationRef source, String targetTownId, boolean allowTerrainFallback)` → `RouteAvailability`（LandTransportNetworkService.java:79）。
- `LandTransportNetworkService.stationRef(Level level, PostStationBlockEntity station)` → `StationRef`（行 116）。
- `RouteAvailability`：`.reachable()`、`.plan()`（record，行 342）。
- `LandRoutePlan`：`.route()`(RouteDefinition)、`.targetStationPos()`(BlockPos)、`.distanceMeters()`（record，行 358）。
- `DispatchTerminalPlan` record 字段顺序：`(sourceTerminal, targetTerminal, int routeIndex, @Nullable RouteDefinition generatedRoute, @Nullable LandRoutePlan landPlan, double pairScore)`（行 2236）。
- `ALLOW_TERRAIN_FALLBACK_FOR_POST_STATION_DISPATCH = false`（行 82）。
- import 已有：`DockRegistry`(行3)、`PostStationRegistry`(行4)、`LandTransportNetworkService`(行48)、`RouteDefinition`(行49)、`Set`(行74)。`PostStationBlockEntity`/`DockBlockEntity`/`TownWarehouseBlockEntity` 同包（com.monpai.sailboatmod.block.entity）无需 import。`DockTownResolver` 已在用（行1708）。**缺 `java.util.LinkedHashSet`**。

## 测试现状

`src/test/java/com/monpai/sailboatmod/market/PickupWiringContractTest.java` 已含 `autoPickupDeadheadsBuyerVehicleToSource`（行66），断言 MarketBlockEntity 源码含 `dispatchAutoPickup(`、`runBackgroundAutoDispatch`、`PickupLock.isPickupOrder(`、`FulfillmentMode.AUTO_PICKUP`。补完方法后应通过。其余 6 个契约测试 Task3-5 已绿。

## 续作步骤（clear 后照此执行）

1. 补 `import java.util.LinkedHashSet;`（放在 `import java.util.Set;` 附近，行 74 区域）。
2. 在 `deadheadBuyerVehicleToSource` 之后插入 `deadheadFromRemoteTerminals` + `planDeadheadRoute`；在 `DispatchTerminalPlan` record 附近插入 `DeadheadRoute` record。
   - ⚠ **编辑工具坑**：本会话多次因 Edit 的 new_string 含 Java 数组字面量 `new T[]{...}` 的 `{}` 触发「malformed and could not be parsed」。**避免数组字面量**，用 `List.of(...)` 迭代。必要时拆成多个小 Edit。
3. `./gradlew compileJava` 确认编译过。
4. `./gradlew test --tests com.monpai.sailboatmod.market.PickupWiringContractTest` 全绿。
5. （Task 7）`./gradlew test` 全量 + `./gradlew build`（产出 `build/libs/*-reobf.jar`）。
6. 提交 + 推送。
   - **提交信息**（Task 6）：`feat(freight): auto-pickup deadheads buyer vehicle to source terminal when not present`
   - **推送方式**（记忆约束，务必遵守）：经代理 `HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897` + `GH_TOKEN=$(gh auth token)`。直连和 gh_token.txt 都不行。
   - 当前分支 `feature/road-planner-rebuild`。
   - 记忆约束：每个 bug 修复/改动都要 build jar + push to GitHub。

## 需求语义复核（防跑偏）

- 「这个驿站和港口都得做」：PORT + POST_STATION 两类终端都要支持空驶——`deadheadBuyerVehicleToSource` 已遍历两 kind，`planDeadheadRoute` 两分支齐全。
- 空驶 = 买家自己的**空闲空车**（`availableBuyerVehiclesForPickup` 已过滤 alive/in-zone/!autopilot/!hasCargo + 归属买家）从他处终端导航到**产地终端**，到站后由 Task4 进-zone 触发装货、Task5（forwardAutoPickupIfNeeded）续发去收货仓。
- 无可调度车 → 不发车，订单留在 PICKUP_LOCKED 队列，下个后台 tick 重试。
- 计划风险注记：Task6 最险，「执行时若 Read 发现现状 API 不支持，应停下与用户确认」。本会话已逐一核实 API **支持**，故继续实现，无需再确认。
