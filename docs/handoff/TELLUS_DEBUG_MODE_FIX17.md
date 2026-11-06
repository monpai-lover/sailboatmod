# fix_17：Tellus 调试模式与服务器线程修复

仅更新服务器主模组，协议仍为 **22**，元数据版本仍为 **1.3.13**。玩家客户端与现有 MarketWeb 附属不需更新。

## 网页模式

新增明确的 **TELLUS（全球水域导向＋真实精寻）** 选项。网页通过受令牌保护的 `/api/capabilities` 判断当前世界是否使用 Tellus 地球生成器；Tellus 世界默认选择 TELLUS，其他世界显示禁用理由并默认 NBT。初始化完成前不能生成，生成过程中也不能重复提交。

Tellus 是仅用于 HTTP 网页的模式，调用现有生产 NBT/Tellus 求解桥。响应保留 `mode=tellus`，`pipeline=production-nbt`，只展示真实最终结果。不增加游戏网络枚举、数据包或协议。非法模式和不支持的 Tellus 请求会返回明确 JSON 错误，不再静默换算法；失败诊断日志保留。

## `Port capture requires server thread`

Minecraft 的 `submit(Supplier)` 在 `scheduleExecutables()` 为 false 时直接执行 Supplier；`MinecraftServer` 停止后正好会进入这条路径。旧调试 HTTP 服务未随世界关闭，可能仍引用已停止的服务器，使泊位读取在 HTTP 线程执行。

修复后，泊位捕获与网页元数据通过已有服务器 tick mailbox 投递，不依赖可能内联的 `submit`。排队时每 50 ms 检查调用方取消，保留 120 秒整体协作期限，并取消未执行回调。停止的服务器直接拒绝读取；所属世界停止时自动关闭 HTTP 服务。旧 HYBRID/NOISE 诊断里的主线程投递也改为 mailbox 并设置等待上限，其原有强制加载诊断行为仍然保留。

## 验证

- **24 项 Java 回归通过**：包括模拟 HTTP 线程内联执行的事件循环、停止的服务器、排队取消、网页鉴权、模式检测、生产求解桥。
- **12 项网页脚本交互测试通过**：执行实际嵌入脚本，模拟 DOM 与 HTTP，覆盖 Tellus 自动选择、禁用原因、初始化/重复提交限制、最终结果图及错误日志。
- **23 项隔离 NeoForge 21.1.265 / Java 21 运行检查通过**：实际码头、泊位读取、NBT 求解、PNG、模式拒绝、令牌鉴权，以及世界停止自动关闭监听。
- **890 个客户端、网络、资源及原网络模式条目与 fix_16 逐字节相同**。核心水域网格类保持原样。

本次原生服务器测试使用平坦测试世界；Tellus 正向能力选择由网页交互和模式映射测试覆盖，没有重新声称在用户线上 Tellus 世界执行过完整 HTTP 航线。上一版的官方地理寻路验收结果见 `BOSPHORUS_ROUTE_DEBUG_FIX16.md`。

## 安装和打开

停服，将旧服务器主 JAR 移出 mods 备份，换入 `sailboatmod-1.3.13-fix_17-neoforge-1.21.1.jar`，不要与旧主 JAR 并存。保留现有依赖、附属与存档。

```text
/sailboat routedebug start 8899 public
```

将命令输出的链接中的 `127.0.0.1:8899` 换成 `zjc.rainplay.cn:54587`，保留完整 `?token=...`，然后刷新页面。Tellus 地球世界会自动选中 TELLUS。退出存档会关闭服务，重新进入后再次执行启动命令。`/sailboat routedebug status` 可查看本次链接。

主 JAR：**27827607 bytes**；SHA-256：

```text
af122ff338b7338bc4dfd84a6f20473a82c1b2cb14b1ba10863b2dc455de2e11
```
