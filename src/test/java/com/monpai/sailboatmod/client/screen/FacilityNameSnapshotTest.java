package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.dock.FacilityNames;
import com.monpai.sailboatmod.transport.air.airport.AirportGroundLayout;
import com.monpai.sailboatmod.transport.air.airport.AirportScreenData;
import com.monpai.sailboatmod.network.packet.rail.RailTerminalActionPacket;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FacilityNameSnapshotTest {
    @Test void defaultAirportNameIsOnlyAPlaceholderAndNeverAnImplicitOverride() throws Exception {
        var data = snapshot("Harbor Town", "");
        var received = AirportScreenData.load(data.save());
        assertEquals("Harbor Town", received.name());
        assertEquals("", received.customName());
        var screen = new AirportScreen(received, packet -> { throw new AssertionError("Opening must not rename"); });
        Field draft = AirportScreen.class.getDeclaredField("nameDraft"); draft.setAccessible(true);
        assertEquals("", draft.get(screen), "Saving an untouched default field must keep automatic naming");
    }
    @Test void airportCustomNameSurvivesSnapshotRoundTrip() {
        var data = snapshot("East Airport", "East Airport");
        assertEquals(data, AirportScreenData.load(data.save()));
    }
    @Test void railNameChangeAndResetRoundTripWithTheSameRevisionAndIdentity() {
        for (String name : List.of("East Freight", "")) {
            var packet = new RailTerminalActionPacket(Level.OVERWORLD.location(), new BlockPos(1,64,2),
                    new UUID(1,2), 42, RailTerminalActionPacket.Action.RENAME, BlockPos.ZERO, new UUID(0,0), name);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                RailTerminalActionPacket.encode(packet, buffer);
                assertEquals(packet, RailTerminalActionPacket.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
    @Test void namesPermitEmptyResetAndRejectControlCharactersAndOversizedOverrides() {
        assertTrue(FacilityNames.validOverride("")); assertTrue(FacilityNames.validOverride("  东站  "));
        assertFalse(FacilityNames.validOverride("a".repeat(65)));
        assertFalse(FacilityNames.validOverride("Line\nBreak"));
        assertFalse(FacilityNames.validOverride("Hidden\u202eName"));
        assertThrows(IllegalArgumentException.class, () -> new RailTerminalActionPacket(Level.OVERWORLD.location(),
                BlockPos.ZERO, new UUID(1,2), 0, RailTerminalActionPacket.Action.RENAME, BlockPos.ZERO, new UUID(0,0), "\n"));
    }
    private static AirportScreenData snapshot(String display, String custom) {
        return new AirportScreenData(Level.OVERWORLD.location(), BlockPos.ZERO, display, "Harbor Town", "Owner", true,
                0, List.of(), AirportGroundLayout.empty(), List.of(), "", custom);
    }
}
