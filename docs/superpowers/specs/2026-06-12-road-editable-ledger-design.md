# 可编辑道路与道路账本设计

日期：2026-06-12

## 背景

当前道路规划系统可以规划、预览、建造、拆除和复用部分已建道路，但已建道路不是一个完整的可编辑对象。主要缺陷是：

- 道路建好后不能通过增删节点、拖动节点、拓宽、改材料来升级。
- 道路修改时缺少正式的施工前地形账本，容易误删或误恢复玩家后来修改过的方块。
- 已建道路和道路规划器之间存在重复建造风险：从城镇 A 到城镇 B 已有道路时，仍可能再次规划一条重复道路。
- 道路规划器主菜单里的“选择目的地 Town”按钮当前没有实际动作。

百年战争模组里的道路系统提供了可参考的生命周期模型：道路分为节点、段和道路方块记录。每个道路方块记录原状态、道路状态和引用计数；拆除段时只释放该段拥有的方块，引用计数归零后才恢复原地形。我们的实现应借鉴这个结构，但保留 sailboatmod 自己的国家、城镇、市场、道路规划和小地图语义。

## 目标

- 允许玩家选择自己有权限管理的已建道路并进入编辑模式。
- 支持增删节点、拖动节点、修改宽度、修改材料、修改道路/桥梁/隧道类型。
- 修改后按差异恢复废弃旧道路 footprint，并在新位置重新施工。
- 记录道路施工前经过的地形，避免后续改线或拆除时破坏世界。
- 旧版本已建道路第一次编辑前自动迁移账本，以编辑前现状作为可恢复快照。
- 道路规划新线路时检测源城镇和目标城镇之间是否已有道路，已有道路时引导编辑现有道路。
- 修复道路规划器主菜单“选择目的地 Town”按钮，让它打开现有城镇选择 UI。

## 非目标

- 不重写整个道路寻路和铺设算法。
- 不废弃现有 `RoadNetworkRecord`，它仍服务于自动路线、市场发货、马车和旧系统。
- 不把百年战争的建筑、战争或权限语义搬进 sailboatmod。
- 不对跨维度道路做单条道路编辑支持；道路编辑保持同维度。

## 权限模型

采用分层权限，满足任一条件即可编辑道路：

- 道路原建造者。
- 道路所属城镇的镇长或城镇管理者。
- 道路所属国家的有道路管理权限的官员。

权限从小到大覆盖。服务端是唯一权限裁决方，客户端只展示服务端返回的可编辑列表。

## 推荐方案

采用“道路账本方案”。

现有系统继续保存 `RoadNetworkRecord` 和 `RoadNetworkGraphSavedData`，同时新增一个正式的可编辑道路账本。账本成为恢复、改线和拓宽的主依据；旧结构作为兼容索引和其他系统的数据源。

## 数据模型

新增或扩展以下持久化模型：

### RoadEditableNetworkSavedData

服务端持久化数据，保存所有可编辑道路索引和方块账本。

职责：

- 按 `roadId` 查询可编辑道路。
- 按 `edgeId` 或城镇连接查询道路。
- 按方块位置查询道路方块账本。
- 提供旧道路迁移入口。
- 标记脏数据并写入世界存档。

### RoadEditableRecord

表示一条可编辑道路。

字段建议：

- `roadId`
- `edgeId`
- `dimensionId`
- `ownerNationId`
- `ownerTownId`
- `creatorUuid`
- `creatorName`
- `sourceTownId`
- `targetTownId`
- `sourceTownName`
- `targetTownName`
- `width`
- `materialId`
- `status`
- `legacyMigrated`
- `nodes`
- `segments`
- `createdAt`
- `updatedAt`

### RoadEditableNode

表示道路编辑节点。

字段建议：

- `nodeId`
- `pos`
- `kind`
- `label`

节点类型包括：

- `NORMAL`
- `SOURCE_TOWN`
- `TARGET_TOWN`
- `MARKET`
- `POST_STATION`
- `DOCK`
- `LEGACY_IMPORTED`

### RoadEditableSegment

表示两个节点之间的一段道路。

字段建议：

- `segmentId`
- `fromNodeId`
- `toNodeId`
- `centerline`
- `displayPath`
- `width`
- `sectionType`
- `materialId`
- `blockPositions`

