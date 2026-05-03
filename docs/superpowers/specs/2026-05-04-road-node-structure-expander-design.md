# RoadPlanner 统一道路节点展开器设计

日期：2026-05-04  
状态：已通过对话设计确认，等待实现计划

## 背景

RoadPlanner 现在的道路结构生成链路分裂：手动画路、自动补全、预览和确认建造分别在不同位置拼装结构。普通道路倾向于只铺平面，桥梁结构则分散在 RoadPlanner 自己的编译器和旧的 road construction 链路中，导致以下问题：

- 手动道路结构无法稳定创建。
- 自动补全道路与手动画路生成效果不一致。
- 桥梁工具缺少入口/出口坡面结构。
- 桥墩没有按独立阶段生成，预览也不可见。
- 普通道路遇到陡峭地形时贴地起伏过大，马车通行困难。

设计目标是引入一个统一的 `RoadNodeStructureExpander`，让手动道路、自动补全、预览和实际建造共享同一套节点展开和结构生成逻辑。

## 目标

1. 手动道路和自动补全道路都只负责产生路径节点与段类型，不再各自生成结构。
2. 所有道路结构由统一展开器生成，包括普通道路、桥梁、坡面、桥墩、栏杆、地基和清理空间。
3. 预览和确认建造使用同一展开结果，避免“预览能看到但建造不同”或“建造有但预览看不到”。
4. 普通道路在地形陡峭时进行平滑、切土和填方，保证马车更容易通行。
5. 桥梁生成先采用程序化结构，预留模板接口，后续可以接 RoadWeaver 风格的 NBT 桥模板。

## 非目标

- 本轮不重写 RoadWeaver 的完整 highway 系统。
- 本轮不强制引入 RoadWeaver 的 NBT 模板资源；只保留 `BridgeTemplateProvider` 接口缝。
- 本轮不改变玩家编辑路径节点的 UI 交互目标，只改变节点被展开成道路结构的后端逻辑。
- 本轮不把小地图 LOD/强制渲染系统混入道路结构展开器；小地图可以消费路径结果，但道路结构生成保持独立。

## 现有接入点

当前相关文件包括：

- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java`
- `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerConfirmBuildPacket.java`
- `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`
- `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompiler.java`
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerPathCompiler.java`
- `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/placement/WeaverSegmentPaver.java`
- `src/main/java/com/monpai/sailboatmod/roadplanner/weaver/bridge/WeaverBridgeBuilder.java`

RoadPlanner 实际施工队列使用 `com.monpai.sailboatmod.road.model.BuildStep` 和 `com.monpai.sailboatmod.road.model.BuildPhase`，阶段包括：

- `FOUNDATION`
- `SURFACE`
- `RAMP`
- `PIER`
- `DECK`
- `RAILING`
- `STREETLIGHT`

统一展开器应优先输出这个旧施工模型可消费的 `BuildStep`，避免额外迁移施工队列。

## 总体架构

新增核心组件：

```text
RoadNodeStructureExpander
  input:
    nodes
    segmentTypes
    RoadPlannerBuildSettings
    ServerLevel / terrain sampler
    mode: PREVIEW or BUILD

  output:
    RoadNodeExpansionResult
      canonicalNodes
      canonicalSegmentTypes
      centerline
      spans
      targetY profile
      previewBlocks
      buildSteps
      issues
```

推荐的包位置：

```text
src/main/java/com/monpai/sailboatmod/roadplanner/structure/
```

建议拆分为小组件：

- `RoadNodeStructureExpander`：对外唯一入口，协调标准化、采样、分段、生成步骤。
- `RoadCenterlineBuilder`：将节点线段插值成连续 block centerline。
- `RoadSpanClassifier`：把连续 segment types 合并成普通道路 span、桥梁 span 等。
- `RoadHeightProfileSmoother`：计算普通道路和桥梁衔接的 `targetY` 高度曲线。
- `RoadSurfaceStepEmitter`：生成普通道路的 foundation、surface、ramp、headroom 清理。
- `BridgeStructureEmitter`：生成桥梁 deck、ramp、pier、railing、清理步骤。
- `BridgeTemplateProvider`：本轮提供接口和程序化默认实现，后续可接 NBT 模板。

