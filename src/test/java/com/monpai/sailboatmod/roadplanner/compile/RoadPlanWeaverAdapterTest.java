package com.monpai.sailboatmod.roadplanner.compile;

import com.monpai.sailboatmod.roadplanner.build.RoadBuildStep;
import com.monpai.sailboatmod.roadplanner.model.NodeSource;
import com.monpai.sailboatmod.roadplanner.model.RoadNode;
import com.monpai.sailboatmod.roadplanner.model.RoadPlan;
import com.monpai.sailboatmod.roadplanner.model.RoadSegment;
import com.monpai.sailboatmod.roadplanner.model.RoadSettings;
import com.monpai.sailboatmod.roadplanner.model.RoadStroke;
import com.monpai.sailboatmod.roadplanner.model.RoadStrokeSettings;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import com.monpai.sailboatmod.roadplanner.weaver.bridge.WeaverBridgeBackend;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverSpanType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlanWeaverAdapterTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roadAndBridgeStrokesCompileIntoTypedSections() {
        RoadNode a = node(0, 64, 0);
        RoadNode b = node(4, 64, 0);
        RoadNode c = node(8, 66, 0);
        RoadStroke road = stroke(RoadToolType.ROAD, a, b);
        RoadStroke bridge = stroke(RoadToolType.BRIDGE, b, c);
        RoadPlan plan = new RoadPlan(UUID.randomUUID(), a.pos(), c.pos(), List.of(
                new RoadSegment(0, a.pos(), a.pos(), c.pos(), List.of(road, bridge), true)
        ), RoadSettings.defaults());

        CompiledRoadPath compiled = new RoadPlanWeaverAdapter().compile(plan);

        assertEquals(List.of(a.pos(), b.pos(), b.pos(), c.pos()), compiled.centerline());
        assertEquals(2, compiled.sections().size());
        assertEquals(CompiledRoadSectionType.ROAD, compiled.sections().get(0).type());
        assertEquals(CompiledRoadSectionType.BRIDGE, compiled.sections().get(1).type());
        assertEquals(WeaverSpanType.BRIDGE, compiled.spans().get(0).type());
        assertTrue(compiled.previewCandidates().stream().allMatch(candidate -> candidate.visible()));
        assertTrue(compiled.issues().isEmpty());
    }

    @Test
    void emptyPlanReturnsBlockingIssue() {
        RoadPlan plan = new RoadPlan(UUID.randomUUID(), BlockPos.ZERO, new BlockPos(10, 64, 10), List.of(), RoadSettings.defaults());

        CompiledRoadPath compiled = new RoadPlanWeaverAdapter().compile(plan);

        assertEquals(1, compiled.issues().size());
        assertTrue(compiled.issues().get(0).blocking());
    }

    @Test
    void roadPreviewAddsLampCandidatesByConfiguredInterval() {
        RoadNode a = node(0, 64, 0);
        RoadNode b = node(24, 64, 0);
        RoadStroke road = stroke(RoadToolType.ROAD, a, b);
        RoadSettings settings = new RoadSettings(5, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, true, true, 8);
        RoadPlan plan = new RoadPlan(UUID.randomUUID(), a.pos(), b.pos(), List.of(
                new RoadSegment(0, a.pos(), a.pos(), b.pos(), List.of(road), true)
        ), settings);

        CompiledRoadPath compiled = new RoadPlanWeaverAdapter().compile(plan);

        assertTrue(compiled.previewCandidates().stream().anyMatch(candidate -> candidate.phase() == RoadBuildStep.Phase.LAMP));
    }

    @Test
    void bridgePreviewFallsBackToCurrentCompilerWhenBackendFails() {
        RoadNode a = node(0, 64, 0);
        RoadNode b = node(8, 64, 0);
        RoadStroke bridge = stroke(RoadToolType.BRIDGE, a, b);
        RoadPlan plan = new RoadPlan(UUID.randomUUID(), a.pos(), b.pos(), List.of(
                new RoadSegment(0, a.pos(), a.pos(), b.pos(), List.of(bridge), true)
        ), RoadSettings.defaults());
        WeaverBridgeBackend failingBackend = (centers, width, state) -> {
            throw new IllegalStateException("boom");
        };

        CompiledRoadPath compiled = new RoadPlanWeaverAdapter(failingBackend).compile(plan);

        assertEquals(CompiledRoadSectionType.BRIDGE, compiled.sections().get(0).type());
        assertTrue(compiled.previewCandidates().stream().anyMatch(candidate -> candidate.phase() == RoadBuildStep.Phase.BRIDGE_DECK));
        assertTrue(compiled.issues().stream().anyMatch(issue -> "bridge_backend_fallback".equals(issue.code())));
    }

    private RoadNode node(int x, int y, int z) {
        return new RoadNode(new BlockPos(x, y, z), 0L, NodeSource.MANUAL);
    }

    private RoadStroke stroke(RoadToolType tool, RoadNode... nodes) {
        return new RoadStroke(UUID.randomUUID(), tool, List.of(nodes), RoadStrokeSettings.defaults());
    }
}
