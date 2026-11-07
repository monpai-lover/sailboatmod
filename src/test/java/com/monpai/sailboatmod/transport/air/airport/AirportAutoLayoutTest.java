package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.network.packet.airport.AirportActionPacket;
import com.monpai.sailboatmod.transport.air.RailFlightPlanBuilder;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AirportAutoLayoutTest {
    private static final BlockPos FIRST = new BlockPos(0, 81, 0);
    private static final BlockPos LAST = new BlockPos(127, 81, 63);

    @Test void previewHasSupportedGeometryAndCompilesBothAirportTaxiProcedures() {
        var source = AirportAutoLayout.propose(FIRST, LAST, List.of(), AirportGroundLayout.empty()).orElseThrow();
        var target = AirportAutoLayout.propose(FIRST.offset(1500, 0, 0), LAST.offset(1500, 0, 0),
                List.of(), AirportGroundLayout.empty()).orElseThrow();
        var runway = source.changedRunway();
        var stand = source.ground().stands().getFirst();
        var outbound = source.ground().route(AirportTaxiRoute.Direction.OUTBOUND, stand.id(), runway.id()).orElseThrow();
        var inbound = target.ground().route(AirportTaxiRoute.Direction.INBOUND, target.ground().stands().getFirst().id(),
                target.changedRunway().id()).orElseThrow();

        assertEquals(source, AirportAutoLayout.propose(LAST, FIRST, List.of(), AirportGroundLayout.empty()).orElseThrow());
        assertTrue(runway.end().getX() > runway.start().getX());
        assertEquals(runway.start().getZ(), runway.end().getZ());
        assertEquals(81, stand.position().y);
        assertTrue(stand.cargoEnabled());
        assertTrue(runway.clearanceBounds().minX >= 0 && runway.clearanceBounds().maxX <= 128);
        assertEquals(AirportGroundValidation.Result.VALID,
                AirportGroundValidation.references(source.ground(), source.facilities()));
        assertTrue(AirportGroundValidation.linkedEndpoint(outbound, source.facilities(), source.ground().stands()));
        assertTrue(AirportGroundValidation.linkedEndpoint(inbound, target.facilities(), target.ground().stands()));
        assertDoesNotThrow(() -> RailFlightPlanBuilder.build(runway, target.changedRunway(), outbound, inbound,
                target.ground().stands().getFirst(), 2, 16, List.of(new Vec3(0, 81, 0), new Vec3(1500, 81, 0)), 8));
    }

    @Test void appendsDisjointNorthSouthLayoutAndRefusesOverlapOrUnevenRegion() {
        var original = AirportAutoLayout.propose(FIRST, LAST, List.of(), AirportGroundLayout.empty()).orElseThrow();
        var added = AirportAutoLayout.propose(new BlockPos(-100, 81, -128), new BlockPos(-37, 81, -1),
                original.facilities(), original.ground()).orElseThrow();

        assertEquals(original.facilities().getFirst(), added.facilities().getFirst());
        assertEquals(original.ground().stands().getFirst(), added.ground().stands().getFirst());
        assertEquals(original.ground().taxiRoutes(), added.ground().taxiRoutes().subList(0, 2));
        assertEquals(original.ground().revision() + 1, added.ground().revision());
        assertNotEquals(original.changedRunway().id(), added.changedRunway().id());
        assertEquals(added.changedRunway().start().getX(), added.changedRunway().end().getX());
        assertTrue(added.changedRunway().end().getZ() > added.changedRunway().start().getZ());
        assertTrue(AirportAutoLayout.propose(FIRST, LAST, original.facilities(), original.ground()).isEmpty());
        assertTrue(AirportAutoLayout.propose(FIRST, LAST.above(), List.of(), AirportGroundLayout.empty()).isEmpty());
        assertTrue(AirportAutoLayout.propose(FIRST, LAST.west(), List.of(), AirportGroundLayout.empty()).isEmpty());
    }

    @Test void regionActionCarriesWholeBlockCornersAndNoClientGeometry() {
        var packet = regionAction(List.of(Vec3.atLowerCornerOf(FIRST), Vec3.atLowerCornerOf(LAST)));
        var wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AirportActionPacket.encode(packet, wire);
            var read = AirportActionPacket.decode(wire);
            assertEquals(packet, read);
            var action = read.toActionTag();
            assertEquals("auto_layout", action.getString("Action"));
            assertEquals(FIRST, BlockPos.of(action.getLong("CornerA")));
            assertEquals(LAST, BlockPos.of(action.getLong("CornerB")));
            assertFalse(action.contains("Facility"));
            assertFalse(action.contains("TaxiRoute"));
        } finally {
            wire.release();
        }
        assertThrows(IllegalArgumentException.class, () -> regionAction(List.of(Vec3.atCenterOf(FIRST),
                Vec3.atLowerCornerOf(LAST))).toActionTag());
        assertThrows(IllegalArgumentException.class, () -> regionAction(List.of(Vec3.atLowerCornerOf(FIRST))).toActionTag());
    }

    @Test void generatedStandStillNeedsLoadedSupportingTerrainBeforeCommit() {
        var draft = AirportAutoLayout.propose(FIRST, LAST, List.of(), AirportGroundLayout.empty()).orElseThrow();
        var request = new AirportEditorSession(new UUID(0, 1), 0, 0, draft, "Town");
        BlockPos unsupported = BlockPos.containing(draft.ground().stands().getFirst().position()).below();
        for (int ticks = 0; ticks < 1024 && request.result().equals("pending"); ticks++) {
            request.advance(AirportEditorSession.PER_TICK_BUDGET, pos -> "Town", pos -> true, pos -> true,
                    pos -> !pos.equals(unsupported));
        }
        assertEquals("unsupported", request.result());
        assertFalse(request.canCommit(request, request.owner, 0, true, 1));
    }

    private static AirportActionPacket regionAction(List<Vec3> corners) {
        return new AirportActionPacket(ResourceLocation.parse("minecraft:overworld"), BlockPos.ZERO, 2,
                AirportActionPacket.Action.APPLY_AUTO_LAYOUT, "", "", "", corners, false, 0, 0, 0, 0, 0, -1);
    }
}
