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
        // 个人字段只在已认证时写入（用守卫包裹）
        assertTrue(service.contains("if (authenticated)"),
                "personal wallet/order fields must be written only when authenticated");
        // 国库/待领款也不得对未登录 guest 暴露
        assertTrue(service.contains("authenticated ? overview.treasuryBalance() : 0L"),
                "treasury balance must not be exposed to unauthenticated visitors");
        assertTrue(service.contains("authenticated ? overview.pendingCredits() : 0L"),
                "pending credits must not be exposed to unauthenticated visitors");
    }

    @Test
    void webFrontendHidesWalletDockWhenNotLoggedIn() throws Exception {
        String app = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/marketweb/app.js"));

        // 钱包是玩家个人的：未登录（无 session）必须隐藏整个钱包 dock，不只是禁用按钮
        assertTrue(app.contains("if (!detail || !state.session)"),
                "renderTopbarWallet must hide the wallet dock entirely when there is no logged-in session");
    }
}
