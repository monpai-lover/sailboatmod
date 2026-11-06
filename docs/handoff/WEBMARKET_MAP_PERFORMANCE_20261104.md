# WebMarket 地图性能更新（NeoForge 1.21.1）

此次仅修改 WebMarket 附属。若服务器已正常使用你提供的 1.3.13 附属，可只替换附属，无需更新客户端。适配当前 `1.21.1-neoforge` 分支的 SailboatMod 1.3.13 主模组 API；主模组网络协议仍为 22。

## 安装

停止服务器，备份并移出旧 `sailboatmod-marketweb` JAR，再把新附属放进服务器的 `mods`。同目录只保留一个 WebMarket 附属。保留原有地图缓存及存档，启动后刷新网页。不要将附属装进客户端。

本次构建使用 `marketWebJar -PfixIndex=14`，发行文件为 `sailboatmod-marketweb-1.3.13-fix_14-neoforge-1.21.1.jar`。内部兼容版本保持 1.3.13。

## 暂停和继续

管理员游戏内输入（服务器控制台省略开头 `/`）：

```text
/marketweb map pause
/marketweb map resume
/marketweb map status
```

`/webmarket` 是相同命令的别名。全局暂停会保存到配置，重启后仍暂停；不要求正在进行 fullrender。暂停时继续显示已有地图、领土和市场，也能正常交易。暂停后正在写入的图片可能完成；Tellus 预测采样和巡检会在检查点停止。缺少缓存的区域暂不补画。

旧的 `/marketweb map fullrender pause|resume` 仍保留，只控制旧渲染任务。全局继续命令同时解除旧任务暂停，避免地图仍停着。

## 默认工作量

附属首次启动后生成 `config/sailboatmod-marketweb-common.toml`：

```toml
[map]
updatesEnabled = true
mainThreadBudgetMillis = 2.0
maxSnapshotsPerTick = 8
maxProbeStartsPerTick = 4
maxInflightProbes = 32
renderThreads = 1
loadedRefreshCooldownSeconds = 30
browserDiscoveryCooldownSeconds = 60
```

主线程采样共享每 tick 2 ms 的软预算：单次不可拆分的采样仍可能超时，超时后本 tick 不再开始后续工作。最多 8 次快照捕获，包括北侧邻块参考；离线读取最多每 tick 发起 4 项、同时 32 项。绘图默认只用一个后台线程，修改线程数后重启服务器。

这些设置归附属所有，旧主模组的采样配置不再决定上述新限制。无需照抄示例覆盖现有配置，命令会维护 `updatesEnabled`。

## 改动

- 实际地形和 Tellus PNG 缓存命中直接由网页线程读取，避免浏览地图持续挤占服务器线程。实际字节的 ETag 支持 304 响应。
- 缺失瓦片发现有冷却和绝对上限。单张远景请求最多检查 256 个已加载区块、排入 64 个，浏览网页不会触发全片离线扫描或生成世界。
- 未变动的已加载区块有 30 秒采样冷却，明确的脏区更新可绕过冷却。
- 定期区域巡检在后台读取目录和文件头，主线程按预算接收有界结果；自动黑块巡检每轮最多检查 128 张瓦片并轮转，支持协作暂停。手动全量修复仍保留。
- 网页只刷新发生变化的地形瓦片；交易事件、选中标记和全局计数不再使整屏地形重载。历史失效时分批重新验证可见图片。
- 待处理状态合并后异步保存，队列快照离开锁后编码和写盘。保存时把队列、在途和待写图区域一并保守记录，恢复后从区域开头重扫，实时游标仍正常推进。重启可能重算少量工作，避免只保存游标而丢掉内存里的待绘任务。
- 公共领土和市场标记共享 2 秒显示缓存，避免每个网页请求重新构建和排序完整领土列表。交易权限和私有物流仍实时检查。

## 参考与验证边界

工作线程、冷却和写图背压参考了 [Squaremap](https://github.com/jpenilla/squaremap) 及 [BlueMap 配置](https://bluemap.bluecolored.de/wiki/configs/Core.html) 的设计。现有坐标、投影、PNG 布局和渲染版本保持兼容。

交付包包含实际测试结果和 SHA256。隔离专服验证使用新建测试世界，不代表生产服的完整模组组合测试。没有生产服 spark 报告，因此不宣称固定的 TPS 或 CPU 降幅；可先暂停地图观察，再继续地图比较实际服务器表现。

最终验证：

- 全量 Gradle：2546 项，2545 通过、1 跳过，0 失败；`marketWebJar -PfixIndex=14` 构建成功。
- Node：78/78 通过，包含投影、Tellus 底图、页面会话与按瓦片刷新。
- 隔离 NeoForge 21.1.265 生产专服：20 项通过，包括三类 PNG 缓存/304、200 次缓存读取无新增任务、恢复后实际生成出生点地形、暂停命令与跨重启保存。使用当前分支重建主模组、GeckoLib 4.9.3、LDLib2 2.2.42。
- Tellus 采样中止/恢复由行为测试覆盖；专服使用普通平坦测试世界验证已有 Tellus PNG 的缓存接口，未在完整 Tellus 世界运行预测渲染。
- 附属 JAR：156 项，CRC 和独立附属类边界通过；大小 935983 字节。
- SHA256：`06da0e2d1c95c77305885bca38c820a67f943701434b83dbdd9420cd3b12ebb2`。
