package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterRoutePublicationGraphTest {
    private static final BlockPos PORT_A = new BlockPos(8, 1, 15);
    private static final BlockPos PORT_B = new BlockPos(56, 1, 15);
    private static final BlockPos PORT_C = new BlockPos(56, 1, 60);
    private static final List<BlockPos> PLANNED = List.of(
            new BlockPos(8, 0, 7), new BlockPos(32, 0, 7), new BlockPos(56, 0, 7));
    private static final List<Vec3> NEW_WATER = List.of(
            new Vec3(8.5, 0, 7.5), new Vec3(32.5, 0, 7.5), new Vec3(56.5, 0, 7.5));

    @TempDir Path saveDirectory;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void normalPublicationKeepsPersistedReverseAlias() throws Exception {
        var fixture = fixture();
        var graph = fixture.graph;
        graph.register(PORT_B, PORT_A, fixture.portB.getRoutesForMap().getFirst());
        var unrelated = new RouteDefinition("Other route",
                List.of(new Vec3(56.5, 0, 20.5), new Vec3(56.5, 0, 55.5)));
        graph.register(PORT_B, PORT_C, unrelated, false);
        var previousSnapshot = graph.snapshot();
        long previousRevision = graph.revision();

        publish(fixture);

        assertEquals(NEW_WATER, fixture.portA.getRoutesForMap().getFirst().waypoints());
        assertEquals(NEW_WATER.reversed(), fixture.portB.getRoutesForMap().getFirst().waypoints());
        assertEquals(NEW_WATER, graph.snapshot().stream()
                .filter(route -> route.sourceDock().equals(PORT_A) && route.targetDock().equals(PORT_B))
                .findFirst().orElseThrow().points());
        assertEquals(previousSnapshot.get(1), graph.snapshot().stream()
                .filter(route -> route.sourceDock().equals(PORT_B) && route.targetDock().equals(PORT_A))
                .findFirst().orElseThrow());
        assertTrue(graph.revision() > previousRevision);
        assertEquals(63, previousSnapshot.get(1).points().getFirst().y);
        assertEquals(previousSnapshot.getLast(), graph.snapshot().stream()
                .filter(route -> route.targetDock().equals(PORT_C)).findFirst().orElseThrow());

        var restored = restore(graph);
        assertEquals(graph.snapshot(), restored.snapshot());
    }

    @Test
    void normalPublicationKeepsSingleReversibleEdgeWhenReverseAliasWasNeverImported() throws Exception {
        var fixture = fixture();

        publish(fixture);

        assertEquals(1, fixture.graph.snapshot().size());
        assertTrue(fixture.graph.snapshot().getFirst().reversible());
        assertMarketGeometry(restore(fixture.graph));
    }

    private static void assertMarketGeometry(WaterRouteNetworkSavedData data) {
        var graph = KnownWaterRouteGraph.build(data.snapshot());
        assertEquals(NEW_WATER, graph.between(PORT_A, PORT_B).orElseThrow().points());
        assertEquals(NEW_WATER.reversed(), graph.between(PORT_B, PORT_A).orElseThrow().points());
    }

    private Fixture fixture() throws Exception {
        FixtureLevel level = allocate(FixtureLevel.class);
        level.storage = new DimensionDataStorage(saveDirectory.toFile(), null, level.registryAccess());
        DockBlockEntity portA = port();
        DockBlockEntity portB = port();
        var legacy = NEW_WATER.stream().map(point -> new Vec3(point.x, 63, point.z)).toList();
        portA.setRoutes(List.of(legacy("A", "B", legacy)), 0);
        portB.setRoutes(List.of(legacy("B", "A", legacy.reversed())), 0);
        var graph = WaterRouteNetworkSavedData.get(level);
        graph.register(PORT_A, PORT_B, portA.getRoutesForMap().getFirst());
        return new Fixture(level, portA, portB, graph);
    }

    private static void publish(Fixture fixture) throws Exception {
        Method apply = WaterAutoRouteService.class.getDeclaredMethod("applyCompletedRoute",
                ServerLevel.class, DockBlockEntity.class, DockBlockEntity.class,
                WaterAutoRouteService.DockSnapshot.class, WaterAutoRouteService.DockSnapshot.class,
                String.class, String.class, WaterRouteResult.class, List.class, ServerPlayer.class);
        apply.setAccessible(true);
        apply.invoke(null, fixture.level, fixture.portA, fixture.portB,
                new WaterAutoRouteService.DockSnapshot(PORT_A, "A", "town-a", "nation", null),
                new WaterAutoRouteService.DockSnapshot(PORT_B, "B", "town-b", "nation", null),
                "OceanA", "OceanB", WaterRouteResult.success(PLANNED), List.of(), null);
    }

    private static WaterRouteNetworkSavedData restore(WaterRouteNetworkSavedData data) throws Exception {
        Method load = WaterRouteNetworkSavedData.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
        load.setAccessible(true);
        return (WaterRouteNetworkSavedData) load.invoke(null, data.save(new CompoundTag(), null), null);
    }

    private static RouteDefinition legacy(String source, String target, List<Vec3> points) {
        return new RouteDefinition("Auto-" + source + "-" + target, points, "", "", 1L, 48, source, target);
    }

    private static DockBlockEntity port() throws Exception {
        DockBlockEntity port = allocate(DockBlockEntity.class);
        Field routes = DockBlockEntity.class.getDeclaredField("routes");
        routes.setAccessible(true);
        routes.set(port, new ArrayList<RouteDefinition>());
        return port;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private record Fixture(FixtureLevel level, DockBlockEntity portA, DockBlockEntity portB,
                           WaterRouteNetworkSavedData graph) {}

    private static final class FixtureLevel extends ServerLevel {
        private DimensionDataStorage storage;

        private FixtureLevel() {
            super(null, command -> {}, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override public DimensionDataStorage getDataStorage() { return storage; }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY); }
    }
}
