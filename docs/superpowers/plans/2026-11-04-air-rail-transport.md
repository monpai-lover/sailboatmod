# Air Rail Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking. The user has chosen air-rail motion and removal of the automatic-flight dependency on Flight Control.

**Goal:** 用玩家自己的飞机完成外观自然、无须安装飞控学的机场自动运输及真实交货。

**Architecture:** 新增独立航线引导任务、纯几何采样器和Sable受控运动桥。注册控制器复用现有机场规划、区块加载、预约、市场库存与唯一结算；旧原生飞控任务不用于模拟本模式进度。

**Tech Stack:** Java21、NeoForge1.21.1、Sable2.0.6/Create Aeronautics1.3.2、JUnit、现有机场/市场系统。

## 1. 冻结旧实飞与确认原生受控运动接口

- [x] 普通冻结旧实飞，保留task/profile、轨迹和清理回执；不得拼接为成功航次。
- [ ] 核对安装的Sable公开pose/pipeline/velocity与网络、乘员、子机体语义；仅对本机体接管，禁止维度全局暂停。
- [ ] 保存当前54输入基线；保留地图与berth-collision及其他已有改动。

## 2. 纯路径和任务进度

Files: `transport/air/rail/RailAircraftPath.java`, `RailAircraftMission.java`, respective tests.

- [ ] 先写失败测试：任意有限机体参考点，弧长恒速、地面拐角留在走廊、跑道直线不切角、空中俯仰/倾斜有界、最后精确停位；NaN/零长/超界输入拒绝。
- [ ] 最小实现：不可变段/累计弧长、分段采样、阶段与距离cursor；每游戏tick最多推进对应时间，不补离线进度。
- [ ] 保存/读回机体和机场修订/assignment、路径与cursor；暂停/地形未知/预约失效不得增加距离，实际停稳门不由期望位置代替。
- [ ] 运行新测试，检查保存进度、跨阶段连续性与重复完成幂等。

## 3. 独占原生运动桥

Files: `compat/aeronautics/` 下独立引导运动桥与必要的optional hook，配套合同/纯生命周期测试。

- [ ] 先写失败测试：同一物理机体只允许一个owner；过期/移除/手动接管/错误维度不得驱动；无Sable/未知ABI返回明确不可用。
- [ ] 通过已核对的Sable接口驱动实际机体，保持网络、子结构和玩家跟随；不与原生FC/其他控制器同时驱动。
- [ ] 每步取得实际pose读回；完成、取消、暂停、重启重绑定均有有界清理。实际暂停不得受重力继续下坠。
- [ ] 运行合同与生命周期测试，独立审查optional-mod加载边界。

## 4. 注册控制器与真实物流

Files: `RegisteredVehicleControllerBlockEntity.java`、机场选择/绑定辅助类、客户端提示及对应测试。

- [ ] 新任务在tick、启动、resume、pause、stop、remove、NBT和交货入口独立分支；自动固定翼不再以FC、轮组校准、引擎推力为先决条件。
- [ ] 用实际机体包围盒、控制器参考点及机场路线挑选可容纳的机位/滑行道/跑道；沿用有界地形检查与预约，不冒充原生测量能力。
- [ ] 只在同一有效assignment、真实机体进入机位、姿态/速度符合并停稳40tick后，调用现有库存转移和市场结算；错误机位/库存满/重复回调不得重复交货。
- [ ] 更新产品提示说明航线引导及控制器机头方向；删除自动模式中误导性的飞控/校准阻断，不另建机场编辑器。
- [ ] 完成有FC与无FC相关测试，再运行完整releaseMod及所需既有检查。

## 5. 游戏内回归与交付

- [ ] 正常停止/归档隔离试飞世界并恢复真实地面备份；部署最终候选，两端版本一致。
- [ ] 无FC正常启动；真实机体沿完整滑行→起飞→转弯→降落→机位路径运动，观察网络和乘员外观。
- [ ] 原4小麦订单成功转移且托管款8仅结算一次；核对前后库存/订单状态与真实轨迹。
- [ ] 另外两种自建布局独立装配/注册运行；每种最终布局连续3单，再检查暂停、重启和障碍/占用，逐项记录。
- [ ] 交付实际验证的JarJar主包与附包、差异和校验值；未通过项明确列出。

