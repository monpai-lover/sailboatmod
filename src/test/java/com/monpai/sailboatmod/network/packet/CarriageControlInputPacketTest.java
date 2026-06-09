package com.monpai.sailboatmod.network.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageControlInputPacketTest {
    @Test
    void roundTripsDriveState() {
        CarriageControlInputPacket packet = new CarriageControlInputPacket(
                com.monpai.sailboatmod.entity.CarriageDriveInput.AccelerationDirection.FORWARD,
                com.monpai.sailboatmod.entity.CarriageDriveInput.TurnDirection.LEFT,
                17.5F,
                0.8F
        );
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        CarriageControlInputPacket.encode(packet, buffer);
        CarriageControlInputPacket decoded = CarriageControlInputPacket.decode(new FriendlyByteBuf(buffer.copy()));

        assertEquals(com.monpai.sailboatmod.entity.CarriageDriveInput.AccelerationDirection.FORWARD, decoded.acceleration());
        assertEquals(com.monpai.sailboatmod.entity.CarriageDriveInput.TurnDirection.LEFT, decoded.turn());
        assertEquals(17.5F, decoded.targetTurnAngle(), 1.0E-6F);
        assertEquals(0.8F, decoded.power(), 1.0E-6F);
    }
}
