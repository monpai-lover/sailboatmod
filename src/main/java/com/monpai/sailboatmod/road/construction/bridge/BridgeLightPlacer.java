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
                                        RoadMaterial material, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = startOrder;
        boolean leftSide = true;

        for (int i = 0; i < bridgePath.size(); i += config.getLightInterval()) {
            BlockPos center = bridgePath.get(i);
            Direction roadDir = getDirection(bridgePath, i);
            Direction perpDir = roadDir.getClockWise();

            int side = leftSide ? -(halfWidth + 1) : (halfWidth + 1);
            Direction armDir = leftSide ? perpDir.getOpposite() : perpDir;

            BlockPos base = new BlockPos(
                center.getX() + perpDir.getStepX() * side,
                deckY + 1,
                center.getZ() + perpDir.getStepZ() * side
            );

            for (int h = 0; h < 3; h++) {
                steps.add(new BuildStep(order++, base.above(h),
                    material.fence().defaultBlockState(), BuildPhase.STREETLIGHT));
            }

            BlockPos armPos = base.above(2).relative(armDir.getOpposite());
            steps.add(new BuildStep(order++, armPos,
                material.fence().defaultBlockState(), BuildPhase.STREETLIGHT));

            BlockPos lanternPos = armPos.below();
            steps.add(new BuildStep(order++, lanternPos,
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true),
                BuildPhase.STREETLIGHT));

            leftSide = !leftSide;
        }
        return steps;
    }

    private Direction getDirection(List<BlockPos> path, int index) {
        BlockPos curr = path.get(index);
        BlockPos next = (index < path.size() - 1) ? path.get(index + 1) : curr;
        BlockPos prev = (index > 0) ? path.get(index - 1) : curr;
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
