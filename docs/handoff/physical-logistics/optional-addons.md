# 可选附属模组：安装、复用与接入边界

2026-10-30，Minecraft 1.21.1 / NeoForge。用户选择优先安装并复用现成附属模组，作为 SailboatMod 的非必需依赖。本页补充[物理物流设计](../../superpowers/specs/2026-10-30-physical-logistics-design.md)，不把安装成功等同于运输适配已经完成。

## 采用方式

附属模组负责它们已经提供的飞控、车辆路线执行、传感器、船舶部件和人工驾驶；SailboatMod 保留注册方块、订单、货舱、机场/港口/驿站、设施预约和网页任务。优先编写薄适配层，只有发布版本确实缺少所需能力时才补接口或扩展算法。

“可选”是相对于 SailboatMod 主模组而言。未安装这些模组时，基础船、马车、市场及网页仍按现有逻辑运行；使用某一附属模组时，必须满足它自身的依赖并完成硬件配置。此次不把八个候选全部添加为 NeoForge 必需依赖，也不把上游代码或资源打进主 JAR。

安装目录只提供实际发布文件。运行时仍须分别判定：模组存在、接口匹配、载具配置有效、当前控制权可用。客户端和服务端安装要求以发布包为准，不能把“可选前置”理解为只在服务端安装带方块的模组。

## 选定版本与用途

下列版本是本次核对和隔离安装的固定组合，不表示永远追随最新版本。文件、官方地址及校验值见 [安装清单](optional-addons.lock.json)。

