package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.roadplanner.corridor.RoadCorridorCompiler;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeExpansionResult;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpander;
import com.monpai.sailboatmod.roadplanner.structure.RoadTerrainSampler;

/**
 * Compiles one stage of a long road from the terrain around it.
 *
 * <p>Road geometry at a point depends only on the route and ground within
 * {@link RoadPlannerStagedPlan#HALO_BLOCKS} of it, so a stage computed on its own matches what the
 * whole road compiled at once would have produced there. Stages therefore need no shared state, and
 * a preview and the later build agree wherever the terrain has not changed.</p>
 */
public final class RoadPlannerStageCompiler {
    private final RoadPlannerStagedPlan plan;
    private final RoadPlannerBuildSettings settings;

    public RoadPlannerStageCompiler(RoadPlannerStagedPlan plan, RoadPlannerBuildSettings settings) {
        this.plan = java.util.Objects.requireNonNull(plan);
        this.settings = settings;
    }

    public RoadNodeExpansionResult expand(RoadPlannerStagedPlan.Stage stage, RoadTerrainSampler source) {
        if (source == null) throw new IllegalStateException("Actual road terrain is unavailable");
        RoadCorridorCompiler.Result compiled = RoadCorridorCompiler.compile(plan.alignment(stage),
                stage.routeFrom() - RoadPlannerStagedPlan.HALO_BLOCKS, stage.routeTo() + RoadPlannerStagedPlan.HALO_BLOCKS,
                stage.ownedFrom(), stage.ownedTo(), settings, source);
        return RoadNodeStructureExpander.result(stage.nodes(), stage.types(),
                compiled.centerline(), compiled.spans(), compiled.steps());
    }
}
