# 运输派发系统重设计

**日期**：2026-06-14
**状态**：设计已确认，待写实现计划
**适用范围**：sailboatmod 市场订单 → 运输派发，**马车（陆路/驿站）与帆船（水路/港口）统一适用**

---

## Context（背景）

当前市场运输派发是一个"单目的地单车、单产地、贪心装满、纯手动点发、距离最短"的刚性模型（入口 `MarketBlockEntity.dispatchOrder` / `tryDispatchWaitingOrdersAuto`，分组在 `byTargetWarehouse`）。它的缺陷：

- 一律"一目的地一车"，不拼车——小订单车辆利用率低
- 不支持"一车去多个产地接货"（一买家多产地买货要收多车）
- 车不够时直接失败，无排队
- 不检查终端实际停靠的载具状态/数量，运力不是真实有限资源
- 没有"卖家发货 / 买家自提"的履约方区分
- 马车与帆船派发逻辑割裂、各写一套

**目标**：建立一个把"运力"视为"停靠在终端的有限载具资源"的调度系统，支持卖家发货与买家自提、智能拼车与多产地接货、车不够排队，且马车与帆船共用同一套调度逻辑。

---

## 架构：规划层 + 执行层分离（方案 C）

```
待发订单(PurchaseOrder, status=WAITING_DISPATCH)
        │
        ▼
  调度规划层 TransportDispatchPlanner（新增，纯逻辑，可单测）
  定时(每15秒)对 LAND / WATER 各跑一遍：
    1. 收集待发订单
    2. 按【产地终端】聚合
    3. 扫描终端停靠的可用载具（状态 + 数量）
    4. 先多产地接货、后同产地拼车 → 一批 DispatchTask
    5. 载具不够的订单留队列（状态不变，下轮重试）
        │  产出 List<DispatchTask>
        ▼
  执行层（复用现有 + 已实现的多站连运）
  对每个 task：扣货→装车→设路线→发车；到站按目的地卸/接
```

### 统一载具抽象（"船和马车都能用"的关键）

调度层**不直接认识** `CarriageEntity`/`SailboatEntity`，只通过 `TransportMode`（LAND / WATER）切换：

| 抽象 | LAND（马车） | WATER（帆船） |
|---|---|---|
| 终端 Terminal | 驿站 PostStation | 港口 Port Dock |
| 停靠载具 | 驿站 zone 内的马车 | 港口 zone 内的帆船 |
| 路由服务 | `LandTransportNetworkService` | `WaterAutoRouteService` |
| 容量 | 马车库存槽 | 帆船 27 槽 |

拼车 / 接货 / 排队 / 履约方等核心逻辑**只写一份**，两种 mode 共用。一个产地若同时有驿站和港口，两种 mode 各跑一遍调度。

---

## 履约方：卖家发货 vs 买家自提

下单时买家选履约方式，订单带标记。两种方式差异只在"用谁的车、车从哪个终端出"，货流向相同（产地 → 买家目的地）。

`PurchaseOrder` 新增：
- `fulfillment`：`SELLER_SHIP` | `BUYER_PICKUP`
- `pickupMode`（仅自提）：`SYSTEM`（系统代开）| `MANUAL`（玩家亲自开）

| | 卖家发货 | 自提-系统代开 | 自提-玩家亲自开 |
|---|---|---|---|
| 车从哪出 | 产地终端的可派载具（卖家的/可租的） | 买家停靠在**任意终端**的载具（空车先开到产地接货，再送回目的地） | 买家自己驾驶 |
| 是否走调度层 | 是 | 是（载具池=买家在各终端的载具） | **否** |
| 货状态 | WAITING_DISPATCH | WAITING_DISPATCH | PICKUP_LOCKED |

**统一点**：卖家发货与系统代开自提，调度层看到的都是"运输工作单 + 载具池"，只是载具池归属不同（卖家发货用产地可派载具；代开自提用买家在任意终端停靠的载具）。代开自提的载具若不在产地，会先空驶到产地接货——执行层用"多产地接货"同一套"到站装货"机制实现（起点站=买家载具当前所在终端，接货站=产地，送货站=买家目的地）。代开自提因载具是买家专属，一般不与他人拼车，但"同一买家多产地接货"仍适用。

### 玩家亲自自提流程

1. 货在产地仓库标记 `PICKUP_LOCKED`（为该买家锁定，不被卖家发货/调度动用）
2. 买家开自己的车船到产地的港口（水路）/驿站（陆路）
3. **载具进入终端 zone → 自动把锁定的货装进货舱**（检测到是该锁定货的买家本人载具）
4. 装货量 = 载具剩余容量与锁定货量取小；装不下的留仓库继续锁定
5. 只装该买家自己的锁定货，不误装别人的
6. 装货后玩家自行驾驶离开，去向/卸货全由玩家掌控（亲自自提=全权自理）

---

## 调度层核心逻辑

