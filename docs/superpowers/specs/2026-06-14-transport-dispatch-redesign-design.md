# 运输派发系统重设计

**日期**：2026-06-14
**状态**：物流部分已按现状收敛（2026-06-14 二次收敛：补并发/执行约束、ETA 复用、钱包拆层、刷金展开为第三部分），待写实现计划
**适用范围**：sailboatmod 市场订单 → 运输派发，**马车（陆路/驿站）与帆船（水路/港口）统一适用**；含市场定价重设计（第二部分）与钱包事务正确性 + 刷金修复（第三部分）

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

> **扫描收敛澄清（不与"任意终端"矛盾）**：买家载具确实**可以**停在任意终端；但调度扫描时**不遍历世界全部终端去找买家有没有车**，而是以"买家载具实际所在的那些终端"为起点（按归属过滤出买家本人空闲载具）。即载具来源不限终端，但扫描成本按"买家车的实际位置"收敛，不做全图扫。详见下文「并发与执行约束」。

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

## 并发与执行约束（收敛新增）

Minecraft 服务端是**单线程主循环**（server tick），不存在多线程并发改仓库/载具。原评审担心的"竞态/加锁"在此模型下不成立——所谓冲突只发生在**同一调度周期内的重复分配**，去重即可，无需任何锁。本节把这些执行边界写死，避免实现时误判。

- **同 tick 去重(替代"加锁")**：`TransportDispatchPlanner` 内部维护一个"本轮已分配载具集合"，选车时跳过已被本轮其他 task 选中的载具；执行层 `loadCargo` 装车**成功**后才把载具标记为占用（busy）落地。从"被选中"到"真正发车"之间不存在并发改写，因此不需要锁，只需保证同一快照内不重复选同一辆车。
- **"空载/空闲"过滤只在选车那一刻生效**：步骤 3 的"存活/空闲/空载/归属/对应 mode"过滤，**仅用于调度选车的瞬间**。一个 task 一旦开始执行（尤其多产地接货：车依次到各产地接货，第二站起车已非空载），**不再受"空载"约束**——这是同一辆车在一个 task 内的多次装货，不是新调度。实现时不得用"空载"误判正在执行接货的车。
- **自提-系统代开的扫描收敛**：不全图扫描"买家在任意终端停靠的载具"（那是 O(终端数×自提订单数)）。**收敛为：只扫描买家在该 mode 下的载具当前所在终端**——复用 `getNearbySailboats` 的 zone 枚举能力，按归属过滤出买家本人的空闲载具。若买家此刻无可用载具，该自提订单**留队列**，下轮 tick 重试。扫描范围由"全部终端"收敛为"买家载具实际所在的少数终端"。
- **进 zone 自动装货的节流**：玩家亲自自提的"进终端 zone → 自动装锁定货"检测，**复用载具已有的停靠判定（速度≈0 才检测）**，而非每 tick 全量扫描。仅在载具实际停靠在终端 zone 时触发一次锁定货匹配。

## ETA（收敛澄清：已有数据源，无需新建）

ETA 不是待解决问题——`ShippingOrder` **已有 `distanceMeters` / `etaSeconds` 字段**，`LandTransportNetworkService` 的 `ReachableTown` / `LandRoutePlan` 已计算 `distanceMeters` / `etaSeconds`（`estimateEtaSeconds(distanceMeters)`）。UI 的 ETA 直接读这些已有字段即可。水路若缺 ETA，按距离用同一 `estimateEtaSeconds` 估算补齐。**不新增 ETA 子系统。**

## 数据模型变更（最小化）

- `PurchaseOrder` 加 `fulfillment` / `pickupMode` 字段，新状态 `WAITING_DISPATCH` / `PICKUP_LOCKED`（现状 `status` 即字符串，新增状态用**字符串常量**，与现状一致，不引枚举；NBT 天然兼容：旧订单缺字段时默认 SELLER_SHIP）
- 复用 `ShipmentManifestEntry`（已有目的地解析）、`ShippingOrder`（已含 `distanceMeters`/`etaSeconds`，供 ETA）
- `DispatchTask` 为内存对象，不持久化（tick 重算）
- 新增 `TransportMode` 枚举（LAND / WATER）抽象终端与路由，**建在现有 `TransportEntity` 接口之上**（马车/帆船均已实现该接口，`getNearbySailboats` 已统一枚举两类，不从零造抽象）

