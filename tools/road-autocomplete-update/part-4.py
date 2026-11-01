EDITS = [
('src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/BoundedColumnCache.java', None, '71310b58a00f42843e3a798fb1c24325598d8a567690e31c98debe798fd8ac93', [
(0, 0, r"""package com.monpai.sailboatmod.road.pathfinding.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/** Completed column values only, FIFO eviction; no eviction/retry loop for pending futures.
 * Unbounded by default for legacy consumers. Autocomplete explicitly opts into a per-map cap.
 */
final class BoundedColumnCache<V> {
    private final ConcurrentHashMap<Long,V> values = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> insertionOrder = new ConcurrentLinkedQueue<>();
    private volatile int limit = Integer.MAX_VALUE;
    void limit(int maximum) {
        int previous = limit; limit = Math.max(16, maximum);
        if (previous == Integer.MAX_VALUE) insertionOrder.addAll(values.keySet());
        trim();
    }
    V computeIfAbsent(Long key, Function<Long,V> sample) {
        boolean[] inserted = {false};
        V result = values.computeIfAbsent(key, k -> {
            V value = sample.apply(k);
            inserted[0] = value != null;
            return value;
        });
        if (inserted[0] && limit < Integer.MAX_VALUE) { insertionOrder.add(key); trim(); }
        return result;
    }
    private void trim() {
        while (values.size() > limit) {
            Long oldest = insertionOrder.poll();
            if (oldest == null) return;
            values.remove(oldest);
        }
    }
    int size() { return values.size(); }
    void clear() { values.clear(); insertionOrder.clear(); }
}
""")
]),
('src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/FastHeightSampler.java', '719bd57f69e4c8b276445ae78a7728168889f89da9c5ca33e83efcebde84fe9d', 'a010bb7f6ae251655bd06a7059cc9e0896ecd13ee3412c508a56be7e91c04b5d', [
(4, 4, r"""import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;
"""),
(35, 36, r"""        RoadSearchBudget.check();
        int value = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState) - 1;
        RoadSearchBudget.check();
        return value;
"""),
(39, 40, r"""        RoadSearchBudget.check();
        int value = generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level, randomState) - 1;
        RoadSearchBudget.check();
        return value;
""")
]),
('src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/TerrainSamplingCache.java', '5e3ba7d851fa307760e5de8b0beb09d26dafdf4949d9e488e0a14ba6cb5a205e', 'd14ed0a749e3e1bee085dd266513670700a943ed4369be71c698e605b9934d3d', [
(12, 13, r"""import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;

"""),
(24, 29, r"""    private final BoundedColumnCache<Integer> heightCache = new BoundedColumnCache<>();
    private final BoundedColumnCache<Integer> waterSurfaceCache = new BoundedColumnCache<>();
    private final BoundedColumnCache<Boolean> waterCache = new BoundedColumnCache<>();
    private final BoundedColumnCache<Integer> oceanFloorCache = new BoundedColumnCache<>();
    private final BoundedColumnCache<Holder<Biome>> biomeCache = new BoundedColumnCache<>();
"""),
(57, 57, r"""    /**
     * Tests only: a cache with no level. Subclasses must override every sampling method they use
     * ({@link #getHeight}, {@link #getOceanFloor}, {@link #isWater}, {@link #getWaterSurfaceY}, {@link #isWaterBiome}).
     */
    protected TerrainSamplingCache(PathfindingConfig.SamplingPrecision precision) {
        this.level = null;
        this.fastSampler = null;
        this.accurateSampler = null;
        this.biomeSource = null;
        this.climateSampler = null;
        this.precision = precision;
        this.blockedColumns = Set.of();
        this.allowedColumns = Set.of();
    }

"""),
(62, 62, r"""        RoadSearchBudget.check();
"""),
(71, 71, r"""        RoadSearchBudget.check();
"""),
(83, 83, r"""        RoadSearchBudget.check();
"""),
(94, 94, r"""        RoadSearchBudget.check();
"""),
(98, 98, r"""        RoadSearchBudget.check();
"""),
(105, 105, r"""        RoadSearchBudget.check();
"""),
(110, 110, r"""        RoadSearchBudget.check();
"""),
(115, 115, r"""        RoadSearchBudget.check();
"""),
(120, 120, r"""        RoadSearchBudget.check();
"""),
(132, 132, r"""        RoadSearchBudget.check();
"""),
(141, 141, r"""        RoadSearchBudget.check();
"""),
(142, 142, r"""    }

    /** Each autocomplete owns its cache; cap each category without altering other callers. */
    public TerrainSamplingCache withEntryLimit(int limit) {
        heightCache.limit(limit); waterSurfaceCache.limit(limit); waterCache.limit(limit);
        oceanFloorCache.limit(limit); biomeCache.limit(limit);
        return this;
""")
]),
('src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/BasicAStarPathfinder.java', '5015f105c21a73db2807d713c730449fd3ad226901c8b7a5c9f5ad32f11e25cc', '3442898c233e3da0f624837894b2d2aa02ec0cc830a3f1f73d7d0ce3d535e290', [
(33, 34, r"""        RoadSearchBudget.check();
        int step = Math.max(1, config.getAStarStep());
"""),
(49, 49, r"""            RoadSearchBudget.check();
"""),
(64, 64, r"""                RoadSearchBudget.check();
"""),
(88, 88, r"""            RoadSearchBudget.check();
""")
]),
('src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/BidirectionalAStarPathfinder.java', 'e85c8da41f96aee0f7384d8c640e972a2f12a82af731c2171ff511faa30519db', '9841190a78cef89adc68dac3466c5fcb7c3c5ab76c872b2c6db68d8b565888b2', [
(35, 36, r"""        RoadSearchBudget.check();
        int step = Math.max(1, config.getAStarStep());
"""),
(64, 64, r"""            RoadSearchBudget.check();
"""),
(87, 87, r"""                RoadSearchBudget.check();
"""),
(120, 120, r"""                RoadSearchBudget.check();
"""),
(156, 156, r"""            RoadSearchBudget.check();
"""),
(166, 166, r"""            RoadSearchBudget.check();
"""),
(193, 193, r"""            RoadSearchBudget.check();
"""),
(211, 211, r"""            RoadSearchBudget.check();
""")
]),
('src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/GradientDescentPathfinder.java', '3458b4fbb3579162f1a46aeee7201f5a018972590d6fdda5caf48509ce14753c', 'a95e8d9d1b97b79ebdd6e34bf2d75c0302b2499fba8ab9fdb16e32a1562e4ab6', [
(33, 34, r"""        RoadSearchBudget.check();
        int step = Math.max(1, config.getAStarStep());
"""),
(49, 49, r"""            RoadSearchBudget.check();
"""),
(63, 63, r"""                RoadSearchBudget.check();
"""),
(120, 120, r"""            RoadSearchBudget.check();
""")
])
]
