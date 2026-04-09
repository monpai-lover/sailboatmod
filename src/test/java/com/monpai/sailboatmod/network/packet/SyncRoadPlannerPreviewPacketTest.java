package com.monpai.sailboatmod.network.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SyncRoadPlannerPreviewPacketTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roundTripsFullPreviewPayload() {
        SyncRoadPlannerPreviewPacket packet = new SyncRoadPlannerPreviewPacket(
                "SourceTown",
                "TargetTown",
                java.util.List.of(new SyncRoadPlannerPreviewPacket.GhostBlock(
                        new BlockPos(2, 65, 2),
                        Blocks.STONE_BRICK_SLAB.defaultBlockState()
                )),
                new BlockPos(1, 65, 1),
                new BlockPos(8, 65, 8),
                new BlockPos(5, 65, 5),
                true
        );

        FriendlyByteBuf encoded = assertDoesNotThrow(() -> encode(packet));
        SyncRoadPlannerPreviewPacket decoded = assertDoesNotThrow(() -> SyncRoadPlannerPreviewPacket.decode(copy(encoded)));
        FriendlyByteBuf roundTrip = assertDoesNotThrow(() -> encode(decoded));

        assertArrayEquals(toByteArray(encoded), toByteArray(roundTrip));
    }

    @Test
    void roundTripsEmptyPreviewPayloadWithClearedHighlights() {
        SyncRoadPlannerPreviewPacket packet = new SyncRoadPlannerPreviewPacket(
                "",
                "",
                java.util.List.of(),
                null,
                null,
                null,
                false
        );

        FriendlyByteBuf encoded = assertDoesNotThrow(() -> encode(packet));
        SyncRoadPlannerPreviewPacket decoded = assertDoesNotThrow(() -> SyncRoadPlannerPreviewPacket.decode(copy(encoded)));
        FriendlyByteBuf roundTrip = assertDoesNotThrow(() -> encode(decoded));

        assertArrayEquals(toByteArray(encoded), toByteArray(roundTrip));
    }

    private static FriendlyByteBuf encode(SyncRoadPlannerPreviewPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncRoadPlannerPreviewPacket.encode(packet, buffer);
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
