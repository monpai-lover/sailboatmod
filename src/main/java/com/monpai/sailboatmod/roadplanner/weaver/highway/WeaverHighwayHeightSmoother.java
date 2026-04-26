package com.monpai.sailboatmod.roadplanner.weaver.highway;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class WeaverHighwayHeightSmoother {
    private WeaverHighwayHeightSmoother() {
    }

    public static int[] smooth(int[] baseY,
                               List<BlockPos> centers,
                               boolean[] isBridge,
                               int slopeRunBlocks,
                               int slopeRiseBlocks) {
        if (baseY == null || centers == null || isBridge == null) {
            return baseY;
        }
        if (baseY.length != centers.size() || baseY.length != isBridge.length) {
            return baseY.clone();
        }
        if (baseY.length <= 2) {
            return baseY.clone();
        }

        int run = Math.max(1, slopeRunBlocks);
        int rise = Math.max(0, slopeRiseBlocks);
        if (rise == 0) {
            return baseY.clone();
        }
        double maxSlope = rise / (double) run;
        double[] smoothed = new double[baseY.length];
        for (int index = 0; index < baseY.length; index++) {
            smoothed[index] = baseY[index];
        }

        int index = 0;
        while (index < baseY.length) {
            while (index < baseY.length && isBridge[index]) {
                index++;
            }
            int start = index;
            while (index < baseY.length && !isBridge[index]) {
                index++;
            }
            int end = index - 1;
            if (start <= end) {
                clampRunForward(smoothed, centers, start, end, maxSlope);
                clampRunBackward(smoothed, centers, start, end, maxSlope);
            }
        }

        int[] result = new int[baseY.length];
        result[0] = baseY[0];
        for (int i = 1; i < baseY.length; i++) {
            if (isBridge[i]) {
                result[i] = baseY[i];
                continue;
            }
            double value = smoothed[i];
            result[i] = value >= result[i - 1] ? (int) Math.floor(value + 1.0E-9D) : (int) Math.ceil(value - 1.0E-9D);
        }
        return result;
    }

    private static void clampRunForward(double[] y, List<BlockPos> centers, int start, int end, double maxSlope) {
        for (int index = start + 1; index <= end; index++) {
            double maxDelta = maxSlope * dist2d(centers.get(index - 1), centers.get(index));
            y[index] = clamp(y[index], y[index - 1] - maxDelta, y[index - 1] + maxDelta);
        }
    }

    private static void clampRunBackward(double[] y, List<BlockPos> centers, int start, int end, double maxSlope) {
        for (int index = end - 1; index >= start; index--) {
            double maxDelta = maxSlope * dist2d(centers.get(index), centers.get(index + 1));
            y[index] = clamp(y[index], y[index + 1] - maxDelta, y[index + 1] + maxDelta);
        }
    }

    private static double dist2d(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
