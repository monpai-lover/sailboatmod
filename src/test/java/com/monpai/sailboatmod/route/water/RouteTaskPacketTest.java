package com.monpai.sailboatmod.route.water;
import com.monpai.sailboatmod.network.packet.SyncRouteTasksPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RouteTaskPacketTest {
    @Test void taskIdentityAndElapsedTimeRoundTrip() {
        var task = new RouteTaskView(UUID.randomUUID(), "minecraft:overworld", new BlockPos(-55,63,12),
                new BlockPos(7400,63,-5), 37, 0, "Tellus LOD粗寻", 1000, 5000);
        var packet = new SyncRouteTasksPacket(UUID.randomUUID(), List.of(task));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try { SyncRouteTasksPacket.encode(packet, buf); assertEquals(packet, SyncRouteTasksPacket.decode(buf)); }
        finally { buf.release(); }
    }
    @Test void oversizedTaskSnapshotsAreRejectedBeforeAllocation() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try { buf.writeUUID(UUID.randomUUID()); buf.writeVarInt(1000000);
            assertThrows(IllegalArgumentException.class, () -> SyncRouteTasksPacket.decode(buf)); }
        finally { buf.release(); }
    }
}
