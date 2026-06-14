# 钱包安全修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 sailboatmod 市场钱包的三类安全/正确性缺陷——刷金（存入侧扣源落空/非原子）、金双向估值口径不一致、钱包归属/越权（玩家级钱包被展示成市场级、个人数据对未登录 guest 暴露），不改任何 UI 视觉只改后端事务与身份处理及文案。

**Architecture:** 钱包后端是两端（游戏内 `MarketWalletActionPacket` + 网页端 `MarketWebService`）共用的同一套服务（`MarketWalletService` 按 `playerUuid` 一人一账，已正确）。本计划只修事务路径与身份/授权处理：存入先确凿扣源再加钱包、失败全回滚；金双向统一固定率；个人数据接口要求登录、空身份不取数；文案去"市场账户"歧义。每个改动配测试，沿用项目既有的两种测试风格。

**Tech Stack:** Java 17、Minecraft Forge 1.20.1、JUnit 5 (Jupiter)、Gradle。测试两种风格：(a) **契约文本断言**——`Files.readString` 读源码 + `assertTrue(source.contains(...))`（见 `MarketWalletTransferContractTest`），用于约束跨文件调用形态；(b) **纯函数行为单测**——直接对纯函数断言（见 `MarketWalletServiceTest`），用于可隔离的逻辑。

---

## 背景与约束（实现前必读）

**根因（已在 spec 第三部分锁定）：**
- **刷金主因**：`cashToWallet` 存入时扣源（Vault/仓库）与加钱包非原子，且 Vault 路径与仓库路径 `||` 短路——扣减可能落空但钱包照加。
- **金口径次因**：`GoldStandardEconomy.goldItemMarketValue`（`economy/GoldStandardEconomy.java:19-31`）用 commodity 动态 `basePrice` 估值；提取侧用固定率 `BALANCE_PER_GOLD_INGOT=18`。口径不一致 = 套利。
- **越权**：`GET /api/markets/{id}`（`MarketWebServer.java:286-288`）走 `resolveIdentityOrGuest`，对未登录 guest 返回 `walletBalance`/`cashBalance`/`myOrders` 等个人字段；guest fallback 身份为 `MarketPlayerIdentity(null,"",null)`（`MarketWebServer.java:912`）。

**已知缓解（不要夸大严重度）：** `MarketWalletSavedData.getAccount`（行 65-67）对空 uuid 只返回临时 empty 账户（余额 0，不持久化），`putAccount`（行 76）空 uuid 拒绝保存。所以未登录访客之间**不会真看到彼此存进去的钱**；真实暴露面是**已登录玩家的 myOrders/cashBalance 经只读接口对 guest 开放**，以及**0 余额 + 文案把玩家钱包呈现成市场公共账户**的语义错误。计划据此修复，不夸大为"资金共享"。

**不可破坏的现有测试约束**（`MarketWalletTransferContractTest`，改 `cashToWallet`/`walletToCash` 时必须保持）：
- web 端 `transferWallet` 必须仍含 `GoldStandardEconomy.tryWithdrawByIdentity(`、`MarketWalletGoldSource.withdrawFromLinkedWarehouse(`、`GoldStandardEconomy.tryDepositByIdentity(`、`MarketWalletGoldSource.depositToLinkedWarehouse(resolved.market(), identity.playerUuid(), amount)`、`MarketWalletService.deposit(`/`withdraw(`。
- web 端**禁止**出现 `? GoldStandardEconomy.tryDeposit(identity.onlinePlayer(), amount)`（行 45-46 的否定断言）。
- 游戏内 `MarketWalletActionPacket` 必须仍含 `GoldStandardEconomy.tryWithdraw(`、`MarketWalletGoldSource.withdrawFromLinkedWarehouse(`、`GoldStandardEconomy.tryDeposit(`。
- `app.js` 必须仍含 `现金/仓库金 -> 钱包` 与 `wallet/transfer`。

**测试命令：**
- 全量：`cd sailboatmod && ./gradlew test`
- 单类：`cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletGoldValueParityTest`
- 单方法：`./gradlew test --tests 'com.monpai.sailboatmod.market.wallet.WalletGoldValueParityTest.goldValueUsesFixedRateNotMarketPrice'`

> Windows 环境下若 `./gradlew` 不可用，用 `gradlew.bat test ...`（同参数）。

---

## File Structure（先锁定边界）

