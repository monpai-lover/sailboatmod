package com.monpai.sailboatmod.route.water.lookahead;

import com.monpai.sailboatmod.route.water.RealBlockWaterMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RouteLookaheadTest {
    @Test
    void scanStopsAtUnloadedChunksWithoutObstacle() {
        RealBlockWaterMap map = LookaheadMaps.wall(-1);
        List<Vec3> route = List.of(v(0, 20), v(64, 20), v(400, 20));

        RouteLookahead.Scan scan = RouteLookahead.scan(route, 1, v(10, 20), 0, 1000, map, LookaheadMaps.LOADED::contains, 1);

        assertTrue(scan.obstacle().isEmpty());
        assertEquals(1, scan.checkedUpTo());
    }

    @Test
    void scanFindsWallBetweenWaypoints() {
        RealBlockWaterMap map = LookaheadMaps.wall(40);
        List<Vec3> route = List.of(v(0, 20), v(64, 20), v(128, 20), v(192, 20));

        RouteLookahead.Scan scan = RouteLookahead.scan(route, 1, v(50, 20), 0, 1000, map, LookaheadMaps.LOADED::contains, 1);

        RouteLookahead.Obstacle obstacle = scan.obstacle().orElseThrow();
        assertEquals(1, obstacle.fromSegment());
        assertEquals(1, obstacle.toSegment());
        assertEquals(cell(98, 20), flat(obstacle.before()));
        assertEquals(cell(101, 20), flat(obstacle.after()));
        assertEquals(1, scan.checkedUpTo());
    }

    @Test
    void scanFindsObstacleOnTheCurrentLongSegment() {
        RealBlockWaterMap map = LookaheadMaps.wall(40);
        List<Vec3> route = List.of(v(0, 20), v(250, 20));

        RouteLookahead.Obstacle obstacle = RouteLookahead.scan(route, 1, v(60, 20), 0, 1000, map,
                LookaheadMaps.LOADED::contains, 1).obstacle().orElseThrow();

        assertEquals(0, obstacle.fromSegment());
        assertEquals(0, obstacle.toSegment());
        assertEquals(cell(98, 20), flat(obstacle.before()));
        assertEquals(cell(101, 20), flat(obstacle.after()));
    }

    @Test
    void obstacleCanSpanAWaypointOnTheWall() {
        RealBlockWaterMap map = LookaheadMaps.wall(40);
        List<Vec3> route = List.of(v(0, 20), v(64, 20), v(100, 20), v(128, 20), v(192, 20));

        RouteLookahead.Obstacle obstacle = RouteLookahead.scan(route, 1, v(50, 20), 0, 1000, map,
                LookaheadMaps.LOADED::contains, 1).obstacle().orElseThrow();

        assertEquals(1, obstacle.fromSegment());
        assertEquals(2, obstacle.toSegment());
        assertEquals(cell(98, 20), flat(obstacle.before()));
        assertEquals(cell(101, 20), flat(obstacle.after()));
    }

    @Test
    void scanResumesAfterCheckedSegments() {
        RealBlockWaterMap map = LookaheadMaps.wall(-1);
        List<Vec3> route = List.of(v(0, 20), v(64, 20), v(128, 20), v(192, 20));

        RouteLookahead.Scan scan = RouteLookahead.scan(route, 1, v(10, 20), 0, 1000, map, LookaheadMaps.LOADED::contains, 1);

        assertTrue(scan.obstacle().isEmpty());
        assertEquals(3, scan.checkedUpTo());
    }

    @Test
    void scanStopsAtTheLookaheadDistance() {
        RealBlockWaterMap map = LookaheadMaps.wall(40);
        List<Vec3> route = List.of(v(0, 20), v(250, 20));

        RouteLookahead.Scan scan = RouteLookahead.scan(route, 1, v(50, 20), 0, 30, map, LookaheadMaps.LOADED::contains, 1);

        assertTrue(scan.obstacle().isEmpty(), "the wall 50 blocks ahead is beyond a 30-block look-ahead");
    }

    @Test
    void millionBlockSegmentDoesNotAllocateTheUnvisitedSteps() {
        java.lang.management.ThreadMXBean management = ManagementFactory.getThreadMXBean();
        assumeTrue(management instanceof com.sun.management.ThreadMXBean,
                "thread allocation measurements require the HotSpot management bean");
        com.sun.management.ThreadMXBean allocations = (com.sun.management.ThreadMXBean) management;
        assumeTrue(allocations.isThreadAllocatedMemorySupported());
        if (!allocations.isThreadAllocatedMemoryEnabled()) {
            allocations.setThreadAllocatedMemoryEnabled(true);
        }
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63);
        List<Vec3> route = List.of(v(0, 20), v(1_000_000, 20));
        // Warm the same API before measuring. No chunk is loaded, so a bounded scan
        // must stop at its first cell regardless of the full segment's length.
        RouteLookahead.scan(List.of(v(0, 20), v(1, 20)), 1, v(0, 20), 0, 160, map, key -> false, 1);
        long thread = Thread.currentThread().threadId();
        long before = allocations.getThreadAllocatedBytes(thread);

        RouteLookahead.Scan scan = RouteLookahead.scan(route, 1, v(0, 20), 0, 160, map, key -> false, 1);

        long allocated = allocations.getThreadAllocatedBytes(thread) - before;
        assertTrue(scan.obstacle().isEmpty());
        assertEquals(0, scan.checkedUpTo());
        assertTrue(allocated < 1_000_000L,
                "a stopped 160-block look-ahead must not allocate the million unvisited steps; allocated=" + allocated);
    }

    @Test
    void corridorIsCappedAt40() {
        List<Vec3> route = List.of(v(8, 8), v(2000, 8));

        Set<Long> chunks = RouteLookahead.corridorChunks(route, 1, v(8, 8), 2000, 1, 40);

        assertEquals(40, chunks.size());
        assertTrue(chunks.contains(ChunkPos.asLong(0, 0)));
        assertTrue(chunks.contains(ChunkPos.asLong(1, 0)));
    }

    @Test
    void corridorStopsAtLookaheadDistance() {
        List<Vec3> route = List.of(v(8, 8), v(2000, 8));

        Set<Long> chunks = RouteLookahead.corridorChunks(route, 1, v(8, 8), 160, 1, 40);

        assertTrue(chunks.contains(ChunkPos.asLong(10, 0)));
        assertTrue(!chunks.contains(ChunkPos.asLong(13, 0)), "nothing past the 160-block look-ahead: " + chunks.size());
    }

    static Vec3 v(int x, int z) {
        return new Vec3(x + 0.5D, 63, z + 0.5D);
    }

    private static BlockPos cell(int x, int z) {
        return new BlockPos(x, 0, z);
    }

    private static BlockPos flat(BlockPos p) {
        return new BlockPos(p.getX(), 0, p.getZ());
    }
}
