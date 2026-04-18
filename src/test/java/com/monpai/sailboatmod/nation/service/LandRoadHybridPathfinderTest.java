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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandRoadHybridPathfinderTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void hybridPathfinderReturnsContinuousLandOnlyPathOnBrokenSlopeFixture() {
        TestTerrainLevel level = allocate(TestTerrainLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.setSurface(0, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(1, 0, 65, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(2, 0, 66, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(3, 0, 66, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(4, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(5, 0, 68, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(6, 0, 68, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());

        RoadPathfinder.PlannedPathResult result = LandRoadHybridPathfinder.find(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(6, 68, 0),
                Set.of(),
                Set.of(),
                new RoadPlanningPassContext(level)
        );

        assertTrue(result.success());
        assertTrue(result.path().size() >= 2);
    }

    @Test
    void hybridPathfinderCanFinishOnShortStepIntoExcludedGoalColumn() {
        TestTerrainLevel level = allocate(TestTerrainLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.setSurface(0, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(1, 0, 65, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(2, 0, 66, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(3, 0, 66, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(4, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(5, 0, 68, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(6, 0, 69, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());

        RoadPathfinder.PlannedPathResult result = LandRoadHybridPathfinder.find(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(6, 69, 0),
                Set.of(),
                Set.of(columnKey(6, 0)),
                new RoadPlanningPassContext(level)
        );

        assertTrue(result.success(), () -> "expected excluded endpoint short-step recovery but got " + result.failureReason());
        assertEquals(new BlockPos(6, 69, 0), result.path().get(result.path().size() - 1));
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
