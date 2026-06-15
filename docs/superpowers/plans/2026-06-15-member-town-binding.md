# 成员-Town 绑定与加入流程重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐缺失的「玩家↔town」绑定维度，重设计为 town-primary 加入流程（玩家先入 town，再随 town 入 nation），作为 P2P 货运 Phase 5 收货候选按 town 收窄的前置依赖。

**Architecture:** 方案 B 双记录 + 事件联动。新增 `TownMemberRecord` 存镇民、`TownMemberInviteRecord` 存入镇请求，均挂在现有 `NationSavedData`（复合字符串键 Map，照搬 `offices`/`invites` 模式）。`NationMemberRecord` 保留不动，其写入由「镇民变更」（`TownMemberService`）与「town↔nation 转换」（`bindTownToNation`/`unbindTownFromNation` 两个唯一收口）驱动。新开 `/town` 顶级指令树。旧档经一次性幂等迁移收编孤儿。

**Tech Stack:** Minecraft Forge 1.20.1, Java 17, Forge `SavedData`（NBT 持久化），Brigadier 命令，JUnit 5 单测。

---

## 基线事实（实现前必读，全部已核实）

**现有真实代码（路径 + 行号 + 签名）：**

- `NationMemberRecord(UUID playerUuid, String lastKnownName, String nationId, String officeId, long joinedAt)` — `nation/model/NationMemberRecord.java`。`save()` 写 `PlayerUuid/LastKnownName/NationId/OfficeId/JoinedAt`；`load(CompoundTag)`；紧凑构造器对 String 做 sanitize（**`officeId`/`nationId` 经 `sanitizeId` 会 `trim().toLowerCase()`**，故所有 id 实际小写存储）。
- `NationOfficeIds.MEMBER = "member"`（小写）— `nation/model/NationOfficeIds.java`。
- `TownNationRequestRecord(String townId, String nationId, String direction, UUID initiatorUuid, long createdAt)` — `nation/model/TownNationRequestRecord.java`。常量 `DIRECTION_INVITE="invite"`/`DIRECTION_APPLY="apply"`（小写）；方法 `isInvite()`/`isApply()`；`save()` 写 `TownId/NationId/Direction/InitiatorUuid/CreatedAt`；`load(CompoundTag)`。
- `TownRecord(String townId, String nationId, String name, UUID mayorUuid, long createdAt, String coreDimension, long corePos, String flagId, String cultureId)` — `nation/model/TownRecord.java`。`name()` 是镇名；`hasNation()` 判 nationId 非空；`TownRecord.noCorePos()`、`TownRecord.normalizeName(String)` 存在。
- `NationSavedData` — `nation/data/NationSavedData.java`。**可 `new NationSavedData()` 脱离 MC 测**（见 `NationCommandsTest:38`）。
  - `load(CompoundTag)` 静态方法（行 64），末尾 `return data;`（行 259）。
  - `save(CompoundTag)`（行 263）。
  - 成员模式：`getMember(UUID)`（638）、`putMember`（645，含 `setDirty()`）、`removeMember`（650）、`getMembersForNation(String)`（659）。
  - office/invite 复合键模式：`offices` Map 键 `officeKey(nationId, officeId)`、`invites` Map 键 `inviteKey(nationId, uuid)`，`putOffice`/`putInvite` 末尾 `setDirty()`。
  - town 查询：`getTown(String)`（555）、`putTown`（562）、`findTownByName(String)`（625）、`getTownsForNation(String)`（601）、`getTownsForMayor(UUID)`（612）、`findNationByName(String)`、`getNation(String)`。
  - town↔nation 请求：`getTownNationRequest(townId, nationId)`、`putTownNationRequest`、`removeTownNationRequest`、`getTownNationRequestsForNation(nationId)`、`clearTownNationRequestsForTown(townId)`。
  - `normalizeId(String)` 私有助手（trim+lowercase）。
- `TownService` — `nation/service/TownService.java`：
  - **`bindTownToNation(NationSavedData, TownRecord, String nationId)`（私有，1182）** 与 **`unbindTownFromNation(NationSavedData, TownRecord)`（私有，1207）** = town↔nation 唯一进出口（6 处 bind、2 处 unbind 调用全收口于此）。
  - `tryCreateTown(ServerPlayer, NationSavedData, String)`（627）建 town，写 `data.putTown(town)`（662），**只写 `mayorUuid`，不写任何成员记录**。
  - `acceptTownApply`（1041）/`rejectTownApply`（1073）/`listTownRequests`（1104）/`kickTownFromNation`（1124）/`leaveTownFromNation`（1153）= town↔nation 请求处理的同构样板（取 actorMember→查权限→查 nation→查 town→校验请求方向→remove+bind/unbind→NationResult）。
  - `NationService.updateKnownPlayer(ServerPlayer)`、`NationService.hasPermission(Level, UUID, NationPermission)` 存在。
- `NationService.joinNation`（418）旧个人入 nation，直接 `putMember(NationMemberRecord(uuid, name, nationId, NationOfficeIds.MEMBER, now))`（437），与 town 无关。
- `NationResult` — `nation/service/NationResult.java`：`NationResult.success(Component)`/`NationResult.failure(Component)`/`.success()` 布尔。
- `NationCommands.register(CommandDispatcher)` — `nation/command/NationCommands.java`。`dispatcher.register(nation)`（310），随后 `dispatcher.register(Commands.literal("townunclaim")...)`（311）、`nationadmin`（319）等独立顶级树。由 `NationEvents:65` 调用。
  - 命令 helper：`sendResult(CommandSourceStack, NationResult)`、`sendLines(CommandSourceStack, List<Component>)`。
- `NationPermission.INVITE_MEMBERS` 枚举值存在（`acceptTownApply` 用）。

**测试样板（已核实可用）：**
- record 往返：`Record.load(record.save())` + `assertEquals`（`nation/model/RoadNetworkRecordTest.java`）。
- SavedData 真行为：`new NationSavedData()` + `putTown(new TownRecord(...))` + 调方法 + 断言（`nation/command/NationCommandsTest.java:37`）。
- 命令注册：`new CommandDispatcher<>()` + `register` + `dispatcher.getRoot().getChild("town")` 非空 + `getCommand()` 非空（`NationCommandsTest:22`）。

**构建/测试命令：**
- 单测：`./gradlew test --tests "com.monpai.sailboatmod.nation.model.TownMemberRecordTest"`（Windows PowerShell 用 `.\gradlew.bat`，但本仓 Bash 工具可用 `./gradlew`）。
- 出 jar：`./gradlew build -x test`（完整 build 会因 6 个无关 web map 测试中断）。

**命名约定（贯穿全计划，避免漂移）：**
- 新 record：`TownMemberRecord`、`TownMemberInviteRecord`。
- 新 service：`TownMemberService`。
- 新迁移：`TownMemberMigration`（`nation/data/` 包）。
- 常量：`TownMemberRecord.OFFICE_MEMBER = "member"`；`TownMemberInviteRecord.DIRECTION_INVITE = "invite"` / `DIRECTION_APPLY = "apply"`。
- NBT 标签：`TownMembers`、`TownMemberRequests`、`townMembersMigrated`。
- Map 字段：`townMembers`（键 `townMemberKey(townId, uuid)` = `townId + "|" + uuid`）、`townMemberInvites`（同键式）。

---

## 阶段 A：数据模型（record + SavedData 存储）

