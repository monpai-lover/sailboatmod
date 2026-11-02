# 道路系统补强规格

> 日期: 2026-04-19
> 前置: 2026-04-19-road-system-refactor-design.md (已实施)

## 1. 概述

补完所有桩实现，对齐RoadWeaver的激进寻路策略，使道路系统完整可用并编译出JAR。

## 2. 范围

### Layer 0: 代价模型修正 + 4个寻路算法
- 修正 TerrainCostModel 参数对齐RoadWeaver（水域惩罚800、高度权重80、坡度惩罚等）
- 修正 PathfindingConfig 默认值
- 新增 TerrainGradientHelper（地形梯度计算）
- 实现 BasicAStarPathfinder（标准A*，8方向，线性高度代价）
- 实现 BidirectionalAStarPathfinder（双向A*，前后交替扩展）
- 实现 GradientDescentPathfinder（平方高度惩罚+坡度软硬阈值）
- 实现 PotentialFieldPathfinder（地形梯度+等高线引导，最激进）

### Layer 0.5: 路面清障 + 可选隧道挖掘
- 新增 AboveColumnClearer — 铺路时清除路面上方4格方块（树木、悬挂物、雪层）
- 新增 TunnelDigger — 当坡度超阈值且绕行代价过高时，挖掘山体创建通道
- 隧道模式可通过配置开关（默认关闭），开启后清除高度增至5格+侧壁挖掘
- RoadSegmentPaver 集成清障逻辑，每个路段铺设后自动清除上方障碍
- 草方块转泥土（路面下方2格）

### Layer 1: 桥接层（旧RoadPathfinder → 新系统）
- RoadPathfinder 的 findPath/findGroundPath 委托到新 PathfinderFactory
- RoadPlanningSnapshotBuilder 委托到新 TerrainSamplingCache
- RoadPlanningSnapshot 包装新缓存数据

### Layer 2: 服务层
- ManualRoadPlannerService 核心方法接入新 RoadBuilder/RoadNetworkApi
- RoadAutoRouteService 接入新寻路+后处理管线
- RoadLifecycleService 接入新 ConstructionQueue
- ArmyCommandManager.buildRoute 接入新寻路

### Layer 3: 兼容层（旧construction桩类）
- RoadGeometryPlanner 委托到新 RoadSegmentPaver + BridgeBuilder
- RoadBridgePlanner 委托到新 BridgeRangeDetector + BridgeBuilder
- RoadCorridorPlanner 委托到新 RoadBuilder
- RoadPlacementPlan 包装新 RoadData
- 其余桩类（RoadTerrainShaper、RoadCoreExclusion等）提供最小可用实现

### Layer 4: 编译JAR
- `./gradlew build` 产出 `-all.jar`

## 3. 代价模型修正细节

### PathfindingConfig 新默认值
```
algorithm = POTENTIAL_FIELD
maxSteps = 20000
elevationWeight = 80.0
biomeWeight = 2.0
stabilityWeight = 15.0
waterDepthWeight = 80.0
nearWaterCost = 80.0
deviationWeight = 0.5
heuristicWeight = 15.0
aStarStep = 8
```

### TerrainCostModel 新常量
```
WATER_BIOME_COST = 12.0
WATER_COLUMN_BASE_PENALTY = 800.0
WATER_DEPTH_SQUARED_WEIGHT = 2.0
SLOPE_SOFT_THRESHOLD = 0.5
SLOPE_HARD_THRESHOLD = 0.8
SLOPE_SOFT_PENALTY = 800.0
SLOPE_HARD_PENALTY = 8000.0
SOFT_GRADE_LIMIT = 0.08
HARD_GRADE_LIMIT = 0.15
SOFT_GRADE_PENALTY = 600.0
HARD_GRADE_PENALTY = 6000.0
CONTOUR_DISCOUNT = 0.45
GRADIENT_ALIGN_PENALTY = 80.0
```

### 新增方法
- `slopeCost(fromX, fromZ, toX, toZ, cache)` — 坡度软硬阈值惩罚
- `gradeCost(elevation, horizontalDist)` — 坡度百分比惩罚
- `waterCostAggressive(depth, weight)` — 平方水深惩罚（允许穿水建桥）

