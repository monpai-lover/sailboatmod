package com.monpai.sailboatmod.roadplanner.build;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadPath;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class RoadBuildStepPlanner {
    private final BlockState edgeState;

    public RoadBuildStepPlanner(BlockState edgeState) {
        this.edgeState = edgeState;
    }

    public List<RoadBuildStep> plan(UUID edgeId, CompiledRoadPath path) {
        List<RoadBuildStep> steps = new ArrayList<>();
        for (WeaverBuildCandidate candidate : path.previewCandidates()) {
            steps.add(new RoadBuildStep(edgeId, candidate.pos(), candidate.state(), candidate.visible(), phaseFor(candidate, path), true));
        }
        return steps.stream()
                .sorted(Comparator.comparingInt(step -> phaseOrder(step.phase())))
                .toList();
    }

    private RoadBuildStep.Phase phaseFor(WeaverBuildCandidate candidate, CompiledRoadPath path) {
        if (!candidate.visible() || candidate.state().is(Blocks.AIR)) {
            return RoadBuildStep.Phase.CLEAR_TO_SKY;
        }
        if (candidate.phase() == RoadBuildStep.Phase.LAMP) {
            return RoadBuildStep.Phase.LAMP;
        }
        if (candidate.state().equals(edgeState)) {
            return RoadBuildStep.Phase.ROAD_EDGE;
        }
        if (isInsideSection(candidate.pos(), path, CompiledRoadSectionType.BRIDGE)) {
            return RoadBuildStep.Phase.BRIDGE_DECK;
        }
        if (isInsideSection(candidate.pos(), path, CompiledRoadSectionType.TUNNEL)) {
            return RoadBuildStep.Phase.TUNNEL;
        }
        return RoadBuildStep.Phase.ROAD_SURFACE;
    }

    private boolean isInsideSection(BlockPos pos, CompiledRoadPath path, CompiledRoadSectionType type) {
        return path.sections().stream()
                .filter(section -> section.type() == type)
                .flatMap(section -> section.centerline().stream())
                .anyMatch(center -> center.distSqr(pos) <= 16.0D);
    }

    private int phaseOrder(RoadBuildStep.Phase phase) {
        return switch (phase) {
            case CLEAR_TO_SKY -> 0;
            case TERRAIN_FLATTEN -> 1;
            case ROAD_SURFACE -> 2;
            case ROAD_EDGE -> 3;
            case BRIDGE_DECK -> 4;
            case BRIDGE_PIER -> 5;
            case BRIDGE_RAMP -> 6;
            case BRIDGE_RAILING -> 7;
            case LAMP -> 8;
            case TUNNEL -> 9;
        };
    }
}
