package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.transport.air.RailFlightPlanBuilder;
import com.monpai.sailboatmod.transport.air.rail.RailAirportSelection;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegionalAirportLayoutTest {
    private static final BlockPos DESK = new BlockPos(68, 1, 78);
    private static final BlockPos FIRST = new BlockPos(-44, 1, 0), LAST = new BlockPos(188, 1, 95);

    @Test void placementUsesDeskAndRequiresTheFullApproaches() {
        var ground = AirportGroundLayout.empty();
        assertEquals(BlockPos.ZERO, RegionalAirportLayout.origin(DESK));
        assertTrue(RegionalAirportLayout.propose(LAST, FIRST, DESK, List.of(), ground).isPresent());
        assertTrue(RegionalAirportLayout.propose(FIRST.east(), LAST, DESK, List.of(), ground).isEmpty());
        assertTrue(RegionalAirportLayout.propose(FIRST, LAST.west(), DESK, List.of(), ground).isEmpty());
        assertTrue(RegionalAirportLayout.propose(FIRST, LAST.north(), DESK, List.of(), ground).isEmpty());
        assertTrue(RegionalAirportLayout.propose(FIRST.above(), LAST.above(), DESK, List.of(), ground).isEmpty());
        var built = RegionalAirportLayout.propose(FIRST, LAST, DESK, List.of(), ground).orElseThrow();
        assertTrue(RegionalAirportLayout.propose(FIRST, LAST, DESK, built.facilities(), built.ground()).isEmpty());
    }

    @Test void installedTemplateClearsTheTerrainScansAndCompilesACompactFlight() throws IOException {
        var source = RegionalAirportLayout.propose(FIRST, LAST, DESK, List.of(), AirportGroundLayout.empty()).orElseThrow();
        var destination = RegionalAirportLayout.propose(FIRST.east(1500), LAST.east(1500), DESK.east(1500),
                List.of(), AirportGroundLayout.empty()).orElseThrow();
        var solid = new HashSet<BlockPos>();
        try (var template = getClass().getResourceAsStream("/data/sailboatmod/structure/regional_airport.nbt")) {
            assertNotNull(template);
            var structure = NbtIo.readCompressed(template, NbtAccounter.unlimitedHeap());
            var palette = structure.getList("palette", Tag.TAG_COMPOUND);
            for (var entry : structure.getList("blocks", Tag.TAG_COMPOUND)) {
                var block = (CompoundTag) entry;
                var coordinates = block.getList("pos", Tag.TAG_INT);
                assertNotEquals("minecraft:air", palette.getCompound(block.getInt("state")).getString("Name"));
                solid.add(new BlockPos(coordinates.getInt(0), coordinates.getInt(1), coordinates.getInt(2)));
            }
        }
        assertFalse(solid.contains(DESK));
        solid.add(DESK);
        for (var facility : source.facilities()) {
            var scan = new AirportValidation.Scan(facility);
            assertEquals(AirportValidation.Result.VALID,
                    scan.advance(AirportValidation.MAX_SCAN_STEPS, pos -> true, pos -> !solid.contains(pos), pos -> pos.getY() == 0));
        }
        assertEquals(AirportGroundValidation.Result.VALID, AirportGroundValidation.validate(source.ground(), source.facilities(),
                pos -> true, pos -> !solid.contains(pos), pos -> pos.getY() == 0));
        for (var pad : source.additionalChecks()) {
            assertFalse(pad.bounds().intersects(source.ground().stands().getFirst().bounds()));
            for (var route : source.ground().taxiRoutes()) assertFalse(route.intersectsHull(pad.bounds()));
        }
        var center = source.ground().stands().getFirst().position();
        var aircraft = new RailAirportSelection.Aircraft(new UUID(0, 1), new AABB(center.x - 8, 1, center.z - 7.5,
                center.x + 8, 7, center.z + 7.5), center.add(0, 3, 0), 90);
        var selected = RailAirportSelection.select(aircraft, source.ground(), source.facilities(), null,
                destination.ground(), destination.facilities(), null).selection().orElseThrow();
        var from = selected.source();
        var to = selected.destination();
        var flight = RailFlightPlanBuilder.build(from.runway(), to.runway(), from.outbound(), to.inbound(), to.stand(),
                selected.anchorAboveSurface(), selected.sweepDiameter(), List.of(from.stand().position(), to.stand().position()),
                8, from.stand(), aircraft.anchor(), new Vec3(-1, 0, 0));
        assertEquals(RailAircraftPath.Phase.TAXI_OUT, flight.legs().getFirst().phase());
        assertEquals(RailAircraftPath.Phase.STAND_STABILIZING, flight.legs().getLast().phase());
    }
}
