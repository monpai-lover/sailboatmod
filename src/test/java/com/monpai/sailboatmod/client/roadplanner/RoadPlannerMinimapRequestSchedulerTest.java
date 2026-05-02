package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerMinimapRequestSchedulerTest {
    private static final UUID SESSION = new UUID(1L, 2L);
    private static final RoadPlannerMapLayout.Rect MAP = new RoadPlannerMapLayout.Rect(40, 30, 400, 300);

    @Test
    void initialRequestUsesCurrentMapCenterAndViewportSize() {
        RoadPlannerMinimapRequestScheduler scheduler = new RoadPlannerMinimapRequestScheduler(250L);
        RoadPlannerMapView view = RoadPlannerMapView.centered(128.0D, -64.0D, 2.0D);

        Optional<RoadPlannerMinimapRequestScheduler.Request> request = scheduler.initialRequest(0L, SESSION, "world", "minecraft:overworld", view, MAP);

        assertTrue(request.isPresent());
        assertEquals(1L, request.orElseThrow().requestId());
        assertEquals(RoadMapSnapshotRequestPacket.Purpose.INITIAL_VIEWPORT, request.orElseThrow().purpose());
        assertEquals(new BlockPos(128, 0, -64), request.orElseThrow().regionCenter());
        assertEquals(256, request.orElseThrow().regionSize());
        assertEquals(MapLod.LOD_4, request.orElseThrow().lod());
    }

    @Test
    void viewportRequestsAreThrottledUntilIntervalPasses() {
        RoadPlannerMinimapRequestScheduler scheduler = new RoadPlannerMinimapRequestScheduler(250L);
        RoadPlannerMapView first = RoadPlannerMapView.centered(0.0D, 0.0D, 2.0D);
        RoadPlannerMapView second = RoadPlannerMapView.centered(512.0D, 0.0D, 2.0D);

        assertTrue(scheduler.initialRequest(0L, SESSION, "world", "minecraft:overworld", first, MAP).isPresent());
        assertTrue(scheduler.viewportRequest(100L, SESSION, "world", "minecraft:overworld", second, MAP).isEmpty());
        assertTrue(scheduler.viewportRequest(251L, SESSION, "world", "minecraft:overworld", second, MAP).isPresent());
    }

    @Test
    void forceRenderRequestBypassesThrottleAndCoversSelection() {
        RoadPlannerMinimapRequestScheduler scheduler = new RoadPlannerMinimapRequestScheduler(500L);
        scheduler.initialRequest(0L, SESSION, "world", "minecraft:overworld", RoadPlannerMapView.centered(0.0D, 0.0D, 2.0D), MAP);

        Optional<RoadPlannerMinimapRequestScheduler.Request> request = scheduler.forceRenderRequest(
                10L,
                SESSION,
                "world",
                "minecraft:overworld",
                new BlockPos(-16, 64, -16),
                new BlockPos(96, 70, 96));

        assertTrue(request.isPresent());
        assertEquals(2L, request.orElseThrow().requestId());
        assertEquals(RoadMapSnapshotRequestPacket.Purpose.FORCE_RENDER, request.orElseThrow().purpose());
        assertEquals(new BlockPos(40, 0, 40), request.orElseThrow().regionCenter());
        assertEquals(128, request.orElseThrow().regionSize());
    }

    @Test
    void acceptsOnlyLatestMatchingResponse() {
        RoadPlannerMinimapRequestScheduler scheduler = new RoadPlannerMinimapRequestScheduler(250L);
        RoadPlannerMinimapRequestScheduler.Request first = scheduler.initialRequest(
                0L,
                SESSION,
                "world",
                "minecraft:overworld",
                RoadPlannerMapView.centered(0.0D, 0.0D, 2.0D),
                MAP).orElseThrow();
        RoadPlannerMinimapRequestScheduler.Request second = scheduler.forceRenderRequest(
                1L,
                SESSION,
                "world",
                "minecraft:overworld",
                BlockPos.ZERO,
                new BlockPos(64, 64, 64)).orElseThrow();

        assertTrue(!scheduler.acceptsResponse(first.requestId(), first.purpose(), first.regionCenter(), first.regionSize()));
        assertTrue(scheduler.acceptsResponse(second.requestId(), second.purpose(), second.regionCenter(), second.regionSize()));
    }
}
