EDITS = [
('src/test/java/com/monpai/sailboatmod/road/pathfinding/FarTownRoadTerrain.java', None, '88ee9a1e96e8800b4a27b0a9c416ae6fcba05516b48c2a947bf7e178e5d6e8cf', [
(0, 0, r"""package com.monpai.sailboatmod.road.pathfinding;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synthetic terrain for far town-to-town road tests: rolling hills plus an inland sea at
 * x in [seaMinX, seaMaxX], |z| < seaHalfWidth that forces a detour. Every uncached height/floor read
 * stands for one ChunkGenerator#getBaseHeight call in production; {@code sampleMicros} simulates its cost.
 */
public class FarTownRoadTerrain extends TerrainSamplingCache {
    public static final int SEA_LEVEL = 63;
    private final int seaMinX, seaMaxX, seaHalfWidth;
    private final long sampleMicros;
    public final AtomicLong baseHeightCalls = new AtomicLong();
    private final ConcurrentHashMap<Long, Integer> heights = new ConcurrentHashMap<>();

    public FarTownRoadTerrain(int seaMinX, int seaMaxX, int seaHalfWidth, long sampleMicros) {
        super(PathfindingConfig.SamplingPrecision.NORMAL);
        this.seaMinX = seaMinX;
        this.seaMaxX = seaMaxX;
        this.seaHalfWidth = seaHalfWidth;
        this.sampleMicros = sampleMicros;
    }

    private boolean sea(int x, int z) {
        return x >= seaMinX && x <= seaMaxX && Math.abs(z) < seaHalfWidth;
    }

    private int baseHeight(int x, int z) {
        baseHeightCalls.incrementAndGet();
        if (sampleMicros > 0) {
            long end = System.nanoTime() + sampleMicros * 1000L;
            while (System.nanoTime() < end) Thread.onSpinWait();
        }
        if (sea(x, z)) return 40;
        return (int) Math.round(72 + 14 * Math.sin(x / 310.0) * Math.cos(z / 260.0) + 5 * Math.sin(x / 57.0 + z / 91.0));
    }

    @Override public int getHeight(int x, int z) {
        return heights.computeIfAbsent(((long) x << 32) | (z & 0xFFFFFFFFL), k -> {
            int h = baseHeight(x, z);
            return sea(x, z) ? SEA_LEVEL - 1 : h;
        });
    }
    @Override public int getOceanFloor(int x, int z) { return sea(x, z) ? 40 : getHeight(x, z); }
    @Override public boolean isWater(int x, int z) { getHeight(x, z); return sea(x, z); }
    @Override public int getWaterSurfaceY(int x, int z) { return sea(x, z) ? SEA_LEVEL : getHeight(x, z); }
    @Override public boolean isWaterBiome(int x, int z) { return sea(x, z); }
}
""")
]),
('src/test/java/com/monpai/sailboatmod/road/pathfinding/cache/BoundedColumnCacheTest.java', None, '7475d220be1c46cb732ca09f9a4ad48577ebac5a330aa0298f414681277e7fdb', [
(0, 0, r"""package com.monpai.sailboatmod.road.pathfinding.cache;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class BoundedColumnCacheTest {
 @Test void boundedAndRecomputesEvictedValues(){var c=new BoundedColumnCache<Long>();c.limit(32);for(long i=0;i<10000;i++){long k=i;assertEquals(k,c.computeIfAbsent(k,x->x).longValue());assertTrue(c.size()<=32);}assertEquals(0,c.computeIfAbsent(0L,x->x).longValue());assertTrue(c.size()<=32);}
 @Test void cachesSuccessButNotExceptions(){var c=new BoundedColumnCache<Integer>();var count=new AtomicInteger();assertThrows(IllegalStateException.class,()->c.computeIfAbsent(1L,k->{throw new IllegalStateException();}));for(int i=0;i<20;i++)assertEquals(7,c.computeIfAbsent(1L,k->{count.incrementAndGet();return 7;}));assertEquals(1,count.get());}
 @Test void concurrentSamplingKeepsResultsAndBound()throws Exception{var c=new BoundedColumnCache<Long>();c.limit(64);try(var pool=Executors.newFixedThreadPool(4)){var work=new java.util.ArrayList<Future<?>>();for(int i=0;i<4;i++)work.add(pool.submit(()->{for(long k=0;k<4000;k++)assertEquals(k,c.computeIfAbsent(k,x->x).longValue());}));for(var f:work)f.get(10,TimeUnit.SECONDS);}assertTrue(c.size()<=64);}
}
""")
])
]
