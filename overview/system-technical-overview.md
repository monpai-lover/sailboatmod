# SailboatMod 系统技术概述

更新时间：2026-04-02

## 1. 总体目标

当前项目正在把模组扩展成一个以 `Town -> Nation -> 市场/港口 -> 物流 -> 建造 -> 居民` 为主链的复合模拟系统。它要模拟的不是单一建筑模组，也不是单一交易模组，而是：

- 城镇与国家管理
- 居民与人口属性
- 港口、航线、船运物流
- 市场交易与价格
- 蓝图建筑与施工
- 国家外交、税收、关税、跨国交易

当前核心设计目标更准确地说，是先把以下三条主线做成可运行骨架：

- 政治/领地主线：`Town`、`Nation`、claim、外交、战争、金库
- 贸易/物流主线：`Market`、`Dock`、`Route`、`SailboatEntity`
- 建造主线：蓝图预览、脚手架、施工、建筑落地记录

经济系统与玩法关系如下：

- `Nation` 提供国家财政、税率、关税、国家金库、对外贸易提案
- `Town` 提供本地成员归属、核心、领地、人口统计入口
- `Market + Dock + SailboatEntity` 承担市场交易、发运、到货
- `ConstructionCostService` 把建造行为与货币/商品市场挂钩
- `Resident` 系统提供人口、教育、职业、幸福与部分施工行为
- 战争/外交已存在骨架，但与经济/物流尚未形成完整联动

需要明确的是：当前项目还没有形成一个统一的宏观经济内核。Town 不是完整地方经济容器，Nation 也还不是全国供需调度器，市场层则同时存在旧挂单市场与新的 SQLite 商品市场两套逻辑。

## 2. 系统分层

### 2.1 Town 层

职责：

- 城镇身份
- 城镇核心
- 市长/成员归属
- 文化与旗帜
- 与 Nation 的绑定关系
- 城镇领地视图与基础统计入口

当前已实现：

- 建城、重命名、弃城
- 放置/移除/移动 town core
- Town 独立存在后再并入 Nation
- 领地 claim 与 town/nation 关系联动
- Town 概览界面与人口/教育统计

计划实现：

- 作为地方生产/消费/建设主体
- 本地库存、本地财政、本地需求
- 本地盈余出售与本地建设消耗

当前未做：

- Town 自身库存
- Town 独立金库
- Town 生产/消费队列
- Town 建材需求账本
- Town 本地市场结算

### 2.2 Nation 层

职责：

- 国家身份与成员体系
- 国家核心、首都、外交、战争
- 国家金库
- 税率与关税
- 国家间贸易提案

当前已实现：

- 国家创建依赖独立 Town
- 外交关系、外交请求、和平提案、战争状态
- 国家金库、国家物品仓
- 销售税、进口关税、动态浮动税率
- Nation 概览与贸易 UI 数据
- 国家间提案式贸易

计划实现：

- Nation 级总库存/总供需
- 全国调拨权
- Nation 级建设优先级/建设队列
- Nation 级经济政策

当前未做：

- 全国物资统筹
- 统一价格制定
- Town 间自动调拨
- 宏观供需汇总后驱动真实价格

### 2.3 市场层

职责：

- 商品挂单
- 购买订单
- 卖家收益结算
- 航运订单生成
- 商品价格模型

当前已实现：

- 真实 `ItemStack` 挂单市场
- `PurchaseOrder`
- `ShippingOrder`
- 卖家 `pendingCredits`
- SQLite 商品市场状态与交易历史
- 市场 UI 概览数据

计划实现：

- 让商品市场逻辑接管更多 UI 与交易流程
- 更统一的商品化交互

当前未做：

- Nation 级统一市场
- Town 级独立市场
- 明确的战略物资、配额、禁运规则

### 2.4 物流层

职责：

- 港口注册与管理
- 航线定义
- 船只派遣
- 订单发运、到货、回程装货

当前已实现：

- `DockBlockEntity`
- `RouteDefinition`
- 自动寻水路建航线
- 船只自动驾驶
- 发货装载/卸货
- 运单记录
- 返回航程尝试装货

计划实现：

