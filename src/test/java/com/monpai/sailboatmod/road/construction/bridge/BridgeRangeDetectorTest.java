package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
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

class BridgeRangeDetectorTest {
    @Test
    void gradualValleyDropStillTriggersBridgeDetection() {
        BridgeRangeDetector detector = new BridgeRangeDetector(new BridgeConfig());
        TestTerrainSamplingCache cache = new TestTerrainSamplingCache();
        int[] heights = {70, 69, 68, 67, 66, 65, 64, 65, 66, 67, 68, 69, 70};
        for (int x = 0; x < heights.length; x++) {
            cache.setLandColumn(x, 0, heights[x]);
        }

        List<BlockPos> path = IntStream.range(0, heights.length)
                .mapToObj(x -> new BlockPos(x, heights[x], 0))
                .toList();

        List<BridgeSpan> spans = detector.detect(path, cache);

        assertFalse(spans.isEmpty(),
                "A gradual ravine should still be marked as a bridge span by the sliding-window detector");
        BridgeSpan canyon = spans.stream()
                .filter(span -> span.startIndex() <= 3 && span.endIndex() >= 8)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Detected bridge span should cover the center of the gradual valley"));
        assertEquals(BridgeSpanKind.SHORT_SPAN_FLAT, canyon.kind(),
                "Short ravines should use the flat pierless bridge classifier");
        assertEquals(70, canyon.deckY(),
                "Short flat bridge deck should use the higher endpoint height");
    }

    @Test
    void narrowWaterCrossingClassifiesAsShortFlatBridge() {
        BridgeRangeDetector detector = new BridgeRangeDetector(new BridgeConfig());
        TestTerrainSamplingCache cache = new TestTerrainSamplingCache();
        cache.setLandColumn(0, 0, 66);
        for (int x = 1; x <= 5; x++) {
            cache.setWaterColumn(x, 0, 58, 63);
        }
        cache.setLandColumn(6, 0, 68);

        List<BlockPos> path = IntStream.rangeClosed(0, 6)
                .mapToObj(x -> new BlockPos(x, cache.getHeight(x, 0), 0))
                .toList();

        List<BridgeSpan> spans = detector.detect(path, cache);

        assertEquals(1, spans.size());
        BridgeSpan span = spans.get(0);
        assertEquals(BridgeSpanKind.SHORT_SPAN_FLAT, span.kind());
        assertEquals(68, span.deckY());
    }

    @Test
    void wideWaterCrossingKeepsRegularBridgeClassification() {
        BridgeRangeDetector detector = new BridgeRangeDetector(new BridgeConfig());
        TestTerrainSamplingCache cache = new TestTerrainSamplingCache();
        cache.setLandColumn(0, 0, 66);
        for (int x = 1; x <= 14; x++) {
            cache.setWaterColumn(x, 0, 58, 63);
        }
        cache.setLandColumn(15, 0, 66);

        List<BlockPos> path = IntStream.rangeClosed(0, 15)
                .mapToObj(x -> new BlockPos(x, cache.getHeight(x, 0), 0))
                .toList();

        List<BridgeSpan> spans = detector.detect(path, cache);

        assertEquals(1, spans.size());
        assertEquals(BridgeSpanKind.REGULAR_BRIDGE, spans.get(0).kind(),
                "Water crossings wider than the short-span limit should keep the existing bridge path");
    }

    @Test
    void unevenWaterEndpointsFallBackToRegularBridge() {
        BridgeRangeDetector detector = new BridgeRangeDetector(new BridgeConfig());
        TestTerrainSamplingCache cache = new TestTerrainSamplingCache();
        cache.setLandColumn(0, 0, 66);
        for (int x = 1; x <= 4; x++) {
            cache.setWaterColumn(x, 0, 58, 63);
        }
        cache.setLandColumn(5, 0, 72);

        List<BlockPos> path = IntStream.rangeClosed(0, 5)
                .mapToObj(x -> new BlockPos(x, cache.getHeight(x, 0), 0))
                .toList();

        List<BridgeSpan> spans = detector.detect(path, cache);

        assertEquals(1, spans.size());
        assertEquals(BridgeSpanKind.REGULAR_BRIDGE, spans.get(0).kind(),
                "Short water spans with endpoint delta above four blocks should not force the flat bridge style");
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
            return heights.getOrDefault(key(x, z), 64);
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
