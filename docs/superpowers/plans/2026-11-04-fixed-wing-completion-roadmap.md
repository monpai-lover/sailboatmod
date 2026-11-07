# 固定翼自动物流剩余三阶段执行计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Track execution with checkboxes and preserve actual runtime evidence.

**Goal:** 先解决当前飞机的连续转弯失高，再跑通机场之间的真实自动运输，最后验证不同玩家自建固定翼及重复、载荷、重启场景。

**Architecture:** 沿用原生气动、飞控和机场地面任务状态机；兼容层提供可证明的环境、能力和控制权信息。三个阶段依次验收，代码检查与实飞结果分开记录。机场设施准备、蓝图检查和离线测试可并行，后续机型的实飞须等当前飞机完成运输闭环。

**Tech Stack:** Minecraft 1.21.1、NeoForge 21.1.265、Create Aeronautics/Sable、Flight Control 0.8.3、Java 21、SailboatMod 机场可视化规划与物流系统。

执行状态更新（2026-11-04，fix54）：同帧默认/被动环境修复已部署，完整构建625个suite/3129测试为0失败；真实native合同均未跳过。ferry54-02实际起飞并连续离地37.8个native秒，published与forRead环境均可用且配置键连续，配平自然取得held/verified。WP0→1（两个共线同高点）后仍失高，按边界普通冻结，未通过转弯/返场/交货。已保存失败世界和逐舵面冻结缓存；完整恢复真实地面备份作为独立诊断布场，不拼接航次成绩。新一轮先捕获交接前后逐substep实际力。阶段2/3仍未验收。完整构建命令和53基线归档说明见 `build/fixed-wing-trim-environment-54/release54-recipe.md`。

## 一、起点与完成口径

当前确认的事实：

- fix52 的参考飞机自动起飞、连续离地约177个原生物理秒，推进到航点索引5后失高，测试在碰地前停止。该航次没有完成降落或交货。
- 该航次是原生 ferry：RVC 未运行商业任务，groundMission 为空。它不能计入商业物流通过数。
- 默认环境兼容代码已有部分真实原生合同测试，但当前飞机的非 NONE 被动预测器仍被拒绝，尚未覆盖本机完整气动路径，也未部署为失高修复。
- 已读到本机30个普通受控 surface，以及被动预测器中的52个 context：46个 Create 帆、6个对称帆。需要验证这些实际 provider 的完整语义。
- 飞机已取消任务并真实停稳，质量95、四轮接地。原4份小麦及订单、托管款记录保持完整。
- 停机布场时，原发动机的27个煤转存到真实玩家背包槽0，发动机剩余燃烧时间791已记录。线速度耦合器的原配置已恢复。当前原商业航线卡尚未恢复；再次启动前必须完成相应恢复和读回。

依据：

- fix52实飞终点（本地证据 `build/live-client-test/fix52-deployment/ferry52-01-stop.json`）
- 实际恢复和货物核对（本地证据 `build/live-client-test/fix52-deployment/ferry52-01-recovery-and-custody.json`）
- 实际被动provider读取（本地证据 `build/live-client-test/fix52-deployment/runtime-01/server-fix52-cached-native-predictor-v1/receipts/cached-native-predictor52-01.jsonl`）
- 当前环境兼容实现的限制（本地证据 `build/fixed-wing-trim-environment-53/implementation53-01.json`）

总体通过条件：参考机与另外两种不同布局飞机，均使用同一套按实测能力工作的逻辑完成完整任务。每种飞机在最终接受的构建和布局上连续完成3单真实载货交付；另完成空载、载荷变化和重启的定向检查。功能变更后只重跑受影响的验收行。

## 二、阶段1：解决连续转弯失高

### 1.1 固定基线和试飞准备

- [x] 保存最终候选代码、原生依赖、JAR和配置的校验值，确认此前地图修复和 berth-collision 基础仍包含在最终候选中。
- [x] 通过公开游戏接口恢复需要的燃料及正常配置，核对真实发动机输入、控制面、起落架和当前机体绑定；恢复操作与试飞成绩分别记录。
- [ ] 为新测试正常编制航线并取得新的 task/profile 回执。沿用参考机的载荷、动力和飞控配置进行前后对比，每轮只改变已定位的问题。
- [ ] 预先绑定转弯入口的被动记录器。完整观察失败起点的航点4→5以及此前的转弯，不等失高后才补取证。

### 1.2 补齐本机真正使用的默认与被动气动路径

主要文件：

