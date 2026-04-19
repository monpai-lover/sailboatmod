# 道路系统全面重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完全重构道路/桥梁系统，基于RoadWeaver架构实现策略模式寻路、网络拓扑规划、异步生成、生物群系材料、半砖+台阶桥梁坡道、悬臂式路灯、三模施工。

**Architecture:** 新代码位于 `com.monpai.sailboatmod.road` 包下，分为 config/model/pathfinding/planning/generation/construction 六大模块。寻路和网络拓扑使用策略模式+工厂模式，施工执行使用策略模式三模切换。桥梁系统拆分为独立子组件（检测/桥墩/坡道/桥面/平台/路灯）。

**Tech Stack:** Minecraft Forge 1.20.1, Java 17, ConcurrentHashMap (terrain cache), ExecutorService (async generation)

**Base paths:**
- Source: `sailboatmod/src/main/java/com/monpai/sailboatmod/road/`
- Reference: `Ref/RoadWeaver-1.20.1-Architectury/common/src/main/java/net/shiroha233/roadweaver/`
- Existing code: `sailboatmod/src/main/java/com/monpai/sailboatmod/`

---

## File Structure

### New files to create (under `road/`):

**config/**
- `RoadConfig.java` — 根配置聚合器
- `PathfindingConfig.java` — 寻路参数（算法、权重、精度、线程数）
- `BridgeConfig.java` — 桥梁参数（桥面高度、桥墩间距、平台长度）
- `AppearanceConfig.java` — 外观参数（宽度、路灯间距、材料映射）
- `ConstructionConfig.java` — 施工参数（各模式速率）

**model/**
- `RoadData.java` — 道路完整数据（宽度、类型、材料、路段、桥梁跨度）
- `RoadSegment.java` — 路段数据（中心点、方块位置列表、高度）
- `BridgeSpan.java` — 桥梁跨度（起止索引、水面Y、海底Y）
- `StructureConnection.java` — 结构连接边（from/to/status）
- `RoadMaterial.java` — 材料集定义（路面/台阶/半砖/栅栏）
- `ConnectionStatus.java` — 连接状态枚举
- `BuildStep.java` — 施工步骤（位置、方块状态、阶段、顺序）
- `BuildPhase.java` — 施工阶段枚举

**pathfinding/**
- `Pathfinder.java` — 策略接口
- `PathfinderFactory.java` — 工厂
- `PathResult.java` — 结果封装
- `cost/TerrainCostModel.java` — 地形代价模型
- `cache/TerrainSamplingCache.java` — 并发地形缓存
- `cache/FastHeightSampler.java` — 快速高度采样
- `cache/AccurateHeightSampler.java` — 精确高度采样
- `impl/BasicAStarPathfinder.java` — 基础A*
- `impl/BidirectionalAStarPathfinder.java` — 双向A*
- `impl/GradientDescentPathfinder.java` — 梯度下降
- `impl/PotentialFieldPathfinder.java` — 势场法
- `post/PathPostProcessor.java` — 路径后处理管线
- `post/SplineHelper.java` — 样条插值

**planning/**
- `NetworkPlanner.java` — 网络拓扑策略接口
- `NetworkPlannerFactory.java` — 拓扑工厂
- `impl/DelaunayPlanner.java` — Delaunay三角剖分
- `impl/MSTPlanner.java` — 最小生成树
- `impl/KNNPlanner.java` — K近邻
- `RoadPlanningService.java` — 规划编排服务

**generation/**
- `RoadGenerationService.java` — tick驱动异步调度
- `RoadGenerationTask.java` — 异步生成任务
- `ThreadPoolManager.java` — 线程池管理
- `GenerationStatus.java` — 状态枚举

**construction/road/**
- `RoadBuilder.java` — 道路建造核心编排
- `RoadSegmentPaver.java` — 路段铺设
- `BiomeMaterialSelector.java` — 生物群系材料选择
- `StreetlightPlacer.java` — 悬臂式路灯

**construction/bridge/**
- `BridgeBuilder.java` — 桥梁建造核心编排
- `BridgeRangeDetector.java` — 桥梁范围检测
- `BridgeDeckPlacer.java` — 桥面铺设
- `BridgeRampBuilder.java` — 半砖+台阶坡道
- `BridgePierBuilder.java` — 桥墩建造
- `BridgePlatformBuilder.java` — 桥头平台
- `BridgeLightPlacer.java` — 桥上路灯

**construction/execution/**
- `ConstructionExecutor.java` — 施工执行器策略接口
- `TickDrivenExecutor.java` — tick驱动慢建
- `NpcWorkerExecutor.java` — NPC工人施工
- `HammerBoostExecutor.java` — 建筑锤加速
- `ConstructionQueue.java` — 施工任务队列

**api/**
- `RoadNetworkApi.java` — 公共API入口

**persistence/**
- `RoadNetworkStorage.java` — 道路网络持久化

### Existing files to modify:
- `nation/service/StructureConstructionManager.java` — 适配新接口
- `nation/service/ManualRoadPlannerService.java` — 重写内部逻辑
- `nation/service/RoadLifecycleService.java` — 重写内部逻辑
- `item/RoadPlannerItem.java` — 适配新数据模型
- `nation/model/RoadNetworkRecord.java` — 扩展字段

### Existing files to delete (Task 1):
- All `Road*` classes under `construction/`
- All `Road*`/`Land*`/`RouteSkeleton`/`GroundRoute*`/`SegmentedRoad*` classes under `nation/service/`

---

## Task 1: 清理旧代码 + 创建包结构

**Files:**
- Delete: all `Road*`, `Land*`, `RouteSkeleton*`, `GroundRoute*`, `SegmentedRoad*` classes under `construction/` and `nation/service/`
- Create: package directories under `road/`

- [ ] **Step 1: 备份并删除旧的 construction/ 道路类**

删除以下文件（在 `construction/` 包下）:
```
RoadBridgePlanner.java
RoadBridgePierPlanner.java
RoadCorridorPlanner.java
RoadGeometryPlanner.java
RoadBezierCenterline.java
RoadRouteNodePlanner.java
RoadCorridorPlan.java
RoadPlacementPlan.java
RoadTerrainShaper.java
RoadLightingPlanner.java
RoadCoreExclusion.java
RoadLegacyJobRebuilder.java
```

```bash
cd sailboatmod/src/main/java/com/monpai/sailboatmod/construction
git rm RoadBridgePlanner.java RoadBridgePierPlanner.java RoadCorridorPlanner.java RoadGeometryPlanner.java RoadBezierCenterline.java RoadRouteNodePlanner.java RoadCorridorPlan.java RoadPlacementPlan.java RoadTerrainShaper.java RoadLightingPlanner.java RoadCoreExclusion.java RoadLegacyJobRebuilder.java
```

- [ ] **Step 2: 删除旧的 nation/service/ 道路类**

删除以下文件（在 `nation/service/` 包下）:
```
RoadPathfinder.java
LandRoadHybridPathfinder.java
SegmentedRoadPathOrchestrator.java
GroundRouteSkeletonPlanner.java
RouteSkeleton.java
RoadNetworkSnapService.java
RoadHybridRouteResolver.java
LandRoadRouteSelector.java
RoadTerrainAnalysisService.java
RoadTerrainSamplingCache.java
RoadPlanningTaskService.java
RoadSelectionService.java
RoadPlanningSnapshotBuilder.java
RoadPlanningSnapshot.java
RoadPlanningPassContext.java
RoadPlanningRequestContext.java
RoadPlanningFailureReason.java
RoadPlanningIslandClassifier.java
RoadPlanningDebugLogger.java
RoadPathPostProcessor.java
LandPathCostModel.java
LandPathQualityEvaluator.java
```

```bash
cd sailboatmod/src/main/java/com/monpai/sailboatmod/nation/service
git rm RoadPathfinder.java LandRoadHybridPathfinder.java SegmentedRoadPathOrchestrator.java GroundRouteSkeletonPlanner.java RouteSkeleton.java RoadNetworkSnapService.java RoadHybridRouteResolver.java LandRoadRouteSelector.java RoadTerrainAnalysisService.java RoadTerrainSamplingCache.java RoadPlanningTaskService.java RoadSelectionService.java RoadPlanningSnapshotBuilder.java RoadPlanningSnapshot.java RoadPlanningPassContext.java RoadPlanningRequestContext.java RoadPlanningFailureReason.java RoadPlanningIslandClassifier.java RoadPlanningDebugLogger.java RoadPathPostProcessor.java LandPathCostModel.java LandPathQualityEvaluator.java
```

- [ ] **Step 3: 创建新包目录结构**

```bash
cd sailboatmod/src/main/java/com/monpai/sailboatmod
mkdir -p road/config road/model road/pathfinding/impl road/pathfinding/cost road/pathfinding/cache road/pathfinding/post road/planning/impl road/generation road/construction/road road/construction/bridge road/construction/execution road/api road/persistence
```

- [ ] **Step 4: 提交清理**

```bash
git add -A
git commit -m "refactor: remove legacy road/bridge system classes, create new road package structure"
```

---

## Task 2: 配置系统 + 数据模型

**Files:**
- Create: `road/config/RoadConfig.java`, `road/config/PathfindingConfig.java`, `road/config/BridgeConfig.java`, `road/config/AppearanceConfig.java`, `road/config/ConstructionConfig.java`
- Create: `road/model/RoadSegment.java`, `road/model/BridgeSpan.java`, `road/model/StructureConnection.java`, `road/model/ConnectionStatus.java`, `road/model/RoadMaterial.java`, `road/model/BuildStep.java`, `road/model/BuildPhase.java`, `road/model/RoadData.java`

- [ ] **Step 1: 创建枚举类型**

`road/model/ConnectionStatus.java`:
```java
package com.monpai.sailboatmod.road.model;

public enum ConnectionStatus {
    PLANNED, GENERATING, COMPLETED, FAILED
}
```

`road/model/BuildPhase.java`:
```java
package com.monpai.sailboatmod.road.model;

public enum BuildPhase {
    FOUNDATION, SURFACE, RAMP, PIER, DECK, RAILING, STREETLIGHT
}
```

- [ ] **Step 2: 创建核心数据记录**

`road/model/RoadSegment.java`:
```java
package com.monpai.sailboatmod.road.model;

import net.minecraft.core.BlockPos;
import java.util.List;

public record RoadSegment(BlockPos center, List<BlockPos> blockPositions, int height) {}
```

`road/model/BridgeSpan.java`:
```java
package com.monpai.sailboatmod.road.model;

public record BridgeSpan(int startIndex, int endIndex, int waterSurfaceY, int oceanFloorY) {
    public int length() { return endIndex - startIndex; }
}
```

`road/model/StructureConnection.java`:
```java
package com.monpai.sailboatmod.road.model;

import net.minecraft.core.BlockPos;

public record StructureConnection(BlockPos from, BlockPos to, ConnectionStatus status) {
    public double distance() {
        return Math.sqrt(from.distSqr(to));
    }
    public StructureConnection withStatus(ConnectionStatus newStatus) {
        return new StructureConnection(from, to, newStatus);
    }
}
```

`road/model/RoadMaterial.java`:
```java
package com.monpai.sailboatmod.road.model;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public record RoadMaterial(Block surface, Block stair, Block slab, Block fence, Block fenceGate) {
    public static final RoadMaterial STONE_BRICK = new RoadMaterial(
        Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB,
        Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE
    );
    public static final RoadMaterial SANDSTONE = new RoadMaterial(
        Blocks.SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB,
        Blocks.BIRCH_FENCE, Blocks.BIRCH_FENCE_GATE
    );
    public static final RoadMaterial COBBLESTONE = new RoadMaterial(
        Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_SLAB,
        Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE
    );
    public static final RoadMaterial MOSSY_COBBLE = new RoadMaterial(
        Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_STAIRS, Blocks.MOSSY_COBBLESTONE_SLAB,
        Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_FENCE_GATE
    );
    public static final RoadMaterial RED_SANDSTONE = new RoadMaterial(
        Blocks.RED_SANDSTONE, Blocks.RED_SANDSTONE_STAIRS, Blocks.RED_SANDSTONE_SLAB,
        Blocks.ACACIA_FENCE, Blocks.ACACIA_FENCE_GATE
    );
    public static final RoadMaterial DIRT_PATH = new RoadMaterial(
        Blocks.DIRT_PATH, Blocks.OAK_STAIRS, Blocks.OAK_SLAB,
        Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE
    );
}
```

`road/model/BuildStep.java`:
```java
package com.monpai.sailboatmod.road.model;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record BuildStep(int order, BlockPos pos, BlockState state, BuildPhase phase) {}
```

- [ ] **Step 3: 创建 RoadData**

`road/model/RoadData.java`:
```java
package com.monpai.sailboatmod.road.model;

import net.minecraft.core.BlockPos;
import java.util.List;

public record RoadData(
    String roadId,
    int width,
    List<RoadSegment> segments,
    List<BridgeSpan> bridgeSpans,
    RoadMaterial material,
    List<BuildStep> buildSteps,
    List<BlockPos> centerPath
) {}
```

- [ ] **Step 4: 创建配置类**

`road/config/PathfindingConfig.java`:
```java
package com.monpai.sailboatmod.road.config;

public class PathfindingConfig {
    public enum Algorithm { BASIC_ASTAR, BIDIRECTIONAL_ASTAR, GRADIENT_DESCENT, POTENTIAL_FIELD }
    public enum SamplingPrecision { NORMAL, HIGH, ULTRA_HIGH }

    private Algorithm algorithm = Algorithm.POTENTIAL_FIELD;
    private int maxSteps = 10000;
    private SamplingPrecision samplingPrecision = SamplingPrecision.NORMAL;
    private double elevationWeight = 2.0;
    private double biomeWeight = 1.0;
    private double stabilityWeight = 1.0;
    private double waterDepthWeight = 1.0;
    private double nearWaterCost = 50.0;
    private double deviationWeight = 0.5;
    private double heuristicWeight = 1.0;
    private int aStarStep = 8;
    private int threadPoolSize = 2;

    public Algorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(Algorithm algorithm) { this.algorithm = algorithm; }
    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public SamplingPrecision getSamplingPrecision() { return samplingPrecision; }
    public void setSamplingPrecision(SamplingPrecision p) { this.samplingPrecision = p; }
    public double getElevationWeight() { return elevationWeight; }
    public void setElevationWeight(double w) { this.elevationWeight = w; }
    public double getBiomeWeight() { return biomeWeight; }
    public void setBiomeWeight(double w) { this.biomeWeight = w; }
    public double getStabilityWeight() { return stabilityWeight; }
    public void setStabilityWeight(double w) { this.stabilityWeight = w; }
    public double getWaterDepthWeight() { return waterDepthWeight; }
    public void setWaterDepthWeight(double w) { this.waterDepthWeight = w; }
    public double getNearWaterCost() { return nearWaterCost; }
    public void setNearWaterCost(double c) { this.nearWaterCost = c; }
    public double getDeviationWeight() { return deviationWeight; }
    public void setDeviationWeight(double w) { this.deviationWeight = w; }
    public double getHeuristicWeight() { return heuristicWeight; }
    public void setHeuristicWeight(double w) { this.heuristicWeight = w; }
    public int getAStarStep() { return aStarStep; }
    public void setAStarStep(int step) { this.aStarStep = step; }
    public int getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(int size) { this.threadPoolSize = size; }
}
```

`road/config/BridgeConfig.java`:
```java
package com.monpai.sailboatmod.road.config;

public class BridgeConfig {
    private int deckHeight = 5;
    private int pierInterval = 8;
    private int platformLength = 3;
    private int lightInterval = 8;
    private int mergeGap = 4;
    private int bridgeMinWaterDepth = 2;

    public int getDeckHeight() { return deckHeight; }
    public void setDeckHeight(int h) { this.deckHeight = h; }
    public int getPierInterval() { return pierInterval; }
    public void setPierInterval(int i) { this.pierInterval = i; }
    public int getPlatformLength() { return platformLength; }
    public void setPlatformLength(int l) { this.platformLength = l; }
    public int getLightInterval() { return lightInterval; }
    public void setLightInterval(int i) { this.lightInterval = i; }
    public int getMergeGap() { return mergeGap; }
    public void setMergeGap(int g) { this.mergeGap = g; }
    public int getBridgeMinWaterDepth() { return bridgeMinWaterDepth; }
    public void setBridgeMinWaterDepth(int d) { this.bridgeMinWaterDepth = d; }
}
```

`road/config/AppearanceConfig.java`:
```java
package com.monpai.sailboatmod.road.config;

public class AppearanceConfig {
    private int defaultWidth = 3;
    private int landLightInterval = 24;

    public int getDefaultWidth() { return defaultWidth; }
    public void setDefaultWidth(int w) { this.defaultWidth = w; }
    public int getLandLightInterval() { return landLightInterval; }
    public void setLandLightInterval(int i) { this.landLightInterval = i; }
}
```

`road/config/ConstructionConfig.java`:
```java
package com.monpai.sailboatmod.road.config;

public class ConstructionConfig {
    private int tickSlowRate = 5;
    private int npcRate = 2;
    private int hammerRate = 1;
    private int hammerBatchSize = 4;

    public int getTickSlowRate() { return tickSlowRate; }
    public void setTickSlowRate(int r) { this.tickSlowRate = r; }
    public int getNpcRate() { return npcRate; }
    public void setNpcRate(int r) { this.npcRate = r; }
    public int getHammerRate() { return hammerRate; }
    public void setHammerRate(int r) { this.hammerRate = r; }
    public int getHammerBatchSize() { return hammerBatchSize; }
    public void setHammerBatchSize(int s) { this.hammerBatchSize = s; }
}
```

`road/config/RoadConfig.java`:
```java
package com.monpai.sailboatmod.road.config;

public class RoadConfig {
    private final PathfindingConfig pathfinding = new PathfindingConfig();
    private final BridgeConfig bridge = new BridgeConfig();
    private final AppearanceConfig appearance = new AppearanceConfig();
    private final ConstructionConfig construction = new ConstructionConfig();

    public PathfindingConfig getPathfinding() { return pathfinding; }
    public BridgeConfig getBridge() { return bridge; }
    public AppearanceConfig getAppearance() { return appearance; }
    public ConstructionConfig getConstruction() { return construction; }
}
```

- [ ] **Step 5: 编译验证**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
```
Expected: 编译通过（旧代码引用会导致错误，暂时注释掉 StructureConstructionManager 和 ManualRoadPlannerService 中的旧引用）

- [ ] **Step 6: 提交**

```bash
git add road/
git commit -m "feat(road): add config system and data models for new road system"
```

---

## Task 3: 地形缓存系统

**Files:**
- Create: `road/pathfinding/cache/TerrainSamplingCache.java`
- Create: `road/pathfinding/cache/FastHeightSampler.java`
- Create: `road/pathfinding/cache/AccurateHeightSampler.java`

- [ ] **Step 1: 创建 FastHeightSampler**

`road/pathfinding/cache/FastHeightSampler.java`:
```java
package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

public class FastHeightSampler {
    private final ServerLevel level;

    public FastHeightSampler(ServerLevel level) {
        this.level = level;
    }

    public int surfaceHeight(int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    public int motionBlockingHeight(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }
}
```

- [ ] **Step 2: 创建 AccurateHeightSampler**

`road/pathfinding/cache/AccurateHeightSampler.java`:
```java
package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

public class AccurateHeightSampler {
    private final ServerLevel level;

    public AccurateHeightSampler(ServerLevel level) {
        this.level = level;
    }

    public int surfaceHeight(int x, int z) {
        for (int y = level.getMaxBuildHeight(); y >= level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && !state.getFluidState().is(Fluids.WATER)
                    && !state.getFluidState().is(Fluids.FLOWING_WATER)) {
                return y;
            }
        }
        return level.getMinBuildHeight();
    }

    public int oceanFloor(int x, int z) {
        for (int y = level.getMaxBuildHeight(); y >= level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && !state.getFluidState().isSource()) {
                return y;
            }
        }
        return level.getMinBuildHeight();
    }
}
```

- [ ] **Step 3: 创建 TerrainSamplingCache**

`road/pathfinding/cache/TerrainSamplingCache.java`:
```java
package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.monpai.sailboatmod.road.config.PathfindingConfig;

import java.util.concurrent.ConcurrentHashMap;

public class TerrainSamplingCache {
    private final ServerLevel level;
    private final FastHeightSampler fastSampler;
    private final AccurateHeightSampler accurateSampler;
    private final PathfindingConfig.SamplingPrecision precision;

    private final ConcurrentHashMap<Long, Integer> heightCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> waterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> oceanFloorCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Holder<Biome>> biomeCache = new ConcurrentHashMap<>();

    public TerrainSamplingCache(ServerLevel level, PathfindingConfig.SamplingPrecision precision) {
        this.level = level;
        this.fastSampler = new FastHeightSampler(level);
        this.accurateSampler = new AccurateHeightSampler(level);
        this.precision = precision;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public int getHeight(int x, int z) {
        return heightCache.computeIfAbsent(key(x, z), k -> {
            if (precision == PathfindingConfig.SamplingPrecision.NORMAL) {
                return fastSampler.surfaceHeight(x, z);
            }
            return accurateSampler.surfaceHeight(x, z);
        });
    }

    public boolean isWater(int x, int z) {
        return waterCache.computeIfAbsent(key(x, z), k -> {
            int surfaceY = getHeight(x, z);
            BlockState above = level.getBlockState(new BlockPos(x, surfaceY + 1, z));
            return above.is(Blocks.WATER);
        });
    }

    public int getOceanFloor(int x, int z) {
        return oceanFloorCache.computeIfAbsent(key(x, z), k -> accurateSampler.oceanFloor(x, z));
    }

    public Holder<Biome> getBiome(int x, int z) {
        return biomeCache.computeIfAbsent(key(x, z), k ->
            level.getBiome(new BlockPos(x, getHeight(x, z), z))
        );
    }

    public boolean isWaterBiome(int x, int z) {
        Holder<Biome> biome = getBiome(x, z);
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER);
    }

    public boolean isNearWater(int x, int z) {
        return isWater(x - 1, z) || isWater(x + 1, z)
            || isWater(x, z - 1) || isWater(x, z + 1);
    }

    public double terrainStability(int x, int z) {
        int center = getHeight(x, z);
        int n = getHeight(x, z - 1);
        int s = getHeight(x, z + 1);
        int e = getHeight(x + 1, z);
        int w = getHeight(x - 1, z);
        double mean = (n + s + e + w) / 4.0;
        double variance = ((n - mean) * (n - mean) + (s - mean) * (s - mean)
                + (e - mean) * (e - mean) + (w - mean) * (w - mean)) / 4.0;
        return Math.sqrt(variance);
    }

    public int getWaterDepth(int x, int z) {
        if (!isWater(x, z)) return 0;
        int surface = getHeight(x, z);
        int floor = getOceanFloor(x, z);
        return surface - floor;
    }

    public ServerLevel getLevel() { return level; }

    public void clear() {
        heightCache.clear();
        waterCache.clear();
        oceanFloorCache.clear();
        biomeCache.clear();
    }
}
```

- [ ] **Step 4: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/pathfinding/cache/
git commit -m "feat(road): add terrain sampling cache with dual-mode height sampling"
```

---

## Task 4: 寻路策略接口 + 代价模型 + 工厂

**Files:**
- Create: `road/pathfinding/Pathfinder.java`, `road/pathfinding/PathResult.java`, `road/pathfinding/PathfinderFactory.java`, `road/pathfinding/cost/TerrainCostModel.java`

- [ ] **Step 1: 创建 PathResult**

`road/pathfinding/PathResult.java`:
```java
package com.monpai.sailboatmod.road.pathfinding;

import com.monpai.sailboatmod.road.model.RoadSegment;
import net.minecraft.core.BlockPos;
import java.util.List;

public record PathResult(List<BlockPos> path, boolean success, String failureReason) {
    public static PathResult success(List<BlockPos> path) {
        return new PathResult(path, true, null);
    }
    public static PathResult failure(String reason) {
        return new PathResult(List.of(), false, reason);
    }
}
```

- [ ] **Step 2: 创建 Pathfinder 策略接口**

`road/pathfinding/Pathfinder.java`:
```java
package com.monpai.sailboatmod.road.pathfinding;

import net.minecraft.core.BlockPos;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;

public interface Pathfinder {
    PathResult findPath(BlockPos start, BlockPos end, TerrainSamplingCache cache);
}
```

- [ ] **Step 3: 创建 TerrainCostModel**

`road/pathfinding/cost/TerrainCostModel.java`:
```java
package com.monpai.sailboatmod.road.pathfinding.cost;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

public class TerrainCostModel {
    private static final double ORTHO_STEP = 1.0;
    private static final double DIAG_STEP = 1.414;
    private static final double WATER_BIOME_COST = 12.0;
    private static final double WATER_COLUMN_BASE_PENALTY = 20.0;
    private static final double WATER_DEPTH_SQUARED_WEIGHT = 0.5;

    private final PathfindingConfig config;

    public TerrainCostModel(PathfindingConfig config) {
        this.config = config;
    }

    public double moveCost(int fromX, int fromZ, int toX, int toZ, TerrainSamplingCache cache) {
        boolean diagonal = (fromX != toX) && (fromZ != toZ);
        double stepCost = diagonal ? DIAG_STEP : ORTHO_STEP;

        int fromH = cache.getHeight(fromX, fromZ);
        int toH = cache.getHeight(toX, toZ);
        double elevationCost = config.getElevationWeight() * Math.abs(toH - fromH);

        double biomeCost = 0;
        if (cache.isWaterBiome(toX, toZ)) {
            biomeCost = config.getBiomeWeight() * WATER_BIOME_COST;
        }

        double stabilityCost = config.getStabilityWeight() * cache.terrainStability(toX, toZ);

        double waterCost = 0;
        if (cache.isWater(toX, toZ)) {
            int depth = cache.getWaterDepth(toX, toZ);
            waterCost = WATER_COLUMN_BASE_PENALTY + (depth * depth) * config.getWaterDepthWeight() * WATER_DEPTH_SQUARED_WEIGHT;
        }

        double nearWaterCost = cache.isNearWater(toX, toZ) ? config.getNearWaterCost() : 0;

        return stepCost + elevationCost + biomeCost + stabilityCost + waterCost + nearWaterCost;
    }

    public double heuristic(int x, int z, int goalX, int goalZ) {
        int dx = Math.abs(x - goalX);
        int dz = Math.abs(z - goalZ);
        return config.getHeuristicWeight() * (dx + dz);
    }

    public double deviationCost(int x, int z, BlockPos start, BlockPos end) {
        double lineX = end.getX() - start.getX();
        double lineZ = end.getZ() - start.getZ();
        double len = Math.sqrt(lineX * lineX + lineZ * lineZ);
        if (len < 1e-6) return 0;
        double dx = x - start.getX();
        double dz = z - start.getZ();
        double perpDist = Math.abs(dx * lineZ - dz * lineX) / len;
        return config.getDeviationWeight() * perpDist;
    }
}
```

- [ ] **Step 4: 创建 PathfinderFactory**

`road/pathfinding/PathfinderFactory.java`:
```java
package com.monpai.sailboatmod.road.pathfinding;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.impl.*;

public final class PathfinderFactory {
    private PathfinderFactory() {}

    public static Pathfinder create(PathfindingConfig config) {
        return switch (config.getAlgorithm()) {
            case BASIC_ASTAR -> new BasicAStarPathfinder(config);
            case BIDIRECTIONAL_ASTAR -> new BidirectionalAStarPathfinder(config);
            case GRADIENT_DESCENT -> new GradientDescentPathfinder(config);
            case POTENTIAL_FIELD -> new PotentialFieldPathfinder(config);
        };
    }
}
```

- [ ] **Step 5: 编译验证并提交**

注意：此时 impl/ 下的4个算法类尚未创建，PathfinderFactory 会编译失败。先创建空桩文件让编译通过，后续 Task 5 填充实现。

为每个算法创建桩文件（仅含类声明和构造函数），例如：
```java
package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

public class BasicAStarPathfinder implements Pathfinder {
    private final PathfindingConfig config;
    public BasicAStarPathfinder(PathfindingConfig config) { this.config = config; }

    @Override
    public PathResult findPath(BlockPos start, BlockPos end, TerrainSamplingCache cache) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
```

对 `BidirectionalAStarPathfinder`、`GradientDescentPathfinder`、`PotentialFieldPathfinder` 重复相同桩结构。

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/pathfinding/
git commit -m "feat(road): add pathfinder strategy interface, cost model, and factory with stubs"
```

---

## Task 5: 四种寻路算法实现

**Files:**
- Modify: `road/pathfinding/impl/BasicAStarPathfinder.java`
- Modify: `road/pathfinding/impl/BidirectionalAStarPathfinder.java`
- Modify: `road/pathfinding/impl/GradientDescentPathfinder.java`
- Modify: `road/pathfinding/impl/PotentialFieldPathfinder.java`

参考: `Ref/RoadWeaver-1.20.1-Architectury/common/src/main/java/net/shiroha233/roadweaver/pathfinding/impl/`

- [ ] **Step 1: 实现 BasicAStarPathfinder**

`road/pathfinding/impl/BasicAStarPathfinder.java` — 替换桩实现:
```java
package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.cost.TerrainCostModel;
import net.minecraft.core.BlockPos;

import java.util.*;

public class BasicAStarPathfinder implements Pathfinder {
    private static final int[][] DIRECTIONS = {
        {1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}
    };

    private final PathfindingConfig config;
    private final TerrainCostModel costModel;

    public BasicAStarPathfinder(PathfindingConfig config) {
        this.config = config;
        this.costModel = new TerrainCostModel(config);
    }

    @Override
    public PathResult findPath(BlockPos start, BlockPos end, TerrainSamplingCache cache) {
        int step = config.getAStarStep();
        int maxSteps = config.getMaxSteps();

        int sx = start.getX(), sz = start.getZ();
        int ex = end.getX(), ez = end.getZ();

        record Node(int x, int z, double g, double f, Node parent) {}
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<Long, Double> bestG = new HashMap<>();

        open.add(new Node(sx, sz, 0, costModel.heuristic(sx, sz, ex, ez), null));
        bestG.put(key(sx, sz), 0.0);

        int visited = 0;
        while (!open.isEmpty() && visited < maxSteps) {
            Node current = open.poll();
            visited++;

            if (Math.abs(current.x - ex) <= step && Math.abs(current.z - ez) <= step) {
                return PathResult.success(reconstructPath(current, cache));
            }

            for (int[] dir : DIRECTIONS) {
                int nx = current.x + dir[0] * step;
                int nz = current.z + dir[1] * step;
                double moveCost = costModel.moveCost(current.x, current.z, nx, nz, cache) * step;
                double devCost = costModel.deviationCost(nx, nz, start, end);
                double ng = current.g + moveCost + devCost;
                long nk = key(nx, nz);

                if (ng < bestG.getOrDefault(nk, Double.MAX_VALUE)) {
                    bestG.put(nk, ng);
                    double nf = ng + costModel.heuristic(nx, nz, ex, ez);
                    open.add(new Node(nx, nz, ng, nf, current));
                }
            }
        }
        return PathResult.failure("A* exceeded max steps: " + maxSteps);
    }

    private List<BlockPos> reconstructPath(Object nodeObj, TerrainSamplingCache cache) {
        record Node(int x, int z, double g, double f, Node parent) {}
        LinkedList<BlockPos> path = new LinkedList<>();
        @SuppressWarnings("unchecked")
        var node = nodeObj;
        while (node != null) {
            var n = (BasicAStarPathfinder.NodeAccessor) node;
            int y = cache.getHeight(n.x(), n.z());
            path.addFirst(new BlockPos(n.x(), y, n.z()));
            node = n.parent();
        }
        return path;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
```

注意：上面的 `reconstructPath` 使用了内部 record 的引用。实际实现时，将 `Node` record 提取为类内部的 private static record，并直接在 `reconstructPath` 中使用类型化参数：

```java
private static record Node(int x, int z, double g, double f, Node parent) {}

private List<BlockPos> reconstructPath(Node node, TerrainSamplingCache cache) {
    LinkedList<BlockPos> path = new LinkedList<>();
    while (node != null) {
        int y = cache.getHeight(node.x, node.z);
        path.addFirst(new BlockPos(node.x, y, node.z));
        node = node.parent;
    }
    return path;
}
```

- [ ] **Step 2: 实现 BidirectionalAStarPathfinder**

`road/pathfinding/impl/BidirectionalAStarPathfinder.java`:

参考 RoadWeaver 的 `BidirectionalAStarPathfinder`。核心区别：从起点和终点同时扩展，当两个搜索前沿相遇时合并路径。使用相同的 `TerrainCostModel` 和 `Node` record。

关键逻辑：
- 维护两个 open 集合 (forwardOpen, backwardOpen) 和两个 bestG map
- 每轮交替扩展 forward 和 backward
- 当某节点同时出现在两个 bestG 中时，合并路径
- 合并时 forward 路径正序 + backward 路径反序

- [ ] **Step 3: 实现 GradientDescentPathfinder**

`road/pathfinding/impl/GradientDescentPathfinder.java`:

参考 RoadWeaver 的 `GradientDescentPathfinder`。核心区别：高度变化使用平方惩罚 `elevation * elevation * weight`，更激进地避免爬坡。

关键逻辑：
- 与 BasicAStar 结构类似，但代价计算不同
- 高度代价: `(heightDiff * heightDiff) * elevationWeight`
- 坡度软/硬阈值惩罚
- 水深代价: `WATER_COLUMN_BASE_PENALTY + (depth²) * weight * WATER_DEPTH_SQUARED_WEIGHT`
- 步数预算乘数更高 (maxSteps * 1.5)

- [ ] **Step 4: 实现 PotentialFieldPathfinder（默认算法）**

`road/pathfinding/impl/PotentialFieldPathfinder.java`:

参考 RoadWeaver 的 `PotentialFieldPathfinder`。核心：势场法 = 目标引力 + 地形梯度排斥。

关键逻辑：
- 计算地形梯度: `gradX = height(x+1,z) - height(x-1,z)`, `gradZ = height(x,z+1) - height(x,z-1)`
- 计算目标引力方向: `goalDirX = goalX - x`, `goalDirZ = goalZ - z` (归一化)
- 合成方向: `dirX = goalDirX - terrainGradWeight * gradX`
- 沿合成方向选择最佳邻居
- 步数预算乘数更高 (maxSteps * 2.0)
- 路径最平滑，适合通用场景

- [ ] **Step 5: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/pathfinding/impl/
git commit -m "feat(road): implement 4 pathfinding algorithms (A*, bidirectional, gradient, potential field)"
```

---

## Task 6: 路径后处理

**Files:**
- Create: `road/pathfinding/post/SplineHelper.java`
- Create: `road/pathfinding/post/PathPostProcessor.java`

参考: `Ref/RoadWeaver-1.20.1-Architectury/.../pathfinding/impl/PathPostProcessor.java` 和 `SplineHelper.java`

- [ ] **Step 1: 创建 SplineHelper**

`road/pathfinding/post/SplineHelper.java`:
```java
package com.monpai.sailboatmod.road.pathfinding.post;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

public final class SplineHelper {
    private SplineHelper() {}

    public static List<BlockPos> catmullRom(List<BlockPos> controlPoints, int segmentsPerSpan) {
        if (controlPoints.size() < 4) return new ArrayList<>(controlPoints);
        List<BlockPos> result = new ArrayList<>();
        result.add(controlPoints.get(0));

        for (int i = 0; i < controlPoints.size() - 3; i++) {
            BlockPos p0 = controlPoints.get(i);
            BlockPos p1 = controlPoints.get(i + 1);
            BlockPos p2 = controlPoints.get(i + 2);
            BlockPos p3 = controlPoints.get(i + 3);

            for (int j = 1; j <= segmentsPerSpan; j++) {
                double t = (double) j / segmentsPerSpan;
                double t2 = t * t;
                double t3 = t2 * t;

                double x = 0.5 * ((2 * p1.getX())
                    + (-p0.getX() + p2.getX()) * t
                    + (2 * p0.getX() - 5 * p1.getX() + 4 * p2.getX() - p3.getX()) * t2
                    + (-p0.getX() + 3 * p1.getX() - 3 * p2.getX() + p3.getX()) * t3);

                double z = 0.5 * ((2 * p1.getZ())
                    + (-p0.getZ() + p2.getZ()) * t
                    + (2 * p0.getZ() - 5 * p1.getZ() + 4 * p2.getZ() - p3.getZ()) * t2
                    + (-p0.getZ() + 3 * p1.getZ() - 3 * p2.getZ() + p3.getZ()) * t3);

                double y = 0.5 * ((2 * p1.getY())
                    + (-p0.getY() + p2.getY()) * t
                    + (2 * p0.getY() - 5 * p1.getY() + 4 * p2.getY() - p3.getY()) * t2
                    + (-p0.getY() + 3 * p1.getY() - 3 * p2.getY() + p3.getY()) * t3);

                result.add(new BlockPos((int) Math.round(x), (int) Math.round(y), (int) Math.round(z)));
            }
        }
        return result;
    }
}
```

- [ ] **Step 2: 创建 PathPostProcessor**

`road/pathfinding/post/PathPostProcessor.java`:
```java
package com.monpai.sailboatmod.road.pathfinding.post;

import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class PathPostProcessor {
    private static final int SPLINE_SEGMENTS_PER_SPAN = 4;
    private static final double RELAXATION_WEIGHT = 0.3;
    private static final int RELAXATION_PASSES = 3;

    public record ProcessedPath(List<BlockPos> path, List<BridgeSpan> bridgeSpans) {}

    public ProcessedPath process(List<BlockPos> rawPath, TerrainSamplingCache cache, int bridgeMinWaterDepth) {
        List<BlockPos> simplified = simplify(rawPath);
        List<BridgeSpan> bridges = detectBridges(simplified, cache, bridgeMinWaterDepth);
        List<BlockPos> straightened = straightenBridges(simplified, bridges);
        List<BlockPos> relaxed = relax(straightened, bridges);
        List<BlockPos> splined = SplineHelper.catmullRom(relaxed, SPLINE_SEGMENTS_PER_SPAN);
        List<BlockPos> rasterized = rasterize(splined);
        List<BridgeSpan> finalBridges = detectBridges(rasterized, cache, bridgeMinWaterDepth);
        return new ProcessedPath(rasterized, finalBridges);
    }

    private List<BlockPos> simplify(List<BlockPos> path) {
        if (path.size() < 3) return new ArrayList<>(path);
        List<BlockPos> result = new ArrayList<>();
        result.add(path.get(0));
        for (int i = 1; i < path.size() - 1; i++) {
            BlockPos prev = path.get(i - 1);
            BlockPos curr = path.get(i);
            BlockPos next = path.get(i + 1);
            int dx1 = curr.getX() - prev.getX(), dz1 = curr.getZ() - prev.getZ();
            int dx2 = next.getX() - curr.getX(), dz2 = next.getZ() - curr.getZ();
            if (dx1 != dx2 || dz1 != dz2) {
                result.add(curr);
            }
        }
        result.add(path.get(path.size() - 1));
        return result;
    }

    private List<BridgeSpan> detectBridges(List<BlockPos> path, TerrainSamplingCache cache, int minDepth) {
        List<BridgeSpan> spans = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            boolean water = cache.isWater(p.getX(), p.getZ())
                    && cache.getWaterDepth(p.getX(), p.getZ()) >= minDepth;
            if (water && start == -1) {
                start = i;
            } else if (!water && start != -1) {
                int surfaceY = cache.getHeight(path.get(start).getX(), path.get(start).getZ());
                int floorY = cache.getOceanFloor(path.get(start).getX(), path.get(start).getZ());
                spans.add(new BridgeSpan(start, i - 1, surfaceY, floorY));
                start = -1;
            }
        }
        if (start != -1) {
            BlockPos p = path.get(start);
            spans.add(new BridgeSpan(start, path.size() - 1,
                cache.getHeight(p.getX(), p.getZ()),
                cache.getOceanFloor(p.getX(), p.getZ())));
        }
        return spans;
    }

    private List<BlockPos> straightenBridges(List<BlockPos> path, List<BridgeSpan> bridges) {
        List<BlockPos> result = new ArrayList<>(path);
        for (BridgeSpan span : bridges) {
            BlockPos entry = path.get(span.startIndex());
            BlockPos exit = path.get(span.endIndex());
            int len = span.length();
            if (len <= 1) continue;
            for (int i = 1; i < len; i++) {
                double t = (double) i / len;
                int x = (int) Math.round(entry.getX() + (exit.getX() - entry.getX()) * t);
                int z = (int) Math.round(entry.getZ() + (exit.getZ() - entry.getZ()) * t);
                int y = (int) Math.round(entry.getY() + (exit.getY() - entry.getY()) * t);
                result.set(span.startIndex() + i, new BlockPos(x, y, z));
            }
        }
        return result;
    }

    private boolean isInBridge(int index, List<BridgeSpan> bridges) {
        for (BridgeSpan span : bridges) {
            if (index >= span.startIndex() && index <= span.endIndex()) return true;
        }
        return false;
    }

    private List<BlockPos> relax(List<BlockPos> path, List<BridgeSpan> bridges) {
        List<BlockPos> result = new ArrayList<>(path);
        for (int pass = 0; pass < RELAXATION_PASSES; pass++) {
            List<BlockPos> next = new ArrayList<>(result);
            for (int i = 1; i < result.size() - 1; i++) {
                if (isInBridge(i, bridges)) continue;
                BlockPos prev = result.get(i - 1);
                BlockPos curr = result.get(i);
                BlockPos nxt = result.get(i + 1);
                int x = (int) Math.round(curr.getX() * (1 - RELAXATION_WEIGHT)
                    + (prev.getX() + nxt.getX()) / 2.0 * RELAXATION_WEIGHT);
                int z = (int) Math.round(curr.getZ() * (1 - RELAXATION_WEIGHT)
                    + (prev.getZ() + nxt.getZ()) / 2.0 * RELAXATION_WEIGHT);
                next.set(i, new BlockPos(x, curr.getY(), z));
            }
            result = next;
        }
        return result;
    }

    private List<BlockPos> rasterize(List<BlockPos> path) {
        if (path.size() < 2) return new ArrayList<>(path);
        List<BlockPos> result = new ArrayList<>();
        result.add(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            BlockPos from = path.get(i - 1);
            BlockPos to = path.get(i);
            List<BlockPos> line = bresenham(from, to);
            for (int j = 1; j < line.size(); j++) {
                result.add(line.get(j));
            }
        }
        return result;
    }

    private List<BlockPos> bresenham(BlockPos from, BlockPos to) {
        List<BlockPos> points = new ArrayList<>();
        int x0 = from.getX(), z0 = from.getZ();
        int x1 = to.getX(), z1 = to.getZ();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        while (true) {
            double t = (dx + dz == 0) ? 0 : Math.sqrt((x0 - from.getX()) * (x0 - from.getX())
                + (z0 - from.getZ()) * (z0 - from.getZ()))
                / Math.sqrt((to.getX() - from.getX()) * (to.getX() - from.getX())
                + (to.getZ() - from.getZ()) * (to.getZ() - from.getZ()) + 0.001);
            int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            points.add(new BlockPos(x0, y, z0));
            if (x0 == x1 && z0 == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x0 += sx; }
            if (e2 < dx) { err += dx; z0 += sz; }
        }
        return points;
    }
}
```

- [ ] **Step 3: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/pathfinding/post/
git commit -m "feat(road): add path post-processor with simplification, bridge detection, spline smoothing"
```

---

## Task 7: 网络拓扑规划

**Files:**
- Create: `road/planning/NetworkPlanner.java`, `road/planning/NetworkPlannerFactory.java`
- Create: `road/planning/impl/DelaunayPlanner.java`, `road/planning/impl/MSTPlanner.java`, `road/planning/impl/KNNPlanner.java`

参考: `Ref/RoadWeaver-1.20.1-Architectury/.../planning/`

- [ ] **Step 1: 创建 NetworkPlanner 接口和工厂**

`road/planning/NetworkPlanner.java`:
```java
package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.model.StructureConnection;
import net.minecraft.core.BlockPos;
import java.util.List;

public interface NetworkPlanner {
    List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks);
}
```

`road/planning/NetworkPlannerFactory.java`:
```java
package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.planning.impl.*;

public final class NetworkPlannerFactory {
    public enum PlanningAlgorithm { DELAUNAY, MST, KNN }

    private NetworkPlannerFactory() {}

    public static NetworkPlanner create(PlanningAlgorithm algorithm) {
        return switch (algorithm) {
            case DELAUNAY -> new DelaunayPlanner();
            case MST -> new MSTPlanner();
            case KNN -> new KNNPlanner();
        };
    }
}
```

- [ ] **Step 2: 实现 MSTPlanner（Kruskal最小生成树）**

`road/planning/impl/MSTPlanner.java`:
```java
package com.monpai.sailboatmod.road.planning.impl;

import com.monpai.sailboatmod.road.model.ConnectionStatus;
import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.planning.NetworkPlanner;
import net.minecraft.core.BlockPos;

import java.util.*;

public class MSTPlanner implements NetworkPlanner {
    @Override
    public List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks) {
        if (points.size() < 2) return List.of();

        record Edge(int from, int to, double dist) {}
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                double dist = Math.sqrt(points.get(i).distSqr(points.get(j)));
                if (dist <= maxEdgeLenBlocks) {
                    edges.add(new Edge(i, j, dist));
                }
            }
        }
        edges.sort(Comparator.comparingDouble(Edge::dist));

        int[] parent = new int[points.size()];
        int[] rank = new int[points.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;

        List<StructureConnection> result = new ArrayList<>();
        for (Edge edge : edges) {
            int rootA = find(parent, edge.from);
            int rootB = find(parent, edge.to);
            if (rootA != rootB) {
                union(parent, rank, rootA, rootB);
                result.add(new StructureConnection(points.get(edge.from), points.get(edge.to), ConnectionStatus.PLANNED));
            }
        }
        return result;
    }

    private int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private void union(int[] parent, int[] rank, int a, int b) {
        if (rank[a] < rank[b]) { parent[a] = b; }
        else if (rank[a] > rank[b]) { parent[b] = a; }
        else { parent[b] = a; rank[a]++; }
    }
}
```

- [ ] **Step 3: 实现 DelaunayPlanner**

`road/planning/impl/DelaunayPlanner.java`:

参考 RoadWeaver 的 `DelaunayPlanner`。核心逻辑：
```java
package com.monpai.sailboatmod.road.planning.impl;

import com.monpai.sailboatmod.road.model.ConnectionStatus;
import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.planning.NetworkPlanner;
import net.minecraft.core.BlockPos;

import java.util.*;

public class DelaunayPlanner implements NetworkPlanner {
    @Override
    public List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks) {
        if (points.size() < 2) return List.of();
        if (points.size() == 2) {
            double dist = Math.sqrt(points.get(0).distSqr(points.get(1)));
            if (dist <= maxEdgeLenBlocks) {
                return List.of(new StructureConnection(points.get(0), points.get(1), ConnectionStatus.PLANNED));
            }
            return List.of();
        }

        // 2D Delaunay using Bowyer-Watson on XZ plane
        double[] xs = new double[points.size()];
        double[] zs = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            xs[i] = points.get(i).getX();
            zs[i] = points.get(i).getZ();
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]);
        }
        double dx = maxX - minX + 1, dz = maxZ - minZ + 1;
        double midX = (minX + maxX) / 2, midZ = (minZ + maxZ) / 2;
        double superSize = Math.max(dx, dz) * 10;

        int n = points.size();
        // super-triangle vertices: n, n+1, n+2
        double[] allX = Arrays.copyOf(xs, n + 3);
        double[] allZ = Arrays.copyOf(zs, n + 3);
        allX[n] = midX - superSize; allZ[n] = midZ - superSize;
        allX[n+1] = midX + superSize; allZ[n+1] = midZ - superSize;
        allX[n+2] = midX; allZ[n+2] = midZ + superSize;

        record Tri(int a, int b, int c) {}
        List<Tri> triangles = new ArrayList<>();
        triangles.add(new Tri(n, n+1, n+2));

        for (int i = 0; i < n; i++) {
            List<Tri> bad = new ArrayList<>();
            for (Tri tri : triangles) {
                if (inCircumcircle(allX, allZ, tri.a, tri.b, tri.c, i)) {
                    bad.add(tri);
                }
            }
            Set<Long> boundary = new LinkedHashSet<>();
            for (Tri tri : bad) {
                int[][] edges = {{tri.a, tri.b}, {tri.b, tri.c}, {tri.c, tri.a}};
                for (int[] edge : edges) {
                    long ek = edgeKey(edge[0], edge[1]);
                    long ek2 = edgeKey(edge[1], edge[0]);
                    boolean shared = false;
                    for (Tri other : bad) {
                        if (other == tri) continue;
                        int[][] oe = {{other.a, other.b}, {other.b, other.c}, {other.c, other.a}};
                        for (int[] o : oe) {
                            if (edgeKey(o[0], o[1]) == ek || edgeKey(o[0], o[1]) == ek2) {
                                shared = true; break;
                            }
                        }
                        if (shared) break;
                    }
                    if (!shared) boundary.add(((long)edge[0] << 32) | (edge[1] & 0xFFFFFFFFL));
                }
            }
            triangles.removeAll(bad);
            for (long bk : boundary) {
                int ea = (int)(bk >> 32);
                int eb = (int)(bk & 0xFFFFFFFFL);
                triangles.add(new Tri(ea, eb, i));
            }
        }

        // Extract edges, filter super-triangle vertices and max distance
        Set<Long> edgeSet = new HashSet<>();
        List<StructureConnection> result = new ArrayList<>();
        for (Tri tri : triangles) {
            if (tri.a >= n || tri.b >= n || tri.c >= n) continue;
            int[][] edges = {{tri.a, tri.b}, {tri.b, tri.c}, {tri.c, tri.a}};
            for (int[] edge : edges) {
                int a = Math.min(edge[0], edge[1]);
                int b = Math.max(edge[0], edge[1]);
                long ek = ((long)a << 32) | b;
                if (edgeSet.add(ek)) {
                    double dist = Math.sqrt(points.get(a).distSqr(points.get(b)));
                    if (dist <= maxEdgeLenBlocks) {
                        result.add(new StructureConnection(points.get(a), points.get(b), ConnectionStatus.PLANNED));
                    }
                }
            }
        }
        return result;
    }

    private boolean inCircumcircle(double[] xs, double[] zs, int a, int b, int c, int p) {
        double ax = xs[a] - xs[p], az = zs[a] - zs[p];
        double bx = xs[b] - xs[p], bz = zs[b] - zs[p];
        double cx = xs[c] - xs[p], cz = zs[c] - zs[p];
        double det = ax * (bz * (cx*cx + cz*cz) - cz * (bx*bx + bz*bz))
                   - bx * (az * (cx*cx + cz*cz) - cz * (ax*ax + az*az))
                   + cx * (az * (bx*bx + bz*bz) - bz * (ax*ax + az*az));
        return det > 0;
    }

    private long edgeKey(int a, int b) {
        return ((long)a << 32) | (b & 0xFFFFFFFFL);
    }
}
```

- [ ] **Step 4: 实现 KNNPlanner**

`road/planning/impl/KNNPlanner.java`:
```java
package com.monpai.sailboatmod.road.planning.impl;

import com.monpai.sailboatmod.road.model.ConnectionStatus;
import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.planning.NetworkPlanner;
import net.minecraft.core.BlockPos;

import java.util.*;

public class KNNPlanner implements NetworkPlanner {
    private static final int K = 3;

    @Override
    public List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks) {
        if (points.size() < 2) return List.of();

        Set<Long> edgeSet = new HashSet<>();
        List<StructureConnection> result = new ArrayList<>();

        for (int i = 0; i < points.size(); i++) {
            record Neighbor(int index, double dist) {}
            List<Neighbor> neighbors = new ArrayList<>();
            for (int j = 0; j < points.size(); j++) {
                if (i == j) continue;
                double dist = Math.sqrt(points.get(i).distSqr(points.get(j)));
                if (dist <= maxEdgeLenBlocks) {
                    neighbors.add(new Neighbor(j, dist));
                }
            }
            neighbors.sort(Comparator.comparingDouble(Neighbor::dist));

            int count = Math.min(K, neighbors.size());
            for (int k = 0; k < count; k++) {
                int a = Math.min(i, neighbors.get(k).index);
                int b = Math.max(i, neighbors.get(k).index);
                long ek = ((long) a << 32) | b;
                if (edgeSet.add(ek)) {
                    result.add(new StructureConnection(points.get(a), points.get(b), ConnectionStatus.PLANNED));
                }
            }
        }
        return result;
    }
}
```

- [ ] **Step 5: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/planning/
git commit -m "feat(road): add network topology planners (Delaunay, MST, KNN) with factory"
```

---

## Task 8: 异步生成服务

**Files:**
- Create: `road/generation/GenerationStatus.java`, `road/generation/ThreadPoolManager.java`, `road/generation/RoadGenerationTask.java`, `road/generation/RoadGenerationService.java`

参考: `Ref/RoadWeaver-1.20.1-Architectury/.../generation/RoadGenerationService.java` 和 `.../runtime/ThreadPoolManager.java`

- [ ] **Step 1: 创建 GenerationStatus 和 ThreadPoolManager**

`road/generation/GenerationStatus.java`:
```java
package com.monpai.sailboatmod.road.generation;

public enum GenerationStatus {
    PLANNED, GENERATING, COMPLETED, FAILED
}
```

`road/generation/ThreadPoolManager.java`:
```java
package com.monpai.sailboatmod.road.generation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ThreadPoolManager {
    private ExecutorService executor;
    private int poolSize;

    public ThreadPoolManager(int poolSize) {
        this.poolSize = poolSize;
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "RoadGen-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public ExecutorService getExecutor() { return executor; }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void restart(int newPoolSize) {
        shutdown();
        this.poolSize = newPoolSize;
        this.executor = Executors.newFixedThreadPool(newPoolSize, r -> {
            Thread t = new Thread(r, "RoadGen-Worker");
            t.setDaemon(true);
            return t;
        });
    }
}
```

- [ ] **Step 2: 创建 RoadGenerationTask**

`road/generation/RoadGenerationTask.java`:
```java
package com.monpai.sailboatmod.road.generation;

import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import net.minecraft.core.BlockPos;

import java.util.concurrent.CompletableFuture;

public class RoadGenerationTask {
    private final String taskId;
    private final StructureConnection connection;
    private volatile GenerationStatus status;
    private volatile PathResult result;
    private volatile CompletableFuture<PathResult> future;

    public RoadGenerationTask(String taskId, StructureConnection connection) {
        this.taskId = taskId;
        this.connection = connection;
        this.status = GenerationStatus.PLANNED;
    }

    public String getTaskId() { return taskId; }
    public StructureConnection getConnection() { return connection; }
    public GenerationStatus getStatus() { return status; }
    public void setStatus(GenerationStatus status) { this.status = status; }
    public PathResult getResult() { return result; }
    public void setResult(PathResult result) { this.result = result; }
    public CompletableFuture<PathResult> getFuture() { return future; }
    public void setFuture(CompletableFuture<PathResult> future) { this.future = future; }
}
```

- [ ] **Step 3: 创建 RoadGenerationService**

`road/generation/RoadGenerationService.java`:
```java
package com.monpai.sailboatmod.road.generation;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.post.PathPostProcessor;
import net.minecraft.server.level.ServerLevel;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RoadGenerationService {
    private final RoadConfig config;
    private final ThreadPoolManager threadPool;
    private final Queue<RoadGenerationTask> pendingQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, RoadGenerationTask> activeTasks = new LinkedHashMap<>();
    private final List<RoadGenerationTask> completedTasks = new ArrayList<>();
    private int taskCounter = 0;

    public RoadGenerationService(RoadConfig config) {
        this.config = config;
        this.threadPool = new ThreadPoolManager(config.getPathfinding().getThreadPoolSize());
    }

    public String submit(StructureConnection connection) {
        String taskId = "road-gen-" + (taskCounter++);
        RoadGenerationTask task = new RoadGenerationTask(taskId, connection);
        pendingQueue.add(task);
        return taskId;
    }

    public void tick(ServerLevel level) {
        // Launch pending tasks
        while (!pendingQueue.isEmpty()) {
            RoadGenerationTask task = pendingQueue.poll();
            if (task == null) break;
            task.setStatus(GenerationStatus.GENERATING);
            activeTasks.put(task.getTaskId(), task);

            Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
            TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());

            CompletableFuture<PathResult> future = CompletableFuture.supplyAsync(() ->
                pathfinder.findPath(
                    task.getConnection().from(),
                    task.getConnection().to(),
                    cache
                ), threadPool.getExecutor()
            );
            task.setFuture(future);
        }

        // Check completed tasks
        Iterator<Map.Entry<String, RoadGenerationTask>> it = activeTasks.entrySet().iterator();
        while (it.hasNext()) {
            RoadGenerationTask task = it.next().getValue();
            CompletableFuture<PathResult> future = task.getFuture();
            if (future != null && future.isDone()) {
                try {
                    PathResult result = future.get();
                    task.setResult(result);
                    task.setStatus(result.success() ? GenerationStatus.COMPLETED : GenerationStatus.FAILED);
                } catch (Exception e) {
                    task.setResult(PathResult.failure(e.getMessage()));
                    task.setStatus(GenerationStatus.FAILED);
                }
                completedTasks.add(task);
                it.remove();
            }
        }
    }

    public List<RoadGenerationTask> pollCompleted() {
        List<RoadGenerationTask> result = new ArrayList<>(completedTasks);
        completedTasks.clear();
        return result;
    }

    public Optional<RoadGenerationTask> getTask(String taskId) {
        RoadGenerationTask task = activeTasks.get(taskId);
        if (task != null) return Optional.of(task);
        return completedTasks.stream().filter(t -> t.getTaskId().equals(taskId)).findFirst();
    }

    public void cancelTask(String taskId) {
        RoadGenerationTask task = activeTasks.remove(taskId);
        if (task != null && task.getFuture() != null) {
            task.getFuture().cancel(true);
            task.setStatus(GenerationStatus.FAILED);
        }
    }

    public void shutdown() {
        threadPool.shutdown();
    }
}
```

- [ ] **Step 4: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/generation/
git commit -m "feat(road): add async road generation service with thread pool and task queue"
```

---

## Task 9: 生物群系材料选择 + 道路铺设

**Files:**
- Create: `road/construction/road/BiomeMaterialSelector.java`
- Create: `road/construction/road/RoadSegmentPaver.java`

- [ ] **Step 1: 创建 BiomeMaterialSelector**

`road/construction/road/BiomeMaterialSelector.java`:
```java
package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

public class BiomeMaterialSelector {

    public RoadMaterial select(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            return RoadMaterial.RED_SANDSTONE;
        }
        if (biome.is(Biomes.DESERT)) {
            return RoadMaterial.SANDSTONE;
        }
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) {
            return RoadMaterial.MOSSY_COBBLE;
        }
        if (biome.is(BiomeTags.IS_TAIGA) || biome.is(BiomeTags.IS_FOREST)) {
            return RoadMaterial.DIRT_PATH;
        }
        if (biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_TAIGA)
                || biome.is(Biomes.FROZEN_RIVER) || biome.is(Biomes.ICE_SPIKES)) {
            return RoadMaterial.COBBLESTONE;
        }
        return RoadMaterial.STONE_BRICK;
    }
}
```

- [ ] **Step 2: 创建 RoadSegmentPaver**

`road/construction/road/RoadSegmentPaver.java`:
```java
package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.List;

public class RoadSegmentPaver {
    private static final int MAX_FOUNDATION_DEPTH = 3;

    private final BiomeMaterialSelector materialSelector;

    public RoadSegmentPaver(BiomeMaterialSelector materialSelector) {
        this.materialSelector = materialSelector;
    }

    public List<BuildStep> pave(List<BlockPos> centerPath, int width, TerrainSamplingCache cache) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = 0;

        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos center = centerPath.get(i);
            RoadMaterial material = materialSelector.select(cache.getBiome(center.getX(), center.getZ()));

            Direction roadDir = getDirection(centerPath, i);
            Direction perpDir = roadDir.getClockWise();

            int prevY = (i > 0) ? centerPath.get(i - 1).getY() : center.getY();
            int nextY = (i < centerPath.size() - 1) ? centerPath.get(i + 1).getY() : center.getY();
            int heightDiff = center.getY() - prevY;

            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = center.relative(perpDir, w);

                // Foundation fill
                for (int d = 1; d <= MAX_FOUNDATION_DEPTH; d++) {
                    BlockPos below = pos.below(d);
                    steps.add(new BuildStep(order++, below, Blocks.COBBLESTONE.defaultBlockState(), BuildPhase.FOUNDATION));
                }

                // Surface
                if (heightDiff > 0) {
                    // Uphill: use stairs
                    BlockState stairState = material.stair().defaultBlockState()
                        .setValue(StairBlock.FACING, roadDir)
                        .setValue(StairBlock.HALF, Half.BOTTOM);
                    steps.add(new BuildStep(order++, pos, stairState, BuildPhase.SURFACE));
                } else if (heightDiff < 0) {
                    // Downhill: use stairs facing opposite
                    BlockState stairState = material.stair().defaultBlockState()
                        .setValue(StairBlock.FACING, roadDir.getOpposite())
                        .setValue(StairBlock.HALF, Half.BOTTOM);
                    steps.add(new BuildStep(order++, pos, stairState, BuildPhase.SURFACE));
                } else {
                    // Flat: use surface block
                    steps.add(new BuildStep(order++, pos, material.surface().defaultBlockState(), BuildPhase.SURFACE));
                }

                // Edge slabs for width 5 and 7
                if (width >= 5 && (w == -halfWidth || w == halfWidth)) {
                    BlockState slabState = material.slab().defaultBlockState()
                        .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
                    steps.add(new BuildStep(order++, pos, slabState, BuildPhase.SURFACE));
                }
            }

            // Railings for width 7
            if (width >= 7) {
                BlockPos leftRail = center.relative(perpDir, -(halfWidth + 1));
                BlockPos rightRail = center.relative(perpDir, halfWidth + 1);
                if (i % 4 == 0) {
                    steps.add(new BuildStep(order++, leftRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
                    steps.add(new BuildStep(order++, rightRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
                }
            }
        }
        return steps;
    }

    private Direction getDirection(List<BlockPos> path, int index) {
        BlockPos curr = path.get(index);
        BlockPos next = (index < path.size() - 1) ? path.get(index + 1) : path.get(index);
        BlockPos prev = (index > 0) ? path.get(index - 1) : curr;
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
```

- [ ] **Step 3: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/construction/road/
git commit -m "feat(road): add biome material selector and road segment paver with width support"
```

---

## Task 10: 桥梁系统 — 检测 + 桥墩 + 桥面

**Files:**
- Create: `road/construction/bridge/BridgeRangeDetector.java`
- Create: `road/construction/bridge/BridgePierBuilder.java`
- Create: `road/construction/bridge/BridgeDeckPlacer.java`
- Create: `road/construction/bridge/BridgePlatformBuilder.java`

- [ ] **Step 1: 创建 BridgeRangeDetector**

`road/construction/bridge/BridgeRangeDetector.java`:
```java
package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class BridgeRangeDetector {
    private final BridgeConfig config;

    public BridgeRangeDetector(BridgeConfig config) {
        this.config = config;
    }

    public List<BridgeSpan> detect(List<BlockPos> centerPath, TerrainSamplingCache cache) {
        List<BridgeSpan> raw = new ArrayList<>();
        int start = -1;
        int waterSurfaceY = 0;
        int oceanFloorY = 0;

        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos p = centerPath.get(i);
            boolean isWater = cache.isWater(p.getX(), p.getZ())
                    && cache.getWaterDepth(p.getX(), p.getZ()) >= config.getBridgeMinWaterDepth();

            if (isWater && start == -1) {
                start = i;
                waterSurfaceY = cache.getHeight(p.getX(), p.getZ());
                oceanFloorY = cache.getOceanFloor(p.getX(), p.getZ());
            } else if (!isWater && start != -1) {
                raw.add(new BridgeSpan(start, i - 1, waterSurfaceY, oceanFloorY));
                start = -1;
            }
        }
        if (start != -1) {
            raw.add(new BridgeSpan(start, centerPath.size() - 1, waterSurfaceY, oceanFloorY));
        }

        return mergeSpans(raw);
    }

    private List<BridgeSpan> mergeSpans(List<BridgeSpan> spans) {
        if (spans.size() < 2) return spans;
        List<BridgeSpan> merged = new ArrayList<>();
        BridgeSpan current = spans.get(0);

        for (int i = 1; i < spans.size(); i++) {
            BridgeSpan next = spans.get(i);
            if (next.startIndex() - current.endIndex() <= config.getMergeGap()) {
                current = new BridgeSpan(
                    current.startIndex(), next.endIndex(),
                    Math.max(current.waterSurfaceY(), next.waterSurfaceY()),
                    Math.min(current.oceanFloorY(), next.oceanFloorY())
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }
}
```

- [ ] **Step 2: 创建 BridgePierBuilder（保留现有核心逻辑）**

`road/construction/bridge/BridgePierBuilder.java`:
```java
package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class BridgePierBuilder {
    private final BridgeConfig config;

    public BridgePierBuilder(BridgeConfig config) {
        this.config = config;
    }

    public record PierNode(BlockPos foundationPos, int waterSurfaceY, int deckY) {}

    public List<PierNode> planPierNodes(List<BlockPos> bridgePath, int deckY, int oceanFloorY) {
        List<PierNode> nodes = new ArrayList<>();
        int interval = config.getPierInterval();
        for (int i = 0; i < bridgePath.size(); i += interval) {
            BlockPos pos = bridgePath.get(i);
            BlockPos foundation = new BlockPos(pos.getX(), oceanFloorY, pos.getZ());
            nodes.add(new PierNode(foundation, pos.getY(), deckY));
        }
        // Always include last position
        if (!bridgePath.isEmpty()) {
            BlockPos last = bridgePath.get(bridgePath.size() - 1);
            BlockPos lastNode = nodes.isEmpty() ? null : nodes.get(nodes.size() - 1).foundationPos;
            if (lastNode == null || !lastNode.equals(new BlockPos(last.getX(), oceanFloorY, last.getZ()))) {
                nodes.add(new PierNode(new BlockPos(last.getX(), oceanFloorY, last.getZ()),
                    last.getY(), deckY));
            }
        }
        return nodes;
    }

    public List<BuildStep> buildPiers(List<PierNode> pierNodes, int order) {
        List<BuildStep> steps = new ArrayList<>();
        for (PierNode node : pierNodes) {
            int fromY = node.foundationPos.getY();
            int toY = node.deckY;
            for (int y = fromY; y <= toY; y++) {
                BlockPos pos = new BlockPos(node.foundationPos.getX(), y, node.foundationPos.getZ());
                steps.add(new BuildStep(order++, pos, Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.PIER));
            }
        }
        return steps;
    }
}
```

- [ ] **Step 3: 创建 BridgeDeckPlacer**

`road/construction/bridge/BridgeDeckPlacer.java`:
```java
package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class BridgeDeckPlacer {

    public List<BuildStep> placeDeck(List<BlockPos> bridgePath, int deckY, int width,
                                      RoadMaterial material, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = startOrder;

        for (int i = 0; i < bridgePath.size(); i++) {
            BlockPos center = bridgePath.get(i);
            Direction roadDir = getDirection(bridgePath, i);
            Direction perpDir = roadDir.getClockWise();

            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = new BlockPos(
                    center.getX() + perpDir.getStepX() * w,
                    deckY,
                    center.getZ() + perpDir.getStepZ() * w
                );
                steps.add(new BuildStep(order++, pos,
                    material.surface().defaultBlockState(), BuildPhase.DECK));
            }

            // Railings on both sides
            BlockPos leftRail = new BlockPos(
                center.getX() + perpDir.getStepX() * -(halfWidth + 1),
                deckY + 1,
                center.getZ() + perpDir.getStepZ() * -(halfWidth + 1)
            );
            BlockPos rightRail = new BlockPos(
                center.getX() + perpDir.getStepX() * (halfWidth + 1),
                deckY + 1,
                center.getZ() + perpDir.getStepZ() * (halfWidth + 1)
            );
            steps.add(new BuildStep(order++, leftRail,
                material.fence().defaultBlockState(), BuildPhase.RAILING));
            steps.add(new BuildStep(order++, rightRail,
                material.fence().defaultBlockState(), BuildPhase.RAILING));
        }
        return steps;
    }

    private Direction getDirection(List<BlockPos> path, int index) {
        BlockPos curr = path.get(index);
        BlockPos next = (index < path.size() - 1) ? path.get(index + 1) : curr;
        BlockPos prev = (index > 0) ? path.get(index - 1) : curr;
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
```

- [ ] **Step 4: 创建 BridgePlatformBuilder**

`road/construction/bridge/BridgePlatformBuilder.java`:
```java
package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class BridgePlatformBuilder {
    private final BridgeConfig config;

    public BridgePlatformBuilder(BridgeConfig config) {
        this.config = config;
    }

    public List<BuildStep> buildPlatform(BlockPos anchorCenter, Direction roadDir,
                                          int width, RoadMaterial material, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int length = config.getPlatformLength();
        int order = startOrder;

        Direction perpDir = roadDir.getClockWise();

        for (int l = 0; l < length; l++) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = anchorCenter
                    .relative(roadDir, l)
                    .relative(perpDir, w);
                steps.add(new BuildStep(order++, pos,
                    material.surface().defaultBlockState(), BuildPhase.SURFACE));
            }
        }
        return steps;
    }
}
```

- [ ] **Step 5: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/construction/bridge/BridgeRangeDetector.java road/construction/bridge/BridgePierBuilder.java road/construction/bridge/BridgeDeckPlacer.java road/construction/bridge/BridgePlatformBuilder.java
git commit -m "feat(road): add bridge detection, pier builder, deck placer, and platform builder"
```

---

## Task 11: 桥梁坡道（半砖+台阶）

**Files:**
- Create: `road/construction/bridge/BridgeRampBuilder.java`

- [ ] **Step 1: 创建 BridgeRampBuilder**

`road/construction/bridge/BridgeRampBuilder.java`:
```java
package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.List;

public class BridgeRampBuilder {

    /**
     * Builds an ascending ramp from platformY to deckY.
     *
     * Pattern per height unit:
     *   [stair] -> [slab top] -> [slab bottom]
     * Each stair raises 1 block, slabs provide smooth 0.5+0.5 transition.
     *
     * @param startPos    ramp start position (at platform level)
     * @param roadDir     direction the road travels (ramp ascends in this direction)
     * @param platformY   Y level of the platform
     * @param deckY       Y level of the bridge deck
     * @param width       road width
     * @param material    road material set
     * @param startOrder  starting build order index
     * @return list of build steps for the ramp
     */
    public List<BuildStep> buildAscendingRamp(BlockPos startPos, Direction roadDir,
                                               int platformY, int deckY, int width,
                                               RoadMaterial material, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        Direction perpDir = roadDir.getClockWise();
        int order = startOrder;
        int heightDiff = deckY - platformY;
        int currentY = platformY;
        int stepIndex = 0;

        for (int h = 0; h < heightDiff; h++) {
            // Phase 1: Stair block (raises 1 full block)
            BlockPos stairCenter = startPos.relative(roadDir, stepIndex).atY(currentY);
            BlockState stairState = material.stair().defaultBlockState()
                .setValue(StairBlock.FACING, roadDir)
                .setValue(StairBlock.HALF, Half.BOTTOM);

            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = stairCenter.relative(perpDir, w);
                steps.add(new BuildStep(order++, pos, stairState, BuildPhase.RAMP));
                // Railing on edges
                if (w == -halfWidth || w == halfWidth) {
                    steps.add(new BuildStep(order++, pos.above(),
                        material.fence().defaultBlockState(), BuildPhase.RAILING));
                }
            }
            currentY++;
            stepIndex++;

            // Phase 2: Slab top (if not at deck yet)
            if (h < heightDiff - 1) {
                BlockPos slabTopCenter = startPos.relative(roadDir, stepIndex).atY(currentY - 1);
                BlockState slabTop = material.slab().defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP);

                for (int w = -halfWidth; w <= halfWidth; w++) {
                    BlockPos pos = slabTopCenter.relative(perpDir, w);
                    steps.add(new BuildStep(order++, pos, slabTop, BuildPhase.RAMP));
                }
                stepIndex++;

                // Phase 3: Slab bottom
                BlockPos slabBotCenter = startPos.relative(roadDir, stepIndex).atY(currentY);
                BlockState slabBot = material.slab().defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM);

                for (int w = -halfWidth; w <= halfWidth; w++) {
                    BlockPos pos = slabBotCenter.relative(perpDir, w);
                    steps.add(new BuildStep(order++, pos, slabBot, BuildPhase.RAMP));
                }
                stepIndex++;
            }
        }
        return steps;
    }

    /**
     * Builds a descending ramp from deckY down to platformY.
     * Mirror of ascending: reverses stair facing and slab order.
     */
    public List<BuildStep> buildDescendingRamp(BlockPos startPos, Direction roadDir,
                                                int deckY, int platformY, int width,
                                                RoadMaterial material, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        Direction perpDir = roadDir.getClockWise();
        int order = startOrder;
        int heightDiff = deckY - platformY;
        int currentY = deckY;
        int stepIndex = 0;

        for (int h = 0; h < heightDiff; h++) {
            // Phase 1: Slab bottom (start of descent)
            if (h > 0) {
                BlockPos slabBotCenter = startPos.relative(roadDir, stepIndex).atY(currentY);
                BlockState slabBot = material.slab().defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM);

                for (int w = -halfWidth; w <= halfWidth; w++) {
                    BlockPos pos = slabBotCenter.relative(perpDir, w);
                    steps.add(new BuildStep(order++, pos, slabBot, BuildPhase.RAMP));
                }
                stepIndex++;

                // Phase 2: Slab top
                BlockPos slabTopCenter = startPos.relative(roadDir, stepIndex).atY(currentY - 1);
                BlockState slabTop = material.slab().defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP);

                for (int w = -halfWidth; w <= halfWidth; w++) {
                    BlockPos pos = slabTopCenter.relative(perpDir, w);
                    steps.add(new BuildStep(order++, pos, slabTop, BuildPhase.RAMP));
                }
                stepIndex++;
            }

            // Phase 3: Stair block (descends 1 full block)
            currentY--;
            BlockPos stairCenter = startPos.relative(roadDir, stepIndex).atY(currentY);
            BlockState stairState = material.stair().defaultBlockState()
                .setValue(StairBlock.FACING, roadDir.getOpposite())
                .setValue(StairBlock.HALF, Half.BOTTOM);

            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = stairCenter.relative(perpDir, w);
                steps.add(new BuildStep(order++, pos, stairState, BuildPhase.RAMP));
                if (w == -halfWidth || w == halfWidth) {
                    steps.add(new BuildStep(order++, pos.above(),
                        material.fence().defaultBlockState(), BuildPhase.RAILING));
                }
            }
            stepIndex++;
        }
        return steps;
    }
}
```

- [ ] **Step 2: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/construction/bridge/BridgeRampBuilder.java
git commit -m "feat(road): add bridge ramp builder with stair+slab ascending/descending pattern"
```

