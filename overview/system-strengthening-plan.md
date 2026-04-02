# SailboatMod 后续补强计划

更新时间：2026-04-02

## 1. 目标

本计划面向后续继续设计和实现的 AI/开发者，目标是把当前已经存在但尚未统一的几套系统，整理成一条清晰的补强路线，优先解决：

- Town 不是经济主体
- 市场双轨
- 建造双轨
- NPC 生产未接线
- 陆运缺失
- Nation 缺少宏观调度

## 2. 总体原则

- 先统一数据语义，再扩功能
- 先打通 Town/Market/Construction，再扩 Nation 宏观层
- 优先保留现有可玩链路，不做一次性推倒重来
- 尽量让新系统包容现有 `ItemStack` 履约链，逐步过渡到统一商品模型

## 3. 分阶段计划

## Phase 1：统一经济主语义

目标：明确“谁在持有库存、谁在结算、谁在下单、谁在施工”。

### 3.1 Town 级经济容器

新增建议：

- `TownStockpileRecord`
- `TownBudgetRecord`
- `TownDemandRecord`
- `TownProductionSnapshot`

建议职责：

- `TownStockpileRecord`：Town 物资库存视图
- `TownBudgetRecord`：Town 本地现金流、收入、支出
- `TownDemandRecord`：食物、建材、生活品、军需缺口
- `TownProductionSnapshot`：按周期汇总本地生产

最小可行落地：

1. 先不做全物品库存，只做关键商品统计层
2. 把 Dock/Market/Construction 对 Town 的消耗和收入记账到 Town
3. 先让 Town 有“地方预算”和“建材缺口”两块数据

### 3.2 统一商品主键

当前问题：

- 旧市场用 `ItemStack`
- 新市场用 `commodityKey=itemId`

补强建议：

- 统一定义 `CommodityKey`
- 短期仍然允许 `ItemStack -> CommodityKey` 直接映射
- 给重点商品补分类：
  - `food`
  - `building_material`
  - `industrial`
  - `military`
  - `luxury`

### 3.3 统一价格来源

目标：

- 市场展示价格、建造材料估价、Town 缺口评估全部来自同一价格服务

建议：

- 保留 `CommodityMarketService` 作为唯一动态价格来源
- `listing.unitPrice` 变成“挂牌参考价/成交记录字段”，不再成为核心定价源

## Phase 2：统一建造系统

目标：把当前玩家建造链与居民施工链整合成单一施工项目模型。

### 4.1 引入 ConstructionProject

建议新增：

- `ConstructionProjectRecord`
- `ConstructionMaterialRequirement`
- `ConstructionFundingRecord`
- `ConstructionProgressSnapshot`

建议字段：

- projectId
- ownerTownId
- ownerNationId
- structureType / blueprintId
- origin / rotation / bounds
- state
- requiredMaterials
- deliveredMaterials
- treasuryBudget
- walletBudget
- assignedBuilders
- currentLayer
- totalLayers

### 4.2 整合现有两套链路

整合目标：

- `StructureConstructionManager` 负责蓝图放置、预览、项目创建
- `BuildingConstructionService` 负责项目持久化与施工状态
- `BuildGoal/BuilderJobGoal` 统一从 `ConstructionProject` 取任务

### 4.3 材料逻辑分层

建议支持三种模式：

- `STRICT_MATERIALS`
  - 必须先交付材料再施工
- `AUTO_PURCHASE_ALLOWED`
  - 缺口可自动按市场价补购
- `MONEY_ONLY_FAST_BUILD`
  - 纯货币即时采购

短期推荐默认：

- `AUTO_PURCHASE_ALLOWED`

## Phase 3：把 Town 与市场/物流打通

目标：Town 变成真正的本地经济单元。

### 5.1 Town 本地建设消耗

目标：

- 建筑项目默认先消耗 Town 储备
- 储备不足时再走 Nation 支援或自动市场采购

优先顺序建议：

1. Town stockpile
2. Nation treasury / nation stockpile
3. 玩家钱包或自动市场采购

### 5.2 Town 本地销售盈余

建议流程：

1. Town 生成本地需求缺口
2. 超出安全库存的部分标记为盈余
3. Dock/Market 可把盈余商品自动或半自动挂牌

### 5.3 Town 采购请求

建议新增：

- `TownSupplyRequest`
- `TownTransferOrder`

作用：

- 让 Town 能发起建材、食物、工具等请求
- 先尝试 Nation 内调拨，再走市场购买

## Phase 4：让居民生产真正接入主循环

目标：让 `JobManager/TaskLib` 不再只是原型。

### 6.1 接线方案

当前已有：

