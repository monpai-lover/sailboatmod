# debugroute 航点节点可视化 + 真实方块 tooltip 设计

日期:2026-06-19
状态:设计已批准,待写实现计划

## Context(为什么做)

水路自动航线在 WorldPainter「原版生成器+populate」地图上出现「穿陆」(webmap 橙线斜穿大陆)。已修「双向 A* 假相遇拼出 1664 格直线空隙」(#73)。但还有第二类穿陆嫌疑——`PathSmoother` 样条平滑的**过冲(overshoot)**:在海岸急拐弯处 Catmull-Rom 曲线甩出原折线、冲进陆地(PathSmoother 自己注释警告过,而 NBT/HYBRID 主路径已去掉末端 verifier,过冲无人纠正)。

要分清「陆地上的航点」到底是:① 寻路真寻到了陆地,还是 ② 样条插值过冲甩进陆地,需要一个 debug 工具——把 `/marketweb debugroute` 显示的航线**每个最终航点**画成可 hover 的节点,tooltip 看到坐标、脚下**真实方块**、是否水、段归属、来源(寻路原始节点 vs 样条插值点)。用大/小圆点区分原始/插值,一眼看出陆地上那串是过冲小点还是寻路大点。

## 目标

`/marketweb debugroute [name]` 投放的调试金线,在 webmap 叠加航点节点层:
- 每个最终航点一个圆点:**寻路原始节点=大圆(r6)**,**样条插值点=小圆(r3)**;**水格=蓝描边**,**陆格=红描边**(陆点醒目)。
- hover 节点 tooltip 显示:`(x,z)` / 真实方块 id / 是否水 / 段(起始/中段/尾段)/ 来源(原始节点/插值点)。

## 关键决策

1. **出身标记直接进正式数据结构**(不用内存旁路缓存):`RouteDefinition` 加 `List<WaypointMeta>`,持久化到 NBT,跟着航线走。
2. **只水路自动航线填标记**:其它航线(陆路/手动/路书)`metas=空`,不受影响。
3. **真实方块信息是 debug 命令时快照,走 trace 内存 transient 字段、不持久化**:metas 持久化,方块 id/是否水只在 debugroute 投递时采样填充(命令时一次性 force 加载读真实方块),不进 ShippingTraceRecord 的 NBT。

## 数据结构

### 新增 `WaypointMeta`(record)
```java
public record WaypointMeta(byte segment, byte origin) {
    // segment: 0=起始段 1=中段 2=尾段
    // origin:  0=寻路原始节点  1=样条插值点
}
```

### `RouteDefinition` 加字段
`List<WaypointMeta> waypointMetas`(与 waypoints 等长,或空=无标记)。所有现有构造器默认传空,向后兼容。`copy()` 一并复制。

## 数据流(端到端)

```
寻路生成(WaterAutoRouteService 后台 Supplier)
  ├─ ThreeSegmentPlanner.runSerial:拼接时已知 jointA/jointB 边界
  │    → 产出"拼接后每点 segment"数组,随结果带出
  ├─ PathSmoother.smooth2D:改成额外输出"每平滑点 origin"
  │    (落在 simplify 控制点上=0 原始;样条采样加密点=1 插值)
  └─ 合成 List<WaypointMeta>,构造 RouteDefinition 带入

持久化(RouteNbtUtil)
  └─ 每个 point CompoundTag 多存 Seg/Origin 两个 byte;读时缺省→metas 空

debugroute 命令(MarketWebCommands.debugRoute)
  ├─ 取 route.waypoints() + route.waypointMetas()
  ├─ 主线程 force 加载逐点区块,读真实方块 → {blockId, isWater}
  │    (复用 RealBlockWaterMap force+getBlockState 思路)
  └─ 打包 metas + 方块信息 → 投进 trace 的内存 transient debug 字段

webmap JSON(MarketWebMapJson.shipment)
  └─ 若 trace 有 debug 节点信息 → points[] 每元素附加 {block,water,origin,segment}

前端(map.js)
  ├─ drawRoute 后画节点圆点(origin→大小,water→描边色)
  └─ hover 命中节点 → tooltip 显示 坐标/方块/水/段/来源
```

## 各阶段产出「origin」判定细则

- `PathSmoother.smoothCenters` 流程:`simplify`(叉积阈值去共线)→ Catmull-Rom 逐段采样 → 弧长重采样。
- 「原始节点」= `simplify` 保留的控制点(真实拐点);「插值点」= 样条加密 + 重采样新生的点。
- 实现:smooth 输出每个最终点时,记录它到最近 simplify 控制点的距离;≈0(同格)→ origin=0,否则 origin=1。或在重采样时直接标记控制点命中。

## 涉及文件

**改**:
- `route/RouteDefinition.java`(加 waypointMetas 字段 + 兼容构造器 + copy)
- 新增 `route/WaypointMeta.java`
- `route/PathSmoother.java`(smooth2D 输出原始/插值标记;新增带 meta 的重载,旧签名保留给陆路/手动)
- `route/water/ThreeSegmentPlanner.java`(runSerial 产出 segment 边界标记,随结果带出)
- `route/water/WaterAutoRouteService.java`(合成 metas,构造 RouteDefinition 带入)
- `route/RouteNbtUtil.java`(NBT 读写每点 Seg/Origin)
- `market/web/MarketWebCommands.java`(debugRoute 采真实方块 + 打包 debug 节点信息)
- `market/logistics/ShippingTraceRecord.java`(加内存 transient debug 节点字段,不进 NBT) 或并行 DTO
- `market/logistics/ShippingTraceService.java`(toDto 透传 debug 节点信息)
- `market/web/map/MarketWebMapDtos.java` + `MarketWebMapJson.java`(points 加 block/water/origin/segment)
- `resources/marketweb/map.js`(节点圆点渲染 + tooltip)

**复用**:
- `RealBlockWaterMap` force 加载 + 读真实方块(`forceAndCapture`/getBlockState/getFluidState)。
- 前端 `state.hover`/`state.tooltip`/`distanceToSegment` 已有的 hover+tooltip+命中基础设施。
- debugRoute 已有的"命令时构造 trace"流程。

## 不做(YAGNI)

- 不给陆路/手动/路书航线加标记。
- 不做 hover 按需实时查方块(marketweb 是 HTTP 轮询,实时通道太重);命令时一次性采。
- 不持久化真实方块快照(命令当下采,重投即刷新)。

## 验证

1. `./gradlew compileJava -x test` 通过。
2. 建一条水路自动航线 → `/marketweb debugroute` → webmap 看到金线 + 航点圆点。
3. hover 陆地上的点:tooltip 显示真实方块(如 grass_block)、是否水=否、段、来源。
4. 验证假设:陆地上的点若多为小圆(插值)→ 样条过冲;若为大圆(原始)→ 寻路寻到陆。
5. 老航线(无 metas)/陆路航线:debugroute 仍能画线,节点层降级(无 origin 区分按默认),不报错。
6. NBT 存读往返:重启后水路航线 metas 仍在。

## 推送

按 [[feedback_build_push]] [[sailboatmod_push]]:build -x test → 代理7897+gh凭据;commit heredoc([[sailboatmod_commit_msg_tmp_path]])。
