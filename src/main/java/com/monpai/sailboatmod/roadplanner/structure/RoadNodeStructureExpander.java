package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import com.monpai.sailboatmod.road.model.BuildPhase;
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
        java.util.List<BuildStep> steps = new java.util.ArrayList<>();
        steps.addAll(RoadSurfaceStepEmitter.emit(allCenterline, allSpans, settings, steps.size()));
        steps.addAll(BridgeStructureEmitter.emit(allCenterline, allSpans, settings, BridgeTemplateProvider.empty(), steps.size(), terrainSampler));
        java.util.List<BuildStep> dedupedSteps = dedupeAndReorder(steps);
        java.util.List<RoadPreviewBlock> previewBlocks = previewBlocksFromSteps(dedupedSteps);
        return new RoadNodeExpansionResult(
                canonicalNodes,
                canonicalSegmentTypes,
                allCenterline,
                allSpans,
                previewBlocks,
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
                    selectedByPosition.put(key, new BuildStep(step.order(), key, step.state(), step.phase()));
                }
            }
        }
        java.util.List<BuildStep> ordered = selectedByPosition.values().stream()
                .sorted(java.util.Comparator.comparingInt(BuildStep::order))
                .toList();
        java.util.List<BuildStep> deduped = new java.util.ArrayList<>(ordered.size());
        for (BuildStep step : ordered) {
            deduped.add(new BuildStep(deduped.size(), step.pos(), step.state(), step.phase()));
        }
        return java.util.List.copyOf(deduped);
    }

    private static boolean shouldReplace(BuildStep existing, BuildStep candidate) {
        int existingPriority = finalBlockPriority(existing);
        int candidatePriority = finalBlockPriority(candidate);
        if (candidatePriority != existingPriority) {
            return candidatePriority > existingPriority;
        }
        return candidate.order() > existing.order();
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
