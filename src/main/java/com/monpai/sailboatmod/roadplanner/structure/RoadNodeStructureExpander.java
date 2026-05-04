package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public final class RoadNodeStructureExpander {
    private RoadNodeStructureExpander() {
    }

    public static RoadNodeExpansionResult expand(List<BlockPos> nodes,
                                                 List<RoadPlannerSegmentType> segmentTypes,
                                                 RoadPlannerBuildSettings settings,
                                                 ServerLevel level,
                                                 RoadStructureMode mode) {
        return expand(nodes, segmentTypes, settings, RoadTerrainSampler.fromLevel(level), mode);
    }

    public static RoadNodeExpansionResult expand(List<BlockPos> nodes,
                                                 List<RoadPlannerSegmentType> segmentTypes,
                                                 RoadPlannerBuildSettings settings,
                                                 RoadTerrainSampler terrainSampler,
                                                 RoadStructureMode mode) {
        List<RoadRouteSection> sections = RoadRouteSectionNormalizer.sections(nodes, segmentTypes);
        if (sections.isEmpty()) {
            return new RoadNodeExpansionResult(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(RoadStructureIssue.error("路径节点不足，至少需要两个有效节点。"))
            );
        }
        List<BlockPos> canonicalNodes = RoadRouteSectionNormalizer.flattenNodes(sections);
        List<RoadPlannerSegmentType> canonicalSegmentTypes = RoadRouteSectionNormalizer.flattenSegmentTypes(sections);
        java.util.List<RoadCenterlinePoint> allCenterline = new java.util.ArrayList<>();
        java.util.List<RoadSpan> allSpans = new java.util.ArrayList<>();
        int centerlineOffset = 0;
        for (RoadRouteSection section : sections) {
            List<RoadCenterlinePoint> rawCenterline = RoadCenterlineBuilder.build(section, terrainSampler);
            List<RoadSpan> rawSpans = RoadSpanClassifier.classify(rawCenterline);
            List<RoadCenterlinePoint> smoothedCenterline = RoadHeightProfileSmoother.smooth(rawCenterline, rawSpans);
            for (RoadSpan span : rawSpans) {
                allSpans.add(new RoadSpan(span.type(), span.startIndex() + centerlineOffset, span.endIndex() + centerlineOffset, span.sourceSegmentType()));
            }
            allCenterline.addAll(smoothedCenterline);
            centerlineOffset += smoothedCenterline.size();
        }
        return new RoadNodeExpansionResult(
                canonicalNodes,
                canonicalSegmentTypes,
                allCenterline,
                allSpans,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
