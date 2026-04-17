# RoadEnhancement 道路增强计划

## 目标

基于 `RoadWeaver-1.20.1-Architectury` 的思路，增强我们当前陆路系统的两部分能力：

1. 提升寻路质量  
   让驿站、town、仓库之间的自动道路更符合地形，更少出现生硬折线、无意义爬坡、贴水乱绕等问题。

2. 提升铺路质量  
   让道路不再只是单点路径，而是具备桥段识别、平滑成形、宽度化铺设和更稳定的施工输出。

最终目标不是照搬 `RoadWeaver` 的 worldgen 体系，而是在保留我们现有物流网络、施工任务、道路持久化体系的前提下，升级 `RoadPathfinder + StructureConstructionManager`。

---

## 当前系统现状

### 1. 路由解析

当前入口在 [RoadAutoRouteService.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java)：

- 优先读取 `NationSavedData` 中已有道路网络
- 使用普通 `dijkstra(...)` 在持久化道路图上求最短路
- 若没有现成道路，则回退到地形寻路 `RoadPathfinder.findPath(...)`

这部分适合 town-to-town、驿站-to-驿站物流调度，应该保留。

### 2. 地形寻路

当前入口在 [RoadPathfinder.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java)：

- 8 方向 A* 风格搜索
- 使用地表高度采样
- 代价项较少：
  - 距离
  - 高差平方惩罚
  - 水惩罚
  - 软地面惩罚
  - 已有道路奖励
  - 转向惩罚

问题：

- 缺少对等高线趋势的利用
- 缺少坡度软/硬分级惩罚
- 缺少稳定性、近水、生物群系等环境语义
- 找到的是“能走的线”，不是“适合作为道路的线”

### 3. 道路后处理与铺设

当前 `RoadPathfinder.finalizePath(...)` 仅做：

- 折线简化
- `bresenham` 补点

当前施工主要在 [StructureConstructionManager.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java)：

- 逐段施工
- 支持恢复
- 当前完成判定偏依赖固定路材

问题：

- 没有桥段识别
- 没有桥段拉直
- 没有曲线平滑
- 没有真正“有宽度”的道路面输出
- 施工层拿到的仍然更像中线点列

---

## RoadWeaver 可借鉴的核心能力

### 1. 更强的地形代价模型

参考：

- [PotentialFieldPathfinder.java](F:/Codex/Ref/RoadWeaver-1.20.1-Architectury/common/src/main/java/net/shiroha233/roadweaver/pathfinding/impl/PotentialFieldPathfinder.java)
- [GradientDescentPathfinder.java](F:/Codex/Ref/RoadWeaver-1.20.1-Architectury/common/src/main/java/net/shiroha233/roadweaver/pathfinding/impl/GradientDescentPathfinder.java)

可借鉴项：

- contour alignment，尽量顺着地势走，而不是硬切坡
- soft grade / hard grade 两级坡度惩罚
- terrain stability 地形稳定性代价
- water / near-water 区分
- biome 代价调节
- bounded search + step budget，避免大规模寻路卡主线程

### 2. 更强的后处理链

参考：

- [PathPostProcessor.java](F:/Codex/Ref/RoadWeaver-1.20.1-Architectury/common/src/main/java/net/shiroha233/roadweaver/pathfinding/impl/PathPostProcessor.java)

可借鉴项：

- `simplifyPath`
- `detectBridgeMask`
- `straightenBridgeRuns`
- `relaxPathSkippingBridge`
- Catmull-Rom / Bezier 曲线平滑
- 宽度化栅格输出 `RoadSegmentPlacement`

### 3. 分段道路输出模型

RoadWeaver 不是只输出 path points，而是输出“道路段 + 道路覆盖块集合”。

这对我们后续很重要，因为：

- 施工层更容易逐段施工
- 可支持不同宽度道路
- 可支持桥、路肩、装饰、路口扩展
- 后续马车、商队、驿站导航都可以复用中心线和宽度信息

