package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageRoutePlannerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void planReturnsConnectorRoadConnectorSegments() {
        TestServerLevel level = newPersistentLevel();

        seedFlatGround(level, 0, 9, -1, 1, 64);
        seedRoad(level, new BlockPos(2, 64, 0), new BlockPos(7, 64, 0));

        CarriageRoutePlan plan = CarriageRoutePlanner.plan(level, new BlockPos(0, 64, 0), new BlockPos(9, 64, 0));

        assertTrue(plan.found());
        assertEquals(List.of(
                CarriageRoutePlan.SegmentKind.TERRAIN_CONNECTOR,
                CarriageRoutePlan.SegmentKind.ROAD_CORRIDOR,
                CarriageRoutePlan.SegmentKind.TERRAIN_CONNECTOR
        ), plan.segments().stream().map(CarriageRoutePlan.Segment::kind).toList());
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private static void setSurfaceColumn(TestServerLevel level, int x, int z, int surfaceY, BlockState state) {
        level.surfaceHeights.put(columnKey(x, z), surfaceY);
        level.blockStates.put(new BlockPos(x, surfaceY, z).asLong(), state);
    }

    private static void seedFlatGround(TestServerLevel level,
                                       int minX,
                                       int maxX,
                                       int minZ,
                                       int maxZ,
                                       int surfaceY) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                setSurfaceColumn(level, x, z, surfaceY, Blocks.GRASS_BLOCK.defaultBlockState());
                level.blockStates.put(new BlockPos(x, surfaceY - 1, z).asLong(), Blocks.DIRT.defaultBlockState());
            }
        }
    }

    private static void seedRoad(TestServerLevel level, BlockPos from, BlockPos to) {
        List<BlockPos> path = buildStraightPath(from, to);
        for (BlockPos pos : path) {
            setSurfaceColumn(level, pos.getX(), pos.getZ(), pos.getY(), Blocks.GRASS_BLOCK.defaultBlockState());
            level.blockStates.put(pos.below().asLong(), Blocks.DIRT.defaultBlockState());
        }
        NationSavedData.get(level).putRoadNetwork(new RoadNetworkRecord(
                "test-road|" + from.asLong() + "|" + to.asLong(),
                "nation",
                "town",
                level.dimension().location().toString(),
                "a",
                "b",
                path,
                1L,
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        ));
    }

    private static List<BlockPos> buildStraightPath(BlockPos from, BlockPos to) {
        java.util.ArrayList<BlockPos> path = new java.util.ArrayList<>();
        if (from == null || to == null) {
            return path;
        }
        int dx = Integer.compare(to.getX(), from.getX());
        int dy = Integer.compare(to.getY(), from.getY());
        int dz = Integer.compare(to.getZ(), from.getZ());
        BlockPos cursor = from;
        path.add(cursor);
        while (!cursor.equals(to)) {
            cursor = cursor.offset(dx, dy, dz);
            path.add(cursor);
        }
        return List.copyOf(path);
    }

    private static TestServerLevel newPersistentLevel() {
        try {
            TestServerLevel level = allocate(TestServerLevel.class);
            level.blockStates = new HashMap<>();
            level.surfaceHeights = new HashMap<>();
            level.biome = Holder.direct(allocate(Biome.class));
            level.dimensionKey = Level.OVERWORLD;
            level.dataStorage = new DimensionDataStorage(Files.createTempDirectory("carriage-route-plan-test").toFile(), null);
            level.registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            TestMinecraftServer server = allocate(TestMinecraftServer.class);
            setField(MinecraftServer.class, server, "levels", new LinkedHashMap<>(Map.of(Level.OVERWORLD, level)));
            level.server = server;
            return level;
        } catch (Exception ex) {
            throw new AssertionError("Unable to create persistent test level", ex);
        }
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to set field " + owner.getSimpleName() + "." + name, ex);
        }
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

    private static final class TestServerLevel extends ServerLevel {
        private Map<Long, BlockState> blockStates;
        private Map<Long, Integer> surfaceHeights;
        private Holder<Biome> biome;
        private ResourceKey<Level> dimensionKey;
        private MinecraftServer server;
        private DimensionDataStorage dataStorage;
        private RegistryAccess registryAccess;

        private TestServerLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blockStates.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public BlockPos getHeightmapPos(Heightmap.Types heightmapType, BlockPos pos) {
            int surfaceY = surfaceHeights.getOrDefault(columnKey(pos.getX(), pos.getZ()), 63);
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

        @Override
        public ResourceKey<Level> dimension() {
            return dimensionKey == null ? Level.OVERWORLD : dimensionKey;
        }

        @Override
        public MinecraftServer getServer() {
            return server;
        }

        @Override
        public DimensionDataStorage getDataStorage() {
            return dataStorage;
        }

        @Override
        public RegistryAccess registryAccess() {
            return registryAccess == null
                    ? RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
                    : registryAccess;
        }

        @Override
        public <T> HolderLookup<T> holderLookup(ResourceKey<? extends net.minecraft.core.Registry<? extends T>> registryKey) {
            return registryAccess().lookupOrThrow(registryKey);
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
