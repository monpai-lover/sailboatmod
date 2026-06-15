package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasuryTierContractTest {
    @Test
    void serviceMapsBalanceToTierAndHidesExactAmount() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/nation/service/NationTradeService.java"));
        assertTrue(src.contains("private static String treasuryTier(long balance)"),
                "service should map balance to abstract tier");
        // 对方精确金额不下发（targetBalance 固定 0）
        assertTrue(src.contains("long targetBalance = 0L"),
                "exact target treasury amount must not be sent to client");
        assertTrue(src.contains("treasuryTier(targetTreasury.currencyBalance())"),
                "target treasury tier should be computed from balance");
    }

    @Test
    void clientRendersTierLabelNotNumber() throws Exception {
        String screen = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/client/screen/nation/NationTradeScreen.java"));
        assertTrue(screen.contains("screen.sailboatmod.trade.balance.tier."),
                "trade screen should render target treasury as an abstract tier label");
    }

    @Test
    void langHasAllTiers() throws Exception {
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        for (String tier : new String[]{"minimal", "modest", "substantial", "wealthy", "prosperous", "unknown"}) {
            String key = "screen.sailboatmod.trade.balance.tier." + tier;
            assertTrue(zh.contains("\"" + key + "\""), "zh missing " + key);
            assertTrue(en.contains("\"" + key + "\""), "en missing " + key);
        }
    }
}
