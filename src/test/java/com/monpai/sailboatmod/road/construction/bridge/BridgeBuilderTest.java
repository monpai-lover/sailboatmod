package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.model.BridgeGapKind;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.BridgeSpanKind;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeBuilderTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void shortSpanBuildSkipsPierRequirement() {
        assertFalse(BridgeBuilder.requiresPiersForLengthForTest(4));
        assertFalse(BridgeBuilder.requiresPiersForLengthForTest(8));
    }

    @Test
    void longSpanBuildRequiresPierBridge() {
        assertTrue(BridgeBuilder.requiresPiersForLengthForTest(9));
        assertTrue(BridgeBuilder.requiresPiersForLengthForTest(16));
    }

    @Test
    void inclusiveNineColumnRegularBridgeUsesPiers() {
        BridgeBuilder builder = new BridgeBuilder(new BridgeConfig());
        TestTerrainSamplingCache cache = new TestTerrainSamplingCache();
        seedBridgeColumns(cache, 0, 10, 66, 55, 63);
        List<BlockPos> centerPath = IntStream.rangeClosed(0, 10)
                .mapToObj(x -> new BlockPos(x, cache.getHeight(x, 0), 0))
                .toList();
        BridgeSpan span = new BridgeSpan(1, 9, 63, 55,
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);

        List<BuildStep> steps = builder.build(span, centerPath, 3, RoadMaterial.STONE_BRICK, 0, cache);

        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.PIER),
                "A regular bridge covering nine inclusive columns should use pier construction");
    }

    @Test
    void regularBridgeRampStartsAtAdjacentLandHeight() {
        BridgeBuilder builder = new BridgeBuilder(new BridgeConfig());
        TestTerrainSamplingCache cache = new TestTerrainSamplingCache();
        seedBridgeColumns(cache, 0, 15, 66, 55, 63);
        List<BlockPos> centerPath = IntStream.rangeClosed(0, 15)
                .mapToObj(x -> new BlockPos(x, cache.getHeight(x, 0), 0))
                .toList();
        BridgeSpan span = new BridgeSpan(1, 14, 63, 55,
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);

        List<BuildStep> steps = builder.build(span, centerPath, 3, RoadMaterial.STONE_BRICK, 0, cache);

        int firstRampY = steps.stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> step.pos().getX() == 1 && step.pos().getZ() == 0)
                .mapToInt(step -> step.pos().getY())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected regular bridge to create an entry ramp"));
        assertEquals(66, firstRampY,
                "A regular bridge ramp should begin at the adjacent land height, not down at water level");
    }

    @Test
    void deepWaterRegularBridgeBuildStepsKeepIncreasingOrder() {
        BridgeBuilder builder = new BridgeBuilder(new BridgeConfig());
        TestTerrainSamplingCache cache = new TestTerrainSamplingCache();
        seedBridgeColumns(cache, 0, 32, 66, 0, 63);
        List<BlockPos> centerPath = IntStream.rangeClosed(0, 32)
                .mapToObj(x -> new BlockPos(x, cache.getHeight(x, 0), 0))
                .toList();
        BridgeSpan span = new BridgeSpan(1, 31, 63, 0,
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);

        List<BuildStep> steps = builder.build(span, centerPath, 3, RoadMaterial.STONE_BRICK, 0, cache);

        int previousOrder = -1;
        for (BuildStep step : steps) {
            assertTrue(step.order() > previousOrder,
                    "Bridge build steps should keep strictly increasing order across piers, deck, lights, and ramps");
            previousOrder = step.order();
        }
    }

    @Test
    void shortFlatSpanBuildsFlatDeckAtClassifiedHeightWithoutPiers() {
        BridgeBuilder builder = new BridgeBuilder(new BridgeConfig());
        List<BlockPos> centerPath = IntStream.rangeClosed(0, 7)
                .mapToObj(x -> new BlockPos(x, x < 6 ? 64 : 68, 0))
                .toList();
        BridgeSpan span = new BridgeSpan(2, 5, 63, 58, BridgeSpanKind.SHORT_SPAN_FLAT, 68);

        List<BuildStep> steps = builder.build(span, centerPath, 3, RoadMaterial.STONE_BRICK, 0);

        assertFalse(steps.stream().anyMatch(step -> step.phase() == BuildPhase.PIER),
                "Short flat bridges must never create pier steps");
        Set<Integer> deckYs = steps.stream()
                .filter(step -> step.phase() == BuildPhase.DECK)
                .map(step -> step.pos().getY())
                .collect(Collectors.toSet());
        assertEquals(Set.of(68), deckYs,
                "Every short-flat deck block should be placed at the classified deck height");
        assertFalse(steps.stream().anyMatch(step -> step.phase() == BuildPhase.RAMP),
                "Short flat bridges should directly connect as a flat deck without ramp or slab slope blocks");
    }

    private static void seedBridgeColumns(TestTerrainSamplingCache cache,
                                          int minX,
                                          int maxX,
                                          int landY,
                                          int waterFloorY,
                                          int waterSurfaceY) {
        cache.setLandColumn(minX, 0, landY);
        cache.setLandColumn(maxX, 0, landY);
        for (int x = minX + 1; x < maxX; x++) {
            cache.setWaterColumn(x, 0, waterFloorY, waterSurfaceY);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (T) unsafe.allocateInstance(type);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static final class TestTerrainSamplingCache extends TerrainSamplingCache {
        private final Map<Long, Integer> heights = new HashMap<>();
        private final Map<Long, Boolean> water = new HashMap<>();
        private final Map<Long, Integer> waterSurface = new HashMap<>();
        private final Map<Long, Integer> oceanFloor = new HashMap<>();
        private final Holder<Biome> biome = Holder.direct(allocate(Biome.class));

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
        public int getWaterSurfaceY(int x, int z) {
            return waterSurface.getOrDefault(key(x, z), getHeight(x, z));
        }

        @Override
        public int getOceanFloor(int x, int z) {
            return oceanFloor.getOrDefault(key(x, z), getHeight(x, z));
        }

        @Override
        public Holder<Biome> getBiome(int x, int z) {
            return biome;
        }

        private long key(int x, int z) {
            return BlockPos.asLong(x, 0, z);
        }
    }
}
