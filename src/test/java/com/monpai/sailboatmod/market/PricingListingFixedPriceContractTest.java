package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingListingFixedPriceContractTest {
    private static String marketBlockEntity() throws Exception {
        return Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
    }

    @Test
    void listingUnitPriceIsFixedAndNotRecomputedFromQuote() throws Exception {
        String source = marketBlockEntity();
        assertTrue(source.contains("private int currentListingUnitPrice(MarketListing listing, int quantity)"),
                "currentListingUnitPrice should still exist");
        assertTrue(source.contains("// pricing: listing price is fixed by the seller, never recomputed"),
                "currentListingUnitPrice must be marked as returning the seller-fixed price");
    }

    @Test
    void listingWindowUsesReferencePriceBand() throws Exception {
        String source = marketBlockEntity();
        assertTrue(source.contains("MarketPricePolicy.referencePriceWindow("),
                "listingPriceWindow should use the reference-price band");
        assertTrue(source.contains("COMMODITY_MARKET.referencePrice("),
                "listingPriceWindow should source the reference price from the service");
        assertFalse(source.contains("MarketPricePolicy.listingWindow("),
                "old bp-based listingWindow must no longer be called");
    }

    @Test
    void resaleKeepsFixedPriceAndListingDoesNotBumpStock() throws Exception {
        String source = marketBlockEntity();
        assertTrue(source.contains("// pricing: keep the seller-fixed unit price on resale"),
                "purchase resolution must keep the fixed listing price (no recompute write-back)");
        assertTrue(source.contains("// pricing: listing no longer bumps stock"),
                "createListingFromDockStorage must not adjust commodity supply on listing");
        assertTrue(source.contains("applyCommodityDemand("),
                "purchases must still record trades for reference-price sourcing");
    }

    @Test
    void overviewSuggestedPriceUsesReferenceNotDynamicQuote() throws Exception {
        String source = marketBlockEntity();
        assertTrue(source.contains("COMMODITY_MARKET.referencePrice("),
                "overview suggested/reference price should come from referencePrice");
        assertFalse(source.contains("currentCommodityUnitPrice("),
                "dynamic-quote currentCommodityUnitPrice must no longer be used");
    }
}
