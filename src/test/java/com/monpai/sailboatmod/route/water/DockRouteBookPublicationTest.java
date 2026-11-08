package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.block.AirportBlock;
import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.compat.ItemStackNbt;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.item.PostRouteBookItem;
import com.monpai.sailboatmod.item.RouteBookItem;
import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData;
import com.monpai.sailboatmod.market.terminal.TerminalVisibility;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.RouteNbtUtil;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the real book import, saved network and dispatch graph without a running game. */
class DockRouteBookPublicationTest {
    private static final BlockPos A = new BlockPos(8, 1, 15), B = new BlockPos(56, 1, 15);
    private static final List<Vec3> POINTS = List.of(
            new Vec3(14.5, 0, 2.5), new Vec3(32.5, 0, 2.5), new Vec3(56.5, 0, 7.5));
    @TempDir Path temporaryDirectory;
    private TestLevel level;
    private TestDock dock;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach void fixture() throws Exception {
        level = allocate(TestLevel.class);
        level.server = allocate(DedicatedServer.class);
        set(MinecraftServer.class, level.server, "levels", Map.of(Level.OVERWORLD, level));
        level.storage = new DimensionDataStorage(temporaryDirectory.toFile(), null, level.registryAccess());
        dock = initialize(allocate(TestDock.class));
        terminal(A, "A", TerminalNetworkSavedData.Kind.PORT);
        terminal(B, "B", TerminalNetworkSavedData.Kind.PORT);
    }

    @Test void importedBookImmediatelyBecomesAvailableToTheKnownDispatchGraphBothWays() throws Exception {
        WaterRouteNetworkSavedData data = WaterRouteNetworkSavedData.get(level);
        var issuedBeforeImport = data.snapshot();
        long revision = data.revision();
        ItemStack waterBook = book(RouteBookItem.class, route("B", POINTS));
        assertTrue(dock.acceptsBook(waterBook));
        dock.setRouteBookStack(waterBook);

        assertEquals(1, dock.importRouteBook());
        assertEquals(POINTS, dock.getRoutesForMap().getFirst().waypoints());
        var catalog = KnownVoyageService.catalog(level, A, null);
        var graph = KnownWaterRouteGraph.build(catalog.routes());
        assertTrue(graph.between(A, B).isPresent(),
                "Import must publish immediately; the next market/menu query cannot wait for a chunk reload");
        assertEquals(POINTS, graph.between(A, B).orElseThrow().points());
        assertEquals(POINTS.reversed(), graph.between(B, A).orElseThrow().points());
        assertTrue(catalog.revision() > revision, "Existing graph views must be invalidated");
        assertTrue(issuedBeforeImport.isEmpty(), "Already issued immutable snapshots must stay unchanged");
    }

    @Test void duplicateAndEmptyBooksDoNotRewriteAnExistingPublishedRoute() throws Exception {
        WaterRouteNetworkSavedData data = WaterRouteNetworkSavedData.get(level);
        RouteDefinition route = route("B", POINTS);
        data.register(A, B, route);
        dock.setRoutes(List.of(route), 0);
        var before = data.snapshot();
        long revision = data.revision();
        dock.setRouteBookStack(book(RouteBookItem.class, route));

        assertEquals(0, dock.importRouteBook());
        dock.setRouteBookStack(ItemStack.EMPTY);
        assertEquals(0, dock.importRouteBook());
        assertEquals(before, data.snapshot());
        assertEquals(revision, data.revision());
    }

