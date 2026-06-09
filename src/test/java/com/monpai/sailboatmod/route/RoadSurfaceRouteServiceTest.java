package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimAccessLevel;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
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
import net.minecraft.world.level.ChunkPos;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadSurfaceRouteServiceTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void continuousRoadSurfaceConnectsStationsThroughTownClaimedRoadEnds() {
        TestRoadWorld world = new TestRoadWorld()
                .claim("town-a", 0, 0)
                .claim("town-c", 2, 0)
                .townAt(new BlockPos(0, 64, 0), "town-a")
                .townAt(new BlockPos(40, 64, 0), "town-c")
                .roadLine(new BlockPos(4, 64, 0), new BlockPos(36, 64, 0));

        List<BlockPos> route = RoadSurfaceRouteService.routeForTest(
                world,
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0)
        );

        assertFalse(route.isEmpty());
        assertEquals(new BlockPos(0, 64, 0), route.get(0));
        assertEquals(new BlockPos(40, 64, 0), route.get(route.size() - 1));
        assertTrue(route.contains(new BlockPos(4, 64, 0)));
        assertTrue(route.contains(new BlockPos(36, 64, 0)));
        assertTrue(route.subList(1, route.size() - 1).stream().allMatch(world::isWalkableRoadSurface));
    }

    @Test
    void shortBrokenRoadSurfaceCanBridgeRoadEnds() {
        TestRoadWorld world = new TestRoadWorld()
                .claim("town-a", 0, 0)
                .claim("town-c", 2, 0)
                .townAt(new BlockPos(0, 64, 0), "town-a")
                .townAt(new BlockPos(40, 64, 0), "town-c")
                .roadLine(new BlockPos(4, 64, 0), new BlockPos(19, 64, 0))
                .roadLine(new BlockPos(21, 64, 0), new BlockPos(36, 64, 0));

        List<BlockPos> route = RoadSurfaceRouteService.routeForTest(
                world,
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0)
        );

        assertFalse(route.isEmpty());
        assertTrue(route.contains(new BlockPos(19, 64, 0)));
        assertTrue(route.contains(new BlockPos(20, 64, 0)));
        assertTrue(route.contains(new BlockPos(21, 64, 0)));
    }

    @Test
    void longBrokenRoadSurfaceDoesNotConnectStations() {
        TestRoadWorld world = new TestRoadWorld()
                .claim("town-a", 0, 0)
                .claim("town-c", 2, 0)
                .townAt(new BlockPos(0, 64, 0), "town-a")
                .townAt(new BlockPos(40, 64, 0), "town-c")
                .roadLine(new BlockPos(4, 64, 0), new BlockPos(16, 64, 0))
                .roadLine(new BlockPos(24, 64, 0), new BlockPos(36, 64, 0));

        List<BlockPos> route = RoadSurfaceRouteService.routeForTest(
                world,
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0)
        );

        assertTrue(route.isEmpty());
    }

    @Test
    void roadSurfaceMustEnterBothTownClaimsBeforeStationsCanAttach() {
        TestRoadWorld world = new TestRoadWorld()
                .claim("town-a", 0, 0)
                .claim("town-c", 2, 0)
                .townAt(new BlockPos(0, 64, 0), "town-a")
                .townAt(new BlockPos(40, 64, 0), "town-c")
                .roadLine(new BlockPos(17, 64, 0), new BlockPos(31, 64, 0));

        List<BlockPos> route = RoadSurfaceRouteService.routeForTest(
                world,
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0)
        );

        assertTrue(route.isEmpty());
    }

    @Test
    void autoRoutePreviewUsesBuiltRoadSurfaceWhenRoadGraphHasNoNodes() {
        TestServerLevel level = newPersistentLevel();
        seedTown(level, "town-a", "Alpha", 0, 0);
        seedTown(level, "town-c", "Cedar", 2, 0);
        seedRoadSurface(level, new BlockPos(4, 64, 0), new BlockPos(36, 64, 0));

        RoadAutoRouteService.RouteResolution resolution = RoadAutoRouteService.resolveAutoRoutePreview(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0)
        );

        assertTrue(resolution.found());
        assertEquals(RoadAutoRouteService.PathSource.ROAD_NETWORK, resolution.source());
        assertTrue(resolution.path().contains(new BlockPos(4, 64, 0)));
        assertTrue(resolution.path().contains(new BlockPos(36, 64, 0)));
    }

    private static final class TestRoadWorld implements RoadSurfaceRouteService.RoadSurfaceWorld {
        private final Map<BlockPos, String> towns = new HashMap<>();
        private final Map<String, List<ChunkPos>> claims = new HashMap<>();
        private final Set<BlockPos> roadSurfaces = new HashSet<>();

        TestRoadWorld townAt(BlockPos pos, String townId) {
            towns.put(pos.immutable(), townId);
            return this;
        }

        TestRoadWorld claim(String townId, int chunkX, int chunkZ) {
            claims.computeIfAbsent(townId, ignored -> new java.util.ArrayList<>()).add(new ChunkPos(chunkX, chunkZ));
            return this;
        }

        TestRoadWorld roadLine(BlockPos from, BlockPos to) {
            int dx = Integer.compare(to.getX(), from.getX());
            int dz = Integer.compare(to.getZ(), from.getZ());
            BlockPos cursor = from;
            roadSurfaces.add(cursor.immutable());
            while (!cursor.equals(to)) {
                cursor = cursor.offset(dx, 0, dz);
                roadSurfaces.add(cursor.immutable());
            }
            return this;
        }

        @Override
        public String townIdAt(BlockPos pos) {
            return towns.getOrDefault(pos, "");
        }

        @Override
        public List<ChunkPos> claimedChunks(String townId) {
            return claims.getOrDefault(townId, List.of());
        }

        @Override
        public boolean hasChunkAt(BlockPos pos) {
            return true;
        }

        @Override
        public boolean isWalkableRoadSurface(BlockPos pos) {
            return roadSurfaces.contains(pos);
        }

        @Override
        public int surfaceY(int x, int z, int fallbackY) {
            return fallbackY;
        }

        @Override
        public int minBuildHeight() {
            return 0;
        }

        @Override
        public int maxBuildHeight() {
            return 256;
        }
    }

    private static void seedTown(TestServerLevel level, String townId, String townName, int chunkX, int chunkZ) {
        NationSavedData data = NationSavedData.get(level);
        data.putTown(new TownRecord(townId, "nation", townName, UUID.randomUUID(), 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putClaim(new NationClaimRecord(
                level.dimension().location().toString(),
                chunkX,
                chunkZ,
                "nation",
                townId,
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                1L
        ));
    }

    private static void seedRoadSurface(TestServerLevel level, BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        BlockPos cursor = from;
        setSurfaceColumn(level, cursor.getX(), cursor.getZ(), cursor.getY(), Blocks.STONE_BRICKS.defaultBlockState());
        while (!cursor.equals(to)) {
            cursor = cursor.offset(dx, 0, dz);
            setSurfaceColumn(level, cursor.getX(), cursor.getZ(), cursor.getY(), Blocks.STONE_BRICKS.defaultBlockState());
        }
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private static void setSurfaceColumn(TestServerLevel level, int x, int z, int surfaceY, BlockState state) {
        level.surfaceHeights.put(columnKey(x, z), surfaceY);
        level.blockStates.put(new BlockPos(x, surfaceY, z).asLong(), state);
    }

    private static TestServerLevel newPersistentLevel() {
        try {
            TestServerLevel level = allocate(TestServerLevel.class);
            level.blockStates = new HashMap<>();
            level.surfaceHeights = new HashMap<>();
            level.biome = Holder.direct(allocate(Biome.class));
            level.dimensionKey = Level.OVERWORLD;
            level.dataStorage = new DimensionDataStorage(Files.createTempDirectory("road-surface-route-test").toFile(), null);
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
        public boolean hasChunkAt(BlockPos pos) {
            return true;
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        public int getMaxBuildHeight() {
            return 256;
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
