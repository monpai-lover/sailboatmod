package com.monpai.sailboatmod.route.water;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring contracts complement the behavioural terrain/geometry tests; not a running-world test. */
class PortTerrainIntegrationTest {
    private String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod", relative));
    }
    @Test void previewUsesNativeSurfaceAndFormalUsesColumns() throws Exception {
        String s = source("route/water/PortTerrainProbe.java");
        assertTrue(s.contains("surface.sample(x, z, 1)"));
        assertTrue(s.contains("predictor.predictChunk"));
        assertTrue(s.contains("if (detailed)"));
    }
    @Test void terrainProbeDoesNotForceChunksOrSaveTheWorld() throws Exception {
        String s = source("route/water/PortTerrainProbe.java");
        assertFalse(s.contains("forceChunk("));
        assertFalse(s.contains("getChunk("));
        assertFalse(s.contains(".save("));
        assertTrue(s.contains("getChunkNow("));
        assertTrue(s.contains("raw.get().copy()"));
    }
    @Test void bothPlannerPathsReceiveUnsavedRealSnapshots() throws Exception {
        String s = source("route/water/WaterAutoRouteService.java");
        assertEquals(2, s.split("portSnapshots.forEach", -1).length - 1);
        assertTrue(s.contains("probe.realSnapshots()"));
        assertTrue(s.indexOf("return submitTellusAutoRoute") < s.indexOf("RealBlockWaterMap berthProbeMap"));
        assertTrue(s.contains("WaterRouteFailureReason.TERRAIN_NOT_READY"));
    }
    @Test void clientUpdatesRatherThanReopeningClosedScreens() throws Exception {
        String s = source("client/DockClientHooks.java");
        assertTrue(s.contains("selection.updateDocks(docks)"));
        assertTrue(s.contains("instanceof DockScreen"));
        String screen = source("client/screen/AutoRouteDockSelectionScreen.java");
        assertTrue(screen.contains("docks.get(i).pos().equals(selected)"));
    }
}
