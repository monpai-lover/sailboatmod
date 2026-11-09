package com.monpai.sailboatmod.transport.air.airport;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class AirportPrefabTest {
    private static final BlockPos FIRST = new BlockPos(0, 81, 0);
    private static final BlockPos LAST = new BlockPos(127, 81, 63);

    @Test void generatesBoundedCanopyWithOpenTaxiEndsInBothOrientations() {
        verifyCanopy(LAST, true);
        verifyCanopy(new BlockPos(63, 81, 127), false);
        verifyCanopy(new BlockPos(363, 81, 103), true);
    }

    @Test void cannotClipCanopyAtSelectionBoundary() {
        var proposal = AirportAutoLayout.propose(FIRST, LAST, List.of(), AirportGroundLayout.empty()).orElseThrow();
        assertTrue(AirportPrefab.plan(proposal, FIRST, new BlockPos(63, 81, 32)).isEmpty());
    }

    @Test void rollsBackAllAttemptedWritesWhenConfigurationCannotCommit() {
        Set<BlockPos> placed = new HashSet<>();
        List<BlockPos> order = List.of(FIRST, FIRST.offset(0, 1, 0), FIRST.offset(0, 2, 0));
        assertFalse(AirportPrefab.placeAtomically(order, placed::add, placed::remove, () -> false));
        assertTrue(placed.isEmpty());
    }

    @Test void rollsBackAWriteThatThrowsAfterChangingTheBlock() {
        Set<BlockPos> placed = new HashSet<>();
        List<BlockPos> order = List.of(FIRST, FIRST.offset(0, 1, 0), FIRST.offset(0, 2, 0));
        Predicate<BlockPos> place = pos -> {
            placed.add(pos);
            if (pos.equals(order.get(1))) throw new IllegalStateException("simulated placement failure");
            return true;
        };
        assertFalse(AirportPrefab.placeAtomically(order, place, placed::remove, () -> fail("must not commit")));
        assertTrue(placed.isEmpty());
    }

    @Test void placesPaintAndPairedLightsFlushOnBothRunwayOrientations() {
        verifyRunwayDetails(LAST, true);
        verifyRunwayDetails(new BlockPos(63, 81, 127), false);
        verifyRunwayDetails(new BlockPos(363, 81, 103), true);
    }

    private static void verifyRunwayDetails(BlockPos last, boolean alongX) {
        var proposal = AirportAutoLayout.propose(FIRST, last, List.of(), AirportGroundLayout.empty()).orElseThrow();
        var runway = proposal.changedRunway();
        var pieces = AirportPrefab.planDetailed(proposal, FIRST, last).orElseThrow();
        var byPosition = new java.util.HashMap<BlockPos, AirportPrefab.Part>();
        for (var piece : pieces) assertNull(byPosition.put(piece.pos(), piece.part()));
        int edge = runway.width() / 2 - 1;
        for (BlockPos endpoint : List.of(runway.start(), runway.end())) {
            for (int side : new int[]{-edge, edge}) {
                var pos = alongX ? endpoint.below().offset(0, 0, side) : endpoint.below().offset(side, 0, 0);
                assertEquals(AirportPrefab.Part.RUNWAY_LIGHT, byPosition.get(pos));
            }
        }
        for (int distance : new int[]{2, (int) runway.length() - 2}) {
            for (int side : new int[]{-edge + 1, 0, edge - 1}) {
                var stripe = alongX ? runway.start().below().offset(distance, 0, side)
                        : runway.start().below().offset(side, 0, distance);
                assertEquals(AirportPrefab.Part.MARKING, byPosition.get(stripe));
            }
        }
        var dashed = alongX ? runway.start().below().east(7) : runway.start().below().south(7);
        var gap = alongX ? runway.start().below().east(13) : runway.start().below().south(13);
        assertEquals(AirportPrefab.Part.MARKING, byPosition.get(dashed));
        assertFalse(byPosition.containsKey(gap));
        assertEquals(4, pieces.stream().filter(p -> p.part() == AirportPrefab.Part.ROOF_LIGHT).count());
        assertTrue(pieces.stream().filter(p -> p.part() == AirportPrefab.Part.MARKING
                || p.part() == AirportPrefab.Part.RUNWAY_LIGHT).allMatch(p -> p.pos().getY() == runway.start().getY() - 1));
        var cost = AirportPrefab.materials(pieces);
        assertEquals(pieces.size(), cost.stone() + cost.markings() + cost.lights());
        assertTrue(pieces.size() <= AirportPrefab.MAX_BLOCKS);
    }

    private static void verifyCanopy(BlockPos last, boolean alongX) {
        var proposal = AirportAutoLayout.propose(FIRST, last, List.of(), AirportGroundLayout.empty()).orElseThrow();
        var stand = proposal.ground().stands().getFirst();
        var canopy = AirportPrefab.plan(proposal, FIRST, last).orElseThrow();
        var unique = new HashSet<>(canopy);
        assertEquals(canopy.size(), unique.size());
        assertTrue(canopy.size() <= AirportPrefab.MAX_BLOCKS);
        assertEquals(4, canopy.stream().filter(p -> p.getY() == FIRST.getY() && unique.contains(p.offset(0, 1, 0))).count());
        assertTrue(canopy.stream().allMatch(p -> p.getX() >= FIRST.getX() && p.getX() <= last.getX()
                && p.getZ() >= FIRST.getZ() && p.getZ() <= last.getZ()));
        int roofY = (int) stand.bounds().maxY;
        assertTrue(canopy.contains(BlockPos.containing(stand.position().x, roofY, stand.position().z)));
        if (stand.width() == 48) {
            BlockPos center = BlockPos.containing(stand.position().x, roofY, stand.position().z);
            for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) {
                assertTrue(unique.contains(center.offset(x, 0, z)));
            }
        }
        assertTrue(canopy.stream().filter(p -> p.getY() < roofY).noneMatch(p -> stand.containsHorizontal(
                p.getX() + .5, p.getZ() + .5)));
        assertTrue(canopy.stream().filter(p -> p.getY() < roofY && unique.contains(p.offset(0, 1, 0))).noneMatch(p -> alongX
                ? p.getZ() == BlockPos.containing(stand.position()).getZ()
                : p.getX() == BlockPos.containing(stand.position()).getX()));
        var pad = proposal.additionalChecks().getFirst();
        assertTrue(canopy.stream().anyMatch(p -> p.getY() == pad.start().getY() && !unique.contains(p.offset(0, 1, 0))
                && p.distSqr(pad.start()) >= 49 && p.distSqr(pad.start()) <= 98));
    }
}