    @Test void explicitBookReimportRestoresOnlyItsDeletedGeometryWhileLegacyCopiesStayBlocked() throws Exception {
        WaterRouteNetworkSavedData data = WaterRouteNetworkSavedData.get(level);
        RouteDefinition recorded = route("B", POINTS);
        dock.setRouteBookStack(book(RouteBookItem.class, recorded));
        assertEquals(1, dock.importRouteBook());
        assertTrue(dock.deleteSelectedRoute());
        assertTrue(data.snapshot().isEmpty());

        TestDock staleReturnDock = initialize(allocate(TestDock.class));
        set(BlockEntity.class, staleReturnDock, "worldPosition", B);
        staleReturnDock.setRoutes(List.of(new RouteDefinition("Old return copy", POINTS.reversed(),
                "Owner", "owner", 1, KnownWaterRouteGraph.length(POINTS), "B", "A")), 0);
        data.importDock(staleReturnDock);
        assertTrue(data.snapshot().isEmpty(), "Loading the other dock's old copy must not revive a deleted route");

        BlockPos c = new BlockPos(80, 1, 15), d = new BlockPos(120, 1, 15);
        terminal(c, "C", TerminalNetworkSavedData.Kind.PORT);
        terminal(d, "D", TerminalNetworkSavedData.Kind.PORT);
        List<Vec3> otherPoints = List.of(new Vec3(80.5, 0, 2.5), new Vec3(120.5, 0, 2.5));
        RouteDefinition other = new RouteDefinition("Other deleted route", otherPoints,
                "Owner", "owner", 1, 40, "C", "D");
        data.register(c, d, other);
        data.invalidateGeometry(otherPoints);
        TestDock unrelatedStaleDock = initialize(allocate(TestDock.class));
        set(BlockEntity.class, unrelatedStaleDock, "worldPosition", c);
        unrelatedStaleDock.setRoutes(List.of(other), 0);

        assertEquals(1, dock.importRouteBook(), "The player's same book is explicitly imported again");
        var graph = KnownWaterRouteGraph.build(KnownVoyageService.catalog(level, A, null).routes());
        assertTrue(graph.between(A, B).isPresent(), "Explicit reimport must restore the deleted water connection");
        assertEquals(POINTS.reversed(), graph.between(B, A).orElseThrow().points());
        data.importDock(unrelatedStaleDock);
        assertTrue(KnownWaterRouteGraph.build(data.snapshot()).between(c, d).isEmpty(),
                "Reimporting one book must not clear deletion protection for other geometries");
    }

    @Test void unknownOrAmbiguousDestinationDoesNotManufactureANetworkConnection() throws Exception {
        dock.setRouteBookStack(book(RouteBookItem.class, route("Unregistered", POINTS)));
        assertEquals(1, dock.importRouteBook());
        assertTrue(WaterRouteNetworkSavedData.get(level).snapshot().isEmpty());

        terminal(new BlockPos(58, 1, 16), "B", TerminalNetworkSavedData.Kind.PORT);
        dock.setRouteBookStack(book(RouteBookItem.class, route("B", List.of(POINTS.getFirst(), POINTS.getLast()))));
        assertEquals(1, dock.importRouteBook());
        assertTrue(WaterRouteNetworkSavedData.get(level).snapshot().isEmpty(),
                "Existing importDock ambiguity checks must still govern book publication");
    }

    @Test void landRouteBookImportCannotPublishWaterInfrastructure() throws Exception {
        var station = initialize(allocate(TestStation.class));
        station.setRouteBookStack(book(PostRouteBookItem.class, route("B", POINTS)));
        assertEquals(1, station.importRouteBook());
        assertEquals(1, station.getRoutesForMap().size());
        assertTrue(WaterRouteNetworkSavedData.get(level).snapshot().isEmpty());
    }

    @Test void portRejectsLandBookEvenWhenTheDestinationStationAndPortShareAName() throws Exception {
        terminal(new BlockPos(8, 5, 16), "A", TerminalNetworkSavedData.Kind.POST_STATION);
        terminal(new BlockPos(56, 5, 16), "B", TerminalNetworkSavedData.Kind.POST_STATION);
        var landRoute = route("B", List.of(new Vec3(8.5, 6.05, 16.5), new Vec3(32.5, 7.05, 16.5),
                new Vec3(56.5, 6.05, 16.5)));
        ItemStack landBook = book(PostRouteBookItem.class, landRoute);
        assertInstanceOf(PostRouteBookItem.class, landBook.getItem());
        boolean accepted = dock.acceptsBook(landBook);
        // A mismatched book can already be in the slot from a save made before the check.
        dock.setRouteBookStack(landBook);
        int imported = dock.importRouteBook();

        assertAll(
                () -> assertFalse(accepted, "The normal port loading gate must reject a land book"),
                () -> assertEquals(0, imported, "A mismatched book already in the slot must also be rejected"),
                () -> assertTrue(dock.getRoutesForMap().isEmpty(), "The port route list must stay empty"),
                () -> assertTrue(WaterRouteNetworkSavedData.get(level).snapshot().isEmpty(),
                        "The land route must not become a public water connection"));
    }

