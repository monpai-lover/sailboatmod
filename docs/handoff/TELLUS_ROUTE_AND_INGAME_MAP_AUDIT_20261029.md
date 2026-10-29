# Tellus 超远规划与游戏内地图：只读审计

用户记忆有对应实现，但目前并未全面统一。

## 已实现

- `WaterAutoRouteService` 在 Tellus 世界使用真实已生成 NBT 优先、`getBaseColumn` 预测回退的水路规划。它不是网页预览的简化水陆配色。
- `SailboatEntity` 滚动加载船周围、当前／下一航点、目的港及前方走廊，并释放旧票据。`RouteLookaheadGuard` 的 Tellus 策略前看约 160 格，最多额外 40 区块，每 10 tick 校验，接近障碍约 48 格时停船等待局部重规划。
- 马车具备滚动区块加载。道路自动补全可查询未生成世界的 generator 高度，正式施工逐步确保真实区块。
- RoadPlanner、Town、Nation 地图已经共用 `RoadPlannerMapCanvas`、`RoadPlannerTileManager.sharedDefault()` 和服务端增量切片。RoadPlanner 界面每 tick 绘制少量客户端已加载区块，补充真实地图。
- 独立 `ClaimPreviewTerrainService` 支持已加载区块和异步 FULL 保存 NBT，避免为该通道生成区块。

## 尚未实现或仍有限制

- Tellus 预测底图目前只用于网页，尚未加入三个游戏内共同地图界面。Town/Nation 可见底图自动使用 FORCE_RENDER，而 `RoadPlannerMapPreloadService` 会加载／生成缺失区块，然后按真实方块采样；这与独立 ClaimPreview 的 NBT 通道不同。
- RoadPlanner 未就绪区是完全不透明棋盘格；直接在下面绘预测图不会显示。当前同步及缓存也没有完整预测／真实来源优先级。
- 水路搜索仍以起点为中心硬限制约 12,288 格，有节点／时间预算。Tellus 数据预取一次排队整条直线走廊，滚动的主要是实际世界窗口。60 秒只限制等待预取，不严格取消后续 generator 调用。
- 正式道路预览仍先加载／生成走廊；上限约 6,000 区块及累计 32,000 采样格，超限需分段。它尚未成为完整的 Tellus 预测→沿途才生成方案。
- 当前强加载票据首次可能同步触发 `ServerLevel.getChunk`；“滚动窗口”不等于冷区块生成完全无卡顿。

## 建议接入顺序

先在共同游戏内地图通道加入独立 Tellus 预测层，真实覆盖掩码仅表示真实数据；已生成未加载地形异步读 NBT，未生成地形保留预测，玩家经过后以真实切片替换。一次接入覆盖道路、城镇、国家界面。主模组复用 `TellusSurfaceSamplers`，不依赖可选 marketweb 附加包的 HTTP／Leaflet 类。

统一原生精度、像素中心、调色与阴影口径，再扩展超长规划为分段、分预算、带进度的任务。道路正式预览与实际施工分开，规划不应提前生成整条世界。

主要源码：`route/water/WaterAutoRouteService.java`、`RealBlockWaterMap.java`、`WaterRoutePolicy.java`、`entity/SailboatEntity.java`、`route/terrain/GeneratorColumnPredictor.java`、`network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java`、`roadplanner/service/RoadPlannerMapPreloadService.java`、`client/screen/ClaimWorldMapView.java`、`client/roadplanner/RoadPlannerTile.java`。共享地图历史见 `docs/superpowers/plans/2026-06-11-shared-map-budget.md`。

本次仅审计；这些新增流式地图及超长道路改动尚未实施。
