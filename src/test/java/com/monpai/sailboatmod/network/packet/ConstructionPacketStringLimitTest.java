package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.ConstructionGhostClientHooks;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ConstructionPacketStringLimitTest {
    private static final String LONG_JOB_ID = "job|" + "a".repeat(110);
    private static final String LONG_ROAD_ID = "manual|town:source_settlement_alpha|town:target_settlement_beta|segment|" + "r".repeat(70);

    @Test
    void roadConstructionProgressPacketSupportsLongRoadIds() {
        SyncRoadConstructionProgressPacket packet = new SyncRoadConstructionProgressPacket(List.of(
                new SyncRoadConstructionProgressPacket.Entry(
                        LONG_ROAD_ID,
                        "SourceTown",
                        "TargetTown",
                        new BlockPos(12, 64, 34),
                        55,
                        3
                )
        ));

        FriendlyByteBuf encoded = assertDoesNotThrow(() -> encode(packet));
        SyncRoadConstructionProgressPacket decoded = assertDoesNotThrow(() -> SyncRoadConstructionProgressPacket.decode(copy(encoded)));
        FriendlyByteBuf roundTrip = assertDoesNotThrow(() -> encode(decoded));

        assertArrayEquals(toByteArray(encoded), toByteArray(roundTrip));
    }

    @Test
    void constructionGhostPreviewPacketSupportsLongJobAndRoadIds() {
        SyncConstructionGhostPreviewPacket packet = new SyncConstructionGhostPreviewPacket(
                List.of(new SyncConstructionGhostPreviewPacket.BuildingEntry(
                        LONG_JOB_ID,
                        "warehouse",
                        new BlockPos(1, 64, 1),
                        List.of(),
                        null,
                        40,
                        2
                )),
                List.of(new SyncConstructionGhostPreviewPacket.RoadEntry(
                        LONG_JOB_ID,
                        LONG_ROAD_ID,
                        "SourceTown",
                        "TargetTown",
                        List.of(),
                        new BlockPos(6, 64, 6),
                        28,
                        1
                ))
        );

        FriendlyByteBuf encoded = assertDoesNotThrow(() -> encode(packet));
        SyncConstructionGhostPreviewPacket decoded = assertDoesNotThrow(() -> SyncConstructionGhostPreviewPacket.decode(copy(encoded)));
        FriendlyByteBuf roundTrip = assertDoesNotThrow(() -> encode(decoded));

        assertArrayEquals(toByteArray(encoded), toByteArray(roundTrip));
    }

    @Test
    void builderHammerPacketSupportsLongJobIds() {
        UseBuilderHammerPacket packet = new UseBuilderHammerPacket(
                ConstructionGhostClientHooks.TargetKind.ROAD,
                LONG_JOB_ID,
                new BlockPos(8, 64, 8)
        );

        FriendlyByteBuf encoded = assertDoesNotThrow(() -> encode(packet));
        UseBuilderHammerPacket decoded = assertDoesNotThrow(() -> UseBuilderHammerPacket.decode(copy(encoded)));
        FriendlyByteBuf roundTrip = assertDoesNotThrow(() -> encode(decoded));

        assertArrayEquals(toByteArray(encoded), toByteArray(roundTrip));
    }

    private static FriendlyByteBuf encode(SyncRoadConstructionProgressPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncRoadConstructionProgressPacket.encode(packet, buffer);
        return buffer;
    }

    private static FriendlyByteBuf encode(SyncConstructionGhostPreviewPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncConstructionGhostPreviewPacket.encode(packet, buffer);
        return buffer;
    }

    private static FriendlyByteBuf encode(UseBuilderHammerPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        UseBuilderHammerPacket.encode(packet, buffer);
        return buffer;
    }

    private static FriendlyByteBuf copy(FriendlyByteBuf buffer) {
        return new FriendlyByteBuf(buffer.copy());
    }

    private static byte[] toByteArray(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(0, bytes);
        return bytes;
    }
}
