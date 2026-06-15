# 成员-Town 绑定与加入流程重设计

日期：2026-06-15
状态：已获用户分节确认，待审阅

## 背景与问题

P2P 货运 Phase 5 要求「收货候选按玩家所在 town 收窄」，但数据模型里**根本没有「玩家→town」绑定**：

- `NationMemberRecord` 只绑 nation（playerUuid / lastKnownName / nationId / officeId / joinedAt），无 town 字段。
- `TownRecord` 只存 `mayorUuid`（镇长），无镇民/居民列表。
- 玩家经旧 `/nation join`（`NationService.joinNation`，行 437）直接挂 nation 籍，**与任何 town 无关**——历史遗留：有 nation 籍却无 town 归属。

因此 Phase 5 的「拉当前 town 的仓库列表」无数据可依。本设计补上「玩家↔town」这一缺失维度，并重设计加入流程为 **town-primary**：玩家先加入 town，再随 town 加入 nation；若 town 已属 nation，则加入即随之获得 nation 籍。

## 设计原则

- **双记录 + 事件联动（方案 B）**：新增 `TownMemberRecord` 存镇民；`NationMemberRecord` 保留，但其写入由「镇民变更」与「town↔nation 转换」**驱动**。现有 `getMember` 调用方零改动，nation 查询全不变。
- **联动收口集中**：town↔nation 的全部进出口只有 `bindTownToNation` / `unbindTownFromNation` 两个私有方法，全量联动逻辑只织这两处即全覆盖。
- **向后兼容**：旧存档无新记录 → 空 Map + 一次性迁移兜底，绝不删改既有 nation 身份/claim/职位。
- **复用现有同构**：town 成员的 invite/apply 双向请求，照搬现有 town↔nation 请求（相反方向相遇即成交）的实现。

## 1. 数据模型

### 1.1 新增 `TownMemberRecord`
`com.monpai.sailboatmod.nation.model`：
```java
public record TownMemberRecord(UUID playerUuid, String townId, String officeId, long joinedAt)
```
- `officeId` 预留 town 级职位，初期常量 `OFFICE_MEMBER`（"MEMBER"），与 nation 的 officeId 解耦。
- NBT `save`/`load` 仿 `NationMemberRecord`：String id 字段缺省空串，向后兼容旧档。
- 支持「一玩家多 town」。

### 1.2 新增 `TownMemberInviteRecord`
复用现有 town↔nation 请求的双向同构：
```java
public record TownMemberInviteRecord(String townId, UUID playerUuid, String direction, UUID initiatorUuid, long createdAt)
```
- `direction` ∈ `APPLY`（玩家申请）/ `INVITE`（镇长邀请），与 `TownNationRequestRecord` 同构。
- 相反方向请求相遇时自动成交。

### 1.3 `NationSavedData` 扩展
照搬现有 `offices`/`invites` 的「复合字符串键 Map + setDirty()」模式：
- `Map<String, TownMemberRecord> townMembers`，键 `townId + "|" + uuid`。
- `Map<String, TownMemberInviteRecord> townMemberInvites`，键 `townId + "|" + uuid`。
- 新增 public 方法（仿 `getMember`/`putMember`/`removeMember`/`getMembersForNation`）：
  - `getTownMember(townId, uuid)`、`putTownMember`、`removeTownMember`
  - `getTownMembersForTown(townId)` —— 全量联动遍历用
  - `getTownsForPlayer(uuid)` —— **Phase 5 收货候选的关键查询**
  - `getTownMembersForPlayer(uuid)`
  - invite 的 `getTownMemberInvite` / `putTownMemberInvite` / `removeTownMemberInvite` / 列表查询。

**保留不动**：`NationMemberRecord`（nation 身份仍走它）、`TownRecord`（仍只存 `mayorUuid`，镇民另存）。

## 2. 加入流程与指令命名

