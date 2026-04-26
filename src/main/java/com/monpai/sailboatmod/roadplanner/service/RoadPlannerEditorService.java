package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerDemolishRoadPacket;
import com.monpai.sailboatmod.roadplanner.build.RoadDemolitionJob;
import com.monpai.sailboatmod.roadplanner.build.RoadDemolitionPlanner;
import com.monpai.sailboatmod.roadplanner.build.RoadRollbackLedger;
import com.monpai.sailboatmod.roadplanner.compile.RoadIssue;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdge;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RoadPlannerEditorService {
    private static final RoadPlannerEditorService GLOBAL = new RoadPlannerEditorService();

    public static RoadPlannerEditorService global() {
        return GLOBAL;
    }

    public RenameResult renameRoad(RoadNetworkGraph graph, UUID edgeId, String newName) {
        Optional<RoadGraphEdge> edge = graph.renameEdge(edgeId, newName);
        if (edge.isEmpty()) {
            return new RenameResult(false, Optional.empty(), List.of(new RoadIssue(
                    "road_edge_missing",
                    "找不到要重命名的道路",
                    null,
                    true
            )));
        }
        return new RenameResult(true, edge, List.of());
    }

    public DemolishResult planDemolition(UUID routeId,
                                         UUID edgeId,
                                         RoadPlannerDemolishRoadPacket.Scope scope,
                                         RoadRollbackLedger ledger,
                                         Map<BlockPos, Boolean> conflicts) {
        RoadDemolitionJob job = new RoadDemolitionPlanner().planEdge(routeId, edgeId, ledger, conflicts);
        if (job.restoreEntries().isEmpty() && job.issues().isEmpty()) {
            return new DemolishResult(Optional.empty(), List.of(new RoadIssue(
                    "road_ledger_missing",
                    "找不到这段道路的回滚账本",
                    null,
                    true
            )));
        }
        return new DemolishResult(Optional.of(job), List.of());
    }

    public record RenameResult(boolean success, Optional<RoadGraphEdge> edge, List<RoadIssue> issues) {
        public RenameResult {
            edge = edge == null ? Optional.empty() : edge;
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    public record DemolishResult(Optional<RoadDemolitionJob> job, List<RoadIssue> issues) {
        public DemolishResult {
            job = job == null ? Optional.empty() : job;
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
