package com.monpai.sailboatmod.road.pathfinding.post;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.BridgeSpanKind;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathPostProcessorTest {
    @Test
    void bridgeApproachLandNodesSnapBackToTerrainAfterSplineRasterization() {
        PathPostProcessor processor = new PathPostProcessor();
        TestTerrainSamplingCache cache = new TestTerrainSamplingCache();

        cache.setLandColumn(0, 1, 66);
        cache.setLandColumn(1, 1, 66);
        cache.setLandColumn(2, 1, 63);
        cache.setLandColumn(2, 0, 63);
        for (int x = 3; x <= 7; x++) {
            cache.setWaterColumn(x, 0, 55, 63);
        }
        cache.setLandColumn(8, 0, 63);
        cache.setLandColumn(9, 0, 64);
        cache.setLandColumn(10, 0, 64);

        List<BlockPos> rawPath = List.of(
                new BlockPos(0, 66, 1),
                new BlockPos(1, 66, 1),
                new BlockPos(2, 63, 0),
                new BlockPos(3, 55, 0),
                new BlockPos(4, 55, 0),
                new BlockPos(5, 55, 0),
                new BlockPos(6, 55, 0),
                new BlockPos(7, 55, 0),
                new BlockPos(8, 63, 0),
                new BlockPos(9, 64, 0),
                new BlockPos(10, 64, 0)
        );

        PathPostProcessor.ProcessedPath processed = processor.process(rawPath, cache, 4);

        assertFalse(processed.bridgeSpans().isEmpty(), "Expected water segment to remain classified as bridge");

        BlockPos bridgeApproach = processed.path().stream()
                .filter(pos -> pos.getX() == 2 && pos.getZ() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a rasterized land node on the bridge approach"));

        assertEquals(cache.getHeight(bridgeApproach.getX(), bridgeApproach.getZ()), bridgeApproach.getY(),
                "A bridge-approach land node should be re-anchored to the terrain height after spline interpolation");

        for (int i = 0; i < processed.path().size(); i++) {
            BlockPos point = processed.path().get(i);
            if (cache.isWater(point.getX(), point.getZ()) || isInBridge(i, processed.bridgeSpans())) {
                continue;
            }
            assertEquals(cache.getHeight(point.getX(), point.getZ()), point.getY(),
                    "Non-bridge land nodes should stay on the sampled terrain");
        }
    }

    @Test
    void shortFlatBridgeColumnsStayClampedToFlatDeckHeight() {
        PathPostProcessor processor = new PathPostProcessor();
        TestTerrainSamplingCache cache = new TestTerrainSamplingCache();

        cache.setLandColumn(0, 1, 66);
        cache.setLandColumn(1, 1, 66);
        cache.setLandColumn(2, 0, 66);
        for (int x = 3; x <= 7; x++) {
            cache.setWaterColumn(x, 0, 58, 63);
        }
        cache.setLandColumn(8, 0, 68);
        cache.setLandColumn(9, 0, 68);

        List<BlockPos> rawPath = List.of(
                new BlockPos(0, 66, 1),
                new BlockPos(1, 66, 1),
                new BlockPos(2, 66, 0),
                new BlockPos(3, 58, 0),
                new BlockPos(4, 58, 0),
                new BlockPos(5, 58, 0),
                new BlockPos(6, 58, 0),
                new BlockPos(7, 58, 0),
                new BlockPos(8, 68, 0),
                new BlockPos(9, 68, 0)
        );

        PathPostProcessor.ProcessedPath processed = processor.process(rawPath, cache, 4);

        BridgeSpan shortFlat = processed.bridgeSpans().stream()
                .filter(span -> span.kind() == BridgeSpanKind.SHORT_SPAN_FLAT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected narrow water crossing to become a short flat bridge"));
        assertEquals(68, shortFlat.deckY());
        assertTrue(IntStream.rangeClosed(shortFlat.startIndex(), shortFlat.endIndex())
                        .mapToObj(processed.path()::get)
                        .allMatch(pos -> pos.getY() == 68),
                "Short flat bridge columns should stay clamped to the selected flat deck height");
    }


    @Test
    void selectedHalfWidthProducesWiderPlacements() {
        PathPostProcessor processor = new PathPostProcessor();
        TestTerrainSamplingCache cache = new TestTerrainSamplingCache();
        for (int x = 0; x <= 12; x++) {
            for (int z = -4; z <= 4; z++) {
                cache.setLandColumn(x, z, 64);
            }
        }
        List<BlockPos> rawPath = List.of(new BlockPos(0, 64, 0), new BlockPos(12, 64, 0));

        PathPostProcessor.ProcessedPath narrow = processor.process(rawPath, cache, 4, 1);
        PathPostProcessor.ProcessedPath wide = processor.process(rawPath, cache, 4, 3);

        int narrowCells = narrow.placements().stream().mapToInt(placement -> placement.positions().size()).sum();
        int wideCells = wide.placements().stream().mapToInt(placement -> placement.positions().size()).sum();
        assertTrue(wideCells > narrowCells * 2,
                "width 7 should produce substantially more footprint cells than width 3");
    }
    private static boolean isInBridge(int index, List<BridgeSpan> spans) {
        for (BridgeSpan span : spans) {
            if (index >= span.startIndex() && index <= span.endIndex()) {
                return true;
            }
        }
        return false;
    }

    private static final class TestTerrainSamplingCache extends TerrainSamplingCache {
        private final Map<Long, Integer> heights = new HashMap<>();
        private final Map<Long, Boolean> water = new HashMap<>();
        private final Map<Long, Integer> waterSurface = new HashMap<>();
        private final Map<Long, Integer> oceanFloor = new HashMap<>();

        private TestTerrainSamplingCache() {
            super(null, PathfindingConfig.SamplingPrecision.HIGH);
        }

        void setLandColumn(int x, int z, int height) {
            long key = key(x, z);
            heights.put(key, height);
            water.put(key, false);
            waterSurface.put(key, height);
            oceanFloor.put(key, height);
        }

        void setWaterColumn(int x, int z, int floorY, int surfaceY) {
            long key = key(x, z);
            heights.put(key, floorY);
            water.put(key, true);
            waterSurface.put(key, surfaceY);
            oceanFloor.put(key, floorY);
        }

        @Override
        public int getHeight(int x, int z) {
            return heights.getOrDefault(key(x, z), 63);
        }

        @Override
        public boolean isWater(int x, int z) {
            return water.getOrDefault(key(x, z), false);
        }

        @Override
        public boolean isWaterBiome(int x, int z) {
            return false;
        }

        @Override
        public int getWaterDepth(int x, int z) {
            return getWaterSurfaceY(x, z) - getOceanFloor(x, z);
        }

        @Override
        public int getWaterSurfaceY(int x, int z) {
            return waterSurface.getOrDefault(key(x, z), getHeight(x, z));
        }

        @Override
        public int getOceanFloor(int x, int z) {
            return oceanFloor.getOrDefault(key(x, z), getHeight(x, z));
        }

        private static long key(int x, int z) {
            return (((long) x) << 32) | (z & 0xFFFFFFFFL);
        }
    }
}
