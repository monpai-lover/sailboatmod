package com.monpai.sailboatmod.resident.service;

import com.monpai.sailboatmod.nation.service.BlueprintService;
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
        BlueprintService.PlacementBounds bounds = new BlueprintService.PlacementBounds(
                center,
                center.offset(Math.max(0, sizeW - 1), Math.max(0, sizeH - 1), Math.max(0, sizeD - 1))
        );
        return placeScaffolding(level, bounds);
    }

    public static List<BlockPos> placeScaffolding(ServerLevel level, BlueprintService.PlacementBounds bounds) {
        List<BlockPos> scaffoldPositions = new ArrayList<>();
        BlockState scaffold = Blocks.SCAFFOLDING.defaultBlockState();
        int minX = bounds.min().getX() - 1;
        int maxX = bounds.max().getX() + 1;
        int minZ = bounds.min().getZ() - 1;
        int maxZ = bounds.max().getZ() + 1;
        int baseY = bounds.min().getY();
        int topY = bounds.max().getY() + 1;

        for (int x = minX; x <= maxX; x += 3) {
            placeScaffoldColumn(level, new BlockPos(x, baseY, minZ), topY, scaffold, scaffoldPositions);
            placeScaffoldColumn(level, new BlockPos(x, baseY, maxZ), topY, scaffold, scaffoldPositions);
        }

        for (int z = minZ; z <= maxZ; z += 3) {
            placeScaffoldColumn(level, new BlockPos(minX, baseY, z), topY, scaffold, scaffoldPositions);
            placeScaffoldColumn(level, new BlockPos(maxX, baseY, z), topY, scaffold, scaffoldPositions);
        }

        placeScaffoldColumn(level, new BlockPos(maxX, baseY, maxZ), topY, scaffold, scaffoldPositions);

        // Add a top perimeter ring so the site reads as an actual work zone.
        for (int x = minX; x <= maxX; x++) {
            placeScaffoldBlock(level, new BlockPos(x, topY, minZ), scaffold, scaffoldPositions);
            placeScaffoldBlock(level, new BlockPos(x, topY, maxZ), scaffold, scaffoldPositions);
        }
        for (int z = minZ; z <= maxZ; z++) {
            placeScaffoldBlock(level, new BlockPos(minX, topY, z), scaffold, scaffoldPositions);
            placeScaffoldBlock(level, new BlockPos(maxX, topY, z), scaffold, scaffoldPositions);
        }

        return scaffoldPositions;
    }

    private static void placeScaffoldColumn(ServerLevel level, BlockPos columnPos, int topY, BlockState scaffold, List<BlockPos> positions) {
        int startY = findColumnStart(level, columnPos);
        for (int y = startY; y <= topY; y++) {
            placeScaffoldBlock(level, new BlockPos(columnPos.getX(), y, columnPos.getZ()), scaffold, positions);
        }
    }

    private static int findColumnStart(ServerLevel level, BlockPos target) {
        for (int y = target.getY(); y >= target.getY() - 4; y--) {
            BlockPos groundPos = new BlockPos(target.getX(), y, target.getZ());
            BlockState groundState = level.getBlockState(groundPos);
            BlockPos placePos = groundPos.above();
            BlockState placeState = level.getBlockState(placePos);
            if (groundState.isSolid() && (placeState.isAir() || placeState.canBeReplaced() || placeState.liquid())) {
                return placePos.getY();
            }
        }
        return target.getY();
    }

    private static void placeScaffoldBlock(ServerLevel level, BlockPos pos, BlockState scaffold, List<BlockPos> positions) {
        BlockState existing = level.getBlockState(pos);
        if (existing.isAir() || existing.canBeReplaced() || existing.liquid()) {
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
