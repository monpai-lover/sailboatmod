package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerCompiledPath;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathCompiler;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RoadPlannerBuildStepCompiler {
    private RoadPlannerBuildStepCompiler() {
    }

    public static List<BuildStep> compile(List<BlockPos> nodes,
                                          List<RoadPlannerSegmentType> segmentTypes,
                                          RoadPlannerBuildSettings settings,
                                          ServerLevel level) {
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        NormalizedRoute route = normalizeRoute(nodes, segmentTypes);
        if (route.sections().isEmpty()) {
            return List.of();
        }
        List<BuildStep> steps = new ArrayList<>();
        for (RouteSection section : route.sections()) {
            steps.addAll(level == null
                    ? fallbackSteps(section.nodes(), section.segmentTypes(), safeSettings)
                    : groupedSteps(section.nodes(), section.segmentTypes(), safeSettings, level));
        }
        return dedupeAndReorder(steps);
    }

    public static List<BuildStep> compileForTest(List<BlockPos> nodes,
                                                 List<RoadPlannerSegmentType> segmentTypes,
                                                 RoadPlannerBuildSettings settings) {
        return compile(nodes, segmentTypes, settings, null);
    }

    private static List<BuildStep> groupedSteps(List<BlockPos> nodes,
                                                List<RoadPlannerSegmentType> segmentTypes,
                                                RoadPlannerBuildSettings settings,
                                                ServerLevel level) {
        List<BuildStep> steps = new ArrayList<>();
        int segmentIndex = 0;
        while (segmentIndex < segmentTypes.size()) {
            RoadPlannerSegmentType type = segmentTypes.get(segmentIndex);
            int start = segmentIndex;
            boolean bridgeGroup = isBridge(type);
            boolean hasMajorBridge = type == RoadPlannerSegmentType.BRIDGE_MAJOR;
            while (segmentIndex < segmentTypes.size() && compatible(bridgeGroup, type, segmentTypes.get(segmentIndex))) {
                hasMajorBridge = hasMajorBridge || segmentTypes.get(segmentIndex) == RoadPlannerSegmentType.BRIDGE_MAJOR;
                segmentIndex++;
            }
            List<BlockPos> sectionNodes = nodes.subList(start, segmentIndex + 1);
            List<RoadPlannerSegmentType> sectionTypes = segmentTypes.subList(start, segmentIndex);
            if (bridgeGroup) {
                int heightBonus = hasMajorBridge ? 5 : 3;
                steps.addAll(RoadPlannerBuildControlService.nodeAnchoredBridgeStepsForCompiler(sectionNodes, settings.width(), level, heightBonus));
            } else {
                steps.addAll(fallbackSteps(sectionNodes, sectionTypes, settings));
            }
        }
        return steps;
    }

    private static List<BuildStep> fallbackSteps(List<BlockPos> nodes,
                                                 List<RoadPlannerSegmentType> segmentTypes,
                                                 RoadPlannerBuildSettings settings) {
        RoadPlannerCompiledPath compiled = RoadPlannerPathCompiler.compile(nodes, segmentTypes, settings);
        List<BuildStep> steps = new ArrayList<>();
        Set<BlockPos> surfacePositions = new LinkedHashSet<>();
        for (RoadPlannerCompiledPath.CompiledBlock block : compiled.blocks()) {
            if (includeFallbackState(block.state()) && !isUnderwaterRoad(block)) {
                surfacePositions.add(block.pos());
            }
        }
        for (BlockPos pos : surfacePositions) {
            for (int dy = 1; dy <= 4; dy++) {
                steps.add(new BuildStep(steps.size(), pos.above(dy), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
            }
        }
        for (BlockPos pos : surfacePositions) {
            steps.add(new BuildStep(steps.size(), pos.below(1), Blocks.DIRT.defaultBlockState(), BuildPhase.FOUNDATION));
            steps.add(new BuildStep(steps.size(), pos.below(2), Blocks.DIRT.defaultBlockState(), BuildPhase.FOUNDATION));
            steps.add(new BuildStep(steps.size(), pos.below(3), Blocks.COBBLESTONE.defaultBlockState(), BuildPhase.FOUNDATION));
        }
        for (RoadPlannerCompiledPath.CompiledBlock block : compiled.blocks()) {
            if (!includeFallbackState(block.state()) || isUnderwaterRoad(block)) {
                continue;
            }
            steps.add(new BuildStep(steps.size(), block.pos(), block.state(), phaseFor(block.segmentType())));
        }
        for (RoadPlannerCompiledPath.LightBlock light : compiled.lights()) {
            if (includeFallbackState(light.state())) {
                steps.add(new BuildStep(steps.size(), light.pos(), light.state(), BuildPhase.STREETLIGHT));
            }
        }
        return steps;
    }

    private static List<BuildStep> dedupeAndReorder(List<BuildStep> steps) {
        Set<StepKey> seen = new HashSet<>();
        List<BuildStep> deduped = new ArrayList<>();
        for (BuildStep step : steps) {
            if (step == null || step.pos() == null || step.state() == null || step.phase() == null) {
                continue;
            }
            StepKey key = new StepKey(step.pos().immutable(), step.phase(), step.state());
            if (seen.add(key)) {
                deduped.add(new BuildStep(deduped.size(), step.pos(), step.state(), step.phase()));
            }
        }
        return List.copyOf(deduped);
    }

    private static NormalizedRoute normalizeRoute(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes) {
        if (nodes == null || nodes.size() < 2) {
            return new NormalizedRoute(List.of());
        }
        List<RouteSection> sections = new ArrayList<>();
        List<BlockPos> sectionNodes = new ArrayList<>();
        List<RoadPlannerSegmentType> sectionTypes = new ArrayList<>();
        for (int index = 0; index < nodes.size() - 1; index++) {
            BlockPos from = nodes.get(index);
            BlockPos to = nodes.get(index + 1);
            if (from == null || to == null) {
                addSection(sections, sectionNodes, sectionTypes);
                sectionNodes = new ArrayList<>();
                sectionTypes = new ArrayList<>();
                continue;
            }
            if (from.equals(to)) {
                continue;
            }
            BlockPos immutableFrom = from.immutable();
            BlockPos immutableTo = to.immutable();
            if (sectionNodes.isEmpty()) {
                sectionNodes.add(immutableFrom);
            } else if (!sectionNodes.get(sectionNodes.size() - 1).equals(immutableFrom)) {
                addSection(sections, sectionNodes, sectionTypes);
                sectionNodes = new ArrayList<>();
                sectionTypes = new ArrayList<>();
                sectionNodes.add(immutableFrom);
            }
            sectionNodes.add(immutableTo);
            sectionTypes.add(segmentTypeAt(segmentTypes, index));
        }
        addSection(sections, sectionNodes, sectionTypes);
        return new NormalizedRoute(List.copyOf(sections));
    }

    private static void addSection(List<RouteSection> sections,
                                   List<BlockPos> nodes,
                                   List<RoadPlannerSegmentType> segmentTypes) {
        if (nodes.size() >= 2 && segmentTypes.size() == nodes.size() - 1) {
            sections.add(new RouteSection(List.copyOf(nodes), List.copyOf(segmentTypes)));
        }
    }

    private static RoadPlannerSegmentType segmentTypeAt(List<RoadPlannerSegmentType> segmentTypes, int index) {
        RoadPlannerSegmentType type = RoadPlannerSegmentType.ROAD;
        if (segmentTypes != null && index >= 0 && index < segmentTypes.size() && segmentTypes.get(index) != null) {
            type = segmentTypes.get(index);
        }
        return normalizeSegmentType(type);
    }

    private static RoadPlannerSegmentType normalizeSegmentType(RoadPlannerSegmentType type) {
        if (type == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        return type == null ? RoadPlannerSegmentType.ROAD : type;
    }

    private static boolean compatible(boolean bridgeGroup, RoadPlannerSegmentType groupType, RoadPlannerSegmentType candidateType) {
        return bridgeGroup ? isBridge(candidateType) : groupType == candidateType;
    }

    private static boolean isBridge(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL;
    }

    private static BuildPhase phaseFor(RoadPlannerSegmentType type) {
        return isBridge(type) ? BuildPhase.DECK : BuildPhase.SURFACE;
    }

    private static boolean includeFallbackState(BlockState state) {
        return state != null && !state.isAir() && state.getFluidState().isEmpty();
    }

    private static boolean isUnderwaterRoad(RoadPlannerCompiledPath.CompiledBlock block) {
        return block.segmentType() == RoadPlannerSegmentType.ROAD && block.pos().getY() <= 62;
    }

    private record NormalizedRoute(List<RouteSection> sections) {
    }

    private record RouteSection(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes) {
    }

    private record StepKey(BlockPos pos, BuildPhase phase, BlockState state) {
    }
}

