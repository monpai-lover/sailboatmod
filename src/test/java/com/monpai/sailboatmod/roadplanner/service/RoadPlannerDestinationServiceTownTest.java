package com.monpai.sailboatmod.roadplanner.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerDestinationServiceTownTest {
    @Test
    void storesStartAndDestinationTownSession() {
        RoadPlannerDestinationService service = new RoadPlannerDestinationService();
        UUID playerId = UUID.randomUUID();

        service.saveTownDestination(playerId,
                new RoadPlannerDestinationService.TownEndpoint("start", "Start", new BlockPos(0, 64, 0)),
                new RoadPlannerDestinationService.TownEndpoint("dest", "Dest", new BlockPos(160, 64, 0)));

        RoadPlannerDestinationService.TownRoute route = service.townRouteFor(playerId).orElseThrow();
        assertEquals("start", route.start().townId());
        assertEquals("dest", route.destination().townId());
        assertTrue(service.destinationFor(playerId).isPresent());
    }
}
