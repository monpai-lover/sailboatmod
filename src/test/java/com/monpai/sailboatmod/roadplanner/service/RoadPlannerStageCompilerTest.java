package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.structure.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerStageCompilerTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test
    void originalLongBridgeVisibleGeometryAndMaterialSurviveStagedRealCompilation() {
        var settings = new RoadPlannerBuildSettings(3, "oak_planks", false);
        var nodes = List.of(new BlockPos(0, 64, 0), new BlockPos(400, 64, 0));
        var types = List.of(RoadPlannerSegmentType.BRIDGE_MAJOR);
        RoadTerrainSampler source = water();
        var expected = RoadNodeStructureExpander.expand(nodes, types, settings, source, RoadStructureMode.BUILD);
        var plan = new RoadPlannerStagedPlan(nodes, types);
        var compiler = new RoadPlannerStageCompiler(plan, settings);
        for (int stage = 0; stage < plan.totalStages(); stage++) compiler.expand(plan.stage(stage), source, true);
        Map<BlockPos, BlockState> combined = new HashMap<>();
        for (int index = 0; index < plan.totalStages(); index++) {
            var stage = plan.stage(index);
            var held = new java.util.HashSet<>(stage.chunks());
            RoadTerrainSampler actualOnly = new RoadTerrainSampler() {
                private void guard(int x, int z) {
                    assertTrue(held.contains(ChunkPos.asLong(Math.floorDiv(x, 16), Math.floorDiv(z, 16))),
                            "actual compilation must not read/generate distant bridge anchors beyond the current real window");
                }
                @Override public int terrainY(int x, int z) { guard(x, z); return source.terrainY(x, z); }
                @Override public int waterSurfaceY(int x, int z) { guard(x, z); return source.waterSurfaceY(x, z); }
                @Override public int oceanFloorY(int x, int z) { guard(x, z); return source.oceanFloorY(x, z); }
                @Override public boolean isWater(int x, int y, int z) { guard(x, z); return source.isWater(x, y, z); }
            };
            visible(compiler.expand(stage, actualOnly, false).buildSteps()).forEach(combined::put);
        }
        assertEquals(visible(expected.buildSteps()), combined);
    }

    @Test
    void realRaisedWaterOrShoreHoldsTheBridgeInsteadOfExecutingTheOldPredictedHeight() {
        var plan = new RoadPlannerStagedPlan(List.of(new BlockPos(0, 64, 0), new BlockPos(400, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR));
        var compiler = new RoadPlannerStageCompiler(plan, new RoadPlannerBuildSettings(3, "oak_planks", false));
        for (int i = 0; i < plan.totalStages(); i++) compiler.expand(plan.stage(i), water(), true);
        RoadTerrainSampler raisedWater = new RoadTerrainSampler() {
            @Override public int terrainY(int x, int z) { return 80; }
            @Override public int waterSurfaceY(int x, int z) { return 79; }
            @Override public int oceanFloorY(int x, int z) { return 40; }
            @Override public boolean isWater(int x, int y, int z) { return y > 40 && y <= 79; }
        };
        assertThrows(IllegalStateException.class, () -> compiler.expand(plan.stage(1), raisedWater, false));
        RoadTerrainSampler raisedShore = (x, z) -> x == 0 ? 80 : 64;
        assertThrows(IllegalStateException.class, () -> compiler.expand(plan.stage(0), raisedShore, false));
    }

    @Test
    void previouslyBuiltHaloCannotCreateAFalseBridgeProfileDrift() {
        var plan = new RoadPlannerStagedPlan(List.of(new BlockPos(0, 64, 0), new BlockPos(400, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR));
        var compiler = new RoadPlannerStageCompiler(plan, new RoadPlannerBuildSettings(3, "oak_planks", false));
        for (int i = 0; i < plan.totalStages(); i++) compiler.expand(plan.stage(i), water(), true);
        RoadTerrainSampler actual = new RoadTerrainSampler() {
            @Override public int terrainY(int x, int z) { return x < 128 ? 80 : 64; }
            @Override public int waterSurfaceY(int x, int z) { return 63; }
            @Override public int oceanFloorY(int x, int z) { return 40; }
            @Override public boolean isWater(int x, int y, int z) { return x >= 128 && y > 40 && y <= 63; }
        };
        assertDoesNotThrow(() -> compiler.expand(plan.stage(1), actual, false));
    }

    @Test
    void longRoadOverRealWaterRequiresAnExplicitBridgeInsteadOfBuildingTheSeaFloor() {
        var plan = new RoadPlannerStagedPlan(List.of(new BlockPos(0, 64, 0), new BlockPos(40_000, 64, 0)), List.of(RoadPlannerSegmentType.ROAD));
        var compiler = new RoadPlannerStageCompiler(plan, RoadPlannerBuildSettings.DEFAULTS);
        assertThrows(IllegalStateException.class, () -> compiler.expand(plan.stage(0), water(), true));
        assertThrows(IllegalStateException.class, () -> compiler.expand(plan.stage(0), water(), false));
    }

    private static Map<BlockPos, BlockState> visible(List<BuildStep> steps) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (BuildStep step : steps) if (!step.state().isAir() && (step.phase() == BuildPhase.DECK
                || step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.SURFACE)) result.put(step.pos(), step.state());
        return result;
    }

    private static RoadTerrainSampler water() {
        return new RoadTerrainSampler() {
            @Override public int terrainY(int x, int z) { return 64; }
            @Override public int waterSurfaceY(int x, int z) { return 63; }
            @Override public int oceanFloorY(int x, int z) { return 40; }
            @Override public boolean isWater(int x, int y, int z) { return y > 40 && y <= 63; }
        };
    }
}
