package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeListingPriceContractTest {
    @Test
    void referenceWindowNoLongerConstrains() throws Exception {
        // 价格窗口只要 >0 即有效、constrained=false（指导价仅展示，不约束上架）
        MarketPricePolicy.ListingPriceWindow w = MarketPricePolicy.referencePriceWindow(100, 10000);
        assertTrue(w.valid(), "any price > 0 should be valid regardless of reference band");
        assertFalse(w.constrained(), "listing price must no longer be constrained to the reference band");

        MarketPricePolicy.ListingPriceWindow low = MarketPricePolicy.referencePriceWindow(100, 1);
        assertTrue(low.valid(), "a price far below reference should still be valid");
    }

    @Test
    void createListingDoesNotRejectOutOfRange() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        int idx = src.indexOf("ListingPriceWindow priceWindow = listingPriceWindow(listed, amount, requestedUnitPrice);");
        assertTrue(idx >= 0, "createListing should still compute the reference window for display");
        // out_of_range 拒单已移除——价格区间不再阻止上架
        int extract = src.indexOf("extractVisibleStorage(sellerId, visibleStorageIndex, amount)", idx);
        String between = src.substring(idx, extract);
        assertFalse(between.contains("listing_price_out_of_range"),
                "createListing must not reject listings for being out of the reference band");
    }
}
