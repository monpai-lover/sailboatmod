package com.monpai.sailboatmod.client.ui.market;

import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.network.packet.CreateMarketListingPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketStockSelectionRegressionTest {
    @Test
    void countBasedReorderingKeepsTheSelectedComponentVariantAndSendsItsIdentity() {
        MarketFixtures initial = new MarketFixtures();
        initial.storage.add(book("components-A", 10));
        initial.storage.add(book("components-B", 9));
        MarketViewModel model = new MarketViewModel(initial.build());
        model.selectStorage(model.storageRowKey(1));
        model.setListingQty("3");
        model.setListingUnitPrice("40");
        MarketFixtures refreshed = new MarketFixtures();
        refreshed.storage.add(book("components-B", 9));
        refreshed.storage.add(book("components-A", 8));

        model.update(refreshed.build());

        assertEquals(0, model.selectedStorageIndex(), "B moved to row 0; row 1 now contains A");
        assertEquals("40", model.listingUnitPrice());
        assertFalse(model.consumeStorageSelectionLost());
        List<Object> sent = new ArrayList<>();
        assertEquals(MarketController.Result.SENT, new MarketController(model, sent::add, () -> 0L).createListing());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CreateMarketListingPacket.encode(assertInstanceOf(CreateMarketListingPacket.class, sent.get(0)), buffer);
            assertEquals(MarketFixtures.MARKET, buffer.readBlockPos());
            assertEquals(0, buffer.readVarInt());
            assertEquals(3, buffer.readVarInt());
            assertEquals(40, buffer.readVarInt());
            assertEquals("", buffer.readUtf(120));
            assertEquals("components-B", buffer.readUtf(128));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void listingRequestDecodeAndEncodePreserveExpectedIdentityAndSellerNote() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf roundTrip = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CreateMarketListingPacket.encode(new CreateMarketListingPacket(MarketFixtures.MARKET, 1, 3, 40,
                    "seller note", "components-B"), buffer);
            byte[] expected = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), expected);
            CreateMarketListingPacket decoded = CreateMarketListingPacket.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            CreateMarketListingPacket.encode(decoded, roundTrip);
            byte[] actual = new byte[roundTrip.readableBytes()];
            roundTrip.readBytes(actual);
            assertArrayEquals(expected, actual);
        } finally {
            buffer.release();
            roundTrip.release();
        }
    }

    @Test
    void displayOnlyLegacyEntriesCannotSendListingRequests() {
        MarketFixtures fixtures = new MarketFixtures();
        fixtures.storage.add(legacyBook(10));
        List<Object> sent = new ArrayList<>();
        MarketViewModel model = new MarketViewModel(fixtures.build());
        assertEquals(MarketController.Result.NOTHING_SELECTED, new MarketController(model, sent::add, () -> 0L).createListing());
        assertTrue(sent.isEmpty());
    }

    @Test
    void legacySameNamedVariantsDropSelectionAfterRefreshEvenWhenTheOldIndexStillMatches() {
        MarketFixtures initial = new MarketFixtures();
        initial.storage.add(legacyBook(10));
        initial.storage.add(legacyBook(9));
        MarketViewModel model = new MarketViewModel(initial.build());
        model.selectStorage(model.storageRowKey(1));
        model.setListingUnitPrice("40");
        MarketFixtures refreshed = new MarketFixtures();
        refreshed.storage.add(legacyBook(9));
        refreshed.storage.add(legacyBook(8));

        model.update(refreshed.build());

        assertNull(model.selectedStorage());
        assertEquals("", model.listingUnitPrice());
        assertTrue(model.consumeStorageSelectionLost());
    }

    private static MarketOverviewData.StorageEntry book(String identity, int count) {
        return new MarketOverviewData.StorageEntry("Enchanted Book x" + count, "minecraft:enchanted_book", "Enchanted Book",
                count, 10, 1, Integer.MAX_VALUE, false, "Warehouse", "material", 0, identity);
    }

    private static MarketOverviewData.StorageEntry legacyBook(int count) {
        return new MarketOverviewData.StorageEntry("Enchanted Book x" + count, "minecraft:enchanted_book", "Enchanted Book",
                count, 10, 1, Integer.MAX_VALUE, false, "Warehouse", "material", 0);
    }
}