- [DefaultTrimEnvironment.java](../../../src/main/java/com/monpai/sailboatmod/compat/flightcontrol/DefaultTrimEnvironment.java)
- [NativeFixedWingTrimEnvironment.java](../../../src/main/java/com/monpai/sailboatmod/compat/flightcontrol/NativeFixedWingTrimEnvironment.java)
- [TrimEnvironmentFrame.java](../../../src/main/java/com/monpai/sailboatmod/compat/flightcontrol/TrimEnvironmentFrame.java)
- [真实原生合同探针](../../../src/test/java/com/monpai/sailboatmod/compat/flightcontrol/NativeDefaultTrimEnvironmentJarProbe.java)
- [现有技术子计划](2026-11-04-default-trim-environment.md)

- [x] 对确切的 CachedPassiveSailPredictor、PassiveCreateSails$1 和两类实际帆 provider 校验版本、方法声明归属、配置与坐标关系；未知实现保留明确拒绝原因。
- [x] 核对捕获的机体、物理步长、姿态、重心偏移和模型属于同一帧。对可变位置、块状态、方向和压力曲线生成完整数值副本，并在发布前复验。
- [x] 被动通道使用普通压力时，必须纳入真实有效的压力配置；稳定配置键排除每帧世界姿态、即时高度和包装对象身份，避免学习窗不断重置。
- [x] 先增加能复现当前非 NONE 被动路径的失败用例，再做最小实现。覆盖普通帆、对称帆、未知覆写、错机体、错步长、过期帧、变更配置和变更几何。
- [x] 确认真正的原生对象路径通过，axial能力仍保持原始状态。不能用简化模型或stub结果代表当前机体已经覆盖。

### 1.3 验证配平学习与连续转弯

- [x] 独立审查及完整测试通过后，才更新隔离测试服和客户端。
- [x] 普通巡航稳态期间，真实 published 环境和 Sample.forRead 环境都应可用，并绑定当前 body/task/frame。
- [x] 原生学习窗应按物理时间自然累计，产生 held/verified。若仍不学习，先读具体拒绝原因，停止重复同样的长航线。
- [ ] 从真实地面起飞，依次验证直线、左/右90°、180°以及连续转弯，最终覆盖原失败航点及后续普通巡航弯道。
- [ ] 首轮采用固定验收带：平飞及定高转弯高度误差不超过±5格，离弯后5个原生秒内回到±2格；至少连续3个转弯无累计失高，并通过计划内全部巡航转弯。主动爬升、进近下降另按其目标轨迹检查。
- [ ] 速度使用该机型当帧原生指标及V1/VR核对，不将某个固定速度、增益或倾角作为所有机型的通用答案。离地后地面助推冲量始终为零。
- [ ] 若环境和学习通过后仍失高，在下降开始处记录有界实际力、升降舵角度、姿态和速度，区分实际施力与分配器预测，再决定下一项最小修复；不能单凭环境修复认定失高消失。
- [ ] 飞控验证通过后，继续正常返回C跑道并停稳，为商业测试布场。该返场属于组件验证和布场。

**阶段1通过：** 同一真实任务连续通过全部巡航弯道并正常返场；有高度、速度、控制面、配平和零空中助推证据。触发安全停止、重建任务或跳航点均不计通过。

## 三、阶段2：机场到机场完整交货闭环

### 2.1 恢复商业测试起点

- [ ] 原机通过正常飞行及地面驾驶到达C货运机位。没有地面任务的ferry，不得凭空补写 groundMission 或将商业START当作返C入口。
- [ ] 恢复原商业航线卡，逐项核对原订单、4小麦manifest、托管款、两端机场及实际收货库存。恢复期间不结算该订单。
- [ ] 使用真实玩家的机场可视化编辑器检查C/D跑道、出入港滑行线、等待点和机位；它们必须贴合实际地表，按整机净空及转弯能力验证。
- [ ] 机场逻辑方块、仓库与实际地面机位关联正确；不能因仍沿用旧平台高度或旧方块距离而阻断已配置机位的交付。

主要文件：

- [AirportGroundSelection.java](../../../src/main/java/com/monpai/sailboatmod/transport/air/airport/AirportGroundSelection.java)
- [AirportGroundPreparation.java](../../../src/main/java/com/monpai/sailboatmod/transport/air/airport/AirportGroundPreparation.java)
- [RegisteredAircraftGroundMission.java](../../../src/main/java/com/monpai/sailboatmod/block/entity/RegisteredAircraftGroundMission.java)
- [AircraftGroundMission.java](../../../src/main/java/com/monpai/sailboatmod/transport/air/ground/AircraftGroundMission.java)
- [CarrierArrivalService.java](../../../src/main/java/com/monpai/sailboatmod/transport/CarrierArrivalService.java)
- [MarketOrderEscrowService.java](../../../src/main/java/com/monpai/sailboatmod/market/MarketOrderEscrowService.java)

### 2.2 正常START后完整自动执行

依次取得实际状态和轨迹：

`C机位 → 自动选路/预订 → TAXI_OUT → HOLD_LINE → LINE_UP → FC_STARTING → FC_ACTIVE → D进近/接地/滑跑 → 原生释放控制 → TAXI_IN → STAND_STABILIZING → COMPLETE/交货`