- 更强的订单调度
- 与更高层经济系统深度耦合

当前未做：

- 陆运网络
- 风险、损耗、封锁、海盗/战时拦截

### 2.5 建造层

职责：

- 蓝图模板读取
- 放置前预览
- 工地脚手架
- 自动施工/辅助施工
- 建筑记录与升级

当前已实现：

- 蓝图 `.nbt` 读取
- 旋转/偏移
- 半透明总体投影预览
- 缺失/阻挡/匹配分色
- 逐层自动建造
- 脚手架工地
- 单块辅助放置
- 自动缺料购买与扣款
- 已放置建筑记录

计划实现：

- 统一施工项目模型
- NPC Builder 与主建造系统统一
- 建筑升级与模块系统深度接入

当前未做：

- 工地材料仓
- 真实材料交付链
- Town/Nation 库存驱动的施工补给

### 2.6 外交/国际贸易层

职责：

- 外交关系
- 国家间交易
- 对跨国港口贸易施加关税
- 限制自动航线的外交条件

当前已实现：

- 提案式国家间交易
- 关税
- 自动跨国航线要求同盟或贸易关系

计划实现：

- 更商品化的国家间贸易
- 更强的贸易协定/制裁/封锁逻辑

当前未做：

- 贸易协定制度
- 港口容量/口岸等级
- 贸易封锁/战争拦截

## 3. Town 系统

### 3.1 Town 当前保存哪些数据

`TownRecord` 当前保存：

- `townId`
- `nationId`
- `name`
- `mayorUuid`
- `createdAt`
- `coreDimension`
- `corePos`
- `flagId`
- `cultureId`

这说明 Town 当前主要是政治/领地身份记录，不是完整经济容器。

关键代码：

- `nation/model/TownRecord.java`
- `nation/service/TownService.java`

### 3.2 Town 是否有库存、生产、消费、人口、NPC 分工、建筑需求

- 库存：未发现 Town 自己的库存结构
- 生产：未发现 Town 自己的生产账本
- 消费：未发现 Town 自己的消费账本
- 人口：有，来自 `ResidentSavedData`
- NPC 分工：有职业与居民记录，但不是 Town 内部经济表
- 建筑需求：未发现 Town 层统一的建材需求结构

`TownEconomyRecord` 目前只是统计快照，字段包括：

- 总人口
- 就业数/失业数
- 识字人口
- 失业率
- 识字率

它不是生产、库存、消费或财政模型。

### 3.3 Town 如何与 Nation 交互

- Town 可以独立创建
- Nation 创建时会绑定一个独立 Town
- claim 与 town/nation 绑定关系会联动更新
- Town 成员也通过 Nation 成员结构与 Nation 体系连接

### 3.4 Town 是否独立结算经济

当前看不到 Town 独立经济结算实现。  
Town 没有地方金库、地方库存、地方生产/消费账本，因此无法称为独立结算经济体。

### 3.5 Town 是否可以本地建设、本地消耗、本地出售盈余

从玩法结果看，可以发生：

- 在 Town 内放建筑
- Dock/Market 本地挂单出售
- 本地收到货并取货

但这些行为当前并不是由 Town 自身账本驱动，而是由：

- 玩家
- `MarketBlockEntity`
- `DockBlockEntity`
- `StructureConstructionManager`
- `ConstructionCostService`

来完成。

### 3.6 Town 的资源生产流程目前怎样

当前更接近如下流程：

1. 居民/玩家/人工操作把物品放入 Dock 存储
2. Market 从 Dock 抽取一部分物品生成挂单
3. 购买者下单，生成 `PurchaseOrder`
4. Dock + Sailboat 发运
5. 到港后 Dock 形成 waybill 并标记订单状态

Town 自身没有一个“本地产出 -> 本地仓储 -> 本地消费 -> 盈余外销”的内建经济循环。

## 4. Nation 系统

### 4.1 Nation 当前保存哪些数据

`NationRecord` 保存：

- `nationId`
- `name`
- `shortName`
- 主副色
- `leaderUuid`
- `createdAt`
- `capitalTownId`
- `coreDimension`
- `corePos`
- `flagId`

