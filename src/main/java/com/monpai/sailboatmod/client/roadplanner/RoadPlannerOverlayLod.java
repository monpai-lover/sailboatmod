package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RoadPlannerOverlayLod {
    public static int stepForPixelsPerBlock(double pixelsPerBlock) {
        if (pixelsPerBlock >= 2.0D) {
            return 1;
        }
        if (pixelsPerBlock >= 1.0D) {
            return 4;
        }
        if (pixelsPerBlock >= 0.5D) {
            return 8;
        }
        return 16;
    }

    public static List<BlockPos> simplify(List<BlockPos> path, int lodStepBlocks) {
        if (path == null || path.size() <= 2 || lodStepBlocks <= 1) {
            return path == null ? List.of() : path.stream().filter(Objects::nonNull).map(BlockPos::immutable).toList();
        }
        ArrayList<BlockPos> simplified = new ArrayList<>();
        BlockPos lastKept = null;
        for (BlockPos point : path) {
            if (point == null) {
                continue;
            }
            if (lastKept == null) {
                lastKept = point.immutable();
                simplified.add(lastKept);
                continue;
            }
            long distance = Math.abs((long) point.getX() - lastKept.getX())
                    + Math.abs((long) point.getZ() - lastKept.getZ());
            if (distance >= lodStepBlocks) {
                lastKept = point.immutable();
                simplified.add(lastKept);
            }
        }
        BlockPos tail = path.get(path.size() - 1);
        if (tail != null && (simplified.isEmpty() || !tail.equals(simplified.get(simplified.size() - 1)))) {
            simplified.add(tail.immutable());
        }
        return List.copyOf(simplified);
    }

    private RoadPlannerOverlayLod() {
    }
}
