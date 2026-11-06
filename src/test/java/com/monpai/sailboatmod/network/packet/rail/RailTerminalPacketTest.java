package com.monpai.sailboatmod.network.packet.rail;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailTerminalPacketTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.parse("minecraft:overworld");
    @Test void bindingRetainsNativeIdentityAndOptimisticRevision() {
        var expected = new RailTerminalActionPacket(DIMENSION, new BlockPos(8, 64, 8), UUID.randomUUID(), 43,
                RailTerminalActionPacket.Action.BIND, new BlockPos(12, 64, 8), UUID.randomUUID());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RailTerminalActionPacket.encode(expected, buffer);
            assertEquals(expected, RailTerminalActionPacket.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
    @Test void rejectsInvalidRevisionAndCoordinatesBeforeWorldAccess() {
        assertThrows(IllegalArgumentException.class, () -> packet(-1, BlockPos.ZERO));
        assertThrows(IllegalArgumentException.class, () -> packet(0, new BlockPos(Integer.MIN_VALUE, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> packet(0, new BlockPos(0, 64, 30_000_001)));
    }
    @Test void pollingSnapshotCannotTurnIntoAnOpenWindowRequest() {
        var data = new CompoundTag(); data.putString("Result", "saved"); data.putLong("Revision", 44);
        var packet = new RailTerminalSnapshotPacket(data, false);
        data.putString("Result", "changed_after_send");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RailTerminalSnapshotPacket.encode(packet, buffer);
            var decoded = RailTerminalSnapshotPacket.decode(buffer);
            assertFalse(decoded.open());
            assertEquals("saved", decoded.data().getString("Result"));
            assertEquals(44, decoded.data().getLong("Revision"));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
    private static RailTerminalActionPacket packet(long revision, BlockPos station) {
        return new RailTerminalActionPacket(DIMENSION, BlockPos.ZERO, UUID.randomUUID(), revision,
                RailTerminalActionPacket.Action.BIND, station, UUID.randomUUID());
    }
}
