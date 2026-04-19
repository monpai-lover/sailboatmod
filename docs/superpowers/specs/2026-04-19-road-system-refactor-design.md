# 道路系统全面重构设计规格

> 日期: 2026-04-19
> 方案: 全面移植重写（方案A）
> 参考: RoadWeaver-1.20.1-Architectury

## 1. 概述

完全删除现有道路/桥梁系统的40+个类，基于RoadWeaver架构从零重建。融入定制需求：桥墩保留、半砖+台阶坡道、悬臂式路灯、三模施工、生物群系感知材料、玩家可选宽度。

## 2. 整体架构

```
com.monpai.sailboatmod.road/
├── api/
│   └── RoadNetworkApi.java              // 公共API入口
├── pathfinding/
│   ├── Pathfinder.java                  // 策略接口
│   ├── PathfinderFactory.java           // 工厂，根据配置创建算法
│   ├── PathResult.java                  // 寻路结果封装
│   ├── impl/
│   │   ├── BasicAStarPathfinder.java
│   │   ├── BidirectionalAStarPathfinder.java
│   │   ├── GradientDescentPathfinder.java
│   │   └── PotentialFieldPathfinder.java  // 默认算法
│   ├── cost/
│   │   └── TerrainCostModel.java
│   ├── cache/
│   │   ├── TerrainSamplingCache.java
│   │   ├── FastHeightSampler.java
│   │   └── AccurateHeightSampler.java
│   └── post/
│       ├── PathPostProcessor.java
│       └── SplineHelper.java
├── planning/
│   ├── NetworkPlanner.java              // 网络拓扑策略接口
│   ├── NetworkPlannerFactory.java
│   ├── impl/
│   │   ├── DelaunayPlanner.java
│   │   ├── MSTPlanner.java
│   │   └── KNNPlanner.java
│   └── RoadPlanningService.java
├── generation/
│   ├── RoadGenerationService.java       // tick驱动异步调度
│   ├── RoadGenerationTask.java
│   ├── ThreadPoolManager.java
│   └── GenerationStatus.java           // PLANNED→GENERATING→COMPLETED→FAILED
├── construction/
│   ├── road/
│   │   ├── RoadBuilder.java
│   │   ├── RoadSegmentPaver.java        // 宽度3/5/7
│   │   ├── BiomeMaterialSelector.java
│   │   └── StreetlightPlacer.java       // 悬臂式路灯
│   ├── bridge/
│   │   ├── BridgeBuilder.java
│   │   ├── BridgeRangeDetector.java
│   │   ├── BridgeDeckPlacer.java
│   │   ├── BridgeRampBuilder.java       // 半砖+台阶坡道
│   │   ├── BridgePierBuilder.java       // 桥墩（保留核心逻辑）
│   │   ├── BridgePlatformBuilder.java   // 桥头平台
│   │   └── BridgeLightPlacer.java
│   └── execution/
│       ├── ConstructionExecutor.java    // 策略接口
│       ├── TickDrivenExecutor.java      // 默认慢建
│       ├── NpcWorkerExecutor.java
│       ├── HammerBoostExecutor.java     // 建筑锤加速
│       └── ConstructionQueue.java
├── model/
│   ├── RoadData.java
│   ├── RoadSegment.java
│   ├── BridgeSpan.java
│   ├── StructureConnection.java
│   └── RoadMaterial.java
├── config/
│   ├── RoadConfig.java
│   ├── PathfindingConfig.java
│   ├── BridgeConfig.java
│   └── AppearanceConfig.java
└── persistence/
    └── RoadNetworkStorage.java
```

## 3. 寻路系统

### 3.1 策略接口

```java
public interface Pathfinder {
    PathResult findPath(BlockPos start, BlockPos end, TerrainSamplingCache cache);
}
```

PathfinderFactory 根据 PathfindingConfig.algorithm 枚举值创建对应实现。

### 3.2 四种算法

| 算法 | 特点 | 适用场景 |
|------|------|----------|
| BasicAStar | 标准A*，曼哈顿启发式，8方向 | 短距离、简单地形 |
| BidirectionalAStar | 双向搜索，中间汇合 | 中距离，收敛快 |
| GradientDescent | 高度²惩罚，激进寻路 | 山地，强调避坡 |
| PotentialField | 地形梯度+目标引力场（默认） | 通用，路径最平滑 |

### 3.3 代价模型

```
总代价 = 步进代价(正交1.0 / 对角1.4)
       + elevationWeight × 高度变化
       + biomeWeight × 生物群系代价(水域=12)
       + stabilityWeight × 地形稳定性(4方向高度方差)
       + 水深代价(WATER_COLUMN_BASE_PENALTY + depth² × waterDepthWeight)
       + nearWaterCost(邻近水域=50)
       + deviationWeight × 到直线的垂直距离
```

