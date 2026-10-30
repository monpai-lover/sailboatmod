package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.TransportRegistry;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

class AirportPreflightTest {
    @TempDir Path directory;
    private TestLevel level;
    private AirportBlockEntity source, destination;
    private Aircraft aircraft;
    private final UUID attempt = UUID.randomUUID();
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @BeforeEach void setup() throws Exception {
        level = allocate(TestLevel.class); level.server = allocate(DedicatedServer.class);
        set(MinecraftServer.class, level.server, "levels", Map.of(Level.OVERWORLD, level));
        level.storage = new DimensionDataStorage(directory.toFile(), null, level.registryAccess());
        level.unloaded = new HashSet<>(); level.blocked = new HashSet<>(); level.time = 100;
        source = airport(new AirportFacility("source", AirportFacility.Type.HELIPAD, new BlockPos(0,64,0), new BlockPos(0,64,0), 7,8));
        destination = airport(new AirportFacility("destination", AirportFacility.Type.RUNWAY, new BlockPos(1000,64,0), new BlockPos(1120,64,0), 16,16));
        aircraft = new Aircraft();
    }
    @AfterEach void clear() { TransportRegistry.clear(level.server); }

    @Test void completedSourceSnapshotSurvivesWhileLongDestinationScanIsPending() throws Exception {
        assertTrue(departure().isPresent());
        long reads = level.sourceReads;
        for (int tick = 0; tick < 10; tick++) { level.time += 5; assertTrue(departure().isPresent()); landing(); }
        assertEquals(reads, level.sourceReads, "same preparation must not repeatedly restart the short source scan");
    }
    @Test void shortSourceLongDestinationAndReservationReachOneConsumableHandshake() throws Exception {
        boolean ready = false;
        for (int retry = 0; retry < 200 && !ready; retry++, level.time += 5) {
            var origin = departure(); var target = landing();
            if (origin.isPresent() && target.isPresent()) {
                assertTrue(reserve(target.get().id()), "reservation must consume the prepared scan without resetting it");
                ready = true;
            }
        }
        assertTrue(ready, "both sides must finish one preparation round");
        clearPrepared(source); clearPrepared(destination);
        assertEquals(1, destination.reservations().size(), "clearing preparation must retain the flight's destination lease");
    }
    @Test void preparedSnapshotStillChecksCurrentHullChunksAndOccupancy() throws Exception {
        assertTrue(departure().isPresent());
        aircraft.hull = aircraft.hull.move(0, 4, 0);
        assertTrue(departure().isEmpty());
        aircraft.hull = aircraft.hull.move(0, -4, 0); level.time += 5;
        assertTrue(departure().isPresent());
        level.unloaded.add(new ChunkPos(0,0));
        assertTrue(departure().isEmpty());
        level.unloaded.clear(); level.time += 5;
        assertTrue(departure().isPresent());
        Aircraft other = new Aircraft(); TransportRegistry.register(level, other.proxy);
        assertTrue(departure().isEmpty());
    }
    @Test void aNewAttemptCannotReuseAnEarlierSuccessfulTerrainSnapshot() throws Exception {
        assertTrue(departure().isPresent()); clearPrepared(source);
        level.blocked.add(new BlockPos(0,65,0));
        assertTrue(preparedDeparture(UUID.randomUUID()).isEmpty());
    }
    @Test void expiredRoundRechecksTerrainAndSameIntentCanRetryAfterRepair() throws Exception {
        assertTrue(departure().isPresent());
        level.time += AirportBlockEntity.PREFLIGHT_MAX_TICKS;
        level.blocked.add(new BlockPos(0,65,0));
        assertTrue(departure().isEmpty());
        level.blocked.clear(); level.time += 25;
        assertTrue(departure().isPresent());
    }
    @Test void preparedLandingCannotBeConsumedAfterAnotherAircraftOccupiesIt() throws Exception {
        Optional<AirportFacility> landing = Optional.empty();
        for (int retry = 0; retry < 200 && landing.isEmpty(); retry++, level.time += 5) landing = landing();
        assertTrue(landing.isPresent());
        Aircraft other = new Aircraft(); other.hull = other.hull.move(1060, 0, 0); TransportRegistry.register(level, other.proxy);
        assertFalse(reserve(landing.get().id()));
        assertTrue(destination.reservations().isEmpty());
    }
    @Test void blockedFirstFacilityDoesNotRestartTheNextFacilityEveryRetry() throws Exception {
        AirportFacility blocked = new AirportFacility("blocked", AirportFacility.Type.HELIPAD, new BlockPos(500,64,0), new BlockPos(500,64,0),7,8);
        Map<String, AirportFacility> facilities = new LinkedHashMap<>();
        facilities.put(blocked.id(), blocked); facilities.put("destination", destination.facilities().getFirst());
        set(AirportBlockEntity.class, destination, "facilities", facilities);
        level.blocked.add(new BlockPos(500,65,0));
        Optional<AirportFacility> landing = Optional.empty();
        for (int retry = 0; retry < 200 && landing.isEmpty(); retry++, level.time += 5) landing = landing();
        assertTrue(landing.isPresent());
        assertEquals("destination", landing.orElseThrow().id());
    }
    private Optional<AirportFacility> preparedDeparture(UUID id) {
        return source.preparedDeparture(id, aircraft.proxy, AircraftClass.ROTORCRAFT, 0);
    }
    private Optional<AirportFacility> departure() { return preparedDeparture(attempt); }
    private Optional<AirportFacility> landing() { return destination.preparedLanding(attempt, aircraft.id, AircraftClass.ROTORCRAFT); }
    private boolean reserve(String facilityId) { return destination.reservePreparedFacility(attempt, facilityId, aircraft.id, 6000); }
    private void clearPrepared(AirportBlockEntity airport) { airport.clearPreflight(attempt); }
    private AirportBlockEntity airport(AirportFacility facility) throws Exception {
        AirportBlockEntity airport = allocate(AirportBlockEntity.class);
        set(BlockEntity.class, airport, "worldPosition", facility.start()); airport.setLevel(level);
        set(BlockEntity.class, airport, "blockState", Blocks.AIR.defaultBlockState());
        set(DockBlockEntity.class, airport, "routes", new ArrayList<>());
        for (String field : List.of("dockName", "ownerName", "ownerUuid", "townId", "nationId")) set(DockBlockEntity.class, airport, field, "");
        for (Field field : AirportBlockEntity.class.getDeclaredFields()) if (Map.class.isAssignableFrom(field.getType())) {
            field.setAccessible(true); field.set(airport, new LinkedHashMap<>());
        }
        set(AirportBlockEntity.class, airport, "facilities", new LinkedHashMap<>(Map.of(facility.id(), facility)));
        set(AirportBlockEntity.class, airport, "reservations", new AirportReservationBook());
        set(AirportBlockEntity.class, airport, "budgetTick", Long.MIN_VALUE);
        return airport;
    }
    private static final class Aircraft implements InvocationHandler {
        final UUID id = UUID.randomUUID(); AABB hull = new AABB(-.5,64,-.5,1.5,66,1.5);
        final LogisticsCarrier proxy = (LogisticsCarrier) Proxy.newProxyInstance(LogisticsCarrier.class.getClassLoader(), new Class<?>[]{LogisticsCarrier.class, AirportApproachState.class}, this);
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch(method.getName()) {
                case "getTransportUuid" -> id; case "transportKind" -> LogisticsCarrier.Kind.AIR;
                case "dimensionId" -> "minecraft:overworld"; case "isTransportAlive" -> true;
                case "transportBounds" -> hull; case "transportPosition" -> hull.getCenter();
                case "physicalArrivalReady" -> true; case "aircraftClass" -> AircraftClass.ROTORCRAFT;
                case "velocityMetersPerSecond" -> Vec3.ZERO;
                default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
            };
        }
    }
    private static final class TestLevel extends ServerLevel {
        DedicatedServer server; DimensionDataStorage storage; Set<ChunkPos> unloaded; Set<BlockPos> blocked; long time, sourceReads;
        private TestLevel() { super(null, command -> {}, null, null, null, null, null, false, 0L, List.of(), false, null); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public DimensionDataStorage getDataStorage() { return storage; }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY); }
        @Override public void blockEntityChanged(BlockPos pos) { }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public boolean hasChunkAt(BlockPos pos) { return !unloaded.contains(new ChunkPos(pos)); }
        @Override public boolean hasChunk(int x, int z) { return !unloaded.contains(new ChunkPos(x,z)); }
        @Override public int getMinBuildHeight() { return 0; }
        @Override public int getHeight() { return 256; }
        @Override public long getGameTime() { return time; }
        @Override public <T extends Entity> List<T> getEntitiesOfClass(Class<T> type, AABB bounds, Predicate<? super T> filter) { return List.of(); }
        @Override public BlockState getBlockState(BlockPos pos) {
            assertTrue(hasChunkAt(pos), "must not read unloaded terrain");
            if (pos.getX() < 500) sourceReads++;
            return (pos.getY()==63 || blocked.contains(pos) ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
    }
    private static void set(Class<?> type, Object target, String name, Object value) throws Exception { Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(target,value); }
    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) throws Exception { Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);return (T)((Unsafe)field.get(null)).allocateInstance(type); }
}