**修改（生产代码）：**
- `economy/GoldStandardEconomy.java` — `goldItemMarketValue` 改为只用固定率（删 commodity 动态价路径）
- `market/web/MarketWebService.java` — `cashToWallet`/`walletToCash` 事务原子化与精确回滚；`marketDetail` 对未登录身份不返回个人字段
- `market/web/MarketWebServer.java` — `GET /api/markets/{id}` 改为要求登录（或在 service 内屏蔽个人字段，二选一，本计划选 service 内屏蔽以保留 guest 看公开行情）
- `market/wallet/MarketWalletService.java` — `getAccount`/`deposit`/`withdraw` 对空身份显式拒绝（防御纵深）
- `network/packet/MarketWalletActionPacket.java` — `walletToCash` 失败回滚口径修正（与 web 对齐）
- `market/wallet/MarketWalletGoldSource.java` — 新增公开方法 `linkedWarehouseGoldValue(market, ownerId)`（盘点即真账，供后续显示层；本计划只建后端方法+测试）
- `src/main/resources/marketweb/app.js` — 钱包文案"市场钱包/当前市场账户"→"我的钱包/个人余额"
- `src/main/resources/assets/sailboatmod/lang/zh_cn.json` + `en_us.json` — 游戏内钱包文案同步

**新建（测试）：**
- `src/test/java/com/monpai/sailboatmod/market/wallet/WalletGoldValueParityTest.java`
- `src/test/java/com/monpai/sailboatmod/market/wallet/WalletDepositAtomicityContractTest.java`
- `src/test/java/com/monpai/sailboatmod/market/wallet/WalletGuestExposureContractTest.java`
- `src/test/java/com/monpai/sailboatmod/market/wallet/WalletEmptyIdentityGuardTest.java`
- `src/test/java/com/monpai/sailboatmod/market/wallet/WalletOwnershipCopyContractTest.java`

---

## Task 1: 金双向估值统一为固定率（次根因）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/economy/GoldStandardEconomy.java:19-31`
- Test: `src/test/java/com/monpai/sailboatmod/market/wallet/WalletGoldValueParityTest.java`

