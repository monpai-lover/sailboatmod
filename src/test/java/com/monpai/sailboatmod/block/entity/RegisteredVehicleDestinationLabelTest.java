package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.transport.LogisticsCarrier.Kind;
import com.monpai.sailboatmod.transport.control.ControllerRegistration;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.*;

class RegisteredVehicleDestinationLabelTest {
    private static final BlockPos END = new BlockPos(8000, 64, 8000);
    private static final BlockPos WRONG_ELEVATION = END.offset(10, 236, 0);
    private static final BlockPos ACTUAL = END.offset(45, 0, 0);
    private static final String ORIGINAL = "Original route destination";
    private static final String EXPECTED = "Renamed real destination";

    @TempDir Path directory;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void landMenuUsesTheSameLoadedThreeDimensionalDestinationAsDispatch() throws Exception {
        assertMatchesDispatch(Kind.LAND);
    }

    @Test
    void seaMenuUsesTheSameLoadedThreeDimensionalDestinationAsDispatch() throws Exception {
        assertMatchesDispatch(Kind.WATER);
    }

    @Test
    void anUnloadedDestinationKeepsTheOriginalRouteText() throws Exception {
        try (Fixture fixture = fixture(Kind.LAND)) {
            fixture.level.loaded.remove(new ChunkPos(ACTUAL));
            assertNull(fixture.resolveDispatch());
            assertEquals(ORIGINAL, fixture.label());
        }
    }

    @Test
    void seaAndLandPickerUsePhysicalSourceInsteadOfSavedBinding() throws Exception {
        for (Kind kind : List.of(Kind.WATER, Kind.LAND)) {
            try (Fixture fixture = fixture(kind)) {
                fixture.parkAt(ACTUAL, Vec3.ZERO);
                BlockPos savedSource = END.offset(-256, 0, 0);
                CompoundTag departure = fixture.knownDeparture();
                assertEquals(ACTUAL.asLong(), departure.getLong("Origin"));
                assertEquals(EXPECTED, departure.getString("OriginName"));
                assertEquals(savedSource, get(fixture.controller, RegisteredVehicleControllerBlockEntity.class,
                        "sourceTerminal"), "Listing destinations must not change the stored binding");
                assertFalse(fixture.startKnownDeparture(savedSource, ACTUAL));
                assertEquals("known_departure_changed", get(fixture.controller,
                        RegisteredVehicleControllerBlockEntity.class, "reason"));
            }
        }
    }

    @Test
    void movingOrVerticallySeparatedVehicleCannotAcquireAStationFromItsHorizontalPosition() throws Exception {
        try (Fixture fixture = fixture(Kind.WATER)) {
            fixture.parkAt(ACTUAL, new Vec3(2, 0, 0));
            assertFalse(fixture.knownDeparture().contains("Origin"));
            fixture.parkAt(ACTUAL.above(48), Vec3.ZERO);
            assertEquals("known_departure_park", fixture.knownDeparture().getString("Reason"));
        }
    }

    @Test
    void overlappingLoadedParkingZonesRequireAnUnambiguousSource() throws Exception {
        try (Fixture fixture = fixture(Kind.LAND)) {
            fixture.addTerminal(ACTUAL.east(2), "Overlapping station");
            fixture.parkAt(ACTUAL, Vec3.ZERO);
            CompoundTag departure = fixture.knownDeparture();
            assertFalse(departure.contains("Origin"));
            assertEquals("known_departure_ambiguous", departure.getString("Reason"));
        }
    }

