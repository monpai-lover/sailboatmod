package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.service.RoadPathfinder;
import net.minecraft.core.HolderLookup;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.Vec3;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadAutoRouteServiceTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void mergesStoredAndGeneratedRoutesWithoutDuplicatingShape() {
        RouteDefinition stored = new RouteDefinition(
                "Manual Route",
                List.of(new Vec3(0.5D, 64.0D, 0.5D), new Vec3(10.5D, 64.0D, 10.5D)),
                "Player",
                "uuid",
                1L,
                20.0D,
                "Start",
                "End"
        );
        RouteDefinition generatedDuplicate = new RouteDefinition(
                "Road Link: End",
                List.of(new Vec3(0.5D, 64.0D, 0.5D), new Vec3(10.5D, 64.0D, 10.5D)),
                "System",
                "",
                2L,
                20.0D,
                "Start",
                "End"
        );
        RouteDefinition generatedNew = new RouteDefinition(
                "Road Link: Other",
                List.of(new Vec3(0.5D, 64.0D, 0.5D), new Vec3(4.5D, 64.0D, 4.5D), new Vec3(8.5D, 64.0D, 8.5D)),
                "System",
                "",
                3L,
                16.0D,
                "Start",
                "Other"
        );

        List<RouteDefinition> merged = RoadAutoRouteService.mergeRoutesForTest(
                List.of(stored),
                List.of(generatedDuplicate, generatedNew)
        );

        assertEquals(2, merged.size());
        assertEquals("Manual Route", merged.get(0).name());
        assertEquals("Road Link: Other", merged.get(1).name());
    }

    @Test
    void prefersHybridNetworkResolutionOverLongerDirectFallback() {
        RoadAutoRouteService.RouteResolution direct = new RoadAutoRouteService.RouteResolution(
                RoadAutoRouteService.PathSource.LAND_TERRAIN,
                List.of(new net.minecraft.core.BlockPos(0, 64, 0), new net.minecraft.core.BlockPos(10, 64, 0))
        );
        RoadAutoRouteService.RouteResolution hybrid = new RoadAutoRouteService.RouteResolution(
                RoadAutoRouteService.PathSource.ROAD_NETWORK,
                List.of(
                        new net.minecraft.core.BlockPos(0, 64, 0),
                        new net.minecraft.core.BlockPos(4, 64, 0),
                        new net.minecraft.core.BlockPos(10, 64, 0)
                )
        );

        RoadAutoRouteService.RouteResolution chosen = RoadAutoRouteService.preferResolutionForTest(direct, hybrid);

        assertTrue(chosen.found());
        assertEquals(RoadAutoRouteService.PathSource.ROAD_NETWORK, chosen.source());
        assertEquals(3, chosen.path().size());
    }

    @Test
    void preferResolutionReturnsDirectLandPathWhenHybridFails() {
        RoadAutoRouteService.RouteResolution direct = new RoadAutoRouteService.RouteResolution(
                RoadAutoRouteService.PathSource.LAND_TERRAIN,
                List.of(new net.minecraft.core.BlockPos(0, 64, 0), new net.minecraft.core.BlockPos(4, 67, 0))
        );
        RoadAutoRouteService.RouteResolution hybrid = RoadAutoRouteService.RouteResolution.none();

        RoadAutoRouteService.RouteResolution chosen = RoadAutoRouteService.preferResolutionForTest(direct, hybrid);

        assertTrue(chosen.found());
        assertEquals(RoadAutoRouteService.PathSource.LAND_TERRAIN, chosen.source());
        assertEquals(2, chosen.path().size());
    }

    @Test
    void preferResolutionReturnsHybridLandPathWhenDirectFails() {
        RoadAutoRouteService.RouteResolution direct = RoadAutoRouteService.RouteResolution.none();
        RoadAutoRouteService.RouteResolution hybrid = new RoadAutoRouteService.RouteResolution(
                RoadAutoRouteService.PathSource.LAND_TERRAIN,
                List.of(new BlockPos(0, 64, 0), new BlockPos(2, 65, 0), new BlockPos(4, 67, 0))
        );

        RoadAutoRouteService.RouteResolution chosen = RoadAutoRouteService.preferResolutionForTest(direct, hybrid);

        assertTrue(chosen.found());
        assertEquals(RoadAutoRouteService.PathSource.LAND_TERRAIN, chosen.source());
        assertEquals(3, chosen.path().size());
    }

    @Test
    void resolveAutoRoutePreviewUsesSharedGroundRecoveryForDirectLandPath() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 6; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState());
        }
        setSurfaceColumn(level, 2, 1, 64, Blocks.GRASS_BLOCK.defaultBlockState());
        setSurfaceColumn(level, 3, 1, 64, Blocks.GRASS_BLOCK.defaultBlockState());
        setSurfaceColumn(level, 4, 1, 64, Blocks.GRASS_BLOCK.defaultBlockState());
        level.blockStates.put(new BlockPos(3, 65, 0).asLong(), Blocks.STONE.defaultBlockState());

        BlockPos requestedStart = new BlockPos(0, 64, 0);
        BlockPos requestedEnd = new BlockPos(6, 64, 0);
        List<BlockPos> sharedGroundPath = RoadPathfinder.findGroundPath(
                level,
                requestedStart,
                requestedEnd,
                java.util.Set.of(),
                java.util.Set.of()
        );

        RoadAutoRouteService.RouteResolution resolution = RoadAutoRouteService.resolveAutoRoutePreview(
                level,
                requestedStart,
                requestedEnd
        );

        assertTrue(sharedGroundPath.size() >= 2, "shared ground path should recover a valid route");
        assertFalse(
                sharedGroundPath.stream().anyMatch(pos -> pos.getX() == 3 && pos.getZ() == 0),
                "shared path should not traverse the obstructed column"
        );
        assertTrue(resolution.found(), "auto-route preview should recover through the shared ground solver");
        assertEquals(RoadAutoRouteService.PathSource.LAND_TERRAIN, resolution.source());
        assertTrue(resolution.path().size() >= 2, "auto-route preview should return a usable recovered route");
        assertEquals(requestedStart, resolution.path().get(0));
        assertEquals(requestedEnd, resolution.path().get(resolution.path().size() - 1));
        assertFalse(
                resolution.path().stream().anyMatch(pos -> pos.getX() == 3 && pos.getZ() == 0),
                "auto-route preview should not traverse the obstructed column"
        );
        assertTrue(
                resolution.path().stream().anyMatch(pos -> pos.getZ() == 1),
                "auto-route preview should recover by detouring around the obstructed column"
        );
    }

    @Test
    void resolveAutoRoutePreviewFallsBackToOrchestratedHybridLandRouteWhenDirectGroundFails() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 2; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState());
        }
        for (int x = 38; x <= 40; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState());
        }
        for (int x = 3; x <= 37; x++) {
            level.surfaceHeights.put(columnKey(x, 0), 64);
            level.blockStates.put(new BlockPos(x, 64, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 63, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 61, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        BlockPos requestedStart = new BlockPos(0, 64, 0);
        BlockPos requestedEnd = new BlockPos(40, 64, 0);

        List<BlockPos> directGroundPath = RoadPathfinder.findGroundPath(
                level,
                requestedStart,
                requestedEnd,
                java.util.Set.of(),
                java.util.Set.of()
        );

        RoadAutoRouteService.RouteResolution resolution = RoadAutoRouteService.resolveAutoRoutePreview(
                level,
                requestedStart,
                requestedEnd
        );

        assertTrue(directGroundPath.isEmpty(), "direct ground route should fail across the water span");
        assertTrue(resolution.found(), "auto-route preview should recover through orchestrated hybrid fallback");
        assertEquals(RoadAutoRouteService.PathSource.LAND_TERRAIN, resolution.source());
        assertEquals(requestedStart, resolution.path().get(0));
        assertEquals(requestedEnd, resolution.path().get(resolution.path().size() - 1));
        assertTrue(
                resolution.path().stream().anyMatch(pos -> pos.getX() >= 3 && pos.getX() <= 37 && pos.getY() >= 68),
                "auto-route preview should include elevated bridge-deck columns from the hybrid fallback"
        );
    }

    @Test
    void resolveAutoRoutePreviewPrefersRoadNetworkWhenOnlyShortTerrainConnectorsAreNeeded() {
        TestServerLevel level = newPersistentLevel();

        seedFlatGround(level, 0, 9, -1, 1, 64);
        seedRoad(level, new BlockPos(2, 64, 0), new BlockPos(7, 64, 0));

        RoadAutoRouteService.RouteResolution resolution = RoadAutoRouteService.resolveAutoRoutePreview(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(9, 64, 0)
        );

        assertTrue(resolution.found());
        assertEquals(RoadAutoRouteService.PathSource.ROAD_NETWORK, resolution.source());
        assertEquals(new BlockPos(0, 64, 0), resolution.path().get(0));
        assertEquals(new BlockPos(9, 64, 0), resolution.path().get(resolution.path().size() - 1));
    }

    @Test
    void resolveAutoRoutePreviewRejectsRouteWhenTerrainConnectorWouldExceedBound() {
        TestServerLevel level = newPersistentLevel();

        seedFlatGround(level, 0, 30, -1, 1, 64);
        seedRoad(level, new BlockPos(20, 64, 0), new BlockPos(28, 64, 0));

        RoadAutoRouteService.RouteResolution resolution = RoadAutoRouteService.resolveAutoRoutePreview(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(30, 64, 0)
        );

        assertFalse(resolution.found(), "carriage routes must not degrade into long off-road fallback");
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
            level.dataStorage = new DimensionDataStorage(Files.createTempDirectory("road-auto-route-test").toFile(), null);
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