- [ ] 系统自动选择连通且适配该机型的路线、跑道和指定机位；保存选择结果、设施修订及预订标识。
- [ ] 在等待线停等占用跑道，对准后完成地面与原生飞控控制权交接，确认取得实际控制权后才放飞。
- [ ] 真实飞抵D，在D选定跑道接地、滑跑和减速；不能以接近机场坐标、路由完成提示或低空飞过代替降落。
- [ ] 原生任务自然完成、实际控制释放后，地面任务接管，沿D入港路线滑到指定机位。
- [ ] 整机进入机位，朝向匹配，真实接地；按现有状态机连续40个游戏tick满足停稳条件：速度≤0.25m/s、角速度≤0.035rad/s、距入港终点≤0.75格。重复旧样本不能累计停稳时间。
- [ ] 通过正常物流入口卸下原4小麦。读回实际收货库存增加、manifest对应项移除、订单完成、托管释放及双方账目守恒；每个订单只结算一次。
- [ ] 沿用已授权的模拟买家和真实服务器订单。验收名称写明“模拟买家下单＋真实飞机运输交货”，不增加第二账号依赖。
- [ ] 核对交货后的返航选择或等待状态；如会自动返航，需事先准备D出港/C入港路线，防止交货后进入不可解释的卡住状态。

### 2.3 定向失败检查

- [ ] 跑道占用、滑行障碍、路径断开或净空不足：停等、重新选择合法路线或给出具体阻断原因，不穿障碍。
- [ ] 飞越机位、错误机位、未停稳、错task或外来控制：不卸货、不结算。
- [ ] 收货库存满、设施修订变化、重复START或重复到达回调：货物和资金守恒，无重复交付或遗留预订；排除问题后可正常继续。

**阶段2通过：** 一条真正商业groundMission从C机位出发，全自动到D指定机位停稳交出原货物，原订单和实际账目完成一次正确结算。有整趟轨迹、状态交接和前后对账。

## 四、阶段3：其他自建固定翼、载荷、重复与重启

主要文件：

- [GroundedFlightReference.java](../../../src/main/java/com/monpai/sailboatmod/transport/air/GroundedFlightReference.java)
- [FlightRoutePlanner.java](../../../src/main/java/com/monpai/sailboatmod/transport/air/FlightRoutePlanner.java)
- [AircraftGroundMissionNbt.java](../../../src/main/java/com/monpai/sailboatmod/transport/air/ground/AircraftGroundMissionNbt.java)
- [FlightRecoveryPolicy.java](../../../src/main/java/com/monpai/sailboatmod/transport/air/FlightRecoveryPolicy.java)
- [已有机队检查清单](2026-11-04-fixed-wing-fleet-regression.md)

### 3.1 最小机型矩阵

| 机型 | 要覆盖的区别 | 必须取得的结果 |
| --- | --- | --- |
| 当前Polartt参考机 | 四轮、现有控制面及原订单 | 完整商业闭环与连续性基准 |
| 已取得的plane_v5 | 三轮、不同轮高、五轴承和不同机身尺寸 | 独立建造/注册/校准后通过同一流程 |
| 另一架独立自建固定翼 | 不同翼面/尾翼/轮距及重心布局 | 验证算法使用实测能力，不依赖参考机坐标或UUID |

- [ ] 每架正常建造、装配并记录蓝图来源和必要改装；取得独立机体标识、质量、重心、动力、控制面及轮组读回。
- [ ] 根据该机实测起飞速度、旋转能力、刹停距离、转弯半径和整机占地，重新选择可用机场路线。缺少必要飞行能力或设施不适配时，起飞前给出具体原因。
- [ ] 每架检查空载和真实载货。若原生库存不参与质量计算，明确记录，并通过正常建造的配重布局验证较高质量与偏心，不写质量缓存。
- [ ] 每架在最终接受的布局上连续完成3单载货运输，使用各自独立订单；包含当前参考机在内，最低9次完整载货交付。
- [ ] 燃料、能量不足或能力下降时，不允许仍沿用上一架/上一次的有效能力证明。
- [ ] 大机型若需要扩大机位或滑行道，须通过可视化配置及真实地形检查；加载范围不足时采用合法分段加载或等待，不把未知区块视为无障碍。

### 3.2 重启与恢复矩阵

| 正常重启的时点 | 正确结果 |
| --- | --- |
| 预检/选路等待 | 订单和货物保留，重新取得能力、地形和预订证明 |
| 滑行/等待线 | 重建同一机体及设施绑定；确认接地和控制权后通过正常RESUME恢复 |
| 飞行中 | 原生未持久化航线进度时，明确要求恢复处理，禁止自动从头重放整张航线卡；保留货物/订单并走正常恢复流程 |
| 到港滑行/机位停稳中 | 重新证明入港路线、机位和接地；停稳时间重新累计 |
| 已完成交货 | 保持已完成和已结算状态，不能再次发货或扣款 |