---

## Task 12: 路灯系统（桥上 + 陆路）

**Files:**
- Create: `road/construction/bridge/BridgeLightPlacer.java`
- Create: `road/construction/road/StreetlightPlacer.java`

- [ ] **Step 1: 创建悬臂式路灯通用逻辑**

两个路灯类共享相同的悬臂式结构：栅栏柱3格 + 横向1格栅栏 + 悬挂灯笼。

`road/construction/bridge/BridgeLightPlacer.java`:
```java
package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;

import java.util.ArrayList;
import java.util.List;

public class BridgeLightPlacer {
    private final BridgeConfig config;

    public BridgeLightPlacer(BridgeConfig config) {
        this.config = config;
    }

    public List<BuildStep> placeLights(List<BlockPos> bridgePath, int deckY, int width,
                                        RoadMaterial material, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = startOrder;
        boolean leftSide = true;

        for (int i = 0; i < bridgePath.size(); i += config.getLightInterval()) {
            BlockPos center = bridgePath.get(i);
            Direction roadDir = getDirection(bridgePath, i);
            Direction perpDir = roadDir.getClockWise();

            int side = leftSide ? -(halfWidth + 1) : (halfWidth + 1);
            Direction armDir = leftSide ? perpDir.getOpposite() : perpDir;

            BlockPos base = new BlockPos(
                center.getX() + perpDir.getStepX() * side,
                deckY + 1,
                center.getZ() + perpDir.getStepZ() * side
            );

            // Fence post: 3 blocks high
            for (int h = 0; h < 3; h++) {
                steps.add(new BuildStep(order++, base.above(h),
                    material.fence().defaultBlockState(), BuildPhase.STREETLIGHT));
            }

            // Horizontal arm: 1 fence extending inward over the road
            BlockPos armPos = base.above(2).relative(armDir.getOpposite());
            steps.add(new BuildStep(order++, armPos,
                material.fence().defaultBlockState(), BuildPhase.STREETLIGHT));

            // Hanging lantern below the arm
            BlockPos lanternPos = armPos.below();
            steps.add(new BuildStep(order++, lanternPos,
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true),
                BuildPhase.STREETLIGHT));

            leftSide = !leftSide;
        }
        return steps;
    }

    private Direction getDirection(List<BlockPos> path, int index) {
        BlockPos curr = path.get(index);
        BlockPos next = (index < path.size() - 1) ? path.get(index + 1) : curr;
        BlockPos prev = (index > 0) ? path.get(index - 1) : curr;
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
```

