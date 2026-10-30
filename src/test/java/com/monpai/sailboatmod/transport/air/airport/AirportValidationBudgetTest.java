package com.monpai.sailboatmod.transport.air.airport;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class AirportValidationBudgetTest {
    @Test void ordinaryAxialRunwayDoesNotSpendItsBudgetScanningEmptyAltitude() {
        ScanResult result = scan(runway(new BlockPos(120, 80, 0), 16, 16));
        assertEquals(AirportValidation.Result.VALID, result.result());
        assertTrue(result.steps() < 170_000, "actual support and clearance should fit within 84 ticks: " + result.steps());
    }

    @Test void ordinaryDiagonalRunwayHasEquivalentCapacityAndWorkToAxialRunway() {
        ScanResult axial = scan(runway(new BlockPos(120, 80, 0), 16, 16));
        ScanResult diagonal = scan(runway(new BlockPos(85, 80, 85), 16, 16));
        assertEquals(AirportValidation.Result.VALID, diagonal.result());
        assertTrue(diagonal.steps() < axial.steps() * 1.25,
                "rotation must not multiply scan cost: " + axial.steps() + " vs " + diagonal.steps());
        assertEquals(AirportValidation.Result.VALID, AirportValidation.validate(
                runway(new BlockPos(85, 80, 85), 16, 16), p -> true, p -> true, p -> true));
    }

    @Test void genuinelyOversizedClearanceStillRejectsBeforeReadingTerrain() {
        var facility = runway(new BlockPos(512, 80, 0), 32, 64);
        assertEquals(AirportValidation.Result.TOO_LARGE, AirportValidation.validate(facility, p -> true,
                p -> { throw new AssertionError("oversized scan must not read terrain"); },
                p -> { throw new AssertionError("oversized scan must not read support"); }));
    }

    @Test void narrowedTraversalStillChecksEveryTouchedBlockAtDifferentAngles() {
        for (BlockPos end : List.of(new BlockPos(40, 80, 0), new BlockPos(0, 80, -40),
                new BlockPos(32, 80, 32), new BlockPos(-35, 80, 20), new BlockPos(17, 80, -40))) {
            var facility = runway(end, 7, 6);
            Set<BlockPos> expectedClear = new HashSet<>(), expectedSupport = new HashSet<>();
            AABB area = facility.clearanceBounds();
            for (int x = (int) Math.floor(area.minX); x < Math.ceil(area.maxX); x++) {
                for (int z = (int) Math.floor(area.minZ); z < Math.ceil(area.maxZ); z++) {
                    int base = facility.clearanceBaseY(x, z);
                    if (base != Integer.MIN_VALUE) for (int y = base; y < base + facility.clearanceHeight(); y++) {
                        expectedClear.add(new BlockPos(x, y, z));
                    }
                    if (facility.requiresSupport(x, z)) expectedSupport.add(new BlockPos(x, 79, z));
                }
            }
            Set<BlockPos> actualClear = new HashSet<>(), actualSupport = new HashSet<>();
            assertEquals(AirportValidation.Result.VALID, AirportValidation.validate(facility, p -> true,
                    p -> { assertTrue(actualClear.add(p), "duplicate clearance cell"); return true; },
                    p -> { assertTrue(actualSupport.add(p), "duplicate support cell"); return true; }));
            assertEquals(expectedClear, actualClear, "clearance for " + end);
            assertEquals(expectedSupport, actualSupport, "support for " + end);
        }
    }

    private static AirportFacility runway(BlockPos end, int width, int height) {
        return new AirportFacility("main", AirportFacility.Type.RUNWAY, new BlockPos(0, 80, 0), end, width, height);
    }

    private static ScanResult scan(AirportFacility facility) {
        AirportValidation.Scan scan = new AirportValidation.Scan(facility);
        AirportValidation.Result result = AirportValidation.Result.WAITING;
        int steps = 0;
        for (int tick = 0; tick < 300 && result == AirportValidation.Result.WAITING; tick++) {
            int[] reads = {0};
            result = scan.advance(2048, p -> { reads[0]++; return true; }, p -> true, p -> true);
            assertTrue(scan.stepsUsed() <= 2048, "tick work budget exceeded");
            assertTrue(reads[0] <= 2048, "tick world-read budget exceeded");
            steps += scan.stepsUsed();
        }
        return new ScanResult(result, steps);
    }
    private record ScanResult(AirportValidation.Result result, int steps) {}
}
