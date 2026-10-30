package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.market.MarketOverviewData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketLiveUpdatePacketTest {
    @Test
    void carriesTheSameWireDataAsTheOverviewReply() {
        MarketOverviewData data = overview();
        FriendlyByteBuf live = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf reply = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MarketLiveUpdatePacket.encode(new MarketLiveUpdatePacket(data), live);
            OpenMarketScreenPacket.encode(new OpenMarketScreenPacket(data), reply);
            byte[] liveBytes = new byte[live.readableBytes()];
            live.getBytes(0, liveBytes);
            byte[] replyBytes = new byte[reply.readableBytes()];
            reply.getBytes(0, replyBytes);
            assertArrayEquals(replyBytes, liveBytes);

            MarketOverviewData decoded = MarketLiveUpdatePacket.decode(live).dataForTest();
            assertEquals(data.listingEntries(), decoded.listingEntries());
            assertEquals(data.myOrders(), decoded.myOrders());
            assertEquals(0, live.readableBytes());
        } finally {
            live.release();
            reply.release();
        }
    }

    @Test
    void registeredLastAsAClientboundPayload() throws Exception {
        String network = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/ModNetwork.java")).replace("\r\n", "\n");
        int at = network.lastIndexOf("CHANNEL.registerMessage(");
        String last = network.substring(at, network.indexOf(");", network.indexOf("Optional.of(", at)) + 2);
        assertTrue(last.contains("MarketLiveUpdatePacket.class") && last.contains("PLAY_TO_CLIENT"), last);
        assertTrue(network.contains("PROTOCOL_VERSION = \"16\""), "a new payload changes the wire protocol");
    }

    private static MarketOverviewData overview() {
        MarketOverviewData.ListingEntry listing = new MarketOverviewData.ListingEntry("L1", "Wheat", "minecraft:wheat", "Wheat",
                12, 0, 7, "Seller", "seller-uuid", "Dock", "", "", "crop", 0);
        MarketOverviewData.MyOrderEntry order = new MarketOverviewData.MyOrderEntry("PO-1", "L1", "Wheat", 3, 21,
                "Dock", "Harbor", "IN_TRANSIT", 0, 0, "Swift", "Coast", "SAILING");
        return new MarketOverviewData(new BlockPos(1, 64, 2), "Harbor", "Owner", "owner", "viewer",
                0, 10L, 0L, 10L, 0L, false, true, "Dock", "0, 64, 0", true, false,
                "", "", 0, 0, 0, 0, 0, 0L, 0L, 0L, 0.0F,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(listing), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), false, List.of(order));
    }
}