`NationSavedData` 另外还保存：

- nations
- towns
- members
- offices
- invites / joinRequests
- claims
- wars
- flags
- diplomacy
- diplomacyRequests
- townNationRequests
- treasuries
- peaceProposals
- placedStructures
- tradeProposals

### 4.2 Nation 是否有国家库存、财政、宏观市场、调拨权、建设队列、政策

- 国家库存：有，`NationTreasuryRecord.items()` 54 格
- 财政：有，`currencyBalance`
- 宏观市场：无明确定义
- 调拨权：未知/待实现
- 建设队列：未发现 Nation 级建设队列
- 政策：已有税率/关税，其他政策未见明确实现

### 4.3 Nation 与 Town 的关系

- Town 是基础，Nation 叠加其上
- Nation 创建依赖 Town
- Town 可先独立，后加入 Nation
- claim 中会体现 nationId / townId 归属

### 4.4 Nation 是否会汇总供需

未发现真实全国供需汇总。  
`NationEconomyRecord` 虽然存在 `productionByType`、`dailyTaxIncome` 等字段，但没有看到它成为经济驱动主结构。

### 4.5 Nation 是否会统一定价

当前不会。  
价格来自：

- 卖家挂单单价
- 或 `CommodityMarketService` 动态报价

而不是 Nation 统一制定。

### 4.6 Nation 是否参与国家间贸易

是，但有两种方式：

- `NationTradeService` 的国家间提案式交易
- 跨国 Dock/Market 商品交易并附加进口关税

## 5. 商品与资源模型

### 5.1 当前交易对象是什么

当前是混合模式，但主交易对象仍然是 Minecraft 真实物品：

- `ItemStack`
- 原版方块/物品

商品抽象层已经开始建立，但没有完全取代 `ItemStack`。

### 5.2 是否有“每种方块定价”的设计

有。  
`CommodityMarketService` 使用 `itemId` 作为 `commodityKey`，当前基本等价于“每种物品一个价格状态”。

### 5.3 商品是否有分类

代码里 `CommodityDefinition` 有 `category` 字段，但目前默认基本为空。  
未发现稳定使用中的建材/工业品/军需/食物分类体系。

### 5.4 商品是否有基础价格、动态价格、库存限制、战略品限制

已实现：

- 基础价格：`basePrice`
- 动态价格：由 `currentStock`、`volatility`、`spreadBp` 驱动
- 上下限：`stockFloor`、`stockCeil`、`priceFloor`、`priceCeil`

未实现或未确认：

- 战略品限制
- 配额
- 禁运

### 5.5 当前实现程度

已实现的是一个可用的 SQLite 商品价格模型，但：

- 分类不足
- 与旧挂单市场双轨并行
- Nation/Town 宏观层还没有真正吃这套数据

## 6. 市场系统

### 6.1 当前是否存在 Nation 级统一市场

不存在。  
当前是全局 `MarketSavedData` 挂单/订单池，listing 上附带 `townId`、`nationId` 元数据。

### 6.2 价格如何形成

当前有两种机制并行：

1. 卖家挂单价  
2. `CommodityMarketService` 动态报价

在 `MarketBlockEntity` 中，展示与购买时会优先尝试使用商品市场报价。

### 6.3 是否按供需变化

SQLite 商品市场按库存变化。  
挂单市场本身不独立做宏观供需曲线，但会通过商品市场报价反映动态价格。

### 6.4 是否有库存约束

有，但分两层：

- 真实层：创建挂单时先从 Dock 抽货
- 抽象层：`availableCount` / `reservedCount`

### 6.5 是否允许直接购买建材

允许。  
`ConstructionCostService` 会直接按商品市场价格估算蓝图或单块材料成本。

### 6.6 购买行为是否真实扣减库存

是，但语义要分开看：

- 创建 listing 时，货物已经从 Dock 真实抽离
- 购买时减少 listing 可用数量并增加保留数量
- 发货时根据 listing 信息拆 cargo

因此它更像“先把货转入市场池，再基于市场池履约”。

### 6.7 Town 本地市场和 Nation 市场是否分离

当前未分离。

