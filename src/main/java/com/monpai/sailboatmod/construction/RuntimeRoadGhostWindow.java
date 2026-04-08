package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RuntimeRoadGhostWindow {
    private RuntimeRoadGhostWindow() {
    }

    public static List<BlockPos> clip(List<BlockPos> positions, BlockPos center, int radius) {
        if (positions == null || positions.isEmpty() || center == null || radius < 0) {
            return List.of();
        }
        int radiusSqr = radius * radius;
        List<BlockPos> clipped = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (pos != null && pos.distSqr(center) <= radiusSqr) {
                clipped.add(pos);
            }
        }
        return List.copyOf(clipped);
    }
}
