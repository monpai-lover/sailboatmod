package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandParkingGraphRouteTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockPos STATION_A = new BlockPos(8, 65, 44);
    private static final BlockPos STATION_B = new BlockPos(56, 65, 44);
    private static final BlockPos PARKING_A = new BlockPos(8, 64, 38);
    private static final BlockPos PARKING_B = new BlockPos(56, 64, 38);
    private static final List<BlockPos> FIRST_EDGE = List.of(
            new BlockPos(8, 64, 40), new BlockPos(18, 64, 40), new BlockPos(26, 64, 42));
    private static final List<BlockPos> SECOND_EDGE = List.of(
            FIRST_EDGE.getLast(), new BlockPos(41, 64, 42), new BlockPos(56, 64, 40));

    @TempDir Path saveDirectory;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void actualGraphKeepsCurvesAndConnectsParkingWithoutVisitingStationBlocks() throws Exception {
        Fixture fixture = fixture();
        var source = stationRef("station-a", "town-a", STATION_A, fixture.level);
        var target = stationRef("station-b", "town-b", STATION_B, fixture.level);
        var service = new LandTransportNetworkService();

        var outbound = service.planRouteBetweenStations(fixture.level, source, target, false);
        var returning = service.planRouteBetweenStations(fixture.level, target, source, false);

        assertTrue(outbound.reachable());
        assertTrue(returning.reachable());
        List<Vec3> expected = List.of(waypoint(PARKING_A), waypoint(FIRST_EDGE.getFirst()),
                waypoint(FIRST_EDGE.get(1)), waypoint(FIRST_EDGE.getLast()), waypoint(SECOND_EDGE.get(1)),
                waypoint(SECOND_EDGE.getLast()), waypoint(PARKING_B));
        assertEquals(expected, outbound.plan().route().waypoints());
        assertEquals(expected.reversed(), returning.plan().route().waypoints());
        assertEquals(source, outbound.plan().sourceStation());
        assertEquals(target, outbound.plan().targetStation());
        assertEquals(STATION_B, outbound.plan().targetStationPos());
        assertEquals(LandTransportNetworkService.RouteSource.ROAD_GRAPH, outbound.plan().source());
        assertEquals(List.of("graph-owner", "graph-owner"),
                fixture.graph.edgesForDimension(DIMENSION).stream().map(RoadGraphEdgeRecord::ownerNationId).toList());
    }

    @Test
    void missingParkingSurfaceDoesNotProducePhysicalRouteFromStationBlock() throws Exception {
        Fixture fixture = fixture();
        fixture.level.parkingGround.remove(PARKING_B);

        var result = new LandTransportNetworkService().planRouteBetweenStations(fixture.level,
                stationRef("station-a", "town-a", STATION_A, fixture.level),
                stationRef("station-b", "town-b", STATION_B, fixture.level), false);

        assertFalse(result.reachable());
        assertEquals(LandTransportNetworkService.RouteFailureReason.NO_ROAD_ROUTE, result.failureReason());
    }

    @Test
    void parkingRouteDoesNotBypassStationNationPermissions() throws Exception {
        Fixture fixture = fixture();
        setField(DockBlockEntity.class, fixture.level.stations.get(STATION_B), "nationId", "other-nation");

        var result = new LandTransportNetworkService().planRouteBetweenStations(fixture.level,
                stationRef("station-a", "town-a", STATION_A, fixture.level),
                stationRef("station-b", "town-b", STATION_B, fixture.level), false);

        assertFalse(result.reachable());
        assertEquals(LandTransportNetworkService.RouteFailureReason.NO_PERMISSION, result.failureReason());
    }

    private Fixture fixture() throws Exception {
        FixtureLevel level = allocate(FixtureLevel.class);
        level.stations = new HashMap<>();
        level.parkingGround = new HashSet<>(Set.of(PARKING_A, PARKING_B));
        level.server = allocate(DedicatedServer.class);
        setField(MinecraftServer.class, level.server, "levels", Map.of(Level.OVERWORLD, level));
        level.storage = new DimensionDataStorage(saveDirectory.toFile(), null, level.registryAccess());
        addStation(level, STATION_A);
        addStation(level, STATION_B);

        var graph = RoadGraphRepository.forLevel(level);
        var nodeA = node(FIRST_EDGE.getFirst());
        var junction = node(FIRST_EDGE.getLast());
        var nodeB = node(SECOND_EDGE.getLast());
        for (var node : List.of(nodeA, junction, nodeB)) {
            graph.putNode(node);
        }
        graph.putEdge(edge(nodeA, junction, FIRST_EDGE));
        graph.putEdge(edge(junction, nodeB, SECOND_EDGE));
        return new Fixture(level, graph);
    }

    private static void addStation(FixtureLevel level, BlockPos pos) throws Exception {
        var station = allocate(PostStationBlockEntity.class);
        setField(BlockEntity.class, station, "worldPosition", pos);
        station.setLevel(level);
        setField(DockBlockEntity.class, station, "nationId", "same-nation");
        setField(DockBlockEntity.class, station, "zoneMinX", 0);
        setField(DockBlockEntity.class, station, "zoneMaxX", 0);
        setField(DockBlockEntity.class, station, "zoneMinZ", -6);
        setField(DockBlockEntity.class, station, "zoneMaxZ", -6);
        level.stations.put(pos, station);
    }

    private static LandTransportNetworkService.StationRef stationRef(String id, String townId, BlockPos pos, ServerLevel level) {
        return new LandTransportNetworkService.StationRef(id, townId, townId, pos, id, level);
    }

    private static RoadGraphNodeRecord node(BlockPos pos) {
        return new RoadGraphNodeRecord(UUID.randomUUID(), DIMENSION, pos,
                RoadGraphNodeRecord.Kind.NORMAL, "graph-owner", "", "", 1L, 1L);
    }

    private static RoadGraphEdgeRecord edge(RoadGraphNodeRecord from, RoadGraphNodeRecord to, List<BlockPos> centerline) {
        return new RoadGraphEdgeRecord(UUID.randomUUID(), from.nodeId(), to.nodeId(), DIMENSION,
                "graph-owner", "", "", "", "", "", "test-road", 3, CompiledRoadSectionType.ROAD,
                RoadGraphEdgeRecord.Status.BUILT, centerline, List.of(from.pos(), to.pos()),
                List.of(), List.of(), List.of(), 1L, 1L);
    }

    private static Vec3 waypoint(BlockPos ground) {
        return new Vec3(ground.getX() + 0.5, ground.getY() + 1.05, ground.getZ() + 0.5);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void setField(Class<?> declaring, Object target, String name, Object value) throws Exception {
        Field field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(FixtureLevel level, RoadGraphRepository graph) {}

    private static final class FixtureLevel extends ServerLevel {
        private DedicatedServer server;
        private DimensionDataStorage storage;
        private Map<BlockPos, BlockEntity> stations;
        private Set<BlockPos> parkingGround;

        private FixtureLevel() {
            super(null, command -> {}, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override public MinecraftServer getServer() { return server; }
        @Override public DimensionDataStorage getDataStorage() { return storage; }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return stations.get(pos); }
        @Override public boolean hasChunkAt(BlockPos pos) { return true; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return (parkingGround.contains(pos) ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
        @Override public BlockPos getHeightmapPos(Heightmap.Types heightmap, BlockPos pos) {
            return new BlockPos(pos.getX(), 65, pos.getZ());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
    }
}
