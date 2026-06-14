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

    @Test
    void walletCopyDoesNotImplyMarketSharedAccount() throws Exception {
        String app = Files.readString(Path.of("src/main/resources/marketweb/app.js"));

        // 标题改为"我的钱包"，顶栏提示去掉"市场账户"措辞（中英双语都不得暗示共享市场账户）
        assertTrue(app.contains("我的钱包"),
                "web wallet title should read as the player's own wallet");
        assertTrue(!app.contains("当前市场账户"),
                "web wallet hint must not call it the market account (zh)");
        assertTrue(!app.contains("Selected market account"),
                "web wallet hint must not call it the market account (en)");
        assertTrue(!app.contains("wallet_balance: \"Market wallet\""),
                "english wallet title must not read as a shared market wallet");
        // 回归：转账按钮文案与接口路径不变（既有契约依赖）
        assertTrue(app.contains("现金/仓库金 -> 钱包"));
        assertTrue(app.contains("wallet/transfer"));
    }
}