---

## 总体设计原则

1. 不替换 `RoadAutoRouteService` 的图网络层  
   现有“优先走已建道路，找不到再走地形”的逻辑是合理的。

2. 升级 `RoadPathfinder`，而不是整套移植 `RoadWeaver`  
   我们的系统是 town 物流与实体运输，不是纯世界生成道路。

3. 在“原始寻路结果”和“施工落地”之间插入独立后处理层  
   让道路成形和道路施工彻底解耦。

4. 施工层继续由 `StructureConstructionManager` 驱动  
   但输入从“点列路径”升级为“分段道路结构”。

---

## 分阶段实施计划

## 第一阶段：增强地形寻路代价模型

### 目标

先不动施工逻辑，只提升 `RoadPathfinder.findPath(...)` 的路径质量。

### 具体改造

1. 在 `RoadPathfinder` 中新增地形采样缓存

- 避免重复取高度、流体、地表方块、群系
- 为长距离驿站路线降低采样开销

2. 将当前 `getMoveCost(...)` 升级为多因子代价函数

新增代价项：

- 坡度 soft penalty
- 坡度 hard penalty
- 近水惩罚
- 地形稳定性惩罚
- 生物群系惩罚
- 等高线方向奖励或折扣

保留现有代价项：

- 基础距离
- 高差
- 软地面
- 已建道路奖励
- 转向惩罚

3. 增加搜索边界和步数预算

- 基于起终点动态生成搜索矩形
- 设置最大步数预算
- 超预算时快速失败并回退

### 交付结果

- 同样距离下，道路更偏向缓坡
- 更少切水、切崖、无意义 zig-zag
- 大范围自动建路更稳定

---

## 第二阶段：引入独立道路后处理器

### 目标

在 `RoadPathfinder.findPath(...)` 产出的原始点列之后，加入类似 `RoadWeaver PathPostProcessor` 的成形管线。

### 新增模块建议

新增类：

- `RoadPathPostProcessor`
- `RoadSegmentPlacement`
- `RoadBridgeAnalyzer`
- `RoadSplineHelper`

建议包路径：

- `com.monpai.sailboatmod.nation.road`

### 后处理流程

1. 原始路径简化

- 去除冗余折点
- 保留必要转向与高差节点

2. 桥段识别

- 基于连续水面、落差、不可直接铺地段识别桥段
- 输出 `bridgeMask`

3. 桥段拉直

- 桥上路径尽量直线
- 减少桥面蛇形

4. 非桥段松弛与平滑

- 对普通道路节点做局部 relax
- 再做 spline 平滑

5. 宽度化栅格输出

- 从“中心线点列”生成“每段道路覆盖方块集合”
- 输出每段中心点和宽度覆盖块

### 交付结果

- 自动路不再是硬折线
- 桥更直
- 施工输入转为真正的“道路段”

---

## 第三阶段：施工系统适配分段道路

### 目标

让 `StructureConstructionManager` 不再只接收单点路径，而能接收宽度化后的道路段。

### 具体改造

1. 扩展道路施工任务结构

当前任务只适合路径点列，需要新增：

- segment center
- segment positions
- targetY
- isBridge
- width

2. 修改恢复判定逻辑

当前 `findRoadResumeIndex(...)` 依赖固定路材块判断完成度，需要升级为：

- 检查一个 segment 是否完成
- 桥段、普通段使用不同判定

3. 分离桥梁施工与普通路面施工

普通段：

- 平整表面
- 铺路面
- 处理边缘、路肩

桥段：

- 先立支撑或桥墩
- 再铺桥面
- 需要独立的结构规则

### 交付结果

- 可施工 2 格、3 格、4 格宽道路
- 桥段和普通路段施工效果明显提升
- 中断恢复更可靠

---

## 第四阶段：与驿站系统深度整合

