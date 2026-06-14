# 点对点货运系统设计

**日期**：2026-06-14
**状态**：设计已确认，待写实现计划
**适用范围**：sailboatmod 市场订单 → 点对点货运。三种运输模式；卖家发货/自动自提两种需车模式**船（水路/港口）与马车（陆路/驿站）走同一套逻辑**

---

## Context（背景）

审查发现的根本缺口：现状买家 A 在卖家 B 的店铺购买商品时，`PurchaseOrder.targetDockPos` 被**硬编码为 B 市场绑定的仓库**（`MarketBlockEntity.purchaseListingResolved` 约 :638 `linkedDockPos`），`PurchaseOrder` **没有"买家收货地"字段**。结果：A 买的货会运回 B 市场的仓库，而非 A 自己的收货地。钱的归属正确（扣 A、付 B），但货送错地方——"A 在 B 店买货、发到 A 所在地、进 A 的仓库"这个基本场景当前走不通。

**目标**：建立点对点货运——货从产地送到**买家下单时指定的收货仓库**，支持三种运输模式，运力足够时系统全自动完成，运力不足时排队。

---

## 三种运输模式（用户确认）

| 模式 | 谁的车 | 谁来开 | 触发 | 排队 |
|---|---|---|---|---|
| **① 真人自提** REAL_PICKUP | 买家自己的车 | 玩家亲自开 | 货在产地锁定，等玩家来 | 否（货锁定等人） |
| **② 自动驾驶自提** AUTO_PICKUP | 买家停靠终端的空闲车 | 系统自动驾驶 | 后台自动 | 是（无买家空车则排队） |
| **③ 卖家发货** SELLER_SHIP | 产地终端的空闲车 | 系统自动驾驶 | 后台自动 | 是（无产地空车则排队） |

**真人自提流程**：货在产地仓库为该买家锁定（PICKUP_LOCKED）→ 买家亲自开车船进产地终端 zone → 检测到是该锁定货的买家本人载具 → 自动把锁定货装进货舱（装货量=载具剩余容量与锁定货量取小）→ 玩家自行驾驶送达，去向自理。

---

## 核心约束：两种需车模式船马车统一

**卖家发货（③）与自动自提（②）这两种需车模式，帆船与马车走同一套逻辑。** 调度、排队、配车、点对点路由、到站入仓的核心逻辑**只写一份**，通过 `TransportMode`（LAND/WATER）切换：

| 抽象 | LAND（马车） | WATER（帆船） |
|---|---|---|
| 终端 Terminal | 驿站 PostStation | 港口 Port Dock |
| 停靠载具 | 驿站 zone 内的马车 | 港口 zone 内的帆船 |
| 路由服务 | `LandTransportNetworkService` | `WaterAutoRouteService` |
| 容量 | 马车库存槽 | 帆船 27 槽 |

调度层（`TransportDispatchService`）**只认 `TransportMode`**，不直接 `instanceof CarriageEntity`/`SailboatEntity`。两载具已都实现 `TransportEntity` 接口、`getNearbySailboats` 已统一枚举两类——复用而非重写。选车/装车/发车/到站入仓的差异仅通过 mode 分支或 `TransportEntity` 接口方法吸收。一个产地若同时有驿站和港口，两种 mode 各跑一遍调度。

> 真人自提是第三种（玩家亲自开），不在此统一约束内——它是 zone 触发装货，不经调度层。

---

## 数据模型变更（修审查缺口）

`PurchaseOrder` 新增字段（字符串/long 常量，NBT 兼容，旧订单缺字段时取默认）：

- `fulfillment`：`REAL_PICKUP` | `AUTO_PICKUP` | `SELLER_SHIP`（默认 `SELLER_SHIP`）
- `targetWarehousePos`：**买家下单时选的收货仓库坐标**——替代现状硬编码的 `linkedDockPos`。这是修复审查缺口的核心：货送到这里，不再送 B 市场的仓库。
- 状态扩展（现状 status 是字符串）：`WAITING_DISPATCH`（待发/排队）、`PICKUP_LOCKED`（真人自提货锁定）、`IN_TRANSIT`（在途）、`DELIVERED`（已送达）。沿用现有 status 字段，新值用字符串常量。

> 现状 `targetDockPos`/`targetDockName` 保留（兼容现有发货路径），但新订单的真实目的地以 `targetWarehousePos` 为准；下单逻辑改为写入 `targetWarehousePos` = 买家选的收货仓。

---

## 下单：选模式 + 选收货仓

购买商品 / 下求购单时，买家额外确定两项，随购买 packet 传到服务端写入 `PurchaseOrder`：

1. **运输模式**：① 真人自提 / ② 自动自提 / ③ 卖家发货（默认 ③）
2. **收货仓库**：从买家自己可用的 town 仓库中选一个（默认买家绑定/所属 town 的仓库）

