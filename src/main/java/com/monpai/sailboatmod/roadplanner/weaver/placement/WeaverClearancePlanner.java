package com.monpai.sailboatmod.roadplanner.weaver.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public final class WeaverClearancePlanner {
    private WeaverClearancePlanner() {
    }

    public static List<WeaverBuildCandidate> clearToSky(List<BlockPos> footprint, int maxBuildY) {
        if (footprint == null || footprint.isEmpty()) {
            return List.of();
        }

        List<WeaverBuildCandidate> candidates = new ArrayList<>();
        for (BlockPos surface : footprint) {
            for (int y = surface.getY() + 1; y <= maxBuildY; y++) {
                candidates.add(new WeaverBuildCandidate(
                        new BlockPos(surface.getX(), y, surface.getZ()),
                        Blocks.AIR.defaultBlockState(),
                        false));
            }
        }
        return List.copyOf(candidates);
    }
}