- [ ] **Step 2: 创建 StreetlightPlacer（陆路路灯）**

`road/construction/road/StreetlightPlacer.java`:
```java
package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.config.AppearanceConfig;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;

import java.util.ArrayList;
import java.util.List;

public class StreetlightPlacer {
    private final AppearanceConfig config;
    private final BiomeMaterialSelector materialSelector;

    public StreetlightPlacer(AppearanceConfig config, BiomeMaterialSelector materialSelector) {
        this.config = config;
        this.materialSelector = materialSelector;
    }

    public List<BuildStep> placeLights(List<BlockPos> centerPath, int width,
                                        List<BridgeSpan> bridgeSpans,
                                        TerrainSamplingCache cache, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = startOrder;
        boolean leftSide = true;

        for (int i = 0; i < centerPath.size(); i += config.getLandLightInterval()) {
            if (isInBridge(i, bridgeSpans)) continue;

            BlockPos center = centerPath.get(i);
            RoadMaterial material = materialSelector.select(cache.getBiome(center.getX(), center.getZ()));
            Direction roadDir = getDirection(centerPath, i);
            Direction perpDir = roadDir.getClockWise();

            int side = leftSide ? -(halfWidth + 1) : (halfWidth + 1);
            Direction armDir = leftSide ? perpDir.getOpposite() : perpDir;

            BlockPos base = center.relative(perpDir, side).above();

            // Fence post: 3 blocks high
            for (int h = 0; h < 3; h++) {
                steps.add(new BuildStep(order++, base.above(h),
                    material.fence().defaultBlockState(), BuildPhase.STREETLIGHT));
            }

            // Horizontal arm
            BlockPos armPos = base.above(2).relative(armDir.getOpposite());
            steps.add(new BuildStep(order++, armPos,
                material.fence().defaultBlockState(), BuildPhase.STREETLIGHT));

            // Hanging lantern
            BlockPos lanternPos = armPos.below();
            steps.add(new BuildStep(order++, lanternPos,
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true),
                BuildPhase.STREETLIGHT));

            leftSide = !leftSide;
        }
        return steps;
    }

    private boolean isInBridge(int index, List<BridgeSpan> spans) {
        for (BridgeSpan span : spans) {
            if (index >= span.startIndex() && index <= span.endIndex()) return true;
        }
        return false;
    }

    private Direction getDirection(List<BlockPos> path, int index) {
        BlockPos curr = path.get(index);
        BlockPos next = (index < path.size() - 1) ? path.get(index + 1) : curr;
        BlockPos prev = (index > 0) ? path.get(index - 1) : curr;
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
```

