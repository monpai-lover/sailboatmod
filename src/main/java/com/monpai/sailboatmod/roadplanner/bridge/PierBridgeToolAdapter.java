package com.monpai.sailboatmod.roadplanner.bridge;

import com.monpai.sailboatmod.roadplanner.build.RoadBuildStep;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadPath;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSection;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PierBridgeToolAdapter {
    private final BlockState deckState;

    public PierBridgeToolAdapter(BlockState deckState) {
        this.deckState = deckState;
    }

    public List<RoadBuildStep> build(UUID edgeId, CompiledRoadPath path) {
        List<RoadBuildStep> steps = new ArrayList<>();
        for (CompiledRoadSection section : path.sections()) {
            if (section.type() != CompiledRoadSectionType.BRIDGE || section.centerline().isEmpty()) {
                continue;
            }
            addClearing(edgeId, section, steps);
            addDeck(edgeId, section, steps);
            addPiers(edgeId, section, steps);
            addRamps(edgeId, section, steps);
            addRailings(edgeId, section, steps);
        }
        return List.copyOf(steps);
    }

    private void addClearing(UUID edgeId, CompiledRoadSection section, List<RoadBuildStep> steps) {
        for (BlockPos center : section.centerline()) {
            steps.add(new RoadBuildStep(edgeId, center.above(8), Blocks.AIR.defaultBlockState(), false, RoadBuildStep.Phase.CLEAR_TO_SKY, true));
        }
    }

    private void addDeck(UUID edgeId, CompiledRoadSection section, List<RoadBuildStep> steps) {
        for (BlockPos center : section.centerline()) {
            steps.add(new RoadBuildStep(edgeId, center, deckState, true, RoadBuildStep.Phase.BRIDGE_DECK, true));
        }
    }

    private void addPiers(UUID edgeId, CompiledRoadSection section, List<RoadBuildStep> steps) {
        for (int index = 1; index < section.centerline().size() - 1; index += 2) {
            steps.add(new RoadBuildStep(edgeId, section.centerline().get(index).below(), Blocks.STONE_BRICKS.defaultBlockState(), true, RoadBuildStep.Phase.BRIDGE_PIER, true));
        }
    }

    private void addRamps(UUID edgeId, CompiledRoadSection section, List<RoadBuildStep> steps) {
        steps.add(new RoadBuildStep(edgeId, section.centerline().get(0), deckState, true, RoadBuildStep.Phase.BRIDGE_RAMP, true));
        steps.add(new RoadBuildStep(edgeId, section.centerline().get(section.centerline().size() - 1), deckState, true, RoadBuildStep.Phase.BRIDGE_RAMP, true));
    }

    private void addRailings(UUID edgeId, CompiledRoadSection section, List<RoadBuildStep> steps) {
        for (BlockPos center : section.centerline()) {
            steps.add(new RoadBuildStep(edgeId, center.offset(0, 1, 3), Blocks.OAK_FENCE.defaultBlockState(), true, RoadBuildStep.Phase.BRIDGE_RAILING, true));
            steps.add(new RoadBuildStep(edgeId, center.offset(0, 1, -3), Blocks.OAK_FENCE.defaultBlockState(), true, RoadBuildStep.Phase.BRIDGE_RAILING, true));
        }
    }
}
