package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.item.RoadPlannerItem;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerDestinationServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void destinationHelpersPreserveExactImmutablePositions() {
        RoadPlannerDestinationService service = new RoadPlannerDestinationService();

        assertEquals(new BlockPos(1, 2, 3), service.fromCoordinates(1, 2, 3));
        assertEquals(new BlockPos(4, 5, 6), service.fromBlock(new BlockPos(4, 5, 6)));
        assertEquals(new BlockPos(7, 8, 9), service.fromCurrentPlayerPosition(new BlockPos(7, 8, 9)));
    }

    @Test
    void plannerItemUsesNewPlannerEntryIntent() {
        assertEquals(RoadPlannerItem.EntryAction.OPEN_NEW_PLANNER, RoadPlannerItem.entryAction(false));
        assertEquals(RoadPlannerItem.EntryAction.SET_CURRENT_POSITION_DESTINATION, RoadPlannerItem.entryAction(true));
        assertFalse(RoadPlannerItem.usesLegacyManualPlanner());
        assertTrue(RoadPlannerItem.entryAction(false).opensPlanner());
        assertFalse(RoadPlannerItem.entryAction(true).opensPlanner());
        assertTrue(RoadPlannerItem.entryAction(true).storesDestinationOnly());
    }

    @Test
    void shiftRightClickDestinationIsPersistedPerPlayer() {
        UUID playerId = UUID.randomUUID();
        RoadPlannerDestinationService service = new RoadPlannerDestinationService();
        BlockPos destination = new BlockPos(10, 70, -5);

        service.saveCurrentPositionDestination(playerId, destination);

        assertEquals(destination, service.destinationFor(playerId).orElseThrow());
    }
}
