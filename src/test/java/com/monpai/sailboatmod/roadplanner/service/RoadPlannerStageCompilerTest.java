package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.corridor.RoadCorridorCompiler;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpander;
import com.monpai.sailboatmod.roadplanner.structure.RoadStructureMode;
import com.monpai.sailboatmod.roadplanner.structure.RoadTerrainSampler;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerStageCompilerTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    /** Hills with a river through them: enough relief and water that every part of the design is exercised. */
    private static final RoadTerrainSampler TERRAIN = new RoadTerrainSampler() {
        private int solid(int x, int z) {
            double hills = 70 + 8 * Math.sin(x / 23.0) * Math.cos(z / 19.0) + 3 * Math.sin((x - z) / 9.0);
            double river = Math.abs(z - 150 + 10 * Math.sin(x / 40.0));
            return (int) Math.round(river < 14 ? Math.min(hills, 56 + river) : hills);
        }
        @Override public int terrainY(int x, int z) { return Math.max(63, solid(x, z)); }
        @Override public int oceanFloorY(int x, int z) { return solid(x, z); }
        @Override public boolean isWater(int x, int y, int z) { return y >= solid(x, z) && y < 63; }
    };

    private static final List<BlockPos> NODES = List.of(
            new BlockPos(0, 64, 0), new BlockPos(90, 64, 20), new BlockPos(110, 64, 60), new BlockPos(110, 64, 120),
            new BlockPos(130, 64, 190), new BlockPos(210, 64, 230), new BlockPos(214, 64, 236), new BlockPos(330, 64, 236),
            new BlockPos(350, 64, 200), new BlockPos(350, 64, 20));
    private static final List<RoadPlannerSegmentType> TYPES = List.of(
            RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD,
            RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD,
            RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD);

    @Test
    void haloCoversEverythingTheRoadGeometryLooksAt() {
        assertTrue(RoadPlannerStagedPlan.HALO_BLOCKS >= RoadCorridorCompiler.CONTEXT,
                "a stage must sample as far as the road design reaches, or neighbouring stages disagree at their seam");
    }

    @Test
    void stagesBuiltSeparatelyAddUpToExactlyTheRoadBuiltAtOnce() {
        var settings = RoadPlannerBuildSettings.DEFAULTS;
        Map<BlockPos, BlockState> whole = blocks(RoadNodeStructureExpander.expand(NODES, TYPES, settings, TERRAIN,
                RoadStructureMode.BUILD).buildSteps());
        var plan = new RoadPlannerStagedPlan(NODES, TYPES);
        var compiler = new RoadPlannerStageCompiler(plan, settings);
        Map<BlockPos, BlockState> staged = new HashMap<>();
        Map<Long, Integer> columnOwner = new HashMap<>();
        for (int index = 0; index < plan.totalStages(); index++) {
            for (BuildStep step : compiler.expand(plan.stage(index), TERRAIN).buildSteps()) {
                assertNull(staged.put(step.pos(), step.state()), "a block may be placed by only one stage: " + step.pos());
                Integer owner = columnOwner.put(ChunkPos.asLong(step.pos().getX(), step.pos().getZ()), index);
                assertTrue(owner == null || owner == index, "a column may belong to only one stage: " + step.pos());
            }
        }
        assertTrue(plan.totalStages() >= 6, "the route must actually be split for this to prove anything");
        Map<BlockPos, BlockState> missing = new HashMap<>(whole);
        staged.forEach((pos, state) -> { if (state.equals(missing.get(pos))) missing.remove(pos); });
        Map<BlockPos, BlockState> extra = new HashMap<>(staged);
        whole.forEach((pos, state) -> { if (state.equals(extra.get(pos))) extra.remove(pos); });
        assertTrue(missing.isEmpty() && extra.isEmpty(), "staged build differs from the whole road: "
                + missing.size() + " blocks missing or different, e.g. " + missing.entrySet().stream().limit(6).toList()
                + "; " + extra.size() + " unexpected, e.g. " + extra.entrySet().stream().limit(6).toList());
    }

    @Test
    void aStageReadsOnlyTheTerrainItsOwnChunksCover() {
        var plan = new RoadPlannerStagedPlan(NODES, TYPES);
        var compiler = new RoadPlannerStageCompiler(plan, RoadPlannerBuildSettings.DEFAULTS);
        for (int index = 0; index < plan.totalStages(); index++) {
            Set<Long> held = new HashSet<>(plan.stage(index).chunks());
            RoadTerrainSampler guarded = new RoadTerrainSampler() {
                private void guard(int x, int z) {
                    assertTrue(held.contains(ChunkPos.asLong(Math.floorDiv(x, 16), Math.floorDiv(z, 16))),
                            "a stage must not read or generate terrain outside the chunks it loaded: " + x + "," + z);
                }
                @Override public int terrainY(int x, int z) { guard(x, z); return TERRAIN.terrainY(x, z); }
                @Override public int oceanFloorY(int x, int z) { guard(x, z); return TERRAIN.oceanFloorY(x, z); }
                @Override public boolean isWater(int x, int y, int z) { guard(x, z); return TERRAIN.isWater(x, y, z); }
            };
            compiler.expand(plan.stage(index), guarded);
        }
    }

    @Test
    void aLongRoadOverWaterIsBridgedWithoutNeedingToBeMarkedAsABridge() {
        var plan = new RoadPlannerStagedPlan(List.of(new BlockPos(0, 64, 0), new BlockPos(40_000, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD));
        RoadTerrainSampler sea = new RoadTerrainSampler() {
            @Override public int terrainY(int x, int z) { return 63; }
            @Override public int oceanFloorY(int x, int z) { return 40; }
            @Override public boolean isWater(int x, int y, int z) { return y >= 40 && y < 63; }
        };
        var steps = new RoadPlannerStageCompiler(plan, RoadPlannerBuildSettings.DEFAULTS).expand(plan.stage(3), sea).buildSteps();
        assertFalse(steps.isEmpty());
        assertTrue(steps.stream().filter(step -> !step.state().isAir()).allMatch(step ->
                step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER || step.pos().getY() >= 62),
                "only piers may reach below the water surface; the road itself must be carried over it");
    }

    @Test
    void terrainMustBeSupplied() {
        var plan = new RoadPlannerStagedPlan(NODES, TYPES);
        var compiler = new RoadPlannerStageCompiler(plan, RoadPlannerBuildSettings.DEFAULTS);
        assertThrows(IllegalStateException.class, () -> compiler.expand(plan.stage(0), null));
    }

    private static Map<BlockPos, BlockState> blocks(List<BuildStep> steps) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (BuildStep step : steps) {
            assertNull(result.put(step.pos(), step.state()), "duplicate step at " + step.pos());
        }
        return result;
    }
}