- [ ] 参考机覆盖上述各状态；其他每架至少通过停机后的正常重启、配置读回及一次新的正常出发和交付。
- [ ] 手动接管、动力中断、重复回调和中途取消后，没有双重控制、重复助推授权、重复结算或残留占位。

**阶段3通过：** 三种布局均完成自己的连续3单及相应载荷/重启检查，给出逐机型、逐场景结果；玩家自建机体通过统一注册和能力识别进入同一执行流程。

## 五、验证命令与证据管理

以下命令属于执行阶段，本次制定计划不运行构建或试飞。

阶段1原生合同及兼容测试：

```bash
cd "$(git rev-parse --show-toplevel)"
env JAVA_HOME="${JAVA21_HOME:?Set JAVA21_HOME to a Java 21 installation}" \
SAILBOAT_NATIVE_FLIGHT_CONTROL_JAR="${TEST_WORKTREE:?Set TEST_WORKTREE}/build/optional-addons-2026-10-30/live-client-server/mods/create_flight_control-neoforge-0.8.3.jar" \
SAILBOAT_NATIVE_JOML_JAR="${SAILBOAT_NATIVE_JOML_JAR:?Set path to JOML 1.10.5}" \
SAILBOAT_NATIVE_DEFAULT_CLASSPATH_FILE=build/fixed-wing-trim-environment-54/native-classpath-release54.txt \
./gradlew test --tests '*NativeDefaultTrimEnvironmentJarContractTest' --tests '*NativeTrimEnvironmentJarContractTest' --tests '*NativeFixedWingTrimEnvironmentTest' --tests '*TrimEnvironmentFrameTest' --tests '*PitchBootstrap*' --no-daemon
```

预期：相关测试通过，两个真实原生合同suite均不得跳过。新的被动provider回归纳入同一合同suite。

阶段2/3状态机、机场、对账与恢复测试：

```bash
cd "$(git rev-parse --show-toplevel)"
env JAVA_HOME="${JAVA21_HOME:?Set JAVA21_HOME to a Java 21 installation}" \
./gradlew test --tests '*AirportGround*' --tests '*AirportLongRunwayTaxiTest' --tests '*RegisteredAircraftGround*' --tests '*RegisteredGround*' --tests '*GroundRecoveryIngress*' --tests '*MarketOrderEscrowTest' --tests '*FlightRecoveryPolicyTest' --tests '*FlightResumePolicyTest' --no-daemon
```

预期：对应的状态、机位停稳、保留货物、唯一结算及恢复分支通过。新增实际失败回归先RED，再修改并GREEN。

- [ ] 每次候选部署前冻结源输入，运行完整releaseMod及全量测试，校验实际JarJar产物、内嵌依赖、类字节、资源与两端版本一致。原生合同环境变量沿用上面的真实依赖配置。
- [ ] 每个实测记录构建校验值、机型/布局、body/task/profile、机场修订、载荷与机体能力、实际轨迹和业务前后状态。
- [ ] 成功航次必须连续；普通冻结后发生原生任务重建的片段不能拼成成功航次。安全停止或人工恢复单列为失败后的布场。
- [ ] 失败触发既定安全停止后保留第一现场；先核对原因再最小修复。重复同一失败而没有新证据时，改进诊断，不继续盲目换参数。
- [ ] 对仍在运行的观察器和临时加载保持边界与清理收据；回归结束恢复受保护的原有62区块集合。
- [ ] 验证全程不通过改位置/速度/质量、空中额外助推、写入任务进度、强制学习状态或伪造交货来制造通过。

## 六、每阶段交付和汇报

| 里程碑 | 交付 | 进入下一阶段的条件 |
| --- | --- | --- |
| 阶段1 | 本机气动兼容修复、真实配平/高度/转弯轨迹、完整返场结果 | 连续转弯保高与返场实测通过 |
| 阶段2 | C→D自动选路、起降、滑行机位视频/轨迹及原订单对账 | 一整单真实运输和唯一结算通过 |
| 阶段3 | 三种布局验收矩阵、至少9次载货交付记录、载荷/重启/失败恢复结果 | 每架每项有实际证据，未通过项逐项列明 |
| 最终包 | 实际验证的主模组JarJar包及附包、配置说明、源码差异、已知限制和校验值 | 包内容与最终验收构建一致 |

汇报使用“当前阶段、已通过、未通过、下一项证据”四项，不用代码测试数量代替飞行完成度。阶段1通过前，剩余工期以实际阻断为准；不把后两阶段写成已完成或给无依据的完成百分比。
