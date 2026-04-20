package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;

import java.util.ArrayList;
import java.util.List;

public class BridgeLightPlacer {
    private final BridgeConfig config;

    public BridgeLightPlacer(BridgeConfig config) {
        this.config = config;
    }

    public List<BuildStep> placeLights(List<BlockPos> bridgePath, int deckY, int width,
                                        RoadMaterial material, Direction roadDir, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = startOrder;
        boolean leftSide = true;

        for (int i = 0; i < bridgePath.size(); i += config.getLightInterval()) {
            Direction localDir = BridgeDeckPlacer.getDirection(bridgePath, i);
            Direction perpDir = localDir.getClockWise();
            // Light is built on top of the railing (deckY+1 is railing, deckY+2 is light base)
            int side = leftSide ? -(halfWidth + 1) : (halfWidth + 1);
            BlockPos center = bridgePath.get(i);
            int baseY = deckY + 2; // on top of railing

            BlockPos base = new BlockPos(
                center.getX() + perpDir.getStepX() * side, baseY,
                center.getZ() + perpDir.getStepZ() * side);

            // 3 fence posts vertically
            for (int h = 0; h < 3; h++) {
                steps.add(new BuildStep(order++, base.above(h),
                    material.fence().defaultBlockState(), BuildPhase.STREETLIGHT));
            }

            // Horizontal arm extending INWARD over the road
            Direction inward = leftSide ? perpDir : perpDir.getOpposite();
            BlockPos armPos = base.above(2).relative(inward);
            steps.add(new BuildStep(order++, armPos,
                material.fence().defaultBlockState(), BuildPhase.STREETLIGHT));

            // Hanging lantern below the arm - HARDCODED Blocks.LANTERN
            BlockPos lanternPos = armPos.below();
            steps.add(new BuildStep(order++, lanternPos,
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true),
                BuildPhase.STREETLIGHT));

            leftSide = !leftSide;
        }
        return steps;
    }
}
