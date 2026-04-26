package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.roadplanner.model.RoadPlan;
import com.monpai.sailboatmod.roadplanner.model.RoadPlanningSession;
import com.monpai.sailboatmod.roadplanner.model.RoadSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerSessionServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void startSessionStoresDefaultPlanForPlayer() {
        RoadPlannerSessionService service = new RoadPlannerSessionService();
        UUID playerId = UUID.randomUUID();
        BlockPos start = new BlockPos(10, 64, 20);
        BlockPos destination = new BlockPos(80, 70, 90);

        RoadPlanningSession session = service.startSession(playerId, Level.OVERWORLD, start, destination);

        assertEquals(playerId, session.playerId());
        assertEquals(start, session.startPos());
        assertEquals(destination, session.destinationPos());
        assertEquals(start, session.plan().start());
        assertEquals(destination, session.plan().destination());
        assertEquals(Optional.of(session), service.getSession(playerId));
    }

    @Test
    void setDestinationUpdatesSessionAndPlanDestination() {
        RoadPlannerSessionService service = new RoadPlannerSessionService();
        UUID playerId = UUID.randomUUID();
        service.startSession(playerId, Level.OVERWORLD, BlockPos.ZERO, new BlockPos(10, 64, 10));

        RoadPlanningSession updated = service.setDestination(playerId, new BlockPos(50, 66, 60)).orElseThrow();

        assertEquals(new BlockPos(50, 66, 60), updated.destinationPos());
        assertEquals(new BlockPos(50, 66, 60), updated.plan().destination());
    }

    @Test
    void replacePlanKeepsSessionIdentityAndStoresNewPlan() {
        RoadPlannerSessionService service = new RoadPlannerSessionService();
        UUID playerId = UUID.randomUUID();
        RoadPlanningSession original = service.startSession(playerId, Level.OVERWORLD, BlockPos.ZERO, new BlockPos(10, 64, 10));
        RoadPlan replacement = new RoadPlan(UUID.randomUUID(), BlockPos.ZERO, new BlockPos(100, 70, 100), List.of(), RoadSettings.defaults());

        RoadPlanningSession updated = service.replacePlan(playerId, replacement).orElseThrow();

        assertEquals(original.sessionId(), updated.sessionId());
        assertEquals(replacement, updated.plan());
        assertEquals(replacement.destination(), updated.destinationPos());
    }

    @Test
    void destinationServiceCreatesCoordinateAndCurrentPositionTargets() {
        RoadPlannerDestinationService destinationService = new RoadPlannerDestinationService();

        assertEquals(new BlockPos(1, 2, 3), destinationService.fromCoordinates(1, 2, 3));
        assertEquals(new BlockPos(4, 5, 6), destinationService.fromBlock(new BlockPos(4, 5, 6)));
        assertEquals(new BlockPos(7, 8, 9), destinationService.fromCurrentPlayerPosition(new BlockPos(7, 8, 9)));
        assertTrue(destinationService.fromCoordinates(1, 2, 3) instanceof BlockPos);
    }
}
