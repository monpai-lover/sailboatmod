package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageUnloadDecisionTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void orderDrivenAlwaysUnloadsRegardlessOfSwitch() {
        // 商单/调度发车：始终卸货，开关关也卸
        assertTrue(CarriageEntity.shouldUnloadAtArrivalForTest(true, false),
                "order-driven delivery must unload even when the manual switch is off");
        assertTrue(CarriageEntity.shouldUnloadAtArrivalForTest(true, true));
    }

    @Test
    void manualFollowsTheSwitch() {
        // 手动发车（无订单）：看开关
        assertTrue(CarriageEntity.shouldUnloadAtArrivalForTest(false, true),
                "manual trip unloads when the switch is on");
        assertFalse(CarriageEntity.shouldUnloadAtArrivalForTest(false, false),
                "manual trip keeps cargo when the switch is off");
    }

    @Test
    void deliverCargoFallsBackToWholePoolWhenManifestEntryHasNoItemSpec() {
        // manifest 条目无物品规格（itemStack 空/quantity 0，setPendingMarketDelivery 的情况）
        java.util.List<com.monpai.sailboatmod.market.ShipmentManifestEntry> deliverHere = java.util.List.of(
                new com.monpai.sailboatmod.market.ShipmentManifestEntry(
                        "", net.minecraft.world.item.ItemStack.EMPTY, "po-1", "so-1", "uuid", "buyer", 0));
        java.util.List<net.minecraft.world.item.ItemStack> pool = new java.util.ArrayList<>(java.util.List.of(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 64)));

        java.util.List<net.minecraft.world.item.ItemStack> deliver =
                com.monpai.sailboatmod.block.entity.DockBlockEntity.resolveDeliverCargo(pool, deliverHere);

        assertFalse(deliver.isEmpty(), "no-spec manifest entry should fall back to delivering the whole pool");
        assertTrue(pool.isEmpty(), "fallback delivery should drain the pool");
    }

    @Test
    void deliverCargoUsesExactSelectionWhenManifestHasItemSpec() {
        // manifest 条目有完整物品规格（多站连运调度）→ 精确抽取，不动其它货
        java.util.List<com.monpai.sailboatmod.market.ShipmentManifestEntry> deliverHere = java.util.List.of(
                new com.monpai.sailboatmod.market.ShipmentManifestEntry(
                        "l1", new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 16),
                        "po-2", "so-2", "uuid", "buyer", 16));
        java.util.List<net.minecraft.world.item.ItemStack> pool = new java.util.ArrayList<>(java.util.List.of(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 64)));

        java.util.List<net.minecraft.world.item.ItemStack> deliver =
                com.monpai.sailboatmod.block.entity.DockBlockEntity.resolveDeliverCargo(pool, deliverHere);

        int delivered = deliver.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
        assertTrue(delivered == 16, "exact selection should deliver only the manifest quantity");
        assertFalse(pool.isEmpty(), "remaining cargo should stay in the pool for onward legs");
    }
}
