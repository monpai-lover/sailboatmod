package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public final class RoadCoreExclusion {
    public static final int DEFAULT_RADIUS = 5;

    private RoadCoreExclusion() {
    }

    public static Set<Long> collectExcludedColumns(Collection<BlockPos> cores, int radius) {
        if (cores == null || cores.isEmpty() || radius < 0) {
            return Set.of();
        }
        LinkedHashSet<Long> excluded = new LinkedHashSet<>();
        for (BlockPos core : cores) {
            if (core == null) {
                continue;
            }
            for (int x = core.getX() - radius; x <= core.getX() + radius; x++) {
                for (int z = core.getZ() - radius; z <= core.getZ() + radius; z++) {
                    excluded.add(columnKey(x, z));
                }
            }
        }
        return excluded.isEmpty() ? Set.of() : Set.copyOf(excluded);
    }

    public static boolean isExcluded(BlockPos pos, Set<Long> excludedColumns) {
        if (pos == null || excludedColumns == null || excludedColumns.isEmpty()) {
            return false;
        }
        return excludedColumns.contains(columnKey(pos.getX(), pos.getZ()));
    }

    public static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }
}
