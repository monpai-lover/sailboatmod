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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlanningSnapshotBuilderTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void snapshotBuilderCachesRepeatedColumnSamplingInsideSingleBuild() {
        TestTerrainLevel level = allocate(TestTerrainLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        for (int x = -8; x <= 40; x++) {
            level.setSurface(x, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        }

        RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.buildForTest(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(32, 64, 0),
                Set.of(),
                Set.of()
        );

        assertNotNull(snapshot.column(0, 0));
        assertTrue(level.surfaceQueries() <= 320, () -> "too many surface queries: " + level.surfaceQueries());
    }

    @Test
    void islandClassifierMarksSmallDisconnectedWaterBoundLandmassAsIsland() {
        RoadPlanningIslandClassifier.IslandSummary summary = RoadPlanningIslandClassifier.classifyForTest(
                Set.of(
                        new BlockPos(20, 64, 20),
                        new BlockPos(21, 64, 20),
                        new BlockPos(20, 64, 21),
                        new BlockPos(21, 64, 21)
                ),
                Set.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(4, 64, 0),
                        new BlockPos(5, 64, 0)
                ),
                true
        );

        assertTrue(summary.isIslandLike());
    }

    @Test
    void snapshotBuilderMarksSourceIslandLikeForIslandToMainlandRoute() {
        TestTerrainLevel level = allocate(TestTerrainLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = -8; x <= 12; x++) {
            for (int z = -8; z <= 8; z++) {
                if (x <= 1 && Math.abs(z) <= 1) {
                    level.setSurface(x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
                } else if (x >= 5) {
                    level.setSurface(x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
                } else {
                    level.setSurface(x, z, 64, Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState());
                }
            }
        }

        RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.buildForTest(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(6, 64, 0),
                Set.of(),
                Set.of()
        );

        assertTrue(snapshot.sourceIslandLike());
        assertFalse(snapshot.targetIslandLike());
    }

    @Test
    void snapshotBuilderDoesNotMarkConnectedPeninsulaAsIsland() {
        TestTerrainLevel level = allocate(TestTerrainLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = -8; x <= 32; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setSurface(x, z, 64, Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = -2; x <= 20; x++) {
            level.setSurface(x, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                level.setSurface(x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = 20; x <= 32; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setSurface(x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
            }
        }

        RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.buildForTest(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(24, 64, 0),
                Set.of(),
                Set.of()
        );

        assertFalse(snapshot.sourceIslandLike(), "connected peninsula should not be classified as island");
        assertFalse(snapshot.targetIslandLike(), "same connected mainland should not be classified as island");
    }

    @Test
    void snapshotBuilderDoesNotMissOddOffsetOneBlockIsthmusConnection() {
        TestTerrainLevel level = allocate(TestTerrainLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = -8; x <= 32; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setSurface(x, z, 64, Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                level.setSurface(x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = -2; x <= 20; x++) {
            level.setSurface(x, 1, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        }
        for (int x = 20; x <= 32; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setSurface(x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
            }
        }

        RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.buildForTest(
                level,
                new BlockPos(0, 64, 1),
                new BlockPos(24, 64, 1),
                Set.of(),
                Set.of()
        );

        assertFalse(snapshot.sourceIslandLike(), "odd-offset isthmus should still count as connected mainland");
        assertFalse(snapshot.targetIslandLike(), "odd-offset isthmus should still count as connected mainland");
    }

    @Test
    void snapshotBuilderExposesDenseRibbonColumnsNeededByHybridFallback() {
        TestTerrainLevel level = allocate(TestTerrainLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        for (int x = -8; x <= 40; x++) {
            for (int z = -2; z <= 2; z++) {
                level.setSurface(x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
            }
        }

        RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.buildForTest(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(32, 64, 0),
                Set.of(),
                Set.of()
        );

        assertNotNull(snapshot.column(1, 0));
    }

    @Test
    void snapshotBuilderDoesNotMarkOffsetTownCoresAsIslandsWhenLandConnectionRunsOneBlockAside() {
        TestTerrainLevel level = allocate(TestTerrainLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = -8; x <= 32; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setSurface(x, z, 64, Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                level.setSurface(x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = -2; x <= 20; x++) {
            level.setSurface(x, 1, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        }
        for (int x = 20; x <= 32; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setSurface(x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
            }
        }

        RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.buildForTest(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(24, 64, 0),
                Set.of(),
                Set.of()
        );

        assertFalse(snapshot.sourceIslandLike(), "land corridor offset by one block should still keep source mainland");
        assertFalse(snapshot.targetIslandLike(), "land corridor offset by one block should still keep target mainland");
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
        private int surfaceQueries;

        private TestTerrainLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, java.util.List.of(), false, null);
        }

        void setSurface(int x, int z, int y, BlockState surface, BlockState above) {
            this.surfaceHeights.put(columnKey(x, z), y);
            this.blockStates.put(new BlockPos(x, y, z).asLong(), surface);
            this.blockStates.put(new BlockPos(x, y + 1, z).asLong(), above);
        }

        int surfaceQueries() {
            return surfaceQueries;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blockStates.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public BlockPos getHeightmapPos(Heightmap.Types heightmapType, BlockPos pos) {
            surfaceQueries++;
            int surfaceY = this.surfaceHeights.getOrDefault(columnKey(pos.getX(), pos.getZ()), 0);
            return new BlockPos(pos.getX(), surfaceY + 1, pos.getZ());
        }

        @Override
        public Holder<Biome> getBiome(BlockPos pos) {
            return biome;
        }
    }
}