### RoadBlockLedgerEntry

表示一个由道路系统管理过的方块。

字段建议：

- `pos`
- `originalState`
- `roadState`
- `refCount`
- `ownerSegmentIds`
- `lastKnownRoadId`
- `updatedAt`

恢复规则：

- 只有 `refCount` 归零时才允许恢复原方块。
- 恢复前必须检查当前世界方块是否仍等于账本中的 `roadState`。
- 如果当前方块已被玩家或其他系统修改，跳过恢复并记录 issue。

## 旧道路迁移

旧道路指没有 `RoadEditableRecord` 或没有完整 ledger 的已建道路。

第一次进入编辑模式时执行迁移：

1. 从 `RoadNetworkRecord` 和 `RoadGraphEdgeRecord` 读取道路 centerline、displayPath、width、placements 和 owned blocks。
2. 如果 graph placements 存在，优先用 placements 生成 footprint。
3. 如果 placements 不完整，使用现有铺路算法根据 centerline 推导 footprint。
4. 将当前世界中的对应方块状态记录为 `originalState`。
5. 将当前道路方块状态记录为 `roadState`。
6. 生成 `RoadEditableRecord`、节点、段和 ledger。
7. 标记 `legacyMigrated=true`。

旧道路迁移只保证可恢复到“第一次编辑前的世界状态”，不假装知道更早之前的自然地形。

## 编辑流程

### 打开编辑

1. 玩家在道路规划器第一层 UI 点击“编辑现有道路”。
2. 客户端发送 `OPEN_EDIT_ROAD_SELECTION`。
3. 服务端列出玩家可管理道路。
4. 客户端打开道路选择列表。
5. 玩家选择道路。
6. 服务端确保 ledger 存在；旧道路先迁移。
7. 客户端进入 `EDIT_EXISTING_ROAD` 模式。

### 修改节点和属性

编辑模式中支持：

- 插入节点。
- 删除普通节点。
- 拖动节点。
- 修改道路宽度。
- 修改材料。
- 修改段类型：道路、桥梁、隧道。
- 查看差异预览。

### 提交修改

客户端提交：

- `roadId`
- 编辑后的节点列表
- 每段类型
- 宽度和材料设置

服务端执行：

1. 校验权限。
2. 检查道路是否被锁定。
3. 编译新道路方案。
4. 计算旧 footprint 和新 footprint 的差异。
5. 创建预算式改造任务。
6. 按 tick 推进恢复和重建。
7. 完成后刷新道路索引、小地图、自动路线和市场发货缓存。

## 差异重建算法

定义：

- `oldFootprint`：旧道路账本中该道路段拥有的方块集合。
- `newFootprint`：新编译方案将占用的方块集合。
- `removed = oldFootprint - newFootprint`
- `added = newFootprint - oldFootprint`
- `kept = oldFootprint ∩ newFootprint`

处理顺序：

1. `removed`
   - 从 owner segment 列表移除当前段。
   - `refCount--`。
   - `refCount > 0` 时保留方块。
   - `refCount == 0` 时，如果当前世界状态等于 `roadState`，恢复 `originalState`。
   - 如果当前世界状态不等于 `roadState`，跳过恢复并记录冲突。

2. `kept`
   - 如果新旧 `roadState` 相同，只更新 owner 信息。
   - 如果材料或类型变化，只有当前世界状态等于旧 `roadState` 时才替换为新 `roadState`。
   - 被玩家改过的方块跳过并记录冲突。

3. `added`
   - 读取当前世界方块作为新的 `originalState`。
   - 写入新 `roadState`。
   - 创建或更新 ledger entry。
   - 增加 owner segment 和 `refCount`。

## 任务执行和 Watchdog 防护

道路改造任务拆为阶段，每 tick 有预算：

- `MIGRATE_LEDGER`
- `COMPILE_NEW_PLAN`
- `RELEASE_REMOVED`
- `UPDATE_KEPT`
- `PLACE_ADDED`
- `SYNC_INDEXES`
- `COMPLETE`

每个阶段只处理有限数量的方块或节点，避免单 tick 扫描和修改过多区块。任务状态需要持久化，服务器重启后可以继续或进入可恢复状态。

## 重复道路检测

玩家选择目标城镇后，服务端计算城镇连接 key：

