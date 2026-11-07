package com.monpai.sailboatmod.network.packet.rail;

import io.netty.buffer.Unpooled;
import com.monpai.sailboatmod.transport.rail.RailRoute;
import com.monpai.sailboatmod.transport.rail.RailStationRef;
import com.monpai.sailboatmod.transport.rail.ui.RailTerminalStopovers;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
    @Test void stopoverEditKeepsExactDestinationAndOrderedNativeStationIdentities() {
        var destination = choice(96);
        var first = choice(48);
        var second = choice(64);
        var expected = RailTerminalActionPacket.setStopovers(DIMENSION, BlockPos.ZERO, UUID.randomUUID(),
                43L, destination, List.of(second, first));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RailTerminalActionPacket.encode(expected, buffer);
            assertEquals(expected, RailTerminalActionPacket.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
    @Test void stopoverEditRejectsExcessiveCountBeforeReadingTheChoices() {
        var target = choice(96);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(DIMENSION).writeBlockPos(BlockPos.ZERO).writeUUID(UUID.randomUUID()).writeLong(43);
            buffer.writeEnum(RailTerminalActionPacket.Action.SET_STOPOVERS);
            buffer.writeBlockPos(BlockPos.ZERO).writeUUID(new UUID(0, 0));
            buffer.writeBlockPos(target.terminalPos()).writeUUID(target.terminalId())
                    .writeBlockPos(target.station().stationPos()).writeUUID(target.station().stationId());
            buffer.writeVarInt(RailRoute.MAX_STOPOVERS + 1);
            assertThrows(IllegalArgumentException.class, () -> RailTerminalActionPacket.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
    @Test void configuredRouteRoundTripsAndRejectsRepeatedEndpointsAndDifferentDimensions() {
        var source = choice(0);
        var target = choice(96);
        var first = choice(48);
        var second = choice(64);
        var plan = new RailTerminalStopovers.Plan(target, List.of(second, first));
        assertTrue(plan.validFrom(source.terminalPos(), source.station()));
        assertEquals(plan, RailTerminalStopovers.Plan.load(plan.save()));
        assertTrue(new RailTerminalStopovers.Plan(target, List.of()).validFrom(source.terminalPos(), source.station()));
        var copiedSource = new RailTerminalStopovers.Choice(first.terminalPos(), first.terminalId(),
                new RailStationRef(DIMENSION.toString(), first.station().stationPos(), source.station().stationId()));
        var foreign = new RailTerminalStopovers.Choice(second.terminalPos(), second.terminalId(),
                new RailStationRef("minecraft:the_nether", second.station().stationPos(), second.station().stationId()));
        for (var invalid : List.of(List.of(source), List.of(target), List.of(first, first), List.of(copiedSource), List.of(foreign))) {
            assertFalse(new RailTerminalStopovers.Plan(target, invalid).validFrom(source.terminalPos(), source.station()));
        }
    }

    @Test void terminalPlanRetainsDifferentDwellTimesWithTheirOrderedStations() {
        var plan = new RailTerminalStopovers.Plan(choice(96), List.of(choice(64), choice(48))).save();
        var stops = plan.getList("Stops", Tag.TAG_COMPOUND);
        stops.getCompound(0).putInt("DwellSeconds", 17);
        stops.getCompound(1).putInt("DwellSeconds", 45);
        assertEquals(plan, RailTerminalStopovers.Plan.load(plan).save());
    }

    @Test void timedPacketRoundTripsAndOldPacketsStillDefaultToOneSecond() {
        var target = choice(96);
        var stops = List.of(choice(64), choice(48));
        var id = UUID.randomUUID();
        var timed = RailTerminalActionPacket.setTimedStopovers(DIMENSION, BlockPos.ZERO, id, 43, target, stops, List.of(3600, 12));
        var legacy = RailTerminalActionPacket.setStopovers(DIMENSION, BlockPos.ZERO, id, 43, target, stops);
        assertEquals(List.of(1, 1), legacy.stopovers().dwellSeconds());
        for (var expected : List.of(timed, legacy)) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                RailTerminalActionPacket.encode(expected, buffer);
                assertEquals(expected, RailTerminalActionPacket.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
        for (var invalid : List.of(List.of(0, 12), List.of(1, 3601), List.of(1))) {
            assertThrows(IllegalArgumentException.class,
                    () -> RailTerminalActionPacket.setTimedStopovers(DIMENSION, BlockPos.ZERO, id, 43, target, stops, invalid));
        }
    }
    private static RailTerminalStopovers.Choice choice(int x) {
        return new RailTerminalStopovers.Choice(new BlockPos(x, 64, 8), UUID.randomUUID(),
                new RailStationRef(DIMENSION.toString(), new BlockPos(x + 1, 64, 8), UUID.randomUUID()));
    }
    private static RailTerminalActionPacket packet(long revision, BlockPos station) {
        return new RailTerminalActionPacket(DIMENSION, BlockPos.ZERO, UUID.randomUUID(), revision,
                RailTerminalActionPacket.Action.BIND, station, UUID.randomUUID());
    }
}