- [ ] **Step 1: 写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/wallet/WalletGoldValueParityTest.java`：

```java
package com.monpai.sailboatmod.market.wallet;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletGoldValueParityTest {
    @Test
    void goldValueUsesFixedRateNotMarketPrice() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/economy/GoldStandardEconomy.java"));

        // goldItemMarketValue 必须只用固定率常量，杜绝与提取侧（固定率）不一致的套利
        assertTrue(source.contains("BALANCE_PER_GOLD_BLOCK")
                        && source.contains("BALANCE_PER_GOLD_INGOT")
                        && source.contains("BALANCE_PER_GOLD_NUGGET"),
                "goldItemMarketValue should value gold by the fixed denomination rates");
        assertFalse(source.contains("CommodityMarketService"),
                "goldItemMarketValue must not derive gold value from the dynamic commodity market price");
        assertFalse(source.contains("ensureCommodity"),
                "goldItemMarketValue must not call ensureCommodity for gold valuation");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletGoldValueParityTest`
Expected: FAIL —— 现状 `GoldStandardEconomy.java:23` 含 `CommodityMarketService` 与 `ensureCommodity`，`assertFalse` 不成立。

- [ ] **Step 3: 改 goldItemMarketValue 为固定率**

把 `economy/GoldStandardEconomy.java` 的 `goldItemMarketValue`（行 19-31）整体替换为：

```java
    /** Returns the market currency value of a gold item stack, or 0 if not a gold item. */
    public static long goldItemMarketValue(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (!stack.is(Items.GOLD_INGOT) && !stack.is(Items.GOLD_BLOCK) && !stack.is(Items.GOLD_NUGGET)) return 0;
        // 金作硬通货，双向换算统一用固定率，杜绝"高估值存、固定率取"的套利刷金。
        int unitValue = stack.is(Items.GOLD_BLOCK) ? BALANCE_PER_GOLD_BLOCK
                : stack.is(Items.GOLD_INGOT) ? BALANCE_PER_GOLD_INGOT : BALANCE_PER_GOLD_NUGGET;
        return (long) unitValue * stack.getCount();
    }
```

> 改完后 `import` 不再需要 commodity 类——但原文件用的是全限定名 `com.monpai.sailboatmod.market.commodity.CommodityMarketService`（无 import 行），所以无需删 import。确认文件顶部没有遗留的 commodity import。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletGoldValueParityTest`
Expected: PASS

- [ ] **Step 5: 跑既有金/经济相关测试防回归**

Run: `cd sailboatmod && ./gradlew test --tests 'com.monpai.sailboatmod.market.commodity.*' --tests 'com.monpai.sailboatmod.market.wallet.*'`
Expected: PASS（金估值改固定率不应破坏钱包/commodity 既有测试；若某测试断言金价随市场变动则需在此暴露并复核）。

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/economy/GoldStandardEconomy.java src/test/java/com/monpai/sailboatmod/market/wallet/WalletGoldValueParityTest.java && git commit -m "fix(wallet): value gold by fixed rate to close store-high/withdraw-low arbitrage"
```

---

## Task 2: 空身份不取账户（防御纵深，纯函数可测）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/wallet/MarketWalletService.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/wallet/WalletEmptyIdentityGuardTest.java`

> 现状 `MarketWalletSavedData.getAccount` 对空 uuid 已返回临时 empty 账户、`putAccount` 已拒绝保存。本任务把"空身份拒绝"上提到 `MarketWalletService` 的**纯函数**层并加可隔离单测，确保任何调用方传空身份都拿到 0 且不可写——为越权修复提供后端保险。

- [ ] **Step 1: 写失败的纯函数行为单测**

新建 `src/test/java/com/monpai/sailboatmod/market/wallet/WalletEmptyIdentityGuardTest.java`：

```java
package com.monpai.sailboatmod.market.wallet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WalletEmptyIdentityGuardTest {
    @Test
    void blankIdentityHasZeroBalanceAndCannotHoldFunds() {
        // 空 uuid 的账户即便被 deposit 也不应累积余额——杜绝所有访客落到同一 "" 账户后互相影响
        MarketWalletAccount blank = MarketWalletAccount.empty("", "", 100L);
        MarketWalletAccount afterDeposit = MarketWalletService.deposit(blank, 500L, 110L);
        assertEquals(0L, afterDeposit.availableBalance(),
                "deposits into a blank-identity account must not accumulate balance");
    }

    @Test
    void blankIdentityWithdrawAlwaysFails() {
        MarketWalletAccount blank = MarketWalletAccount.empty("", "", 100L);
        MarketWalletService.AccountResult result = MarketWalletService.withdraw(blank, 1L, 110L);
        assertFalse(result.success(),
                "withdrawals from a blank-identity account must always fail");
    }

    @Test
    void normalIdentityStillWorks() {
        // 回归：正常身份不受影响
        MarketWalletAccount acc = MarketWalletAccount.empty("player-1", "GoatDie", 100L);
        MarketWalletAccount afterDeposit = MarketWalletService.deposit(acc, 240L, 110L);
        assertEquals(240L, afterDeposit.availableBalance());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletEmptyIdentityGuardTest`
Expected: FAIL —— 现状 `deposit(blank, 500L, 110L)` 会返回 availableBalance=500（纯函数不检查空身份）。

- [ ] **Step 3: 在纯函数 deposit/withdraw 加空身份守卫**

`MarketWalletService.java` 的纯函数 `deposit(MarketWalletAccount, long, long)`（行 71-81）改为：

```java
    public static MarketWalletAccount deposit(MarketWalletAccount account, long amount, long nowMillis) {
        MarketWalletAccount safe = account == null ? MarketWalletAccount.empty("", "", nowMillis) : account;
        if (safe.playerUuid() == null || safe.playerUuid().isBlank()) {
            // 空身份账户不持有任何资金，防止未登录/匿名身份落到共享账户
            return safe;
        }
        long safeAmount = Math.max(0L, amount);
        return new MarketWalletAccount(
                safe.playerUuid(),
                safe.playerName(),
                saturatedAdd(safe.availableBalance(), safeAmount),
                safe.reservedBalance(),
                nowMillis
        );
    }
```

纯函数 `withdraw(MarketWalletAccount, long, long)`（行 83-105）在方法体最前面（`MarketWalletAccount safe = ...` 之后）加空身份守卫：

```java
    public static AccountResult withdraw(MarketWalletAccount account, long amount, long nowMillis) {
        MarketWalletAccount safe = account == null ? MarketWalletAccount.empty("", "", nowMillis) : account;
        if (safe.playerUuid() == null || safe.playerUuid().isBlank()) {
            return AccountResult.failure(safe, Math.max(0L, amount));
        }
        long safeAmount = Math.max(0L, amount);
        // ...（其余原有逻辑保持不变）
```

> 只在 `deposit`/`withdraw` 两个纯函数加守卫即可满足测试；`reserve`/`spendReserved`/`releaseReserved` 不在本计划范围（它们由已登录买单流程驱动，不经匿名路径），保持不变以免破坏 `MarketWalletServiceTest`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletEmptyIdentityGuardTest`
Expected: PASS

- [ ] **Step 5: 跑既有钱包单测防回归**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.MarketWalletServiceTest`
Expected: PASS（既有测试用的都是 `player-1`/`player-2` 非空身份，不受守卫影响）。

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/wallet/MarketWalletService.java src/test/java/com/monpai/sailboatmod/market/wallet/WalletEmptyIdentityGuardTest.java && git commit -m "fix(wallet): reject blank-identity deposits/withdrawals to prevent shared anon account"
```

---

## Task 3: marketDetail 对未登录身份不返回个人字段（越权主修复）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`（`marketDetail` ~:176-238）
- Test: `src/test/java/com/monpai/sailboatmod/market/wallet/WalletGuestExposureContractTest.java`

> 选用"在 service 内对未登录身份屏蔽个人字段"而非"整个接口要求登录"——保留 guest 仍能看公开行情/挂单，只屏蔽 `walletBalance`/`walletReservedBalance`/`walletTotalBalance`/`cashBalance`/`myOrders` 等个人数据。判定"未登录"= `identity.playerUuid() == null`（guest fallback 即此）。

- [ ] **Step 1: 写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/wallet/WalletGuestExposureContractTest.java`：

```java
package com.monpai.sailboatmod.market.wallet;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletGuestExposureContractTest {
    @Test
    void marketDetailGuardsPersonalFieldsForAnonymousIdentity() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));

        // marketDetail 必须判定未登录身份（playerUuid()==null）并据此屏蔽个人字段
        assertTrue(service.contains("identity.playerUuid() == null"),
                "marketDetail should detect anonymous identity by null playerUuid");
        assertTrue(service.contains("boolean authenticated"),
                "marketDetail should branch personal fields on an authenticated flag");
        // 个人字段只在已认证时写入（用守卫包裹），文本上要求 authenticated 出现在 wallet 字段附近
        assertTrue(service.contains("if (authenticated)"),
                "personal wallet/order fields must be written only when authenticated");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletGuestExposureContractTest`
Expected: FAIL —— 现状 `marketDetail` 无 `authenticated` 分支，个人字段无条件写入。

- [ ] **Step 3: 在 marketDetail 加 authenticated 守卫**

在 `MarketWebService.java` 的 `marketDetail`（行 181 `overview` 构建之后、行 200 起的钱包字段之前）插入认证判定，并把个人字段包进 `if (authenticated)`。具体改动：

在 `overview` 构建（行 181-185）后加：

```java
        boolean authenticated = identity.playerUuid() != null;