**核心约束**：现有 `/nation town join <nation>` 已占用，语义是「town 加入 nation」。玩家加入 town 是**新维度**，必须用不冲突的命名空间。

**新开 `/town` 顶级指令树**，与 `/nation` 平行，专管「玩家↔town」：

| 指令 | 作用 | 委派 |
|---|---|---|
| `/town apply <town>` | 玩家申请加入某 town | `TownMemberService.applyToTown` |
| `/town invite <player>` | 镇长邀请玩家入镇 | `TownMemberService.invitePlayer` |
| `/town accept <player>` | 镇长批准申请 | `TownMemberService.acceptApply` |
| `/town reject <player>` | 镇长拒绝申请 | `TownMemberService.rejectApply` |
| `/town join <town>` | 玩家接受邀请（相反方向相遇即成交） | `TownMemberService.joinTown` |
| `/town decline <town>` | 玩家拒绝邀请 | `TownMemberService.declineInvite` |
| `/town leave <town>` | 玩家退出某 town | `TownMemberService.leaveTown` |
| `/town kick <player>` | 镇长踢出镇民 | `TownMemberService.kickMember` |
| `/town list` | 列出我加入的所有 town | `TownMemberService.listMyTowns` |
| `/town members [town]` | 列出某 town 的镇民 | `TownMemberService.listMembers` |
| `/town requests` | 镇长查看待处理申请/邀请 | `TownMemberService.listRequests` |

**加入即随 town 入 nation（关键联动）**：`acceptApply`/`joinTown` 成功写入 `TownMemberRecord` 后，立即检查该 town 是否已属 nation（`TownRecord.hasNation()`）：
- 该 town 已属 nation → 同步给该玩家写 `NationMemberRecord(该 nationId, NationOfficeIds.MEMBER)`。玩家无需再跑 `/nation join`。
- 该 town 无 nation（独立 town）→ 只写 town 成员，玩家暂无 nation 籍；将来该 town 入 nation 时由全量联动补上。

**退出/踢出**：`leaveTown`/`kickMember` 删 `TownMemberRecord` 后，若该 town 属 nation **且**该玩家不再属于该 nation 下任何 town，则同步删其 `NationMemberRecord`（避免脱离 town 却仍挂 nation 籍）。

**为何独立 `/town` 而非塞进 `/nation town`**：
1. 语义零歧义——`/nation town *` 是「对 town 做 nation 操作」，`/town *` 是「玩家对 town 操作」。
2. 复用现有双向请求（invite/apply 相遇成交）的同构实现。
3. 未来 town 自治指令有自然归宿。

## 3. 全量联动（town 在 nation 间进出时，镇民身份跟随）

**挂载点已锁定**：`bindTownToNation`（TownService:1182）与 `unbindTownFromNation`（TownService:1207）是 town↔nation 的唯一进出口——全部 6 处 bind、2 处 unbind 调用都收口于此。

**`bindTownToNation` 末尾追加（town 入 nation → 全镇民入 nation）**：
```java
for (TownMemberRecord m : data.getTownMembersForTown(town.townId())) {
    NationMemberRecord existing = data.getMember(m.playerUuid());
    if (existing == null || !nationId.equals(existing.nationId())) {
        data.putMember(new NationMemberRecord(
                m.playerUuid(), nameOf(data, m.playerUuid()),
                nationId, NationOfficeIds.MEMBER, System.currentTimeMillis()));
    }
}
```
- 已属该 nation 的不重写（幂等，保留其 officeId/joinedAt）。
- `lastKnownName` 取已知名缓存，缺则空串。