所有权重通过 PathfindingConfig 可配置。

### 3.4 地形缓存

TerrainSamplingCache:
- ConcurrentHashMap 并发安全
- 双模采样: FastHeightSampler(快速近似) / AccurateHeightSampler(世界生成精确)
- 缓存项: 高度、水检测、海底高度、生物群系、近水判定
- 精度级别: NORMAL / HIGH / ULTRA_HIGH

### 3.5 路径后处理

PathPostProcessor 管线:
1. 路径简化 — 移除共线点
2. 桥梁检测 — 标记水域穿越段
3. 桥梁拉直 — 水上段线性插值
4. 路径松弛 — 加权平均平滑(跳过桥梁区域)
5. 样条生成 — Catmull-Rom 或 Bezier (SplineHelper)
6. 光栅化 — 转为带宽度的 RoadSegment 列表

### 3.6 异步执行

- RoadGenerationService 每tick轮询任务队列
- 寻路任务提交到 ThreadPoolManager 线程池(可配置线程数，默认2)
- 完成后回调主线程，状态 GENERATING → COMPLETED
- 支持取消和超时(maxSteps 限制)

## 4. 网络拓扑规划

### 4.1 策略接口

```java
public interface NetworkPlanner {
    List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks);
}
```

### 4.2 三种算法

- DelaunayPlanner: Delaunay三角剖分，增量插入+外接圆检测，按最大边长过滤
- MSTPlanner: Kruskal最小生成树，Union-Find检测环路，产出最小连通图
- KNNPlanner: K近邻，每个结构连接最近的K个邻居

NetworkPlannerFactory 根据配置选择算法。

## 5. 桥梁系统

### 5.1 桥梁检测

BridgeRangeDetector:
- 沿路径扫描连续水域列(水深 ≥ bridgeMinWaterDepth)
- 合并间距 ≤ mergeGap(默认4) 的相邻范围
- 输出 BridgeSpan(startIdx, endIdx, waterSurfaceY, oceanFloorY)

### 5.2 整体剖面

```
                    ┌─────────桥面(水面+5)─────────┐
                    │  实心方块 + 悬臂路灯          │
        台阶爬升 ──┤                               ├── 台阶下降
       /            │     桥墩(海底→桥面)           │            \
  ┌──平台──┐        │         │    │               │        ┌──平台──┐
  │ 启动平台│   半砖过渡       │    │          半砖过渡  │ 启动平台│
──┘ (陆地) └───/────┘         │    │          └────\───┘ (陆地) └──
```

### 5.3 桥头平台 (BridgePlatformBuilder)

- 两端各生成平坦区域
- 尺寸: 宽度=道路宽度, 长度=platformLength(默认3格)
- 高度与陆地齐平
- 作为坡道起始/终止锚点

### 5.4 坡道 (BridgeRampBuilder)

爬升策略: 台阶快速提升 + 半砖 bottom-top-bottom 过渡

爬升序列示例(爬升5格):
```
Y+5: [实心方块]  ← 桥面开始
Y+4: [台阶↑]
Y+3: [半砖top] → [半砖bottom]
Y+2: [台阶↑]
Y+1: [半砖top] → [半砖bottom]
Y+0: [台阶↑]    ← 平台高度
```

规则:
- 每个台阶提升1格高度
- 半砖 top→bottom 提供0.5+0.5的平缓过渡
- 台阶朝向自动匹配道路方向
- 下坡对称处理(反向台阶+半砖)
- 坡道两侧放置栅栏栏杆

### 5.5 桥墩 (BridgePierBuilder)

- 保留现有桥墩逻辑核心
- 从海底到桥面的垂直支撑柱
- 间距: pierInterval(默认8格)
- 材料: 石砖/圆石(可配置)

### 5.6 桥面 (BridgeDeckPlacer)

- 高度: 水面 + deckHeight(默认5格)
- 实心方块铺设，宽度与道路一致
- 两侧栏杆(栅栏)

### 5.7 桥上路灯 (BridgeLightPlacer)

- 间距: lightInterval(默认8格)
- 悬臂式: 栅栏柱3格高 + 横向1格栅栏 + 悬挂灯笼
- 交替放置在道路两侧

## 6. 陆路道路系统

### 6.1 道路铺设 (RoadSegmentPaver)

- 宽度: 玩家可选 3/5/7 格
- 沿路径光栅化，每段按宽度向两侧扩展
- 地形适配: 向下填充最多3格支撑，削平凸起

### 6.2 生物群系材料 (BiomeMaterialSelector)

| 生物群系 | 路面 | 台阶/半砖 |
|----------|------|-----------|
| 平原/草原 | 石砖 | 石砖台阶/半砖 |
| 沙漠 | 砂岩 | 砂岩台阶/半砖 |
| 森林/针叶林 | 泥径 | 橡木台阶/半砖 |
| 雪地 | 圆石 | 圆石台阶/半砖 |
| 沼泽 | 苔石 | 苔石台阶/半砖 |
| 恶地 | 红砂岩 | 红砂岩台阶/半砖 |