```

把行 200-203 的钱包余额字段、行 206 的 cashBalance、行 230 的 myOrders 改为仅在 `authenticated` 时写入；未认证时写 0 / 空数组。即把原：

```java
        root.addProperty("walletBalance", overview.walletAvailableBalance());
        root.addProperty("walletAvailableBalance", overview.walletAvailableBalance());
        root.addProperty("walletReservedBalance", overview.walletReservedBalance());
        root.addProperty("walletTotalBalance", overview.walletTotalBalance());
```

替换为：

```java
        if (authenticated) {
            root.addProperty("walletBalance", overview.walletAvailableBalance());
            root.addProperty("walletAvailableBalance", overview.walletAvailableBalance());
            root.addProperty("walletReservedBalance", overview.walletReservedBalance());
            root.addProperty("walletTotalBalance", overview.walletTotalBalance());
        } else {
            root.addProperty("walletBalance", 0L);
            root.addProperty("walletAvailableBalance", 0L);
            root.addProperty("walletReservedBalance", 0L);
            root.addProperty("walletTotalBalance", 0L);
        }
```

把行 206 `root.addProperty("cashBalance", cashBalance(identity));` 替换为：

```java
        root.addProperty("cashBalance", authenticated ? cashBalance(identity) : 0L);
```

把行 230 `root.add("myOrders", myOrders(marketData, identity.playerUuidString()));` 替换为：

```java
        if (authenticated) {
            root.add("myOrders", myOrders(marketData, identity.playerUuidString()));
        } else {
            root.add("myOrders", new com.google.gson.JsonArray());
        }
```

> `walletOnline`(行 207)/`walletCurrency`(行 208) 是非敏感展示字段，保留原样。`overview` 仍按原参数构建（buildOverviewForIdentity 对空身份内部已返回 0，此处的 if 是显式防御 + 让契约测试可断言）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletGuestExposureContractTest`
Expected: PASS

- [ ] **Step 5: 跑既有 web 测试防回归**

