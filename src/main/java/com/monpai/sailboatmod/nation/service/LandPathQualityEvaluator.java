package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class LandPathQualityEvaluator {
    private LandPathQualityEvaluator() {
    }

    public static int elevationVariance(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos pos : path) {
            if (pos == null) {
                continue;
            }
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
        }
        if (minY == Integer.MAX_VALUE) {
            return 0;
        }
        return Math.max(0, maxY - minY);
    }

    public static int fragmentedColumns(List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return 0;
        }
        int fragmented = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            if (previous == null || current == null) {
                fragmented++;
                continue;
            }
            if (Math.max(Math.abs(current.getX() - previous.getX()), Math.abs(current.getZ() - previous.getZ())) > 1) {
                fragmented++;
            }
        }
        return fragmented;
    }
}