**`unbindTownFromNation` 开头追加（town 脱离 nation → 镇民退籍，保护多 town 成员）**：
```java
String leavingNationId = town.nationId();  // 重写前先取
// 既有：重写 town 为无 nation、重写 claim…
for (TownMemberRecord m : data.getTownMembersForTown(town.townId())) {
    if (stillInNationViaOtherTown(data, m.playerUuid(), leavingNationId, town.townId())) {
        continue;  // 该玩家还属于此 nation 下别的 town，保留 nation 籍
    }
    NationMemberRecord existing = data.getMember(m.playerUuid());
    if (existing != null && leavingNationId.equals(existing.nationId())) {
        data.removeMember(m.playerUuid());
    }
}
```
- `stillInNationViaOtherTown`：遍历该玩家 `getTownsForPlayer`，若有另一个 town 仍属同一 nation 则保留——「一玩家多 town」下唯一会咬到的边界。
- 只删 nationId 匹配的，避免误删该玩家在别处获得的 nation 身份。

**镇长（mayorUuid）特例**：现有 town 只认 `mayorUuid`，镇长可能无 `TownMemberRecord`。联动遍历的是 `getTownMembersForTown`，故镇长须在建 town / 迁移时补一条自己的 `TownMemberRecord`（见 §4），否则不会随 town 联动。

**一致性保证**：所有 town↔nation 转换强制走这两个收口 → 镇民的 nation 籍永远 = 其所属 town 的 nation 并集。§2 的玩家级联动（动一个玩家）与此处 town 级联动（动整 town）互补、不重叠。

## 4. 存档迁移

旧档两类"孤儿"必须在升级时被收编，否则联动漏人：
- **孤儿 1**：镇长无 `TownMemberRecord`（旧 `tryCreateTown` 只写 `mayorUuid`）。
- **孤儿 2**：玩家有 `NationMemberRecord` 却无任何 town（旧 `joinNation` 直接挂 nation 籍）。

**迁移策略：惰性补 + 一次性扫描**

1. **建 town 时补镇民（根治孤儿 1 的未来增量）**：`tryCreateTown` 写完 `data.putTown(town)` 后紧接：
   ```java
   data.putTownMember(new TownMemberRecord(
           actor.getUUID(), town.townId(), TownMemberRecord.OFFICE_MEMBER, now));
   ```
   镇长自动成为首位镇民。

2. **一次性迁移扫描（`NationSavedData.load` 末尾触发，幂等）**：新增 `TownMemberMigration.runOnce(data)`，用持久化布尔 `townMembersMigrated` 防重跑：
   - **镇长补录**：遍历所有 `TownRecord`，若 `mayorUuid` 在该 town 无 `TownMemberRecord` → 补 `TownMemberRecord(mayorUuid, townId, OFFICE_MEMBER, town.createdAt())`。
   - **nation 孤儿归并**：遍历所有 `NationMemberRecord`，若该玩家无任何 `TownMemberRecord`：
     - 是某 town 镇长 → 已被上一步补上，跳过。
     - 否则 → 挂到其 nation 的首都 town（`nation.capitalTownId()`，空则该 nation 首个 town）的 `TownMemberRecord` 下。
     - 该 nation 一个 town 都没有（理论残档）→ 保留其 `NationMemberRecord` 不动，记 warn 日志，不强造 town 成员。
   - 跑完置 `townMembersMigrated = true`，`setDirty()`。

3. **迁移不改 `NationMemberRecord`**：归并只**新增** `TownMemberRecord` 让 nation 籍"有据可依"，绝不删改既有 nation 身份——旧玩家登录后 nation 籍、claim、职位全不变。

**幂等性**：标记位 + "已存在则跳过"双保险；即使标记丢失重跑，`putTownMember` 以 `townId|uuid` 为键覆盖写，只补缺不重复。

## 5. 序列化、GUI 与测试

### 5.1 序列化（嵌入 `NationSavedData` NBT）
照搬现有 Map↔ListTag 模式：
- `TownMembers`（ListTag）：每条 `TownMemberRecord.save()`，load 回填 `townMembers` Map（键 `townId|uuid`）。
- `TownMemberRequests`（ListTag）：每条 `TownMemberInviteRecord.save()`，回填 `townMemberInvites` Map。
- `townMembersMigrated`（putBoolean/getBoolean）：迁移幂等标记。