- [ ] **Step 3: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/construction/bridge/BridgeLightPlacer.java road/construction/road/StreetlightPlacer.java
git commit -m "feat(road): add cantilever streetlight placers for bridges and land roads"
```

---

## Task 13: 桥梁/道路建造编排器

**Files:**
- Create: `road/construction/bridge/BridgeBuilder.java`
- Create: `road/construction/road/RoadBuilder.java`

- [ ] **Step 1: 创建 BridgeBuilder（桥梁建造编排）**

`road/construction/bridge/BridgeBuilder.java`:
```java
package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class BridgeBuilder {
    private final BridgeConfig config;
    private final BridgePierBuilder pierBuilder;
    private final BridgeDeckPlacer deckPlacer;
    private final BridgeRampBuilder rampBuilder;
    private final BridgePlatformBuilder platformBuilder;
    private final BridgeLightPlacer lightPlacer;

    public BridgeBuilder(BridgeConfig config) {
        this.config = config;
        this.pierBuilder = new BridgePierBuilder(config);
        this.deckPlacer = new BridgeDeckPlacer();
        this.rampBuilder = new BridgeRampBuilder();
        this.platformBuilder = new BridgePlatformBuilder(config);
        this.lightPlacer = new BridgeLightPlacer(config);
    }

    public List<BuildStep> build(BridgeSpan span, List<BlockPos> centerPath,
                                  int width, RoadMaterial material, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;

        int deckY = span.waterSurfaceY() + config.getDeckHeight();
        List<BlockPos> bridgePath = centerPath.subList(span.startIndex(), span.endIndex() + 1);

        // 1. Entry platform
        BlockPos entryCenter = centerPath.get(span.startIndex());
        Direction entryDir = getDirection(centerPath, span.startIndex());
        int entryY = entryCenter.getY();
        List<BuildStep> entryPlatform = platformBuilder.buildPlatform(
            entryCenter.relative(entryDir.getOpposite(), config.getPlatformLength()),
            entryDir, width, material, order);
        steps.addAll(entryPlatform);
        order += entryPlatform.size();

        // 2. Ascending ramp (entry side)
        BlockPos rampStart = entryCenter;
        List<BuildStep> ascRamp = rampBuilder.buildAscendingRamp(
            rampStart, entryDir, entryY, deckY, width, material, order);
        steps.addAll(ascRamp);
        order += ascRamp.size();

        // 3. Piers
        List<BridgePierBuilder.PierNode> pierNodes = pierBuilder.planPierNodes(
            bridgePath, deckY, span.oceanFloorY());
        List<BuildStep> piers = pierBuilder.buildPiers(pierNodes, order);
        steps.addAll(piers);
        order += piers.size();

        // 4. Bridge deck
        List<BuildStep> deck = deckPlacer.placeDeck(bridgePath, deckY, width, material, order);
        steps.addAll(deck);
        order += deck.size();

        // 5. Exit platform
        BlockPos exitCenter = centerPath.get(span.endIndex());
        Direction exitDir = getDirection(centerPath, span.endIndex());
        int exitY = exitCenter.getY();
        List<BuildStep> exitPlatform = platformBuilder.buildPlatform(
            exitCenter.relative(exitDir, 1), exitDir, width, material, order);
        steps.addAll(exitPlatform);
        order += exitPlatform.size();

        // 6. Descending ramp (exit side)
        BlockPos descStart = exitCenter.relative(exitDir, 1).atY(deckY);
        List<BuildStep> descRamp = rampBuilder.buildDescendingRamp(
            descStart, exitDir, deckY, exitY, width, material, order);
        steps.addAll(descRamp);
        order += descRamp.size();

        // 7. Bridge lights
        List<BuildStep> lights = lightPlacer.placeLights(bridgePath, deckY, width, material, order);
        steps.addAll(lights);

        return steps;
    }

    private Direction getDirection(List<BlockPos> path, int index) {
        BlockPos curr = path.get(index);
        BlockPos next = (index < path.size() - 1) ? path.get(index + 1) : curr;
        BlockPos prev = (index > 0) ? path.get(index - 1) : curr;
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
```

- [ ] **Step 2: 创建 RoadBuilder（道路建造编排）**

`road/construction/road/RoadBuilder.java`:
```java
package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.bridge.BridgeBuilder;
import com.monpai.sailboatmod.road.construction.bridge.BridgeRangeDetector;
import com.monpai.sailboatmod.road.model.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class RoadBuilder {
    private final RoadConfig config;
    private final RoadSegmentPaver paver;
    private final StreetlightPlacer streetlightPlacer;
    private final BridgeRangeDetector bridgeDetector;
    private final BridgeBuilder bridgeBuilder;
    private final BiomeMaterialSelector materialSelector;

    public RoadBuilder(RoadConfig config) {
        this.config = config;
        this.materialSelector = new BiomeMaterialSelector();
        this.paver = new RoadSegmentPaver(materialSelector);
        this.streetlightPlacer = new StreetlightPlacer(config.getAppearance(), materialSelector);
        this.bridgeDetector = new BridgeRangeDetector(config.getBridge());
        this.bridgeBuilder = new BridgeBuilder(config.getBridge());
    }

    public RoadData buildRoad(String roadId, List<BlockPos> centerPath, int width,
                               TerrainSamplingCache cache) {
        List<BridgeSpan> bridgeSpans = bridgeDetector.detect(centerPath, cache);

        List<BuildStep> allSteps = new ArrayList<>();
        int order = 0;

        // 1. Pave land segments (skip bridge spans)
        List<BuildStep> landSteps = paver.pave(
            filterLandPath(centerPath, bridgeSpans), width, cache);
        for (BuildStep step : landSteps) {
            allSteps.add(new BuildStep(order++, step.pos(), step.state(), step.phase()));
        }

        // 2. Build bridges
        RoadMaterial defaultMaterial = materialSelector.select(
            cache.getBiome(centerPath.get(0).getX(), centerPath.get(0).getZ()));
        for (BridgeSpan span : bridgeSpans) {
            List<BuildStep> bridgeSteps = bridgeBuilder.build(
                span, centerPath, width, defaultMaterial, order);
            allSteps.addAll(bridgeSteps);
            order += bridgeSteps.size();
        }

        // 3. Land streetlights
        List<BuildStep> lights = streetlightPlacer.placeLights(
            centerPath, width, bridgeSpans, cache, order);
        allSteps.addAll(lights);

        return new RoadData(roadId, width, List.of(), bridgeSpans, defaultMaterial, allSteps, centerPath);
    }

    private List<BlockPos> filterLandPath(List<BlockPos> path, List<BridgeSpan> bridges) {
        List<BlockPos> land = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            boolean inBridge = false;
            for (BridgeSpan span : bridges) {
                if (i >= span.startIndex() && i <= span.endIndex()) {
                    inBridge = true;
                    break;
                }
            }
            if (!inBridge) land.add(path.get(i));
        }
        return land;
    }
}
```

- [ ] **Step 3: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/construction/bridge/BridgeBuilder.java road/construction/road/RoadBuilder.java
git commit -m "feat(road): add bridge and road builder orchestrators"
```

---

## Task 14: 施工执行系统（三模）

**Files:**
- Create: `road/construction/execution/ConstructionExecutor.java`
- Create: `road/construction/execution/ConstructionQueue.java`
- Create: `road/construction/execution/TickDrivenExecutor.java`
- Create: `road/construction/execution/NpcWorkerExecutor.java`
- Create: `road/construction/execution/HammerBoostExecutor.java`

- [ ] **Step 1: 创建 ConstructionExecutor 接口和 ConstructionQueue**

`road/construction/execution/ConstructionExecutor.java`:
```java
package com.monpai.sailboatmod.road.construction.execution;

import net.minecraft.server.level.ServerLevel;

public interface ConstructionExecutor {
    boolean tick(ConstructionQueue queue, ServerLevel level);
}
```

`road/construction/execution/ConstructionQueue.java`:
```java
package com.monpai.sailboatmod.road.construction.execution;

import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class ConstructionQueue {
    public enum State { RUNNING, PAUSED, COMPLETED, CANCELLED }

    private final String roadId;
    private final List<BuildStep> steps;
    private int currentIndex;
    private State state;
    private final List<RollbackEntry> rollbackEntries = new ArrayList<>();

    public record RollbackEntry(BlockPos pos, BlockState previousState) {}

    public ConstructionQueue(String roadId, List<BuildStep> steps) {
        this.roadId = roadId;
        this.steps = List.copyOf(steps);
        this.currentIndex = 0;
        this.state = State.RUNNING;
    }

    public boolean hasNext() {
        return currentIndex < steps.size() && state == State.RUNNING;
    }

    public BuildStep next() {
        if (!hasNext()) return null;
        return steps.get(currentIndex++);
    }

    public BuildStep peek() {
        if (!hasNext()) return null;
        return steps.get(currentIndex);
    }

    public void executeStep(BuildStep step, ServerLevel level) {
        BlockState prev = level.getBlockState(step.pos());
        rollbackEntries.add(new RollbackEntry(step.pos(), prev));
        level.setBlock(step.pos(), step.state(), 3);
    }

    public double progress() {
        if (steps.isEmpty()) return 1.0;
        return (double) currentIndex / steps.size();
    }

    public void pause() { if (state == State.RUNNING) state = State.PAUSED; }
    public void resume() { if (state == State.PAUSED) state = State.RUNNING; }
    public void cancel() { state = State.CANCELLED; }
    public void complete() { state = State.COMPLETED; }

    public void rollback(ServerLevel level) {
        for (int i = rollbackEntries.size() - 1; i >= 0; i--) {
            RollbackEntry entry = rollbackEntries.get(i);
            level.setBlock(entry.pos(), entry.previousState(), 3);
        }
    }

    public String getRoadId() { return roadId; }
    public State getState() { return state; }
    public int getTotalSteps() { return steps.size(); }
    public int getCompletedSteps() { return currentIndex; }
}
```

- [ ] **Step 2: 创建 TickDrivenExecutor（默认慢建）**

`road/construction/execution/TickDrivenExecutor.java`:
```java
package com.monpai.sailboatmod.road.construction.execution;

import com.monpai.sailboatmod.road.config.ConstructionConfig;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.server.level.ServerLevel;

public class TickDrivenExecutor implements ConstructionExecutor {
    private final ConstructionConfig config;
    private int tickCounter = 0;

    public TickDrivenExecutor(ConstructionConfig config) {
        this.config = config;
    }

    @Override
    public boolean tick(ConstructionQueue queue, ServerLevel level) {
        if (!queue.hasNext()) {
            queue.complete();
            return true;
        }
        tickCounter++;
        if (tickCounter >= config.getTickSlowRate()) {
            tickCounter = 0;
            BuildStep step = queue.next();
            if (step != null) {
                queue.executeStep(step, level);
            }
        }
        return !queue.hasNext();
    }
}
```

- [ ] **Step 3: 创建 NpcWorkerExecutor**

`road/construction/execution/NpcWorkerExecutor.java`:
```java
package com.monpai.sailboatmod.road.construction.execution;

import com.monpai.sailboatmod.road.config.ConstructionConfig;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.server.level.ServerLevel;

public class NpcWorkerExecutor implements ConstructionExecutor {
    private final ConstructionConfig config;
    private int tickCounter = 0;

    public NpcWorkerExecutor(ConstructionConfig config) {
        this.config = config;
    }

    @Override
    public boolean tick(ConstructionQueue queue, ServerLevel level) {
        if (!queue.hasNext()) {
            queue.complete();
            return true;
        }
        tickCounter++;
        if (tickCounter >= config.getNpcRate()) {
            tickCounter = 0;
            BuildStep step = queue.next();
            if (step != null) {
                queue.executeStep(step, level);
            }
        }
        return !queue.hasNext();
    }
}
```

- [ ] **Step 4: 创建 HammerBoostExecutor**

`road/construction/execution/HammerBoostExecutor.java`:
```java
package com.monpai.sailboatmod.road.construction.execution;

import com.monpai.sailboatmod.road.config.ConstructionConfig;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.server.level.ServerLevel;

public class HammerBoostExecutor implements ConstructionExecutor {
    private final ConstructionConfig config;
    private int tickCounter = 0;

    public HammerBoostExecutor(ConstructionConfig config) {
        this.config = config;
    }

    @Override
    public boolean tick(ConstructionQueue queue, ServerLevel level) {
        if (!queue.hasNext()) {
            queue.complete();
            return true;
        }
        tickCounter++;
        if (tickCounter >= config.getHammerRate()) {
            tickCounter = 0;
            int batch = config.getHammerBatchSize();
            for (int i = 0; i < batch && queue.hasNext(); i++) {
                BuildStep step = queue.next();
                if (step != null) {
                    queue.executeStep(step, level);
                }
            }
        }
        return !queue.hasNext();
    }
}
```

- [ ] **Step 5: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/construction/execution/
git commit -m "feat(road): add construction execution system with tick/npc/hammer modes"
```

---

## Task 15: RoadPlanningService + RoadNetworkApi + 持久化

**Files:**
- Create: `road/planning/RoadPlanningService.java`
- Create: `road/api/RoadNetworkApi.java`
- Create: `road/persistence/RoadNetworkStorage.java`

- [ ] **Step 1: 创建 RoadPlanningService**

`road/planning/RoadPlanningService.java`:
```java
package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.construction.road.RoadBuilder;
import com.monpai.sailboatmod.road.generation.RoadGenerationService;
import com.monpai.sailboatmod.road.generation.RoadGenerationTask;
import com.monpai.sailboatmod.road.model.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.post.PathPostProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public class RoadPlanningService {
    private final RoadConfig config;
    private final RoadGenerationService generationService;
    private final RoadBuilder roadBuilder;
    private final Map<String, ConstructionQueue> activeConstructions = new LinkedHashMap<>();

    public RoadPlanningService(RoadConfig config) {
        this.config = config;
        this.generationService = new RoadGenerationService(config);
        this.roadBuilder = new RoadBuilder(config);
    }

    public String planRoad(StructureConnection connection) {
        return generationService.submit(connection);
    }

    public void tick(ServerLevel level) {
        generationService.tick(level);

        // Process completed pathfinding tasks
        for (RoadGenerationTask task : generationService.pollCompleted()) {
            if (task.getResult() != null && task.getResult().success()) {
                TerrainSamplingCache cache = new TerrainSamplingCache(level,
                    config.getPathfinding().getSamplingPrecision());
                PathPostProcessor postProcessor = new PathPostProcessor();
                PathPostProcessor.ProcessedPath processed = postProcessor.process(
                    task.getResult().path(), cache, config.getBridge().getBridgeMinWaterDepth());

                int width = config.getAppearance().getDefaultWidth();
                RoadData roadData = roadBuilder.buildRoad(
                    task.getTaskId(), processed.path(), width, cache);

                ConstructionQueue queue = new ConstructionQueue(task.getTaskId(), roadData.buildSteps());
                activeConstructions.put(task.getTaskId(), queue);
            }
        }

        // Tick active constructions (default: tick-driven)
        Iterator<Map.Entry<String, ConstructionQueue>> it = activeConstructions.entrySet().iterator();
        while (it.hasNext()) {
            ConstructionQueue queue = it.next().getValue();
            if (queue.getState() == ConstructionQueue.State.COMPLETED
                    || queue.getState() == ConstructionQueue.State.CANCELLED) {
                it.remove();
            }
        }
    }

    public Optional<ConstructionQueue> getConstruction(String roadId) {
        return Optional.ofNullable(activeConstructions.get(roadId));
    }

    public Map<String, ConstructionQueue> getActiveConstructions() {
        return Collections.unmodifiableMap(activeConstructions);
    }

    public void cancelRoad(String roadId, ServerLevel level) {
        ConstructionQueue queue = activeConstructions.get(roadId);
        if (queue != null) {
            queue.cancel();
            queue.rollback(level);
        }
        generationService.cancelTask(roadId);
    }

    public void shutdown() {
        generationService.shutdown();
    }
}
```

- [ ] **Step 2: 创建 RoadNetworkApi**

`road/api/RoadNetworkApi.java`:
```java
package com.monpai.sailboatmod.road.api;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.planning.NetworkPlannerFactory;
import com.monpai.sailboatmod.road.planning.NetworkPlanner;
import com.monpai.sailboatmod.road.planning.RoadPlanningService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RoadNetworkApi {
    private final RoadConfig config;
    private final RoadPlanningService planningService;

    public RoadNetworkApi(RoadConfig config) {
        this.config = config;
        this.planningService = new RoadPlanningService(config);
    }

    public List<StructureConnection> planNetwork(List<BlockPos> structurePositions,
                                                   int maxEdgeLength,
                                                   NetworkPlannerFactory.PlanningAlgorithm algorithm) {
        NetworkPlanner planner = NetworkPlannerFactory.create(algorithm);
        return planner.plan(structurePositions, maxEdgeLength);
    }

    public String buildRoad(StructureConnection connection) {
        return planningService.planRoad(connection);
    }

    public void tick(ServerLevel level) {
        planningService.tick(level);
    }

    public Optional<ConstructionQueue> getConstruction(String roadId) {
        return planningService.getConstruction(roadId);
    }

    public Map<String, ConstructionQueue> getActiveConstructions() {
        return planningService.getActiveConstructions();
    }

    public void cancelRoad(String roadId, ServerLevel level) {
        planningService.cancelRoad(roadId, level);
    }

    public void shutdown() {
        planningService.shutdown();
    }
}
```

- [ ] **Step 3: 创建 RoadNetworkStorage（持久化桩）**

`road/persistence/RoadNetworkStorage.java`:
```java
package com.monpai.sailboatmod.road.persistence;

import com.monpai.sailboatmod.road.model.RoadData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

public class RoadNetworkStorage {
    private final Map<String, RoadData> roads = new LinkedHashMap<>();

    public void save(RoadData road) {
        roads.put(road.roadId(), road);
    }

    public Optional<RoadData> get(String roadId) {
        return Optional.ofNullable(roads.get(roadId));
    }

    public void remove(String roadId) {
        roads.remove(roadId);
    }

    public Collection<RoadData> getAll() {
        return Collections.unmodifiableCollection(roads.values());
    }

    public CompoundTag serialize() {
        CompoundTag tag = new CompoundTag();
        ListTag roadList = new ListTag();
        for (RoadData road : roads.values()) {
            CompoundTag roadTag = new CompoundTag();
            roadTag.putString("roadId", road.roadId());
            roadTag.putInt("width", road.width());
            ListTag pathTag = new ListTag();
            for (BlockPos pos : road.centerPath()) {
                CompoundTag posTag = new CompoundTag();
                posTag.putInt("x", pos.getX());
                posTag.putInt("y", pos.getY());
                posTag.putInt("z", pos.getZ());
                pathTag.add(posTag);
            }
            roadTag.put("centerPath", pathTag);
            roadList.add(roadTag);
        }
        tag.put("roads", roadList);
        return tag;
    }

    public void deserialize(CompoundTag tag) {
        roads.clear();
        ListTag roadList = tag.getList("roads", Tag.TAG_COMPOUND);
        for (int i = 0; i < roadList.size(); i++) {
            CompoundTag roadTag = roadList.getCompound(i);
            String roadId = roadTag.getString("roadId");
            int width = roadTag.getInt("width");
            ListTag pathTag = roadTag.getList("centerPath", Tag.TAG_COMPOUND);
            List<BlockPos> path = new ArrayList<>();
            for (int j = 0; j < pathTag.size(); j++) {
                CompoundTag posTag = pathTag.getCompound(j);
                path.add(new BlockPos(posTag.getInt("x"), posTag.getInt("y"), posTag.getInt("z")));
            }
            roads.put(roadId, new RoadData(roadId, width, List.of(), List.of(), null, List.of(), path));
        }
    }
}
```

- [ ] **Step 4: 编译验证并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add road/planning/RoadPlanningService.java road/api/ road/persistence/
git commit -m "feat(road): add planning service, public API, and network storage"
```

---

## Task 16: 集成现有系统

**Files:**
- Modify: `nation/service/StructureConstructionManager.java`
- Modify: `nation/service/ManualRoadPlannerService.java`
- Modify: `nation/service/RoadLifecycleService.java`
- Modify: `item/RoadPlannerItem.java`

此任务将新道路系统接入现有模组框架。由于这些文件体量大（StructureConstructionManager 6000+行），修改策略是：替换内部调用，保留对外接口。

- [ ] **Step 1: 在 StructureConstructionManager 中注入 RoadNetworkApi**

在类中添加字段和初始化：
```java
// 在 StructureConstructionManager 类顶部添加
import com.monpai.sailboatmod.road.api.RoadNetworkApi;
import com.monpai.sailboatmod.road.config.RoadConfig;

// 在字段区域添加
private RoadNetworkApi roadNetworkApi;

// 在构造函数或初始化方法中添加
this.roadNetworkApi = new RoadNetworkApi(new RoadConfig());
```

- [ ] **Step 2: 替换 tickRoadConstructions() 内部逻辑**

找到 `tickRoadConstructions()` 方法（约第590行），将其内部逻辑替换为委托给新系统：
```java
private void tickRoadConstructions(ServerLevel level) {
    roadNetworkApi.tick(level);
}
```

保留方法签名和调用点不变，仅替换内部实现。

- [ ] **Step 3: 替换 scheduleManualRoad() 系列方法**

找到 `scheduleManualRoad()` 的多个重载（约第1495-1512行），替换为：
```java
public void scheduleManualRoad(ServerLevel level, BlockPos from, BlockPos to, int width) {
    var connection = new com.monpai.sailboatmod.road.model.StructureConnection(
        from, to, com.monpai.sailboatmod.road.model.ConnectionStatus.PLANNED);
    roadNetworkApi.buildRoad(connection);
}
```

- [ ] **Step 4: 更新 ManualRoadPlannerService 内部调用**

在 `ManualRoadPlannerService` 中：
- 将所有对旧 `RoadPathfinder`、`RoadBezierCenterline`、`RoadCorridorPlanner`、`RoadGeometryPlanner` 的调用替换为通过 `RoadNetworkApi` 的调用
- 保留 `handleSneakUse()`、`handlePrimaryUse()` 等对外接口
- 保留 `PlannerMode` 枚举和 `PreviewOptionKind` 枚举
- 核心变更：`buildPlanCandidates()` 方法内部改为调用新的寻路+后处理+道路建造管线

关键替换模式：
```java
// 旧代码（删除）:
// RoadPathfinder pathfinder = new RoadPathfinder(...);
// List<BlockPos> path = pathfinder.findPath(...);
// RoadBezierCenterline centerline = RoadBezierCenterline.build(path);
// RoadCorridorPlan corridor = RoadCorridorPlanner.plan(...);
// RoadGeometryPlan geometry = RoadGeometryPlanner.plan(...);

// 新代码:
Pathfinder pathfinder = PathfinderFactory.create(roadConfig.getPathfinding());
TerrainSamplingCache cache = new TerrainSamplingCache(level, roadConfig.getPathfinding().getSamplingPrecision());
PathResult result = pathfinder.findPath(from, to, cache);
if (result.success()) {
    PathPostProcessor postProcessor = new PathPostProcessor();
    PathPostProcessor.ProcessedPath processed = postProcessor.process(
        result.path(), cache, roadConfig.getBridge().getBridgeMinWaterDepth());
    RoadBuilder builder = new RoadBuilder(roadConfig);
    RoadData roadData = builder.buildRoad(roadId, processed.path(), width, cache);
    // 使用 roadData.buildSteps() 生成预览和施工队列
}
```

- [ ] **Step 5: 更新 RoadLifecycleService**

替换内部对旧类的引用，委托给 `RoadNetworkApi`：
```java
// 取消道路
public void cancelRoad(String roadId, ServerLevel level) {
    roadNetworkApi.cancelRoad(roadId, level);
}
```

- [ ] **Step 6: 清理编译错误**

运行编译，逐一修复所有对已删除类的引用：
```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | grep "error:" | head -50
```

对每个编译错误：
- 如果是对已删除类的 import → 删除 import，替换为新类
- 如果是对已删除方法的调用 → 替换为新 API 调用
- 如果是对已删除 record 的引用 → 替换为新 model 类

- [ ] **Step 7: 编译通过并提交**

```bash
cd sailboatmod && ./gradlew compileJava 2>&1 | tail -20
git add -A
git commit -m "feat(road): integrate new road system with existing StructureConstructionManager and ManualRoadPlannerService"
```

---

## Task 17: 最终验证与清理

- [ ] **Step 1: 全量编译验证**

```bash
cd sailboatmod && ./gradlew clean build 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 检查无残留旧引用**

```bash
cd sailboatmod/src/main/java && grep -r "RoadBezierCenterline\|RoadCorridorPlanner\|RoadGeometryPlanner\|LandRoadHybridPathfinder\|SegmentedRoadPathOrchestrator\|GroundRouteSkeletonPlanner" --include="*.java" | head -20
```

Expected: 无输出（所有旧引用已清除）

- [ ] **Step 3: 检查新包结构完整性**

```bash
find sailboatmod/src/main/java/com/monpai/sailboatmod/road -name "*.java" | sort
```

Expected: 列出所有新创建的约40个Java文件

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m "chore(road): final cleanup and verification of road system refactor"
```

---

## Self-Review Checklist

**1. Spec coverage:**
- [x] 寻路策略模式 (4算法+工厂) → Task 4-5
- [x] 地形缓存 (双模采样) → Task 3
- [x] 路径后处理 (简化/桥梁检测/平滑/样条) → Task 6
- [x] 网络拓扑规划 (Delaunay/MST/KNN) → Task 7
- [x] 异步生成服务 (线程池+tick驱动) → Task 8
- [x] 生物群系材料选择 → Task 9
- [x] 道路铺设 (宽度3/5/7) → Task 9
- [x] 桥梁检测 → Task 10
- [x] 桥墩建造 (保留核心逻辑) → Task 10
- [x] 桥面铺设 → Task 10
- [x] 桥头平台 → Task 10
- [x] 桥梁坡道 (半砖+台阶) → Task 11
- [x] 桥上路灯 (悬臂式) → Task 12
- [x] 陆路路灯 (悬臂式) → Task 12
- [x] 施工三模 (tick/NPC/锤子) → Task 14
- [x] 施工队列 (暂停/恢复/取消/回滚) → Task 14
- [x] 配置系统 (层级化) → Task 2
- [x] 数据模型 → Task 2
- [x] 公共API → Task 15
- [x] 持久化 → Task 15
- [x] 集成现有系统 → Task 16
- [x] 清理旧代码 → Task 1

**2. Placeholder scan:** 无 TBD/TODO。Task 5 Step 2-4 描述了算法关键逻辑而非完整代码——这是因为这些算法较复杂，实现时需参考 RoadWeaver 源码。已提供足够的关键逻辑描述和参考路径。

**3. Type consistency:**
- `PathResult` — 在 Task 4 定义，Task 5/8/15/16 中一致使用
- `TerrainSamplingCache` — Task 3 定义，全局一致
- `TerrainCostModel` — Task 4 定义，Task 5 中使用
- `BridgeSpan` — Task 2 定义，Task 6/10/12/13 中一致
- `BuildStep` / `BuildPhase` — Task 2 定义，全局一致
- `RoadMaterial` — Task 2 定义，Task 9/10/11/12/13 中一致
- `ConstructionQueue` — Task 14 定义，Task 15/16 中一致
- `StructureConnection` — Task 2 定义，Task 7/8/15/16 中一致
- `RoadData` — Task 2 定义，Task 13/15 中一致








