# 道路规划器稳定性修复 Spec

## 背景

道路规划器存在 8 个已确认问题，影响核心规划→预览→建造流程。本 spec 覆盖全部修复。

---

## 修复 1：道路被错误标记为桥梁

**根因：** `RoadPlannerScreen.segmentTypeForConnection()` 检查了前一个节点和目标点的水面状态，陆地上靠近水边的节点也被标成 BRIDGE_MAJOR。

**方案：** 只在目标点本身在水上时标桥梁。去掉"前一个节点在水上→标桥梁"的逻辑。

**文件：** `RoadPlannerScreen.java` — `segmentTypeForConnection()`

---

## 修复 2：桥梁不能作为第一个节点

**根因：** `addNodeWithWaterSplit` 在 nodeCount==0 时直接 addClickNode 不走分裂。第一个节点无法被标为桥梁。

**方案：** 允许第一个节点为任意段类型。水域分裂在有前一个节点时才触发。

**文件：** `RoadPlannerScreen.java` — `addNodeWithWaterSplit()`

---

## 修复 3：新增小桥类型 (BRIDGE_SMALL)

**需求：** 跨度≤24 且 深度≤6 的水域用小桥（RoadWeaver 增高型拱桥），超过用大桥（桥墩大桥）。

**桥梁构建核心规则（大桥小桥通用）：**
- 桥梁段首尾两个节点必须在陆地上
- 从两端向中间收敛构建：
  - 入口端：从陆地 Y 开始上坡，每 2 格水平距离升 1 格高度，升到 deckY 后转平面
  - 出口端：从 deckY 开始下坡，每 2 格水平距离降 1 格高度，降到陆地 Y
  - 坡面长度 = `(deckY - landY) * 2`，不会超过总长度的 1/4
  - 中间剩余部分全是平面桥面
- deckY = `max(入口Y, 出口Y) + 5`（至少高出水面 5 格）
- 大桥额外：每 4 格一个桥墩（石砖柱从桥面向下延伸到水底）
- 小桥额外：走 RoadWeaver 拱桥样式（弧形升高）

**方案：**
- `RoadPlannerWaterCrossingSplitter.split()` 根据水域跨度选择 BRIDGE_SMALL 或 BRIDGE_MAJOR
- `RoadPlannerAutoCompleteService.classifySegment()` 同样根据跨度/深度选择
- `RoadPlannerBuildControlService.nodeAnchoredBridgeSteps()` 重写为两端收敛逻辑
- BRIDGE_SMALL 段走简化版拱桥后端

**文件：** `RoadPlannerWaterCrossingSplitter.java`, `RoadPlannerAutoCompleteService.java`, `RoadPlannerBuildControlService.java`

---

## 修复 4：强制渲染不工作 / 进度条不动

**根因：**
- `processChunks` skipPredicate 匹配时 chunk 被 poll 但不计入 completedChunks
- `alreadySubmitted` 集合永不清除

**方案：**
- skipped chunk 也计入 completedChunks（进度条正常推进）
- 限制每 tick poll 上限防止队列被一次清空
- 选框渲染时调用 `tileRenderScheduler.clear()` 重置 submitted 集合

**文件：** `RoadPlannerForceRenderQueue.java`, `RoadPlannerScreen.java` (mouseReleased)

---

## 修复 5：端点重设后重开 UI 被覆盖

**根因：** Draft 只存 nodes/segmentTypes，不存端点位置。

**方案：**
- `RoadPlannerDraftStore.Draft` 增加 `startPos`/`endPos` 字段
- `RoadPlannerDraftPersistence` save/load 增加 `P,startX,startY,startZ` 和 `E,endX,endY,endZ` 行
- `applyTownRoute` 恢复 draft 时优先用 draft 中的端点位置

**文件：** `RoadPlannerDraftStore.java`, `RoadPlannerDraftPersistence.java`, `RoadPlannerScreen.java`

---

## 修复 6：设为桥梁/道路/隧道按钮不生效

**根因：** `setSelectedEdgeType` 操作 `graph`（已建道路），不是 `linePlan`（规划草稿）。

**方案：** 改为操作 `linePlan.setSegmentTypeFromNode(selectedNode.nodeIndex(), segType)`。需要 `selectedNode` 非空。

**文件：** `RoadPlannerScreen.java` — `setSelectedEdgeType()`（已部分修复，需验证 stash 中的改动）

---

## 修复 7：ghost block 预览与小地图不一致

**根因：** 修复 1 解决后，道路段不再被错误标为桥梁，PathCompiler 能正确编译连续道路。桥梁段走各自后端。

**依赖：** 修复 1 + 修复 3 解决后此问题自动消失。

---

## 修复 8：自动补全桥梁变水下道路

**状态：** 已修复（classifySegment 加入 landProbe 检测）。需验证与修复 3 的小桥阈值一致。

**文件：** `RoadPlannerAutoCompleteService.java`
