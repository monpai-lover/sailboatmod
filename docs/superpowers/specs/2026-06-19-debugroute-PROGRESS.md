# debugroute 工具实施进度 — 存盘交接

写于 2026-06-19。配套设计文档:`2026-06-19-debugroute-waypoint-nodes-design.md`(spec,已批准已提交)。
本文件记录**实施进度**,供中断后无缝接手。分支 `feature/road-planner-rebuild`。

## 当前状态:9 个子任务,#1~#6 已改完代码(未编译验证),#7 进行中,#8/#9 未动

> ⚠️ 还没跑过 `./gradlew compileJava -x test`。全部改完 #7~#9 后必须先编译验证再 build jar。

## 重要事实校正(和上一份口头交接的出入)

- 上次 `/clear` 写的那份 `HANDOFF.md` **没真正落盘**(那是 `/clear` 命令 stdout 里的工具块,渲染层没执行)。
- working tree 有 **13 个未提交改动**(`WaterRoutePathfinder`/`ThreeSegmentPlanner` 等),那是**上一阶段「Bug A 假相遇 + 中段重做」**的成果,jar 1.3.10(`build/libs/`,17:11)是它们的产物。**这批不要替用户提交、不要回滚**,debug 工具叠在其上。
- debug 工具是**全新工作**,从 `RouteDefinition` 原始结构起步。

## 已完成(代码已写入磁盘)

### #1 ✅ `route/WaypointMeta.java`(新建)
`record WaypointMeta(byte segment, byte origin)`,常量 `SEGMENT_START=0/MID=1/END=2`、`ORIGIN_RAW=0/INTERP=1`,工厂 `of(int,int)`。

### #2 ✅ `route/RouteDefinition.java`
主构造器末尾加第 9 字段 `List<WaypointMeta> waypointMetas`。规范构造器里 null→`List.of()`、非空→`List.copyOf`。**保留** 2/6/8 参三个便利构造器(全默认传 `List.of()`)→ 所有现存 21 处 `new RouteDefinition(...)` 调用点零改动。`copy()` 一并复制 metas。

### #3 ✅ `route/PathSmoother.java`
新增公开 `smooth2DWithOrigin(polyline, fixedY, spacing)` → `record SmoothWithOrigin(List<BlockPos> points, List<Byte> origins)`。内部:`smoothCenters` 加 `List<Byte> originsOut` 重载(旧签名委托传 null);origin 判定 = 平滑点取整坐标命中 `simplify` 控制点坐标集合(`Set<Long>` packKey)→ RAW,否则 INTERP。新增 `extractCenters` 带 origin 重载、`thinWithOrigin`(抽稀同步抽 origin 保 index 对齐)、`packKey`、`originForKey`。两点直线退化:首尾 RAW 中间 INTERP。旧 `smooth2D`/`smooth2DInterpolatedY`(陆路/手动)不变。

### #4 ✅ `route/water/ThreeSegmentPlanner.java`
新增 `record PlannedPath(List<BlockPos> path, int jointA, int jointB)`。把原 7 参 `runSerial` 主体改名为 `runSerialPlanned`(返回 `WaterRouteResult<PlannedPath>`);保留旧 3 参 + 7 参 `runSerial`(内部调 planned 解包回 `List<BlockPos>`,给测试/非 debug 用)。拼接时记 `jointAPos`/`jointBPos` 坐标,**去折(`dropBackfoldsNearJoints` 改点数)后用 `nearestIndex(full, pos)` 重定位 jointA/jointB**(关键:平滑/去折都改点数,joint 必须靠坐标重定位,不能靠原 index)。新增 `nearestIndex` 私有方法。

### #5 ✅ `route/water/WaterAutoRouteService.java`
- import 加 `WaypointMeta`。
- threeSeg Supplier:改调 `runSerialPlanned` 拿 `PlannedPath` → `smooth2DWithOrigin` 拿 points+origins → `buildMetas(...)` 写入 `metasOut`。
- `metasOut` = `AtomicReference<List<WaypointMeta>>`(后台 Supplier 写、主线程 `applyCompletedRoute` 读,Atomic 保可见性)。**这是 metas 从后台搬到主线程的通道,最终仍塞进 RouteDefinition 持久化——不违背 spec「出身标记进正式结构、不用旁路缓存」决策**(旁路缓存指的是绕过 RouteDefinition,这里没绕)。
- `applyCompletedRoute` 加 `List<WaypointMeta> waypointMetas` 参,onComplete 传 `metasOut.get()`。
- 新增 `buildMetas(smoothed, origins, original, jointA, jointB)`:segment = 平滑点投影到原折线最近 index vs jointA/jointB(`nearestIndexOnPolyline`);origin = origins[i]。
- `routeDefinitionFromPath` 加 metas 参,末尾**仅当 `metas.size()==waypoints.size()` 才带入**(单段 fallback / 长度不符 → `List.of()` 降级)。
- 单段 fallback Supplier(singleSeg)**不** set metasOut → metas 留空,debug 降级,安全。

### #6 ✅ `route/RouteNbtUtil.java`
- `writeRoutes`:每 point 仅当 `route.waypointMetas().size()==waypoints.size()` 时写 `Seg`/`Org` 两个 byte。
- `readRoutes`:逐点读 `Seg`/`Org`,**只有每个 point 都带 `Seg` 且总数==waypoints 才认 metas**(`allHaveMeta` 闸门),否则 `List.of()`(旧航线降级)。用 9 参构造器带入 `finalMetas`。

