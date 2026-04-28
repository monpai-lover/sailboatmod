---
date: 2026-04-28
topic: road-planner-clearing-minimap-preview
---

# 道路规划器：障碍清理 + 小地图优化 + 预览验证

## 需求 1：道路建造清理四周障碍

**现状：** `RoadPlannerBuildControlService` 只生成 SURFACE/DECK 步骤，不清理障碍也不铺地基。老 `RoadSegmentPaver` 清理路面上方 4 格 + 铺 3 层地基。

**方案：** 在 `RoadPlannerBuildControlService.buildSteps()` 中，对每个路面方块额外生成：
- CLEAR_TO_SKY：路面上方 1~4 格设为 AIR
- FOUNDATION：路面下方 1~3 格设为 DIRT/COBBLESTONE
- 清理步骤排在路面步骤之前（phase ordering 已有）

修改文件：`RoadPlannerBuildControlService.java`

## 需求 2：小地图优化（参考 Recruits）

**Recruits 做法：** 增量合并——只更新已加载 chunk 的像素，保留 tile 中已有数据。我们的 `forceRenderChunk` 已经是增量的，但 `renderLoadedChunksInTile` 可以更高效。

**方案：**
- `RoadPlannerTileManager.forceRenderChunk` 去掉 `loadedFromCache` 门控，允许增量更新已缓存 tile
- 已完成（上一轮改动）

## 需求 3：验证 ghost block 预览路径结构

**方案：** 代码审查确认 `nodeAnchoredBridgeSteps` 和 `buildSteps` 生成的 BuildStep 列表正确映射到 `SyncRoadPlannerPreviewPacket.GhostBlock`。
