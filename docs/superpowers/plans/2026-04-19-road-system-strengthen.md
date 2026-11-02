# 道路系统补强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补完所有桩实现（4寻路算法、曲线工具、分段并行、清障隧道、桩类委托、UI重构），编译出可运行JAR。

**Architecture:** 分层实施——先补核心引擎（代价模型+算法），再补工具层（曲线/清障/并行），然后桥接旧系统，最后UI重构。每层编译验证后提交。

**Tech Stack:** Minecraft Forge 1.20.1, Java 17, CompletableFuture (parallel pathfinding), Forge RenderType (translucent preview)

**Base path:** `src/main/java/com/monpai/sailboatmod/road/`

---

## Task 1: 修正代价模型 + PathfindingConfig 默认值

**Files:**
- Modify: `road/config/PathfindingConfig.java`
- Modify: `road/config/AppearanceConfig.java`
- Modify: `road/pathfinding/cost/TerrainCostModel.java`

- [ ] **Step 1: 更新 PathfindingConfig 默认值和新字段**

替换 `road/config/PathfindingConfig.java` 全部内容:
```java
package com.monpai.sailboatmod.road.config;

public class PathfindingConfig {
    public enum Algorithm { BASIC_ASTAR, BIDIRECTIONAL_ASTAR, GRADIENT_DESCENT, POTENTIAL_FIELD }
    public enum SamplingPrecision { NORMAL, HIGH, ULTRA_HIGH }

    private Algorithm algorithm = Algorithm.POTENTIAL_FIELD;
    private int maxSteps = 20000;
    private SamplingPrecision samplingPrecision = SamplingPrecision.NORMAL;
    private double elevationWeight = 80.0;
    private double biomeWeight = 2.0;
    private double stabilityWeight = 15.0;
    private double waterDepthWeight = 80.0;
    private double nearWaterCost = 80.0;
    private double deviationWeight = 0.5;
    private double heuristicWeight = 15.0;
    private int aStarStep = 8;
    private int threadPoolSize = 2;
    private int segmentThreshold = 96;
    private int maxSegments = 8;

    public Algorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(Algorithm a) { this.algorithm = a; }
    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int v) { this.maxSteps = v; }
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
    public void setAStarStep(int s) { this.aStarStep = s; }
    public int getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(int s) { this.threadPoolSize = s; }
    public int getSegmentThreshold() { return segmentThreshold; }
    public void setSegmentThreshold(int t) { this.segmentThreshold = t; }
    public int getMaxSegments() { return maxSegments; }
    public void setMaxSegments(int m) { this.maxSegments = m; }
}
```

- [ ] **Step 2: 更新 AppearanceConfig 加入隧道配置**

替换 `road/config/AppearanceConfig.java` 全部内容:
```java
package com.monpai.sailboatmod.road.config;

public class AppearanceConfig {
    private int defaultWidth = 3;
    private int landLightInterval = 24;
    private boolean tunnelEnabled = false;
    private int roadClearHeight = 4;
    private int tunnelClearHeight = 5;
    private int tunnelLightInterval = 8;

    public int getDefaultWidth() { return defaultWidth; }
    public void setDefaultWidth(int w) { this.defaultWidth = w; }
    public int getLandLightInterval() { return landLightInterval; }
    public void setLandLightInterval(int i) { this.landLightInterval = i; }
    public boolean isTunnelEnabled() { return tunnelEnabled; }
    public void setTunnelEnabled(boolean e) { this.tunnelEnabled = e; }
    public int getRoadClearHeight() { return roadClearHeight; }
    public void setRoadClearHeight(int h) { this.roadClearHeight = h; }
    public int getTunnelClearHeight() { return tunnelClearHeight; }
    public void setTunnelClearHeight(int h) { this.tunnelClearHeight = h; }
    public int getTunnelLightInterval() { return tunnelLightInterval; }
    public void setTunnelLightInterval(int i) { this.tunnelLightInterval = i; }
}
```

<!-- PLACEHOLDER_STEP3 -->
