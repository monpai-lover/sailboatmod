package com.monpai.sailboatmod.map.route;

import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StationMapRouteLifecycleTest {
    private static final String DIM = "minecraft:overworld";
    private static final BlockPos SOURCE = new BlockPos(0, 64, 0), TARGET = new BlockPos(1000, 64, 0);
    private static final RouteDefinition LINK = new RouteDefinition("Road Link: B", List.of(new Vec3(.5, 65.05, .5),
            new Vec3(1012.5, 65.05, .5)), "System", "", 1, 1012, "A", "B");

    private static StationMapRouteSavedData published() {
        var data = new StationMapRouteSavedData();
        data.put(new StationMapRouteSavedData.StationSnapshot(DIM, SOURCE, "A", List.of(LINK), List.of(LINK)));
        data.put(new StationMapRouteSavedData.StationSnapshot(DIM, TARGET, "B", List.of()));
        data.put(new StationMapRouteSavedData.StationSnapshot(DIM, new BlockPos(10000, 64, 0), "B", List.of()));
        return data;
    }

    @Test void aNormalRefreshRetainsPublishedGeometryForAnUnloadedKnownDestinationAcrossReload() {
        var data = published();
        AtomicInteger checks = new AtomicInteger();
        var updated = data.refreshGeneratedCatalog(DIM, SOURCE, "A", List.of(), List.of(), pos -> {
            checks.incrementAndGet(); assertEquals(TARGET, pos); return false;
        });
        assertEquals(List.of(LINK), updated.generatedRoutes());
        assertEquals(1, checks.get());
        var reloaded = StationMapRouteSavedData.load(data.save(new CompoundTag()));
        assertEquals(List.of(LINK), reloaded.station(DIM, SOURCE).routes());
    }

    @Test void aLoadedDestinationWithNoFreshGeneratedConnectionInvalidatesItsOldPublishedLink() {
        var data = published();
        var updated = data.refreshGeneratedCatalog(DIM, SOURCE, "A", List.of(), List.of(), pos -> true);
        assertTrue(updated.generatedRoutes().isEmpty());
        assertTrue(updated.routes().isEmpty());
    }

    @Test void destroyingADestinationPrunesIncomingGeneratedLinksAndRejectsAStaleLoadedSourceCache() {
        var data = published();
        data.put(new StationMapRouteSavedData.StationSnapshot(DIM, SOURCE, "A", List.of(LINK), List.of(LINK), List.of(LINK)));
        data.remove(DIM, TARGET);
        assertTrue(data.station(DIM, SOURCE).generatedRoutes().isEmpty());
        assertEquals(List.of(LINK), data.station(DIM, SOURCE).routes(), "explicitly registered geometry must remain");
        assertTrue(data.retainPublishedGenerated(DIM, SOURCE, List.of(LINK)).isEmpty(), "a stale loaded A cannot republish its deleted generated link");
    }
}
