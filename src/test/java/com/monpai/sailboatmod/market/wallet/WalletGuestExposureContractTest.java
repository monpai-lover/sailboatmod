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
    }
}
