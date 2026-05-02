package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerRoutePreloadSchedulerTest {
    @Test
    void latestRequestReplacesPreviousRequestAndRejectsStaleResponses() {
        RoadPlannerRoutePreloadScheduler scheduler = new RoadPlannerRoutePreloadScheduler();
        RoadPlannerRoutePreloadScheduler.Request first = scheduler.entryRequest(
                UUID.randomUUID(),
                "world_a",
                "minecraft:overworld",
                new BlockPos(0, 64, 0),
                new BlockPos(256, 64, 0),
                List.of()).orElseThrow();
        RoadPlannerRoutePreloadScheduler.Request second = scheduler.forceRenderRequest(
                first.sessionId(),
                first.worldId(),
                first.dimensionId(),
                new BlockPos(0, 64, 0),
                new BlockPos(128, 64, 128)).orElseThrow();

        assertFalse(scheduler.acceptsResponse(first.sessionId(), first.requestId(), first.purpose(), first.worldId(), first.dimensionId()));
        assertTrue(scheduler.acceptsResponse(second.sessionId(), second.requestId(), second.purpose(), second.worldId(), second.dimensionId()));
    }
}
