# 道路系统Bug修复规格

> 日期: 2026-04-20
> 前置: roadpath_reinforcement 分支

## 9个Bug修复

### Fix 1+3: 桥梁系统重做

完全重写 BridgeBuilder、BridgeRampBuilder、BridgePierBuilder、BridgeDeckPlacer、BridgeLightPlacer。

**桥梁剖面:**
```
                        ┌──── 平面桥面(水面+5) ────┐
                       /│  桥墩间隔8格              │\
            半砖爬升  / │    │         │            │ \  半砖下降
  ┌──陆地平台──┐    /   │    │桥墩     │桥墩        │   \    ┌──陆地平台──┐
──┘           └──/     └────┘         └────────────┘     \──┘           └──
```

**爬升序列(纯半砖, 每步+0.5格):**
```
[slab bottom] Y+0.5
[slab top]    Y+1.0
[slab bottom] Y+1.5
[slab top]    Y+2.0
...直到水面+5
```
不用台阶，纯半砖 bottom→top 交替。下坡对称反转。

**桥墩:** 间距 `Math.max(5, pierInterval)`，从海底到桥面垂直石砖柱。

**扶手:** 桥面两侧全程栅栏(高1格)。坡道段也有扶手。

**路灯(建在扶手顶部):**
```
  [灯笼]        ← 悬挂灯笼
  [栅栏]←横臂   ← 朝路内侧延伸
  [栅栏]        ← 柱身
  [栅栏]        ← 柱身
  [栅栏]←扶手   ← 扶手顶部=路灯底座
──[桥面]──
```
间距8格，交替左右。

**施工顺序:** 平台→坡道→桥墩→桥面→下坡→平台→扶手→路灯

### Fix 2: 路灯灯笼硬编码
灯笼位置强制用 `Blocks.LANTERN` + `HANGING=true`，不走材料系统。

### Fix 4: 道路跟随地形
RoadSegmentPaver 每个位置用 `cache.getHeight(x,z)` 获取真实地面高度，不用路径Y。

### Fix 5: 寻路成功条件放宽
4个Pathfinder成功条件改为 `manhattan <= step * 3`。到达后追加目标点。BasicAStar maxSteps加2倍乘数。

### Fix 6: 施工障碍自动处理
StructureConstructionManager 的 `describeRoadPlacementFailure()`:
1. 障碍物→清除为AIR
2. 缺支撑→放圆石
3. 记录临时方块，施工完成后回收
4. 只有清除/支撑都失败才报错

### Fix 7: 幽灵方块半透明纹理
RoadPlannerPreviewRenderer 用 `BlockRenderDispatcher` + translucent渲染实际方块模型(alpha=0.45)。

### Fix 8: 路宽选择UI
新建 RoadPlannerConfigScreen(宽度3/5/7按钮组)，目标选择后弹出。新增 ConfigureRoadPlannerPacket。

### Fix 9: 渐进式回滚
ConstructionQueue 新增 `progressiveRollback(level, batchSize)` 每tick恢复3个方块。RoadLifecycleService 接入渐进式拆除。
