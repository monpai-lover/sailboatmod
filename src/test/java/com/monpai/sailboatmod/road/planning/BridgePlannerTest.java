package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.config.RoadConfig;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgePlannerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void bridgePlanAllowsIslandCrossingWithLongLandApproaches() {
        TestServerLevel level = newTestLevel();
        seedGround(level, 0, 100, -10, 10, 64);
        seedWater(level, 40, 50, -10, 10, 55, 63);

        BridgePlanner.BridgePlanResult result = new BridgePlanner(new RoadConfig())
                .plan(level, new BlockPos(0, 64, 0), new BlockPos(100, 64, 0), 3);

        assertTrue(result.success(), () -> result.failureReason()
                + " spans=" + result.bridgeSpans()
                + " pathSize=" + result.centerPath().size());
        assertFalse(result.bridgeSpans().isEmpty(), "A valid island crossing should keep the bridge candidate");
    }

    private static void seedGround(TestServerLevel level, int minX, int maxX, int minZ, int maxZ, int surfaceY) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                setBlock(level, x, surfaceY, z, Blocks.GRASS_BLOCK.defaultBlockState());
                setBlock(level, x, surfaceY - 1, z, Blocks.DIRT.defaultBlockState());
                level.setMotionBlockingHeight(x, z, surfaceY + 1);
            }
        }
    }

    private static void seedWater(TestServerLevel level,
                                  int minX,
                                  int maxX,
                                  int minZ,
                                  int maxZ,
                                  int floorY,
                                  int waterTopY) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                setBlock(level, x, floorY, z, Blocks.SAND.defaultBlockState());
                for (int y = floorY + 1; y <= waterTopY; y++) {
                    setBlock(level, x, y, z, Blocks.WATER.defaultBlockState());
                }
                setBlock(level, x, waterTopY + 1, z, Blocks.AIR.defaultBlockState());
                level.setMotionBlockingHeight(x, z, waterTopY + 1);
            }
        }
    }

    private static void setBlock(TestServerLevel level, int x, int y, int z, BlockState state) {
        level.blockStates.put(new BlockPos(x, y, z).asLong(), state);
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
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
        public BlockPos getHeightmapPos(Heightmap.Types heightmapType, BlockPos pos) {
            return new BlockPos(pos.getX(), getHeight(heightmapType, pos.getX(), pos.getZ()), pos.getZ());
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
