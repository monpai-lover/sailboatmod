package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.config.AppearanceConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class TunnelDigger {
    private final AppearanceConfig config;

    public TunnelDigger(AppearanceConfig config) {
        this.config = config;
    }

    public boolean isMountainInterior(BlockPos surfacePos, int checkHeight,
                                       net.minecraft.server.level.ServerLevel level) {
        for (int h = 1; h <= checkHeight; h++) {
            BlockPos above = surfacePos.above(h);
            if (level.getBlockState(above).isAir()) return false;
        }
        return true;
    }

    public List<BuildStep> dig(BlockPos surfacePos, int width, Direction roadDir, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2 + 1; // +1 for wall gap
        int clearHeight = config.getTunnelClearHeight();
        Direction perpDir = roadDir.getClockWise();
        int order = startOrder;

        for (int w = -halfWidth; w <= halfWidth; w++) {
            BlockPos base = surfacePos.relative(perpDir, w);
            boolean isWall = (w == -halfWidth || w == halfWidth);

            for (int h = 1; h <= clearHeight; h++) {
                BlockPos pos = base.above(h);
                if (isWall) {
                    // Side walls: stone bricks
                    steps.add(new BuildStep(order++, pos,
                        Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.RAILING));
                } else {
                    // Interior: clear to air
                    steps.add(new BuildStep(order++, pos,
                        Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
                }
            }

            // Ceiling: stone bricks (prevent gravel/sand collapse)
            BlockPos ceiling = base.above(clearHeight + 1);
            steps.add(new BuildStep(order++, ceiling,
                Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.RAILING));
        }

        return steps;
    }

    public List<BuildStep> placeTunnelLight(BlockPos surfacePos, Direction roadDir, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        // Wall torch on both sides
        BlockPos leftWall = surfacePos.relative(roadDir.getClockWise(), -(surfacePos.getX() % 2 == 0 ? 1 : -1)).above(2);
        steps.add(new BuildStep(startOrder, leftWall,
            Blocks.WALL_TORCH.defaultBlockState(), BuildPhase.STREETLIGHT));
        return steps;
    }
}
