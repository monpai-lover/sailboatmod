package com.monpai.sailboatmod.market.commodity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommodityInitializerTest {
    @Test
    void musketBayonetIsClassifiedAsWeaponInsteadOfFood() {
        CommodityDefinition definition = CommodityInitializer.createDefault(
                "musketmod:musket_with_bayonet",
                "musketmod:musket_with_bayonet",
                "Musket with Bayonet"
        );

        assertEquals("weapon", definition.category());
    }

    @Test
    void unknownModItemDoesNotDefaultToFood() {
        CommodityDefinition definition = CommodityInitializer.createDefault(
                "examplemod:ancient_relic",
                "examplemod:ancient_relic",
                "Ancient Relic"
        );

        assertEquals("other", definition.category());
    }
}