- `JobLib`
- `TaskLib`
- `TaskManager`
- `JobManager`

需要新增或接线：

- 生产输入从 Town/Dock/仓库扣除
- 生产输出写回 TownStockpile 或 Dock 仓储
- 税收部分可进入 Town/Nation 账本

### 6.2 居民岗位与建筑挂钩

建议：

- `WorkerModule` 作为岗位容量来源
- `LivingModule` 作为居住容量来源
- 工作站与建筑模块统一

### 6.3 食物与消费优先

第一批优先接入的生产链建议：

1. 食物链
2. 建材链
3. 基础工业链

原因：

- 与 Town 稳定运行最直接相关
- 与建造和人口维护直接相关

## Phase 5：Nation 宏观层

目标：Nation 从“金库 + 外交壳层”升级为“国家调度层”。

### 7.1 全国汇总视图

建议新增：

- `NationStockSummary`
- `NationDemandSummary`
- `NationConstructionPriority`

Nation 需要至少能看到：

- 各 Town 食物余缺
- 各 Town 建材余缺
- 各 Town 在建项目
- 跨 Town 可转运资源

### 7.2 调拨逻辑

建议：

- Nation 不直接吞掉 Town
- Nation 作为二级调度层，只处理：
  - 跨 Town 调拨
  - 国家重点工程
  - 战争/封锁时优先级调整

### 7.3 政策层

短期可加：

- 重点商品税率调整
- 国家建设补贴
- 战时优先物资

## Phase 6：物流补全

目标：让物流不仅服务港口市场，也服务 Town 建设和 Nation 调拨。

### 8.1 海运继续扩展

建议补强：

- 运费
- 船队运力统计
- 路线繁忙度
- 战时封锁/停航

### 8.2 新增陆运抽象层

短期不必先做实体车队，可先做抽象层：

- `LogisticsOrder`
- `ShipmentRecord`
- `TransferMode` = SEA / LAND

第一阶段只需：

- 给陆运一个时间/容量/费用模型
- 先不生成实体商队

### 8.3 建材运输接入

目标：

- 建造项目不再总是即时补购
- 支持“市场买到 -> 物流运输 -> 工地入库 -> 开工”

## Phase 7：国际贸易与战争联动

目标：统一“提案式国家交易”和“港口商品贸易”。

### 9.1 统一国际贸易模型

建议分层：

- 外交层：是否允许贸易
- 市场层：是否允许商品流动
- 物流层：是否有航线和运力
- 财政层：是否收税与关税

### 9.2 保留两种交易形态

建议保留：

- `DiplomaticTradeProposal`
  - 用于大宗、协议性、政治性交易
- `CommodityTradeFlow`
  - 用于日常商品贸易

### 9.3 战争期规则

后续可加：

- 敌对国禁运
- 港口封锁
- 航线中断
- 进口涨价

## 4. 推荐新增核心类

建议优先新增或统一以下核心类：

- `TownStockpileRecord`
- `TownBudgetRecord`
- `TownDemandRecord`
- `ConstructionProjectRecord`
- `ConstructionMaterialRequirement`
- `TownSupplyRequest`
- `TownTransferOrder`
- `NationStockSummary`
- `NationDemandSummary`
- `LogisticsOrder`
- `ShipmentRecord`
- `CommodityKey`

## 5. 实施优先级

### P0：马上补

- Town 本地经济容器最小版
- 商品统一主键与统一价格来源
- 建造项目模型统一

### P1：随后补

- 居民生产接入 Town 库存
- Town 本地盈余/缺口与市场挂单打通
- Nation 汇总层

### P2：中期补

- 陆运抽象层
- 建材物流交付
- 国际贸易统一制度

### P3：后续扩展

- 战争封锁与贸易中断
- 战略物资与优先级系统
- 更复杂的风险、保险、损耗

## 6. 最小落地顺序建议

推荐按以下顺序推进：

1. 新增 `TownStockpileRecord + TownBudgetRecord`
2. 让建造先读 Town/Nation 预算与库存
3. 统一 `ConstructionProjectRecord`
4. 让 Dock/Market 的成交结果回写 Town 账本
5. 把 `JobManager` 接到 Town 库存
6. 做 Nation 汇总面板与调拨请求
7. 再补陆运与国际贸易升级

## 7. 注意事项

- 不建议先做过重的全实体陆运，否则复杂度和性能会过早爆炸
- 不建议继续扩大“旧市场逻辑”和“新商品逻辑”的分叉
- 不建议让 Nation 直接替代 Town，Town 仍应是地方经济主体
- 建造系统是当前最容易与经济系统打通的入口，应优先作为统一经济语义的切入点

