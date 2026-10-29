package com.monpai.sailboatmod.transport.control;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.function.Predicate;

/** A complete, bounded scan. Unchecked or unloaded cells can never produce an all-clear result. */
public final class SweptTerrainProbe {
    public enum Result {
        CLEAR,
        BLOCKED,
        WAITING
    }

    private static final long MAX_CELLS = 1_048_576L;
    private AABB bounds;
    private int minX, minY, minZ, sizeX, sizeY;
    private long cursor, cells;
    private boolean finished;

    public Result scan(
            AABB requested, int budget, Predicate<BlockPos> loaded, Predicate<BlockPos> collision) {
        if (requested == null || budget <= 0 || loaded == null || collision == null)
            return Result.WAITING;
        if (!requested.equals(bounds) || finished) {
            if (!begin(requested)) return Result.WAITING;
        }
        int remaining = Math.min(budget, 32_768);
        while (cursor < cells && remaining-- > 0) {
            long horizontal = cursor / sizeY;
            BlockPos at =
                    new BlockPos(
                            minX + (int) (horizontal % sizeX),
                            minY + (int) (cursor % sizeY),
                            minZ + (int) (horizontal / sizeX));
            try {
                if (!loaded.test(at)) return Result.WAITING;
                if (collision.test(at)) {
                    finished = true;
                    return Result.BLOCKED;
                }
            } catch (RuntimeException unavailable) {
                return Result.WAITING;
            }
            cursor++;
        }
        if (cursor < cells) return Result.WAITING;
        finished = true; // The next call rechecks even identical bounds; terrain may have changed.
        return Result.CLEAR;
    }

    private boolean begin(AABB requested) {
        bounds = requested;
        finished = false;
        cursor = cells = 0;
        double[] coordinates = {
            requested.minX,
            requested.minY,
            requested.minZ,
            requested.maxX,
            requested.maxY,
            requested.maxZ
        };
        for (double coordinate : coordinates) {
            if (!Double.isFinite(coordinate)
                    || coordinate <= Integer.MIN_VALUE + 1D
                    || coordinate >= Integer.MAX_VALUE - 1D) return invalid();
        }
        minX = (int) Math.floor(requested.minX);
        minY = (int) Math.floor(requested.minY);
        minZ = (int) Math.floor(requested.minZ);
        long width = (long) Math.ceil(requested.maxX) - minX;
        long height = (long) Math.ceil(requested.maxY) - minY;
        long depth = (long) Math.ceil(requested.maxZ) - minZ;
        if (width <= 0
                || height <= 0
                || depth <= 0
                || width > MAX_CELLS
                || height > MAX_CELLS / width
                || depth > MAX_CELLS / (width * height)) return invalid();
        sizeX = (int) width;
        sizeY = (int) height;
        cells = width * height * depth;
        return true;
    }

    private boolean invalid() {
        bounds = null;
        return false;
    }
}
