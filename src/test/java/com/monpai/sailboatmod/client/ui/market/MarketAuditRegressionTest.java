package com.monpai.sailboatmod.client.ui.market;

import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.network.packet.CreateMarketListingPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions from the phase-1 Windows audit (sailboatmod-ingame-ui-phase1-test-report). Storage rows are built like
 * MarketBlockEntity builds them: the label carries the count, and two different stacks (two enchanted books with
 * different enchantments) can produce identical rows. Each check decodes the packet that would actually be sent.
 */
class MarketAuditRegressionTest {
    private final List<Object> sent = new ArrayList<>();

    @Test
    void clickingTheSecondOfTwoIdenticalRowsListsTheSecondStack() {
        MarketFixtures fixtures = new MarketFixtures();
        fixtures.storage.add(serverStorage("minecraft:enchanted_book", "Enchanted Book", 1, 10));
        fixtures.storage.add(serverStorage("minecraft:enchanted_book", "Enchanted Book", 1, 10));
        MarketViewModel model = new MarketViewModel(fixtures.build());
        MarketController controller = new MarketController(model, sent::add, () -> 0L);

        model.selectStorage(model.storageRowKey(1));

        assertEquals(1, model.selectedStorageIndex());
        assertEquals(MarketController.Result.SENT, controller.createListing());
        assertEquals(1, listingWire(sent.get(0)).storageIndex());
    }

    @Test
    void reselectingTheSelectedRowKeepsTheTypedPrice() {
        MarketFixtures fixtures = new MarketFixtures();
        MarketOverviewData.StorageEntry storage = serverStorage("minecraft:wheat", "Wheat", 64, 10);
        fixtures.storage.add(storage);
        MarketViewModel model = new MarketViewModel(fixtures.build());
        MarketController controller = new MarketController(model, sent::add, () -> 0L);
        model.setListingQty("3");
        model.setListingUnitPrice("11");

        model.selectStorage(model.storageRowKey(0));

        assertEquals("11", model.listingUnitPrice(), "the field still shows 11, so 11 is what must be sent");
        assertEquals(MarketController.Result.SENT, controller.createListing());
        ListingWire wire = listingWire(sent.get(0));
        assertEquals(0, wire.storageIndex());
        assertEquals(3, wire.quantity());
        assertEquals(11, wire.unitPrice());
    }

    @Test
    void aStockCountChangeKeepsTheSelectionAndItsPrice() {
        MarketFixtures initial = new MarketFixtures();
        initial.storage.add(serverStorage("minecraft:stick", "Stick", 100, 2));
        initial.storage.add(serverStorage("minecraft:wheat", "Wheat", 64, 10));
        MarketViewModel model = new MarketViewModel(initial.build());
        model.selectStorage(model.storageRowKey(1));
        model.setListingUnitPrice("11");

        MarketFixtures refreshed = new MarketFixtures();
        refreshed.storage.add(initial.storage.get(0));
        refreshed.storage.add(serverStorage("minecraft:wheat", "Wheat", 63, 10));
        model.update(refreshed.build());

        assertEquals("minecraft:wheat", model.selectedStorage().commodityKey());
        assertEquals(63, model.selectedStorage().quantity());
        assertEquals("11", model.listingUnitPrice());
        MarketController controller = new MarketController(model, sent::add, () -> 0L);
        assertEquals(MarketController.Result.SENT, controller.createListing());
        ListingWire wire = listingWire(sent.get(0));
        assertEquals(1, wire.storageIndex());
        assertEquals(11, wire.unitPrice());
    }

    @Test
    void aReorderedRefreshFollowsTheStackWhenItIsUnique() {
        MarketFixtures initial = new MarketFixtures();
        initial.storage.add(serverStorage("minecraft:stick", "Stick", 100, 2));
        initial.storage.add(serverStorage("minecraft:wheat", "Wheat", 64, 10));
        MarketViewModel model = new MarketViewModel(initial.build());
        model.selectStorage(model.storageRowKey(1));
        model.setListingUnitPrice("11");

        MarketFixtures refreshed = new MarketFixtures();
        refreshed.storage.add(serverStorage("minecraft:wheat", "Wheat", 64, 10));
        refreshed.storage.add(serverStorage("minecraft:stick", "Stick", 100, 2));
        model.update(refreshed.build());

        assertEquals(0, model.selectedStorageIndex());
        assertEquals("minecraft:wheat", model.selectedStorage().commodityKey());
        assertEquals("11", model.listingUnitPrice());
        assertFalse(model.consumeStorageSelectionLost());
    }