### 6.8 当前市场实现与计划

已实现：

- listing / order / shipping / pendingCredits
- 本地直接交付
- 海运发运
- 税与关税
- SQLite 商品价格/交易历史

计划中：

- 更先进的 UI
- 更统一的商品化市场逻辑

## 7. 建造系统

### 7.1 建筑建造目前如何进行

当前主要由 `StructureConstructionManager` 驱动：

1. 读取蓝图模板
2. 生成旋转/偏移后的 placement
3. 检查区域是否可放置
4. 估价并扣款
5. 摆脚手架
6. 按层自动施工或通过 assist 模式逐块补建
7. 施工完成后放核心块并写入 `PlacedStructureRecord`

### 7.2 建造是否需要真实材料

- 自动整栋建造：不要求背包实际持有全部材料
- assist 单块模式：优先消耗背包内材料，没有则自动购买

### 7.3 是否支持通过经济/货币自动采购缺失材料

支持。  
`ConstructionCostService` 会：

- 先估算整栋或单块成本
- 优先扣 Nation 金库余额
- 不足再扣玩家钱包
- 记录到商品市场买入

### 7.4 建造消耗是扣钱、扣库存，还是两者都有

当前主逻辑是：

- 扣钱为主
- Nation 物品金库不参与自动扣料
- Town 库存也不参与自动扣料
- 单块 assist 时可能直接从玩家背包扣一个对应物品

### 7.5 建筑蓝图是否存在

存在。  
`BlueprintService` 读取 `data/.../structures/*.nbt`。

### 7.6 是否有“项目”“施工进度”“材料交付”的概念

- 项目：部分存在，尤其在 `BuildingConstructionRecord`
- 施工进度：有，按 layer 计
- 材料交付：较弱，没有独立材料运输/工地仓体系

### 7.7 Town 建筑和 Nation 建筑是否分开

结构类型上区分：

- `VICTORIAN_TOWN_HALL`
- `NATION_CAPITOL`
- `OPEN_AIR_MARKETPLACE`
- `WATERFRONT_DOCK`
- `COTTAGE`
- `TAVERN`
- `SCHOOL`

但底层施工管理器并未分成两套完全独立系统。

## 8. NPC 生产系统

### 8.1 NPC 如何参与生产

当前居民主要通过：

- `ResidentRecord`
- `ResidentEntity`
- `Profession`
- `JobLib/TaskLib`

来描述职业与潜在生产任务。

### 8.2 NPC 是否与 Town 挂钩

是。  
`ResidentRecord` 和 `ResidentEntity` 都带 `townId`。

### 8.3 NPC 是否真的采集/制造/运输

部分是，部分不是：

- 施工：部分真实，有移动到工地与层进度推进
- 采集/制造：任务库存在，但没看到主循环完整接线
- 运输：未看到居民承担物流运输

### 8.4 NPC 生产与库存系统如何连接

当前基本未完全连接。  
`JobManager.tick()` 会产出“给仓库”和“给税收”的输出拆分，但未看到它被主系统稳定调用并落到 Dock/Treasury。

### 8.5 NPC 是表现层还是承担实际逻辑

当前更偏：

- 人口属性与行为表现层
- 兼具少量真实施工逻辑

还不是完整生产执行层。

## 9. 物流系统

### 9.1 当前物流是否主要依赖实体

是。  
主运输实体是 `SailboatEntity`。

### 9.2 有哪些运输实体

当前明确实现的是船。  
未确认成熟的商队、车辆、驮兽物流。

### 9.3 是否已有抽象数据层

有：

- `RouteDefinition`
- `PurchaseOrder`
- `ShippingOrder`
- `ShipmentManifestEntry`
- Dock 内部 `WaybillEntry`

### 9.4 海运当前实现了什么

已实现：

- 港口注册
- 航线定义/导入
- 自动寻水路
- 船只自动驾驶
- 货舱容量校验
- 订单分批发运
- 到货收货
- 运单记录
- 回程尝试装货

### 9.5 陆运当前实现了什么

经济物流意义上的陆运基本未实现。  
道路系统更多是建筑布局/视觉连接，不是货运系统。

