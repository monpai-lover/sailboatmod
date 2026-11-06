package com.monpai.sailboatmod.network.packet.airport;

import com.monpai.sailboatmod.transport.air.airport.AirportGroundLayout;
import com.monpai.sailboatmod.transport.air.airport.AirportScreenData;
import com.monpai.sailboatmod.client.screen.AirportScreen;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AirportDimensionBindingTest {
    @Test void snapshotWireDataRetainsItsExplicitDimension() {
        var tag = snapshot().save();
        tag.putString("Dimension", "minecraft:the_nether");
        var decoded = AirportScreenData.load(tag);
        assertEquals("minecraft:the_nether", decoded.save().getString("Dimension"));
    }

    @Test void snapshotWithoutDimensionIsRejectedInsteadOfDefaultingToCurrentWorld() {
        var tag = snapshot().save();
        tag.remove("Dimension");
        assertThrows(IllegalArgumentException.class, () -> AirportScreenData.load(tag));
    }

    @Test void foreignDimensionSnapshotCannotReplaceAnExistingScreensIdentityOrDraft() {
        var original = snapshot();
        var screen = new AirportScreen(original);
        var tag = original.save();
        tag.putString("Dimension", "minecraft:the_nether");
        tag.putString("Name", "Wrong airport at same coordinates");
        screen.update(AirportScreenData.load(tag), false);
        assertSame(original, screen.data());
    }

    @Test void actionWireRoundTripBindsItsOriginalDimension() {
        var packet = AirportActionPacket.simple(Level.NETHER.location(), BlockPos.ZERO, 17,
                AirportActionPacket.Action.REMOVE_RUNWAY, "runway");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AirportActionPacket.encode(packet, buffer);
            var decoded = AirportActionPacket.decode(buffer);
            assertEquals(Level.NETHER.location(), decoded.dimension());
            assertEquals(packet, decoded);
        } finally { buffer.release(); }
    }

    @Test void malformedOrOversizedDimensionCannotEnterActionOrSnapshotState() {
        var tag = snapshot().save();
        tag.putString("Dimension", "INVALID DIMENSION");
        assertThrows(IllegalArgumentException.class, () -> AirportScreenData.load(tag));
        assertThrows(IllegalArgumentException.class, () -> AirportActionPacket.simple(null, BlockPos.ZERO, 0,
                AirportActionPacket.Action.REFRESH, ""));
        assertThrows(IllegalArgumentException.class, () -> AirportActionPacket.simple(
                ResourceLocation.fromNamespaceAndPath("test", "x".repeat(256)), BlockPos.ZERO, 0,
                AirportActionPacket.Action.REFRESH, ""));
    }

    private static AirportScreenData snapshot() {
        return new AirportScreenData(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO, "Airport", "Town", "Owner", true, 0,
                List.of(), AirportGroundLayout.empty(), List.of(), "");
    }
}
