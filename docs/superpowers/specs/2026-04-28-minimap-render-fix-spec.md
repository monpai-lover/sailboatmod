# 小地图渲染管线修复 Spec

## 背景

小地图渲染管线存在 12 个故障点，核心问题是后台线程访问非线程安全的 Minecraft API。采用"主线程采集 + 后台写像素 + 加锁"方案修复。

## 修复清单

### 1. loadedTiles 改为 ConcurrentHashMap
`RoadPlannerTileManager.loadedTiles` 从 `HashMap` 改为 `ConcurrentHashMap`，解决渲染线程和后台线程同时访问的竞态。

### 2. 主线程采集地形数据
`tick()` 中在主线程调用 `RoadPlannerChunkImage` 构造函数采集地形（安全访问 ClientLevel），生成像素数据后再提交给后台线程写入 tile。

具体做法：`forceRenderChunk` 拆分为两步：
- `captureChunkImage(level, chunkPos)` — 主线程调用，返回 RoadPlannerChunkImage
- `applyChunkImage(tile, chunkImage, localX, localZ)` — 可在后台线程调用

### 3. RoadPlannerTile 加 synchronized
`updateChunk()` 和 `render()` 中对 image 的读写加 `synchronized(this)` 保护，防止后台写像素和主线程 upload 冲突。

### 4. 去掉 hasCachedTileForChunk 的 skipPredicate
skipPredicate 从 `hasCachedTileForChunk || alreadySubmitted` 改为只检查 `alreadySubmitted`。PNG 文件检查导致渲染一次后永不更新。

### 5. 异常处理
`TileRenderScheduler.submit()` 的 executor.execute 包裹 try-catch，防止静默失败。

### 6. 保留 dirty flag
后台线程写完像素后设 dirty=true，主线程 render 时检测 dirty 并 upload（已实现）。

## 文件修改

- `RoadPlannerTileManager.java` — ConcurrentHashMap + 拆分 forceRenderChunk
- `RoadPlannerTile.java` — synchronized 保护 image 读写
- `RoadPlannerScreen.java` — tick() 主线程采集 + 去掉 hasCachedTileForChunk
- `RoadPlannerTileRenderScheduler.java` — 异常处理
