package com.monpai.sailboatmod.roadplanner.build;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class RoadDemolitionPlanner {
    public RoadDemolitionJob planEdge(UUID routeId,
                                      UUID edgeId,
                                      RoadRollbackLedger ledger,
                                      Map<BlockPos, Boolean> conflictsByPos) {
        List<RoadRollbackEntry> restoreEntries = new ArrayList<>();
        List<RoadDemolitionJob.Issue> issues = new ArrayList<>();
        for (RoadRollbackEntry entry : ledger.entriesForEdge(edgeId)) {
            if (conflictsByPos.getOrDefault(entry.pos(), false)) {
                issues.add(new RoadDemolitionJob.Issue("当前位置已被玩家或其它系统修改，跳过恢复: " + entry.pos(), true));
            } else {
                restoreEntries.add(entry);
            }
        }
        RoadDemolitionJob.Status status = restoreEntries.isEmpty() && !issues.isEmpty()
                ? RoadDemolitionJob.Status.BLOCKED
                : RoadDemolitionJob.Status.PLANNED;
        return new RoadDemolitionJob(UUID.randomUUID(), routeId, List.of(edgeId), restoreEntries, issues, status);
    }

    public Set<UUID> collectBranchEdges(UUID rootEdgeId, Map<UUID, List<UUID>> childEdgesByEdge) {
        Set<UUID> result = new LinkedHashSet<>();
        collect(rootEdgeId, childEdgesByEdge, result);
        return Set.copyOf(result);
    }

    private void collect(UUID edgeId, Map<UUID, List<UUID>> childEdgesByEdge, Set<UUID> result) {
        if (!result.add(edgeId)) {
            return;
        }
        for (UUID child : childEdgesByEdge.getOrDefault(edgeId, List.of())) {
            collect(child, childEdgesByEdge, result);
        }
    }
}