## 4. 四种算法实现要点

### BasicAStarPathfinder
- 8方向移动，步长=aStarStep
- 代价: stepCost + elevation×weight + biome + stability + waterDepth×weight + nearWater + deviation
- 启发式: Chebyshev近似 `|dx|+|dz| - 0.6×min(|dx|,|dz|)` × heuristicWeight
- 成功条件: 曼哈顿距离 < step×2

### BidirectionalAStarPathfinder
- 前向+后向两个open集合交替扩展
- HEURISTIC_EPSILON = 0.2 偏向前向
- 相遇检测: 节点同时出现在两个bestG中
- 路径合并: forward正序 + backward反序

### GradientDescentPathfinder
- 平方高度惩罚: elevation² × weight
- 坡度惩罚: slope > 0.5 → +800, slope > 0.8 → +8000
- 平方水深惩罚: 800 + depth² × weight × 2.0
- 步数预算: max(5000, maxSteps × 3)
- 成功距离因子: 1.5

### PotentialFieldPathfinder（默认，最激进）
- 地形梯度: TerrainGradientHelper.terrainGradient() 中心差分
- 等高线方向: 垂直于梯度，朝向目标对齐
- 等高线折扣: CONTOUR_DISCOUNT = 0.45
- 坡度百分比惩罚: grade > 0.08 → +600, grade > 0.15 → +6000
- 梯度对齐惩罚: GRADIENT_ALIGN_PENALTY = 80.0
- 平方高度代价: elevation² × weight × 0.5
- 步数预算: max(5000, maxSteps × 4)
- 搜索缓冲: min(1024, manhattan/3)

## 5. TerrainGradientHelper（新增）

```java
terrainGradient(x, z, cache) → double[2]  // 中心差分 [gradX, gradZ]
contourDirection(gradX, gradZ, goalDirX, goalDirZ) → double[2]  // 垂直于梯度
contourAlignment(moveX, moveZ, contourX, contourZ) → double  // 点积
computeGrade(elevation, horizontalDist) → double  // 坡度百分比
gradientMagnitude(gradX, gradZ) → double
```

## 5.5 曲线工具补强

### SplineHelper 扩展
现有 Catmull-Rom 保留，新增：
- `bezierDeCasteljau(controlPoints, t)` — De Casteljau递归细分算法
- `elevateBezierDegree(points)` — 贝塞尔升阶（三次→五次）
- `CurveMode` 枚举: `CATMULL_ROM`, `BEZIER_CASTELJAU`
- `interpolate(controlPoints, segmentsPerSpan, mode)` — 根据模式选择算法

### 自动曲线模式切换
PathPostProcessor 在样条生成阶段按段落自动选择曲线模式：
- 转弯角度 > 60° 的段落 → `BEZIER_CASTELJAU`（更平滑的急弯过渡）
- 直线或缓弯段落 → `CATMULL_ROM`（计算更快，直线段效果好）
- 桥梁段 → 不做样条，保持线性拉直

判定逻辑：
```
for each span of 4 control points:
    angle = angleBetween(p0→p1, p2→p3)
    if angle > 60° → BEZIER_CASTELJAU
    else → CATMULL_ROM
```

### HeightProfileSmoother（新增类）
位置: `road/pathfinding/post/HeightProfileSmoother.java`

双向坡度限制平滑算法，确保道路高度变化不超过最大坡度：
```
输入: List<BlockPos> path, List<BridgeSpan> bridges, double maxSlopePerSegment
输出: int[] smoothedHeights

算法:
1. 前向pass: 从起点到终点，每个点的高度 ≤ 前一点 + maxSlope
2. 后向pass: 从终点到起点，每个点的高度 ≤ 后一点 + maxSlope
3. 桥梁段高度保持不变（跳过）
```

### RoadHeightInterpolator（新增类）
位置: `road/pathfinding/post/RoadHeightInterpolator.java`

沿路径中心线的线性高度插值：
- `interpolateHeight(BlockPos point, List<BlockPos> centerPath, int[] heights)` — 投影到最近路段，线性插值Y值
- `batchInterpolate(List<BlockPos> points, List<BlockPos> centerPath, int[] heights)` — 批量插值（局部搜索优化）