校验：收货仓必须是买家有权写入的 town 仓库；选不到（买家无仓库）时提示并拒绝下单或回退默认。

---

## 调度层（卖家发货 / 自动自提，船马车统一）

新增 `TransportDispatchService`，后台 tick 触发（**每 15 秒 = 300 tick**，沿用 `ServerEvents` 现有低频计数器模式），对 LAND 和 WATER 各跑一遍：

### 步骤
1. **收集待发订单**：取所有 `WAITING_DISPATCH` 状态、`AUTO_PICKUP` 或 `SELLER_SHIP` 的订单。`REAL_PICKUP`（真人自提）不进调度。
2. **按产地终端聚合**：按订单产地（货所在终端）分组。
3. **找空闲车**：对每个产地终端，扫描 zone 内可用载具，过滤：存活、空闲（非自动驾驶中）、空载、归属允许（③用产地可派车 / ②用买家本人的车）、对应 mode（驿站只算马车、港口只算帆船）。复用 `getAvailableSailboatsForDispatch`/`getNearbySailboats` 状态过滤。
4. **点对点配车（首期不拼车）**：一订单一车——取一辆空闲车，装该订单的货，路由到该订单的 `targetWarehousePos` 对应终端。每订单独立一趟 task。
5. **无车排队**：该产地此刻无可用车 → 订单留队列（状态保持 WAITING_DISPATCH），下轮 tick 重试。
6. **自动自提的车来源**：②的车是**买家在该 mode 终端停靠的空闲车**。扫描收敛为买家载具实际所在终端（复用 zone 枚举按归属过滤买家本人车），无则排队。

### 产出 DispatchTask（内存对象，不持久化，tick 重算）
`{载具, 装货清单 manifest, 站点序列(产地→收货仓终端), mode}`。

### 同 tick 去重（无锁）
MC 单线程，无并发。Planner 内维护"本轮已分配载具集合"，选车跳过已分配；执行层 `loadCargo` 成功才标占用。

---

## 执行层（复用现有 + 按 mode 分支）

调度产出 DispatchTask 后：
1. 从产地仓库扣货、`loadCargo` 装车、`setPendingShipmentManifest` 绑清单
2. 按 mode 设路线（见下「路由策略」）
3. `startAutopilot` 发车
4. 到站（买家收货仓对应终端）→ 复用现有到站卸货逻辑（`finishAutopilot`/`finishAutopilotAndUnloadAtDestination` + `splitManifestByDestination`），**但目的地解析用订单的 `targetWarehousePos`**
5. 入仓：货入 `targetWarehousePos` 的 town 仓库（复用 `tryDeliverManifestEntryToWarehouse`，按 recipientUuid=买家入仓）

### 路由策略（陆路简单 / 海运复用寻路）

- **陆路（LAND）**：用现成路网 `LandTransportNetworkService.planRouteToTown`/`planRouteBetweenStations`（已有驿站路网与路由），直接 `setLandTransportTask` 发车。无需新建寻路。
- **海运（WATER）**：**优先复用已生成航线，无则自动寻路并持久化复用**——这套机制 `WaterAutoRouteService` 已实现，调度层接入而非重写：
  1. 源港的航线集合（`getRoutesForMap()`，挂在 dock 上、已持久化）里若有到目标港的可用航线 → 直接走现成航线。
  2. 没有 → 调 `WaterAutoRouteService.submitAutoRoute(...)`：`WaterRoutePathfinder` 自动寻路 → `routeDefinitionFromPath` 生成 `RouteDefinition`。
  3. 生成的航线经 `upsertAutoRoute`（含 `isGeneratedWaterRoute`/`isSameGeneratedWaterRoute` 去重）**存回源港航线集合并持久化**，下次同源→目标港直接复用（步骤 1 命中）。
  4. 寻路失败（无水路可达）→ 该订单留队列/标记不可达，下轮重试（与排队同处理）。
  > 不在点对点调度里新造寻路或缓存——复用 `WaterAutoRouteService` 的"生成+去重+持久化"现成能力。

### 真人自提执行（不经调度层）
- 货在产地仓库标记 `PICKUP_LOCKED`（为该买家锁定，不被调度动用）
- 买家载具进产地终端 zone（复用停靠判定：速度≈0 才检测，非每 tick 全扫）→ 检测到本买家锁定货 → 自动装入货舱
- 只装该买家自己的锁定货，不误装他人；装不下的留仓库继续锁定

---

## 金钱结算（现状已正确，保留）

- 购买时扣买家钱（`MarketWalletService.withdraw` on 买家）、付卖家（`paySeller` deposit，扣销售税/进口关税后）。归属正确，保留现状。
- 发货失败/排队不影响已扣款（货最终会送达或锁定）；真人自提货锁定期间钱已付、货等取。

---