| 模组 | 本次版本 / mod ID | 安装后可直接使用 | Sailboat 接入现状与下一步 |
|---|---|---|---|
| [Flight Control](https://modrinth.com/mod/create-flight-control/version/FSr0O2k8) | 0.8.3 / `create_flight_control` | 飞控、导航终端、航线卡，固定翼起降程序 | **现有反射桥已实现**。登记器使用同结构真实终端和卡，不伪造第三方控制者身份；仍需三种机型实飞验收 |
| [Automated Logistics](https://modrinth.com/mod/create-aeronautics-automated-logistics/version/ESwTkggq) | 0.7.0 / `create_aeronautics_automated_logistics` | 自身的录线、日程、停靠和离线运输玩法 | 尚无 Sailboat 订单驱动适配。先评估独立的 AL 日程后端，再处理 FC 飞行中的离线交接；安装不会解除 `sable_custody_transaction_missing` |
| [Avionics](https://modrinth.com/mod/create-avionics) | 0.7.0 alpha / `create_avionics` | CC 外设、雷达、结构传感器和执行器访问 | 优先只读感知适配；公开 Java 读数也须核验线程、坐标、有效期。Lua 控制不会自动进入我们的控制权管理 |
| [Automatics](https://modrinth.com/mod/create-automatics) | 1.0.1 / `createautomatics` | 车辆录线、速度和停站配置 | **陆运优先适配候选**。实际 JAR 有路线设置、启停和状态方法；先复用其路线执行，再补订单、设施准入和完整停泊判定 |
| [Deep Seas](https://modrinth.com/mod/create-deep-seas/version/3.0.0) | 3.0.0 / `create_submarine`；内含 `create_high_seas` 0.1.1、`create_abyss` 1.0.0 | 船舶/潜艇部件、压载、定深、海床跟随、船壳诊断 | 水运优先复用硬件和可核实的诊断/控制；不把定深当作通用码头导航。无需另装 High Seas |
| [Aeroworks](https://modrinth.com/mod/create-aeroworks) | 1.5.0 / `aeroworks` | 陀螺、操纵杆、模块控制台、驾驶盘和油门 | 可直接作为机械部件/人工驾驶界面；自动与人工控制交接仍须适配验证 |
| [Synaxis](https://modrinth.com/mod/synaxis) | 1.5.0 / `synaxis` | Lua、电路、执行器和驾驶控制设施 | 可选高级编程后端；不作为第一版所有载具的共同前置 |
| [World Engine](https://www.curseforge.com/minecraft/mc-mods/world-engine/files/9229628) | 0.1.3 / `worldengine` | 作者提供的 Sable 空间查询/物理优化 | 单独安装对照测试；不增加导航接口，不承诺当前组合的性能收益 |

发布资料存在几处差异，安装清单已按实际包核对：

- AL 0.7.0 发布说明明确新的 Advanced Transponder / Drive Modules 尚未公开可用。JAR 含 `MODULE_OUTPUT` 不能证明玩家已能使用这种模式。
- Synaxis、Automatics 的 Modrinth 版本依赖列表为空，JAR 内却有必需依赖；不能只按网页下载后直接认定完整。
- Synaxis 需要 `sable_schematic_api >= 0.4.1`。本次使用已改名的 **Sable Photomancy 1.0.1**，mod ID 仍为 `sable_schematic_api`。
- Avionics 需要 **CC: Tweaked**；本次锁定 1.120.2。Avionics 0.7.0 和这个 MC 分支的 CC 发布元数据均标为 alpha。
- Deep Seas 当前还有较新的 3.4.5 beta，但它增加客户端 Sodium 依赖和渲染改动，本次不静默替换用户指定的 3.0.0。
- Aeroworks 网页前部部分 planned 条目落后于发布包；1.5.0 JAR 已包含模块控制台及 wheel/yoke/throttle。

## 安装顺序

1. 使用独立的 1.21.1 NeoForge 客户端和服务端测试实例，先保持现有 Sailboat 基础依赖完整。此次服务端基线：NeoForge 21.1.265、Create 6.0.10、Sable 2.0.6、Aeronautics bundled 1.3.2、GeckoLib 4.9.3、LDLib2 2.2.42。
2. Aeronautics bundled 1.3.2 已内含 Aeronautics、Simulated、Offroad；**不内含 Sable**。保留独立 Sable JAR，避免重复安装内嵌的三个组件。
3. 按实际玩法安装候选及其前置。空运首先装 FC；传感器玩法加 Avionics + CC；轮式路线玩法加 Automatics；船舶部件加 Deep Seas；Synaxis 另需 Photomancy。AL 可独立录线测试，World Engine 另做有/无对照。完整候选集合只用于共存测试，不是所有玩家的最低安装要求。
4. 七个内容模组及 CC、Photomancy 均按客户端/服务端匹配安装。World Engine 此次只实测专用服务端，未验证只装服务端时的客户端联机；客户端测试时按对应版本单独核验。市场网页 addon 继续使用其原有服务端安装方式。
5. 当前 Sailboat fix_15 是协议 **17**。原整合包 fix_12 是协议 **15**；不要把当前完整主模组 JAR 配给旧 fix_12 客户端。旧服仅两类替换的 Sable 热补丁与本次整套升级是两种不同安装路径，见[服务端修复说明](../SABLE_DEDICATED_SERVER_REFLECTION_FIX.md)。
6. 启动后核对实际加载版本与依赖，再进入新测试世界配置机械。依赖解析通过和服务端 `Done` 不能代替客户端联机、动力动作、运输或重启验收。

固定清单不是完整整合包：它列出八个候选和两个新增前置，原有 NeoForge、Create、Sable、航空学及 Sailboat 基础依赖仍由测试实例提供。下载自官方发布地址，不改写、解包重组或重新发布第三方 JAR。

## 同车控制与网页边界

当前代码能核查 FC 任务、卡片、结构和关联状态，但**尚无覆盖所有第三方控制者的统一仲裁**。在统一控制权接入前，玩家必须在同一载具上只启动一套自动驾驶：FC 航线、AL 日程、Automatics 路线或自编 Lua 控制不能同时驱动同一组执行器。

字节码核对发现 Avionics 和 Automatics 都在 Offroad 车轮 `getSteeringSignal` 的入口注入可取消逻辑。两者可以安装共存，但不得在 Automatics 自动驾驶的车上同时启用 Avionics Lua 转向覆盖。只读雷达和传感器可以作为适配目标；读取动作不得顺便改变油门、转向或制动。

网页继续显示已经接入 Sailboat 的真实任务。AL 自己的日程、Automatics 自己的路线、雷达目标或 Lua 任务不会仅因安装就出现在物流订单中。后续必须由相应适配器提供任务关联、实际位置、控制者、数据年龄和故障原因，再按现有权限发给网页；不由“已安装”合成运输状态。

## 后续实施顺序

1. 保持现有 FC 真实终端/卡接入，按三机型完成机场与完整货运验收；补足共享的运动、货物、设施和控制权边界。
2. **Avionics 只读感知 → Automatics 陆运后端**：先验证雷达/车轮读数、旧数据拒绝和可选依赖缺失，再做路线导入、启停确认、人工接管、到站停稳与订单回执。不能以 `isRouteActive == false` 单独认定货物到达。
3. **Deep Seas 水运后端**：优先船壳/吃水/推进硬件及定深能力，再补水面航线、港池与靠泊。声纳渲染类多数在客户端，专用服务端只使用核实过的服务器读数接口；沿用精确签名查找，增加客户端类缺失的回归测试。
4. **AL 独立日程适配**：让登记载具显式选择 AL 执行方式，由 AL 独占运动；Sailboat 只在确认站点映射、装卸归属和幂等回执后关联订单。此路线与“FC 飞机空中切换至离线航程”分开验收，不要求先重写 AL 的回放器。
5. **FC 混合远航与优化**：继续独立处理 Sable 持久独占托管、物理移除确认、燃料和固定翼动量恢复。World Engine 对照测试使用相同存档副本、机械、载荷、视距与路线，记录 tick、保存/恢复延迟及故障；没有实测收益就不列为推荐必装。

原有 M0–M6 的安全和一致性验收不变；M2/M3/M4 的驱动实现优先顺序改为**现成后端适配 → 最小必要扩展 → 缺失能力的自有实现**。不得在调度线程里再启动一套油门/转向循环与已选后端竞争。

## 本次验证记录

下载元数据、JAR 依赖和接口核验已完成。服务端组合测试结果见 [隔离安装验证](optional-addons-verification.md)。当前没有进行客户端联机、实体机械驾驶、货物交付或性能基准，不将安装与启动结果写成这些玩法已经通过。

组合启动中发现 FC 工具枪兼容 Mixin 未应用，以及基线已有的 LDLib/EMI 分侧日志和停服驻留；均已在验证页列出。部署到实际游玩环境前仍需处理和复核相关交互，不将这组下载清单标为完整兼容整合包。
