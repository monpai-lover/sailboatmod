package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.Phase;
import com.monpai.sailboatmod.transport.control.SweptTerrainProbe;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AirportGroundContactTest {
    private final AirportFacility runway = new AirportFacility("main", AirportFacility.Type.RUNWAY,
            new BlockPos(135450, 132, -28698), new BlockPos(135495, 132, -28698), 40, 16);
    private final Vec3 parking = new Vec3(135548.5, 132, -28707.5);
    private final AirportParkingStand stand = new AirportParkingStand("a", parking, 90, 48, 48, 16, true);
    private final AirportTaxiRoute out = new AirportTaxiRoute("out", AirportTaxiRoute.Direction.OUTBOUND, "main", "a",
            List.of(parking, Vec3.atBottomCenterOf(runway.start())), 40, 16, 3, 0);
    private final AirportTaxiRoute in = new AirportTaxiRoute("in", AirportTaxiRoute.Direction.INBOUND, "main", "a",
            List.of(Vec3.atBottomCenterOf(runway.end()), parking), 40, 16, 3, -1);
    private final AirportGroundLayout layout = new AirportGroundLayout(3, List.of(stand), List.of(out, in));
    private final GroundAssignment assignment = new GroundAssignment(3, "main", "a", "out", "in");

    @Test void tellusPitchedStartCanSweepOverItsOwnDeckIncludingTheSolidSubstrate() {
        var contact = AirportGroundContact.forStep(layout, List.of(runway), assignment, Phase.TAXI_OUT, Phase.TAXI_OUT).orElseThrow();
        var swept = new AABB(135540.69, 130.9389, -28722.0001, 135556.64, 138.684, -28692.9992);
        var probe = new SweptTerrainProbe();
        assertEquals(AirportGroundValidation.Result.VALID, AirportGroundValidation.references(layout, List.of(runway)));
        assertEquals(SweptTerrainProbe.Result.BLOCKED, probe.scan(swept, 16384,
                pos -> true, pos -> pos.getY() < 132 && new AABB(pos).intersects(swept)));
        assertEquals(SweptTerrainProbe.Result.CLEAR, probe.scan(contact.scanBounds(swept), 16384,
                pos -> true, pos -> pos.getY() < 132 && new AABB(pos).intersects(swept)
                        && !contact.permitsBelowDeck(pos, new AABB(pos))));
    }

    @Test void ordinaryObstaclesAndObstaclesProjectingAboveADeckCellStillBlock() {
        var contact = AirportGroundContact.forStep(layout, List.of(runway), assignment, Phase.TAXI_OUT, Phase.TAXI_OUT).orElseThrow();
        BlockPos deck = new BlockPos(135548, 131, -28708);
        assertTrue(contact.requiredSupport(deck));
        assertTrue(contact.permitsBelowDeck(deck, new AABB(deck)));
        assertFalse(contact.permitsBelowDeck(deck, new AABB(deck).expandTowards(0, .5, 0)));
        assertFalse(contact.permitsBelowDeck(deck.above(), new AABB(deck.above())));
        BlockPos unrelatedDeck = deck.offset(200, 0, 0);
        assertFalse(contact.requiredSupport(unrelatedDeck));
        assertFalse(contact.permitsBelowDeck(unrelatedDeck, new AABB(unrelatedDeck)));
        var aboveFloor = new AABB(135547.1, 132.2, -28708, 135548.9, 134, -28707);
        assertEquals(131, contact.scanBounds(aboveFloor).minY);
    }

    @Test void permitsOnlyPinnedRunwayContactAtLiftoffAndTouchdown() {
        BlockPos deck = runway.start().below();
        BlockPos standDeck = BlockPos.containing(parking).below();
        var liftoff = AirportGroundContact.forStep(layout, List.of(runway), assignment, Phase.TAKEOFF, Phase.CLIMB).orElseThrow();
        assertTrue(liftoff.permitsBelowDeck(deck, new AABB(deck)));
        assertFalse(liftoff.permitsBelowDeck(standDeck, new AABB(standDeck)));
        assertTrue(AirportGroundContact.forStep(layout, List.of(runway), assignment, Phase.CLIMB, Phase.CLIMB).isEmpty());
        assertTrue(AirportGroundContact.forStep(layout, List.of(runway), assignment, Phase.APPROACH, Phase.APPROACH).isEmpty());
        var touchdown = AirportGroundContact.forStep(layout, List.of(runway), assignment, Phase.APPROACH, Phase.LANDING_ROLL).orElseThrow();
        assertTrue(touchdown.permitsBelowDeck(deck, new AABB(deck)));
        assertFalse(touchdown.permitsBelowDeck(standDeck, new AABB(standDeck)));
        assertTrue(AirportGroundContact.forStep(layout.withRevision(4), List.of(runway), assignment, Phase.TAXI_OUT, Phase.TAXI_OUT).isEmpty());
    }
}
