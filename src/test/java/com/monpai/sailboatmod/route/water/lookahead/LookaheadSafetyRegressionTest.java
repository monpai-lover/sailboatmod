package com.monpai.sailboatmod.route.water.lookahead;

import com.monpai.sailboatmod.route.repair.RoutePatch;
import com.monpai.sailboatmod.route.water.NbtChunkWaterReader;
import com.monpai.sailboatmod.route.water.RealBlockWaterMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class LookaheadSafetyRegressionTest {
    private static final BlockPos LOGGED_START = new BlockPos(37282, 65, -55395);
    private static final BlockPos LOGGED_GRID_GOAL = new BlockPos(37288, 65, -55400);
    private static final Set<BlockPos> PHANTOM_LAND = Set.of(new BlockPos(37282, 65, -55396));

    @Test
    void loggedShallowDiagonalAgreesWithStrictHullTraversal() {
        RealBlockWaterMap map = map(LOGGED_START, PHANTOM_LAND);
        List<Vec3> route = List.of(v(LOGGED_START), v(LOGGED_GRID_GOAL));
        assertTrue(map.segmentHullClear(LOGGED_START, LOGGED_GRID_GOAL, 1),
                "the dry cell lies outside the exact 2x2 swept corridor");

        RouteLookahead.Scan scan = RouteLookahead.scan(route, 1, route.getFirst(), 0, 160,
                map, key -> true, 1);

        assertTrue(scan.obstacle().isEmpty(), "a strict-clear edge must not produce a phantom obstacle");
        assertEquals(1, scan.checkedUpTo());
    }

    @Test
    void repeatedTicksDoNotGrowRouteOrPersistPhantomRepairs() {
        FakeHost host = new FakeHost(List.of(v(LOGGED_START), v(LOGGED_GRID_GOAL)));
        RouteLookaheadGuard guard = guard(host, LOGGED_START, PHANTOM_LAND);
        List<Vec3> original = List.copyOf(host.route);

        for (long tick = 10; tick <= 60; tick += 10) guard.tick(tick);

        assertEquals(original, host.route, "rechecking the same safe leg must not add two points each time");
        assertEquals(0, host.splices);
        assertEquals(0, host.patches);
        assertEquals(0, host.escalations);
        assertFalse(host.slotHeld);
    }

    @Test
    void invalidMergedConnectorIsNeverAppliedOrPersisted() {
        BlockPos a = new BlockPos(16, 63, 16);
        BlockPos b = new BlockPos(23, 63, 17);
        Set<BlockPos> land = Set.of(new BlockPos(22, 63, 18), new BlockPos(18, 63, 18));
        RealBlockWaterMap map = map(a, land);
        List<Vec3> route = List.of(v(a), v(b));
        RouteLookahead.Obstacle obstacle = RouteLookahead.scan(route, 1, route.getFirst(), 0, 160,
                map, key -> true, 1).obstacle().orElseThrow();
        assertEquals(new BlockPos(20, 63, 17), obstacle.before());
        assertFalse(map.segmentHullClear(a, obstacle.before(), 1),
                "rounding the interior anchor changes the retained connector geometry");
        assertFalse(LookaheadReroute.compute(map, obstacle.before(), obstacle.after(), 1).isEmpty(),
                "the local detour itself succeeds, so validating only it is insufficient");
        FakeHost host = new FakeHost(route);

        guard(host, a, land).tick(10);

        assertEquals(route, host.route);
        assertEquals(0, host.splices, "an unsafe connector must be rejected before modifying the live route");
        assertEquals(0, host.patches, "an unsafe connector must never be saved to the dock route");
        assertEquals(1, host.escalations);
        assertFalse(host.slotHeld);
    }

    @Test
    void localRepairDoesNotRescanAnUnchangedHundredThousandBlockSuffix() {
        BlockPos a = new BlockPos(0, 63, 20);
        FakeHost host = new FakeHost(List.of(v(a), v(new BlockPos(100000, 63, 20))),
                v(new BlockPos(50, 63, 20)));
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63);
        for (long key : LookaheadMaps.LOADED) map.seedChunk(ChunkPos.getX(key), ChunkPos.getZ(key),
                LookaheadMaps.columns(ChunkPos.getX(key), ChunkPos.getZ(key), 40));
        RouteLookaheadGuard guard = new RouteLookaheadGuard(host,
                key -> LookaheadMaps.LOADED.contains(key)
                        ? LookaheadMaps.columns(ChunkPos.getX(key), ChunkPos.getZ(key), 40) : null,
                () -> map, Runnable::run, Runnable::run);

        guard.tick(10);

        assertEquals(1, host.splices, "a local repair must accept the unchanged collinear remote suffix");
        assertEquals(1, host.patches);
        assertEquals(0, host.escalations);
        assertEquals(v(new BlockPos(100000, 63, 20)), host.route.getLast());
    }

    @Test
    void unknownConnectorKeepsNearObstacleHoldAndWaitsBeforeRetrying() {
        BlockPos a = new BlockPos(16, 63, 16);
        BlockPos b = new BlockPos(136, 63, 17);
        BlockPos boat = new BlockPos(70, 63, 16);
        Set<BlockPos> land = Set.of(new BlockPos(117, 63, 18));
        FakeHost host = new FakeHost(List.of(v(a), v(b)), v(boat));
        Deque<Runnable> background = new ArrayDeque<>();
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63);
        // The old route prefix is outside the current loaded corridor. Local detour columns
        // remain known; final connector validation reaches the genuinely unknown prefix.
        for (int cx = 3; cx <= 12; cx++) for (int cz = -4; cz <= 4; cz++)
            map.seedChunk(cx, cz, columns(cx, cz, 63, land));
        RouteLookaheadGuard guard = new RouteLookaheadGuard(host,
                key -> columns(ChunkPos.getX(key), ChunkPos.getZ(key), 63, land), () -> map,
                background::add, Runnable::run);
        guard.tick(10);
        assertTrue(guard.shouldHold());
        background.removeFirst().run();

        assertTrue(guard.shouldHold(), "a known nearby obstruction remains protected while its connector is unknown");
        assertFalse(host.slotHeld, "waiting for terrain must release worker exclusion");
        assertEquals(0, host.splices);
        assertEquals(0, host.patches);
        assertEquals(0, host.escalations);
        for (long tick = 20; tick < 110; tick += 10) guard.tick(tick);
        assertTrue(background.isEmpty(), "unknown terrain must not queue another worker every ten ticks");
        guard.tick(110);
        assertEquals(1, background.size(), "terrain is retried after a bounded delay");
        assertTrue(guard.shouldHold());
        guard.reset();
        assertFalse(guard.shouldHold());
    }

    @Test
    void deterministicConnectorBudgetEscalatesInsteadOfRetryingForever() {
        FakeHost host = new FakeHost(List.of(v(new BlockPos(0, 63, 20)), v(new BlockPos(100000, 63, 21))),
                v(new BlockPos(50, 63, 20)));
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63);
        // More known connector cells than the 4096-cell budget, followed by unknown world.
        for (int cx = 0; cx <= 512; cx++) for (int cz = 0; cz < 4; cz++)
            map.seedChunk(cx, cz, LookaheadMaps.columns(cx, cz, 40));
        RouteLookaheadGuard guard = new RouteLookaheadGuard(host,
                key -> LookaheadMaps.columns(ChunkPos.getX(key), ChunkPos.getZ(key), 40),
                () -> map, Runnable::run, Runnable::run);

        guard.tick(10);

        assertEquals(0, host.splices);
        assertEquals(0, host.patches);
        assertEquals(1, host.escalations, "a deterministic over-budget new connector needs a bounded local rescue");
        assertFalse(host.slotHeld);
    }

    @Test
    void verifiedBentOriginalLegsClearHoldWhenTheirObstacleIsRemoved() {
        BlockPos a = new BlockPos(16, 63, 16);
        BlockPos bend = new BlockPos(116, 63, 17);
        BlockPos end = new BlockPos(116, 63, 25);
        Vec3 boat = v(new BlockPos(70, 63, 16));
        Set<BlockPos> land = new HashSet<>();
        for (int z = 17; z <= 21; z++) land.add(new BlockPos(116, 63, z));
        land.add(new BlockPos(115, 63, 20)); // Outside both original legs, inside the before->after chord.
        Supplier<RealBlockWaterMap> maps = () -> {
            RealBlockWaterMap map = new RealBlockWaterMap(null, 63);
            for (int cx = 3; cx <= 12; cx++) for (int cz = -4; cz <= 4; cz++)
                map.seedChunk(cx, cz, columns(cx, cz, 63, land));
            return map;
        };
        FakeHost host = new FakeHost(List.of(v(a), v(bend), v(end)), boat);
        Deque<Runnable> background = new ArrayDeque<>();
        RouteLookahead.Obstacle original = RouteLookahead.scan(host.route, 1, boat, 0, 160,
                maps.get(), key -> true, 1).obstacle().orElseThrow();
        assertEquals(0, original.fromSegment());
        assertEquals(1, original.toSegment());
        RouteLookaheadGuard guard = new RouteLookaheadGuard(host,
                key -> columns(ChunkPos.getX(key), ChunkPos.getZ(key), 63, land), maps,
                background::add, Runnable::run);
        guard.tick(10);
        background.removeFirst().run();
        assertTrue(guard.shouldHold());
        assertFalse(host.slotHeld);

        land.removeIf(p -> p.getX() == 116);
        assertFalse(maps.get().segmentHullClear(original.before(), original.after(), 1),
                "the unrelated inside-corner land still blocks the chord");
        RouteLookahead.Scan clear = RouteLookahead.scan(host.route, 1, boat, 0, 160, maps.get(), key -> true, 1);
        assertTrue(clear.obstacle().isEmpty());
        assertTrue(clear.checkedUpTo() > original.toSegment(), "both original legs are fully verified clear");

        guard.tick(110);

        assertFalse(guard.shouldHold(), "fully verified original legs supersede the old obstruction hold");
        assertTrue(background.isEmpty());
        assertEquals(0, host.patches);
        assertEquals(0, host.escalations);
    }

    private static RouteLookaheadGuard guard(FakeHost host, BlockPos centre, Set<BlockPos> land) {
        return new RouteLookaheadGuard(host,
                key -> columns(ChunkPos.getX(key), ChunkPos.getZ(key), centre.getY(), land),
                () -> map(centre, land), Runnable::run, Runnable::run);
    }

    private static RealBlockWaterMap map(BlockPos centre, Set<BlockPos> land) {
        RealBlockWaterMap map = new RealBlockWaterMap(null, centre.getY());
        int cx = centre.getX() >> 4;
        int cz = centre.getZ() >> 4;
        for (int x = cx - 4; x <= cx + 4; x++) {
            for (int z = cz - 4; z <= cz + 4; z++) map.seedChunk(x, z, columns(x, z, centre.getY(), land));
        }
        return map;
    }

    private static NbtChunkWaterReader.ChunkColumns columns(int cx, int cz, int seaY, Set<BlockPos> land) {
        boolean[] water = new boolean[256];
        int[] surface = new int[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = z * 16 + x;
            water[i] = !land.contains(new BlockPos((cx << 4) + x, seaY, (cz << 4) + z));
            surface[i] = seaY - 1;
        }
        return new NbtChunkWaterReader.ChunkColumns(water, surface);
    }

    private static Vec3 v(BlockPos p) { return Vec3.atBottomCenterOf(p); }

    private static final class FakeHost implements RouteLookaheadGuard.Host {
        private List<Vec3> route;
        private int target = 1;
        private final Vec3 position;
        private boolean slotHeld;
        private int splices;
        private int patches;
        private int escalations;

        private FakeHost(List<Vec3> route) {
            this(route, route.getFirst());
        }
        private FakeHost(List<Vec3> route, Vec3 position) {
            this.route = new ArrayList<>(route);
            this.position = position;
        }
        @Override public List<Vec3> route() { return route; }
        @Override public int targetIndex() { return target; }
        @Override public Vec3 position() { return position; }
        @Override public boolean autopilotActive() { return true; }
        @Override public boolean claimRerouteSlot() {
            if (slotHeld) return false;
            slotHeld = true;
            return true;
        }
        @Override public void releaseRerouteSlot() { slotHeld = false; }
        @Override public void applySplice(RouteSplicer.Spliced spliced) {
            splices++;
            route = new ArrayList<>(spliced.route());
            target = spliced.targetIndex();
        }
        @Override public void escalateToFullRescue() {
            assertFalse(slotHeld);
            escalations++;
        }
        @Override public void onPatch(RoutePatch patch) { patches++; }
    }
}
