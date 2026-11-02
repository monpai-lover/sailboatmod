package com.monpai.sailboatmod.road.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WidthRasterizer {
    private WidthRasterizer() {
    }

    public static Set<BlockPos> rasterizeCrossSection(BlockPos center,
                                                      double perpX,
                                                      double perpZ,
                                                      int halfWidth) {
        return new LinkedHashSet<>(crossSectionLine(center, perpX, perpZ, halfWidth));
    }

    public static Set<BlockPos> stitchAdjacentCrossSections(BlockPos previousCenter,
                                                            double previousPerpX,
                                                            double previousPerpZ,
                                                            BlockPos center,
                                                            double perpX,
                                                            double perpZ,
                                                            int halfWidth) {
        LinkedHashSet<BlockPos> stitched = new LinkedHashSet<>();
        List<BlockPos> previousSection = crossSectionLine(previousCenter, previousPerpX, previousPerpZ, halfWidth);
        List<BlockPos> currentSection = crossSectionLine(center, perpX, perpZ, halfWidth);
        int steps = Math.max(previousSection.size(), currentSection.size());

        for (int i = 0; i < steps; i++) {
            BlockPos from = sample(previousSection, i, steps);
            BlockPos to = sample(currentSection, i, steps);
            stitched.addAll(bresenhamLine(from, to));
        }
        return stitched;
    }

    public static Set<BlockPos> fillCircle(BlockPos center, int radius) {
        LinkedHashSet<BlockPos> footprint = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    footprint.add(new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz));
                }
            }
        }
        return footprint;
    }

    private static List<BlockPos> crossSectionLine(BlockPos center,
                                                   double perpX,
                                                   double perpZ,
                                                   int halfWidth) {
        BlockPos left = offset(center, perpX, perpZ, -halfWidth);
        BlockPos right = offset(center, perpX, perpZ, halfWidth);
        return bresenhamLine(left, right);
    }

    private static BlockPos offset(BlockPos center, double perpX, double perpZ, int distance) {
        int x = center.getX() + (int) Math.round(perpX * distance);
        int z = center.getZ() + (int) Math.round(perpZ * distance);
        return new BlockPos(x, center.getY(), z);
    }

    private static BlockPos sample(List<BlockPos> line, int index, int steps) {
        if (line.size() == 1 || steps <= 1) {
            return line.get(0);
        }
        int mappedIndex = (int) Math.round(index * (line.size() - 1) / (double) (steps - 1));
        return line.get(Math.max(0, Math.min(mappedIndex, line.size() - 1)));
    }

    private static List<BlockPos> bresenhamLine(BlockPos start, BlockPos end) {
        List<BlockPos> line = new ArrayList<>();
        int x0 = start.getX();
        int z0 = start.getZ();
        int x1 = end.getX();
        int z1 = end.getZ();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;

        while (true) {
            line.add(new BlockPos(x, start.getY(), z));
            if (x == x1 && z == z1) {
                return line;
            }
            int doubled = err * 2;
            if (doubled > -dz) {
                err -= dz;
                x += sx;
            }
            if (doubled < dx) {
                err += dx;
                z += sz;
            }
        }
    }
}
