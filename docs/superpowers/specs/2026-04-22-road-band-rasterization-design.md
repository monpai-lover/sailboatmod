# Road Band Rasterization & Short-Span Bridge Design

## Context

当前道路系统使用"逐节点横截面"方式生成路面：每个路径节点按四方向取垂直方向，铺一条横截面。方向切换时（转弯）产生缺角/漏洞。桥梁系统使用固定大桥结构处理所有跨越场景，导致小峡谷/浅水也出现高桥墩。道路终点可能浮空，桥梁下坡无法正确连接地面。

参考 RoadWeaver 的核心思路：先把整条路栅格化成连续宽带（点到线段距离 <= 半宽），再按段分配格子。下游铺路和桥面只消费格子集合，不再自行扩宽。

## 改动范围

### 新增文件
- `road/model/RoadSegmentPlacement.java` — 段归属格子模型

### 重写文件
- `road/pathfinding/post/PathPostProcessor.java` — 新增 rasterizeSegments + anchorEndpoints
- `road/construction/road/RoadSegmentPaver.java` — 从格子集合铺路，不再自行扩宽

### 修改文件
- `road/construction/road/RoadBuilder.java` — 消费 placements 列表
- `road/construction/bridge/BridgeBuilder.java` — 消费 placements 列表
- `road/construction/road/StreetlightPlacer.java` — 从 placements 取中心点
- `road/construction/bridge/BridgeDeckPlacer.java` — 从 placements 取格子
- `nation/service/ManualRoadPlannerService.java` — 传递 placements 到预览和施工

## 1. 数据模型

```java
// road/model/RoadSegmentPlacement.java
public record RoadSegmentPlacement(
    BlockPos center,
    int segmentIndex,
    List<BlockPos> positions,
    boolean bridge
) {}
```

## 2. PathPostProcessor 管线

```
process() 输出 ProcessedPath:
  ├─ simplify（去共线点）
  ├─ detectBridges（识别水域桥段）
  ├─ straightenBridges（桥段拉直 + 线性插值）
  ├─ relax（非桥段路径平滑）
  ├─ smoothHeights（高度限坡，前向+后向 pass）
  ├─ autoSpline（非桥段 Catmull-Rom/Bezier，桥段 lerp）
  ├─ rasterizeSegments（连续宽带扫描）★
  ├─ anchorEndpoints（首尾段接地修正）★
  └─ 输出 { path, bridgeSpans, placements }
```

## 3. rasterizeSegments 算法

输入：样条化路径 `List<BlockPos> path`，`int halfWidth`，`List<BridgeSpan> bridges`
输出：`List<RoadSegmentPlacement>`

```
for each segment i (path[i] → path[i+1]):
    bbox = boundingBox(path[i], path[i+1]).expand(halfWidth + 1)
    for each (x, z) in bbox:
        distSq = distToSegmentSq(x, z, path[i], path[i+1])
        if distSq <= halfWidth * halfWidth:
            record (x, z) → segment i, distSq

for each (x, z) with multiple segment hits:
    assign to segment with smallest distSq

group by segment → List<RoadSegmentPlacement>
    center = path[segmentIndex]
    positions = all (x, z) assigned to this segment, with Y from height interpolation
    bridge = segmentIndex falls within any BridgeSpan
```

点到线段距离：
```java
double t = clamp(dot(P-A, B-A) / dot(B-A, B-A), 0, 1)
projection = A + t * (B - A)
distSq = |P - projection|²
```

高度插值：
```java
y = path[i].getY() + t * (path[i+1].getY() - path[i].getY())
```

## 4. 终点锚定（双重保证）

寻路阶段：起终点 snap 到 `cache.getHeight(x, z)`。

rasterizeSegments 之后：
```
for each placement in first/last 3 segments:
    for each pos in placement.positions:
        groundY = cache.getHeight(pos.x, pos.z)
        if |pos.y - groundY| > 2:
            pos.y = groundY
```

## 5. 小跨桥处理

桥段在 rasterizeSegments 之前已被 straightenBridges 拉直。rasterizeSegments 对桥段和陆路一视同仁。

桥面高度：两岸地形线性过渡，确保 waterSurfaceY + 2 以上。

桥端连接：桥段首尾各扩展 2 格到陆路段，格子集合有重叠。陆路段先放置（地基+路面），桥段后放置（桥面覆盖）。

## 6. RoadSegmentPaver 重写

不再自行计算横截面和扩宽。改为：
```
pave(List<RoadSegmentPlacement> placements, TerrainSamplingCache cache):
    for each placement where !bridge:
        for each pos in placement.positions:
            skip if deep water (depth > 2)
            surfaceY = cache.getHeight or waterSurfaceY for shallow
            clear above to motionBlockingHeight
            place foundation + surface block
    place streetlights at interval from placement centers
```

## 7. BridgeBuilder 适配

```
build(BridgeSpan span, List<RoadSegmentPlacement> placements, ...):
    bridgePlacements = placements where bridge && index in span range
    for each placement:
        deckY = placement.center.getY()  // 已由高度插值确定
        for each pos in placement.positions:
            place deck block at deckY
            place pier from ground/ocean floor to deckY (per-position floor query)
    place railings and lights from placement centers
```

## 8. ManualRoadPlannerService 适配

转换 placements 到 `RoadGeometryPlanner.RoadBuildStep` 时：
- 从 placements 生成 BuildStep 列表（保留 phase 映射）
- ghostBlocks 从 placements.positions 生成

## 验证

- `./gradlew compileJava` 编译通过
- 游戏内测试：
  - 直线道路：无漏洞
  - 90° 转弯：内侧无缺角
  - S 弯：连续平滑
  - 跨小河（5-15格）：平桥连接两岸
  - 跨峡谷：桥面平滑过渡
  - 道路终点：贴地，不浮空
  - 桥下坡：连接到地面路面
