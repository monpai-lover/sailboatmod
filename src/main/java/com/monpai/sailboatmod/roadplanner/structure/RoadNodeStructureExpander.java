package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.roadplanner.corridor.RoadAlignment;
import com.monpai.sailboatmod.roadplanner.corridor.RoadCorridorCompiler;
import net.minecraft.core.BlockPos;
import com.monpai.sailboatmod.road.model.BuildStep;
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
        java.util.List<BuildStep> steps = new java.util.ArrayList<>();
        for (RoadRouteSection section : sections) {
            RoadCorridorCompiler.Result compiled = RoadCorridorCompiler.compile(
                    RoadAlignment.of(section.nodes(), section.segmentTypes()), settings, terrainSampler);
            int centerlineOffset = allCenterline.size();
            for (RoadSpan span : compiled.spans()) {
                allSpans.add(new RoadSpan(span.type(), span.startIndex() + centerlineOffset,
                        span.endIndex() + centerlineOffset, span.sourceSegmentType()));
            }
            allCenterline.addAll(compiled.centerline());
            steps.addAll(compiled.steps());
        }
        return result(canonicalNodes, canonicalSegmentTypes, allCenterline, allSpans, steps);
    }

    /** Packages compiled road steps: one final block per position, renumbered in build order. */
    public static RoadNodeExpansionResult result(List<BlockPos> canonicalNodes,
                                                 List<RoadPlannerSegmentType> canonicalSegmentTypes,
                                                 List<RoadCenterlinePoint> centerline,
                                                 List<RoadSpan> spans,
                                                 List<BuildStep> steps) {
        java.util.List<BuildStep> dedupedSteps = dedupeAndReorder(steps);
        return new RoadNodeExpansionResult(
                canonicalNodes,
                canonicalSegmentTypes,
                centerline,
                spans,
                previewBlocksFromSteps(dedupedSteps),
                dedupedSteps,
                List.of()
        );
    }

    private static java.util.List<RoadPreviewBlock> previewBlocksFromSteps(java.util.List<BuildStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<RoadPreviewBlock> preview = new java.util.ArrayList<>();
        for (BuildStep step : steps) {
            if (isVisiblePreviewStep(step)) {
                preview.add(new RoadPreviewBlock(step.pos(), step.state(), step.phase()));
            }
        }
        return java.util.List.copyOf(preview);
    }

    private static boolean isVisiblePreviewStep(BuildStep step) {
        if (step == null || step.pos() == null || step.state() == null || step.phase() == null || step.state().isAir()) {
            return false;
        }
        return switch (step.phase()) {
            case SURFACE, RAMP, DECK, PIER, RAILING, STREETLIGHT -> true;
            case FOUNDATION -> false;
        };
    }

    private static java.util.List<BuildStep> dedupeAndReorder(java.util.List<BuildStep> steps) {
        java.util.Map<BlockPos, BuildStep> selectedByPosition = new java.util.LinkedHashMap<>();
        if (steps != null) {
            for (BuildStep step : steps) {
                if (step == null || step.pos() == null || step.state() == null || step.phase() == null) {
                    continue;
                }
                BlockPos key = step.pos().immutable();
                BuildStep existing = selectedByPosition.get(key);
                if (existing == null || shouldReplace(existing, step)) {
                    selectedByPosition.put(key, new BuildStep(existing == null ? step.order() : existing.order(),
                            key, step.state(), step.phase()));
                }
            }
        }
        java.util.List<BuildStep> deduped = new java.util.ArrayList<>(selectedByPosition.size());
        for (BuildStep step : selectedByPosition.values()) {
            deduped.add(new BuildStep(deduped.size(), step.pos(), step.state(), step.phase()));
        }
        return java.util.List.copyOf(deduped);
    }

    private static boolean shouldReplace(BuildStep existing, BuildStep candidate) {
        int existingPriority = finalBlockPriority(existing);
        int candidatePriority = finalBlockPriority(candidate);
        return candidatePriority != existingPriority ? candidatePriority > existingPriority : true;
    }

    private static int finalBlockPriority(BuildStep step) {
        if (step.state().isAir()) {
            return 0;
        }
        return switch (step.phase()) {
            case DECK, RAMP -> 70;
            case SURFACE -> 60;
            case RAILING, STREETLIGHT -> 50;
            case PIER -> 30;
            case FOUNDATION -> 20;
        };
    }
}
