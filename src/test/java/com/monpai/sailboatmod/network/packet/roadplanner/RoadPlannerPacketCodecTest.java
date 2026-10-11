package com.monpai.sailboatmod.network.packet.roadplanner;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoadPlannerPacketCodecTest {
    @Test
    void preservesRoadNodeCoordinates() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            List<BlockPos> positions = List.of(new BlockPos(-12, 70, 40), new BlockPos(18, 65, -24));
            RoadPlannerPacketCodec.writeBlockPosList(buffer, positions);
            assertEquals(positions, RoadPlannerPacketCodec.readBlockPosList(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsHugeCountBeforeAllocating() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> RoadPlannerPacketCodec.readBlockPosList(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsTruncatedCoordinateList() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(2);
            buffer.writeBlockPos(BlockPos.ZERO);
            assertThrows(IllegalArgumentException.class, () -> RoadPlannerPacketCodec.readBlockPosList(buffer));
        } finally {
            buffer.release();
        }
    }
}