映射表通过配置文件可自定义扩展。

### 6.3 陆路坡道

- 地形高度变化时使用与桥梁相同的台阶+半砖系统
- 坡度限制: 每3格水平距离最多1格高度变化(超过则绕行)

### 6.4 陆路路灯 (StreetlightPlacer)

- 间距: landLightInterval(默认24格)
- 悬臂式: 栅栏柱3格 + 横向栅栏 + 灯笼
- 交替放置在道路两侧
- 材料跟随生物群系(橡木栅栏/云杉栅栏/石墙等)

### 6.5 道路边缘

- 3格宽: 无额外边缘
- 5格宽: 两侧各1格半砖边缘
- 7格宽: 两侧各1格半砖边缘 + 间隔栏杆

## 7. 施工执行系统

### 7.1 三模施工

| 模式 | 类 | 触发 | 速度 |
|------|-----|------|------|
| 自动慢建 | TickDrivenExecutor | 默认 | 每5tick放1方块 |
| NPC工人 | NpcWorkerExecutor | 分配工人 | 每2tick放1方块 |
| 建筑锤加速 | HammerBoostExecutor | 玩家右键 | 每tick放4方块 |

### 7.2 施工队列

ConstructionQueue 管理有序 BuildStep 列表:
1. 地基填充
2. 路面铺设
3. 坡道/台阶
4. 桥墩
5. 桥面
6. 栏杆
7. 路灯

支持暂停/恢复/取消，进度同步到客户端。

## 8. 配置系统

```
RoadConfig
├── PathfindingConfig
│   ├── algorithm: POTENTIAL_FIELD
│   ├── maxSteps: 10000
│   ├── samplingPrecision: NORMAL
│   ├── elevationWeight: 2.0
│   ├── biomeWeight: 1.0
│   ├── waterDepthWeight: 1.0
│   └── threadPoolSize: 2
├── BridgeConfig
│   ├── deckHeight: 5
│   ├── pierInterval: 8
│   ├── platformLength: 3
│   ├── lightInterval: 8
│   ├── mergeGap: 4
│   └── bridgeMinWaterDepth: 2
├── AppearanceConfig
│   ├── defaultWidth: 3
│   ├── landLightInterval: 24
│   ├── biomeMaterials: Map<Biome, MaterialSet>
│   └── lightStyle: CANTILEVER
└── ConstructionConfig
    ├── tickSlowRate: 5
    ├── npcRate: 2
    ├── hammerRate: 1
    └── hammerBatchSize: 4
```

## 9. 数据流

```
结构发现 → NetworkPlanner(拓扑决策)
  → StructureConnection 列表
  → RoadGenerationService(异步调度)
    → ThreadPool: Pathfinder(寻路) + PathPostProcessor(后处理)
    → 回到主线程: BridgeRangeDetector(桥梁检测)
    → RoadBuilder / BridgeBuilder(生成BuildStep)
    → ConstructionQueue(施工队列)
    → ConstructionExecutor(三模执行)
```

## 10. 需要删除的现有类

construction/ 包下所有 Road* 类:
- RoadBridgePlanner, RoadBridgePierPlanner, RoadCorridorPlanner
- RoadGeometryPlanner, RoadBezierCenterline, RoadRouteNodePlanner
- RoadCorridorPlan, RoadPlacementPlan, RoadTerrainShaper
- RoadLightingPlanner, RoadCoreExclusion, RoadLegacyJobRebuilder

nation/service/ 包下所有 Road* 类:
- RoadPathfinder, LandRoadHybridPathfinder
- SegmentedRoadPathOrchestrator, GroundRouteSkeletonPlanner
- RouteSkeleton, RoadNetworkSnapService
- RoadHybridRouteResolver, LandRoadRouteSelector
- RoadTerrainAnalysisService, RoadTerrainSamplingCache
- RoadPlanningTaskService, RoadSelectionService
- RoadPlanningSnapshotBuilder, RoadPlanningSnapshot
- RoadPlanningPassContext, RoadPlanningRequestContext
- RoadPlanningFailureReason, RoadPlanningIslandClassifier
- RoadPlanningDebugLogger, RoadPathPostProcessor
- LandPathCostModel, LandPathQualityEvaluator

需要适配但不删除的:
- StructureConstructionManager (适配新接口)
- ManualRoadPlannerService (重写内部逻辑，保留对外接口)
- RoadLifecycleService (重写内部逻辑，保留生命周期管理职责)
- RoadPlannerItem, 客户端UI类 (适配新数据模型)
- 网络包类 (适配新数据结构)
- RoadNetworkRecord (可能需要扩展字段)
