package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class AboveColumnClearer {

    public static List<BuildStep> clear(BlockPos surfacePos, int width, int clearHeight,
                                         Direction roadDir, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        Direction perpDir = roadDir.getClockWise();
        int order = startOrder;

        for (int w = -halfWidth; w <= halfWidth; w++) {
            BlockPos base = surfacePos.relative(perpDir, w);

            for (int h = 1; h <= clearHeight; h++) {
                BlockPos pos = base.above(h);
                steps.add(new BuildStep(order++, pos, Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
            }

            // Extra: clear bushes/snow at clearHeight+1
            BlockPos extra = base.above(clearHeight + 1);
            steps.add(new BuildStep(order++, extra, Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));

            // Convert grass to dirt 2 blocks below surface
            for (int d = 1; d <= 2; d++) {
                BlockPos below = base.below(d);
                steps.add(new BuildStep(order++, below, Blocks.DIRT.defaultBlockState(), BuildPhase.FOUNDATION));
            }
        }
        return steps;
    }

    public static boolean shouldClear(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) return false;
        return true;
    }
}
