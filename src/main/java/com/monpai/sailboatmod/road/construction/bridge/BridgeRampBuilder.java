package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
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
        // Each full block needs 2 slab steps (bottom at Y, top at Y = +0.5 visual)
        int totalSlabSteps = heightDiff * 2;
        int currentY = platformY;
        boolean useBottom = true; // alternate bottom, top, bottom, top...

        for (int s = 0; s < totalSlabSteps; s++) {
            BlockPos center = startPos.relative(roadDir, s);
            SlabType slabType = useBottom ? SlabType.BOTTOM : SlabType.TOP;
            BlockState slabState = material.slab().defaultBlockState()
                .setValue(SlabBlock.TYPE, slabType);

            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = center.relative(perpDir, w).atY(currentY);
                steps.add(new BuildStep(order++, pos, slabState, BuildPhase.RAMP));
            }
            // Railings on both sides of ramp
            BlockPos leftRail = center.relative(perpDir, -(halfWidth + 1)).atY(currentY + 1);
            BlockPos rightRail = center.relative(perpDir, halfWidth + 1).atY(currentY + 1);
            steps.add(new BuildStep(order++, leftRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
            steps.add(new BuildStep(order++, rightRail, material.fence().defaultBlockState(), BuildPhase.RAILING));

            if (!useBottom) {
                currentY++; // after top slab, move up one full block
            }
            useBottom = !useBottom;
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
        int totalSlabSteps = heightDiff * 2;
        int currentY = deckY;
        boolean useTop = true; // descending: top, bottom, top, bottom...

        for (int s = 0; s < totalSlabSteps; s++) {
            BlockPos center = startPos.relative(roadDir, s);
            SlabType slabType = useTop ? SlabType.TOP : SlabType.BOTTOM;
            BlockState slabState = material.slab().defaultBlockState()
                .setValue(SlabBlock.TYPE, slabType);

            if (useTop) {
                currentY--; // before top slab going down, drop one block
            }

            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = center.relative(perpDir, w).atY(currentY);
                steps.add(new BuildStep(order++, pos, slabState, BuildPhase.RAMP));
            }
            BlockPos leftRail = center.relative(perpDir, -(halfWidth + 1)).atY(currentY + 1);
            BlockPos rightRail = center.relative(perpDir, halfWidth + 1).atY(currentY + 1);
            steps.add(new BuildStep(order++, leftRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
            steps.add(new BuildStep(order++, rightRail, material.fence().defaultBlockState(), BuildPhase.RAILING));

            useTop = !useTop;
        }
        return steps;
    }
}