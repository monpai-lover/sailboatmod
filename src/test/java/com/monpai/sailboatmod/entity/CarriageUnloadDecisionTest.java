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

    @Test
    void noSpecEntryDoesNotDrainPoolWhenThereIsOnwardCargo() {
        // 多站连运：本站条目无规格 + 还有续运货(hasKeepOnboard=true) → 不得全卸（否则把续运货误投本站）
        java.util.List<com.monpai.sailboatmod.market.ShipmentManifestEntry> deliverHere = java.util.List.of(
                new com.monpai.sailboatmod.market.ShipmentManifestEntry(
                        "", net.minecraft.world.item.ItemStack.EMPTY, "po-1", "so-1", "uuid", "buyer", 0));
        java.util.List<net.minecraft.world.item.ItemStack> pool = new java.util.ArrayList<>(java.util.List.of(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 64)));

        java.util.List<net.minecraft.world.item.ItemStack> deliver =
                com.monpai.sailboatmod.block.entity.DockBlockEntity.resolveDeliverCargo(pool, deliverHere, true);

        assertTrue(deliver.isEmpty(),
                "no-spec entry must NOT drain the whole pool when onward cargo remains (would mis-deliver)");
        assertFalse(pool.isEmpty(), "onward cargo must stay on the vehicle");
    }

    @Test
    void noSpecEntryDrainsPoolOnlyWhenNoOnwardCargo() {
        // 终点站：无续运货(hasKeepOnboard=false) → 空规格才安全兜底全卸（修手动发车）
        java.util.List<com.monpai.sailboatmod.market.ShipmentManifestEntry> deliverHere = java.util.List.of(
                new com.monpai.sailboatmod.market.ShipmentManifestEntry(
                        "", net.minecraft.world.item.ItemStack.EMPTY, "po-1", "so-1", "uuid", "buyer", 0));
        java.util.List<net.minecraft.world.item.ItemStack> pool = new java.util.ArrayList<>(java.util.List.of(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 64)));

        java.util.List<net.minecraft.world.item.ItemStack> deliver =
                com.monpai.sailboatmod.block.entity.DockBlockEntity.resolveDeliverCargo(pool, deliverHere, false);

        assertFalse(deliver.isEmpty(), "no-spec entry at final stop should fall back to delivering the whole pool");
        assertTrue(pool.isEmpty(), "fallback delivery drains the pool at the final stop");
    }

    @Test
    void manualTripWithoutManifestDrainsPoolWhenNoOnwardCargo() {
        java.util.List<com.monpai.sailboatmod.market.ShipmentManifestEntry> deliverHere = java.util.List.of();
        java.util.List<net.minecraft.world.item.ItemStack> pool = new java.util.ArrayList<>(java.util.List.of(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 64)));

        java.util.List<net.minecraft.world.item.ItemStack> deliver =
                com.monpai.sailboatmod.block.entity.DockBlockEntity.resolveDeliverCargo(pool, deliverHere, false);

        assertFalse(deliver.isEmpty(), "manual cargo with no manifest should deliver the whole pool at the final stop");
        assertTrue(pool.isEmpty(), "manual no-manifest delivery should drain the pool");
    }
}
