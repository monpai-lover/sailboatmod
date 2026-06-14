package com.monpai.sailboatmod.market.web;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebItemResolveTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void resolvesRegisteredItemIdForBuyOrderPreview() {
        MarketWebService.ItemPreview preview = MarketWebService.resolveItemPreview("minecraft:oak_log");

        assertNotNull(preview);
        assertEquals("minecraft:oak_log", preview.commodityKey());
        assertEquals("minecraft:oak_log", preview.itemId());
        assertEquals("Oak Log", preview.displayName());
        assertFalse(preview.category().isBlank());
        assertTrue(preview.suggestedUnitPrice() > 0);
    }

    @Test
    void rejectsMissingItemIdForBuyOrderPreview() {
        assertNull(MarketWebService.resolveItemPreview("missing:not_a_real_item"));
    }
}
