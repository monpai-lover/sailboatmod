# Road Planner 水域桥梁、坡段与远端预览修复设计

日期：2026-05-04
分支：feature/road-planner-rebuild

## 背景

当前 Road Planner 的道路结构展开已经统一走 `RoadNodeStructureExpander`，手动道路和自动补全都会进入同一套预览/确认建造链路。但实测暴露出三个问题：

1. 一些小水域被过度当作桥梁处理，桥型没有按水域规模区分。
2. 坡段没有稳定生成，尤其桥头坡面和普通道路高度变化段表现不符合预期。
3. 桥梁结构展开到一定距离后预览不可见，需要区分是结构未生成还是客户端渲染裁剪。

## 目标

- 使用宽度 + 深度组合判定水域桥梁类型。
- 窄水域仍然使用 RoadWeaver 桥梁模式，不回退为填路。
- 普通道路和桥梁都要稳定生成坡段，保证马车通行。
- 保证远端桥梁结构可预览：近处显示方块模型，远处至少显示线框/路径骨架。
- 保持手动道路和自动补全共用同一套结构展开逻辑。

## 非目标

- 不重写 Road Planner UI。
- 不改 Town/Nation 小地图逻辑。
- 不引入新的桥梁工具交互模式。
- 不把窄水域改成填路或涵洞；窄水域仍走桥梁结构。

## 设计

### 1. 水域分类规则

RoadPlanner 自动展开前先分析路径上的连续水域 span。

规则如下：

- 连续水面 `< 4 blocks`：视作噪声，不切桥。
- 连续水面 `4–24 blocks`：生成 `BRIDGE_SMALL`，使用 RoadWeaver 小桥结构。
- 连续水面 `> 24 blocks`：生成 `BRIDGE_MAJOR`，使用 RoadWeaver 大桥结构。
- 最大水深 `>= 3 blocks`：即使水面较窄，也升级为 `BRIDGE_MAJOR`。

`RoadPlannerWaterCrossingSplitter` 负责输出更准确的 `SplitNode`/segment type。`RoadPlannerRouteExpander.segmentTypeForConnection(...)` 不能再只因任意采样触水就提升为桥，需要复用同一套 span 分类结果或相同阈值，避免后处理重新把噪声水样变成小桥。

### 2. 桥梁结构模式

桥梁仍统一是桥节点，但结构展开阶段区分小桥和大桥：

- `BRIDGE_SMALL`
  - 桥面高度低于大桥。
  - 入口/出口坡段必须存在。
  - 对短 span 优先降低桥面高度以保留坡段空间。
  - 可减少桥墩数量，但仍使用 RoadWeaver 桥梁展开，而不是普通路。

- `BRIDGE_MAJOR`
  - 桥面高度满足深水/宽水面通行要求。
  - 入口坡、主桥面、出口坡、桥墩、栏杆都应生成。
  - 深水窄桥也使用大桥规则。

### 3. 坡段生成

坡段生成改为显式 profile，而不是只依赖相邻点高度临时判断：

- 普通道路：`RoadHeightProfileSmoother` 输出目标高度后，只要路面高度沿路径发生变化，就把对应 footprint 标为 `RAMP`。
- 小桥/大桥：`BridgeStructureEmitter` 生成桥梁高度 profile 时，必须计算入口 ramp range、deck range、出口 ramp range。
- 如果桥长不足以容纳默认坡长：
  - 小桥优先降低 `deckY`。
  - 大桥保留最小水面净空，但允许压缩坡长。
  - 仍要生成至少一段可见 `RAMP`，避免桥头直接竖跳。

### 4. 远端预览

先通过测试或诊断区分两类问题：

- 结构数据已生成，但客户端不显示：修 `RoadPlannerPreviewRenderer`。
- 结构数据未生成：修 `RoadNodeStructureExpander` / `BridgeStructureEmitter`。

渲染设计：

- server packet 和 client preview state 保留完整 ghost blocks。
- 近处：渲染方块模型。
- 远处：不再完全裁掉桥梁，至少渲染线框或路径骨架。
- 对 `MAX_PREVIEW_RENDER_DISTANCE`、`MAX_MODEL_RENDER_DISTANCE_SQR`、`MAX_WIREFRAME_RENDER_DISTANCE_SQR`、`MAX_PREVIEW_RENDER_BLOCKS` 做分层处理：
  - 方块模型可以继续限距限量。
  - 桥梁线框/路径骨架应覆盖整条规划路线。

### 5. 数据流

1. UI 收集手动节点或自动补全节点。
2. `RoadPlannerRouteExpander` 展开稀疏节点并分析水域 span。
3. 输出规范化 nodes + segmentTypes。
4. `RoadPlannerBuildControlService.previewExpansion(...)` 和 `RoadPlannerBuildStepCompiler.compile(...)` 都调用 `RoadNodeStructureExpander`。
5. `RoadNodeStructureExpander` 根据 segment type 生成普通道路、坡段、小桥、大桥。
6. preview packet 同步完整 ghost blocks。
7. client renderer 分层渲染近处模型和远处线框/骨架。

## 测试计划

新增或更新测试：

- `RoadPlannerWaterCrossingSplitterTest`
  - 3 格水域不切桥。
  - 4–24 格水域生成 `BRIDGE_SMALL`。
  - 大于 24 格水域生成 `BRIDGE_MAJOR`。
  - 窄但深水域生成 `BRIDGE_MAJOR`。

- `RoadPlannerRouteExpanderTest`
  - 噪声水样不会被 `segmentTypeForConnection` 重新提升为桥。
  - 小水域桥段保持 RoadWeaver 桥梁 segment type。

- `RoadNodeStructureExpanderTest`
  - 小桥生成 `RAMP` 和 `DECK`。
  - 大桥生成 `RAMP`、`DECK`、`PIER`、`RAILING`。
  - 普通道路高度变化生成 `RAMP`。

- `RoadPlannerPreviewRendererTest`
  - 远端桥梁 ghost block 不应导致整段预览消失。
  - 远距离时至少保留线框/路径骨架。

## 验收标准

- 小水塘/噪声水样不会再生成桥。
- 窄水域生成小桥，不被当成大桥。
- 深水窄水域和宽水域生成大桥。
- 桥梁入口和出口可见坡段存在。
- 普通道路陡峭地形有平滑坡段或 ramp 表现。
- 桥梁远端在规划预览中不会完全消失。
- `compileJava`、相关单测、全量 `test`、`jarJar` 通过。
