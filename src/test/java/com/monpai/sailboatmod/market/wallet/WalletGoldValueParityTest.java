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