    @Test
    void knownVoyageDoesNotDeliverToAReplacementOfTheWrongTerminalKind() throws Exception {
        try (Fixture fixture = fixture(Kind.WATER)) {
            var replacement = allocate(PostStationBlockEntity.class);
            set(replacement, BlockEntity.class, "worldPosition", ACTUAL);
            set(replacement, BlockEntity.class, "level", fixture.level);
            var original = fixture.level.entities.put(ACTUAL, replacement);
            set(fixture.controller, RegisteredVehicleControllerBlockEntity.class, "destinationTerminal", ACTUAL);
            assertSame(replacement, fixture.resolveDispatch(), "Legacy route resolution has its existing behavior");
            set(fixture.controller, RegisteredVehicleControllerBlockEntity.class, "knownDepartureRoute", fixture.route);
            assertNull(fixture.resolveDispatch());
            assertEquals(ACTUAL, get(fixture.controller, RegisteredVehicleControllerBlockEntity.class,
                    "destinationTerminal"), "Do not replace the chosen terminal with another destination");
            fixture.level.entities.put(ACTUAL, original);
            set(fixture.controller, RegisteredVehicleControllerBlockEntity.class, "destinationTerminal", null);
            assertNull(fixture.resolveDispatch(), "A known voyage without its saved endpoint must not guess nearby");
        }
    }

    @Test
    void startedKnownWaterVoyageCannotEnableReturnAfterOneWayValidation() throws Exception {
        try (Fixture fixture = fixture(Kind.WATER)) {
            var method = RegisteredVehicleControllerBlockEntity.class.getDeclaredMethod("autoReturnSettingLocked");
            method.setAccessible(true);
            set(fixture.controller, RegisteredVehicleControllerBlockEntity.class, "active", true);
            assertEquals(false, method.invoke(fixture.controller), "Legacy water routes retain their existing setting");
            set(fixture.controller, RegisteredVehicleControllerBlockEntity.class, "knownDepartureRoute", fixture.route);
            assertEquals(true, method.invoke(fixture.controller));
            set(fixture.controller, RegisteredVehicleControllerBlockEntity.class, "active", false);
            assertEquals(false, method.invoke(fixture.controller), "Parked vessels may choose return before starting");
        }
    }

    private void assertMatchesDispatch(Kind kind) throws Exception {
        try (Fixture fixture = fixture(kind)) {
            assertSame(fixture.level.entities.get(ACTUAL), fixture.resolveDispatch());
            set(fixture.controller, RegisteredVehicleControllerBlockEntity.class, "destinationTerminal", null);
            assertEquals(EXPECTED, fixture.label());
            assertNull(get(fixture.controller, RegisteredVehicleControllerBlockEntity.class, "destinationTerminal"),
                    "Opening the menu must not assign a dispatch destination");
        }
    }

    private Fixture fixture(Kind kind) throws Exception {
        var level = allocate(TestLevel.class);
        level.server = allocate(DedicatedServer.class);
        set(level.server, MinecraftServer.class, "levels", Map.of(Level.OVERWORLD, level));
        level.storage = new DimensionDataStorage(directory.toFile(), null, level.registryAccess());
        level.loaded = new HashSet<>();
        level.entities = new HashMap<>();

        var controller = allocate(RegisteredVehicleControllerBlockEntity.class);
        set(controller, BlockEntity.class, "level", level);
        set(controller, RegisteredVehicleControllerBlockEntity.class, "registration",
                new ControllerRegistration(UUID.randomUUID(), "owner", kind));
        set(controller, RegisteredVehicleControllerBlockEntity.class, "sourceTerminal", END.offset(-256, 0, 0));
        set(controller, RegisteredVehicleControllerBlockEntity.class, "manifest", new ArrayList<>());
        var points = List.of(Vec3.atCenterOf(END.offset(-256, 0, 0)), Vec3.atCenterOf(END));
        set(controller, RegisteredVehicleControllerBlockEntity.class, "activeRoute", new ArrayList<>(points));
        var route = new RouteDefinition("test route", points, "Owner", "owner", 1, 256, "Origin", ORIGINAL);

        var fixture = new Fixture(level, controller, route, kind);
        fixture.addTerminal(WRONG_ELEVATION, "Wrong elevation");
        fixture.addTerminal(ACTUAL, EXPECTED);
        return fixture;
    }