`min(sourceTownId, targetTownId) + "|" + max(sourceTownId, targetTownId)`

处理规则：

- 没有既有道路：正常进入新建规划。
- 有一条既有道路且玩家可编辑：打开该道路编辑模式。
- 有多条既有道路且玩家可编辑：打开选择列表，让玩家选择要编辑的道路。
- 有既有道路但玩家不可编辑：提示已有道路且缺少权限，不允许重复建造。

道路名字不参与重复判断，避免重命名导致重复道路。

## UI 设计计划

### 第一层：道路规划器主菜单

按钮：

- 打开道路规划
- 选择目的地城镇
- 编辑现有道路
- 拆除已有道路
- 查看施工队列
- 关闭

`选择目的地城镇` 当前绑定到无动作关闭，需要改为向服务端发送目标选择动作，并复用现有 `RoadPlannerTargetSelectionScreen`。

### 第二层：道路选择列表

`编辑现有道路` 打开列表。

列表功能：

- 筛选：本城镇、本国家、我建造的道路。
- 搜索：道路名、起点城镇、终点城镇。
- 每项显示：道路名、起点、终点、长度、宽度、状态。
- 旧道路显示“需要迁移”。
- 被锁定或施工中的道路显示不可编辑原因。

### 第三层：地图编辑模式

进入 `EDIT_EXISTING_ROAD` 后：

- 旧道路用灰色底线显示。
- 新编辑路线用高亮线显示。
- 节点可选中、拖动、插入和删除。
- 右侧属性栏显示宽度、材料、段类型和冲突提示。
- 底部按钮为：预览差异、确认改造、取消。

差异预览颜色：

- 红色：将恢复的旧道路区域。
- 绿色：新增道路区域。
- 蓝色或黄色：保留或共享区域。
- 橙色：冲突区域，不会自动覆盖。

## 数据同步

道路修改完成后刷新：

- `RoadNetworkRecord`
- `RoadNetworkGraphSavedData`
- `RoadEditableNetworkSavedData`
- `RoadPlannerRoadOverlaySyncPacket`
- 共享地图/小地图道路 chunk 索引
- 自动路线缓存
- 市场、驿站、马车发货相关道路缓存

## 错误处理

- 权限失败：拒绝进入编辑或提交。
- 道路锁定：提示道路正在施工、回滚或被编辑。
- 旧道路迁移失败：保留原状并提示。
- 新路径编译失败：不改变世界和账本。
- 当前方块被玩家修改：不覆盖，记录冲突。
- 中途服务器重启：任务从持久化状态继续。
- 跨维度编辑：拒绝。

## 测试计划

自动测试：

- 新建道路生成 editable ledger。
- 旧道路第一次编辑会迁移 ledger。
- 拓宽道路只新增两侧方块，不恢复原道路。
- 缩窄道路恢复两侧废弃方块。
- 改线恢复旧路径废弃方块并铺设新路径。
- 两条道路共享方块时，修改其中一条不会恢复共享方块。
- 玩家修改过道路方块时，恢复不会覆盖玩家修改。
- A-B 已有道路时，新建入口转入编辑而不是重复建造。
- 权限 D 覆盖建造者、城镇管理者、国家官员。
- 主菜单“选择目的地城镇”打开目标选择 UI。
- 主菜单“编辑现有道路”打开道路选择 UI。

手动验证：

- 在已有城镇之间建一条路，再尝试重复规划，确认进入编辑。
- 拖动一个中间节点改线，确认旧路废弃区域恢复、新路生成。
- 拓宽道路，确认原路保留且两侧扩建。
- 修改玩家手动放置在旧路上的方块，确认改造不覆盖该方块。
- 服务器重启后确认未完成改造任务能继续。

## 实施顺序建议

1. 修复主菜单“选择目的地城镇”按钮。
2. 新增可编辑道路列表 UI 和服务端可编辑道路查询。
3. 新增 editable ledger 数据模型和旧道路迁移。
4. 新建道路完成时同步生成 ledger。
5. 实现编辑模式加载旧道路节点。
6. 实现差异预览。
7. 实现预算式改造任务。
8. 接入重复道路检测。
9. 刷新地图、覆盖层、自动路线和市场缓存。
10. 补齐自动测试和手动验证。
