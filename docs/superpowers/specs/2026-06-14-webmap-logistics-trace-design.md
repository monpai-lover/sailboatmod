# webmap 物流轨迹显示（手动发车 + 实时跟走）

**日期**：2026-06-14
**状态**：设计已确认，待写实现计划
**适用范围**：sailboatmod 网页端地图（webmap）的物流轨迹层；马车（陆路）与帆船（水路）统一适用

---

## Context（背景）

网页端地图能显示运输轨迹，但有两个缺口：

1. **手动发车不显示**：`ShippingTraceService` 只追踪有 `ShippingOrder` 的运输——市场调度发车才创建 `ShippingOrder` + `ShippingTraceRecord`。玩家**手动点击启动 autopilot**的马车/帆船只设置载具的 `autopilotRoute` + autopilot 激活标志，**不创建 ShippingOrder/Trace**，因此 `ShippingTraceSavedData` 里没有记录，`/api/map/shipments` 返回空，地图上看不到。
2. **位置是估算不是实时**：现状 `ShipmentTrace` 只有 `points[]`（路线 waypoints）+ `completedPointCount` + `progressRatio`，**没有载具实时坐标**。地图图标停在"已完成路点"上，不在路点间平滑移动，更新还是每 5 秒一跳。

**目标**：手动发车的马车/船运也在地图上显示物流轨迹；载具图标按**实时坐标**平滑跟走；轨迹更新速度与视觉效果按下文设计。

---

## 现状事实（探查结论，实现以此为准）

- 轨迹数据源：`MarketWebMapLayerService.shipments(server, identity)`（:73）→ `ShippingTraceService.toDtos(ShippingTraceService.visibleFor(...))`。
- DTO：`MarketWebMapDtos.ShipmentTrace`（:44-56）字段：`shippingOrderId, label, transportMode, status, sourceName, targetName, points(List<Point{x,z}>), completedPointCount, progressRatio`。
- 轨迹记录：`ShippingTraceRecord`（NBT 持久化于 `ShippingTraceSavedData`）字段含 `shipperUuid, nationId, townId, sourceName, targetName, waypoints, completedPointCount, progressRatio`，**无实时坐标字段**。
- 可见性筛选：`ShippingTraceService.visibleFor`（:69-96）——状态须 `isMapVisibleStatus`（SAILING/IN_TRANSIT/ARRIVED）且 `waypoints.size() >= 2`；且看的人是 shipper 本人或同国（`viewerNationId == trace.nationId`）。
- 进度更新：`ShippingTraceService.updateProgress(level, shippingOrderId, completedPointCount, progressRatio)`（:58-67），载具航行时调用。
- 市场调度建 Trace：`MarketBlockEntity` ~:1815 `ShippingTraceService.createOrUpdateTrace(level, shippingOrder, route)`。
- 载具能力：`SailboatEntity`/`CarriageEntity` 持有 `autopilotRoute`（List<Vec3> waypoints）、`autopilotTargetIndex`、实时 `position()`。
- 前端：`marketweb/map.js`——`SHIPMENT_REFRESH_MS = 5000`（:9）轮询 `/api/map/shipments`（`loadShipments` :1391）；`drawShipments`（:907-921）画已完成段实线（青 `#0ea5e9` 宽4）+ 未完成段虚线（蓝 `#2563eb` 宽3 dash[12,9]）+ `drawShipmentVehicleIcon`（:939-959，帆船/马车图标）；`worldToScreen`（:410-421）世界(x,z)→屏幕像素；图标位置 `shipmentIconPose`（:923-937）用 `completedPointCount` 取路点，**无插值**。

---

## 设计

### 决策汇总（用户确认）

| 决策点 | 选择 |
|---|---|
| 手动发车轨迹来源 | 手动发车也建 ShippingTrace（复用现有系统，标记"手动"） |
| 位置精度 | 实时坐标（后端同步载具真实位置，地图跟车走） |
| 更新速度 | 后端每 2 秒同步实时坐标 + 前端插值平滑补间 |
| 视觉效果 | 增强现有样式（已走实线/未走虚线 + 图标朝向 + 尾迹/脉动 + 手动车异色） |

### A. 后端：手动发车建 Trace

载具**手动启动 autopilot**（玩家自己点发车，`TransportTaskKind.NONE`、无市场订单）时，创建一条 ShippingTrace：

- `waypoints` = 载具 `autopilotRoute` 的全部 Vec3 路点（x/z）。
- 无 `ShippingOrder`，因此 `shippingOrderId` 用一个**手动专属 id**（如 `"manual-" + 载具UUID`，避免与订单 id 冲突，且便于到站清理）。
- `shipperUuid` = 驾驶/发车玩家 UUID；`nationId` = 该玩家国家（保证 `visibleFor` 的"本人/同国可见"正常工作）。
- Trace 新增标记字段 `manual`（boolean），区分手动车 vs 调度车（前端据此用不同颜色）。
- `status` 发车即设为可见状态（IN_TRANSIT 或 SAILING，使 `isMapVisibleStatus` 通过）。
- 触发点：马车/帆船手动 `startAutopilot` 成功后（服务端），调用一个新的 `ShippingTraceService.createOrUpdateManualTrace(level, vehicle)`。

### B. 后端：实时坐标同步