定时 tick 触发（每 15 秒），对 LAND 和 WATER 各跑一遍。

### 步骤 1：收集待发订单
取所有 `WAITING_DISPATCH` 状态、`SELLER_SHIP` 或自提-系统代开的订单。`PICKUP_LOCKED`（玩家亲自自提）不进调度。

### 步骤 2：按产地终端聚合
按订单的产地终端分组——这是拼车边界（"同产地都可拼"）。

### 步骤 3：检查停靠载具
对每个产地终端，扫描 zone 内可用载具，过滤：存活、空闲（非自动驾驶中）、空载、归属允许（卖家的/可租的；自提则买家的）、对应 mode（驿站只算马车、港口只算帆船）。得到该终端此刻可用载具**数量**。复用 `getAvailableSailboatsForDispatch` / `getNearbySailboats` 的状态过滤。

### 步骤 4：先多产地接货（为同一买家集货，独立机制）
识别"同一买家、不同产地、同一目的地"的订单 → 规划一辆车**依次到各产地接货**（贪心最近产地）再送达买家。优先于拼车处理。

### 步骤 5：后同产地拼车（同产地 → 多目的地逐站送）
对一个产地剩余的订单：
1. 按目的地聚类
2. 贪心装车：取一辆可用载具，按"贪心最近下一站"顺序，把去往不同目的地的订单往车上装，直到装满或装完 → 一个 DispatchTask（多目的地逐站送）
3. 还有订单+还有车 → 再开一辆车拼下一批（**车数不限，有车就发**）
4. 车用完还有订单 → 剩余订单留队列（状态保持 WAITING_DISPATCH），下轮 tick 重试

### 步骤 6：产出 DispatchTask
每个 task 含：`{载具, manifest装货清单, 站点序列(接货站/送货站，已按贪心最近排序), mode}`。

### 排队
留队列的订单状态本身就是队列（WAITING_DISPATCH），无需额外结构，下轮 tick 自动重试——"排队等有车"。

---

## 执行层对接

调度层产出 `DispatchTask` 后，执行层：
1. 从产地仓库扣货、`loadCargo` 装车、`setPendingShipmentManifest` 绑清单
2. 按 mode 设第一段路线：陆路 `setLandTransportTask` + `LandTransportNetworkService`；水路设航线 + `WaterAutoRouteService`
3. `startAutopilot` 发车
4. 到站后**复用已实现的多站连运逻辑**（`DockBlockEntity.splitManifestByDestination` + 马车 `finishAutopilot` / 帆船 `finishAutopilotAndUnloadAtDestination` 的续运）
5. **多产地接货是卸货的镜像**：到产地接货站时"装"而非"卸"——需对称新增"到接货站装货"分支（执行层目前只有到站卸货，需补到站装货这一半）

---

## 数据模型变更（最小化）

- `PurchaseOrder` 加 `fulfillment` / `pickupMode` 字段，新状态 `WAITING_DISPATCH` / `PICKUP_LOCKED`（NBT 兼容：旧订单缺字段时默认 SELLER_SHIP）
- 复用 `ShipmentManifestEntry`（已有目的地解析）、`ShippingOrder`
- `DispatchTask` 为内存对象，不持久化（tick 重算）
- 新增 `TransportMode` 枚举（LAND / WATER）抽象终端与路由

---

## tick 调度

- `ServerEvents.onServerTick` 加低频调度计数器（每 15 秒 = 300 tick），调用 `TransportDispatchService.runDispatchCycle(serverLevel)`，沿用现有 `cleanupTickCounter` 模式
- 玩家亲自自提的 zone 触发：在载具 tick / 进 zone 检测里，载具进终端 zone 且该产地有本买家 PICKUP_LOCKED 货 → 自动装货入舱

---

## UI 变更（游戏内 + 网页端都改）

### 游戏内 MarketScreen（已有 DISPATCH 发货页，需适配新调度）

现状：`MarketScreen` 已有 `MarketPage.DISPATCH` 页（`buildDispatchPage` 行 645），三列式：订单列表 / 运输方案 / 发货操作面板，发货发 `DispatchMarketOrderPacket`。这是按"一订单一车手动发"做的，需适配新模型：

- **下单侧（购买时）**：买家下单要能选 `fulfillment`（卖家发货 / 买家自提）和自提的 `pickupMode`（系统代开 / 亲自开）。在购买流程/求购单 UI 加选项。
- **DISPATCH 页适配**：
  - 订单行展示履约方式、当前调度状态（待发/排队/运输中），让玩家看到"排队等车"状态
  - 展示拼车/接货信息（这趟车拼了哪些目的地、经过哪些产地）
  - 自动调度为主后，DISPATCH 页从"手动逐单发"转为"查看调度状态 + 可选手动催发/指定"；保留手动发货入口作为兜底
  - 自提-系统代开：让买家选用哪个 mode 的载具池（可沿用 `TransportTerminalKind` 选择）