## 输入标准化规则

展开器收到节点后先做 canonicalization：

1. 删除连续重复节点。
2. 如果节点少于 2 个，返回 `issues`，不生成施工队列。
3. 如果 `segmentTypes` 缺项，缺失段补为普通 `ROAD`。
4. `BLOCKED_REQUIRES_BRIDGE` 在结构展开阶段视为 `BRIDGE_MAJOR`。
5. 手动画路与自动补全都必须提交相同的数据形态：`nodes + segmentTypes + settings`。
6. 自动补全可以继续使用现有 route expander 产生更细节点，但不能直接生成最终结构。

## Centerline 与 span

`RoadCenterlineBuilder` 将相邻节点逐 block 插值，输出连续 centerline。每个 centerline point 至少包含：

- `x`
- `z`
- 原始 terrain height
- 所属 segment index
- 所属 segment type
- 插值距离/累计距离

`RoadSpanClassifier` 基于 centerline 合并连续类型：

- `ROAD` → 普通道路 span
- `BRIDGE_SMALL` / `BRIDGE_MAJOR` / `BLOCKED_REQUIRES_BRIDGE` → 桥梁 span
- 后续新增 tunnel 或特殊结构时，只需扩展 span 类型，而不是绕开展开器

## 普通道路地形平滑

普通道路不再直接硬贴地，而是生成适合马车通行的 `targetY` 曲线。

默认策略：

1. 先采样原始地形高度。
2. 对普通道路 span 运行高度平滑。
3. 限制相邻道路采样点的最大坡度，默认可按“每 2 到 3 格水平距离升降 1 格”处理。
4. 如果原地形变化在可接受范围内，道路自然贴地。
5. 如果原地形过陡：
   - 高于 `targetY` 的位置切土并清理 headroom。
   - 低于 `targetY` 的位置补 foundation。
   - 表面保持连续的 `SURFACE` 或必要的 `RAMP`。
6. 普通道路桥梁衔接处使用同一高度曲线，避免入口突然断层。

生成阶段：

- `FOUNDATION`：填方、地基、边缘支撑。
- `SURFACE`：主要道路表面。
- `RAMP`：坡度变化需要 slab/stair-like 表达的位置。
- 清理步骤：清除道路上方阻挡马车通行的 headroom。

## 桥梁结构展开

连续桥梁 span 展开为：

```text
entry ramp -> deck -> exit ramp
```

桥梁规则：

1. 桥面 `deck` 保持稳定目标高度，避免中间随地形上下波动。
2. 入口和出口使用坡面连接普通道路 `targetY`。
3. 坡面优先用 slab bottom/top 或等价块表达，保证视觉和通行连续。
4. 桥墩单独生成 `PIER` phase，不再混在 `DECK`。
5. 栏杆单独生成 `RAILING` phase。
6. 桥面为 `DECK` phase。
7. 如果桥梁跨度过长，程序化生成按固定间距布置桥墩。
8. 如果桥墩落点不合适，记录 warning issue，但不应导致客户端崩溃。

本轮程序化桥梁至少应覆盖：

- 桥面
- 入口坡面
- 出口坡面
- 桥墩
- 栏杆
- 必要清理空间

## 预览与实际建造一致性

`RoadNodeExpansionResult` 同时提供：

- `previewBlocks`：客户端 ghost preview 可用的轻量块列表。
- `buildSteps`：实际施工队列使用的 `BuildStep` 列表。

两者必须来自同一次结构展开逻辑，而不是两个独立编译器。

预览可见阶段应包含：

- `SURFACE`
- `RAMP`
- `DECK`
- `PIER`
- `RAILING`
- `STREETLIGHT`

`FOUNDATION` 是否显示可由 UI 选项控制；默认可以隐藏深层 foundation，但不能因此影响实际 build steps。

## 数据流接入

目标数据流：

