package com.monpai.sailboatmod.roadplanner.corridor;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadRouteRefinerTest {
    private static final RoadRouteRefiner.Terrain FLAT = terrain((x, z) -> 64, (x, z) -> false);

    private interface Height { int at(int x, int z); }
    private interface Flag { boolean at(int x, int z); }

    private static RoadRouteRefiner.Terrain terrain(Height height, Flag water) {
        return new RoadRouteRefiner.Terrain() {
            @Override public int height(int x, int z) { return height.at(x, z); }
            @Override public boolean water(int x, int z) { return water.at(x, z); }
            @Override public boolean blocked(int x, int z) { return false; }
        };
    }

    /** What an eight-direction search produces crossing open ground at a shallow angle. */
    private static List<BlockPos> staircase(int steps) {
        List<BlockPos> path = new ArrayList<>();
        int x = 0;
        int z = 0;
        path.add(new BlockPos(x, 64, z));
        for (int i = 0; i < steps; i++) {
            x += 8;
            if (i % 3 == 0) {
                z += 8;
            }
            path.add(new BlockPos(x, 64, z));
        }
        return path;
    }

    private static double length(List<BlockPos> path) {
        double total = 0;
        for (int i = 1; i < path.size(); i++) {
            total += Math.hypot(path.get(i).getX() - path.get(i - 1).getX(), path.get(i).getZ() - path.get(i - 1).getZ());
        }
        return total;
    }

    private static double sharpestTurnDegrees(List<BlockPos> path) {
        double sharpest = 0;
        for (int i = 1; i < path.size() - 1; i++) {
            double in = Math.atan2(path.get(i).getZ() - path.get(i - 1).getZ(), path.get(i).getX() - path.get(i - 1).getX());
            double out = Math.atan2(path.get(i + 1).getZ() - path.get(i).getZ(), path.get(i + 1).getX() - path.get(i).getX());
            double turn = Math.abs(Math.toDegrees(Math.atan2(Math.sin(out - in), Math.cos(out - in))));
            sharpest = Math.max(sharpest, turn);
        }
        return sharpest;
    }

    @Test
    void gridDoglegsAcrossOpenGroundBecomeOneStraightLine() {
        List<BlockPos> searched = staircase(60);
        List<BlockPos> refined = RoadRouteRefiner.refine(searched, 24, FLAT);

        assertEquals(searched.get(0), refined.get(0));
        assertEquals(searched.get(searched.size() - 1), refined.get(refined.size() - 1));
        BlockPos end = searched.get(searched.size() - 1);
        assertEquals(Math.hypot(end.getX(), end.getZ()), length(refined), 1.0, "the refined route should be the direct line");
        assertTrue(sharpestTurnDegrees(refined) < 4, "no doglegs may remain: " + sharpestTurnDegrees(refined));
        assertTrue(sharpestTurnDegrees(searched) >= 45);
    }

    @Test
    void nodesAreEvenlySpaced() {
        List<BlockPos> refined = RoadRouteRefiner.refine(staircase(60), 24, FLAT);
        for (int i = 1; i < refined.size(); i++) {
            double gap = Math.hypot(refined.get(i).getX() - refined.get(i - 1).getX(), refined.get(i).getZ() - refined.get(i - 1).getZ());
            assertTrue(gap > 18 && gap < 30, "gap " + gap + " at node " + i);
        }
    }

    @Test
    void aDetourAroundALakeIsKept() {
        // The search went around; the direct line would cross 80 blocks of water.
        RoadRouteRefiner.Terrain lake = terrain((x, z) -> 64, (x, z) -> Math.hypot(x - 200, z) < 40);
        List<BlockPos> searched = new ArrayList<>();
        for (int x = 0; x <= 144; x += 8) searched.add(new BlockPos(x, 64, 0));
        for (int i = 1; i <= 7; i++) searched.add(new BlockPos(144 + i * 8, 64, i * 8));
        for (int x = 208; x <= 248; x += 8) searched.add(new BlockPos(x, 64, 56));
        for (int i = 1; i <= 7; i++) searched.add(new BlockPos(248 + i * 8, 64, 56 - i * 8));
        for (int x = 312; x <= 400; x += 8) searched.add(new BlockPos(x, 64, 0));

        List<BlockPos> refined = RoadRouteRefiner.refine(searched, 12, lake);

        for (int i = 1; i < refined.size(); i++) {
            BlockPos a = refined.get(i - 1);
            BlockPos b = refined.get(i);
            for (int s = 0; s <= 12; s++) {
                int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * s / 12.0);
                int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * s / 12.0);
                assertFalse(lake.water(x, z), "refinement must not put the road in the lake at " + x + "," + z);
            }
        }
        assertTrue(length(refined) <= length(searched) + 1, "refinement only ever shortens a route");
        assertTrue(sharpestTurnDegrees(refined) < 40, "the corners of the detour should be swept into curves");
    }

    @Test
    void aDetourAroundAHillIsKeptBecauseTheDirectLineWouldClimbIt() {
        RoadRouteRefiner.Terrain hill = terrain((x, z) -> 64 + (int) Math.max(0, 30 - Math.hypot(x - 200, z) / 2), (x, z) -> false);
        List<BlockPos> searched = new ArrayList<>();
        for (int x = 0; x <= 120; x += 8) searched.add(new BlockPos(x, 64, 0));
        for (int i = 1; i <= 9; i++) searched.add(new BlockPos(120 + i * 8, 64, i * 8));
        for (int i = 1; i <= 2; i++) searched.add(new BlockPos(192 + i * 8, 64, 72));
        for (int i = 1; i <= 9; i++) searched.add(new BlockPos(208 + i * 8, 64, 72 - i * 8));
        for (int x = 288; x <= 400; x += 8) searched.add(new BlockPos(x, 64, 0));

        List<BlockPos> refined = RoadRouteRefiner.refine(searched, 12, hill);

        int highest = refined.stream().mapToInt(node -> hill.height(node.getX(), node.getZ())).max().orElseThrow();
        assertTrue(highest <= 64 + 6, "the refined road should still pass around the hill, but reaches height " + highest);
    }

    @Test
    void withoutTerrainOnlySmallDeviationsAreStraightened() {
        List<BlockPos> searched = new ArrayList<>();
        for (int x = 0; x <= 200; x += 8) searched.add(new BlockPos(x, 64 + x / 20, (x / 8) % 2 == 0 ? 0 : 6));
        searched.add(new BlockPos(200, 74, 200));

        List<BlockPos> refined = RoadRouteRefiner.refine(searched, 16, null);

        assertEquals(searched.get(0), refined.get(0));
        assertEquals(searched.get(searched.size() - 1), refined.get(refined.size() - 1));
        assertTrue(refined.stream().anyMatch(node -> node.getX() > 170 && node.getZ() < 30),
                "a real turn far from the direct line must survive");
        assertTrue(refined.stream().filter(node -> node.getX() < 150).allMatch(node -> Math.abs(node.getZ() - 3) <= 4),
                "the wobble should be gone");
        assertTrue(refined.get(5).getY() >= 64 && refined.get(5).getY() <= 74, "heights are carried over from the searched path");
    }

    @Test
    void degenerateInputIsReturnedUntouched() {
        assertTrue(RoadRouteRefiner.refine(List.of(), 24, FLAT).isEmpty());
        assertEquals(List.of(new BlockPos(1, 2, 3)), RoadRouteRefiner.refine(List.of(new BlockPos(1, 2, 3), new BlockPos(1, 9, 3)), 24, FLAT));
        List<BlockPos> two = List.of(new BlockPos(0, 64, 0), new BlockPos(5, 64, 0));
        assertEquals(two, RoadRouteRefiner.refine(two, 24, FLAT));
    }
}