- **数据**：扩展 `MarketOverviewData.OrderEntry` 加履约方式/调度状态字段；`DispatchOption` 体现拼车/接货。改 `MarketBlockEntity.buildOverview` 填充。

### 网页端 market web（仓储页新增发货内容）

现状：网页端有"仓储(inventory)"页（`marketweb/app.js`，数据来自 `/api/markets/{id}` 的 `storageEntries`），但**无专门发货页**，只有"重试派发"辅助（`/dispatch/retry`）。

按用户要求"在仓储里增加发货相关内容"：
- 在**仓储页**内增加发货区块：展示该仓储待发订单、调度状态（待发/排队/运输中）、拼车/接货信息
- 复用现有 `detail.orderEntries` / `detail.availableDispatchOptions` 数据（已随 `/api/markets/{id}` 返回）
- 主动发货操作：扩展 `/dispatch/retry` 或新增 `POST /api/markets/{id}/dispatch`（复用 `retryDispatch` 后端逻辑）
- 前端：在 `app.js` 仓储渲染（`renderStorageChoiceGrid` 附近）下方加发货子区块，沿用现有卡片样式
- 后端：`MarketWebService` 对应 endpoint 返回发货/调度状态

### UI 共用数据

游戏内和网页端都基于 `MarketOverviewData`（同源）。本次扩展该数据结构（履约方式、调度状态、拼车/接货摘要）后两端同时受益，避免各写一套。

---

## 关键决策汇总（用户确认）

| 决策点 | 选择 |
|---|---|
| 履约方 | 卖家发货 + 买家自提，下单选 |
| 载具来源 | 停靠终端的现有可用载具，不凭空生成 |
| 自提走法 | 系统代开（用买家任意终端的载具，空驶到产地接货）+ 玩家亲自开（货锁定、进 zone 自动装货） |
| 车不够 | 排队等有车（状态即队列） |
| 拼车范围 | 同产地都可拼（不论卖家） |
| 多产地接货 | 支持（为同买家集货，独立机制） |
| 接货 vs 拼车优先级 | 先接货后拼车 |
| 一产地一轮车数 | 不限（有车就发） |
| 触发 | 自动定时批处理 |
| 调度频率 | 每 15 秒 |
| 运费 | 不算 |
| 站点顺序 | 贪心最近下一站 |
| 适用载具 | **马车 + 帆船统一** |
| UI | 游戏内 DISPATCH 页适配 + 网页仓储页新增发货，两端都改 |

---

## 组件划分（单一职责）

- `TransportMode`（枚举）：抽象 LAND/WATER 的终端类型与路由服务
- `TransportDispatchPlanner`（新增，纯逻辑）：输入"待发订单 + 终端载具快照"，输出 `List<DispatchTask>`。无副作用、可大量单测
- `DispatchTask`（record）：一趟运输的完整描述（载具/清单/站点序列/mode）
- `TransportDispatchService`（新增）：tick 入口，收集订单、查载具、调用 Planner、把 task 交执行层
- 执行层：复用 `CarriageEntity`/`SailboatEntity` 现有发车 + 多站连运，补"到接货站装货"分支
- 数据：`PurchaseOrder` 加字段；自提锁定状态

---

## 验证

1. **单测（调度规划层为主，纯逻辑）**：
   - 同产地拼车：多目的地订单装一车、贪心最近下一站排序
   - 多产地接货：同买家多产地同目的地 → 一车依次接货
   - 先接货后拼车的优先级
   - 车不够 → 部分发 + 剩余留队列
   - 两 mode（LAND/WATER）走同一逻辑、产出正确 task
   - 载具状态/数量过滤正确（忙/有货/非本 mode 的车被排除）
2. **集成（游戏内）**：
   - 卖家发货：多买家同产地下单 → 一车拼单逐站送
   - 买家自提-系统代开：买家载具空车去产地装货送回
   - 买家自提-亲自开：开车进产地 zone → 自动装货
   - 一买家多产地买货 → 一车依次接货再送
   - 车不够 → 订单排队，补车后下轮自动发
   - 马车走陆路、帆船走水路各验一遍
3. **回归**：现有手动发货入口仍可用；已实现的多站卸货不被破坏

---

## 实施分期建议

为控制风险，建议拆成可独立验证的阶段（写实现计划时细化）：
1. 数据模型 + TransportMode 抽象 + 履约方字段 + MarketOverviewData 扩展（履约/调度状态/拼车摘要）
2. TransportDispatchPlanner 纯逻辑 + 单测（拼车/接货/排队/双 mode）
3. TransportDispatchService tick 接入 + 执行层对接（卖家发货走通）
4. 买家自提（系统代开-任意终端载具空驶接货 + 亲自开进 zone 装货）
5. 多产地接货执行层"到接货站装货"分支
6. UI：游戏内 DISPATCH 页适配（下单选履约方、展示调度/拼车状态）+ 网页仓储页新增发货区块