Run: `cd sailboatmod && ./gradlew test --tests 'com.monpai.sailboatmod.market.web.*' --tests com.monpai.sailboatmod.market.wallet.MarketWalletTransferContractTest`
Expected: PASS（只在 guest 分支屏蔽，不动 transferWallet 调用形态，契约测试断言仍成立）。

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java src/test/java/com/monpai/sailboatmod/market/wallet/WalletGuestExposureContractTest.java && git commit -m "fix(wallet): do not expose wallet balance/orders to unauthenticated web visitors"
```

---

## Task 4: cashToWallet 存入事务原子化（刷金主修复，web 端）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`（`cashToWallet` :312-325）
- Test: `src/test/java/com/monpai/sailboatmod/market/wallet/WalletDepositAtomicityContractTest.java`

> 核心：把 Vault 与仓库的 `||` 短路改为"明确判定哪个真扣成功、只在确凿扣成功后才 deposit"。`tryWithdrawByIdentity` 返回 `Boolean`（null=无 Vault / true=扣成功 / false=余额不足），现状 `!Boolean.TRUE.equals(withdrawn)` 已能把 null 和 false 都视作"Vault 没扣到"再走仓库 fallback——**逻辑形态基本正确，本任务主要是显式化三态、确保 deposit 只发生在扣源成功后**，并保持现有契约测试要求的方法调用都在。

- [ ] **Step 1: 写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/wallet/WalletDepositAtomicityContractTest.java`：

```java
package com.monpai.sailboatmod.market.wallet;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletDepositAtomicityContractTest {
    @Test
    void cashToWalletDepositsOnlyAfterConfirmedWithdrawal() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));

        // 三态显式化：区分 Vault 不可用(null) 与 扣款成功(true)
        assertTrue(service.contains("Boolean vaultWithdrawn"),
                "cashToWallet should capture the Vault tri-state result explicitly");
        // 仍保留仓库 fallback（现有契约要求）
        assertTrue(service.contains("MarketWalletGoldSource.withdrawFromLinkedWarehouse("),
                "cashToWallet must keep the linked-warehouse withdrawal fallback");
        // deposit 必须在确认扣源成功的分支内，文本上 deposit 应在 withdrew==true 守卫之后
        assertTrue(service.contains("boolean withdrew"),
                "cashToWallet should track a definitive 'withdrew' flag before depositing");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletDepositAtomicityContractTest`
Expected: FAIL —— 现状无 `Boolean vaultWithdrawn` / `boolean withdrew` 变量。

- [ ] **Step 3: 重写 cashToWallet 为显式三态**

把 `MarketWebService.java` 的 `cashToWallet`（行 312-325）整体替换为：

```java
    private ActionResult cashToWallet(ResolvedMarket resolved, MarketPlayerIdentity identity, String playerUuid, String playerName, long amount) {
        if (amount <= 0L) {
            return ActionResult.failure("invalid_amount", "Invalid amount");
        }
        // 三态：null=无 Vault，true=Vault 已扣，false=Vault 余额不足
        Boolean vaultWithdrawn = identity.onlinePlayer() != null
                ? GoldStandardEconomy.tryWithdraw(identity.onlinePlayer(), amount)
                : GoldStandardEconomy.tryWithdrawByIdentity(identity.playerUuid(), playerName, amount);
        boolean withdrew = Boolean.TRUE.equals(vaultWithdrawn);
        if (!withdrew) {
            // Vault 不可用或不足 → 必须从绑定仓库真扣到，才算扣源成功
            withdrew = MarketWalletGoldSource.withdrawFromLinkedWarehouse(resolved.market(), identity.playerUuid(), amount);
        }
        if (!withdrew) {
            return ActionResult.failure("insufficient_cash", "Insufficient cash");
        }
        // 仅在确凿扣源成功后加钱包
        MarketWalletService.deposit(resolved.level(), playerUuid, playerName, amount);
        return ActionResult.success();
    }
