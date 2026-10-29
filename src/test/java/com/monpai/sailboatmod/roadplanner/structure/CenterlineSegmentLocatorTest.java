package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CenterlineSegmentLocatorTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void findsTheSameSegmentAsAFullScanOnWindingSelfCrossingRoads() {
        Random random = new Random(7);
        for (int trial = 0; trial < 40; trial++) {
            List<RoadCenterlinePoint> centerline = randomCenterline(random, 50 + random.nextInt(600));
            CenterlineSegmentLocator locator = new CenterlineSegmentLocator(centerline);
            for (int query = 0; query < 400; query++) {
                RoadCenterlinePoint anchor = centerline.get(random.nextInt(centerline.size()));
                // mostly next to the road, sometimes far away (the full-scan fallback)
                int spread = query % 10 == 0 ? 200 : 12;
                int x = anchor.pos().getX() + random.nextInt(spread * 2 + 1) - spread;
                int z = anchor.pos().getZ() + random.nextInt(spread * 2 + 1) - spread;
                assertEquals(fullScan(x, z, centerline), locator.nearestSegment(x, z),
                        "trial " + trial + " query (" + x + ", " + z + ")");
            }
        }
    }

    @Test
    void handlesRepeatedPointsAndLongSparseSegments() {
        List<RoadCenterlinePoint> centerline = new ArrayList<>();
        centerline.add(point(0, 0));
        centerline.add(point(0, 0));
        centerline.add(point(3000, 2000));
        centerline.add(point(3000, 2000));
        centerline.add(point(3010, 2000));
        CenterlineSegmentLocator locator = new CenterlineSegmentLocator(centerline);
        Random random = new Random(3);
        for (int query = 0; query < 2000; query++) {
            int x = random.nextInt(3200) - 100;
            int z = random.nextInt(2200) - 100;
            assertEquals(fullScan(x, z, centerline), locator.nearestSegment(x, z));
        }
    }

    @Test
    void longRoadsExpandInTimeProportionalToTheirLength() {
        RoadPlannerBuildSettings settings = new RoadPlannerBuildSettings(5, "stone_bricks", true);
        RoadTerrainSampler hills = (x, z) -> 64 + (int) Math.round(8 * Math.sin(x / 40.0) + 6 * Math.cos(z / 30.0));
        expandMillis(1500, settings, hills); // JIT warm-up
        long shortRoad = expandMillis(1500, settings, hills);
        long longRoad = expandMillis(6000, settings, hills);
        // Four times the length must cost about four times as much; the old full scan cost about sixteen times.
        assertTrue(longRoad <= Math.max(250, shortRoad) * 9,
                "1500 blocks took " + shortRoad + " ms, 6000 blocks took " + longRoad + " ms");
    }

    private static long expandMillis(int length, RoadPlannerBuildSettings settings, RoadTerrainSampler sampler) {
        List<BlockPos> nodes = new ArrayList<>();
        for (int x = 0; x <= length; x += 150) {
            nodes.add(new BlockPos(x, 64, (x / 150) % 2 == 0 ? 0 : 40));
        }
        List<RoadPlannerSegmentType> types = new ArrayList<>(Collections.nCopies(nodes.size() - 1, RoadPlannerSegmentType.ROAD));
        long start = System.nanoTime();
        RoadNodeStructureExpander.expand(nodes, types, settings, sampler, RoadStructureMode.PREVIEW);
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static List<RoadCenterlinePoint> randomCenterline(Random random, int length) {
        List<RoadCenterlinePoint> points = new ArrayList<>();
        int x = 0;
        int z = 0;
        int dx = 1;
        int dz = 0;
        for (int i = 0; i < length; i++) {
            points.add(point(x, z));
            if (random.nextInt(12) == 0) {
                int turn = random.nextInt(3) - 1;
                int ndx = turn == 0 ? dx : -dz * turn;
                int ndz = turn == 0 ? dz : dx * turn;
                dx = ndx;
                dz = ndz;
            }
            int stride = random.nextInt(20) == 0 ? 1 + random.nextInt(40) : random.nextInt(2);
            x += dx * stride;
            z += dz * stride;
        }
        return points;
    }

    private static RoadCenterlinePoint point(int x, int z) {
        return new RoadCenterlinePoint(new BlockPos(x, 64, z), 0, RoadPlannerSegmentType.ROAD, 64, 64, 0.0D);
    }

    /** The original nearest-segment search: every segment, first strictly nearer one wins. */
    private static int fullScan(int x, int z, List<RoadCenterlinePoint> centerline) {
        int best = 0;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int i = 0; i < centerline.size() - 1; i++) {
            BlockPos start = centerline.get(i).pos();
            BlockPos end = centerline.get(i + 1).pos();
            double ax = start.getX();
            double az = start.getZ();
            double dx = end.getX() - ax;
            double dz = end.getZ() - az;
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0E-9D ? 0.0D : Math.max(0.0D, Math.min(1.0D, ((x - ax) * dx + (z - az) * dz) / lengthSq));
            double px = ax + t * dx;
            double pz = az + t * dz;
            double distanceSq = (x - px) * (x - px) + (z - pz) * (z - pz);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = i;
            }
        }
        return best;
    }
}
