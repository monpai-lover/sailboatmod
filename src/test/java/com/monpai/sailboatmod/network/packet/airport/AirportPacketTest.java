package com.monpai.sailboatmod.network.packet.airport;

import io.netty.buffer.Unpooled;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AirportPacketTest {
    @Test void actionRoundTripRetainsDirectedPathOrderAndRevision() {
        var packet = new AirportActionPacket(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO, 17, AirportActionPacket.Action.UPSERT_TAXI_ROUTE,
                "taxi-out", "runway", "stand", List.of(new Vec3(1,64,2), new Vec3(10,64,2), new Vec3(20,64,2)),
                false, 90, 20, 8, 12, 2, 1);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AirportActionPacket.encode(packet, buffer);
            assertEquals(packet, AirportActionPacket.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            assertEquals("OUTBOUND", packet.toActionTag().getCompound("TaxiRoute").getString("Direction"));
        } finally { buffer.release(); }
    }
    @Test void rejectsOversizedPointCountBeforeAllocation() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUtf("minecraft:overworld",256).writeBlockPos(BlockPos.ZERO).writeLong(0);
            buffer.writeEnum(AirportActionPacket.Action.UPSERT_TAXI_ROUTE);
            buffer.writeUtf("a",64).writeUtf("b",64).writeUtf("c",64).writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> AirportActionPacket.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void rejectsOversizedTextAndNonFiniteGeometryRatherThanTruncatingIds() {
        assertThrows(IllegalArgumentException.class, () -> packet("a".repeat(65), List.of(Vec3.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> packet("stand", List.of(new Vec3(Double.NaN,0,0))));
        assertThrows(IllegalArgumentException.class, () -> packet("route", Collections.nCopies(65, Vec3.ZERO)));
    }
    @Test void snapshotRoundTripPreservesGeometryAndResourceStatuses() {
        var layout = new AirportGroundLayout(17,List.of(new AirportParkingStand("stand",new Vec3(0,64,0),90,24,24,16,true)),
                List.of(new AirportTaxiRoute("out",AirportTaxiRoute.Direction.OUTBOUND,"runway","stand",
                        List.of(new Vec3(0,64,0),new Vec3(20,64,0),new Vec3(40,64,0)),9,16,2,1)));
        var snapshot = new AirportScreenData(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO,"Airport","Town","Owner",true,17,
                List.of(new AirportFacility("runway",AirportFacility.Type.RUNWAY,new BlockPos(40,64,0),new BlockPos(100,64,0),9,16)),
                layout,List.of("stand|stand|occupied"),"stale_revision");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AirportSnapshotPacket.encode(new AirportSnapshotPacket(snapshot,false),buffer);
            assertEquals(new AirportSnapshotPacket(snapshot,false),AirportSnapshotPacket.decode(buffer));
            assertEquals(0,buffer.readableBytes());
        } finally {buffer.release();}
    }
    @Test void snapshotRejectsConflictingVersions() {
        assertThrows(IllegalArgumentException.class, () -> new AirportScreenData(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO,"Airport","Town","Owner",true,5,
                List.of(),AirportGroundLayout.empty(),List.of(),""));
    }
    private static AirportActionPacket packet(String id, List<Vec3> points) {
        return new AirportActionPacket(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO,0,AirportActionPacket.Action.UPSERT_STAND,id,"","",points,
                true,0,16,16,12,2,-1);
    }
}