### 9.6 物流是否影响市场流通、建材运输、国家贸易

- 市场流通：是，强影响
- 建材运输：弱，当前建材主要是即时购入而不是物流送达
- 国家贸易：间接影响，跨国港口贸易会触发关税

### 9.7 当前是否存在路线容量、时间、风险、封锁、损耗

- 容量：有，基于船货舱
- 时间：有，记录出发、耗时、路程
- 风险：未见成熟实现
- 封锁：未见成熟实现
- 损耗：未见成熟实现

## 10. 国家间贸易系统

### 10.1 当前国际贸易模式是什么

当前是两套模式并行：

- 提案式国家间贸易
- 跨国港口商品贸易

### 10.2 是固定收益、真实商品运输还是混合

混合：

- `NationTradeService` 属于直接国库交换
- `Market + Dock + SailboatEntity` 属于真实商品运输

### 10.3 是否有贸易路线、贸易协议、关税、容量、风险、封锁、口岸

- 贸易路线：有海运航线
- 贸易协议：外交状态中存在 `TRADE`
- 关税：有
- 容量：有，船容量
- 风险：未见成熟实现
- 封锁：未见成熟实现
- 口岸：Dock 实际承担口岸功能，但没有正式口岸等级体系

### 10.4 海运和国际贸易如何连接

- 跨国 Dock 之间的交易订单可以通过船只航线实际发运
- 自动创建跨国航线要求外交关系满足条件
- 进口国会收取关税

### 10.5 当前国家间贸易实现程度

中等。  
已经可运行，但仍不是统一外贸系统，更像：

- 一套提案式国库交换
- 一套跨国港口市场海运

## 11. 数据结构与代码架构

### 11.1 核心持久化/记录结构

- `NationSavedData`
- `MarketSavedData`
- `ResidentSavedData`
- `BuildingConstructionSavedData`
- `TownRecord`
- `NationRecord`
- `NationTreasuryRecord`
- `TownEconomyRecord`
- `NationEconomyRecord`
- `PlacedStructureRecord`
- `BuildingConstructionRecord`

### 11.2 核心服务/管理器

- `TownService`
- `NationService`
- `NationTradeService`
- `NationTreasuryService`
- `TaxService`
- `TownOverviewService`
- `NationOverviewService`
- `CommodityMarketService`
- `CommodityMarketRepository`
- `BlueprintService`
- `ConstructionCostService`
- `StructureConstructionManager`
- `BuildingConstructionService`
- `ConstructionScaffoldingService`
- `ResidentEconomyService`
- `JobManager`
- `TaskManager`
- `AutoRouteService`

### 11.3 各模块职责简述

- `NationSavedData`：国家总状态中心
- `MarketSavedData`：listing/order/shipping/pendingCredits
- `CommodityMarketService`：动态商品报价、库存调整、交易历史
- `MarketBlockEntity`：市场交互入口、挂单、购买、自动发运
- `DockBlockEntity`：港口仓储、路线、船只派遣、收货、回程装货
- `SailboatEntity`：自动航运与订单履约实体
- `BlueprintService`：蓝图模板展开
- `StructureConstructionManager`：玩家蓝图建造主链
- `ConstructionCostService`：建材估价与扣款
- `BuildingConstructionService`：居民施工记录管理
- `ResidentEntity`：居民实体 AI 层
- `JobManager/TaskManager`：居民任务原型层

## 12. 已实现内容 / 未实现内容 / 计划方向

### 12.1 已实现

- Town/Nation/Claim/外交/战争数据骨架
- Nation 金库与税收/关税
- 国家间提案式贸易
- Dock 仓储与海运航线
- Sailboat 自动航运
- Market 挂单/订单/航运订单/卖家收益
- SQLite 商品价格模型与交易历史
- 蓝图放置前投影预览
- 脚手架工地与逐层施工
- 建筑落地记录
- 居民基础属性与部分施工 AI

### 12.2 正在进行中

- 市场 UI 与商品市场逻辑继续接轨
- 建造系统与经济系统更深打通
- Town/Nation/Dock/Market 新 UI 接入
- 旧逻辑与新逻辑并行整合