旧档无这些标签 → 空 Map + 标记 false，`load` 末尾触发 `TownMemberMigration.runOnce`。

### 5.2 GUI（最小必要，复用现有 town 面板）
遵循 YAGNI，只补镇民体系必需的视图：
- **town 信息面板加「镇民」段**：现有 town 详情 GUI 追加镇民列表（名字 + 加入时间），数据来自 `getTownMembersForTown`。镇长视角额外显示「待处理申请/邀请」与「踢出」按钮。
- **玩家「我的 town」入口**：现有 nation/玩家界面加「我加入的 town」列表（`getTownsForPlayer`），每项可点开 town 信息。
- **不新建独立顶级屏**：镇民管理嵌进既有 town 面板；邀请/申请走指令或面板按钮（复用现有 town↔nation 请求 UI 的同构组件）。
- GUI 数据下发细节在实现计划里按现有 town 面板代码现状再定；本设计只锁定「镇民段挂在 town 面板、数据源 `getTownMembersForTown`/`getTownsForPlayer`」。

### 5.3 测试策略（沿用本仓两类：纯逻辑单测 + 源码契约断言）

**纯逻辑单测（JUnit，脱离 MC 运行时）：**
- `TownMemberRecord` save/load 往返；空串字段兼容旧档。
- `NationSavedData` townMembers 增删查：`putTownMember`/`getTownMember`/`removeTownMember`/`getTownMembersForTown`/`getTownsForPlayer`，键 `townId|uuid` 不串扰。
- **加入联动**：joinTown 到「已属 nation 的 town」→ 该玩家获 `NationMemberRecord`；到独立 town → 无 nation 籍。
- **全量联动**：bindTownToNation → 全镇民获该 nation 籍（已属者幂等不重写）；unbindTownFromNation → 镇民退籍，但「多 town 同 nation」者保留（`stillInNationViaOtherTown`）。
- **请求合并**：apply/invite 相反方向相遇即成交。
- **迁移**：镇长补 `TownMemberRecord`；nation 孤儿挂首都 town；无 town 的 nation 残档保留 nation 籍不崩；标记位防重跑。

**源码契约断言（`PickupWiringContractTest` 风格）：**
- `NationSavedData` save/load 含 `TownMembers`/`TownMemberRequests`/`townMembersMigrated`。
- `/town` 指令树注册了 apply/invite/accept/reject/join/leave/kick/list/members/requests。
- `bindTownToNation`/`unbindTownFromNation` 含对 `getTownMembersForTown` 的遍历联动。
- `tryCreateTown` 建 town 后写 `putTownMember`。
- 两份 lang 文件含新 `/town` 指令反馈 key（双语）。

**构建验证**：`./gradlew build -x test` 出 jar；新测试单独 `./gradlew test --tests "...TownMember*"`。

## 范围边界（YAGNI）

- 不做 town 级职位权限体系（`officeId` 预留但初期只有 MEMBER；副镇长/镇内权限留待将来）。
- 不做跨 nation 的 town 成员可见性、不做镇民间私有权限。
- GUI 只补镇民列表 + 申请处理，不做独立镇政管理屏。
- 不动既有 `/nation join/leave/kick` 个人指令（保留兼容；新流程经 `/town` + 联动达成 town-primary）。
- 迁移只新增 `TownMemberRecord`，绝不删改既有 nation 身份/claim/职位。

## 对 Phase 5 的解锁

本设计落地后，Phase 5 §1.2 `receivingWarehouseOptionsForViewer` 即可改为：
1. `data.getTownsForPlayer(uuid)` 拿玩家所有 town（替代原计划的「getMember→单 townId」）。
2. 多 town 时枚举各 town 的 `TownWarehouseRegistry` 仓库，合并为收货候选。
3. 单 town 时直接取该 town 仓库。

这恰好对应早期澄清「当玩家在多个 town 里就需要拉 town 仓库列表」的需求。
