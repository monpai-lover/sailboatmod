package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public final class RoadBezierCenterline {
    private static final int MAX_SAFE_STEP_HEIGHT = 5;
    private static final int MAX_SEARCH_RADIUS = 1;
    private static final int MAX_SMOOTHABLE_SPAN = 8;
    private static final double[] CORNER_SAMPLE_T = {0.2D, 0.5D, 0.8D};

    private RoadBezierCenterline() {
    }

    public static List<BlockPos> build(List<BlockPos> routeNodes,
                                       Function<BlockPos, SurfaceSample> sampler,
                                       Set<Long> blockedColumns) {
        Objects.requireNonNull(routeNodes, "routeNodes");
        Objects.requireNonNull(sampler, "sampler");
        Objects.requireNonNull(blockedColumns, "blockedColumns");
        routeNodes.forEach(pos -> Objects.requireNonNull(pos, "routeNodes contains null"));
        if (routeNodes.isEmpty()) {
            return List.of();
        }

        List<BlockPos> controlPoints = simplify(routeNodes);
        List<BlockPos> basePath = rasterize(controlPoints, sampler, blockedColumns, true);
        if (controlPoints.size() < 3 || basePath.isEmpty()) {
            return basePath.isEmpty() ? List.copyOf(controlPoints) : basePath;
        }

        List<BlockPos> smoothedSeedPoints = new ArrayList<>();
        smoothedSeedPoints.add(controlPoints.get(0));
        for (int i = 1; i < controlPoints.size() - 1; i++) {
            BlockPos previous = controlPoints.get(i - 1);
            BlockPos current = controlPoints.get(i);
            BlockPos next = controlPoints.get(i + 1);
            List<BlockPos> curve = trySmoothCorner(previous, current, next, sampler, blockedColumns);
            if (curve.isEmpty()) {
                smoothedSeedPoints.add(current);
                continue;
            }
            for (int index = 1; index < curve.size() - 1; index++) {
                smoothedSeedPoints.add(curve.get(index));
            }
        }
        smoothedSeedPoints.add(controlPoints.get(controlPoints.size() - 1));

        List<BlockPos> smoothedPath = rasterize(smoothedSeedPoints, sampler, blockedColumns, true);
        return smoothedPath.isEmpty() ? basePath : smoothedPath;
    }

    private static List<BlockPos> simplify(List<BlockPos> routeNodes) {
        List<BlockPos> simplified = new ArrayList<>();
        for (BlockPos pos : routeNodes) {
            if (simplified.isEmpty()) {
                simplified.add(pos.immutable());
                continue;
            }
            BlockPos previous = simplified.get(simplified.size() - 1);
            if (sameColumn(previous, pos)) {
                continue;
            }
            simplified.add(pos.immutable());
            while (simplified.size() >= 3) {
                int lastIndex = simplified.size() - 1;
                BlockPos before = simplified.get(lastIndex - 2);
                BlockPos middle = simplified.get(lastIndex - 1);
                BlockPos after = simplified.get(lastIndex);
                if (!isCollinear(before, middle, after)) {
                    break;
                }
                simplified.remove(lastIndex - 1);
            }
        }
        return List.copyOf(simplified);
    }

    private static List<BlockPos> trySmoothCorner(BlockPos previous,
                                                  BlockPos current,
                                                  BlockPos next,
                                                  Function<BlockPos, SurfaceSample> sampler,
                                                  Set<Long> blockedColumns) {
        if (isCollinear(previous, current, next)
                || squaredDistance(previous, current) > MAX_SMOOTHABLE_SPAN * MAX_SMOOTHABLE_SPAN
                || squaredDistance(current, next) > MAX_SMOOTHABLE_SPAN * MAX_SMOOTHABLE_SPAN) {
            return List.of();
        }

        SurfaceSample previousSample = safeExact(previous, sampler, blockedColumns, false);
        SurfaceSample currentSample = safeExact(current, sampler, blockedColumns, false);
        SurfaceSample nextSample = safeExact(next, sampler, blockedColumns, false);
        if (previousSample == null || currentSample == null || nextSample == null) {
            return List.of();
        }

        List<BlockPos> curve = new ArrayList<>();
        curve.add(previousSample.surfacePos());
        BlockPos last = previousSample.surfacePos();
        for (double t : CORNER_SAMPLE_T) {
            int x = (int) Math.round(quadratic(previous.getX(), current.getX(), next.getX(), t));
            int z = (int) Math.round(quadratic(previous.getZ(), current.getZ(), next.getZ(), t));
            BlockPos resolved = findNearestSurface(x, z, last.getY(), sampler, blockedColumns, false);
            if (resolved == null || Math.abs(resolved.getY() - last.getY()) > MAX_SAFE_STEP_HEIGHT) {
                return List.of();
            }
            if (!sameColumn(curve.get(curve.size() - 1), resolved)) {
                curve.add(resolved);
                last = resolved;
            }
        }
        if (Math.abs(nextSample.surfacePos().getY() - last.getY()) > MAX_SAFE_STEP_HEIGHT) {
            return List.of();
        }
        if (!sameColumn(curve.get(curve.size() - 1), nextSample.surfacePos())) {
            curve.add(nextSample.surfacePos());
        }
        return curve.size() < 3 ? List.of() : List.copyOf(curve);
    }

    private static List<BlockPos> rasterize(List<BlockPos> seedPoints,
                                            Function<BlockPos, SurfaceSample> sampler,
                                            Set<Long> blockedColumns,
                                            boolean allowBridge) {
        LinkedHashMap<Long, BlockPos> rasterized = new LinkedHashMap<>();
        BlockPos start = findNearestSurface(
                seedPoints.get(0).getX(),
                seedPoints.get(0).getZ(),
                seedPoints.get(0).getY(),
                sampler,
                blockedColumns,
                allowBridge
        );
        if (start == null) {
            return List.of();
        }
        rasterized.put(columnKey(start), start);
        BlockPos last = start;
        for (int i = 1; i < seedPoints.size(); i++) {
            for (BlockPos rawStep : bresenham(last, seedPoints.get(i))) {
                if (sameColumn(rawStep, last)) {
                    continue;
                }
                BlockPos resolved = findNearestSurface(rawStep.getX(), rawStep.getZ(), last.getY(), sampler, blockedColumns, allowBridge);
                if (resolved == null || Math.abs(resolved.getY() - last.getY()) > MAX_SAFE_STEP_HEIGHT) {
                    return List.of();
                }
                rasterized.put(columnKey(resolved), resolved);
                last = resolved;
            }
        }
        return List.copyOf(rasterized.values().stream().toList());
    }

    private static SurfaceSample safeExact(BlockPos pos,
                                           Function<BlockPos, SurfaceSample> sampler,
                                           Set<Long> blockedColumns,
                                           boolean allowBridge) {
        SurfaceSample sample = sampler.apply(new BlockPos(pos.getX(), 0, pos.getZ()));
        return isSafe(sample, blockedColumns, allowBridge) ? sample : null;
    }

    private static BlockPos findNearestSurface(int x,
                                               int z,
                                               int preferredY,
                                               Function<BlockPos, SurfaceSample> sampler,
                                               Set<Long> blockedColumns,
                                               boolean allowBridge) {
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestHeightDiff = Integer.MAX_VALUE;
        for (int radius = 0; radius <= MAX_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    SurfaceSample sample = sampler.apply(new BlockPos(x + dx, 0, z + dz));
                    if (!isSafe(sample, blockedColumns, allowBridge)) {
                        continue;
                    }
                    BlockPos surfacePos = sample.surfacePos();
                    int distance = Math.abs(surfacePos.getX() - x) + Math.abs(surfacePos.getZ() - z);
                    int heightDiff = Math.abs(surfacePos.getY() - preferredY);
                    if (distance < bestDistance || (distance == bestDistance && heightDiff < bestHeightDiff)) {
                        best = surfacePos;
                        bestDistance = distance;
                        bestHeightDiff = heightDiff;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static boolean isSafe(SurfaceSample sample, Set<Long> blockedColumns, boolean allowBridge) {
        return sample != null
                && sample.surfacePos() != null
                && !sample.blocked()
                && !blockedColumns.contains(columnKey(sample.surfacePos()))
                && (allowBridge || !sample.requiresBridge());
    }

    private static List<BlockPos> bresenham(BlockPos from, BlockPos to) {
        List<BlockPos> line = new ArrayList<>();
        int x0 = from.getX();
        int z0 = from.getZ();
        int x1 = to.getX();
        int z1 = to.getZ();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        while (true) {
            line.add(new BlockPos(x0, from.getY(), z0));
            if (x0 == x1 && z0 == z1) {
                break;
            }
            int doubled = err * 2;
            if (doubled > -dz) {
                err -= dz;
                x0 += sx;
            }
            if (doubled < dx) {
                err += dx;
                z0 += sz;
            }
        }
        return line;
    }

    private static boolean sameColumn(BlockPos left, BlockPos right) {
        return left.getX() == right.getX() && left.getZ() == right.getZ();
    }

    private static boolean isCollinear(BlockPos previous, BlockPos current, BlockPos next) {
        int firstDx = Integer.compare(current.getX(), previous.getX());
        int firstDz = Integer.compare(current.getZ(), previous.getZ());
        int secondDx = Integer.compare(next.getX(), current.getX());
        int secondDz = Integer.compare(next.getZ(), current.getZ());
        return firstDx == secondDx && firstDz == secondDz;
    }

    private static int squaredDistance(BlockPos left, BlockPos right) {
        int dx = right.getX() - left.getX();
        int dz = right.getZ() - left.getZ();
        return dx * dx + dz * dz;
    }

    private static double quadratic(double start, double control, double end, double t) {
        double inverse = 1.0D - t;
        return (inverse * inverse * start) + (2.0D * inverse * t * control) + (t * t * end);
    }

    private static long columnKey(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
    }

    public record SurfaceSample(BlockPos surfacePos, boolean blocked, boolean requiresBridge) {
    }
}
