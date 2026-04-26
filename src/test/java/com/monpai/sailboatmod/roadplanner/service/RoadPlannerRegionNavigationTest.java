package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.roadplanner.model.NodeSource;
import com.monpai.sailboatmod.roadplanner.model.RoadNode;
import com.monpai.sailboatmod.roadplanner.model.RoadPlan;
import com.monpai.sailboatmod.roadplanner.model.RoadPlanningSession;
import com.monpai.sailboatmod.roadplanner.model.RoadSegment;
import com.monpai.sailboatmod.roadplanner.model.RoadSettings;
import com.monpai.sailboatmod.roadplanner.model.RoadStroke;
import com.monpai.sailboatmod.roadplanner.model.RoadStrokeSettings;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerRegionNavigationTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nextRegionUsesLastNodeAsExitAndCreatesFollowingRegion() {
        RoadPlannerSessionService service = new RoadPlannerSessionService();
        UUID playerId = UUID.randomUUID();
        RoadPlanningSession session = service.startSession(playerId, Level.OVERWORLD, BlockPos.ZERO, new BlockPos(256, 70, 0));
        RoadNode a = new RoadNode(BlockPos.ZERO, 1L, NodeSource.MANUAL);
        RoadNode b = new RoadNode(new BlockPos(64, 64, 0), 2L, NodeSource.MANUAL);
        RoadStroke stroke = new RoadStroke(UUID.randomUUID(), RoadToolType.ROAD, List.of(a, b), RoadStrokeSettings.defaults());
        RoadSegment segment = new RoadSegment(0, BlockPos.ZERO, a.pos(), b.pos(), List.of(stroke), true);
        service.replacePlan(playerId, new RoadPlan(session.plan().planId(), session.startPos(), session.destinationPos(), List.of(segment), RoadSettings.defaults()));

        RoadPlanningSession next = service.nextRegion(playerId, 128).orElseThrow();

        assertEquals(1, next.activeRegionIndex());
        assertEquals(b.pos(), next.plan().segments().get(1).entryPoint());
        assertTrue(next.plan().segments().get(1).regionCenter().getX() > next.plan().segments().get(0).regionCenter().getX());
    }
}
