package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.integration.xaero.SailboatClaimHighlightEntry;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncClaimHighlightsPacketTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roundTripsClaimHighlightEntries() {
        SyncClaimHighlightsPacket packet = new SyncClaimHighlightsPacket(List.of(
                new SailboatClaimHighlightEntry(
                        "minecraft:overworld",
                        -4,
                        12,
                        "north",
                        "North Kingdom",
                        "harbor",
                        "Harbor",
                        0x123456,
                        0x654321
                ),
                new SailboatClaimHighlightEntry(
                        "minecraft:the_nether",
                        8,
                        -3,
                        "south",
                        "South Kingdom",
                        "",
                        "",
                        0xABCDEF,
                        0xFEDCBA
                )
        ));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncClaimHighlightsPacket.encode(packet, buffer);
        SyncClaimHighlightsPacket decoded = SyncClaimHighlightsPacket.decode(buffer);

        assertEquals(packet.entries(), decoded.entries());
    }

    @Test
    void constructorDefensivelyCopiesEntries() {
        List<SailboatClaimHighlightEntry> source = new java.util.ArrayList<>();
        source.add(new SailboatClaimHighlightEntry(
                "minecraft:overworld",
                1,
                2,
                "north",
                "North",
                "capital",
                "Capital",
                0x111111,
                0x222222
        ));

        SyncClaimHighlightsPacket packet = new SyncClaimHighlightsPacket(source);
        source.clear();

        assertEquals(1, packet.entries().size());
    }
}
