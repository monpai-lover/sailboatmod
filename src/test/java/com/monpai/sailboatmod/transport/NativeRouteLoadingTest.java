package com.monpai.sailboatmod.transport;

import com.monpai.sailboatmod.transport.physical.TransportLoadingWindow;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRouteLoadingTest {
    private static final UUID OWNER = new UUID(0, 1), BODY = new UUID(0, 2);
    private static final Vec3 POSITION = new Vec3(13.9, 1, 13.9);
    private static final AABB HULL = new AABB(11.9, 0, 11.9, 15.9, 2, 15.9);
    private static final List<Vec3> ROUTE = List.of(new Vec3(13.9, 1, 16.3), new Vec3(16.3, 1, 15.9));

    @Test void coldCandidateKeepsItsLeaseUntilFullInsteadOfReplayingBothWindows() {
        var backend = new DelayedBackend();
        try (var window = new TransportLoadingWindow(backend)) {
            for (int tick = 0; tick <= 15; tick += 5) {
                backend.time = tick;
                var requested = new ArrayList<Vec3>();
                var selected = NativeRouteProgression.selectLoaded(ROUTE, 0, POSITION, Vec3.ZERO, 3,
                        target -> true, point -> true, target -> {
                            requested.add(target);
                            return window.update(null, OWNER, BODY, HULL, target);
                        });
                assertEquals(List.of(ROUTE.getLast()), requested, "Only the selected path may change chunk leases");
                assertEquals(tick >= 10, selected.terrainReady());
                assertEquals(tick >= 10 ? 1 : 0, selected.index());
            }
            assertEquals(1, backend.requests);
            assertEquals(0, backend.earlyReleases);
            assertTrue(backend.futures.stream().noneMatch(CompletableFuture::isCancelled));
        }
    }

    @Test void blockedShortcutOnlyLoadsOriginalRouteAndKeepsItsIndex() {
        var requested = new ArrayList<Vec3>();
        var selected = NativeRouteProgression.selectLoaded(ROUTE, 0, POSITION, Vec3.ZERO, 3,
                target -> !target.equals(ROUTE.getLast()), point -> true,
                target -> { requested.add(target); return true; });
        assertEquals(List.of(ROUTE.getFirst()), requested);
        assertTrue(selected.terrainReady());
        assertEquals(0, selected.index());
    }

    @Test void unreadTerrainStillRequestsSelectedWindowButCannotAdvance() {
        var requested = new ArrayList<Vec3>();
        var selected = NativeRouteProgression.selectLoaded(ROUTE, 0, POSITION, Vec3.ZERO, 3,
                target -> false, point -> true,
                target -> { requested.add(target); return true; });
        assertEquals(List.of(ROUTE.getFirst()), requested);
        assertFalse(selected.terrainReady());
        assertEquals(0, selected.index());
    }

    @Test void optionalColdWindowFallsBackButRequiredFinalLegCanLoadIt() {
        var backend = new DelayedBackend();
        backend.full.add(new ChunkPos(1, 2));
        try (var window = new TransportLoadingWindow(backend)) {
            assertFalse(window.isWindowLoaded(null, HULL, ROUTE.getLast()));
            assertTrue(window.isWindowLoaded(null, HULL, ROUTE.getFirst()));
            assertEquals(0, backend.requests);
            assertTrue(backend.admitted.isEmpty());
            var requested = new ArrayList<Vec3>();
            var selected = NativeRouteProgression.selectLoaded(ROUTE, 0, POSITION, Vec3.ZERO, 3,
                    target -> true, window.isWindowLoaded(null, HULL, ROUTE.getLast()) ? p -> true : null,
                    target -> { requested.add(target); return window.update(null, OWNER, BODY, HULL, target); });
            assertEquals(List.of(ROUTE.getFirst()), requested);
            assertTrue(selected.terrainReady());
            assertEquals(0, selected.index());
            // Having reached the bend, the last point is now the ordinary route, not an optional shortcut.
            for (int tick = 0; tick <= 10; tick += 5) {
                backend.time = tick;
                selected = NativeRouteProgression.selectLoaded(ROUTE, 1, ROUTE.getFirst(), Vec3.ZERO, 3,
                        target -> true, null, target -> window.update(null, OWNER, BODY, HULL, target));
                assertEquals(tick >= 10, selected.terrainReady());
            }
            assertEquals(1, backend.requests);
            assertEquals(0, backend.earlyReleases);
        }
    }

    private static final class DelayedBackend implements TransportLoadingWindow.Backend {
        long time;
        int requests, earlyReleases;
        final Set<ChunkPos> cold = Set.of(new ChunkPos(2, 1), new ChunkPos(1, 2));
        final Map<ChunkPos, Long> admitted = new HashMap<>();
        final Set<ChunkPos> full = new HashSet<>();
        final List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        public long tick() { return time; }
        public boolean isLoaded(ChunkPos chunk) {
            if (!cold.contains(chunk) || full.contains(chunk)) return true;
            Long start = admitted.get(chunk);
            if (start != null && time - start >= 6) { full.add(chunk); return true; }
            return false;
        }
        public CompletableFuture<Boolean> request(ChunkPos chunk) {
            requests++;
            var future = new CompletableFuture<Boolean>(); futures.add(future); return future;
        }
        public void ticket(UUID owner, ChunkPos chunk, boolean retain) { }
        public void airportTicket(UUID owner, ChunkPos chunk, boolean retain) {
            if (retain) admitted.putIfAbsent(chunk, time);
            else {
                if (admitted.containsKey(chunk) && !isLoaded(chunk)) earlyReleases++;
                admitted.remove(chunk);
            }
        }
        public boolean structure(UUID body, UUID registration, boolean retain) { return true; }
    }
}
