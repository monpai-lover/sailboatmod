package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.List;

public class BridgeRampBuilder {

    public List<BuildStep> buildAscendingRamp(BlockPos startPos, Direction roadDir,
                                               int platformY, int deckY, int width,
                                               RoadMaterial material, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        Direction perpDir = roadDir.getClockWise();
        int order = startOrder;
        int heightDiff = deckY - platformY;
        int currentY = platformY;
        int stepIndex = 0;

        for (int h = 0; h < heightDiff; h++) {
            BlockPos stairCenter = startPos.relative(roadDir, stepIndex).atY(currentY);
            BlockState stairState = material.stair().defaultBlockState()
                .setValue(StairBlock.FACING, roadDir)
                .setValue(StairBlock.HALF, Half.BOTTOM);

            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = stairCenter.relative(perpDir, w);
                steps.add(new BuildStep(order++, pos, stairState, BuildPhase.RAMP));
                if (w == -halfWidth || w == halfWidth) {
                    steps.add(new BuildStep(order++, pos.above(),
                        material.fence().defaultBlockState(), BuildPhase.RAILING));
                }
            }
            currentY++;
            stepIndex++;

            if (h < heightDiff - 1) {
                BlockPos slabTopCenter = startPos.relative(roadDir, stepIndex).atY(currentY - 1);
                BlockState slabTop = material.slab().defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP);

                for (int w = -halfWidth; w <= halfWidth; w++) {
                    BlockPos pos = slabTopCenter.relative(perpDir, w);
                    steps.add(new BuildStep(order++, pos, slabTop, BuildPhase.RAMP));
                }
                stepIndex++;

                BlockPos slabBotCenter = startPos.relative(roadDir, stepIndex).atY(currentY);
                BlockState slabBot = material.slab().defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM);

                for (int w = -halfWidth; w <= halfWidth; w++) {
                    BlockPos pos = slabBotCenter.relative(perpDir, w);
                    steps.add(new BuildStep(order++, pos, slabBot, BuildPhase.RAMP));
                }
                stepIndex++;
            }
        }
        return steps;
    }

    public List<BuildStep> buildDescendingRamp(BlockPos startPos, Direction roadDir,
                                                int deckY, int platformY, int width,
                                                RoadMaterial material, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        Direction perpDir = roadDir.getClockWise();
        int order = startOrder;
        int heightDiff = deckY - platformY;
        int currentY = deckY;
        int stepIndex = 0;

        for (int h = 0; h < heightDiff; h++) {
            if (h > 0) {
                BlockPos slabBotCenter = startPos.relative(roadDir, stepIndex).atY(currentY);
                BlockState slabBot = material.slab().defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM);

                for (int w = -halfWidth; w <= halfWidth; w++) {
                    BlockPos pos = slabBotCenter.relative(perpDir, w);
                    steps.add(new BuildStep(order++, pos, slabBot, BuildPhase.RAMP));
                }
                stepIndex++;

                BlockPos slabTopCenter = startPos.relative(roadDir, stepIndex).atY(currentY - 1);
                BlockState slabTop = material.slab().defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP);

                for (int w = -halfWidth; w <= halfWidth; w++) {
                    BlockPos pos = slabTopCenter.relative(perpDir, w);
                    steps.add(new BuildStep(order++, pos, slabTop, BuildPhase.RAMP));
                }
                stepIndex++;
            }

            currentY--;
            BlockPos stairCenter = startPos.relative(roadDir, stepIndex).atY(currentY);
            BlockState stairState = material.stair().defaultBlockState()
                .setValue(StairBlock.FACING, roadDir.getOpposite())
                .setValue(StairBlock.HALF, Half.BOTTOM);

            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = stairCenter.relative(perpDir, w);
                steps.add(new BuildStep(order++, pos, stairState, BuildPhase.RAMP));
                if (w == -halfWidth || w == halfWidth) {
                    steps.add(new BuildStep(order++, pos.above(),
                        material.fence().defaultBlockState(), BuildPhase.RAILING));
                }
            }
            stepIndex++;
        }
        return steps;
    }
}
