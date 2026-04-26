package com.monpai.sailboatmod.roadplanner.build;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadPath;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdge;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNode;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.graph.RoadRouteMetadata;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

public class RoadPlannerConfirmBuildService {
    private final RoadBuildStepPlanner stepPlanner;

    public RoadPlannerConfirmBuildService(RoadBuildStepPlanner stepPlanner) {
        this.stepPlanner = stepPlanner;
    }

    public RoadPlannerConfirmBuildResult confirm(UUID routeId, UUID actorId, CompiledRoadPath path, String roadName) {
        RoadNetworkGraph graph = new RoadNetworkGraph();
        BlockPos start = path.centerline().isEmpty() ? BlockPos.ZERO : path.centerline().get(0);
        BlockPos end = path.centerline().isEmpty() ? start : path.centerline().get(path.centerline().size() - 1);
        RoadGraphNode from = graph.addNode(start, RoadGraphNode.Kind.NORMAL);
        RoadGraphNode to = graph.addNode(end, RoadGraphNode.Kind.NORMAL);
        CompiledRoadSectionType type = path.sections().isEmpty() ? CompiledRoadSectionType.ROAD : path.sections().get(0).type();
        int width = path.sections().isEmpty() ? 5 : path.sections().get(0).width();
        RoadGraphEdge edge = graph.addEdge(from.nodeId(), to.nodeId(), new RoadRouteMetadata(
                roadName,
                "起点",
                "终点",
                actorId,
                0L,
                width,
                type,
                RoadRouteMetadata.Status.PLANNED
        ));
        List<RoadBuildStep> steps = stepPlanner.plan(edge.edgeId(), path);
        RoadBuildJob job = RoadBuildJob.create(UUID.randomUUID(), routeId, edge.edgeId(), steps);
        List<RoadBuildStep> visiblePreview = steps.stream()
                .filter(RoadBuildStep::visible)
                .toList();
        return new RoadPlannerConfirmBuildResult(edge, job, visiblePreview);
    }
}
