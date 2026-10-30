# 可选模组隔离安装验证

日期：2026-10-30。Sailboat 源码基线 `e61fa1a6`，完整主模组/网页 addon 为 fix_15、协议 17。此次只安装第三方官方发布文件到独立测试实例并更新文档，没有修改 Sailboat 运行时代码或制作新的主模组构建。

## 结果与边界

| 组合 | 外部 JAR 数 | 专用服务端启动 | `save-all flush` 与维度保存 | 正常退出 |
|---|---:|---|---|---|
| 原有基线 + 七个内容候选 + CC + Photomancy | 94 | `Done` | 通过 | 未通过；`stop` 后 40 秒仍驻留，仅向本次子进程发 SIGTERM，退出码 143 |
| 上述组合 + World Engine 0.1.3 | 95 | `Done`；原生物理管线/Rapier 初始化有日志 | 通过 | 同上 |
| 诊断对照：95 JAR 组合临时移除 EMI 与 EMI Loot | 93 | `Done`；LDLib Widget 分侧日志消失 | 通过 | 同上；测试后已恢复 EMI 两项 |
| 最终默认实例：明确 flat 设置，恢复 EMI，不启用 World Engine | 94 | `Done`；Sable 默认物理管线；无 `No key layers` | 通过 | 同上；本轮测试进程已停止 |

这些结果仅证明此主机、版本和配置组合到达服务端启动及保存节点。**不是全功能兼容通过**：没有客户端联机、真实飞机/船/车运行、订单交货、运输加载票据生命周期、带载具重启恢复或性能基准。World Engine 初始化不证明有性能收益，也不验证其物理效果与原后端等价。停服驻留现象在未加这八个候选的上一轮基线中已存在，此次未确定根因或修复。

每轮均未出现原 Sable 崩溃特征 `Attempted to load class net/minecraft/client/multiplayer/ClientLevel`，也未出现 `Sable transport loading tickets unavailable` 或运输票据重建失败。不能据此推导实际运输票据增删/重启已通过。

## 已发现的问题

1. **FC 0.8.3 工具枪兼容 Mixin 非致命失败。** 日志为 `ToolGunValueSettingsMixin` 包含非 private 静态方法 `cfc$preferBearingToolGun`，导致该 Mixin 未应用。服务端继续启动，不将其误报为启动致命失败；工具枪值设置交互及是否影响目标机械配置仍需客户端/玩法验收。本次没有重打包或改写 FC。
2. **LDLib2 与 EMI 的基线分侧问题。** `ModularUI$ModularUIWidget` 被 Mixin 元数据校验尝试加载，在专用服务端记录 invalid dist。旧基线已有相同日志；仅移走 EMI/EMI Loot 后消失。LDLib 的通用 `emi.RecipeScreenMixin` 引用该客户端 Widget，插件的 EMI 存在性判定未按此路径隔离分侧。这与已修复的 Sailboat/Sable `ClientLevel` 反射路径不同。本次恢复原测试模组组合，不将对照移除称为源码修复。
3. **既有整合包日志。** PowerGrid 原生 solver 在该 macOS 主机不可用，会回退 Java；部分可选联动配方/战利品和 `copycats:copycat_catwalk` 物理标签引用不存在的项。旧基线也有这些类型的日志，不能声称全部日志无错误。
4. **初始测试配置。** 首轮新 flat 世界沿用空的 `generator-settings={}`，记录一次 `No key layers` 非致命错误；后三轮未出现。最终测试实例改为显式 bedrock/dirt/grass 层及 plains 生物群系，并完成启动/保存复核；详见下方机器可读结果。

## 安装与证据

- 运行环境：Apple Silicon macOS，Eclipse Temurin 21.0.12.1，NeoForge 21.1.265；独立 loopback 服务端 `127.0.0.1:57671`，网页 `127.0.0.1:57672`。使用测试世界，没有打开或改写用户 Desktop 的 fix_12 服务端及正式客户端。
- [固定安装清单](optional-addons.lock.json)包含八个候选和两个新增前置的官方下载地址、大小、SHA-256/SHA-512、mod ID 与 JAR 实际依赖。九个 Modrinth 文件的 SHA-512 与官方 API 一致；World Engine 从官方 CurseForge CDN 下载后计算本地锁定哈希，未取得上游哈希，未标记为官方哈希验证通过。
- Deep Seas 3.0.0 的 `create_submarine`、`create_high_seas`、`create_abyss` 均有 FML 容器创建证据；控制台文件列表只显示主 mod，不代表其他两个 ID 未加载。High Seas 已内含，不再另装。Abyss 日志明确其生产环境内容禁用，不将容器加载宣传为该玩法可用。
- 测试文件位于工作树的 `build/optional-addons-2026-10-30/`，第三方 JAR、世界和原始日志均未提交到 Git。`downloads/` 保留十个候选/前置文件；`server/` 是可复现实例。World Engine 最终放在 `optional-disabled/`，默认内容组合不启用性能替换。
- 每轮控制台日志、结果 JSON、启动脚本 `run-validation.py` 及 Deep Seas 容器证据保留在该构建目录。[精简验证记录](optional-addons-validation.json)记录检查结论、日志 SHA-256 和范围。

本机复现启动命令（在上述 `server/` 目录中）：

```sh
/Users/goatdie/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home/bin/java \
  @user_jvm_args.txt \
  @libraries/net/neoforged/neoforge/21.1.265/unix_args.txt nogui
```

完整附属集合是共存测试配置，玩家按[安装指南](optional-addons.md)选择需要的模块即可。现有 FC 桥及后续薄适配的验收仍使用[物理物流矩阵](acceptance.md)，不因本页的 `Done` 跳过控制权、完整停泊和货物守恒测试。