### PathPostProcessor 集成
在现有管线中插入高度平滑步骤：
```
简化 → 桥梁检测 → 桥梁拉直 → 路径松弛 → 高度剖面平滑(新) → 样条生成 → 光栅化
```

## 5.8 分段并行寻路（SegmentedParallelPathfinder）

位置: `road/pathfinding/impl/SegmentedParallelPathfinder.java`

长距离路径自动细分为子段，提交到线程池并行求解，完成后合并。

### 触发条件
- 曼哈顿距离 > `SEGMENT_THRESHOLD`（默认96格，可配置）
- 低于阈值时直接用单线程 Pathfinder

### 细分策略
```
1. 计算 A→B 直线
2. 沿直线等距插入中间锚点 C1, C2, ... Cn（每段 ≤ SEGMENT_THRESHOLD）
3. 每个锚点投影到最近地面位置（避免悬空）
4. 生成子段: [A→C1], [C1→C2], ..., [Cn→B]
```

### 并行执行
```java
List<CompletableFuture<PathResult>> futures = new ArrayList<>();
for (SegmentRequest seg : segments) {
    futures.add(CompletableFuture.supplyAsync(
        () -> pathfinder.findPath(seg.from(), seg.to(), cache),
        threadPool.getExecutor()
    ));
}
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

### 路径合并
- 顺序拼接各子段路径，去除重叠的锚点
- 任一子段失败 → 整体失败（返回失败原因）
- 合并后的路径交给 PathPostProcessor 做统一后处理

### 配置
PathfindingConfig 新增：
```
segmentThreshold = 96       // 分段阈值（曼哈顿距离）
maxSegments = 8             // 最大分段数
```

### 集成
- PathfinderFactory 新增: 当距离 > threshold 时自动包装为 SegmentedParallelPathfinder
- RoadGenerationService 无需改动（已经是异步的，内部再并行）

## 6. 桥接层细节

### RoadPathfinder（旧） → 新系统
```java
findPath(level, from, to, blocked, allowWater) {
    cache = new TerrainSamplingCache(level, NORMAL);
    pathfinder = PathfinderFactory.create(config);
    result = pathfinder.findPath(from, to, cache);
    if (result.success()) return result.path();
    return List.of();
}
```

### ManualRoadPlannerService 核心方法
- `buildPlanCandidates()` → 调用新 Pathfinder + PathPostProcessor + RoadBuilder
- `planSegment()` → 委托到新寻路管线
- `postProcessPath()` → 委托到新 PathPostProcessor

### RoadAutoRouteService
- `resolveAutoRouteAsync()` → 提交到 RoadGenerationService
- `findLandRoute()` / `findPathWithSnapshot()` → 委托到新 Pathfinder

## 8. 路面清障 + 可选隧道挖掘

### AboveColumnClearer（新增类）
位置: `road/construction/road/AboveColumnClearer.java`

铺路后自动清除路面上方障碍物：
```
默认模式: 清除路面上方 4 格（树木、树叶、悬挂物、雪层）
隧道模式: 清除路面上方 5 格 + 侧壁挖掘（可配置 2-16 格）
```

规则：
- 跳过空气方块
- 清除所有非空气方块为 AIR
- 在清除高度+1处额外清除灌木和雪层
- 路面下方2格的草方块转为泥土（防止草蔓延到路面）
- 不清除基岩和屏障方块

### TunnelDigger（新增类）
位置: `road/construction/road/TunnelDigger.java`

当路径穿过山体时挖掘通道：
- 触发条件: 路面上方连续 N 格（默认3）都是实心方块 → 判定为"山体内部"
- 挖掘范围: 宽度=道路宽度+2（两侧各1格间隙），高度=清除高度
- 顶部放置石砖作为隧道顶板（防止沙砾/沙子塌落）
- 两侧放置石砖墙壁（1格厚）
- 每8格放置一个火把提供照明
- 隧道入口/出口处放置台阶过渡

### 配置扩展
AppearanceConfig 新增：
```
tunnelEnabled = false        // 隧道模式开关
roadClearHeight = 4          // 默认清除高度
tunnelClearHeight = 5        // 隧道清除高度
tunnelLightInterval = 8      // 隧道火把间距
```

### RoadSegmentPaver 集成
铺设每个路段后调用:
1. `AboveColumnClearer.clear(pos, width, clearHeight)` — 清除上方
2. 如果 tunnelEnabled 且检测到山体内部 → `TunnelDigger.dig(pos, width, tunnelHeight)` — 挖掘隧道

## 9. 道路规划器UI交互重构

### 9.1 新增配置屏幕（RoadPlannerConfigScreen）
位置: `client/screen/RoadPlannerConfigScreen.java`

在目标城镇选择后、寻路开始前弹出，让玩家配置本次道路参数：
- 道路宽度: 3/5/7 格（按钮组切换）
- 寻路算法: 基础A*/双向A*/梯度下降/势场法（下拉选择）
- 材料预设: 自动(生物群系)/石砖/砂岩/圆石/苔石/红砂岩/泥径（下拉选择）
- 隧道模式: 开/关（复选框）
- 确认按钮 → 发送 `ConfigureRoadPlannerPacket` 到服务器，开始寻路

新增网络包: `ConfigureRoadPlannerPacket`（Client→Server）
- 字段: width, algorithm, materialPreset, tunnelEnabled

### 9.2 预览渲染重构（RoadPlannerPreviewRenderer）

#### 半透明材料纹理预览
- 幽灵方块改为半透明（alpha=0.45）实际方块纹理渲染
- 使用 `RenderType.translucent()` 渲染层
- 路面方块显示实际材料纹理（石砖/砂岩等）
- 台阶/半砖显示对应形状

#### 流动粒子路径动画
- 沿路径中心线生成流动粒子（类似末影之眼轨迹）
- 粒子颜色: 青色(0.3, 0.9, 0.88)，从起点流向终点
- 粒子间距: 每2格一个，速度: 每tick移动0.5格
- 自定义 `RoadPathParticle` 继承 `TextureSheetParticle`

#### 路段类型彩色高亮
- 陆路段: 绿色路径线 (0.2, 0.9, 0.3)
- 桥梁段: 蓝色路径线 (0.3, 0.6, 1.0)
- 隧道段: 棕色路径线 (0.7, 0.45, 0.2)
- 坡道段: 黄色路径线 (0.95, 0.85, 0.2)

### 9.3 增强HUD面板

重做HUD为紧凑面板（右上角，220×variable）：
- 标题栏: "道路规划器" + 当前模式(建造/取消/拆除)
- 路线信息: "[源城镇] → [目标城镇]" + 距离
- 小地图路径缩略图: 64×64像素俯视图，显示路径走向
- 配置摘要: "宽度:5 | 材料:石砖 | 算法:势场法"
- 分段进度条: 每个寻路子段一个小进度条
- 预计完成时间
- 施工进度（如有）: 阶段名 + 百分比 + 工人数

### 9.4 路径锚点拖拽调整

预览状态下玩家可交互调整路线：
- 路径上每隔20格显示可交互锚点（金色小方块）
- 对准锚点 + 右键拖拽 → 移动锚点位置
- 释放后局部重新寻路（只重算前后两段）
- 新增网络包: `DragRoadAnchorPacket`（Client→Server）
  - 字段: anchorIndex, newPosition

### 9.5 施工详情查看

施工中对准道路 + 右键道路规划器：
- 弹出施工详情HUD叠加层
- 显示: 当前阶段、已放置/总方块数、剩余时间、施工模式、工人列表
- 新增网络包: `RequestConstructionDetailPacket` / `SyncConstructionDetailPacket`

### 9.6 世界内浮动标签

3D世界中显示路线关键信息：
- 起点/终点: 浮动文字标签显示城镇名
- 桥梁段: "桥梁 [长度]格"
- 隧道段: "隧道 [长度]格"
- Billboard渲染面向玩家，距离>64格隐藏

## 10. 编译JAR
- `./gradlew build` 产出 `build/libs/sailboatmod-*-all.jar`
