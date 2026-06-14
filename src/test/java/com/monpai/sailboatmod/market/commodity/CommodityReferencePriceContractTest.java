package com.monpai.sailboatmod.market.commodity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommodityReferencePriceContractTest {
    @Test
    void repositoryQueriesRecentTradeAverageOrderedByTimeDesc() throws Exception {
        String repo = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/commodity/CommodityMarketRepository.java"));

        assertTrue(repo.contains("recentTradeAveragePrice("),
                "repository should expose recentTradeAveragePrice");
        // 最近 N 笔：按时间倒序取 N 条，对其 unit_price 求均值
        assertTrue(repo.contains("ORDER BY created_at DESC"),
                "recent-trade query should order by created_at DESC to take the latest N");
        assertTrue(repo.contains("LIMIT ?"),
                "recent-trade query should bound to the latest N trades via LIMIT");
        assertTrue(repo.contains("commodity_trade_history"),
                "query should read from the trade history table");
        assertTrue(repo.contains("unit_price"),
                "average should be computed over unit_price");
    }

    @Test
    void serviceReferencePriceFallsBackToBasePriceWhenNoTrades() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/commodity/CommodityMarketService.java"));

        assertTrue(service.contains("referencePrice("),
                "service should expose referencePrice");
        assertTrue(service.contains("recentTradeAveragePrice("),
                "referencePrice should source from recent trade average");
        assertTrue(service.contains("estimateBaseUnitPrice("),
                "referencePrice should fall back to base price when there are no trades");
        assertTrue(service.contains("REFERENCE_TRADE_SAMPLE_SIZE"),
                "recent-N sample size should be a named constant (default 20)");
        assertTrue(service.contains("= 20"),
                "default recent-N should be 20 per spec");
    }
}