> **现状已实现、本次复用而非新建**：多站连运（水陆 `finishAutopilotAndUnloadAtDestination` / `finishAutopilot` + `DockBlockEntity.splitManifestByDestination`）、统一载具枚举（`getNearbySailboats` / `getAvailableSailboatsForDispatch`）、ETA 字段。执行层**唯一要补**的是"到接货站装货"这一镜像分支（现状只有到站卸货）。

---

## tick 调度

- `ServerEvents.onServerTick` 加低频调度计数器（每 15 秒 = 300 tick），调用 `TransportDispatchService.runDispatchCycle(serverLevel)`，沿用现有 `cleanupTickCounter` 模式
- 玩家亲自自提的 zone 触发：在载具 tick / 进 zone 检测里，载具进终端 zone 且该产地有本买家 PICKUP_LOCKED 货 → 自动装货入舱

---

## UI 变更（游戏内 + 网页端都改）

### 游戏内 MarketScreen（Elementa UI，发运页重构 + 购买弹窗 + 钱包区）

UI 框架：**Elementa**（`gg.essential.elementa`）。现状 `MarketPage.DISPATCH` 页（`buildDispatchPage` 行 645）是三列"订单/船只/发运操作"的逐单手动派发，与新自动调度模型不匹配，重构为**双视角页**。

#### 1. 发运页重构：上"卖家发货" / 下"买家物流"两区

玩家同时是卖家和买家，发运页垂直分两区，各自滚动：