### Task A1: `TownMemberRecord`

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/model/TownMemberRecord.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/model/TownMemberRecordTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownMemberRecordTest {
    @Test
    void saveLoadRoundTrip() {
        UUID player = UUID.randomUUID();
        TownMemberRecord record = new TownMemberRecord(player, "Crimea", TownMemberRecord.OFFICE_MEMBER, 123L);

        TownMemberRecord loaded = TownMemberRecord.load(record.save());

        assertEquals(player, loaded.playerUuid());
        assertEquals("crimea", loaded.townId());
        assertEquals("member", loaded.officeId());
        assertEquals(123L, loaded.joinedAt());
    }

    @Test
    void loadOldTagWithMissingFieldsDefaultsBlank() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerUuid", new UUID(0L, 0L));

        TownMemberRecord loaded = TownMemberRecord.load(tag);

        assertEquals("", loaded.townId());
        assertEquals("", loaded.officeId());
        assertEquals(0L, loaded.joinedAt());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.model.TownMemberRecordTest"`
Expected: FAIL — `TownMemberRecord` 不存在（编译错误）。

- [ ] **Step 3: Write minimal implementation**

```java
package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record TownMemberRecord(
        UUID playerUuid,
        String townId,
        String officeId,
        long joinedAt
) {
    public static final String OFFICE_MEMBER = "member";

    public TownMemberRecord {
        playerUuid = playerUuid == null ? new UUID(0L, 0L) : playerUuid;
        townId = sanitizeId(townId);
        officeId = sanitizeId(officeId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerUuid", playerUuid);
        tag.putString("TownId", townId);
        tag.putString("OfficeId", officeId);
        tag.putLong("JoinedAt", joinedAt);
        return tag;
    }

    public static TownMemberRecord load(CompoundTag tag) {
        UUID playerUuid = tag.hasUUID("PlayerUuid") ? tag.getUUID("PlayerUuid") : new UUID(0L, 0L);
        return new TownMemberRecord(
                playerUuid,
                tag.getString("TownId"),
                tag.getString("OfficeId"),
                tag.getLong("JoinedAt")
        );
    }

    private static String sanitizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.model.TownMemberRecordTest"`
Expected: PASS（2 个测试）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/model/TownMemberRecord.java src/test/java/com/monpai/sailboatmod/nation/model/TownMemberRecordTest.java
git commit -m "feat: add TownMemberRecord (player-town binding)"
```

### Task A2: `TownMemberInviteRecord`

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/model/TownMemberInviteRecord.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/model/TownMemberInviteRecordTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownMemberInviteRecordTest {
    @Test
    void saveLoadRoundTripAndDirectionHelpers() {
        UUID player = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        TownMemberInviteRecord invite = new TownMemberInviteRecord(
                "Crimea", player, TownMemberInviteRecord.DIRECTION_INVITE, initiator, 50L);

        TownMemberInviteRecord loaded = TownMemberInviteRecord.load(invite.save());

        assertEquals("crimea", loaded.townId());
        assertEquals(player, loaded.playerUuid());
        assertEquals(initiator, loaded.initiatorUuid());
        assertEquals(50L, loaded.createdAt());
        assertTrue(loaded.isInvite());
        assertFalse(loaded.isApply());
    }

    @Test
    void applyDirectionHelper() {
        TownMemberInviteRecord apply = new TownMemberInviteRecord(
                "t", new UUID(0L, 0L), TownMemberInviteRecord.DIRECTION_APPLY, new UUID(0L, 0L), 1L);
        assertTrue(apply.isApply());
        assertFalse(apply.isInvite());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.model.TownMemberInviteRecordTest"`
Expected: FAIL — `TownMemberInviteRecord` 不存在。

- [ ] **Step 3: Write minimal implementation**

```java
package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record TownMemberInviteRecord(
        String townId,
        UUID playerUuid,
        String direction,
        UUID initiatorUuid,
        long createdAt
) {
    public static final String DIRECTION_INVITE = "invite";
    public static final String DIRECTION_APPLY = "apply";

    public TownMemberInviteRecord {
        townId = sanitizeId(townId);
        playerUuid = playerUuid == null ? new UUID(0L, 0L) : playerUuid;
        direction = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        initiatorUuid = initiatorUuid == null ? new UUID(0L, 0L) : initiatorUuid;
    }

    public boolean isInvite() {
        return DIRECTION_INVITE.equals(direction);
    }

    public boolean isApply() {
        return DIRECTION_APPLY.equals(direction);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("TownId", townId);
        tag.putUUID("PlayerUuid", playerUuid);
        tag.putString("Direction", direction);
        tag.putUUID("InitiatorUuid", initiatorUuid);
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static TownMemberInviteRecord load(CompoundTag tag) {
        UUID playerUuid = tag.hasUUID("PlayerUuid") ? tag.getUUID("PlayerUuid") : new UUID(0L, 0L);
        UUID initiatorUuid = tag.hasUUID("InitiatorUuid") ? tag.getUUID("InitiatorUuid") : new UUID(0L, 0L);
        return new TownMemberInviteRecord(
                tag.getString("TownId"),
                playerUuid,
                tag.getString("Direction"),
                initiatorUuid,
                tag.getLong("CreatedAt")
        );
    }

    private static String sanitizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.model.TownMemberInviteRecordTest"`
Expected: PASS（2 个测试）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/model/TownMemberInviteRecord.java src/test/java/com/monpai/sailboatmod/nation/model/TownMemberInviteRecordTest.java
git commit -m "feat: add TownMemberInviteRecord (apply/invite request)"
```

### Task A3: `NationSavedData` townMembers 存储 + 查询

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/data/NationSavedData.java`（加字段、键助手、getter/putter/remover/查询；不含 save/load，save/load 在 Task A4）
- Test: `src/test/java/com/monpai/sailboatmod/nation/data/NationSavedDataTownMemberTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationSavedDataTownMemberTest {
    @Test
    void putGetRemoveTownMember() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));

        assertEquals(player, data.getTownMember("crimea", player).playerUuid());

        data.removeTownMember("crimea", player);
        assertNull(data.getTownMember("crimea", player));
    }

    @Test
    void getTownMembersForTownIsolatesByTown() {
        NationSavedData data = new NationSavedData();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        data.putTownMember(new TownMemberRecord(p1, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMember(new TownMemberRecord(p2, "crimea", TownMemberRecord.OFFICE_MEMBER, 2L));
        data.putTownMember(new TownMemberRecord(p1, "kerch", TownMemberRecord.OFFICE_MEMBER, 3L));

        assertEquals(2, data.getTownMembersForTown("crimea").size());
        assertEquals(1, data.getTownMembersForTown("kerch").size());
    }

    @Test
    void getTownsForPlayerReturnsAllTowns() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMember(new TownMemberRecord(player, "kerch", TownMemberRecord.OFFICE_MEMBER, 2L));

        List<String> towns = data.getTownsForPlayer(player);
        assertEquals(2, towns.size());
        assertTrue(towns.contains("crimea"));
        assertTrue(towns.contains("kerch"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.data.NationSavedDataTownMemberTest"`
Expected: FAIL — `putTownMember`/`getTownMember` 等不存在。

- [ ] **Step 3: Write minimal implementation**

在 `NationSavedData.java` 字段声明区（与 `members`/`offices`/`invites` Map 相邻处）加：

```java
    private final Map<String, TownMemberRecord> townMembers = new HashMap<>();
    private final Map<String, TownMemberInviteRecord> townMemberInvites = new HashMap<>();
    private boolean townMembersMigrated = false;
```

确保文件顶部 import：

```java
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownMemberInviteRecord;
```

在成员方法区（`getMembersForNation` 之后，行 668 附近）加键助手 + townMember 方法：

```java
    private static String townMemberKey(String townId, UUID playerUuid) {
        if (townId == null || playerUuid == null) {
            return "";
        }
        String normalized = townId.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "" : normalized + "|" + playerUuid;
    }

    public TownMemberRecord getTownMember(String townId, UUID playerUuid) {
        String key = townMemberKey(townId, playerUuid);
        return key.isBlank() ? null : townMembers.get(key);
    }

    public void putTownMember(TownMemberRecord member) {
        if (member == null) {
            return;
        }
        String key = townMemberKey(member.townId(), member.playerUuid());
        if (key.isBlank()) {
            return;
        }
        townMembers.put(key, member);
        setDirty();
    }

    public void removeTownMember(String townId, UUID playerUuid) {
        String key = townMemberKey(townId, playerUuid);
        if (!key.isBlank() && townMembers.remove(key) != null) {
            setDirty();
        }
    }

    public List<TownMemberRecord> getTownMembersForTown(String townId) {
        String normalized = normalizeId(townId);
        List<TownMemberRecord> result = new ArrayList<>();
        for (TownMemberRecord member : townMembers.values()) {
            if (normalized.equals(member.townId())) {
                result.add(member);
            }
        }
        return result;
    }

    public List<String> getTownsForPlayer(UUID playerUuid) {
        List<String> result = new ArrayList<>();
        if (playerUuid == null) {
            return result;
        }
        for (TownMemberRecord member : townMembers.values()) {
            if (playerUuid.equals(member.playerUuid()) && !result.contains(member.townId())) {
                result.add(member.townId());
            }
        }
        return result;
    }

    public List<TownMemberRecord> getTownMembersForPlayer(UUID playerUuid) {
        List<TownMemberRecord> result = new ArrayList<>();
        if (playerUuid == null) {
            return result;
        }
        for (TownMemberRecord member : townMembers.values()) {
            if (playerUuid.equals(member.playerUuid())) {
                result.add(member);
            }
        }
        return result;
    }
```

> 注：`Locale` 已被现有代码 import（office load 处用过）。若编译报 `Locale` 未导入，在文件顶部加 `import java.util.Locale;`。`HashMap`/`ArrayList`/`List`/`Map`/`UUID` 现有文件已 import。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.data.NationSavedDataTownMemberTest"`
Expected: PASS（3 个测试）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/data/NationSavedData.java src/test/java/com/monpai/sailboatmod/nation/data/NationSavedDataTownMemberTest.java
git commit -m "feat: add townMembers storage + queries to NationSavedData"
```

### Task A4: `NationSavedData` townMemberInvites 存储 + save/load 序列化

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/data/NationSavedData.java`（townMemberInvites 方法 + save/load 织入三项 NBT）
- Test: `src/test/java/com/monpai/sailboatmod/nation/data/NationSavedDataTownMemberPersistenceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.TownMemberInviteRecord;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationSavedDataTownMemberPersistenceTest {
    @Test
    void townMembersAndInvitesSurviveSaveLoad() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 7L));
        data.putTownMemberInvite(new TownMemberInviteRecord(
                "crimea", player, TownMemberInviteRecord.DIRECTION_APPLY, player, 9L));
        data.markTownMembersMigratedForTest();

        NationSavedData loaded = NationSavedData.load(data.save(new CompoundTag()));

        assertNotNull(loaded.getTownMember("crimea", player));
        assertEquals(7L, loaded.getTownMember("crimea", player).joinedAt());
        assertNotNull(loaded.getTownMemberInvite("crimea", player));
        assertTrue(loaded.getTownMemberInvite("crimea", player).isApply());
        assertTrue(loaded.isTownMembersMigratedForTest());
    }

    @Test
    void oldSaveWithoutTownMemberTagsLoadsEmptyAndUnmigrated() {
        NationSavedData loaded = NationSavedData.load(new CompoundTag());

        assertTrue(loaded.getTownMembersForTown("crimea").isEmpty());
        assertFalse(loaded.isTownMembersMigratedForTest());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.data.NationSavedDataTownMemberPersistenceTest"`
Expected: FAIL — `putTownMemberInvite`/`getTownMemberInvite`/save-load 标签缺失。

- [ ] **Step 3: Write minimal implementation**

在 townMember 方法区后追加 invite 方法 + 迁移标记 + 测试钩子：

```java
    private TownMemberInviteRecord putInviteKeyed(TownMemberInviteRecord invite) {
        return invite;
    }

    public TownMemberInviteRecord getTownMemberInvite(String townId, UUID playerUuid) {
        String key = townMemberKey(townId, playerUuid);
        return key.isBlank() ? null : townMemberInvites.get(key);
    }

    public void putTownMemberInvite(TownMemberInviteRecord invite) {
        if (invite == null) {
            return;
        }
        String key = townMemberKey(invite.townId(), invite.playerUuid());
        if (key.isBlank()) {
            return;
        }
        townMemberInvites.put(key, invite);
        setDirty();
    }

    public void removeTownMemberInvite(String townId, UUID playerUuid) {
        String key = townMemberKey(townId, playerUuid);
        if (!key.isBlank() && townMemberInvites.remove(key) != null) {
            setDirty();
        }
    }

    public List<TownMemberInviteRecord> getTownMemberInvitesForTown(String townId) {
        String normalized = normalizeId(townId);
        List<TownMemberInviteRecord> result = new ArrayList<>();
        for (TownMemberInviteRecord invite : townMemberInvites.values()) {
            if (normalized.equals(invite.townId())) {
                result.add(invite);
            }
        }
        return result;
    }

    public boolean isTownMembersMigrated() {
        return townMembersMigrated;
    }

    public void setTownMembersMigrated(boolean value) {
        if (townMembersMigrated != value) {
            townMembersMigrated = value;
            setDirty();
        }
    }

    // 测试钩子
    public void markTownMembersMigratedForTest() {
        this.townMembersMigrated = true;
    }

    public boolean isTownMembersMigratedForTest() {
        return townMembersMigrated;
    }
```

> 删掉上面那个误加的 `putInviteKeyed` 占位（写实现时不要包含它——它仅为标示位置，实际不需要）。

在 `load(CompoundTag)` 方法内、`return data;`（行 259）**之前**，照搬现有 ListTag 读取模式加：

```java
        ListTag townMemberTag = tag.getList("TownMembers", Tag.TAG_COMPOUND);
        for (Tag raw : townMemberTag) {
            if (raw instanceof CompoundTag compound) {
                TownMemberRecord member = TownMemberRecord.load(compound);
                String key = townMemberKey(member.townId(), member.playerUuid());
                if (!key.isBlank()) {
                    data.townMembers.put(key, member);
                }
            }
        }

        ListTag townMemberInviteTag = tag.getList("TownMemberRequests", Tag.TAG_COMPOUND);
        for (Tag raw : townMemberInviteTag) {
            if (raw instanceof CompoundTag compound) {
                TownMemberInviteRecord invite = TownMemberInviteRecord.load(compound);
                String key = townMemberKey(invite.townId(), invite.playerUuid());
                if (!key.isBlank()) {
                    data.townMemberInvites.put(key, invite);
                }
            }
        }

        data.townMembersMigrated = tag.getBoolean("townMembersMigrated");
```

在 `save(CompoundTag)` 方法内、`return tag;` 之前，照搬现有 save 模式加：

```java
        ListTag townMemberTag = new ListTag();
        for (TownMemberRecord member : townMembers.values()) {
            townMemberTag.add(member.save());
        }
        tag.put("TownMembers", townMemberTag);

        ListTag townMemberInviteTag = new ListTag();
        for (TownMemberInviteRecord invite : townMemberInvites.values()) {
            townMemberInviteTag.add(invite.save());
        }
        tag.put("TownMemberRequests", townMemberInviteTag);

        tag.putBoolean("townMembersMigrated", townMembersMigrated);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.data.NationSavedDataTownMemberPersistenceTest"`
Expected: PASS（2 个测试）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/data/NationSavedData.java src/test/java/com/monpai/sailboatmod/nation/data/NationSavedDataTownMemberPersistenceTest.java
git commit -m "feat: persist townMembers/invites + migration flag in NationSavedData"
```

---

## 阶段 B：全量联动（town↔nation 转换驱动 nation 籍）

### Task B1: `bindTownToNation` 联动——全镇民入 nation

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TownService.java`（`bindTownToNation` 末尾追加；加私有助手 `nameForTownMember`）
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/TownMemberCascadeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TownMemberCascadeTest {
    @Test
    void bindTownToNationGivesAllTownMembersNationMembership() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID resident = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 0x111111, 0x222222, mayor, 1L, "", "", NationRecord.noCorePos(), ""));
        TownRecord town = new TownRecord("crimea", "", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(town);
        data.putTownMember(new TownMemberRecord(mayor, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMember(new TownMemberRecord(resident, "crimea", TownMemberRecord.OFFICE_MEMBER, 2L));

        TownService.bindTownToNationForTest(data, town, "alpha");

        assertNotNull(data.getMember(mayor));
        assertEquals("alpha", data.getMember(mayor).nationId());
        assertNotNull(data.getMember(resident));
        assertEquals("alpha", data.getMember(resident).nationId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberCascadeTest"`
Expected: FAIL — `bindTownToNationForTest` 不存在。

- [ ] **Step 3: Write minimal implementation**

在 `TownService.java` 的 `bindTownToNation`（行 1182）方法末尾、`data.clearTownNationRequestsForTown(town.townId());`（1204）**之后**、方法闭合 `}`（1205）之前追加：

```java
        for (TownMemberRecord townMember : data.getTownMembersForTown(updated.townId())) {
            NationMemberRecord existing = data.getMember(townMember.playerUuid());
            if (existing == null || !nationId.equals(existing.nationId())) {
                data.putMember(new NationMemberRecord(
                        townMember.playerUuid(),
                        nameForTownMember(data, townMember.playerUuid(), existing),
                        nationId,
                        NationOfficeIds.MEMBER,
                        System.currentTimeMillis()));
            }
        }
```

在 `TownService` 类内（`unbindTownFromNation` 附近）加私有助手 + 测试钩子：

```java
    private static String nameForTownMember(NationSavedData data, UUID playerUuid, NationMemberRecord existing) {
        if (existing != null && !existing.lastKnownName().isBlank()) {
            return existing.lastKnownName();
        }
        NationMemberRecord member = data.getMember(playerUuid);
        return member == null ? "" : member.lastKnownName();
    }

    static void bindTownToNationForTest(NationSavedData data, TownRecord town, String nationId) {
        bindTownToNation(data, town, nationId);
    }
```

确认 `TownService.java` 顶部已 import `TownMemberRecord`、`NationMemberRecord`、`NationOfficeIds`。若缺，加：

```java
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeIds;
```

> 注：`bindTownToNation` 内变量 `updated` 是重写后的 TownRecord（townId 不变），用它取镇民等价于用 `town.townId()`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberCascadeTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/TownService.java src/test/java/com/monpai/sailboatmod/nation/service/TownMemberCascadeTest.java
git commit -m "feat: cascade town members into nation on bindTownToNation"
```

### Task B2: `unbindTownFromNation` 联动——镇民退籍 + 多 town 保护

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TownService.java`（`unbindTownFromNation` 开头取 leavingNationId、末尾追加退籍循环；加 `stillInNationViaOtherTown` 助手 + 测试钩子）
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/TownMemberCascadeTest.java`（追加测试方法）

- [ ] **Step 1: Write the failing test（追加到 TownMemberCascadeTest）**

```java
    @Test
    void unbindTownFromNationRemovesNationMembership() {
        NationSavedData data = new NationSavedData();
        UUID resident = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 0x111111, 0x222222, resident, 1L, "", "", NationRecord.noCorePos(), ""));
        TownRecord town = new TownRecord("crimea", "alpha", "Crimea", resident, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(town);
        data.putTownMember(new TownMemberRecord(resident, "crimea", TownMemberRecord.OFFICE_MEMBER, 2L));
        data.putMember(new NationMemberRecord(resident, "Res", "alpha", "member", 2L));

        TownService.unbindTownFromNationForTest(data, town);

        org.junit.jupiter.api.Assertions.assertNull(data.getMember(resident));
    }

    @Test
    void unbindKeepsNationMembershipIfPlayerInAnotherTownOfSameNation() {
        NationSavedData data = new NationSavedData();
        UUID resident = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 0x111111, 0x222222, resident, 1L, "", "", NationRecord.noCorePos(), ""));
        TownRecord leaving = new TownRecord("crimea", "alpha", "Crimea", resident, 1L, "", TownRecord.noCorePos(), "", "european");
        TownRecord other = new TownRecord("kerch", "alpha", "Kerch", resident, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(leaving);
        data.putTown(other);
        data.putTownMember(new TownMemberRecord(resident, "crimea", TownMemberRecord.OFFICE_MEMBER, 2L));
        data.putTownMember(new TownMemberRecord(resident, "kerch", TownMemberRecord.OFFICE_MEMBER, 3L));
        data.putMember(new NationMemberRecord(resident, "Res", "alpha", "member", 2L));

        TownService.unbindTownFromNationForTest(data, leaving);

        assertNotNull(data.getMember(resident));
        assertEquals("alpha", data.getMember(resident).nationId());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberCascadeTest"`
Expected: FAIL — `unbindTownFromNationForTest` 不存在。

- [ ] **Step 3: Write minimal implementation**

在 `unbindTownFromNation`（行 1207）方法内：先在最开头取 leavingNationId（在 `data.putTown(updated);` 重写之前），然后在方法末尾（`for (NationClaimRecord claim : claims)` 循环之后、方法闭合之前）加退籍循环。改写后的方法体：

```java
    private static void unbindTownFromNation(NationSavedData data, TownRecord town) {
        String leavingNationId = town.nationId();  // 重写前先取
        List<NationClaimRecord> claims = managedClaimsForNationRewrite(data, town, town);
        TownRecord updated = new TownRecord(
                town.townId(), "", town.name(), town.mayorUuid(), town.createdAt(),
                town.coreDimension(), town.corePos(), town.flagId(), town.cultureId()
        );
        data.putTown(updated);

        for (NationClaimRecord claim : claims) {
            data.putClaim(rewriteTownClaim(claim, "", town.townId()));
        }

        if (!leavingNationId.isBlank()) {
            for (TownMemberRecord townMember : data.getTownMembersForTown(town.townId())) {
                if (stillInNationViaOtherTown(data, townMember.playerUuid(), leavingNationId, town.townId())) {
                    continue;
                }
                NationMemberRecord existing = data.getMember(townMember.playerUuid());
                if (existing != null && leavingNationId.equals(existing.nationId())) {
                    data.removeMember(townMember.playerUuid());
                }
            }
        }
    }

    private static boolean stillInNationViaOtherTown(NationSavedData data, UUID playerUuid, String nationId, String excludingTownId) {
        for (String townId : data.getTownsForPlayer(playerUuid)) {
            if (townId.equals(excludingTownId)) {
                continue;
            }
            TownRecord other = data.getTown(townId);
            if (other != null && nationId.equals(other.nationId())) {
                return true;
            }
        }
        return false;
    }

    static void unbindTownFromNationForTest(NationSavedData data, TownRecord town) {
        unbindTownFromNation(data, town);
    }
```

> 注：`leavingNationId` 取自 `town.nationId()`（已被 sanitizeId 小写化），与 `existing.nationId()`（同样小写）可直接 equals。`excludingTownId` 用 `town.townId()`，与 `getTownsForPlayer` 返回的（已小写）一致。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberCascadeTest"`
Expected: PASS（3 个测试全过）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/TownService.java src/test/java/com/monpai/sailboatmod/nation/service/TownMemberCascadeTest.java
git commit -m "feat: remove nation membership on unbind, protect multi-town members"
```

### Task B3: `tryCreateTown` 建 town 补镇长为首位镇民

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TownService.java`（`tryCreateTown` 在 `data.putTown(town)` 后写 putTownMember）
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/TownMemberCascadeTest.java`（追加；改用现有 `tryCreateTown` 不便脱 MC，故此处用源码契约断言补充）

- [ ] **Step 1: Write the failing test（新建源码契约测试，因 tryCreateTown 需 ServerPlayer 不易单测）**

Create: `src/test/java/com/monpai/sailboatmod/nation/service/TownCreateMemberWiringTest.java`

```java
package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TownCreateMemberWiringTest {
    @Test
    void tryCreateTownRegistersMayorAsTownMember() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/nation/service/TownService.java"));
        int createIdx = src.indexOf("private static TownCreationOutcome tryCreateTown");
        assertTrue(createIdx >= 0, "tryCreateTown should exist");
        String body = src.substring(createIdx);
        assertTrue(body.contains("putTownMember(new TownMemberRecord("),
                "tryCreateTown should register the mayor as a town member via putTownMember");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownCreateMemberWiringTest"`
Expected: FAIL — `tryCreateTown` 尚未调 `putTownMember`。

- [ ] **Step 3: Write minimal implementation**

在 `tryCreateTown`（行 627）中，`data.putTown(town);`（662）**之后**紧接加：

```java
        data.putTownMember(new TownMemberRecord(
                actor.getUUID(), town.townId(), TownMemberRecord.OFFICE_MEMBER, now));
```

（`now` 变量在 650 已定义；`town` 在 651 已构造。）

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownCreateMemberWiringTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/TownService.java src/test/java/com/monpai/sailboatmod/nation/service/TownCreateMemberWiringTest.java
git commit -m "feat: register mayor as first town member on town creation"
```

---

## 阶段 C：`TownMemberService`（玩家↔town 加入流程 + nation 联动）

### Task C1: `applyToTown` / `invitePlayer`（双向请求，相遇成交）

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/TownMemberService.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/TownMemberServiceTest.java`

本任务实现的核心逻辑用「纯 data + uuid」的内部静态方法，便于脱 MC 单测；公开的 `ServerPlayer` 重载在 Task C4 加。

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownMemberInviteRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownMemberServiceTest {
    private static TownRecord standaloneTown(NationSavedData data, UUID mayor) {
        TownRecord town = new TownRecord("crimea", "", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(town);
        return town;
    }

    @Test
    void applyCreatesApplyRequest() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        standaloneTown(data, UUID.randomUUID());

        TownMemberService.applyToTownForTest(data, player, "crimea");

        TownMemberInviteRecord req = data.getTownMemberInvite("crimea", player);
        assertNotNull(req);
        assertTrue(req.isApply());
        assertNull(data.getTownMember("crimea", player));  // 未直接入镇
    }

    @Test
    void inviteThenApplyMergesAndAddsMember() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        standaloneTown(data, mayor);

        TownMemberService.inviteToTownForTest(data, mayor, "crimea", player);
        TownMemberService.applyToTownForTest(data, player, "crimea");

        assertNotNull(data.getTownMember("crimea", player));  // 相遇成交
        assertNull(data.getTownMemberInvite("crimea", player));  // 请求已清
    }

    @Test
    void applyThenInviteMergesAndAddsMember() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        standaloneTown(data, mayor);

        TownMemberService.applyToTownForTest(data, player, "crimea");
        TownMemberService.inviteToTownForTest(data, mayor, "crimea", player);

        assertNotNull(data.getTownMember("crimea", player));
        assertNull(data.getTownMemberInvite("crimea", player));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberServiceTest"`
Expected: FAIL — `TownMemberService` 不存在。

- [ ] **Step 3: Write minimal implementation**

```java
package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownMemberInviteRecord;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class TownMemberService {

    private TownMemberService() {
    }

    // ---- 玩家申请加入 town ----
    public static NationResult applyToTown(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = data.findTownByName(rawTownName);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.not_found", rawTownName));
        }
        return applyToTownInternal(data, actor.getUUID(), town);
    }

    static NationResult applyToTownForTest(NationSavedData data, UUID playerUuid, String townId) {
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.not_found", townId));
        }
        return applyToTownInternal(data, playerUuid, town);
    }

    private static NationResult applyToTownInternal(NationSavedData data, UUID playerUuid, TownRecord town) {
        if (data.getTownMember(town.townId(), playerUuid) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.already_member", town.name()));
        }
        TownMemberInviteRecord existing = data.getTownMemberInvite(town.townId(), playerUuid);
        if (existing != null && existing.isInvite()) {
            data.removeTownMemberInvite(town.townId(), playerUuid);
            return completeJoin(data, playerUuid, town);
        }
        if (existing != null && existing.isApply()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.apply.already_sent", town.name()));
        }
        data.putTownMemberInvite(new TownMemberInviteRecord(
                town.townId(), playerUuid, TownMemberInviteRecord.DIRECTION_APPLY, playerUuid, System.currentTimeMillis()));
        return NationResult.success(Component.translatable("command.sailboatmod.town.apply.success", town.name()));
    }

    // ---- 镇长邀请玩家入 town ----
    public static NationResult invitePlayer(ServerPlayer actor, ServerPlayer target) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        NationService.updateKnownPlayer(target);
        TownRecord town = firstTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return inviteToTownInternal(data, town, target.getUUID());
    }

    static NationResult inviteToTownForTest(NationSavedData data, UUID mayorUuid, String townId, UUID targetUuid) {
        TownRecord town = data.getTown(townId);
        if (town == null || !mayorUuid.equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return inviteToTownInternal(data, town, targetUuid);
    }

    private static NationResult inviteToTownInternal(NationSavedData data, TownRecord town, UUID targetUuid) {
        if (data.getTownMember(town.townId(), targetUuid) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.already_member", town.name()));
        }
        TownMemberInviteRecord existing = data.getTownMemberInvite(town.townId(), targetUuid);
        if (existing != null && existing.isApply()) {
            data.removeTownMemberInvite(town.townId(), targetUuid);
            return completeJoin(data, targetUuid, town);
        }
        if (existing != null && existing.isInvite()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.already_sent", town.name()));
        }
        data.putTownMemberInvite(new TownMemberInviteRecord(
                town.townId(), targetUuid, TownMemberInviteRecord.DIRECTION_INVITE, targetUuid, System.currentTimeMillis()));
        return NationResult.success(Component.translatable("command.sailboatmod.town.invite.success", town.name()));
    }

    // ---- 写入镇民 + 随 town 入 nation 联动 ----
    static NationResult completeJoin(NationSavedData data, UUID playerUuid, TownRecord town) {
        data.putTownMember(new TownMemberRecord(
                playerUuid, town.townId(), TownMemberRecord.OFFICE_MEMBER, System.currentTimeMillis()));
        TownService.syncPlayerNationWithTown(data, playerUuid, town);
        return NationResult.success(Component.translatable("command.sailboatmod.town.join.success", town.name()));
    }

    private static TownRecord firstTownForMayor(NationSavedData data, UUID mayorUuid) {
        for (TownRecord town : data.getTownsForMayor(mayorUuid)) {
            return town;
        }
        return null;
    }
}
```

并在 `TownService` 中加被 `completeJoin` 调用的联动入口（公开静态，纯 data 操作）：

```java
    public static void syncPlayerNationWithTown(NationSavedData data, UUID playerUuid, TownRecord town) {
        if (data == null || playerUuid == null || town == null || !town.hasNation()) {
            return;  // 独立 town：只写镇民，不挂 nation
        }
        String nationId = town.nationId();
        NationMemberRecord existing = data.getMember(playerUuid);
        if (existing == null || !nationId.equals(existing.nationId())) {
            data.putMember(new NationMemberRecord(
                    playerUuid,
                    nameForTownMember(data, playerUuid, existing),
                    nationId,
                    NationOfficeIds.MEMBER,
                    System.currentTimeMillis()));
        }
    }
```

> 注：`getTownsForMayor` 返回 `List<TownRecord>`（NationSavedData:612 已确认）。`syncPlayerNationWithTown` 复用 Task B1 的 `nameForTownMember` 助手。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberServiceTest"`
Expected: PASS（3 个测试）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/TownMemberService.java src/main/java/com/monpai/sailboatmod/nation/service/TownService.java src/test/java/com/monpai/sailboatmod/nation/service/TownMemberServiceTest.java
git commit -m "feat: TownMemberService apply/invite with merge + nation sync"
```

### Task C2: `joinTown` 联动——入已属 nation 的 town 即获 nation 籍

**Files:**
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/TownMemberServiceTest.java`（追加）
- Modify: 无新代码（`completeJoin` + `syncPlayerNationWithTown` 已在 C1 实现；本任务验证联动正确）

- [ ] **Step 1: Write the failing test（追加到 TownMemberServiceTest）**

```java
    @Test
    void joinTownAlreadyInNationGrantsNationMembership() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        data.putNation(new com.monpai.sailboatmod.nation.model.NationRecord(
                "alpha", "Alpha", "ALP", 0x111111, 0x222222, mayor, 1L, "", "", com.monpai.sailboatmod.nation.model.NationRecord.noCorePos(), ""));
        TownRecord town = new TownRecord("crimea", "alpha", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(town);

        // 镇长先邀请、玩家申请 → 相遇成交 → completeJoin → 联动
        TownMemberService.inviteToTownForTest(data, mayor, "crimea", player);
        TownMemberService.applyToTownForTest(data, player, "crimea");

        assertNotNull(data.getTownMember("crimea", player));
        assertNotNull(data.getMember(player));
        org.junit.jupiter.api.Assertions.assertEquals("alpha", data.getMember(player).nationId());
    }

    @Test
    void joinStandaloneTownGivesNoNationMembership() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        standaloneTown(data, mayor);  // nationId=""

        TownMemberService.inviteToTownForTest(data, mayor, "crimea", player);
        TownMemberService.applyToTownForTest(data, player, "crimea");

        assertNotNull(data.getTownMember("crimea", player));
        assertNull(data.getMember(player));  // 独立 town 无 nation 籍
    }
```

- [ ] **Step 2: Run test to verify it fails or passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberServiceTest"`
Expected: PASS（C1 的 `syncPlayerNationWithTown` 已正确实现此联动；若 FAIL 说明 C1 联动有 bug，回 C1 修）。

- [ ] **Step 3: （无新实现，仅确认）**

若 Step 2 通过则跳过。若不通过，检查 `TownService.syncPlayerNationWithTown` 的 `town.hasNation()` 分支与 `nationId` 取值。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberServiceTest"`
Expected: PASS（5 个测试全过）。

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/monpai/sailboatmod/nation/service/TownMemberServiceTest.java
git commit -m "test: verify join-town nation cascade (in-nation vs standalone)"
```

### Task C3: `leaveTown` / `kickMember`——退镇 + 条件退籍

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TownMemberService.java`（加 leaveTown/kickMember 内部逻辑）
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/TownMemberServiceTest.java`（追加）

- [ ] **Step 1: Write the failing test（追加）**

```java
    @Test
    void leaveTownRemovesMemberAndNationMembership() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        data.putNation(new com.monpai.sailboatmod.nation.model.NationRecord(
                "alpha", "Alpha", "ALP", 0x111111, 0x222222, mayor, 1L, "", "", com.monpai.sailboatmod.nation.model.NationRecord.noCorePos(), ""));
        TownRecord town = new TownRecord("crimea", "alpha", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(town);
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putMember(new com.monpai.sailboatmod.nation.model.NationMemberRecord(player, "P", "alpha", "member", 1L));

        TownMemberService.leaveTownForTest(data, player, "crimea");

        assertNull(data.getTownMember("crimea", player));
        assertNull(data.getMember(player));  // 不再属任何此 nation 的 town → 退籍
    }

    @Test
    void leaveTownKeepsNationIfStillInAnotherTown() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        data.putNation(new com.monpai.sailboatmod.nation.model.NationRecord(
                "alpha", "Alpha", "ALP", 0x111111, 0x222222, mayor, 1L, "", "", com.monpai.sailboatmod.nation.model.NationRecord.noCorePos(), ""));
        data.putTown(new TownRecord("crimea", "alpha", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putTown(new TownRecord("kerch", "alpha", "Kerch", mayor, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMember(new TownMemberRecord(player, "kerch", TownMemberRecord.OFFICE_MEMBER, 2L));
        data.putMember(new com.monpai.sailboatmod.nation.model.NationMemberRecord(player, "P", "alpha", "member", 1L));

        TownMemberService.leaveTownForTest(data, player, "crimea");

        assertNull(data.getTownMember("crimea", player));
        assertNotNull(data.getMember(player));  // 仍在 kerch（同 nation）→ 保留
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberServiceTest"`
Expected: FAIL — `leaveTownForTest` 不存在。

- [ ] **Step 3: Write minimal implementation**

在 `TownMemberService` 加：

```java
    public static NationResult leaveTown(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = data.findTownByName(rawTownName);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.not_found", rawTownName));
        }
        return leaveTownInternal(data, actor.getUUID(), town);
    }

    static NationResult leaveTownForTest(NationSavedData data, UUID playerUuid, String townId) {
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.not_found", townId));
        }
        return leaveTownInternal(data, playerUuid, town);
    }

    private static NationResult leaveTownInternal(NationSavedData data, UUID playerUuid, TownRecord town) {
        if (data.getTownMember(town.townId(), playerUuid) == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.leave.not_member", town.name()));
        }
        data.removeTownMember(town.townId(), playerUuid);
        TownService.demotePlayerNationIfOrphaned(data, playerUuid, town);
        return NationResult.success(Component.translatable("command.sailboatmod.town.leave.success", town.name()));
    }

    public static NationResult kickMember(ServerPlayer actor, ServerPlayer target) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = firstTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        if (data.getTownMember(town.townId(), target.getUUID()) == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.kick.not_member", town.name()));
        }
        if (target.getUUID().equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.kick.is_mayor"));
        }
        data.removeTownMember(town.townId(), target.getUUID());
        TownService.demotePlayerNationIfOrphaned(data, target.getUUID(), town);
        return NationResult.success(Component.translatable("command.sailboatmod.town.kick.success", town.name()));
    }
```

在 `TownService` 加退籍助手（复用 B2 的 `stillInNationViaOtherTown`）：

```java
    public static void demotePlayerNationIfOrphaned(NationSavedData data, UUID playerUuid, TownRecord leftTown) {
        if (data == null || playerUuid == null || leftTown == null || !leftTown.hasNation()) {
            return;
        }
        String nationId = leftTown.nationId();
        if (stillInNationViaOtherTown(data, playerUuid, nationId, leftTown.townId())) {
            return;
        }
        NationMemberRecord existing = data.getMember(playerUuid);
        if (existing != null && nationId.equals(existing.nationId())) {
            data.removeMember(playerUuid);
        }
    }
```

> 注：`stillInNationViaOtherTown` 在 B2 已加为 `private static`；本任务调用方 `demotePlayerNationIfOrphaned` 同在 `TownService` 内，可直接调，无需改可见性。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberServiceTest"`
Expected: PASS（7 个测试全过）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/TownMemberService.java src/main/java/com/monpai/sailboatmod/nation/service/TownService.java src/test/java/com/monpai/sailboatmod/nation/service/TownMemberServiceTest.java
git commit -m "feat: leaveTown/kickMember with orphan nation demotion"
```

### Task C4: 接受/拒绝/退邀 + 列表查询（accept/reject/decline/list/members/requests）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TownMemberService.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/TownMemberServiceTest.java`（追加）

- [ ] **Step 1: Write the failing test（追加）**

```java
    @Test
    void acceptApplyByMayorAddsMember() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        standaloneTown(data, mayor);
        TownMemberService.applyToTownForTest(data, player, "crimea");

        TownMemberService.acceptApplyForTest(data, mayor, "crimea", player);

        assertNotNull(data.getTownMember("crimea", player));
        assertNull(data.getTownMemberInvite("crimea", player));
    }

    @Test
    void rejectApplyByMayorClearsRequestNoMember() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        standaloneTown(data, mayor);
        TownMemberService.applyToTownForTest(data, player, "crimea");

        TownMemberService.rejectApplyForTest(data, mayor, "crimea", player);

        assertNull(data.getTownMember("crimea", player));
        assertNull(data.getTownMemberInvite("crimea", player));
    }

    @Test
    void listMyTownsReturnsJoinedTowns() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        data.putTown(new TownRecord("crimea", "", "Crimea", UUID.randomUUID(), 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));

        java.util.List<net.minecraft.network.chat.Component> lines = TownMemberService.listMyTownsForTest(data, player);
        assertTrue(lines.size() >= 1);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberServiceTest"`
Expected: FAIL — `acceptApplyForTest` 等不存在。

- [ ] **Step 3: Write minimal implementation**

在 `TownMemberService` 加（公开 ServerPlayer 重载 + Test 重载 + 内部逻辑），并在文件顶部加 import `java.util.ArrayList`、`java.util.List`、`com.monpai.sailboatmod.nation.model.TownMemberInviteRecord`（若未导入）：

```java
    public static NationResult acceptApply(ServerPlayer actor, ServerPlayer applicant) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = firstTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return acceptApplyInternal(data, town, applicant.getUUID());
    }

    static NationResult acceptApplyForTest(NationSavedData data, UUID mayorUuid, String townId, UUID applicantUuid) {
        TownRecord town = data.getTown(townId);
        if (town == null || !mayorUuid.equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return acceptApplyInternal(data, town, applicantUuid);
    }

    private static NationResult acceptApplyInternal(NationSavedData data, TownRecord town, UUID applicantUuid) {
        TownMemberInviteRecord req = data.getTownMemberInvite(town.townId(), applicantUuid);
        if (req == null || !req.isApply()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.apply.missing", town.name()));
        }
        data.removeTownMemberInvite(town.townId(), applicantUuid);
        return completeJoin(data, applicantUuid, town);
    }

    public static NationResult rejectApply(ServerPlayer actor, ServerPlayer applicant) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = firstTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return rejectApplyInternal(data, town, applicant.getUUID());
    }

    static NationResult rejectApplyForTest(NationSavedData data, UUID mayorUuid, String townId, UUID applicantUuid) {
        TownRecord town = data.getTown(townId);
        if (town == null || !mayorUuid.equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return rejectApplyInternal(data, town, applicantUuid);
    }

    private static NationResult rejectApplyInternal(NationSavedData data, TownRecord town, UUID applicantUuid) {
        TownMemberInviteRecord req = data.getTownMemberInvite(town.townId(), applicantUuid);
        if (req == null || !req.isApply()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.apply.missing", town.name()));
        }
        data.removeTownMemberInvite(town.townId(), applicantUuid);
        return NationResult.success(Component.translatable("command.sailboatmod.town.apply.reject.success", town.name()));
    }

    public static NationResult joinTown(ServerPlayer actor, String rawTownName) {
        // 玩家接受邀请 = 走 applyToTown（相反方向 invite 存在则成交）
        return applyToTown(actor, rawTownName);
    }

    public static NationResult declineInvite(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = data.findTownByName(rawTownName);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.not_found", rawTownName));
        }
        TownMemberInviteRecord req = data.getTownMemberInvite(town.townId(), actor.getUUID());
        if (req == null || !req.isInvite()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.decline.missing", town.name()));
        }
        data.removeTownMemberInvite(town.townId(), actor.getUUID());
        return NationResult.success(Component.translatable("command.sailboatmod.town.decline.success", town.name()));
    }

    public static List<Component> listMyTowns(ServerPlayer actor) {
        return listMyTownsForTest(NationSavedData.get(actor.level()), actor.getUUID());
    }

    static List<Component> listMyTownsForTest(NationSavedData data, UUID playerUuid) {
        List<String> townIds = data.getTownsForPlayer(playerUuid);
        if (townIds.isEmpty()) {
            return List.of(Component.translatable("command.sailboatmod.town.list.empty"));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.town.list.header"));
        for (String townId : townIds) {
            TownRecord town = data.getTown(townId);
            String name = town == null ? townId : town.name();
            lines.add(Component.translatable("command.sailboatmod.town.list.entry", name));
        }
        return lines;
    }

    public static List<Component> listMembers(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = rawTownName == null || rawTownName.isBlank()
                ? firstTownForMayor(data, actor.getUUID())
                : data.findTownByName(rawTownName);
        if (town == null) {
            return List.of(Component.translatable("command.sailboatmod.town.not_found", rawTownName == null ? "" : rawTownName));
        }
        List<TownMemberRecord> members = data.getTownMembersForTown(town.townId());
        if (members.isEmpty()) {
            return List.of(Component.translatable("command.sailboatmod.town.members.empty", town.name()));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.town.members.header", town.name()));
        for (TownMemberRecord member : members) {
            NationMemberRecord nm = data.getMember(member.playerUuid());
            String name = nm == null || nm.lastKnownName().isBlank() ? member.playerUuid().toString() : nm.lastKnownName();
            lines.add(Component.translatable("command.sailboatmod.town.members.entry", name));
        }
        return lines;
    }

    public static List<Component> listRequests(ServerPlayer actor) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = firstTownForMayor(data, actor.getUUID());
        if (town == null) {
            return List.of(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        List<TownMemberInviteRecord> requests = data.getTownMemberInvitesForTown(town.townId());
        if (requests.isEmpty()) {
            return List.of(Component.translatable("command.sailboatmod.town.requests.empty", town.name()));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.town.requests.header", town.name()));
        for (TownMemberInviteRecord req : requests) {
            NationMemberRecord nm = data.getMember(req.playerUuid());
            String name = nm == null || nm.lastKnownName().isBlank() ? req.playerUuid().toString() : nm.lastKnownName();
            lines.add(Component.translatable("command.sailboatmod.town.requests.entry", name, req.direction()));
        }
        return lines;
    }
```

文件顶部补 import：

```java
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import java.util.ArrayList;
import java.util.List;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberServiceTest"`
Expected: PASS（10 个测试全过）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/TownMemberService.java src/test/java/com/monpai/sailboatmod/nation/service/TownMemberServiceTest.java
git commit -m "feat: accept/reject/decline/list commands in TownMemberService"
```

---

## 阶段 D：`/town` 指令树注册

### Task D1: 注册 `/town` 顶级指令树

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/command/NationCommands.java`（加 import + 在 `dispatcher.register(nation)` 后注册 `/town` 树）
- Test: `src/test/java/com/monpai/sailboatmod/nation/command/TownCommandsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class TownCommandsTest {
    private static CommandNode<CommandSourceStack> townChild(String sub) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        NationCommands.register(dispatcher);
        CommandNode<CommandSourceStack> town = dispatcher.getRoot().getChild("town");
        assertNotNull(town, "/town root should be registered");
        return town.getChild(sub);
    }

    @Test
    void registersTownSubcommands() {
        for (String sub : new String[]{"apply", "invite", "accept", "reject", "join", "decline", "leave", "kick", "list", "members", "requests"}) {
            assertNotNull(townChild(sub), "/town " + sub + " should be registered");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.command.TownCommandsTest"`
Expected: FAIL — `/town` 根节点不存在。

- [ ] **Step 3: Write minimal implementation**

在 `NationCommands.java` 顶部加 import：

```java
import com.monpai.sailboatmod.nation.service.TownMemberService;
```

在 `dispatcher.register(nation);`（行 310）**之后**加 `/town` 树注册：

```java
        LiteralArgumentBuilder<CommandSourceStack> town = Commands.literal("town");

        town.then(Commands.literal("apply")
                .then(Commands.argument("town", StringArgumentType.greedyString())
                        .executes(context -> sendResult(context.getSource(),
                                TownMemberService.applyToTown(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "town"))))));

        town.then(Commands.literal("invite")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> sendResult(context.getSource(),
                                TownMemberService.invitePlayer(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))));

        town.then(Commands.literal("accept")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> sendResult(context.getSource(),
                                TownMemberService.acceptApply(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))));

        town.then(Commands.literal("reject")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> sendResult(context.getSource(),
                                TownMemberService.rejectApply(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))));

        town.then(Commands.literal("join")
                .then(Commands.argument("town", StringArgumentType.greedyString())
                        .executes(context -> sendResult(context.getSource(),
                                TownMemberService.joinTown(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "town"))))));

        town.then(Commands.literal("decline")
                .then(Commands.argument("town", StringArgumentType.greedyString())
                        .executes(context -> sendResult(context.getSource(),
                                TownMemberService.declineInvite(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "town"))))));

        town.then(Commands.literal("leave")
                .then(Commands.argument("town", StringArgumentType.greedyString())
                        .executes(context -> sendResult(context.getSource(),
                                TownMemberService.leaveTown(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "town"))))));

        town.then(Commands.literal("kick")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> sendResult(context.getSource(),
                                TownMemberService.kickMember(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))));

        town.then(Commands.literal("list")
                .executes(context -> sendLines(context.getSource(),
                        TownMemberService.listMyTowns(context.getSource().getPlayerOrException()))));

        town.then(Commands.literal("members")
                .executes(context -> sendLines(context.getSource(),
                        TownMemberService.listMembers(context.getSource().getPlayerOrException(), "")))
                .then(Commands.argument("town", StringArgumentType.greedyString())
                        .executes(context -> sendLines(context.getSource(),
                                TownMemberService.listMembers(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "town"))))));

        town.then(Commands.literal("requests")
                .executes(context -> sendLines(context.getSource(),
                        TownMemberService.listRequests(context.getSource().getPlayerOrException()))));

        dispatcher.register(town);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.command.TownCommandsTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/command/NationCommands.java src/test/java/com/monpai/sailboatmod/nation/command/TownCommandsTest.java
git commit -m "feat: register /town command tree for player-town membership"
```

---

## 阶段 E：存档迁移

### Task E1: `TownMemberMigration.runOnce`

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/data/TownMemberMigration.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/data/TownMemberMigrationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownMemberMigrationTest {
    @Test
    void migratesMayorToTownMember() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        data.putTown(new TownRecord("crimea", "", "Crimea", mayor, 5L, "", TownRecord.noCorePos(), "", "european"));

        TownMemberMigration.runOnce(data);

        assertNotNull(data.getTownMember("crimea", mayor));
        assertTrue(data.isTownMembersMigrated());
    }

    @Test
    void migratesNationOrphanToCapitalTown() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 0x111111, 0x222222, mayor, 1L, "crimea", "", NationRecord.noCorePos(), ""));
        data.putTown(new TownRecord("crimea", "alpha", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putMember(new NationMemberRecord(orphan, "Orphan", "alpha", "member", 1L));

        TownMemberMigration.runOnce(data);

        assertNotNull(data.getTownMember("crimea", orphan));  // 挂到首都 town
    }

    @Test
    void isIdempotentViaFlag() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        data.putTown(new TownRecord("crimea", "", "Crimea", mayor, 5L, "", TownRecord.noCorePos(), "", "european"));
        TownMemberMigration.runOnce(data);

        // 标记已置，二次跑不再扫描：手动删镇民后重跑，不应被补回
        data.removeTownMember("crimea", mayor);
        TownMemberMigration.runOnce(data);

        assertNull(data.getTownMember("crimea", mayor));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.data.TownMemberMigrationTest"`
Expected: FAIL — `TownMemberMigration` 不存在。

- [ ] **Step 3: Write minimal implementation**

需要 `NationSavedData` 暴露 `getNations()`（已存在，行 ~386 `getNations()` 返回 `Collection<NationRecord>`）、`getTownsForNation(String)`（601）、所有 members 的遍历。先在 `NationSavedData` 加一个返回全体 nation 成员的查询（迁移用）：

```java
    public List<NationMemberRecord> getAllMembers() {
        return new ArrayList<>(members.values());
    }

    public List<TownRecord> getAllTowns() {
        return new ArrayList<>(towns.values());
    }
```

（放在成员/town 方法区。`members`/`towns` 是现有私有 Map 字段。）

然后创建迁移类：

```java
package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.List;

public final class TownMemberMigration {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TownMemberMigration() {
    }

    public static void runOnce(NationSavedData data) {
        if (data == null || data.isTownMembersMigrated()) {
            return;
        }

        // 1. 镇长补录
        for (TownRecord town : data.getAllTowns()) {
            if (town.mayorUuid() == null) {
                continue;
            }
            if (data.getTownMember(town.townId(), town.mayorUuid()) == null) {
                data.putTownMember(new TownMemberRecord(
                        town.mayorUuid(), town.townId(), TownMemberRecord.OFFICE_MEMBER, town.createdAt()));
            }
        }

        // 2. nation 孤儿归并
        for (NationMemberRecord member : data.getAllMembers()) {
            if (!data.getTownsForPlayer(member.playerUuid()).isEmpty()) {
                continue;  // 已有 town（含上一步补的镇长）
            }
            NationRecord nation = data.getNation(member.nationId());
            if (nation == null) {
                continue;
            }
            TownRecord target = resolveCapitalTown(data, nation);
            if (target == null) {
                LOGGER.warn("Nation {} has member {} but no town; leaving nation membership as-is",
                        nation.nationId(), member.playerUuid());
                continue;
            }
            data.putTownMember(new TownMemberRecord(
                    member.playerUuid(), target.townId(), TownMemberRecord.OFFICE_MEMBER, member.joinedAt()));
        }

        data.setTownMembersMigrated(true);
    }

    private static TownRecord resolveCapitalTown(NationSavedData data, NationRecord nation) {
        if (!nation.capitalTownId().isBlank()) {
            TownRecord capital = data.getTown(nation.capitalTownId());
            if (capital != null) {
                return capital;
            }
        }
        List<TownRecord> towns = data.getTownsForNation(nation.nationId());
        return towns.isEmpty() ? null : towns.get(0);
    }
}
```

> 注：`LogUtils.getLogger()` 是 Forge/Mojang 标准日志（本仓多处用，如需确认可 grep `LogUtils.getLogger`）。`NationRecord.capitalTownId()` 已确认存在（`bindTownToNation` 用过）。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.data.TownMemberMigrationTest"`
Expected: PASS（3 个测试）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/data/TownMemberMigration.java src/main/java/com/monpai/sailboatmod/nation/data/NationSavedData.java src/test/java/com/monpai/sailboatmod/nation/data/TownMemberMigrationTest.java
git commit -m "feat: TownMemberMigration runOnce (mayor + nation orphan backfill)"
```

### Task E2: 在 `NationSavedData.load` 末尾触发迁移

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/data/NationSavedData.java`（`load` 的 `return data;` 前调 `TownMemberMigration.runOnce(data)`）
- Test: `src/test/java/com/monpai/sailboatmod/nation/data/NationSavedDataMigrationHookTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationSavedDataMigrationHookTest {
    @Test
    void loadRunsMigrationForOldSaveWithTownButNoTownMembers() {
        // 构造一个"旧档"：有 town、无 TownMembers 标签、无迁移标记
        NationSavedData seed = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        seed.putTown(new TownRecord("crimea", "", "Crimea", mayor, 5L, "", TownRecord.noCorePos(), "", "european"));
        CompoundTag saved = seed.save(new CompoundTag());
        // 模拟旧档：移除 TownMembers 相关标签与迁移标记
        saved.remove("TownMembers");
        saved.remove("TownMemberRequests");
        saved.remove("townMembersMigrated");

        NationSavedData loaded = NationSavedData.load(saved);

        assertTrue(loaded.isTownMembersMigrated(), "load should trigger migration");
        assertNotNull(loaded.getTownMember("crimea", mayor), "mayor should be backfilled as town member");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.data.NationSavedDataMigrationHookTest"`
Expected: FAIL — load 尚未触发迁移，`isTownMembersMigrated()` 为 false。

- [ ] **Step 3: Write minimal implementation**

在 `NationSavedData.load(CompoundTag)` 方法内，`return data;`（行 259，Task A4 加的 townMember 读取之后）**之前**加：

```java
        TownMemberMigration.runOnce(data);

        return data;
```

（`TownMemberMigration` 同包 `nation.data`，无需 import。）

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.data.NationSavedDataMigrationHookTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/data/NationSavedData.java src/test/java/com/monpai/sailboatmod/nation/data/NationSavedDataMigrationHookTest.java
git commit -m "feat: trigger TownMemberMigration on NationSavedData load"
```

---

## 阶段 F：i18n（指令反馈文案双语）

### Task F1: 补 `/town` 指令的 lang key（en_us + zh_cn）

**Files:**
- Modify: `src/main/resources/assets/sailboatmod/lang/en_us.json`
- Modify: `src/main/resources/assets/sailboatmod/lang/zh_cn.json`
- Test: `src/test/java/com/monpai/sailboatmod/nation/TownLangContractTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TownLangContractTest {
    private static final String[] KEYS = {
            "command.sailboatmod.town.not_found",
            "command.sailboatmod.town.already_member",
            "command.sailboatmod.town.apply.success",
            "command.sailboatmod.town.apply.already_sent",
            "command.sailboatmod.town.apply.missing",
            "command.sailboatmod.town.apply.reject.success",
            "command.sailboatmod.town.invite.success",
            "command.sailboatmod.town.invite.already_sent",
            "command.sailboatmod.town.invite.not_mayor",
            "command.sailboatmod.town.join.success",
            "command.sailboatmod.town.decline.success",
            "command.sailboatmod.town.decline.missing",
            "command.sailboatmod.town.leave.success",
            "command.sailboatmod.town.leave.not_member",
            "command.sailboatmod.town.kick.success",
            "command.sailboatmod.town.kick.not_member",
            "command.sailboatmod.town.kick.is_mayor",
            "command.sailboatmod.town.list.empty",
            "command.sailboatmod.town.list.header",
            "command.sailboatmod.town.list.entry",
            "command.sailboatmod.town.members.empty",
            "command.sailboatmod.town.members.header",
            "command.sailboatmod.town.members.entry",
            "command.sailboatmod.town.requests.empty",
            "command.sailboatmod.town.requests.header",
            "command.sailboatmod.town.requests.entry",
    };

    @Test
    void bothLangFilesContainAllTownKeys() throws Exception {
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        for (String key : KEYS) {
            assertTrue(en.contains("\"" + key + "\""), "en_us.json missing " + key);
            assertTrue(zh.contains("\"" + key + "\""), "zh_cn.json missing " + key);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.TownLangContractTest"`
Expected: FAIL — lang 文件缺这些 key。

- [ ] **Step 3: Write minimal implementation**

在 `en_us.json` 末尾（最后一个键后，注意补逗号）加入：

```json
    "command.sailboatmod.town.not_found": "Town '%s' not found",
    "command.sailboatmod.town.already_member": "You are already a member of %s",
    "command.sailboatmod.town.apply.success": "Application sent to %s",
    "command.sailboatmod.town.apply.already_sent": "You already applied to %s",
    "command.sailboatmod.town.apply.missing": "No pending application for %s",
    "command.sailboatmod.town.apply.reject.success": "Rejected the application for %s",
    "command.sailboatmod.town.invite.success": "Invitation sent for %s",
    "command.sailboatmod.town.invite.already_sent": "Invitation already pending for %s",
    "command.sailboatmod.town.invite.not_mayor": "You must be a town mayor to do that",
    "command.sailboatmod.town.join.success": "Joined town %s",
    "command.sailboatmod.town.decline.success": "Declined the invitation from %s",
    "command.sailboatmod.town.decline.missing": "No pending invitation from %s",
    "command.sailboatmod.town.leave.success": "Left town %s",
    "command.sailboatmod.town.leave.not_member": "You are not a member of %s",
    "command.sailboatmod.town.kick.success": "Removed the member from %s",
    "command.sailboatmod.town.kick.not_member": "That player is not a member of %s",
    "command.sailboatmod.town.kick.is_mayor": "Cannot remove the mayor",
    "command.sailboatmod.town.list.empty": "You have not joined any town",
    "command.sailboatmod.town.list.header": "Towns you belong to:",
    "command.sailboatmod.town.list.entry": "- %s",
    "command.sailboatmod.town.members.empty": "%s has no members",
    "command.sailboatmod.town.members.header": "Members of %s:",
    "command.sailboatmod.town.members.entry": "- %s",
    "command.sailboatmod.town.requests.empty": "%s has no pending requests",
    "command.sailboatmod.town.requests.header": "Pending requests for %s:",
    "command.sailboatmod.town.requests.entry": "- %s (%s)"
```

在 `zh_cn.json` 末尾同样加入（中文）：

```json
    "command.sailboatmod.town.not_found": "未找到城镇 '%s'",
    "command.sailboatmod.town.already_member": "你已经是 %s 的成员",
    "command.sailboatmod.town.apply.success": "已向 %s 提交加入申请",
    "command.sailboatmod.town.apply.already_sent": "你已申请加入 %s",
    "command.sailboatmod.town.apply.missing": "%s 没有待处理的申请",
    "command.sailboatmod.town.apply.reject.success": "已拒绝 %s 的加入申请",
    "command.sailboatmod.town.invite.success": "已发出加入 %s 的邀请",
    "command.sailboatmod.town.invite.already_sent": "%s 已有待处理的邀请",
    "command.sailboatmod.town.invite.not_mayor": "你必须是镇长才能这样做",
    "command.sailboatmod.town.join.success": "已加入城镇 %s",
    "command.sailboatmod.town.decline.success": "已拒绝来自 %s 的邀请",
    "command.sailboatmod.town.decline.missing": "没有来自 %s 的待处理邀请",
    "command.sailboatmod.town.leave.success": "已退出城镇 %s",
    "command.sailboatmod.town.leave.not_member": "你不是 %s 的成员",
    "command.sailboatmod.town.kick.success": "已将该成员移出 %s",
    "command.sailboatmod.town.kick.not_member": "该玩家不是 %s 的成员",
    "command.sailboatmod.town.kick.is_mayor": "无法移除镇长",
    "command.sailboatmod.town.list.empty": "你尚未加入任何城镇",
    "command.sailboatmod.town.list.header": "你所属的城镇：",
    "command.sailboatmod.town.list.entry": "- %s",
    "command.sailboatmod.town.members.empty": "%s 暂无成员",
    "command.sailboatmod.town.members.header": "%s 的成员：",
    "command.sailboatmod.town.members.entry": "- %s",
    "command.sailboatmod.town.requests.empty": "%s 暂无待处理请求",
    "command.sailboatmod.town.requests.header": "%s 的待处理请求：",
    "command.sailboatmod.town.requests.entry": "- %s（%s）"
```

> 实现注意：两个 JSON 文件加新键前，要把原本最后一个键的行尾补上逗号，且新块最后一行**不带**尾逗号（保持合法 JSON）。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.TownLangContractTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/assets/sailboatmod/lang/en_us.json src/main/resources/assets/sailboatmod/lang/zh_cn.json src/test/java/com/monpai/sailboatmod/nation/TownLangContractTest.java
git commit -m "feat: add /town command lang keys (en_us + zh_cn)"
```

---

## 阶段 G：GUI（镇民段挂现有 town 面板）

> **执行提示**：本阶段是「完整体系」里 GUI 范围的落地。GUI 的字段级细节依赖现有 town 面板代码现状——执行此阶段前，先 `Grep` 定位现有 town 详情面板/overview 数据结构（搜 `TownOverview`、`describeTown`、客户端 `TownScreen`/`NationScreen` 相关），确认挂载点后再按下列任务展开。若现有 town 面板用纯指令驱动（无独立 overview record），则 GUI 退化为「`/town members`、`/town list` 指令已满足查看需求」，本阶段可缩减为 Task G1 的服务端数据查询断言。

### Task G1: town 成员数据查询契约（GUI 数据源就位）

**Files:**
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/TownMemberQueryContractTest.java`

本任务锁定「GUI 数据源」契约：`getTownMembersForTown` / `getTownsForPlayer` 是 GUI 渲染镇民段与「我的 town」段的唯一数据来源，已在阶段 A 实现。此处补一个集成性断言，防止后续重构破坏数据源。

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownMemberQueryContractTest {
    @Test
    void townPanelDataSourcesReturnConsistentView() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID resident = UUID.randomUUID();
        data.putTown(new TownRecord("crimea", "", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putTownMember(new TownMemberRecord(mayor, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMember(new TownMemberRecord(resident, "crimea", TownMemberRecord.OFFICE_MEMBER, 2L));

        // GUI 镇民段数据源
        assertEquals(2, data.getTownMembersForTown("crimea").size());
        // GUI "我的 town" 段数据源
        assertEquals(1, data.getTownsForPlayer(mayor).size());
        assertEquals(1, data.getTownsForPlayer(resident).size());
    }
}
```

- [ ] **Step 2: Run test to verify it passes（数据源已存在，应直接通过）**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberQueryContractTest"`
Expected: PASS（阶段 A 已实现这些查询）。

- [ ] **Step 3: （无新实现）**

数据源已就位。GUI 渲染层的具体接线（把这两个查询的结果下发到客户端 town 面板）依现有 town 面板架构在执行时定。若现有 town 面板有 overview record，在其后追加 `List<TownMemberLine>` 字段，沿现有同步路径下发；若无，则 `/town members`/`/town list` 指令已满足查看需求，GUI 增强可作为后续独立工作项。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.nation.service.TownMemberQueryContractTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/monpai/sailboatmod/nation/service/TownMemberQueryContractTest.java
git commit -m "test: lock GUI data-source contract for town member panel"
```

---

## 阶段 H：全量验证 + 构建 + 推送

### Task H1: 全套新测试 + 出 jar + 推送

**Files:** 无（验证 + 发布）

- [ ] **Step 1: 跑全部新增测试**

Run:
```bash
./gradlew test --tests "com.monpai.sailboatmod.nation.model.TownMemberRecordTest" \
  --tests "com.monpai.sailboatmod.nation.model.TownMemberInviteRecordTest" \
  --tests "com.monpai.sailboatmod.nation.data.NationSavedDataTownMemberTest" \
  --tests "com.monpai.sailboatmod.nation.data.NationSavedDataTownMemberPersistenceTest" \
  --tests "com.monpai.sailboatmod.nation.service.TownMemberCascadeTest" \
  --tests "com.monpai.sailboatmod.nation.service.TownCreateMemberWiringTest" \
  --tests "com.monpai.sailboatmod.nation.service.TownMemberServiceTest" \
  --tests "com.monpai.sailboatmod.nation.command.TownCommandsTest" \
  --tests "com.monpai.sailboatmod.nation.data.TownMemberMigrationTest" \
  --tests "com.monpai.sailboatmod.nation.data.NationSavedDataMigrationHookTest" \
  --tests "com.monpai.sailboatmod.nation.TownLangContractTest" \
  --tests "com.monpai.sailboatmod.nation.service.TownMemberQueryContractTest"
```
Expected: 全部 PASS。

- [ ] **Step 2: 出 jar（验证整体编译，跳过无关失败测试）**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL，`build/libs/` 出 jar。

- [ ] **Step 3: 提交剩余改动（若有）**

```bash
git add -A
git commit -m "chore: member-town binding system complete" || echo "nothing to commit"
```

- [ ] **Step 4: 推送到 GitHub（按 memory sailboatmod-push 方式）**

Run:
```bash
REPO_PATH="$(git remote get-url origin | sed -E 's#.*github.com[:/]([^/]+/[^/.]+)(\.git)?#\1#')"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git -c http.proxy=http://127.0.0.1:7897 -c http.sslBackend=openssl push "https://x-access-token:$(gh auth token)@github.com/${REPO_PATH}.git" "$BRANCH"
```
Expected: 推送成功。

- [ ] **Step 5: 完成**

打印总结：新增镇民-town 绑定体系（TownMemberRecord + TownMemberInviteRecord + TownMemberService + /town 指令 + 全量联动 + 存档迁移），解锁 Phase 5 收货候选按 town 收窄。

---

## 自审记录

**1. Spec coverage（spec 各节 → 计划任务）：**
- spec §1 数据模型 → Task A1（TownMemberRecord）、A2（TownMemberInviteRecord）、A3/A4（NationSavedData 存储 + 序列化）✅
- spec §2 加入流程与指令 → Task C1（apply/invite 合并）、C2（join 联动）、C3（leave/kick）、C4（accept/reject/decline/list）、D1（/town 指令树）✅
- spec §3 全量联动 → Task B1（bind 联动）、B2（unbind 联动 + 多 town 保护）✅
- spec §4 存档迁移 → Task B3（建 town 补镇长）、E1（runOnce 扫描）、E2（load 触发）✅
- spec §5.1 序列化 → Task A4 ✅
- spec §5.2 GUI → 阶段 G（数据源契约 G1 + 执行时接线说明）✅
- spec §5.3 测试 → 每个 Task 含 TDD 测试；契约断言（TownCreateMemberWiringTest、TownCommandsTest、TownLangContractTest）✅
- spec「对 Phase 5 的解锁」→ getTownsForPlayer 在 A3 实现，H1 总结点明 ✅

**2. Placeholder scan：** Task A4 Step 3 有一处 `putInviteKeyed` 占位方法，已在该步骤内用注释明确「写实现时不要包含它」。其余无 TBD/TODO。阶段 G 的「执行时接线」是有意的、spec 已授权的延迟决策（spec §5.2 原文「GUI 数据下发细节在实现计划里按现有 town 面板代码现状再定」），非占位失败——且配了 G1 锁定数据源契约 + 明确的退化路径。

**3. Type consistency（贯穿核对）：**
- `TownMemberRecord(UUID, String townId, String officeId, long)` — A1 定义，A3/B1/B3/C1/E1 调用一致 ✅
- `TownMemberInviteRecord(String townId, UUID, String direction, UUID, long)` — A2 定义，A4/C1/C4 一致 ✅
- `OFFICE_MEMBER="member"` / `DIRECTION_INVITE="invite"` / `DIRECTION_APPLY="apply"` — 全小写，与现有 sanitize 一致 ✅
- `townMemberKey(townId, uuid)` — A3 定义，A4 复用 ✅
- `nameForTownMember(data, uuid, existing)` — B1 定义，C1 `syncPlayerNationWithTown` 复用 ✅
- `stillInNationViaOtherTown(data, uuid, nationId, excludingTownId)` — B2 定义 private，C3 `demotePlayerNationIfOrphaned` 同类内复用 ✅
- `bindTownToNationForTest` / `unbindTownFromNationForTest` — B1/B2 测试钩子，命名一致 ✅
- `NationOfficeIds.MEMBER` — B1/C1 用于 nation 籍 officeId，与现有 joinNation 一致 ✅
- `completeJoin(data, uuid, town)` — C1 定义，C2/C4 复用 ✅

无类型漂移。计划自洽。
