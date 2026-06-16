# 帆船窄河道卡死自救 + webmap 阻塞告警 + 载具破坏兜底 —— 任务恢复文档

> 生成时间：2026-06-16　分支：`feature/road-planner-rebuild`
> 项目：F:\Codex\sailboatmod（Minecraft Forge 1.20.1，package `com.monpai.sailboatmod`）

---

## 0. 一句话现状

代码改动**已基本完成**（A/B/C 三块全做完），**唯一卡在最后一步**：往两个 lang 文件加 2 条 key（zh_cn 那条 Edit 工具一直无返回卡住）。**尚未构建、尚未推送。**

恢复后只需：补完 lang 两条 → `./gradlew build -x test` → 推送。

---

## 1. 需求背景（用户原话）

1. **窄河道物理卡死**：帆船自动航行经过较窄河道时被两岸地形/方块物理夹住，引擎推进但船不动、`dist` 不缩小，永久卡死。用户补充「现在是在比较窄的河道里面容易物理卡死」。
2. **卡死阻塞要在 webmap 更新状态**告知玩家去救。
3. **载具被破坏要有兜底**。

**用户已确认决策：**
- 挣脱方式 = **后退 + 小幅交替摆舵**（温和，低穿地形风险）。
- 多轮自救仍失败 = **暂停并提示玩家**（不强制完成、不无限重试）。

**范围边界：** 只改帆船 SailboatEntity，**不动马车 CarriageEntity**（陆地场景不同 + 历史回归风险 task #107）。

完整计划文件：`C:\Users\24908\.claude\plans\compiled-coalescing-panda.md`

---

## 2. 已完成的代码改动（逐文件核对清单）

### ✅ 预修复：编译阻塞（已做，已 compileJava BUILD SUCCESSFUL）
`market/logistics/ShippingTraceService.java`
- `createOrUpdateTrace`（~L29-47）的 `new ShippingTraceRecord(...)` 补了第 18 参 `0.0D`（currentSpeed），现为 `..., 0.0D, 0.0D, 0.0D, false`。
- `createOrUpdateManualTrace`（~L161）的 `new ShippingTraceRecord(...)` 补了 `0.0D`，现为 `..., currentX, currentZ, 0.0D, true`。
- 注：这是上一个会话遗留的 task #111 record 改动导致的 18 参缺口，已修平。

### ✅ A. 窄河道卡死自救（SailboatEntity.java）
- **常量**（~L159，AUTOPILOT_STALL_SKIP_RADIUS 之后）：新增 5 个
  `AUTOPILOT_STUCK_DETECT_TICKS=50` / `AUTOPILOT_STUCK_MOVE_EPSILON=0.02D` / `AUTOPILOT_UNSTICK_REVERSE_TICKS=24` / `AUTOPILOT_UNSTICK_MAX_ATTEMPTS=5` / `AUTOPILOT_UNSTICK_YAW_STEP=4.0F`。
- **状态字段**（~L212，autopilotLastTargetDistance 之后）：
  `autopilotStuckTicks` / `autopilotUnstickTicks` / `autopilotUnstickAttempts` / `autopilotUnstickYawDir=1` / `autopilotUnstickStartPos` / `autopilotTraceStuck`。
- **脱困分支**：`computeAutopilotCommand` 开头（isReadyToUnloadAtDestination 块之后）插入 `if (autopilotUnstickTicks > 0) {...}`，期间设 yaw + 返回 `AutopilotCommand(true,true,yawDir,unstickYaw,EngineGear.REVERSE)`；倒计时到 0 调 `endUnstickAttempt()`。
- **卡死检测**：`computeAutopilotCommand` 内 no-progress 累积块（`autopilotLastTargetDistance = dist;` 之后）插入：
  `physicallyStuck = horizontalCollision && getDeltaMovement().horizontalDistanceSqr() < EPSILON² && getEngineGear()!=STOP && absYawError<=AUTOPILOT_TURN_IN_PLACE_DEGREES`；
  累计达阈值 → `beginUnstickAttempt()` + 返回 STOP。
- **新方法群**（紧跟 computeAutopilotCommand 的 `}` 之后，selectAutopilotGear 之前）：
  `beginUnstickAttempt()` / `endUnstickAttempt()`（moved>1格²→复位清STUCK；连续MAX_ATTEMPTS无效→beginStuckHelpNotice+markStuckTraceStatus+pauseAutopilot）/ `resetUnstickState()` / `beginStuckHelpNotice()`（复用到站通知字段写「卡住」+ notifyStuckToOwner）/ `notifyStuckToOwner()`（traceShipperUuid→UUID→getPlayerList().getPlayer→sendSystemMessage(message.sailboatmod.autopilot.stuck)）/ `activeTraceId()` / `markStuckTraceStatus()` / `clearStuckTraceStatus()`（带 autopilotTraceStuck 守卫）。
- **跨程清理 resetUnstickState() 调用点**：`startAutopilotInternal`（resetArrivalNotice 旁）、`stopAutopilot(boolean)`（autopilotLastTargetDistance=NaN 之后）、`finishAutopilotAndUnloadAtDestination`（方法开头）。

