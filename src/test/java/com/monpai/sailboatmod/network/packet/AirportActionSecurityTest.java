package com.monpai.sailboatmod.network.packet;
import com.monpai.sailboatmod.network.packet.airport.AirportActionPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class AirportActionSecurityTest {
    @TempDir Path temporary;
    @Test void missingSenderCannotReadOrMutateAnAirport() throws Exception {
        var context = PacketServerTestSupport.context(null);
        assertDoesNotThrow(() -> AirportActionPacket.handle(AirportActionPacket.simple(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO,0,
                AirportActionPacket.Action.REMOVE_RUNWAY,"runway"),() -> context));
    }
    @Test void actionFromPreviousDimensionIsRejectedBeforeAnyChunkOrAirportLookup() throws Exception {
        // This fixture intentionally has no initialized chunk source: reaching hasChunkAt would fail.
        var player = PacketServerTestSupport.player(temporary, "nation");
        var context = PacketServerTestSupport.context(player);
        var packet = AirportActionPacket.simple(net.minecraft.world.level.Level.NETHER.location(),BlockPos.ZERO,0,
                AirportActionPacket.Action.REMOVE_RUNWAY,"runway");
        assertDoesNotThrow(() -> AirportActionPacket.handle(packet, () -> context));
    }
}
