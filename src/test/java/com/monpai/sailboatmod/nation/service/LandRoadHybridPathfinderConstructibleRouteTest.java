package com.monpai.sailboatmod.nation.service;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LandRoadHybridPathfinderConstructibleRouteTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void plannerPrefersCutFillBeforeFailingOnBrokenGround() {
        TestTerrainLevel level = newLevel();
        for (int x = 0; x <= 3; x++) {
            level.setSurface(x, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        }
        for (int x = 5; x <= 8; x++) {
            level.setSurface(x, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        }

        LandRoadHybridPathfinder.ConstructibleRoute route = LandRoadHybridPathfinder.findConstructibleRouteForTest(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                Set.of(),
                Set.of(),
                new RoadPlanningPassContext(level)
        );

        assertTrue(route.success(), route.toString());
        assertTrue(route.segments().stream().anyMatch(segment -> segment.type() == LandRoadHybridPathfinder.ConstructibleSegmentType.FILL),
                route.toString());
    }

    @Test
    void plannerUsesShortArchBridgeForSmallWaterSpan() {
        TestTerrainLevel level = newLevel();
        for (int x = 0; x <= 10; x++) {
            level.setSurface(x, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        }
        for (int x = 4; x <= 6; x++) {
            level.surfaceHeights.put(columnKey(x, 0), 64);
            level.blockStates.put(new BlockPos(x, 64, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 63, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        LandRoadHybridPathfinder.ConstructibleRoute route = LandRoadHybridPathfinder.findConstructibleRouteForTest(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(10, 64, 0),
                Set.of(),
                Set.of(),
                new RoadPlanningPassContext(level)
        );

        assertTrue(route.success(), route.toString());
        assertTrue(route.segments().stream().anyMatch(segment -> segment.type() == LandRoadHybridPathfinder.ConstructibleSegmentType.SHORT_ARCH_BRIDGE),
                route.toString());
    }

    @Test
    void plannerAllowsTunnelWhenMountainWallBlocksSurfacePath() {
        TestTerrainLevel level = newLevel();
        for (int x = 0; x <= 12; x++) {
            level.setSurface(x, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        }
        for (int x = 5; x <= 7; x++) {
            for (int y = 65; y <= 70; y++) {
                level.blockStates.put(new BlockPos(x, y, 0).asLong(), Blocks.STONE.defaultBlockState());
            }
        }

        LandRoadHybridPathfinder.ConstructibleRoute route = LandRoadHybridPathfinder.findConstructibleRouteForTest(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(12, 64, 0),
                Set.of(),
                Set.of(),
                new RoadPlanningPassContext(level)
        );

        assertTrue(route.success(), route.toString());
        assertTrue(route.segments().stream().anyMatch(segment -> segment.type() == LandRoadHybridPathfinder.ConstructibleSegmentType.TUNNEL),
                route.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (T) unsafe.allocateInstance(type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static TestTerrainLevel newLevel() {
        TestTerrainLevel level = allocate(TestTerrainLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        return level;
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    static final class TestTerrainLevel extends ServerLevel {
        private Map<Long, BlockState> blockStates;
        private Map<Long, Integer> surfaceHeights;
        private Holder<Biome> biome;

        private TestTerrainLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, java.util.List.of(), false, null);
        }

        void setSurface(int x, int z, int y, BlockState surface, BlockState above) {
            this.surfaceHeights.put(columnKey(x, z), y);
            this.blockStates.put(new BlockPos(x, y, z).asLong(), surface);
            this.blockStates.put(new BlockPos(x, y + 1, z).asLong(), above);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blockStates.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public BlockPos getHeightmapPos(Heightmap.Types heightmapType, BlockPos pos) {
            int surfaceY = this.surfaceHeights.getOrDefault(columnKey(pos.getX(), pos.getZ()), 0);
            return new BlockPos(pos.getX(), surfaceY + 1, pos.getZ());
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        public Holder<Biome> getBiome(BlockPos pos) {
            return biome;
        }
    }
}