### ✅ B. webmap 卡死告警
- `ShippingTraceService.isMapVisibleStatus`：可见集合加入 `"STUCK"`。
- `SailboatEntity.resumeAutopilot()`：末尾加 `clearStuckTraceStatus()`（玩家恢复→STUCK 回 SAILING）。
- `marketweb/map.js`（4 处前端 + 2 处 i18n）：
  - i18n：en `statusStuck:"Stuck · needs rescue"`、zh-CN `statusStuck:"阻塞 · 需救援"`（均加在 status 之后）。
  - `drawShipments`：算 `isStuck`（status==STUCK），未走段线红色 `#dc2626`+加粗(5)，传 isStuck 给图标函数。
  - `drawShipmentVehicleIcon(ctx,shipment,points,completedPointCount,isStuck)`：STUCK 时红色光晕加强 + 右上角红圈白「!」角标。
  - 列表项模板（map-shipment-row）：STUCK 行红色加粗 + 追加「· 阻塞·需救援」。
  - 详情 popup（renderSelection 的 shipment 分支）：status 行 STUCK 时红色 + 显示 statusStuck 文案。
  - hover tooltip（item.data shipment 分支）：STUCK 时追加红色 statusStuck span。

### ✅ C. 载具破坏订单兜底（SailboatEntity.remove）
- `remove(RemovalReason)`：在原「移除手动轨迹」块之后，新增 `if (KILLED || DISCARDED) { rollbackMarketShipment(); clearAutopilotShipmentContext(); }`。
  - 必须在 `Containers.dropContents`（KILLED 掉落）之前执行（位置已对）。
  - CHANGED_DIMENSION 不回滚（跨维度运输继续）。
  - `clearAutopilotShipmentContext()` 清 manifest 保证回滚幂等（防双退款）。
  - 复用现成方法 `rollbackMarketShipment()`（~L2620+，内部空 manifest 早退、可回滚退市场/不可回滚 loadCargo、updateStatus FAILED）。

---

## 3. ⬜ 剩余唯一未完成步骤（D）

### D1. lang 双文件加 2 条 key（**卡在这里**）

两个文件都要加（en_us.json 有 UTF-8 BOM，注意保持）。插入位置：`message.sailboatmod.auto_route.water.failed.generic` 那一行之后。

**zh_cn.json**（`F:\Codex\sailboatmod\src\main\resources\assets\sailboatmod\lang\zh_cn.json`，约 L1460 之后）：
```json
    "entity.sailboatmod.autopilot.stuck": "卡住，请协助",
    "message.sailboatmod.autopilot.stuck": "你的帆船在窄河道卡住了，已暂停自动航行，请前往救援。",
```

**en_us.json**（同目录，找对应 `auto_route.water.failed.generic` 行之后）：
```json
    "entity.sailboatmod.autopilot.stuck": "Stuck — needs help",
    "message.sailboatmod.autopilot.stuck": "Your sailboat is stuck in a narrow channel. Autopilot paused — please go rescue it.",
```
> ⚠️ 恢复后先用 Grep 确认这两条 key 是否**已经被加进去了**（zh_cn 的 Edit 可能已部分成功）。避免重复插入导致 JSON 重复键。

### D2. 构建
```bash
cd F:/Codex/sailboatmod && ./gradlew build -x test
```
（JVM 21 已在跑，compileJava 之前已成功；full build 会出 reobf jar 到 build/libs/）

### D3. 推送（标准约束：代理 7897 + gh token 内嵌 URL，禁直连/gh_token.txt）
```bash
TOKEN=$(gh auth token); git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push "https://x-access-token:${TOKEN}@github.com/monpai-lover/sailboatmod.git" feature/road-planner-rebuild
```
> 推送前需先 `git add` + `git commit`（本项目非 worktree，直接在工作区）。

---

## 4. 任务清单状态（TaskList）

- #112 A 窄河道卡死自救 —— ✅ completed
- #113 B webmap 卡死告警 —— ✅ completed
- #114 C 载具破坏订单兜底 —— ✅ completed
- #115 D lang + 构建推送 —— ⬜ in_progress（卡在 lang）
- #109 马车返回不可见 —— pending（本批次未处理，独立问题）
- #111 弹窗 town名/速度/ETA —— pending（本批次只修了它的编译阻塞，功能本身未做）

---

## 5. 验证要点（构建后游戏内测）

1. 窄河道撞岸约2-3秒→后退+船头小幅摆动→挣脱继续航线（脱困成功 webmap 不变红）。
2. 完全堵死窄口→约5轮后暂停、船头「卡住，请协助」、船主收到聊天、**webmap 该轨迹变红「阻塞·需救援」+红圈「!」角标**；玩家手动恢复后 webmap 恢复正常色。
3. 载货订单船在途被破坏→买家退款/订单回滚、webmap 订单轨迹消失（FAILED 不可见）、货按现有规则掉落。
4. 无回归：开阔水域不误触发；大角度 pivot 转向不被误判卡死；正常到站/返航/多站连运不受影响。

---

## 6. 注意事项 / 坑

- 本会话期间 Edit 工具多次「无返回」卡住（尤其含中文/特殊字符的内容）。恢复后**逐个文件先 Read 确认实际落盘内容**，再决定补哪些 Edit，避免重复/遗漏。
- 重点先核对：zh_cn.json 那 2 条 key 是否已存在（很可能 Edit 卡住前没成功）。
- 推送方式严格按 §3.3，别用直连。
- 每个 bug 修复都要 build jar + push（用户硬性要求）。
