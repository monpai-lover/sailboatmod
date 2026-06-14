package com.monpai.sailboatmod.market;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LowestActiveAskTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static MarketListing listing(net.minecraft.world.item.Item item, int unitPrice, int available) {
        return new MarketListing(
                "id-" + unitPrice, "seller", "Seller",
                new ItemStack(item), unitPrice, available, 0,
                net.minecraft.core.BlockPos.ZERO, "dock", "town", "nation", 0, "");
    }

    @Test
    void picksLowestUnitPriceAmongMatchingActiveListings() {
        String key = com.monpai.sailboatmod.market.commodity.CommodityKeyResolver.resolve(new ItemStack(Items.OAK_LOG));
        List<MarketListing> listings = List.of(
                listing(Items.OAK_LOG, 30, 64),
                listing(Items.OAK_LOG, 12, 64),   // 最低
                listing(Items.OAK_LOG, 25, 64),
                listing(Items.STONE, 1, 64)        // 不同商品，忽略
        );
        assertEquals(12, MarketSavedData.lowestUnitPriceForCommodity(listings, key));
    }

    @Test
    void returnsZeroWhenNoMatchingListing() {
        String key = com.monpai.sailboatmod.market.commodity.CommodityKeyResolver.resolve(new ItemStack(Items.DIAMOND));
        List<MarketListing> listings = List.of(listing(Items.OAK_LOG, 30, 64));
        assertEquals(0, MarketSavedData.lowestUnitPriceForCommodity(listings, key));
    }

    @Test
    void returnsZeroForEmpty() {
        assertEquals(0, MarketSavedData.lowestUnitPriceForCommodity(List.of(), "any"));
    }
}