```
┌─ 商品 │ 大盘指数 │ 上架 │ [发运] │ 结算 │ 求购订单 ──── 刷新 绑定仓库 取消 ┐
├──────────────────────────────────────────────────────────────────────┤
│  ▼ 我的发货（作为卖家）                          本仓 ▾   自动调度:开 │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ 🛒 橡木原木 x64 → 克里米亚      [运输中] 🚢大猴号 拼车2单 ETA 90s│ │
│  │ 🛒 铁锭 x32 → 北境           [排队中] 等待载具…              │ │
│  │ 🛒 石头 x128 → 克里米亚+北境   [待发] 🐴克里米亚运输1 ETA --   │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                          [手动催发选中订单] ← 兜底  │
├──────────────────────────────────────────────────────────────────┤
│  ▼ 我的物流（作为买家）                                            │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ 📦 小麦 x64 从 绿谷 → 我   [运输中] 🚢 ETA 120s  卖家发货      │ │
│  │ 📦 金锭 x16 从 矿镇 → 我   [自提待取] 🐴 货在矿镇驿站锁定 亲自取│ │
│  │ 📦 钻石 x4  从 深渊 → 我   [已送达] ✓ 已入仓                  │ │
│  └──────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

- **上区（卖家发货）**：每行=我作为卖家的待发/在途订单，显示 货物 / 目的地 / 状态(待发·排队·运输中) / 载具(🐴马车·🚢帆船图标区分陆路驿站/水路港口) / 拼车数 / ETA。自动调度为主，保留"手动催发选中订单"兜底按钮。
- **下区（买家物流）**：每行=我买的货，显示 货物 / 来源 / 状态(运输中·自提待取·已送达) / 履约方式。只读展示，不操作。
- **载具图标区分**：中列不再是独立"选车"列（车由调度自动分配）；载具信息内嵌在订单行，🐴=马车(POST_STATION)，🚢=帆船(PORT)。

#### 2. 购买商品弹窗：选发货方式（履约方在此确定）

购买/下求购单时弹出，确定 `fulfillment` + `pickupMode`：

```
┌─ 购买：橡木原木 x64 ───────────────────┐
│  单价 12   总价 768                     │
│  目的地：[我的仓库 克里米亚 ▾]           │
│  发货方式：                             │
│   ◉ 卖家发货      （卖家调度送到我仓库） │
│   ○ 自提-系统代开 （用我的车去产地接货） │
│   ○ 自提-亲自开   （我开车去产地，进港自动装货）│
│  [ 确认购买 ]   [ 取消 ]                 │
└─────────────────────────────────────────┘
```

选项映射：卖家发货→`SELLER_SHIP`；自提-系统代开→`BUYER_PICKUP`+`SYSTEM`；自提-亲自开→`BUYER_PICKUP`+`MANUAL`。随购买 packet 传到服务端写入 PurchaseOrder。

#### 3. 结算页钱包区重构（存取入口显式化）

> **依赖**：本节是**钱包显示层**，依赖下文「第三部分：钱包事务正确性」先完成。`linkedWarehouseGoldValue` 公开盘点方法既供本节显示，也是事务层修复要复核的同一笔账；存入/提回按钮调用的 `cashToWallet`/`walletToCash` 必须是事务层修复后的原子版本，否则会把刷金洞带进新 UI。**先做事务层，再做本显示层。**

现状（`buildFinanceAction` 行 1969）问题：存款入口不明显（输入框空"金额"占位符、看不出可存多少）、多个 `%s` 占位符未填（`market.pending`="待领款：%s" 等被当文本直显）。重构：

```
┌─ 领款 / 钱包 ─────────────────────────────────┐
│  可存入来源（自动检测）                          │
│  ┌────────────────────────────────────────┐   │
│  │ 💰 来源：仓库金（无经济插件）             │   │  ← 有Vault→"插件钱包"
│  │    可存入：288  （金锭×16 = 288）         │   │  ← 实时数值
│  └────────────────────────────────────────┘   │
│  钱包余额：1,024     冻结：0                     │
│  金额：[__288__] (默认填可存入上限，可改)        │
│  [ ⬇ 存入钱包 ]   [ ⬆ 提回仓库 ]               │
│  ──────── 内部转账（所有者）────────            │
│  [钱包→国库] [国库→钱包] [待领→钱包]            │
└────────────────────────────────────────────────┘
```

- **可存入来源自动检测**：有 Vault 插件（`VaultEconomyBridge.getBalanceByIdentity` 非空）→ 显示"插件钱包"及余额；无插件 → 显示"仓库金"及**绑定仓库**金等值。
- **仓库金等值（复用现有后端）**：`MarketWalletGoldSource` 已有 `withdrawFromLinkedWarehouse`/`depositToLinkedWarehouse` 和 `planRemoval` 里的"绑定仓库金盘点"（`countMatchingStock × unitValue` 累加）。只需**把该盘点提取为公开方法** `linkedWarehouseGoldValue(market, ownerId)`（只算账本页那个已绑定仓库，不含玩家背包），供两端 UI 显示。无需全新实现。
- **存入/提回方向**：存入=仓库金/插件钱包→市场钱包（`MarketWalletGoldSource.withdrawFromLinkedWarehouse`）；提回=市场钱包→**放回绑定仓库**（`depositToLinkedWarehouse`，已实现）/ 插件钱包。
- **金额输入框默认填可存入上限**，玩家可改。
- **修 `%s` bug**：`待领款`/`净收支`/`收入` 等 metric 用 `Component.translatable(key, value)` 正确填参，而非把含 `%s` 的翻译当纯文本。

#### 4. 数据

扩展 `MarketOverviewData`：OrderEntry 加 履约方式/调度状态（卖家发货区用）；新增"我的买家物流"条目列表（买家物流区用，含来源/状态/履约）；钱包区加 `depositSourceKind`(VAULT/WAREHOUSE_GOLD)、`depositableAmount`（来自 `linkedWarehouseGoldValue` 或 Vault 余额）。`DispatchOption` 体现拼车/接货。改 `MarketBlockEntity.buildOverview` 填充。**游戏内与网页端共用此数据**，钱包升级两端同步受益。

### 网页端 market web（仓储页新增发货内容）

现状：网页端有"仓储(inventory)"页（`marketweb/app.js`，数据来自 `/api/markets/{id}` 的 `storageEntries`），但**无专门发货页**，只有"重试派发"辅助（`/dispatch/retry`）。

按用户要求"在仓储里增加发货相关内容"：
- 在**仓储页**内增加发货区块：展示该仓储待发订单、调度状态（待发/排队/运输中）、拼车/接货信息
- 复用现有 `detail.orderEntries` / `detail.availableDispatchOptions` 数据（已随 `/api/markets/{id}` 返回）
- 主动发货操作：扩展 `/dispatch/retry` 或新增 `POST /api/markets/{id}/dispatch`（复用 `retryDispatch` 后端逻辑）
- 前端：在 `app.js` 仓储渲染（`renderStorageChoiceGrid` 附近）下方加发货子区块，沿用现有卡片样式
- 后端：`MarketWebService` 对应 endpoint 返回发货/调度状态

### 网页端钱包升级（与游戏内同逻辑）

> **依赖**：同游戏内钱包显示层，本节依赖「第三部分：钱包事务正确性」先完成。网页端 `cashToWallet`/`walletToCash`（`MarketWebService`）正是刷金主洞所在——**事务层修好之前不要为了"显示可存入数值"去碰这段事务逻辑**，否则会沿用或扩散漏洞。

现状：网页端已有钱包区（`app.js` 的 `wallet-dock`、转账输入框，后端 `MarketWebService.transferWallet` 含 CASH_TO_WALLET/WALLET_TO_CASH 等动作，`MarketWalletGoldSource` 已实现仓库金存取）。但与游戏内同样的痛点：**可存入数值不显式**，玩家看不出能存多少、来源是什么。

按游戏内同一套设计升级（共用 `MarketWalletGoldSource.linkedWarehouseGoldValue` + Vault 检测）：
- **显式"可存入来源"卡片**：`/api/markets/{id}` 返回的 `depositSourceKind`(VAULT/WAREHOUSE_GOLD) + `depositableAmount`，前端展示"来源：插件钱包/仓库金"及实时数值。
- **存入/提回按钮**：复用现有 `transferWallet` 的 CASH_TO_WALLET（存入）/ WALLET_TO_CASH（提回放回绑定仓库）动作；转账输入框默认填 `depositableAmount`。
- **数据同源**：`depositSourceKind`/`depositableAmount` 已在 `MarketOverviewData`（见上"数据"小节），游戏内与网页端共用，无需各算一套。
- 注意：网页前端代码在 `market/web/` 与 `marketweb/`，与当前进行中的 webmarket 工作重叠——此项需与 webmarket 协调或在其完成后实现。

### UI 共用数据

游戏内和网页端都基于 `MarketOverviewData`（同源）。本次扩展该数据结构（履约方式、调度状态、拼车/接货摘要、钱包可存入来源/数值）后两端同时受益，避免各写一套。

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
| 并发模型（收敛） | 单线程 tick，无锁；同 tick 已分配载具去重，装车成功才占用 |
| 空载过滤生效时机（收敛） | 仅选车那一刻；task 执行期（接货车非空）不受约束 |
| 自提扫描范围（收敛） | 只扫买家载具当前所在终端，非全图；无车则留队列 |
| 进 zone 装货节流（收敛） | 复用停靠判定（速度≈0 才检测），非每 tick 全扫 |
| ETA 来源（收敛） | 复用 `ShippingOrder.distanceMeters/etaSeconds`，不新建 |
| 钱包（收敛） | 拆事务正确性层（后端，含刷金修复，优先级最高）+ 显示层（UI，依赖前者） |
| UI | 发运页重构为上卖家发货/下买家物流双区；购买弹窗选发货方式；游戏内+网页钱包存取入口显式化（仓库金等值/Vault 自动检测，复用 MarketWalletGoldSource）；修 %s 占位符 bug。两端都改 |

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

# 第二部分：市场定价模型重设计

> 与上面的运输派发重设计同属"市场系统重构"，合并在本 spec 统一管理。这部分聚焦商品定价；运输部分见上。

## Context（定价部分背景）

现状定价用动态公式 `basePrice × volatilityFactor^(-stock)`，把"卖家定价权"和"市场参考价"混在一起，导致两个严重问题：

1. **批量上架崩盘**：上架立即 `+stock`（`MarketBlockEntity.createListingFromDockStorage` 约 :520 的 `adjustCommoditySupply`），批量上架 2000 个 → stock 暴增 → `basePrice × 1.01^(-2000) ≈ 0` 价格压崩 → 价格窗口太低，无法再上架。
2. **改已挂单价**：`currentListingUnitPrice()`（约 :2066）对"约束定价"商品每次成交/查看时用动态价重算，已上架挂单价被库存反复改写——不符现实（挂单价应由卖家定死）。
   且 `CommodityMarketState.currentStock` 全局共享，一个市场批量上架影响所有市场。

**用户决定**：去掉系统动态定价，仅保留"参考价"用于定价建议和价格保护——价格不会太高也不会太低，且挂单价由卖家定死、永不被系统改写。

## 设计

### 核心原则
**挂单价定死 + 成交撮合 + 参考价保护**。系统不再用库存公式驱动任何实际价格。

### 1. 删除动态定价公式
- `CommodityMarketService` 的 `basePrice × volatilityFactor^(-stock)` 不再驱动任何实际价格；库存不再影响价格（根除批量上架崩盘）。
- `currentListingUnitPrice()`（约 :2066）**统一返回 `listing.unitPrice()`**，不再调 `quoteCommodity()` 重算。去掉"约束定价/无约束"两套逻辑，统一为"挂单价定死"。

### 2. 参考价 = 最近成交均价（回退基准价）
- 参考价随真实成交浮动，贴近现实。
- 计算：取该商品**最近 N 笔成交**（N 默认 20，可调）的成交均价；数据源是现有 `CommodityTradeRecord`（成交时 `applyTrade` 已写 SQLite）。
- **回退**：无成交记录时（初期）回退到 `basePrice`（现有 `estimateBaseUnitPrice`）做初始锚。
- 新增：`CommodityMarketRepository.recentTradeAveragePrice(commodityKey, n)` SQL 查询 + `CommodityMarketService.referencePrice(key)`（无记录返回 basePrice）。

### 3. 价格保护：参考价 ±50%
- 上架时卖家自己定价，但 `requestedUnitPrice` 必须落在 `[参考价 × 0.5, 参考价 × 1.5]` 内，否则上架被拒并提示。
- 替换现状 `listingPriceWindow`（约 :2055，基于动态价 ±10%）→ 基于**参考价 ±50%**。范围常数（0.5/1.5）集中在 `MarketPricePolicy`。

### 4. 挂单价定死
- `MarketListing.unitPrice` 上架时按卖家输入固定，之后**永不被系统改写**。
- 成交后**不再**用 `currentListingUnitPrice` 重算回写 listing（去掉约 :620 回写）。

### 5. 指标改为派生（截图里的"最低卖价/参考价"）
- **最低卖价** = 扫描该商品当前所有活跃挂单取最低 `unitPrice`（成交撮合派生），不再来自公式。
- **参考价** = 最近成交均价（§2）。**24h 均价/成交量** = `CommodityTradeRecord` 历史聚合。
- 改 `MarketBlockEntity.buildOverview` 及 `MarketOverviewData` 对应字段来源。

### 6. 上架不再立即 +stock
- 去掉 `createListingFromDockStorage`（约 :520）的 `adjustCommoditySupply`——stock 不再驱动价。
- stock 仅作"在售数量"统计（如需），不参与定价。
- 成交时 `applyCommodityDemand`（约 :605）保留——它写 `CommodityTradeRecord`（参考价数据源），但不再驱动价格公式。

## 受影响代码点（现状→新）

| 现状 | 位置 | 新方向 |
|---|---|---|
| 上架立即 +stock | `MarketBlockEntity.createListingFromDockStorage` ~:520 | 删除 `adjustCommoditySupply` |
| `currentListingUnitPrice` 动态重算 | `MarketBlockEntity` ~:2066 | 统一返回 `listing.unitPrice()` |
| 成交回写 listing 价 | `MarketBlockEntity.purchaseListingResolved` ~:620 | 删除回写 |
| 价格窗口 ±10% 基于动态价 | `MarketBlockEntity.listingPriceWindow` ~:2055 + `MarketPricePolicy` | 参考价 ±50% |
| "最低卖价/参考价" 来自公式 | `MarketBlockEntity.buildOverview` | 派生（最低挂单 / 成交均价）|
| 动态定价公式 | `CommodityMarketService.buildQuote/getStockPrice/getVolFactor` | 不再驱动实际价；保留成交记录写入 |
| 成交均价查询（新增）| `CommodityMarketRepository`/`CommodityMarketService` | `recentTradeAveragePrice(key,n)` 回退 basePrice |

## 定价部分关键决策（用户确认）

| 决策点 | 选择 |
|---|---|
| 动态定价公式 | 删除，不再驱动实际价 |
| 参考价来源 | 最近成交均价（回退 basePrice） |
| 最近 N 笔 | N=20（可调） |
| 价格保护范围 | 参考价 ±50%（50%~150%） |
| 挂单价 | 卖家定死，永不被系统改写 |
| 最低卖价 | 当前最便宜活跃挂单（派生） |
| 上架 +stock | 删除 |

## 定价部分验证

1. **单测**：`referencePrice` 无记录回退 basePrice / 有 N 笔返回正确均价；价格保护 ±50% 内通过、超出被拒；`currentListingUnitPrice` 恒等于 `listing.unitPrice()`（不随 stock 变）。
2. **集成**：批量上架 2000 个不崩、仍可上架；成交后已有挂单价不变、参考价随成交移动；上架价超 ±50% 被拒；"最低卖价"显示最便宜挂单。
3. **回归**：购买/成交流程可用；钱包/结算不受影响。

> 注：`GoldStandardEconomy.goldItemMarketValue` 用 commodity 价给金估值——本重设计后金价不再被库存压崩，金估值随之稳定。但**这只解决"金估值波动"，不解决刷金本身**。刷金的真实根因（存入侧扣源落空/非原子）及其修复见下方**第三部分：钱包事务正确性与刷金修复**（已从原"方案另定"展开为正式章节）。

---

# 第三部分：钱包事务正确性与刷金修复

> 与运输、定价同属"市场系统重构"。本部分是**钱包事务正确性**的后端基础层，**优先级高于所有 UI**（含第一部分的钱包显示层、网页端钱包升级）。两端钱包 UI 共用这一份后端，事务修对了两端同时安全。**涵盖三类缺陷**：(A) 刷金（存入侧扣源落空/非原子）、(B) 金双向口径不一致、(C) 钱包归属/越权（玩家级钱包被展示成市场级、个人数据对未登录 guest 泄露）。

## Context（刷金真实根因）

刷金 bug 的真实路径（用户确认）：**从绑定仓库存放的金类物品，经钱包"存入"时不会实际消耗这些物品，但钱包余额照加；提款又能成功把金提回仓库 → 仓库金没少、钱包却多了 = 刷金。经济插件（Vault）路径同样有此问题。**

定位到的代码（`MarketWebService.cashToWallet` 行 312-325，游戏内 `MarketWalletActionPacket.cashToWallet` 同构）：

```java
Boolean withdrawn = onlinePlayer != null
        ? GoldStandardEconomy.tryWithdraw(onlinePlayer, amount)        // 背包/Vault
        : GoldStandardEconomy.tryWithdrawByIdentity(uuid, name, amount); // 离线:仅 Vault
