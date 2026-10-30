package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.market.*;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
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
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketPickupSourceTest {
    private static final String BUYER = "00000000-0000-0000-0000-000000000002";
    private static final BlockPos WAREHOUSE_A = new BlockPos(0, 64, 0), WAREHOUSE_B = new BlockPos(1000, 64, 0);
    private static final BlockPos AIRPORT_A = new BlockPos(32, 64, 0), AIRPORT_B = new BlockPos(1032, 64, 0);
    @TempDir Path temporaryDirectory;
    private TestLevel level;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @AfterEach void clearRegistry() { if (level != null) AirportRegistry.clear(level.server); }

    @Test void foreignMarketBroadcastCannotLoadReservedGoodsAtAnotherTownsAirport() throws Exception {
        Fixture f = fixture();
        assertFalse(f.marketB.tryLoadPickupCargo(f.aircraft.proxy));
        assertEquals(0, f.aircraft.loaded);
        assertTrue(f.aircraft.manifest.isEmpty());
        assertEquals(PickupLock.STATUS_LOCKED, f.data.getPurchaseOrder("order-b").status());
        assertEquals(4, f.data.getListing("listing-b").reservedCount());
        assertTrue(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        assertEquals(4, f.aircraft.loaded);
        assertEquals("order-a", f.aircraft.manifest.getFirst().purchaseOrderId());
        assertEquals("IN_TRANSIT", f.data.getPurchaseOrder("order-a").status());
        assertEquals(PickupLock.STATUS_LOCKED, f.data.getPurchaseOrder("order-b").status());
    }
    @Test void flyingOverCorrectAirportOrMovingQuicklyCannotPickUp() throws Exception {
        Fixture f = fixture();
        f.aircraft.hull = hull(AIRPORT_A).move(0, 10, 0);
        assertFalse(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        f.aircraft.hull = hull(AIRPORT_A); f.aircraft.velocity = new Vec3(0, 2, 0);
        assertFalse(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        assertEquals(0, f.aircraft.loaded);
        assertEquals(PickupLock.STATUS_LOCKED, f.data.getPurchaseOrder("order-a").status());
    }
    @Test void virtualOrWrongDimensionCarrierCannotLoadAtMatchingCoordinates() throws Exception {
        Fixture f = fixture();
        f.aircraft.physical = false;
        assertFalse(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        f.aircraft.physical = true; f.aircraft.dimension = "minecraft:the_nether";
        assertFalse(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        assertEquals(0, f.aircraft.loaded);
    }
    @Test void unloadedSourceWarehouseIsNotReadOrTreatedAsAvailable() throws Exception {
        Fixture f = fixture(); level.unloaded.add(new ChunkPos(WAREHOUSE_A));
        assertFalse(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        assertEquals(0, f.aircraft.loaded);
    }
    @Test void staleUnloadedAirportRegistryEntryCannotLoadCargo() throws Exception {
        Fixture f = fixture(); level.unloaded.add(new ChunkPos(AIRPORT_A));
        assertFalse(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        assertEquals(0, f.aircraft.loaded);
    }
    @Test void incompatibleAircraftCannotLoadAtHelipad() throws Exception {
        Fixture f = fixture(); f.aircraft.airframe = AircraftClass.FIXED_WING;
        assertFalse(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        assertEquals(0, f.aircraft.loaded);
    }
    @Test void legacyDirectPortLinkLoadsOnlyAtThatPortAndKeepsWaterPickupWorking() throws Exception {
        Fixture f = fixture();
        DockBlockEntity port = allocate(DockBlockEntity.class);
        set(BlockEntity.class, port, "worldPosition", WAREHOUSE_A); port.setLevel(level);
        for (String field : List.of("zoneMinX", "zoneMinZ", "zoneMaxX", "zoneMaxZ")) set(DockBlockEntity.class, port, field, field.contains("Min") ? -8 : 8);
        level.entities.put(WAREHOUSE_A, port);
        f.aircraft.kind = LogisticsCarrier.Kind.WATER;
        assertFalse(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        f.aircraft.hull = hull(WAREHOUSE_A);
        assertTrue(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        assertEquals(4, f.aircraft.loaded);
        assertEquals("IN_TRANSIT", f.data.getPurchaseOrder("order-a").status());
    }
    @Test void sourceTownWithoutMatchingLoadedTerminalFailsClosed() throws Exception {
        Fixture f = fixture(); AirportRegistry.unregister(level, AIRPORT_A);
        assertFalse(f.marketA.tryLoadPickupCargo(f.aircraft.proxy));
        assertEquals(0, f.aircraft.loaded);
    }

    private Fixture fixture() throws Exception {
        level = allocate(TestLevel.class);
        level.entities = new LinkedHashMap<>(); level.unloaded = new HashSet<>(); level.time = 100;
        level.server = allocate(DedicatedServer.class);
        set(MinecraftServer.class, level.server, "levels", Map.of(Level.OVERWORLD, level));
        level.storage = new DimensionDataStorage(temporaryDirectory.toFile(), null, level.registryAccess());
        for (var entry : Map.of(WAREHOUSE_A, "town-a", WAREHOUSE_B, "town-b").entrySet()) {
            TownWarehouseBlockEntity warehouse = allocate(TownWarehouseBlockEntity.class);
            set(BlockEntity.class, warehouse, "worldPosition", entry.getKey()); warehouse.setLevel(level);
            set(TownWarehouseBlockEntity.class, warehouse, "townId", entry.getValue()); level.entities.put(entry.getKey(), warehouse);
        }
        addAirport(AIRPORT_A, "town-a"); addAirport(AIRPORT_B, "town-b");
        MarketSavedData data = MarketSavedData.get(level);
        seed(data, "a", WAREHOUSE_A); seed(data, "b", WAREHOUSE_B);
        return new Fixture(market(WAREHOUSE_A), market(WAREHOUSE_B), data, new Aircraft());
    }
    private void addAirport(BlockPos position, String town) throws Exception {
        AirportBlockEntity airport = allocate(AirportBlockEntity.class);
        set(BlockEntity.class, airport, "worldPosition", position); airport.setLevel(level);
        set(DockBlockEntity.class, airport, "townId", town);
        var facility = new AirportFacility("pad", AirportFacility.Type.HELIPAD, position, position, 7, 8);
        set(AirportBlockEntity.class, airport, "facilities", new LinkedHashMap<>(Map.of("pad", facility)));
        for (String field : List.of("validation", "reservationChecks", "arrivalChecks")) set(AirportBlockEntity.class, airport, field, new HashMap<>());
        set(AirportBlockEntity.class, airport, "reservations", new AirportReservationBook());
        set(AirportBlockEntity.class, airport, "budgetTick", Long.MIN_VALUE);
        level.entities.put(position, airport); AirportRegistry.register(level, position);
    }
    private MarketBlockEntity market(BlockPos warehouse) throws Exception {
        MarketBlockEntity market = allocate(MarketBlockEntity.class); market.setLevel(level);
        set(MarketBlockEntity.class, market, "linkedDockPos", warehouse); return market;
    }
    private static void seed(MarketSavedData data, String suffix, BlockPos source) {
        data.putListing(new MarketListing("listing-" + suffix, "seller", "Seller", new ItemStack(Items.IRON_INGOT), 10, 0, 4, source, "Source", "", "", 0, ""));
        data.putPurchaseOrder(new PurchaseOrder("order-" + suffix, "listing-" + suffix, BUYER, "Buyer", 4, 40, source, "Source", new BlockPos(2000,64,0), "Target", PickupLock.STATUS_LOCKED, "REAL_PICKUP", new BlockPos(2000,64,0)));
    }
    private static AABB hull(BlockPos pos) { return new AABB(pos.getX()-.5,64,pos.getZ()-.5,pos.getX()+1.5,66,pos.getZ()+1.5); }
    private record Fixture(MarketBlockEntity marketA, MarketBlockEntity marketB, MarketSavedData data, Aircraft aircraft) { }
    private static final class Aircraft implements InvocationHandler {
        final UUID uuid = UUID.randomUUID(); int loaded; AABB hull = hull(AIRPORT_A); Vec3 velocity = Vec3.ZERO; boolean physical = true;
        LogisticsCarrier.Kind kind = LogisticsCarrier.Kind.AIR; AircraftClass airframe = AircraftClass.ROTORCRAFT; String dimension = "minecraft:overworld";
        List<ShipmentManifestEntry> manifest = List.of();
        final LogisticsCarrier proxy = (LogisticsCarrier) Proxy.newProxyInstance(LogisticsCarrier.class.getClassLoader(), new Class<?>[]{LogisticsCarrier.class, AirportApproachState.class}, this);
        @SuppressWarnings("unchecked") public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getTransportUuid" -> uuid; case "getOwnerUuid" -> BUYER; case "transportKind" -> kind; case "transportMode" -> "HELICOPTER";
                case "dimensionId" -> dimension; case "isTransportAlive" -> true; case "transportBounds" -> hull; case "transportPosition" -> hull.getCenter();
                case "aircraftClass" -> airframe; case "velocityMetersPerSecond" -> velocity; case "physicalArrivalReady" -> physical;
                case "canLoadCargo" -> true;
                case "loadCargo" -> { loaded += ((List<ItemStack>) args[0]).stream().mapToInt(ItemStack::getCount).sum(); yield true; }
                case "setPendingShipmentManifest" -> { manifest = List.copyOf((List<ShipmentManifestEntry>) args[0]); yield null; }
                case "getPendingShipmentManifest" -> manifest;
                default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
            };
        }
    }
    private static final class TestLevel extends ServerLevel {
        DedicatedServer server; DimensionDataStorage storage; Map<BlockPos, BlockEntity> entities; Set<ChunkPos> unloaded; long time;
        private TestLevel() { super(null, command -> {}, null, null, null, null, null, false, 0L, List.of(), false, null); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public DimensionDataStorage getDataStorage() { return storage; }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public boolean hasChunkAt(BlockPos pos) { return !unloaded.contains(new ChunkPos(pos)); }
        @Override public boolean hasChunk(int x, int z) { return !unloaded.contains(new ChunkPos(x,z)); }
        @Override public int getMinBuildHeight() { return 0; }
        @Override public int getHeight() { return 256; }
        @Override public long getGameTime() { return time; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { if (!hasChunkAt(pos)) throw new AssertionError("must not read unloaded source: " + pos); return entities.get(pos); }
        @Override public BlockState getBlockState(BlockPos pos) { if (!hasChunkAt(pos)) throw new AssertionError("must not read unloaded terrain"); return (pos.getY()==63 ? Blocks.STONE : Blocks.AIR).defaultBlockState(); }
        @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
    }
    private static void set(Class<?> type, Object target, String name, Object value) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) throws Exception { Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);return (T)((Unsafe)field.get(null)).allocateInstance(type); }
}
