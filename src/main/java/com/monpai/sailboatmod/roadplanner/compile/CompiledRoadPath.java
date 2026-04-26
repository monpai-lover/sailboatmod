package com.monpai.sailboatmod.roadplanner.compile;

import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverRoadSpan;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import net.minecraft.core.BlockPos;

import java.util.List;

public record CompiledRoadPath(List<BlockPos> centerline,
                               List<CompiledRoadSection> sections,
                               List<WeaverRoadSpan> spans,
                               List<RoadIssue> issues,
                               List<WeaverBuildCandidate> previewCandidates) {
    public CompiledRoadPath {
        centerline = centerline == null ? List.of() : centerline.stream().map(BlockPos::immutable).toList();
        sections = sections == null ? List.of() : List.copyOf(sections);
        spans = spans == null ? List.of() : List.copyOf(spans);
        issues = issues == null ? List.of() : List.copyOf(issues);
        previewCandidates = previewCandidates == null ? List.of() : List.copyOf(previewCandidates);
    }
}