- `ShippingTraceRecord` 新增字段 `currentX`（double）/ `currentZ`（double）：载具实时坐标。NBT save/load（缺字段时回退到首个 waypoint，兼容旧记录）。
- 载具航行 tick 里，**每 2 秒**（复用现有低频 tick 计数器模式，如 40 tick=2秒）把当前 `position()` 写进对应 Trace（`ShippingTraceService.updateLivePosition(level, traceId, x, z)`）。调度车与手动车都同步。
- `ShipmentTrace` DTO 新增 `current`（Point{x,z}）+ `manual`（boolean）+ `progressRatio`（已有）输出。
- 进度 `completedPointCount`/`progressRatio` 仍按现有 `updateProgress` 维护（前端可用作"已走/未走"分段）。

### C. 可见性与生命周期

- `isMapVisibleStatus` 集合不变（SAILING/IN_TRANSIT/ARRIVED）；手动 Trace 发车即标 IN_TRANSIT/SAILING 进入该集合。
- **到站/停止清理**：载具 autopilot 结束（`finishAutopilot` / `stopAutopilot`）时，把对应手动 Trace 转终态或移除——不在地图残留。手动 traceId（`manual-<载具UUID>`）便于精确定位删除。
- 手动车若被玩家中途接管（取消 autopilot），同样清理其 Trace。

### D. 前端：插值 + 视觉增强

- 轮询频率：`SHIPMENT_REFRESH_MS` 调到 **2000**（与后端 2 秒坐标同步对齐）。
- **插值平滑**：前端记录上一次 `current` 与本次 `current`，在两次轮询之间按时间线性补间，载具图标平滑移动（不每 2 秒跳）。无 `current`（旧数据）时回退到现状的 `completedPointCount` 路点定位。
- **视觉增强**（在现有 `drawShipments` 基础上）：
  - 已走段实线 / 未走段虚线（保留）。
  - 载具图标按**移动方向**旋转（用前后 `current` 差算朝向）。
  - 加尾迹或脉动光晕（轻量，CSS/canvas）。
  - **手动车异色**：`manual=true` 的轨迹/图标用区别于调度车的配色（如调度车青蓝、手动车橙/绿），图例可注明。

### E. 数据流

```
载具手动 startAutopilot（服务端）
  → ShippingTraceService.createOrUpdateManualTrace(level, vehicle)  建手动 Trace（manual=true, id=manual-<uuid>）
载具航行 tick（每 2 秒）
  → ShippingTraceService.updateLivePosition(level, traceId, x, z)   写 currentX/Z
  → updateProgress(...)                                            维护已走/未走分段
/api/map/shipments
  → ShipmentTrace 输出 points + current + manual + progressRatio
前端轮询（~2 秒）
  → 记录 prev/next current → 时间补间插值 → canvas 画轨迹 + 平滑跟走图标（朝向/尾迹/手动异色）
载具 finishAutopilot/stopAutopilot
  → 清理/转终态对应手动 Trace（不残留）
```

---

## 组件划分（单一职责）

- `ShippingTraceRecord`：加 `currentX/currentZ`、`manual` 字段 + NBT 兼容。
- `ShippingTraceService`：加 `createOrUpdateManualTrace(level, vehicle)`、`updateLivePosition(level, traceId, x, z)`、手动 Trace 清理；`toDtos` 输出 current/manual。
- `MarketWebMapDtos.ShipmentTrace`：加 `current`（Point）、`manual`（boolean）。
- `MarketWebMapJson.shipments`：输出新增字段。
- 载具（`SailboatEntity`/`CarriageEntity`）：手动 startAutopilot 触发建 Trace；航行 tick 每 2 秒同步坐标；autopilot 结束清理。
- 前端 `marketweb/map.js`：插值补间、方向旋转、尾迹/脉动、手动异色、轮询对齐。

---

## 验证

1. **单测（后端纯逻辑）**：
   - `ShippingTraceRecord` NBT save/load 含 currentX/Z/manual，旧记录缺字段回退 waypoint[0]。
   - 手动 traceId 生成（`manual-<uuid>`）与调度 id 不冲突。
   - `visibleFor` 对手动 Trace（本人/同国）可见、非同国不可见。
   - 前端插值纯函数（若可抽离）：给定 prev/next/进度比，返回补间坐标。
2. **集成（游戏内 + 网页）**：
   - 手动发车马车 → 网页地图出现轨迹、图标实时跟走。
   - 手动发车帆船 → 同上（水路）。
   - 调度发车仍正常显示（不回归）。
   - 手动车与调度车颜色可区分。
   - 载具到站 → 轨迹从地图消失（不残留）。
   - 多辆同时在途 → 各自独立轨迹、各自跟走。
3. **回归**：现有市场调度轨迹显示不被破坏；前端旧字段（completedPointCount/progressRatio）仍可用。

---

## 实施分期建议

1. 后端数据模型：`ShippingTraceRecord` 加 currentX/Z/manual + NBT 兼容 + DTO/JSON 输出字段
2. 后端逻辑：手动发车建 Trace（createOrUpdateManualTrace）+ 航行每 2 秒同步坐标（updateLivePosition）+ autopilot 结束清理
3. 前端：插值补间 + 实时跟走 + 方向旋转
4. 前端视觉：尾迹/脉动 + 手动车异色 + 图例

> 注：前端在 `marketweb/map.js`，与近期 webmap 工作同区，实现时注意不与其它 webmap 改动冲突。
