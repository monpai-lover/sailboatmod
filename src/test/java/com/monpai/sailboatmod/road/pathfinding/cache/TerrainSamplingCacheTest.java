package com.monpai.sailboatmod.road.pathfinding.cache;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.construction.bridge.BridgeRangeDetector;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainSamplingCacheTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void underwaterPlantsDoNotCollapseWaterDepthForBridgeDetection() {
        TestServerLevel level = newTestLevel();
        for (int x = 0; x <= 2; x++) {
            seedWaterColumnWithKelp(level, x, 0, 55, 63, 60, 62);
        }

        TerrainSamplingCache cache = new TerrainSamplingCache(level,
                com.monpai.sailboatmod.road.config.PathfindingConfig.SamplingPrecision.HIGH);

        assertEquals(55, cache.getOceanFloor(1, 0),
                "Kelp should not become the sampled ocean floor");
        assertEquals(63, cache.getWaterSurfaceY(1, 0),
                "The top water column should still be detected above kelp");
        assertTrue(cache.isWater(1, 0),
                "A kelp-filled column should still count as water");
        assertEquals(8, cache.getWaterDepth(1, 0),
                "Kelp should not be treated as the ocean floor");

        List<BridgeSpan> spans = new BridgeRangeDetector(new BridgeConfig()).detect(List.of(
                new BlockPos(0, 55, 0),
                new BlockPos(1, 55, 0),
                new BlockPos(2, 55, 0)
        ), cache);

        assertFalse(spans.isEmpty(),
                "A kelp-filled water column should still be detected as a bridge span");
    }

    @Test
    void canopyNoiseIsSkippedWhenResolvingSurfaceHeight() {
        TestServerLevel level = newTestLevel();
        setBlock(level, 0, 64, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        setBlock(level, 0, 69, 0, Blocks.OAK_LOG.defaultBlockState());
        setBlock(level, 0, 70, 0, Blocks.OAK_LEAVES.defaultBlockState());
        level.setMotionBlockingHeight(0, 0, 71);

        TerrainSamplingCache cache = new TerrainSamplingCache(level,
                com.monpai.sailboatmod.road.config.PathfindingConfig.SamplingPrecision.HIGH);

        assertEquals(64, cache.getHeight(0, 0),
                "Tree canopy blocks should not become the sampled road surface");
    }

    private static void seedWaterColumnWithKelp(TestServerLevel level,
                                                int x,
                                                int z,
                                                int floorY,
                                                int waterTopY,
                                                int kelpBottomY,
                                                int kelpTopY) {
        setBlock(level, x, floorY, z, Blocks.SAND.defaultBlockState());
        for (int y = floorY + 1; y <= waterTopY; y++) {
            setBlock(level, x, y, z, Blocks.WATER.defaultBlockState());
        }
        for (int y = kelpBottomY; y < kelpTopY; y++) {
            setBlock(level, x, y, z, Blocks.KELP_PLANT.defaultBlockState());
        }
        setBlock(level, x, kelpTopY, z, Blocks.KELP.defaultBlockState());
        level.setMotionBlockingHeight(x, z, waterTopY + 1);
    }

    private static void setBlock(TestServerLevel level, int x, int y, int z, BlockState state) {
        level.blockStates.put(new BlockPos(x, y, z).asLong(), state);
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

    private static TestServerLevel newTestLevel() {
        try {
            TestServerLevel level = allocate(TestServerLevel.class);
            level.blockStates = new HashMap<>();
            level.motionBlockingHeights = new HashMap<>();
            level.biome = Holder.direct(allocate(Biome.class));
            level.dimensionKey = Level.OVERWORLD;
            level.server = allocate(TestMinecraftServer.class);
            return level;
        } catch (Exception ex) {
            throw new AssertionError("Unable to create test level", ex);
        }
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private static final class TestServerLevel extends ServerLevel {
        private Map<Long, BlockState> blockStates;
        private Map<Long, Integer> motionBlockingHeights;
        private Holder<Biome> biome;
        private net.minecraft.resources.ResourceKey<Level> dimensionKey;
        private MinecraftServer server;

        private TestServerLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        void setMotionBlockingHeight(int x, int z, int height) {
            motionBlockingHeights.put(columnKey(x, z), height);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blockStates.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public int getHeight(Heightmap.Types heightmapType, int x, int z) {
            return motionBlockingHeights.getOrDefault(columnKey(x, z), 64);
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        public int getMaxBuildHeight() {
            return 128;
        }

        @Override
        public int getSeaLevel() {
            return 63;
        }

        @Override
        public Holder<Biome> getBiome(BlockPos pos) {
            return biome;
        }

        @Override
        public net.minecraft.resources.ResourceKey<Level> dimension() {
            return dimensionKey == null ? Level.OVERWORLD : dimensionKey;
        }

        @Override
        public MinecraftServer getServer() {
            return server;
        }
    }

    private static final class TestMinecraftServer extends MinecraftServer {
        private TestMinecraftServer() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        protected boolean initServer() {
            return false;
        }

        @Override
        public int getOperatorUserPermissionLevel() {
            return 0;
        }

        @Override
        public int getFunctionCompilationLevel() {
            return 0;
        }

        @Override
        public boolean shouldRconBroadcast() {
            return false;
        }

        @Override
        public net.minecraft.SystemReport fillServerSystemReport(net.minecraft.SystemReport report) {
            return report;
        }

        @Override
        public boolean isDedicatedServer() {
            return false;
        }

        @Override
        public int getRateLimitPacketsPerSecond() {
            return 0;
        }

        @Override
        public boolean isEpollEnabled() {
            return false;
        }

        @Override
        public boolean isCommandBlockEnabled() {
            return false;
        }

        @Override
        public boolean isPublished() {
            return false;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        @Override
        public boolean isSingleplayerOwner(com.mojang.authlib.GameProfile profile) {
            return false;
        }
    }
}
