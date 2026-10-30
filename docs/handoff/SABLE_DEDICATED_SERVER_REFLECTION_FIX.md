# Sable 专用服务端反射修复

日期：2026-10-30。适用源码分支：Minecraft 1.21.1 NeoForge，协议 17。用户已有 fix_12 服务端热补丁的相同修复现已进入主模组源码；无需长期依赖手工替换 class。

## 根因与最小修复

Sable 2.0.6 的 `SubLevelContainer` 同时声明：

```text
getContainer(ServerLevel) -> ServerSubLevelContainer
getContainer(ClientLevel) -> ClientSubLevelContainer
```

旧实现调用 `Class.getMethod` 时，JVM 的 `getDeclaredMethods0` 解析其他方法签名，触发 `ClientLevel` 类加载。专用服务端的 NeoForge `RuntimeDistCleaner` 拒绝该客户端类并抛出 `RuntimeException`，异常经 `SableLoadingTickets.initialize` 进入模组初始化队列。仅捕获异常会使票据接口始终不可用，不能作为功能修复。

[SableLoadingTickets.java](../../src/main/java/com/monpai/sailboatmod/compat/aeronautics/SableLoadingTickets.java) 现在用 `MethodHandles.publicLookup()` 按完整声明类、参数和返回类型绑定以下方法：

| 方法 | 精确返回类型 | 参数 |
|---|---|---|
| `SubLevelContainer.getContainer`（static） | `ServerSubLevelContainer` | `ServerLevel` |
| `SubLevelContainer.getSubLevel` | `SubLevel` | `UUID` |
| `ServerSubLevelContainer.addForceLoadTicket` | `boolean` | `ServerSubLevel, SubLevelLoadingTicketType, Object` |
| `ServerSubLevelContainer.removeForceLoadTicket` | `boolean` | `ServerSubLevel, SubLevelLoadingTicketType, Object` |
| `ServerSubLevelContainer.collectForceLoadTickets` | `Map` | 无 |

返回类型以实际 Sable 2.0.6 JAR 的 `javap -p -s` 核对，尤其 `getSubLevel` 不能误写为 `ServerSubLevel`。票据类型工厂和 ticket record 的访问器目前不含客户端类型签名，保留其现有反射。

保持不变的业务语义：

- 票据类型仍为 `sailboatmod:registered_transport`，编码仍为 `UUIDUtil.CODEC`。
- 物理 UUID 查结构，登记 UUID 作为票据 key；不借用或删除玩家及其他模组的票据。
- 原生增删返回 false 也可能只是已存在/已不存在；调用成功仍向加载窗口返回 true，保持幂等。
- 对账先收集自有过期票，再删除；没有 Sable 时安全降级。
- `MethodHandle.invoke` 直接抛出 Throwable；与用户随包补丁一致，操作路径重抛 Error，其余失败返回 false 或记录告警。不能无条件吞掉所有 Throwable。

## 两种版本的兼容范围

### 用户原整合包：fix_12 / 协议 15

已重新核对用户提供的原 JAR 和服务端热补丁哈希：

| 文件 | SHA-256 |
|---|---|
| 原 fix_12 主模组 | `0b55b03deb9229bc9d4b1cb7e67ab9f4ff6113dc91aad022e222b7da2ced1dae` |
| 用户已有 fix_12 服务端热补丁 | `9142fe6c698cfb0017c5ab1200ac2548dea240c30018d074ac467bc22eeded26` |

逐 ZIP entry 核对只改变以下两项，无增加/删除 entry，`ModNetwork.class` 字节完全相同：

```text
com/monpai/sailboatmod/compat/aeronautics/SableLoadingTickets.class
com/monpai/sailboatmod/compat/aeronautics/SableLoadingTickets$Access.class
```

因此用户原 fix_12 客户端继续搭配该协议 15 服务端热补丁；本轮未修改该旧包，也未把新分支完整 JAR 冒充旧版兼容补丁。此次核对协议和字节差异不等于已进行客户端联机测试。

### 当前源码：fix_15 / 协议 17

本次 `releaseMod -PfixIndex=15` 产物位于 `build/libs/`：

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `sailboatmod-1.3.13-fix_15-neoforge-1.21.1.jar` | 31465909 | `a6f9599dab806569e23ae00f03430eb37587261dc45e99c6a059022f9f251dad` |
| `sailboatmod-marketweb-1.3.13-fix_15-neoforge-1.21.1.jar` | 5008270 | `5ac1a4a1db2efa2c6519861bbf4c42865c12e6402009cb80bf8a4e7f6580eeba` |

相对前一次构建的 fix_14，主 JAR 的解压内容仍仅改变上述两个 class，其他 entry 相同；网页附属 JAR 的 SHA-256 与 fix_14 相同。新 JAR 保留8120个Minecraft网页资源，未混入测试类。网络协议没有因本修复改变，仍为17；不能直接与协议15的 fix_12 客户端混用。

## 本次验证

[SableLoadingTicketsTest.java](../../src/test/java/com/monpai/sailboatmod/compat/aeronautics/SableLoadingTicketsTest.java) 使用隔离类加载器加载真实编译后的桥接及 Access，临时编译外部 API 形状，并在加载 ClientLevel 时抛出与 NeoForge 相同类型的拒绝异常。断言既包括“不加载客户端类”，也包括“票据接口可用”，防止捕获异常后静默关闭集成。

| 验证 | 本次结果 |
|---|---|
| 修复前新回归 | 4项中3项失败，复现 `ClientLevel … invalid dist DEDICATED_SERVER`；无Sable用例通过 |
| 修复后定向回归 | 26项全部通过：新回归、Sable桥接、加载窗口、登记状态 |
| 全量 JUnit | 2061项，2060通过、1跳过，0失败/错误，443个suite |
| `test releaseMod -PfixIndex=15` | 构建成功 |
| 原/补丁/新制品检查 | 上述哈希、双class范围、资源完整性和协议分别核对 |
| 真实专用服务端启动 | Temurin21.0.12.1、NeoForge21.1.265、Sable2.0.6及用户整合包85个外部JAR，在独立普通世界约26.9秒到达 `Done` |
| 本次错误路径检查 | 没有ClientLevel专服拒绝、Sable票据接口不可用或票据对账失败告警 |
| 停服 | 发送stop后记录所有维度保存完成；40秒后进程仍未退出，由验证脚本SIGTERM结束，退出码143。**不算正常进程退出通过** |

启动使用全新的临时世界、回环地址和独立端口，没有打开用户存档。整合包仍有既存第三方警告/资源错误及停服后进程驻留现象；此次通过的是特定 Sable 初始化错误修复，不是整个整合包零错误认证。

本地原始证据：

- `/tmp/sailboat-sable-reflection-red.log`
- `/tmp/sailboat-sable-reflection-green.log`
- `/tmp/sailboat-sable-fix15-release.log`
- `/tmp/sailboat-sable-fix15-server-s1fo5yz1/validation-console.log`
- `/tmp/sailboat-sable-fix15-server-s1fo5yz1/validation-result.json`

可重复的工程检查：

```sh
./gradlew test --tests '*SableLoadingTicketsTest' --tests '*SableReflectionAccessTest' --tests '*TransportLoadingWindowTest' --tests '*ControllerRegistrationTest' --no-daemon --console=plain
./gradlew test releaseMod --no-daemon --console=plain -PfixIndex=15
```

客户端联机、真实结构票据增删、包含活动运输票据的重启恢复、完整动力运输及付款仍未实测。隔离API回归不替代这些游戏内场景，也不解决已有Sable结构序列化或FC航段恢复限制。
