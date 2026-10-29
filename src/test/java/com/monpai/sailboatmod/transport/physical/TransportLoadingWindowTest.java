package com.monpai.sailboatmod.transport.physical;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class TransportLoadingWindowTest {
    private static final UUID REG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SHIP = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final AABB HULL = new AABB(-4, 63, -4, 4, 70, 4);

    @Test void distantTargetKeepsOnlyTheBoundedForwardWindow() {
        Set<ChunkPos> chunks = TransportLoadingWindow.desiredChunks(HULL, new Vec3(100_000, 64, 0));
        assertFalse(chunks.isEmpty()); assertTrue(chunks.size() <= 128);
        assertTrue(chunks.stream().allMatch(c -> c.getMinBlockX() < 192 && c.getMinBlockZ() < 48));
        assertTrue(chunks.contains(new ChunkPos(-1, -1))); assertTrue(chunks.contains(new ChunkPos(0, 0)));
    }

    @Test void requestsAreBoundedPerTickAndDoNotBlockOnColdGeneration() {
        var backend = new FakeBackend(); var window = new TransportLoadingWindow(backend);
        assertFalse(window.update(null, REG, SHIP, HULL, new Vec3(100_000, 64, 0)));
        assertEquals(4, backend.pending.size());
        window.update(null, REG, SHIP, HULL, new Vec3(100_000, 64, 0));
        assertEquals(4, backend.pending.size(), "two updates in the same tick share one request budget");
        backend.time++; window.update(null, REG, SHIP, HULL, new Vec3(100_000, 64, 0));
        assertEquals(8, backend.pending.size()); assertTrue(backend.retained.isEmpty());
    }

    @Test void loadedWindowIsReadyAndReleasesOldWorldAndStructureTickets() {
        var backend = new FakeBackend(); backend.allLoaded = true;
        var window = new TransportLoadingWindow(backend);
        assertTrue(window.update(null, REG, SHIP, HULL, new Vec3(100, 64, 0)));
        Set<ChunkPos> old = Set.copyOf(backend.retained);
        backend.time++;
        assertTrue(window.update(null, REG, SHIP, HULL.move(1024, 0, 0), new Vec3(1200, 64, 0)));
        assertTrue(Collections.disjoint(old, backend.retained));
        assertTrue(backend.structureRetained);
        window.close(); window.close(); assertTrue(backend.retained.isEmpty()); assertFalse(backend.structureRetained);
    }

    @Test void leavingWindowDoesNotCancelSharedChunkFutures() {
        var backend = new FakeBackend(); var window = new TransportLoadingWindow(backend);
        window.update(null, REG, SHIP, HULL, new Vec3(100, 64, 0));
        List<CompletableFuture<Boolean>> started = List.copyOf(backend.pending.values());
        window.close();
        assertFalse(started.isEmpty()); assertTrue(started.stream().noneMatch(CompletableFuture::isCancelled));
    }

    @Test void oversizedChangedHullReleasesItsFormerTicketsAndStructureLease() {
        var backend = new FakeBackend(); backend.allLoaded = true;
        var window = new TransportLoadingWindow(backend);
        assertTrue(window.update(null, REG, SHIP, HULL, new Vec3(100,64,0)));
        assertFalse(window.update(null, REG, SHIP, HULL.inflate(1000), new Vec3(100,64,0)));
        assertTrue(backend.retained.isEmpty()); assertFalse(backend.structureRetained);
    }

    private static final class FakeBackend implements TransportLoadingWindow.Backend {
        long time; boolean allLoaded; boolean structureRetained;
        final Map<ChunkPos, CompletableFuture<Boolean>> pending = new LinkedHashMap<>();
        final Set<ChunkPos> retained = new HashSet<>();
        @Override public long tick() { return time; }
        @Override public boolean isLoaded(ChunkPos chunk) { return allLoaded; }
        @Override public CompletableFuture<Boolean> request(ChunkPos chunk) {
            return pending.computeIfAbsent(chunk, ignored -> new CompletableFuture<>());
        }
        @Override public void ticket(UUID owner, ChunkPos chunk, boolean retain) { if (retain) retained.add(chunk); else retained.remove(chunk); }
        @Override public boolean structure(UUID physical, UUID registration, boolean retain) { structureRetained = retain; return true; }
    }
}
