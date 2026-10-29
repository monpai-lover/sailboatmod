package com.monpai.sailboatmod.route.water.lookahead;

import com.monpai.sailboatmod.route.repair.RoutePatch;
import com.monpai.sailboatmod.route.water.NbtChunkWaterReader;
import com.monpai.sailboatmod.route.water.RealBlockWaterMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteLookaheadGuardTest {
    private static class FakeHost implements RouteLookaheadGuard.Host {
        List<Vec3> route = new ArrayList<>(List.of(v(0, 20), v(64, 20), v(128, 20), v(192, 20)));
        int target = 1;
        Vec3 position = v(50, 20);
        boolean active = true;
        boolean slotFree = true;
        boolean slotHeld;
        int splices;
        int patches;
        int escalations;

        @Override public List<Vec3> route() { return route; }
        @Override public int targetIndex() { return target; }
        @Override public Vec3 position() { return position; }
        @Override public boolean autopilotActive() { return active; }

        @Override
        public boolean claimRerouteSlot() {
            if (!slotFree || slotHeld) {
                return false;
            }
            slotHeld = true;
            return true;
        }

        @Override public void releaseRerouteSlot() { slotHeld = false; }

        @Override
        public void applySplice(RouteSplicer.Spliced spliced) {
            splices++;
            route = new ArrayList<>(spliced.route());
            target = spliced.targetIndex();
        }

        @Override
        public void escalateToFullRescue() {
            escalations++;
            assertFalse(slotHeld, "the shared reroute slot must be free before the full rescue starts");
        }
        @Override public void onPatch(RoutePatch patch) { patches++; }
    }

    private final Deque<Runnable> background = new ArrayDeque<>();
    private int columnLookups;

    private RouteLookaheadGuard guard(FakeHost host, int wallEnd, boolean deferBackground) {
        LongFunction<NbtChunkWaterReader.ChunkColumns> columns = key -> {
            columnLookups++;
            return LookaheadMaps.LOADED.contains(key)
                    ? LookaheadMaps.columns(ChunkPos.getX(key), ChunkPos.getZ(key), wallEnd) : null;
        };
        return new RouteLookaheadGuard(host, columns, () -> new RealBlockWaterMap(null, 63),
                deferBackground ? background::add : Runnable::run, Runnable::run);
    }

    @Test
    void detectsWallAndAppliesSplice() {
        FakeHost host = new FakeHost();
        RouteLookaheadGuard guard = guard(host, 40, false);

        guard.tick(10);

        assertEquals(1, host.splices);
        assertEquals(1, host.patches);
        assertEquals(0, host.escalations);
        assertFalse(host.slotHeld);
        assertTrue(host.route.size() > 4);
    }

    @Test
    void onlyOneRerouteAtATime() {
        FakeHost host = new FakeHost();
        host.slotFree = false;

        guard(host, 40, false).tick(10);

        assertEquals(0, host.splices);
        assertEquals(0, host.escalations);
    }

    @Test
    void resultIsDroppedWhenAutopilotStopped() {
        FakeHost host = new FakeHost();
        RouteLookaheadGuard guard = guard(host, 40, true);
        guard.tick(10);
        assertTrue(host.slotHeld);

        host.active = false;
        background.poll().run();

        assertEquals(0, host.splices);
        assertEquals(0, host.patches);
        assertFalse(host.slotHeld);
    }

    @Test
    void resultIsDroppedAfterResetAndRestartOfTheSameRoute() {
        FakeHost host = new FakeHost();
        RouteLookaheadGuard guard = guard(host, 40, true);
        guard.tick(10);
        assertTrue(host.slotHeld);

        host.active = false;
        guard.reset();
        host.route = new ArrayList<>(host.route);
        host.active = true;
        background.removeFirst().run();

        assertEquals(0, host.splices, "a stopped voyage's callback cannot change a restarted voyage with the same route");
        assertEquals(0, host.patches);
        assertEquals(0, host.escalations);
        assertFalse(host.slotHeld);
    }

    @Test
    void oldCompletionCannotReleaseTheRestartedVoyagesRerouteSlot() {
        FakeHost host = new FakeHost();
        RouteLookaheadGuard previous = guard(host, 40, true);
        previous.tick(10);
        previous.reset();
        // The entity starts a new voyage and owns a new guard. Its slot is
        // independent of a still-pending computation from the previous guard.
        host.slotHeld = false;
        RouteLookaheadGuard restarted = guard(host, 40, true);
        restarted.tick(20);
        assertEquals(2, background.size());
        assertTrue(host.slotHeld);

        background.removeFirst().run();

        assertEquals(0, host.splices);
        assertTrue(host.slotHeld, "the old callback must not release a slot held by the restarted voyage");
        background.removeFirst().run();
        assertEquals(1, host.splices);
        assertFalse(host.slotHeld);
    }

    @Test
    void escalatesWhenNoDetour() {
        FakeHost host = new FakeHost();

        guard(host, 63, false).tick(10);

        assertEquals(1, host.escalations);
        assertEquals(0, host.splices);
        assertFalse(host.slotHeld);
    }

    @Test
    void holdsWithin48BlocksOfTheObstacleWhilePending() {
        FakeHost host = new FakeHost();
        host.position = v(58, 20); // 40 blocks before the last clear cell (98,20) in front of the wall
        RouteLookaheadGuard guard = guard(host, 40, true);
        guard.tick(10);

        assertTrue(guard.shouldHold());
        host.position = v(38, 20);
        assertFalse(guard.shouldHold());
    }

    @Test
    void ticksOnlyEvery10() {
        FakeHost host = new FakeHost();

        guard(host, 40, false).tick(3);

        assertEquals(0, columnLookups);
        assertEquals(0, host.splices);
    }

    @Test
    void extraChunksStayWithinCap() {
        FakeHost host = new FakeHost();

        assertTrue(guard(host, 40, false).extraChunks().size() <= RouteLookaheadGuard.MAX_EXTRA_CHUNKS);
    }

    @Test
    void staleResultIsDroppedAndRescanned() {
        FakeHost host = new FakeHost();
        RouteLookaheadGuard guard = guard(host, 40, true);
        guard.tick(10);
        host.target = 3; // the boat is already past the blocked segment's end

        background.poll().run();

        assertEquals(0, host.splices);
        assertEquals(0, host.escalations, "a stale result is dropped, not escalated");
        assertFalse(host.slotHeld);
    }

    @Test
    void overCapEscalatesDirectly() {
        FakeHost host = new FakeHost();
        for (int i = 0; host.route.size() < RouteLookaheadGuard.MAX_WAYPOINTS; i++) {
            host.route.add(v(200 + (i % 50), 20));
        }

        guard(host, 40, false).tick(10);

        assertEquals(0, host.splices);
        assertEquals(1, host.escalations);
        assertEquals(0, background.size(), "no second background computation");
        assertFalse(host.slotHeld);
    }

    @Test
    void slotIsReleasedWhenSplicingThrows() {
        FakeHost host = new FakeHost() {
            @Override
            public void applySplice(RouteSplicer.Spliced spliced) {
                throw new IllegalStateException("boom");
            }
        };

        guard(host, 40, false).tick(10);

        assertFalse(host.slotHeld, "a failed splice must not leave the shared reroute slot held");
    }

    private static Vec3 v(int x, int z) {
        return new Vec3(x + 0.5D, 63, z + 0.5D);
    }
}
