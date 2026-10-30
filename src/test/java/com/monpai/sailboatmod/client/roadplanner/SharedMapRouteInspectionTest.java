package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import net.minecraft.world.level.ChunkPos;

import static org.junit.jupiter.api.Assertions.*;

class SharedMapRouteInspectionTest {
    @Test
    void inspectionConsumesLeftMapClicksWithoutCreatingRoadNodes() {
        var screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        var map = screen.mapLayoutForTest().map();
        var tool = RoadPlannerTopToolbar.toolbar(1280, RoadPlannerTopToolbar.Group.TOOLS).items().stream()
                .filter(item -> item.toolType() == RoadToolType.ROAD).findFirst().orElseThrow();
        screen.mouseClicked(14, 10, 0);
        screen.mouseClicked(tool.bounds().x() + 2, tool.bounds().y() + 2, 0);
        assertFalse(screen.routeLayer().inspectionEnabled());
        screen.routeLayer().setInspectionEnabled(true);
        assertTrue(screen.mouseClicked(map.x() + 120, map.y() + 120, 0));
        assertEquals(0, screen.plannedNodeCountForTest());
        screen.routeLayer().setInspectionEnabled(false);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        assertEquals(1, screen.plannedNodeCountForTest());
    }

    @Test
    void inspectionKeepsMiddleDragAndWheelZoomAvailable() {
        var screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        var map = screen.mapLayoutForTest().map();
        screen.routeLayer().setInspectionEnabled(true);
        assertTrue(screen.mouseClicked(map.x() + 100, map.y() + 100, 2));
        assertTrue(screen.mouseDragged(map.x() + 130, map.y() + 120, 2, 30, 20));
        assertTrue(screen.mouseReleased(map.x() + 130, map.y() + 120, 2));
        assertTrue(screen.mouseScrolled(map.x() + 130, map.y() + 120, 0, 1));
        assertEquals(0, screen.plannedNodeCountForTest());
    }

    @Test
    void enablingInspectionCancelsAnUnreleasedForceRenderGesture() {
        var screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        var map = screen.mapLayoutForTest().map();
        var tool = RoadPlannerTopToolbar.toolbar(1280, RoadPlannerTopToolbar.Group.TOOLS).items().stream()
                .filter(item -> item.toolType() == RoadToolType.FORCE_RENDER).findFirst().orElseThrow();
        screen.mouseClicked(14, 10, 0);
        screen.mouseClicked(tool.bounds().x() + 2, tool.bounds().y() + 2, 0);
        screen.mouseClicked(map.x() + 240, map.y() + 240, 0);
        screen.routeLayer().setInspectionEnabled(true);
        screen.mouseReleased(map.x() + 260, map.y() + 260, 0);
        assertEquals(0, screen.forceRenderTotalChunksForTest());
    }

    @Test
    void returningToTheRetainedPlannerKeepsTileSchedulingAvailable() throws Exception {
        var screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.removed();
        screen.init();
        var schedulerField = RoadPlannerScreen.class.getDeclaredField("tileRenderScheduler");
        schedulerField.setAccessible(true);
        var scheduler = (RoadPlannerTileRenderScheduler) schedulerField.get(screen);
        assertTrue(scheduler.markSubmitted(new ChunkPos(100, 100)), "returning from route options must not leave the retained planner's scheduler closed");
        scheduler.close();
    }
}
