package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerEditCommitPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketCollectionBoundsTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void tradeRejectsItemCountLargerThanEncodedPayloadBeforeAllocating() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeEnum(TradeScreenActionPacket.Action.PROPOSE);
            buffer.writeUtf("beta");
            buffer.writeLong(0L);
            buffer.writeLong(0L);
            buffer.writeVarInt(1024);

            assertThrows(IllegalArgumentException.class, () -> TradeScreenActionPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void roadEditRejectsSegmentCountLargerThanEncodedPayloadBeforeAllocating() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(new UUID(0L, 0L));
            buffer.writeUtf("road");
            buffer.writeVarInt(0);
            buffer.writeVarInt(1024);

            assertThrows(IllegalArgumentException.class, () -> RoadPlannerEditCommitPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
