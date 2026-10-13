package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.market.MarketOverviewData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenMarketScreenPacketTest {
    @Test
    void roundTripPreservesStockComponentIdentities() {
        MarketOverviewData before = emptyOverview(List.of());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            OpenMarketScreenPacket.encode(new OpenMarketScreenPacket(before), buffer);
            MarketOverviewData after = OpenMarketScreenPacket.decode(buffer).dataForTest();
            assertEquals(before.dockStorageEntries(), after.dockStorageEntries());
            assertEquals("components-A", after.dockStorageEntries().get(0).stockIdentity());
            assertEquals("components-B", after.dockStorageEntries().get(1).stockIdentity());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripPreservesAvailableDispatchOptions() {
        MarketOverviewData.DispatchOption carrier = new MarketOverviewData.DispatchOption(
                "PORT",
                "Crimea Port",
                "Sailboat A",
                "",
                "Crimea Port",
                "",
                0,
                0,
                true,
                "Ready",
                "Docked at Crimea Port"
        );
        OpenMarketScreenPacket packet = new OpenMarketScreenPacket(emptyOverview(List.of(carrier)));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        OpenMarketScreenPacket.encode(packet, buffer);
        OpenMarketScreenPacket decoded = OpenMarketScreenPacket.decode(new FriendlyByteBuf(buffer.copy()));

        assertEquals(List.of(carrier), decoded.dataForTest().availableDispatchOptions());
        assertEquals(List.of(myOrder()), decoded.dataForTest().myOrders());
    }

    private static MarketOverviewData emptyOverview(List<MarketOverviewData.DispatchOption> availableDispatchOptions) {
        return new MarketOverviewData(
                BlockPos.ZERO,
                "Market",
                "-",
                "",
                "",
                0,
                0L,
                0L,
                0L,
                0L,
                false,
                true,
                "Crimea Warehouse",
                "0, 64, 0",
                true,
                true,
                "crimea",
                "Crimea",
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0L,
                0.0F,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(stock("components-A", 10), stock("components-B", 9)),
                List.of(),
                List.of(),
                List.of(),
                availableDispatchOptions,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                List.of(myOrder())
        );
    }

    private static MarketOverviewData.StorageEntry stock(String identity, int count) {
        return new MarketOverviewData.StorageEntry("Enchanted Book x" + count, "minecraft:enchanted_book", "Enchanted Book",
                count, 10, 1, Integer.MAX_VALUE, false, "Warehouse", "material", 0, identity);
    }

    private static MarketOverviewData.MyOrderEntry myOrder() {
        return new MarketOverviewData.MyOrderEntry(
                "order-1", "listing-1", "Wheat", 12, 240,
                "Crimea Port", "Riverside Dock", "WAITING_SHIPMENT",
                2, 120, "Sailboat A", "Trade Route", "PENDING");
    }
}
