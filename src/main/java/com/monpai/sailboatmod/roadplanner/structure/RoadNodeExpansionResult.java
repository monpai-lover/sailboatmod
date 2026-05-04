package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;

import java.util.List;

public record RoadNodeExpansionResult(List<BlockPos> canonicalNodes,
                                      List<RoadPlannerSegmentType> canonicalSegmentTypes,
                                      List<RoadCenterlinePoint> centerline,
                                      List<RoadSpan> spans,
                                      List<RoadPreviewBlock> previewBlocks,
                                      List<BuildStep> buildSteps,
                                      List<RoadStructureIssue> issues) {
    public RoadNodeExpansionResult {
        canonicalNodes = canonicalNodes == null ? List.of() : canonicalNodes.stream().map(BlockPos::immutable).toList();
        canonicalSegmentTypes = canonicalSegmentTypes == null ? List.of() : List.copyOf(canonicalSegmentTypes);
        centerline = centerline == null ? List.of() : List.copyOf(centerline);
        spans = spans == null ? List.of() : List.copyOf(spans);
        previewBlocks = previewBlocks == null ? List.of() : List.copyOf(previewBlocks);
        buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.severity() == RoadStructureIssue.Severity.ERROR);
    }
}