## UI 变更（网页端 + 客户端一致）

### 下单弹窗（两端）
购买/求购弹窗加两项：**运输模式**（三选一单选）+ **收货地**（下拉选择）。

**收货地选项的来源（两端一致）**：
- 读取**绑定玩家所在的 town**——网页端按登录身份解析该玩家的 town（`NationSavedData.getMember(uuid).nationId/townId` 链路），客户端按当前玩家解析其所属 town。
- 据此**列出该玩家可用的收货仓库**作为下拉选项（该 town 内、该玩家有权写入的 town 仓库），玩家从中选一个作为 `targetWarehousePos`。默认选中该玩家绑定/默认仓库。
- 玩家无 town 或无可用仓库时：网页端与客户端都给出明确提示（不能选收货地→不能用②③需送达的模式，或提示先绑定/加入 town）。
- **两端同源**：收货地候选列表由后端统一计算（基于玩家身份的 town），随 `MarketOverviewData`/详情数据下发，网页端和客户端读同一份，避免各算一套。

### 物流状态页（两端）
展示买家的货运订单：货物 / 来源产地 / 收货仓 / 模式 / 状态（待发·排队·在途·已送达·自提待取）/ 载具图标（🐴马车·🚢帆船）/ ETA（复用 `ShippingOrder.distanceMeters/etaSeconds`）。只读展示为主。

### 一致性
网页端（`marketweb`）与客户端（Elementa `MarketScreen`）UI 配套、数据同源（`MarketOverviewData` 扩展模式/收货仓/状态字段），两端同时受益。

---

## 关键决策汇总（用户确认）

| 决策点 | 选择 |
|---|---|
| 三种模式 | 真人自提 / 自动驾驶自提 / 卖家发货 |
| 买家收货地 | 下单时选自己的收货仓库（修审查缺口） |
| 自动化 | 完全后台自动（真人自提除外） |
| 排队 | 只用于需车的两种（②③），无车排队下轮重试 |
| 运力来源 | 停靠终端的现有空闲车/船，不凭空生成 |
| 路由策略 | 陆路用现成路网；海运优先复用已生成航线，无则自动寻路生成并持久化复用（复用 WaterAutoRouteService 现成能力） |
| 拼车/多产地 | **首期不做**，只点对点（一订单一车） |
| **②③船马车** | **走同一套逻辑（TransportMode 切换）** |
| 调度频率 | 每 15 秒 |
| 金钱 | 现状结算正确，保留 |
| 收货地选项 | 基于绑定玩家所在 town 列出其可用收货仓，两端读同一份后端数据 |
| UI | 网页端 + 客户端一致 |

---

## 组件划分

- `PurchaseOrder`：加 `fulfillment` / `targetWarehousePos` 字段 + 状态扩展
- `TransportMode`（枚举 LAND/WATER）：抽象终端与路由，建在现有 `TransportEntity` 之上
- `TransportDispatchService`（新增）：后台 tick，收集订单、查车、点对点配车、排队。**只认 TransportMode**
- `DispatchTask`（record）：一趟运输描述（载具/清单/站点/mode），内存对象
- 执行层：复用现有载具发车 + 到站入仓，目的地改用 `targetWarehousePos`
- 真人自提：货锁定 + 进 zone 装货
- UI：下单弹窗（模式+收货仓）+ 物流状态页，两端

---

## 验证

1. **单测（调度纯逻辑为主）**：点对点配车正确、无车排队、②③走同一逻辑产出正确 task（双 mode）、空闲车过滤正确、同 tick 去重。
2. **集成（游戏内 + 网页）**：
   - A 在 B 店买货选③卖家发货+收货仓 → 货发到 A 的收货仓（**修审查缺口的核心验证**）
   - A 客户端购买、A 网页端购买，目的地都正确解析为 A 选的收货仓
   - ②自动自提：买家空车自动去产地拉回收货仓
   - ①真人自提：货锁定，买家开车进 zone 自动装货
   - 无车 → 排队，补车后下轮自动发
   - 马车走陆路、帆船走水路各验一遍（同一套逻辑）
   - 钱货结算：A 扣款、B 收款、货入 A 仓
3. **回归**：现有购买/钱包/已实现的到站卸货不被破坏。

---

## 实施分期（写实现计划时细化）

1. **数据模型 + 下单选模式/收货仓**：`PurchaseOrder` 加字段，下单写入 `targetWarehousePos`（修审查缺口，卖家发货点对点走通）
2. **调度 + 排队（②③船马车统一）**：`TransportDispatchService` 后台 tick，点对点配车，无车排队，卖家发货自动
3. **自动驾驶自提**：买家空车自动去产地拉
4. **真人自提**：货锁定 + 进 zone 自动装货
5. **两端 UI**：下单弹窗（模式+收货仓）+ 物流状态页（客户端 Elementa + 网页一致）