if (!Boolean.TRUE.equals(withdrawn)
        && !MarketWalletGoldSource.withdrawFromLinkedWarehouse(...)) {  // 兜底:仓库
    return failure("insufficient_cash");
}
MarketWalletService.deposit(...);  // 加钱包
```

**根因（主）**：扣源与加钱包**非原子**，且 Vault 路径与仓库路径是 `||` 短路——**只要前者返回成功就不再验证后者，扣减可能落空但钱包照加**。`tryWithdrawByIdentity` 仅问 Vault；当 Vault 桥返回"成功/非 null"语义不严谨、或玩家离线无法真正扣物时，仓库扣减（`withdrawFromLinkedWarehouse`）被短路跳过，仓库金一点没少，钱包却 `deposit` 加满。`walletToCash`（行 327-344）反向同构：先扣钱包，给付失败时把钱"退回钱包"，存在给付与回退不一致的口子。

**根因（次）**：金的双向估值口径不一致——`goldItemMarketValue`（存入侧估值，被 `BankActionPacket:124`/`BankMenu:113`/`BankLoanService:335` 调用）用 commodity **动态 basePrice**；`MarketWalletGoldSource` 提取侧用**固定率** `BALANCE_PER_GOLD_INGOT=18`。当金价被推离 18 时，高价存、低价取，套利刷金。

> **澄清**：早期排查曾怀疑"多线程并发改仓库"——**不成立**。Minecraft 服务端是单线程主循环，无真并发，无需加锁。真正的洞是上面两条事务/口径问题。

## Context（钱包归属与越权——独立安全缺陷）

钱包**数据模型本身是正确的**：后端 `MarketWalletService` 已按 `playerUuid` **一人一账**（账户存于 `MarketWalletSavedData`，按玩家 UUID 索引）。问题在**展示/取数的身份处理**，造成"看起来像市场公共钱包、且跨玩家泄露"：

1. **只读接口对未登录访客开放**：`GET /api/markets/{id}`（`MarketWebServer` 行 286-288）走 `resolveIdentityOrGuest`，而该接口返回 `walletBalance`/`walletReservedBalance`/`myOrders` 等**个人数据**。对比转账接口 `transferWallet`（行 300 `requireIdentity` + 行 295 校验 `playerUuid != null`）是有保护的——**但只读的余额/订单展示没有要求登录**。
2. **访客合并到同一空账户**：未登录访客 fallback 为 `new MarketPlayerIdentity(null, "", null)`（行 912）。链路 `marketDetail` → `overview` 用 `identity.playerUuidString()=""` → `MarketWalletService.getAccount(level, "", "")` → **所有访客共享同一个 `playerUuid=""` 账户**。即截图里"市场钱包 0 / 当前市场账户 · GoatDie's Market"——展示的是这个空账户，且任何访客看到/动用的都是它。
3. **文案误导**：`marketweb/app.js:953` `wallet_balance="市场钱包"`、`:962` `wallet_topbar_hint="当前市场账户"`，把玩家级钱包叫成"市场账户"，强化"人人共享一个市场钱包"的错觉。
4. **客户端同构**：游戏内 `MarketScreen` 钱包区同样需要核对——确保它取的是**当前玩家**的账户、文案不暗示市场公共账户（lang `screen.sailboatmod.market.wallet.*` 仅"钱包"，需确认 UI 实际绑定的身份为本人）。

> **泄露面定性**：不是 A 看到 B 的真实余额，而是**所有未登录用户被映射到同一个空 UUID 账户、且本应登录才可见的个人数据对 guest 暴露**。属后端**授权缺陷 + 身份合并**，非文案问题（文案是次要的误导放大）。

## 设计

### 1. 存入事务原子化（主修复）

`cashToWallet`（网页端 + 游戏内 + 任何 Vault 路径）统一为**"先确凿扣源、扣成功才加钱包、失败全回滚"**：

- 扣源三选一（在线背包 / Vault / 绑定仓库），**必须有且仅有一个真正扣减成功**，才执行 `MarketWalletService.deposit`。
- 去掉"前者返回真就跳过后者验证"的 `||` 短路歧义：每个扣源返回值语义收敛为明确的"确实扣了 amount 等额 / 没扣"，`tryWithdrawByIdentity`/Vault 桥的 null/false/true 三态必须区分清楚，"未知/不可用"绝不能当成"已扣"。
- 任一步失败 → 不 `deposit`、已扣的源**全额回滚**，返回 failure。

### 2. 提取事务对称化

`walletToCash` 对称：先扣钱包 → 给付（Vault / 放回绑定仓库）→ **给付确认成功才算完成**；给付失败 → 钱包**精确回滚**原额（不重复 `deposit`、不产生黑洞或双份）。

### 3. 金双向口径统一（次修复）

金作**硬通货**，双向换算统一用**固定率**（`BALANCE_PER_GOLD_*`），`goldItemMarketValue` 不再走 commodity 动态价。金不参与市场涨跌，彻底消除"高估值存、低固定率取"的套利（用户确认：双向固定率）。

### 4. 盘点方法即真账

`linkedWarehouseGoldValue(market, ownerId)`（显示层要新增的公开盘点方法）与 `withdrawFromLinkedWarehouse` 的实际扣减**必须基于同一口径同一仓库**——显示的"可存入数值"就是真正能扣到的数值，避免"显示能存 288、实际扣不出 288 却已加钱包"。

### 5. 钱包归属与越权修复（玩家级钱包，非市场级）

明确钱包是**玩家级、一人一个**，与"市场"无关——市场只是访问入口，不是账户归属。

- **个人数据接口要求登录**：返回 `walletBalance`/`reservedBalance`/`myOrders` 等个人字段的接口（`GET /api/markets/{id}` → `marketDetail`）改为 `requireIdentity`，或在 `marketDetail` 内**对未登录身份不返回任何钱包/订单字段**（只返回公开的挂单/行情）。未登录不得看到任何账户数值。
- **禁止空账户合并**：`playerUuid` 为 null/空白时**绝不**调用 `getAccount(level, "", "")`。`MarketWalletService.getAccount` 对空身份直接返回"无账户/0 且不可操作"，杜绝所有访客落到同一个 `""` 账户。
- **取数身份即本人**：钱包余额、可存入数值、myOrders 一律以**已认证的当前玩家 `playerUuid`** 取数；任何地方都不得用市场 `ownerUuid` 或匿名身份代查他人账户。
- **文案去歧义**：`marketweb/app.js` 的 `wallet_balance`"市场钱包"、`wallet_topbar_hint`"当前市场账户"改为表达"**我的钱包 / 个人余额**"（如"我的钱包"+"在 GoatDie's Market 交易"），不再暗示市场公共账户。游戏内 lang `screen.sailboatmod.market.wallet.*` 同步校准。
- **客户端同步核对**：游戏内 `MarketScreen` 钱包区确认绑定当前玩家身份、文案不暗示市场公共账户。两端展示同源于本人账户。

## 受影响代码点（现状→新）

| 现状 | 位置 | 新方向 |
|---|---|---|
| 存入非原子 + Vault/仓库 `||` 短路 | `MarketWebService.cashToWallet` ~:312、`MarketWalletActionPacket.cashToWallet` | 先确凿扣源、扣成功才加钱包、失败全回滚 |
| 提取给付失败处理含糊 | `MarketWebService.walletToCash` ~:327、`MarketWalletActionPacket.walletToCash` | 扣钱包→给付确认→失败精确回滚 |
| `tryWithdrawByIdentity` 仅问 Vault、三态不清 | `GoldStandardEconomy` :64-70 | null/false/true 三态明确，"不可用"≠"已扣" |
| 金估值用动态 basePrice | `GoldStandardEconomy.goldItemMarketValue` :19-31 | 统一固定率，不走 commodity |
| 仓库金盘点未公开 | `MarketWalletGoldSource.planRemoval` 内联盘点 | 提取为公开 `linkedWarehouseGoldValue`，与扣减同口径 |
| 个人数据接口对 guest 开放 | `MarketWebServer` `GET /api/markets/{id}` :286-288 `resolveIdentityOrGuest` | 改 `requireIdentity`，或对未登录不返回钱包/订单字段 |
| 访客合并到空账户 | `resolveIdentityOrGuest` :912 `MarketPlayerIdentity(null,"",null)` → `getAccount(level,"","")` | 空身份不取账户、不返回数值；禁止 `""` 账户合并 |
| 钱包文案误导为市场级 | `marketweb/app.js` :953/:962、游戏内 lang `wallet.*` | 改"我的钱包/个人余额"，去除"市场账户"暗示 |
| 客户端钱包归属待核 | 游戏内 `MarketScreen` 钱包区 | 确认绑定当前玩家、文案不暗示公共账户 |

## 钱包部分关键决策（用户确认）

| 决策点 | 选择 |
|---|---|
| 刷金主根因 | 存入侧扣源落空 / 非原子（含 Vault 路径） |
| 存入事务 | 先确凿扣源、扣成功才加钱包、失败全回滚 |
| 金双向口径 | 双向固定率（金作硬通货，不随市场） |
| 钱包优化分层 | 拆为**事务正确性层**（本章，后端）+ **显示层**（UI 章节） |
| 优先级 | 事务正确性层 > 所有钱包 UI |
| 钱包归属 | **玩家级、一人一个**（市场仅入口，非账户归属） |
| 越权根因 | 个人数据接口对 guest 开放 + 访客合并到空 UUID 账户 |
| 越权修复 | 个人数据接口要求登录、空身份不取账户、文案去"市场账户"歧义、客户端同步核对 |

## 钱包部分验证

1. **单测/集成**：存入后扣源（仓库金/Vault）**确有等额减少**；扣源不足时**不 deposit**、无回滚残留；任一扣源成功后另一扣源不被重复触发。
2. **往返守恒**：反复"存入→提取"任意次，仓库金 + 钱包余额**总额恒定**（净额为零）。
3. **离线路径**：玩家离线时经网页端存入，仓库金同样被真实扣减（不再因 Vault 短路而跳过仓库扣减并照加钱包）。
4. **金口径**：把市场金价推离 18 后，存入/提取金的等值不变，无套利空间。
5. **回归**：正常存取、钱包→国库/国库→钱包/待领→钱包等转账不受影响。
6. **归属/越权**：未登录访问 `GET /api/markets/{id}` **不返回任何钱包余额/myOrders**（或要求登录）；两个不同玩家登录看到**各自**的余额，互不可见；空身份不再落到共享 `""` 账户；网页端与游戏内文案均表达"我的钱包"而非"市场账户"。

---

## 实施分期建议

为控制风险，建议拆成可独立验证的阶段（写实现计划时细化）：
1. 数据模型 + TransportMode 抽象（建在现有 `TransportEntity` 上）+ 履约方字段 + MarketOverviewData 扩展（履约/调度状态/拼车摘要）
2. TransportDispatchPlanner 纯逻辑 + 单测（拼车/接货/排队/双 mode/同 tick 去重/空载边界）
3. TransportDispatchService tick 接入 + 执行层对接（卖家发货走通；复用已实现的多站连运）
4. 买家自提（系统代开-收敛扫描买家载具所在终端、空驶接货 + 亲自开进 zone 装货-复用停靠判定节流）
5. 多产地接货执行层"到接货站装货"镜像分支
6. **钱包事务正确性（后端，优先级高于以下所有 UI）**：存入/提取原子化、Vault/仓库扣源三态收敛、金双向固定率、`linkedWarehouseGoldValue` 公开盘点；**钱包归属/越权修复**（个人数据接口要求登录、禁止空账户合并、文案去"市场账户"歧义、客户端核对绑定本人）（即第三部分；刷金 + 越权修复在此落地）
7. UI（游戏内 Elementa）：发运页重构为上卖家发货/下买家物流双区（载具图标区分马车/帆船）+ 购买弹窗选发货方式 + 结算页钱包**显示层**重构（依赖阶段 6：可存入来源显示、默认填上限、修 %s bug）
8. UI（网页端，与 webmarket 协调）：仓储页新增发货区块 + 钱包**显示层**升级（依赖阶段 6：可存入来源/数值显式化）
