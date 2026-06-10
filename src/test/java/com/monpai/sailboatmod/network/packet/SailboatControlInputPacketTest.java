package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.SailboatControlInput;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SailboatControlInputPacketTest {
    @Test
    void roundTripsExplicitKeyState() {
        SailboatControlInputPacket packet = new SailboatControlInputPacket(
                SailboatControlInput.fromKeys(true, false, false, true, false)
        );
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        SailboatControlInputPacket.encode(packet, buffer);
        SailboatControlInputPacket decoded = SailboatControlInputPacket.decode(new FriendlyByteBuf(buffer.copy()));

        assertFalse(decoded.input().wantsForward());
        assertFalse(decoded.input().wantsReverse());
        assertTrue(decoded.input().wantsTurn());
    }
}
