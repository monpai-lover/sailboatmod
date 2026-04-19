package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import java.util.Set;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public final class RoadCoreExclusion {
    public static final int DEFAULT_RADIUS = 3;

    private RoadCoreExclusion() {}

    public static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public static boolean isExcluded(BlockPos pos, Set<Long> excludedColumns) {
        if (pos == null || excludedColumns == null || excludedColumns.isEmpty()) {
            return false;
        }
        return excludedColumns.contains(columnKey(pos.getX(), pos.getZ()));
    }

    public static Set<Long> collectExcludedColumns(Set<BlockPos> cores, int radius) {
        return Set.of();
    }
}
