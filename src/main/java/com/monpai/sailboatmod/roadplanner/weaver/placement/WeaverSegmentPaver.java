package com.monpai.sailboatmod.roadplanner.weaver.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WeaverSegmentPaver {
    private WeaverSegmentPaver() {
    }

    public static List<WeaverBuildCandidate> paveCenterline(List<BlockPos> centers, int width, BlockState roadState) {
        if (width != 3 && width != 5 && width != 7) {
            throw new IllegalArgumentException("width must be 3, 5, or 7");
        }
        if (centers == null || centers.isEmpty()) {
            return List.of();
        }

        Set<BlockPos> footprint = new LinkedHashSet<>();
        int radius = width / 2;
        for (int index = 0; index < centers.size(); index++) {
            BlockPos center = centers.get(index);
            Direction2d direction = directionAt(centers, index);
            for (int offset = -radius; offset <= radius; offset++) {
                footprint.add(center.offset(direction.normalX() * offset, 0, direction.normalZ() * offset));
            }
        }

        List<WeaverBuildCandidate> candidates = new ArrayList<>(footprint.size());
        for (BlockPos pos : footprint) {
            candidates.add(new WeaverBuildCandidate(pos, roadState, true));
        }
        return List.copyOf(candidates);
    }

    private static Direction2d directionAt(List<BlockPos> centers, int index) {
        BlockPos previous = centers.get(Math.max(0, index - 1));
        BlockPos next = centers.get(Math.min(centers.size() - 1, index + 1));
        int dx = Integer.compare(next.getX() - previous.getX(), 0);
        int dz = Integer.compare(next.getZ() - previous.getZ(), 0);
        if (dx == 0 && dz == 0) {
            dx = 1;
        }
        return new Direction2d(dx, dz, -dz, dx);
    }

    private record Direction2d(int x, int z, int normalX, int normalZ) {
    }
}