### 12.3 计划中但未实现

- Town 级库存与地方财政
- Nation 级全国供需与调拨
- 统一商品分类体系
- NPC 真实采集/制造/运输闭环
- 工地材料交付与施工仓
- 陆运系统
- 更完整的国际贸易制度
- 战争封锁对物流和贸易的影响

## 13. 当前已知问题与设计取舍

### 13.1 关键问题

- Town 目前不是经济主体
- Nation 目前只有财政和政治层，没有全国调度层
- 市场存在旧挂单市场与新商品市场双轨并行
- 建造存在两套并行施工链路
- NPC 生产任务系统存在，但还没有真正接入经济主循环
- 陆运缺失，经济过度依赖海运

### 13.2 设计取舍与后果

- 用实体船运驱动物流，沉浸感强，但有性能和区块加载风险
- 建材采用“可即时补购”模式，操作体验好，但削弱真实供应链
- 市场先保留 `ItemStack` 履约模型，便于现有玩法落地，但导致与抽象商品市场割裂
- Nation 税/关税先行实现，便于玩法推进，但宏观经济基础仍薄弱

### 13.3 性能/复杂度风险

- `FindJobGoal` 的大范围扫描
- 实体船长航线的 chunk 强制加载
- 双轨市场逻辑带来的状态一致性问题
- 双轨建造逻辑带来的维护成本

## 14. 技术上下文

### 14.1 主要开发环境

- Minecraft 1.20.1
- Forge 47.2.0
- Java 17

### 14.2 技术前提与约束

- 主要持久化基于 `SavedData`
- 商品市场额外使用 SQLite
- 自动船运依赖实体 tick、路径与区块保持加载
- 建筑放置与施工依赖服务端方块更新与结构模板
- 任何宏观经济升级都必须考虑 Minecraft tick 成本、实体数量、区块边界和持久化一致性

### 14.3 相关关键配置

- `gradle.properties` 指定 MC/Forge 版本
- `ModConfig` 控制 SQLite 市场是否启用及数据库文件名

## 15. 核心系统关系图（文字版）

```text
Player / Resident / SailboatEntity
  -> 驱动交互与实体行为

Town
  -> 提供城镇归属、核心、领地、人口统计
  -> 目前不是完整地方经济账本

Nation
  -> 提供国家归属、金库、税率、关税、外交、战争、提案式贸易

DockBlockEntity
  -> 存储货物
  -> 保存航线
  -> 指派船只
  -> 接收货物并生成 waybill

MarketBlockEntity
  -> 从 Dock 抽货生成 listing
  -> 购买后生成 PurchaseOrder
  -> 尝试自动发运
  -> 卖家收益结算

CommodityMarketService
  -> 生成动态价格
  -> 记录供需与交易历史

SailboatEntity
  -> 载货
  -> 自动航行
  -> 到港卸货
  -> 推进 ShippingOrder / PurchaseOrder 状态

BlueprintService
  -> 读取蓝图模板

StructureConstructionManager
  -> 预览
  -> 摆脚手架
  -> 自动施工/辅助施工
  -> 建成后写入 PlacedStructureRecord

ConstructionCostService
  -> 估算建材价格
  -> 扣 Nation 金库 / 玩家钱包
  -> 向商品市场记录买入

ResidentEntity / BuildGoal / BuilderJobGoal
  -> 参与部分施工
  -> 尚未形成完整生产/运输闭环
```

## 16. 当前风险与待决策问题清单

- 是否把 Town 升级为真实地方经济单元
- 是否以 SQLite 商品市场取代旧挂单价格体系
- 是否保留“即时补购建材”为主，还是转向真实材料供应链
- 是否统一两套建造系统
- 是否让 Nation 成为全国物资调度者
- 是否引入陆运
- 是否把居民任务系统接入真实库存与产出
- 是否把国际贸易统一到单一制度模型

## 17. 最值得优先补完的模块清单

- Town 本地库存与地方经济账本
- 统一商品模型与价格模型
- 统一施工项目模型
- 居民生产主循环
- 陆运/内陆物流
- Nation 宏观调度层
- 国际贸易制度统一

