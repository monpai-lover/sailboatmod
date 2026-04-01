package com.monpai.sailboatmod.resident.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages construction site scaffolding (inspired by MineColonies)
 */
public class ConstructionScaffoldingService {

    public static List<BlockPos> placeScaffolding(ServerLevel level, BlockPos center, int sizeW, int sizeH, int sizeD) {
        List<BlockPos> scaffoldPositions = new ArrayList<>();
        BlockState scaffold = Blocks.SCAFFOLDING.defaultBlockState();

        // Place scaffolding around the perimeter
        for (int y = 0; y < sizeH + 2; y++) {
            // Four corners
            placeScaffoldBlock(level, center.offset(0, y, 0), scaffold, scaffoldPositions);
            placeScaffoldBlock(level, center.offset(sizeW, y, 0), scaffold, scaffoldPositions);
            placeScaffoldBlock(level, center.offset(0, y, sizeD), scaffold, scaffoldPositions);
            placeScaffoldBlock(level, center.offset(sizeW, y, sizeD), scaffold, scaffoldPositions);

            // Edges (every 3 blocks)
            for (int i = 3; i < sizeW; i += 3) {
                placeScaffoldBlock(level, center.offset(i, y, 0), scaffold, scaffoldPositions);
                placeScaffoldBlock(level, center.offset(i, y, sizeD), scaffold, scaffoldPositions);
            }
            for (int i = 3; i < sizeD; i += 3) {
                placeScaffoldBlock(level, center.offset(0, y, i), scaffold, scaffoldPositions);
                placeScaffoldBlock(level, center.offset(sizeW, y, i), scaffold, scaffoldPositions);
            }
        }

        return scaffoldPositions;
    }

    private static void placeScaffoldBlock(ServerLevel level, BlockPos pos, BlockState scaffold, List<BlockPos> positions) {
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, scaffold, 3);
            positions.add(pos);
        }
    }

    public static void removeScaffolding(ServerLevel level, List<BlockPos> scaffoldPositions) {
        for (BlockPos pos : scaffoldPositions) {
            if (level.getBlockState(pos).is(Blocks.SCAFFOLDING)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }
}