    @Test
    void anAmbiguousOrVanishedStackDropsTheSelectionAndPriceInsteadOfGuessing() {
        MarketFixtures initial = new MarketFixtures();
        initial.storage.add(serverStorage("minecraft:stick", "Stick", 100, 2));
        initial.storage.add(serverStorage("minecraft:enchanted_book", "Enchanted Book", 1, 10));
        MarketViewModel model = new MarketViewModel(initial.build());
        model.selectStorage(model.storageRowKey(1));
        model.setListingUnitPrice("40");

        // The selected row's position now holds something else and two identical books exist elsewhere.
        MarketFixtures refreshed = new MarketFixtures();
        refreshed.storage.add(serverStorage("minecraft:enchanted_book", "Enchanted Book", 1, 10));
        refreshed.storage.add(serverStorage("minecraft:stick", "Stick", 100, 2));
        refreshed.storage.add(serverStorage("minecraft:enchanted_book", "Enchanted Book", 1, 10));
        model.update(refreshed.build());

        assertNull(model.selectedStorage());
        assertEquals(-1, model.selectedStorageIndex());
        assertEquals("", model.listingUnitPrice(), "the price typed for the old stack must not carry over");
        assertTrue(model.consumeStorageSelectionLost(), "the player is told to pick again");
        MarketController controller = new MarketController(model, sent::add, () -> 0L);
        assertEquals(MarketController.Result.NOTHING_SELECTED, controller.createListing());
        assertTrue(sent.isEmpty());
    }

    @Test
    void numberInputOutsideItsRangeReachesTheModelAsInvalid() {
        assertTrue(Ui.isNumberWithin("10", 0, 1_000_000));
        assertFalse(Ui.isNumberWithin("2000000", 0, 1_000_000));
        assertFalse(Ui.isNumberWithin("abc", 0, 1_000_000));
        assertFalse(Ui.isNumberWithin("", 0, 1_000_000));
        assertEquals(-1, MarketViewModel.parseQuantity(Ui.INVALID_NUMBER));
        assertEquals(-1, MarketViewModel.parseQuantity(""), "a cleared quantity cannot be sent");
    }

    @Test
    void priceBandsOutsideTheAcceptedRangeBlockTheBuyOrder() {
        MarketFixtures fixtures = new MarketFixtures();
        MarketViewModel model = new MarketViewModel(fixtures.build());
        model.setBuyOrderCommodity("minecraft:wheat");
        model.setBuyOrderQty("5");
        model.setBuyOrderMinBp("-100");
        model.setBuyOrderMaxBp("300");
        assertTrue(model.isBuyOrderValid());

        model.setBuyOrderMaxBp("5000");
        assertFalse(model.isBuyOrderValid(), "5000 used to be clamped to 1000 and sent silently");
        model.setBuyOrderMaxBp("-");
        assertFalse(model.isBuyOrderValid());
        model.setBuyOrderMaxBp("");
        model.setBuyOrderMinBp("");
        assertTrue(model.isBuyOrderValid(), "empty bands read as 0, as before");
    }

    private static MarketOverviewData.StorageEntry serverStorage(String commodityKey, String itemName, int count, int suggested) {
        return new MarketOverviewData.StorageEntry(itemName + " x" + count, commodityKey, itemName, count,
                suggested, 1, Integer.MAX_VALUE, false, "Warehouse", "material", 0, "fixture:" + commodityKey + ":" + itemName);
    }

    private static ListingWire listingWire(Object packet) {
        CreateMarketListingPacket listing = assertInstanceOf(CreateMarketListingPacket.class, packet);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CreateMarketListingPacket.encode(listing, buffer);
            return new ListingWire(buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
        } finally {
            buffer.release();
        }
    }

    private record ListingWire(BlockPos marketPos, int storageIndex, int quantity, int unitPrice) {
    }
}