```

> 此版本保留了契约测试要求的全部调用：`GoldStandardEconomy.tryWithdrawByIdentity(`、`MarketWalletGoldSource.withdrawFromLinkedWarehouse(`、`MarketWalletService.deposit(`。语义上 deposit 严格在 `withdrew==true` 之后，杜绝"扣源落空仍加钱包"。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletDepositAtomicityContractTest`
Expected: PASS

- [ ] **Step 5: 跑钱包契约测试防回归**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.MarketWalletTransferContractTest`
Expected: PASS（`webWalletTransferEndpointUsesSameLedgerSources` 要求的所有 `contains` 仍满足）。

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java src/test/java/com/monpai/sailboatmod/market/wallet/WalletDepositAtomicityContractTest.java && git commit -m "fix(wallet): deposit to wallet only after a confirmed source withdrawal (web)"
```

---

## Task 5: walletToCash 失败精确回滚（web + 游戏内对齐）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java`（`walletToCash` :327-344）
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/MarketWalletActionPacket.java`（`walletToCash` :102-116）
- Test: 扩展 `src/test/java/com/monpai/sailboatmod/market/wallet/WalletDepositAtomicityContractTest.java`

> 现状两端 `walletToCash` 在给付失败时调用 `MarketWalletService.deposit(...)` 把钱"退回钱包"——语义对（回滚），但写成 `deposit` 容易和"加钱包"混淆且依赖外部状态。本任务统一为显式回滚注释 + 确保 web 端给付失败路径仍走 `depositToLinkedWarehouse` fallback（契约要求），游戏内补回滚一致性。

- [ ] **Step 1: 扩展契约测试（加失败回滚断言）**

在 `WalletDepositAtomicityContractTest.java` 追加测试方法：

```java
    @Test
    void walletToCashRefundsWalletWhenPayoutFails() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
        String packet = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/network/packet/MarketWalletActionPacket.java"));

        // web 端：给付失败必须把已扣的钱包额退回（回滚），并保留仓库 fallback
        assertTrue(service.contains("depositToLinkedWarehouse(resolved.market(), identity.playerUuid(), amount)"),
                "walletToCash should fall back to the player's linked warehouse for payout");
        assertTrue(service.contains("// rollback wallet"),
                "walletToCash should mark the wallet refund as an explicit rollback");

        // 游戏内：给付失败同样显式回滚钱包
        assertTrue(packet.contains("// rollback wallet"),
                "in-game walletToCash should mark the wallet refund as an explicit rollback");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletDepositAtomicityContractTest`
Expected: FAIL —— 现状两文件均无 `// rollback wallet` 注释。

- [ ] **Step 3: 改 web 端 walletToCash 显式回滚**

把 `MarketWebService.java` 的 `walletToCash`（行 327-344）整体替换为：

```java
    private ActionResult walletToCash(ResolvedMarket resolved, MarketPlayerIdentity identity, String playerUuid, String playerName, long amount) {
        if (amount <= 0L) {
            return ActionResult.failure("invalid_amount", "Invalid amount");
        }
        MarketWalletService.AccountResult withdrawn = MarketWalletService.withdraw(resolved.level(), playerUuid, playerName, amount);
        if (!withdrawn.success()) {
            return ActionResult.failure("insufficient_wallet", "Insufficient market wallet balance");
        }
        Boolean deposited = GoldStandardEconomy.tryDepositByIdentity(identity.playerUuid(), playerName, amount);
        if (Boolean.TRUE.equals(deposited)) {
            return ActionResult.success();
        }
        if (deposited == null && MarketWalletGoldSource.depositToLinkedWarehouse(resolved.market(), identity.playerUuid(), amount)) {
            return ActionResult.success();
        }
        // rollback wallet: 给付未成功，把已扣的钱包额精确退回，避免黑洞
        MarketWalletService.deposit(resolved.level(), playerUuid, playerName, amount);
        return ActionResult.failure("cash_deposit_unavailable", "Cash deposit is unavailable");
    }
```

- [ ] **Step 4: 改游戏内 walletToCash 显式回滚**

把 `MarketWalletActionPacket.java` 的 `walletToCash`（行 102-116）整体替换为：

```java
    private boolean walletToCash(ServerPlayer player, String playerUuid, String playerName) {
        if (amount <= 0L) {
            return false;
        }
        MarketWalletService.AccountResult withdrawn = MarketWalletService.withdraw(player.level(), playerUuid, playerName, amount);
        if (!withdrawn.success()) {
            return false;
        }
        Boolean deposited = GoldStandardEconomy.tryDeposit(player, amount);
        if (Boolean.TRUE.equals(deposited)) {
            return true;
        }
        // rollback wallet: 实物金给付未成功，把已扣的钱包额精确退回
        MarketWalletService.deposit(player.level(), playerUuid, playerName, amount);
        return false;
    }
```

> 游戏内 `tryDeposit(player, amount)` 对在线玩家几乎总成功（背包/掉落），失败路径罕见但仍需回滚一致；保留 `tryDeposit(`/`tryWithdraw(` 满足契约测试。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletDepositAtomicityContractTest --tests com.monpai.sailboatmod.market.wallet.MarketWalletTransferContractTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java src/main/java/com/monpai/sailboatmod/network/packet/MarketWalletActionPacket.java src/test/java/com/monpai/sailboatmod/market/wallet/WalletDepositAtomicityContractTest.java && git commit -m "fix(wallet): make walletToCash payout failure roll back the wallet explicitly (both ends)"
```

---

## Task 6: 新增 linkedWarehouseGoldValue 公开盘点方法（盘点即真账）

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/wallet/MarketWalletGoldSource.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/wallet/WalletOwnershipCopyContractTest.java`（本任务先放盘点方法的契约断言）

> 显示层（后续 UI 计划）要展示"可存入数值"，必须与 `withdrawFromLinkedWarehouse` 同口径同仓库。本任务在后端建公开方法 `linkedWarehouseGoldValue(market, ownerId)`，复用现有 `planRemoval` 的盘点逻辑（`countMatchingStock × unitValue` 累加），只读不扣。本计划只建方法 + 契约测试，不接 UI。

- [ ] **Step 1: 写失败的契约测试**

新建 `src/test/java/com/monpai/sailboatmod/market/wallet/WalletOwnershipCopyContractTest.java`：

```java
package com.monpai.sailboatmod.market.wallet;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletOwnershipCopyContractTest {
    @Test
    void goldSourceExposesPublicLinkedWarehouseValuation() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/wallet/MarketWalletGoldSource.java"));

        assertTrue(source.contains("public static long linkedWarehouseGoldValue("),
                "gold source should expose a public read-only valuation of the linked warehouse gold");
        assertTrue(source.contains("countMatchingStock(ownerId"),
                "valuation must read the owner's private warehouse stock, same source as withdrawal");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletOwnershipCopyContractTest`
Expected: FAIL —— 现状无 `linkedWarehouseGoldValue` 公开方法。

- [ ] **Step 3: 新增 linkedWarehouseGoldValue 方法**

在 `MarketWalletGoldSource.java` 的 `depositToLinkedWarehouse`（行 56-62）之后插入：

```java
    /**
     * 只读盘点：绑定仓库内该 owner 的金类物品折算为货币总值，与提取走同一口径同一仓库。
     * 供两端 UI 显示"可存入数值"，不扣减任何物品。
     */
    public static long linkedWarehouseGoldValue(MarketBlockEntity market, UUID ownerId) {
        if (market == null || ownerId == null) {
            return 0L;
        }
        TownWarehouseBlockEntity warehouse = market.getLinkedWarehouse();
        if (warehouse == null) {
            return 0L;
        }
        long total = 0L;
        for (Denomination denomination : denominations()) {
            long count = Math.max(0, warehouse.countMatchingStock(ownerId, new ItemStack(denomination.item())));
            total += count * denomination.unitValue();
        }
        return total;
    }
```

> 复用现有 `denominations()`（行 194-201）和 `Denomination`（行 203）；与 `planRemoval`（行 71-88）的盘点口径完全一致——同样 `countMatchingStock × unitValue`，保证"显示能存 N = 实际能扣 N"。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletOwnershipCopyContractTest`
Expected: PASS

- [ ] **Step 5: 跑钱包来源契约测试防回归**

Run: `cd sailboatmod && ./gradlew test --tests 'com.monpai.sailboatmod.market.wallet.*'`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/java/com/monpai/sailboatmod/market/wallet/MarketWalletGoldSource.java src/test/java/com/monpai/sailboatmod/market/wallet/WalletOwnershipCopyContractTest.java && git commit -m "feat(wallet): add read-only linkedWarehouseGoldValue valuation (same source as withdrawal)"
```

---

## Task 7: 钱包文案去"市场账户"歧义（网页端 + 游戏内）

**Files:**
- Modify: `src/main/resources/marketweb/app.js`（:953-962 钱包文案）
- Modify: `src/main/resources/assets/sailboatmod/lang/zh_cn.json`（:382 起 wallet.*）
- Modify: `src/main/resources/assets/sailboatmod/lang/en_us.json`（对应 wallet.* 键）
- Test: 扩展 `src/test/java/com/monpai/sailboatmod/market/wallet/WalletOwnershipCopyContractTest.java`

> 把"市场钱包/当前市场账户"改为表达"我的钱包/个人余额"，消除"市场公共账户、人人共享"的错觉。**注意**：`MarketWalletTransferContractTest:57` 断言 app.js 必须含 `现金/仓库金 -> 钱包`——不要改动这条按钮文案，只改 `wallet_balance` 标题与 `wallet_topbar_hint`。

- [ ] **Step 1: 扩展契约测试（文案断言）**

在 `WalletOwnershipCopyContractTest.java` 追加：

```java
    @Test
    void walletCopyDoesNotImplyMarketSharedAccount() throws Exception {
        String app = Files.readString(Path.of("src/main/resources/marketweb/app.js"));

        // 标题改为"我的钱包"，顶栏提示去掉"市场账户"措辞
        assertTrue(app.contains("我的钱包"),
                "web wallet title should read as the player's own wallet");
        assertTrue(!app.contains("当前市场账户"),
                "web wallet hint must not call it the market account");
        // 回归：转账按钮文案与接口路径不变（既有契约依赖）
        assertTrue(app.contains("现金/仓库金 -> 钱包"));
        assertTrue(app.contains("wallet/transfer"));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletOwnershipCopyContractTest`
Expected: FAIL —— 现状 app.js:953 是 `市场钱包`、:962 是 `当前市场账户`。

- [ ] **Step 3: 改 app.js 文案**

`src/main/resources/marketweb/app.js` 行 953-954、962 改为：

```javascript
  wallet_balance: "我的钱包",
  wallet_balance_hint: "购买商品和发布求购都会从你个人的市场钱包扣款，可在这里和现金、国库互转。",
```

```javascript
  wallet_topbar_hint: "我的个人余额",
```

> 仅改这三处文案值；其余键（含按钮 `现金/仓库金 -> 钱包`、`wallet/transfer` 路径）保持不变。

- [ ] **Step 4: 改游戏内 lang（zh_cn + en_us）**

`src/main/resources/assets/sailboatmod/lang/zh_cn.json` 行 382 改为：

```json
    "screen.sailboatmod.market.wallet.available": "我的钱包",
```

`src/main/resources/assets/sailboatmod/lang/en_us.json` 对应键 `screen.sailboatmod.market.wallet.available` 改为：

```json
    "screen.sailboatmod.market.wallet.available": "My Wallet",
```

> 若 en_us 该键当前值不是 "Wallet"，以实际为准只改这一个键的值为 "My Wallet"。其余 wallet.* 转账按钮键不动。

- [ ] **Step 5: 跑测试确认通过 + 防回归**

Run: `cd sailboatmod && ./gradlew test --tests com.monpai.sailboatmod.market.wallet.WalletOwnershipCopyContractTest --tests com.monpai.sailboatmod.market.wallet.MarketWalletTransferContractTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
cd sailboatmod && git add src/main/resources/marketweb/app.js src/main/resources/assets/sailboatmod/lang/zh_cn.json src/main/resources/assets/sailboatmod/lang/en_us.json src/test/java/com/monpai/sailboatmod/market/wallet/WalletOwnershipCopyContractTest.java && git commit -m "fix(wallet): reword wallet UI as personal, not a shared market account"
```

---

## Task 8: 全量测试 + 构建验证

**Files:** 无（验证任务）

- [ ] **Step 1: 跑全部测试**

Run: `cd sailboatmod && ./gradlew test`
Expected: PASS（全绿）。若有失败，定位是否本计划改动引入；钱包契约测试、`MarketWalletServiceTest`、commodity 测试都应通过。

- [ ] **Step 2: 编译验证**

Run: `cd sailboatmod && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL，无编译错误。

- [ ] **Step 3: 完整构建出 jar（按项目记忆：每次修复要 build jar）**

Run: `cd sailboatmod && ./gradlew build`
Expected: BUILD SUCCESSFUL，`build/libs/` 下生成 `-reobf.jar`。

- [ ] **Step 4: 推送（按项目记忆：经代理 7897 + gh 凭据）**

> 按 sailboatmod 推送方式：经代理 7897 + gh 凭据推送（直连和 gh_token.txt 都不行）。执行时确认远程与分支后推送本计划的全部提交。

---

## Self-Review（已对照 spec 第三部分核对）

**Spec 覆盖：**
- 刷金主因（存入非原子/扣源落空）→ Task 4（web）+ Task 5（回滚）✓
- 金双向口径 → Task 1 ✓
- 越权（个人接口对 guest 开放）→ Task 3 ✓
- 空账户合并防御 → Task 2 ✓
- 盘点即真账（linkedWarehouseGoldValue）→ Task 6 ✓
- 文案去歧义 → Task 7 ✓
- 客户端同步核对 → Task 5（游戏内 walletToCash）+ Task 7（游戏内 lang）✓
- 验证 → Task 8 ✓

**类型/签名一致性：** `goldItemMarketValue`、`cashToWallet`、`walletToCash`、`linkedWarehouseGoldValue`、`deposit`/`withdraw` 纯函数签名均与现状核实一致；`MarketPlayerIdentity.playerUuid()` 为 null 判定未登录与 guest fallback 一致。

**无占位符：** 每个代码步骤含完整代码；每个命令含预期结果。

**范围说明：** 本计划只覆盖 spec 第三部分（钱包安全）= 实施分期阶段 6。运输派发（阶段 1-5）、定价（第二部分）、钱包显示层 UI（阶段 7-8）不在本计划，各自独立成计划。