    private record Fixture(TestLevel level, RegisteredVehicleControllerBlockEntity controller,
                           RouteDefinition route, Kind kind) implements AutoCloseable {
        void addTerminal(BlockPos pos, String name) throws Exception {
            var dock = kind == Kind.LAND ? allocate(PostStationBlockEntity.class) : allocate(DockBlockEntity.class);
            set(dock, DockBlockEntity.class, "dockName", name);
            set(dock, BlockEntity.class, "worldPosition", pos);
            set(dock, BlockEntity.class, "level", level);
            set(dock, DockBlockEntity.class, "zoneMinX", -6);
            set(dock, DockBlockEntity.class, "zoneMaxX", 6);
            set(dock, DockBlockEntity.class, "zoneMinZ", -6);
            set(dock, DockBlockEntity.class, "zoneMaxZ", 6);
            level.entities.put(pos, dock);
            level.loaded.add(new ChunkPos(pos));
            if (kind == Kind.LAND) PostStationRegistry.register(level, pos);
            else DockRegistry.register(level, pos);
            var type = kind == Kind.LAND ? TerminalNetworkSavedData.Kind.POST_STATION : TerminalNetworkSavedData.Kind.PORT;
            TerminalNetworkSavedData.get(level).putEntry(new TerminalNetworkSavedData.TerminalEntry(
                    level.dimension().location().toString(), pos.asLong(), type, name, "town", "", List.of(), Set.of()));
        }

        void parkAt(BlockPos pos, Vec3 velocity) throws Exception {
            var registration = (ControllerRegistration) get(controller, RegisteredVehicleControllerBlockEntity.class,
                    "registration");
            UUID physical = UUID.randomUUID();
            assertTrue(registration.register(physical, 20, true));
            Vec3 center = Vec3.atCenterOf(pos);
            set(controller, RegisteredVehicleControllerBlockEntity.class, "bindingValid", true);
            set(controller, RegisteredVehicleControllerBlockEntity.class, "view", new PhysicalVehicleView(
                    physical, level.dimension().location().toString(), center, new Vec3(0, 0, 1), velocity,
                    AABB.ofSize(center, 2, 2, 2), 20, true));
        }

        CompoundTag knownDeparture() throws Exception {
            var method = RegisteredVehicleControllerBlockEntity.class.getDeclaredMethod("knownDepartureScreenData",
                    ServerLevel.class);
            method.setAccessible(true);
            return (CompoundTag) method.invoke(controller, level);
        }

        boolean startKnownDeparture(BlockPos origin, BlockPos target) throws Exception {
            var method = RegisteredVehicleControllerBlockEntity.class.getDeclaredMethod("startKnownDestination",
                    ServerLevel.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(controller, level, origin.asLong() + ":" + target.asLong());
        }

        String label() throws Exception {
            var method = RegisteredVehicleControllerBlockEntity.class.getDeclaredMethod("destinationName", RouteDefinition.class);
            method.setAccessible(true);
            return (String) method.invoke(controller, route);
        }

        Object resolveDispatch() throws Exception {
            var method = RegisteredVehicleControllerBlockEntity.class.getDeclaredMethod("resolveDestination", ServerLevel.class);
            method.setAccessible(true);
            return method.invoke(controller, level);
        }

        @Override public void close() {
            for (BlockPos pos : level.entities.keySet()) {
                if (kind == Kind.LAND) PostStationRegistry.unregister(level, pos);
                else DockRegistry.unregister(level, pos);
            }
        }
    }

    private static final class TestLevel extends ServerLevel {
        DedicatedServer server;
        DimensionDataStorage storage;
        Map<BlockPos, BlockEntity> entities;
        Set<ChunkPos> loaded;

        private TestLevel() { super(null, runnable -> {}, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public DimensionDataStorage getDataStorage() { return storage; }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public long getGameTime() { return 100; }
        @Override public boolean hasChunkAt(BlockPos pos) { return loaded.contains(new ChunkPos(pos)); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            assertTrue(hasChunkAt(pos), "Destination lookup must not query unloaded blocks");
            return entities.get(pos);
        }
    }

    private static Object get(Object target, Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }
}