    @Test void unloadingAPostStationKeepsItsPublishedMarketConnectionInTheSavedNetwork() throws Exception {
        var station = initialize(allocate(TestStation.class));
        terminal(A, "A", TerminalNetworkSavedData.Kind.POST_STATION);
        terminal(B, "B", TerminalNetworkSavedData.Kind.POST_STATION);
        var network = TerminalNetworkSavedData.get(level);
        network.addReachableTown("minecraft:overworld", A.asLong(), "town-B");
        PostStationRegistry.register(level, A);

        station.onChunkUnloaded();
        station.setRemoved();

        assertTrue(station.isRemoved());
        assertFalse(PostStationRegistry.get(level).contains(A), "Unload must still unregister the live station");
        var reloaded = TerminalNetworkSavedData.load(network.save(new CompoundTag(), level.registryAccess()));
        assertTrue(TerminalVisibility.isVisible(reloaded, "town-A", "town-B"),
                "A previously published road must remain market-visible after chunk unload and server save");
    }

    @Test void airportNetworkSurvivesUnloadButIsRemovedByBlockReplacement() throws Exception {
        var airport = initialize(allocate(AirportBlockEntity.class));
        terminal(A, "A", TerminalNetworkSavedData.Kind.AIRPORT);
        var network = TerminalNetworkSavedData.get(level);

        airport.onChunkUnloaded();
        airport.setRemoved();

        assertNotNull(network.getEntry("minecraft:overworld", A.asLong()), "Unload must keep the airport identity");
        var block = allocate(AirportBlock.class);
        var occupied = Blocks.CHEST.defaultBlockState();
        block.onRemove(occupied, level, A, occupied, false);
        assertNotNull(network.getEntry("minecraft:overworld", A.asLong()), "A same-block update is not destruction");
        block.onRemove(occupied, level, A, Blocks.AIR.defaultBlockState(), false);
        assertNull(network.getEntry("minecraft:overworld", A.asLong()), "A replaced airport cannot remain market-visible");
    }

    private <T extends DockBlockEntity> T initialize(T terminal) throws Exception {
        set(BlockEntity.class, terminal, "worldPosition", A);
        set(BlockEntity.class, terminal, "blockState", Blocks.AIR.defaultBlockState());
        set(DockBlockEntity.class, terminal, "routes", new ArrayList<RouteDefinition>());
        terminal.setLevel(level);
        return terminal;
    }

    private void terminal(BlockPos pos, String name, TerminalNetworkSavedData.Kind kind) {
        TerminalNetworkSavedData.get(level).putEntry(new TerminalNetworkSavedData.TerminalEntry(
                "minecraft:overworld", pos.asLong(), kind, name, "town-" + name, "nation", List.of(), Set.of()));
    }

    private static RouteDefinition route(String destination, List<Vec3> points) {
        return new RouteDefinition("Recorded water route", points, "Owner", "owner", 1,
                KnownWaterRouteGraph.length(points), "A", destination);
    }

    private static ItemStack book(Class<? extends Item> bookType, RouteDefinition route) throws Exception {
        // Preserve the runtime item class without writing to the already-frozen item registry.
        Item item = allocate(bookType);
        set(Item.class, item, "components", Items.BOOK.components());
        ItemStack stack = new ItemStack(item);
        ItemStackNbt.updateTag(stack, tag -> RouteNbtUtil.writeRoutes(tag, "Routes", List.of(route)));
        return stack;
    }

    private static final class TestDock extends DockBlockEntity {
        private TestDock() { super(BlockPos.ZERO, Blocks.AIR.defaultBlockState()); }
        boolean acceptsBook(ItemStack book) { return acceptsRouteBook(book); }
        @Override public void setChanged() { } // GUI/marker refresh is irrelevant to route publication.
    }

    private static final class TestStation extends PostStationBlockEntity {
        private TestStation() { super(BlockPos.ZERO, Blocks.AIR.defaultBlockState()); }
        @Override public void setChanged() { }
    }

    private static final class TestLevel extends ServerLevel {
        DedicatedServer server;
        DimensionDataStorage storage;
        private TestLevel() {
            super(null, command -> {}, null, null, null, null, null, false, 0L, List.of(), false, null);
        }
        @Override public MinecraftServer getServer() { return server; }
        @Override public DimensionDataStorage getDataStorage() { return storage; }
        @Override public RegistryAccess registryAccess() {
            return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public void invalidateCapabilities(BlockPos pos) { }
        @Override public void removeBlockEntity(BlockPos pos) { } // Only persistent callbacks are under test.
        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            throw new AssertionError("Import publication must not load or read remote blocks: " + pos);
        }
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((Unsafe) field.get(null)).allocateInstance(type);
    }
}