## 进行中

### #7 🔨 `market/web/MarketWebCommands.java` debugRoute 采真实方块 + 打包
**已确认的关键事实**:
- debugRoute(L305)本就在**主线程**(命令执行线程),可直接 force 加载读真实方块。
- 现有逻辑:把 `route.waypoints()` 塞进 `ShippingTraceRecord` 投到 trace,`DEBUG_TRACE_PREFIX="debugroute_"`,status 必须 `SAILING`,manual=true(金线)。
- **`ShippingTraceRecord` 是 record,不能加 transient 实例字段** → 走 spec L80 的备选「**并行 DTO 旁路**」。
- **采方块逻辑可直接抄** `RealBlockWaterMap.diagnosePathLandCrossings`(L126-186):`ForgeChunkManager.forceChunk` → `level.getChunk(cx,cz,FULL,true)` → `level.getFluidState(new BlockPos(x,seaLevel,z))` 判水 + `level.getBlockState` 取 `BuiltInRegistries.BLOCK.getKey().toString()` 方块 id → `forceChunk(...,false)` 释放。海平面那格是否水 = 船看的可航判定。

**计划(待实现)**:
1. 新建 debug 节点旁路缓存,建议放 `ShippingTraceService` 里一个 `static Map<String traceId, List<DebugNode>>`(或独立 `DebugRouteNodeCache`)。`DebugNode` 字段:`{double x, z; String blockId; boolean water; byte origin; byte segment;}`。命令重投即覆盖,不持久化。
2. debugRoute 里:取 `route.waypoints()` + `route.waypointMetas()`,主线程逐点 force 加载读 `{blockId,isWater}`,合成 `List<DebugNode>`(metas 缺位→origin/segment 给默认 0,降级),`put(traceId, nodes)`。
   - ⚠️ 逐点 force 加载可能很多点(平滑后 ~256)→ 注意性能。可参照 diagnose 的抽样,但 debug 要每点都画,**不抽样**;改为按 chunk 去重(同一 chunk 只 force 一次,读完该 chunk 内所有落点再释放)避免反复 force 同一区块。
3. `debugRouteClear` 同步清旁路缓存。

## 未动

### #8 ⬜ trace DTO 透传 + webmap JSON
- `ShippingTraceService.toDto`(约 L123,需确认):把旁路缓存的 `List<DebugNode>` 按 traceId 取出,透传进 `ShipmentTrace`。
- `MarketWebMapDtos.Point`:`record Point(double x, double z)` → 加 `block/water/origin/segment`(可空)。⚠️ 牵连所有 `new Point(...)` 调用点,**用兼容构造器**:主构造器 6 字段,保留 2 参构造器默认(`null`/`false`/`-1`)。先 grep `new MarketWebMapDtos.Point\(|new Point\(` 确认调用点。
- `MarketWebMapJson`(L31 points 序列化、L108 shipment):序列化时附加 `block/water/origin/segment`。
- 透传建议**并行 DTO**:不碰 `ShippingTraceRecord` 持久化 record。可能需要给 `ShipmentTrace` 或 `Point` 携带 debug 字段。

### #9 ⬜ `resources/marketweb/map.js` 节点渲染 + tooltip
- `drawRoute` 后(约 L1187)画节点圆点:`origin==RAW`→大圆 r6,`INTERP`→小圆 r3;`water`→蓝描边,陆→红描边。
- hover tooltip:复用已有 `state.hover`/`state.tooltip`/`distanceToSegment`(约 L1290-1310)。显示 `(x,z)`/方块 id/是否水/段(起始/中段/尾段)/来源(原始/插值)。
- 老航线(无 debug 节点)降级:无节点层,仍画金线,不报错。
- ⚠️ map.js 改的是 `marketweb` 子 jar 资源,build 产物 `sailboatmod-marketweb-1.3.10.jar`。

## 收尾(必做)

1. `./gradlew compileJava -x test` 通过(测试有失效技术债,必须 `-x test`,见记忆 `marketweb_snapshot_deadlock`)。
2. `./gradlew build -x test` 出 jar。
3. 推 GitHub:经代理 7897 + gh 凭据(直连/gh_token.txt 都不行,见记忆 `sailboatmod_push`)。commit message 用 **Bash heredoc** 写(Write 工具写的 /tmp 与 git 看到的不是同一文件,见记忆 `sailboatmod_commit_msg_tmp_path`)。每个 bug fix 都 build+push(记忆 `feedback_build_push`)。
4. **本次没改 packet** → 不用 bump PROTOCOL_VERSION。

## 验证(spec §验证)

建水路自动航线 → `/marketweb debugroute` → webmap 看金线 + 航点圆点 → hover 陆地上的点看 tooltip:
- 陆地点多为**小圆(插值)** → 样条过冲(Bug B);多为**大圆(原始)** → 寻路寻到陆。这是这个工具的最终目的:分清两类穿陆来源,再回头修。

## 工具调用 Bug 提醒(本会话现象)

`/clear`、`/model` 等本地命令插入后,紧跟的 `call`/`invoke` 工具块有时被渲染成纯文本不执行。对策:命令插入后重新发起被打断的工具调用;关键产出(如本进度文件)用 Write 工具单独发,确认 "File created/updated" 回执。
