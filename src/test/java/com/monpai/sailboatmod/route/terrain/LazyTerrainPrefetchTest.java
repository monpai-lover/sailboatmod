package com.monpai.sailboatmod.route.terrain;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class LazyTerrainPrefetchTest {
    @Test void nativeDownloadsHaveABoundedConcurrencyAndQueuedRequestsStartAfterCompletion() {
        List<CompletableFuture<Void>> downloads = new ArrayList<>();
        TiledPrefetchGate gate = new TiledPrefetchGate() {
            protected CompletableFuture<Void> fetchTile(int x, int z) {
                var future = new CompletableFuture<Void>(); downloads.add(future); return future;
            }
        };
        List<CompletableFuture<Void>> results = new ArrayList<>();
        for (int i=0;i<20;i++) results.add(gate.request(i*32,0));
        assertEquals(4, downloads.size());
        downloads.get(0).complete(null);
        assertEquals(5, downloads.size());
        assertTrue(results.getFirst().isDone());
        assertFalse(results.getLast().isDone());
    }

    @Test void extremelyDistantCorridorIsLazyAndDeduplicatesNeighbouringSamples() throws Exception {
        List<Long> requested = new ArrayList<>();
        PrefetchGate gate = new PrefetchGate() {
            public CompletableFuture<Void> request(int x,int z) {
                requested.add(TiledPrefetchGate.tileKey(x,z)); return CompletableFuture.completedFuture(null);
            }
            public List<CompletableFuture<Void>> requestCorridor(BlockPos a,BlockPos b,int side) {
                throw new AssertionError("eager API must not be used");
            }
        };
        // Reflect to keep the first RED a behavioral assertion rather than a compiler failure.
        java.lang.reflect.Method method;
        try { method=PrefetchGate.class.getMethod("corridor",BlockPos.class,BlockPos.class,int.class); }
        catch (NoSuchMethodException missing) { fail("missing lazy corridor iterator"); return; }
        @SuppressWarnings("unchecked") Iterable<CompletableFuture<Void>> corridor=(Iterable<CompletableFuture<Void>>)method.invoke(gate,
                new BlockPos(-20_000_000,64,-1),new BlockPos(20_000_000,64,0),512);
        assertTrue(requested.isEmpty());
        Iterator<CompletableFuture<Void>> iterator=corridor.iterator();
        for(int i=0;i<12;i++) { assertTrue(iterator.hasNext()); iterator.next(); }
        assertEquals(12,requested.size()); assertEquals(12,new HashSet<>(requested).size());
    }
}