### 目标

让驿站创建道路不只是“算一条路线”，而是“规划并建设可持续使用的道路网络”。

### 具体改造

1. 驿站自动建路使用增强版地形寻路

- 驿站到驿站
- 驿站到 town 边界主路
- 驿站到仓库

2. 道路建成后写入道路网络图

- 将 segment center 或中心线节点回写到 `NationSavedData`
- 后续物流优先走已建道路

3. 为马车运输提供统一道路导航接口

- 中心线用于导航
- 宽度用于车辆纠偏与避障
- 桥段可限制速度或碰撞规则

4. 与道路创建器联动

- 玩家手动选择目标 town
- AI 自动生成到目标 town 边界最近主路的连接线
- 新路自动吸附已有道路主干

### 交付结果

- 驿站成为陆路网络节点
- 新路能更自然接入旧路
- 后续马车系统更容易做自动跑商与出租

---

## 建议新增的数据结构

## 1. RoadSegmentPlacement

建议字段：

- `BlockPos center`
- `List<BlockPos> positions`
- `boolean bridge`
- `int targetY`
- `int width`

作用：

- 作为寻路后处理与施工系统之间的标准交换结构

## 2. RoadPathResult

建议字段：

- `List<BlockPos> rawPath`
- `List<BlockPos> simplifiedPath`
- `List<RoadSegmentPlacement> segments`
- `double totalCost`
- `PathSource source`

作用：

- 便于调试
- 便于 UI 预览
- 便于 ETA 和长度估算

## 3. TerrainSample / TerrainCache

建议字段：

- surfaceY
- oceanFloorY
- isWater
- nearWater
- biomeId
- stabilityScore
- groundTag

作用：

- 统一地形成本采样
- 降低重复查询成本

---

## 关键实现顺序

推荐严格按下面顺序做，不要直接跳到施工层：

1. 升级 `RoadPathfinder` 代价函数
2. 给 `RoadPathfinder` 增加采样缓存与预算控制
3. 抽出 `RoadPathPostProcessor`
4. 引入 `RoadSegmentPlacement`
5. 让道路预览和自动建路使用新结果
6. 最后再改 `StructureConstructionManager`

原因：

- 先把路径质量拉上去，再谈铺路质量
- 先确定标准输出结构，再改施工系统
- 避免施工层反复返工

---

## 风险与注意事项

### 1. 性能风险

- 长距离 town-to-town 搜索会显著增加采样次数
- 必须做缓存、搜索边界和步数预算

### 2. 平滑过度风险

- spline 可能把路甩进不可施工区域
- 平滑后必须重新校验 segment 落点合法性

### 3. 桥段识别误判

- 小水沟不一定该建桥
- 深水和浅水、短跨和长跨要区分处理

### 4. 施工兼容性

- 现有任务、存档、道路恢复逻辑基于旧 path 结构
- 需要兼容旧版道路数据，避免现有世界失效

---

## 验收标准

### 算法层

- 同样起终点下，新路径平均转角数量明显下降
- 平均坡度和最大坡度低于旧版
- 跨水路线优先形成可解释桥段

### 视觉层

- 自动道路折线感明显下降
- 桥梁段保持更直
- 道路宽度在视觉上连续稳定

### 系统层

- 驿站自动建路能够成功接入已有 town 主路
- 施工任务支持中断恢复
- 新道路可回写到道路网络供物流系统复用

---

## 最终建议

这次增强的正确方向不是“把 `RoadWeaver` 全部搬进来”，而是：

- 保留我们自己的道路网络调度层
- 借用 `RoadWeaver` 的地形成本模型
- 借用 `RoadWeaver` 的路径后处理思想
- 把输出升级为可施工、可导航、可持久化的分段道路结构

一句话总结：

**我们要保留自己的物流系统骨架，用 `RoadWeaver` 强化“怎么找路”和“怎么把路做得像路”。**