## 2026-11-04 fix55 runtime checkpoint

- `releaseMod -PfixIndex=55` passed: 640 suites, 3282 tests, zero failures/errors, two existing skips. Main JarJar SHA256 `08409b1dd2f9ff07bd9c0799ae619ef727c2a51da60aca324855bb77879d00f1`, 28,572,594 bytes; 5 bundled libraries. Market addon retained all 8120 verified Minecraft assets.
- Both isolated server/client normally booted without Flight Control; the test player authenticated and joined. Original body `airframe-a` remains, actual mass now 88 after normal optional-block removal. Actual four wheat, IN_TRANSIT orders and escrow8 remain unchanged.
- Current server server-fix55/client-fix55, normal server frozen for setup. Evidence lives in original worktree `build/live-client-test/fix55-deployment`; exact world-before55 clone and per-file hashes preserved. Main implementation source remains in fixedwing-berth-collision worktree.
- C/D public configuration revisions22/20 now have 48m facilities/corridors and 256m runways. Widening correctly rejected old hold points; C hold56.5 and D hold-1536.5 passed ordinary references/occupancy validation. Named paths/stands retained. Short route uses actual cruise point(-800,-12,560), public speed30, explicit destination D; business rows untouched.
- Original aircraft was moved to C through a separately labelled native public fixture teleport, not a claimed flight. Actual anchor84.94/-57.75/515.58, upright nose matches stand yaw84.637; subsequent normal ticks settled it. No guided flight or delivery has passed yet.
- Actual public START failed. Backend public readback shows available=false/applied=false. Root cause: optional mixin plugin scanned only LoadingModList.modFiles, which excludes nested Rapier GAMELIBRARY, so all three mixins were skipped. Native-interface unit tests alone did not expose this loader boundary. Fix56 plugin contract/fix in progress.
- Fix56 also hardens fresh/rebound lease acquisition against active/unknown foreign FCC state; its new lifecycle tests pass. Geometry-only START admission now queues bounded preflight even for an already-loaded manifest; cargo admission/arrival retain full terrain validation and 40 real stopped ticks.
- Runtime helpers are exact PID/hash bound, fresh receipts only. Original forceload62 must be restored after testing; current setup additionally loaded C/D registry chunks and rectangle48,480..128,552. Temporary wake assertion wake-assertion-a plus older owned wake-assertion-b remain task-scoped. No commits/push this run.

## fix56 flight and finite surface control checkpoint

- Fix56 resolved the actual nested Rapier loading boundary. Installed no-FC server/client accepted ordinary START; actual readback proved 28.62m taxi/HOLD_SHORT, then takeoff, climb and banked CRUISE at distance2710.66m. Original cargo/order/escrow remain in transit; arrival and delivery have not passed.
- User observed continuously spinning ordinary Create bearings. Flight is now paused via the real owner's public PAUSE action with the world running. Do not use global tick freeze with a player tracked on a Sable body: the native frozen-player pose delta can repeat. A separately labelled ordinary owner TP recovered the player; it is not passenger-retention evidence.
- User requires realistic finite surface response and active rudders on community aircraft. Approved implementation: explicit bearing role/neutral/polarity/limits in controller UI, native finite-angle servo independent of motor RPM/Flight Control, pitch/roll/yaw error/rate demands based on actual attitude and short route preview. Bindings survive restart; leases and derivative history do not. Missing/unavailable bound hardware pauses the task. Only explicit inactive parked clear restores ordinary RPM.
- Polartt fixture has two ailerons and two elevator halves, no active rudder. v5 blueprint independently contains a Y-axis rudder bearing at local(14,5,14) and four connected vertical symmetric sails; assembly and actual rudder motion remain to be verified.
- [x] Pure domain roles/profile/control law, native finite-angle servo, client binding UI and actual-attitude extraction tests.
- [ ] Integrate lifecycle/permissions, independent review and complete release57 build.
- [ ] Preserve paused real cruise mission across normal deployment; bind/read actual four Polartt faces; verify finite-angle turn/pitch response.
- [ ] Finish original real four-wheat delivery/unique settlement and automatic return; then v5 active rudder and third layout regressions.