```text
RoadPlannerScreen / AutoComplete
  -> 编辑 nodes + segmentTypes
  -> submitPreviewWithSettings
  -> RoadPlannerPreviewRequestPacket
  -> RoadPlannerBuildControlService.startPreview
  -> RoadNodeStructureExpander.expand(PREVIEW)
  -> previewBlocks

RoadPlannerConfirmBuildPacket
  -> RoadPlannerBuildControlService.confirmPreview
  -> RoadNodeStructureExpander.expand(BUILD)
  -> buildSteps
  -> existing construction queue
```

实现时：

1. `RoadPlannerScreen` 保留编辑和提交职责，不直接生成结构。
2. `RoadPlannerPreviewRequestPacket` 负责安全读取请求并触发展开。
3. `RoadPlannerBuildControlService` 保存 canonical preview input，确认时重新用同一输入展开 build steps。
4. `RoadPlannerBuildStepCompiler` 瘦身为 adapter，内部调用 `RoadNodeStructureExpander`，之后可以逐步删除重复逻辑。
5. `RoadPlannerPathCompiler` 不再作为最终结构来源，只保留路径/候选辅助角色。

## 错误处理

展开器不向客户端抛出导致崩溃的异常，而是返回 `issues`：

- `ERROR`：路径无法展开，不能进入施工队列。
- `WARNING`：已降级或自动修复，可以继续预览/建造。
- `INFO`：普通提示。

示例 issue：

- 路径节点不足。
- segment type 缺失，已按普通道路补齐。
- 地形过陡，已进行道路平滑和填方。
- 桥梁跨度较长，已按程序化桥墩间距分段。
- 桥墩落点异常，已跳过该桥墩或降级处理。

UI 可以显示这些信息，但施工安全判断以 `ERROR` 是否存在为准。

## 测试策略

### 单元测试

新增 focused tests 覆盖展开器：

- 平路普通道路生成连续 `FOUNDATION + SURFACE`。
- 陡坡普通道路产生平滑 `targetY`，坡度不超过设定上限。
- 陡坡道路需要切土/填方时生成对应 build steps。
- 桥梁 span 生成 entry ramp、deck、exit ramp。
- 桥墩使用 `PIER` phase。
- 栏杆使用 `RAILING` phase。
- 混合路径 `ROAD -> BRIDGE -> ROAD` 高度连续。
- 缺 segment type 和重复节点能被标准化处理。

### 集成测试

- 手动画路和自动补全提交同一形态输入。
- `RoadPlannerPreviewRequestPacket` 和 `RoadPlannerConfirmBuildPacket` 通过同一展开器。
- `previewBlocks` 与 `buildSteps` 的可视主体一致。
- 预览过滤包含 `RAMP`、`PIER`、`RAILING`，桥梁主要结构不会被过滤掉。

### 游戏内验收

- 平地手动画路可以创建道路结构。
- 山坡手动画路会平滑，马车不会被剧烈坡度卡住。
- 河流或峡谷桥梁具备桥面、坡面、桥墩和栏杆。
- 自动补全道路与手动画路的结构风格一致。
- 预览里能看到最终会建造的主要结构。
- 异常路径不会让客户端崩溃，会显示可理解的问题信息。

## 实施顺序建议

1. 新建展开器结果模型和基础输入标准化。
2. 添加 centerline 和 span classifier。
3. 添加普通道路高度平滑与普通道路 step emitter。
4. 添加程序化桥梁 emitter，确保 `RAMP`、`DECK`、`PIER`、`RAILING` 分 phase 输出。
5. 让 `RoadPlannerBuildStepCompiler` 通过 adapter 调用展开器。
6. 接入 preview request 与 confirm build。
7. 更新预览过滤规则。
8. 补齐单元测试和集成测试。
9. 运行 `compileJava`、相关 tests，并打 `-all.jar`。

## 验收标准

实现完成后应满足：

- 手动道路和自动补全道路共用 `RoadNodeStructureExpander`。
- 普通道路会针对陡峭地形平滑处理，并生成可通行道路结构。
- 桥梁包含桥面、坡面、桥墩、栏杆。
- 预览和实际建造结构一致。
- 桥墩、坡面、栏杆在预览中可见。
- 无法展开的输入返回 issues，不导致客户端崩溃。
- 现有未相关的小地图/Town/Nation UI 改动不被重置或误改。
