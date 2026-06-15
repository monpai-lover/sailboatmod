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
                List.of(),
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
                false
        );
    }
}
