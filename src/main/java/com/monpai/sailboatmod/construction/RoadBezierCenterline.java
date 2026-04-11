package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public final class RoadBezierCenterline {
    private static final int MAX_SAFE_STEP_HEIGHT = 8;
    private static final int MAX_SEARCH_RADIUS = 1;
    private static final int MAX_CONTIGUOUS_BRIDGE_COLUMNS = 5;
    private static final int MAX_TOTAL_BRIDGE_COLUMNS = 14;
    private static final double MAX_BRIDGE_SHARE = 0.20D;
    private static final double CURVE_RESAMPLE_INTERVAL = 0.85D;
    private static final double CURVE_DENSE_SAMPLES_PER_BLOCK = 4.0D;
    private static final double CORNER_ROUNDING_FACTOR = 0.60D;
    private static final double MAX_CORNER_ROUNDING_DISTANCE = 4.0D;
    private static final double MIN_SHARED_SEGMENT_REMAINING = 0.05D;

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

        List<BlockPos> baselinePath = simplifyColumns(routeNodes);
        List<BlockPos> controlPoints = simplify(routeNodes);
        List<BlockPos> basePath = rasterize(controlPoints, sampler, blockedColumns, collectBridgeColumns(baselinePath, sampler, blockedColumns));
        if (!isValidCandidatePath(basePath, baselinePath, sampler, blockedColumns)) {
            basePath = baselinePath;
        }
        if (controlPoints.size() < 3 || basePath.isEmpty()) {
            return basePath;
        }

        List<BlockPos> smoothedSeedPoints = resampleCurve(controlPoints, basePath);
        if (smoothedSeedPoints.isEmpty()) {
            return basePath;
        }
        List<BlockPos> smoothedPath = rasterize(smoothedSeedPoints, sampler, blockedColumns, collectBridgeColumns(basePath, sampler, blockedColumns));
        return isValidCandidatePath(smoothedPath, basePath, sampler, blockedColumns)
                ? smoothedPath
                : basePath;
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

    private static List<BlockPos> simplifyColumns(List<BlockPos> routeNodes) {
        List<BlockPos> simplified = new ArrayList<>();
        for (BlockPos pos : routeNodes) {
            if (simplified.isEmpty() || !sameColumn(simplified.get(simplified.size() - 1), pos)) {
                simplified.add(pos.immutable());
            }
        }
        return List.copyOf(simplified);
    }

    private static List<BlockPos> resampleCurve(List<BlockPos> controlPoints, List<BlockPos> basePath) {
        List<CurvePoint> denseCurve = sampleDenseCurve(controlPoints);
        if (denseCurve.size() < 2) {
            return List.of();
        }

        BlockPos start = basePath.get(0);
        BlockPos end = basePath.get(basePath.size() - 1);
        List<BlockPos> resampled = new ArrayList<>();
        resampled.add(start.immutable());

        double nextDistance = CURVE_RESAMPLE_INTERVAL;
        double traversed = 0.0D;
        CurvePoint previous = denseCurve.get(0);
        for (int i = 1; i < denseCurve.size(); i++) {
            CurvePoint current = denseCurve.get(i);
            double segmentLength = distance(previous, current);
            if (segmentLength <= 1.0E-6D) {
                previous = current;
                continue;
            }
            while (traversed + segmentLength >= nextDistance) {
                double t = (nextDistance - traversed) / segmentLength;
                BlockPos sample = new BlockPos(
                        (int) Math.round(lerp(previous.x(), current.x(), t)),
                        start.getY(),
                        (int) Math.round(lerp(previous.z(), current.z(), t))
                );
                if (!sameColumn(resampled.get(resampled.size() - 1), sample)) {
                    resampled.add(sample);
                }
                nextDistance += CURVE_RESAMPLE_INTERVAL;
            }
            traversed += segmentLength;
            previous = current;
        }

        if (!sameColumn(resampled.get(resampled.size() - 1), end)) {
            resampled.add(end.immutable());
        }
        return List.copyOf(resampled);
    }

    private static List<CurvePoint> sampleDenseCurve(List<BlockPos> controlPoints) {
        double[] roundingDistances = computeCornerRoundingDistances(controlPoints);
        List<CurvePoint> denseCurve = new ArrayList<>();
        CurvePoint last = point(controlPoints.get(0));
        denseCurve.add(last);
        for (int i = 1; i < controlPoints.size() - 1; i++) {
            BlockPos previous = controlPoints.get(i - 1);
            BlockPos current = controlPoints.get(i);
            BlockPos next = controlPoints.get(i + 1);
            double roundingDistance = roundingDistances[i];
            if (roundingDistance <= 1.0E-6D) {
                continue;
            }

            CurvePoint entry = pointAlong(current, previous, roundingDistance);
            CurvePoint exit = pointAlong(current, next, roundingDistance);
            appendLinearSamples(denseCurve, last, entry);
            appendQuadraticSamples(denseCurve, entry, point(current), exit);
            last = exit;
        }
        appendLinearSamples(denseCurve, last, point(controlPoints.get(controlPoints.size() - 1)));
        return List.copyOf(denseCurve);
    }

    private static double[] computeCornerRoundingDistances(List<BlockPos> controlPoints) {
        double[] roundingDistances = new double[controlPoints.size()];
        for (int i = 1; i < controlPoints.size() - 1; i++) {
            double previousLength = distance(controlPoints.get(i - 1), controlPoints.get(i));
            double nextLength = distance(controlPoints.get(i), controlPoints.get(i + 1));
            roundingDistances[i] = Math.min(
                    MAX_CORNER_ROUNDING_DISTANCE,
                    Math.min(previousLength, nextLength) * CORNER_ROUNDING_FACTOR
            );
        }

        for (int i = 1; i < controlPoints.size() - 2; i++) {
            double sharedLength = distance(controlPoints.get(i), controlPoints.get(i + 1));
            double allowedCombined = Math.max(0.0D, sharedLength - MIN_SHARED_SEGMENT_REMAINING);
            double currentCombined = roundingDistances[i] + roundingDistances[i + 1];
            if (currentCombined <= allowedCombined || currentCombined <= 1.0E-6D) {
                continue;
            }
            double scale = allowedCombined / currentCombined;
            roundingDistances[i] *= scale;
            roundingDistances[i + 1] *= scale;
        }
        return roundingDistances;
    }

    private static List<BlockPos> rasterize(List<BlockPos> seedPoints,
                                            Function<BlockPos, SurfaceSample> sampler,
                                            Set<Long> blockedColumns,
                                            Set<Long> allowedBridgeColumns) {
        LinkedHashMap<Long, BlockPos> rasterized = new LinkedHashMap<>();
        BlockPos start = findNearestSurface(
                seedPoints.get(0).getX(),
                seedPoints.get(0).getZ(),
                seedPoints.get(0).getY(),
                sampler,
                blockedColumns,
                allowedBridgeColumns
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
                BlockPos resolved = findNearestSurface(rawStep.getX(), rawStep.getZ(), last.getY(), sampler, blockedColumns, allowedBridgeColumns);
                if (resolved == null || Math.abs(resolved.getY() - last.getY()) > MAX_SAFE_STEP_HEIGHT) {
                    return List.of();
                }
                List<BlockPos> contiguousSegment = connectContiguous(last, resolved, sampler, blockedColumns, allowedBridgeColumns);
                if (contiguousSegment.isEmpty()) {
                    return List.of();
                }
                for (int stepIndex = 1; stepIndex < contiguousSegment.size(); stepIndex++) {
                    BlockPos step = contiguousSegment.get(stepIndex);
                    rasterized.put(columnKey(step), step);
                }
                last = contiguousSegment.get(contiguousSegment.size() - 1);
            }
        }
        return List.copyOf(rasterized.values().stream().toList());
    }

    private static List<BlockPos> connectContiguous(BlockPos from,
                                                    BlockPos to,
                                                    Function<BlockPos, SurfaceSample> sampler,
                                                    Set<Long> blockedColumns,
                                                    Set<Long> allowedBridgeColumns) {
        if (sameColumn(from, to)) {
            return List.of(from);
        }
        if (isAdjacent(from, to)) {
            return List.of(from, to);
        }

        List<BlockPos> connected = new ArrayList<>();
        connected.add(from);
        BlockPos current = from;
        for (BlockPos rawStep : bresenham(from, to)) {
            if (sameColumn(rawStep, current)) {
                continue;
            }
            BlockPos resolved = findNearestSurface(rawStep.getX(), rawStep.getZ(), current.getY(), sampler, blockedColumns, allowedBridgeColumns);
            if (resolved == null
                    || !isAdjacent(current, resolved)
                    || Math.abs(resolved.getY() - current.getY()) > MAX_SAFE_STEP_HEIGHT) {
                return List.of();
            }
            if (!sameColumn(current, resolved)) {
                connected.add(resolved);
                current = resolved;
            }
        }
        return sameColumn(current, to) ? List.copyOf(connected) : List.of();
    }

    private static SurfaceSample safeExact(BlockPos pos,
                                           Function<BlockPos, SurfaceSample> sampler,
                                           Set<Long> blockedColumns,
                                           Set<Long> allowedBridgeColumns) {
        SurfaceSample sample = sampler.apply(new BlockPos(pos.getX(), 0, pos.getZ()));
        return isSafe(sample, blockedColumns, allowedBridgeColumns) ? sample : null;
    }

    private static BlockPos findNearestSurface(int x,
                                               int z,
                                               int preferredY,
                                               Function<BlockPos, SurfaceSample> sampler,
                                               Set<Long> blockedColumns,
                                               Set<Long> allowedBridgeColumns) {
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
                    if (!isSafe(sample, blockedColumns, allowedBridgeColumns)) {
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

    private static boolean isSafe(SurfaceSample sample, Set<Long> blockedColumns, Set<Long> allowedBridgeColumns) {
        return sample != null
                && sample.surfacePos() != null
                && !sample.blocked()
                && !blockedColumns.contains(columnKey(sample.surfacePos()))
                && (!sample.requiresBridge() || allowedBridgeColumns.contains(columnKey(sample.surfacePos())));
    }

    private static Set<Long> collectBridgeColumns(List<BlockPos> path,
                                                  Function<BlockPos, SurfaceSample> sampler,
                                                  Set<Long> blockedColumns) {
        Set<Long> bridgeColumns = new HashSet<>();
        for (BlockPos pos : path) {
            SurfaceSample sample = safeExact(pos, sampler, blockedColumns, Set.of(columnKey(pos)));
            if (sample == null) {
                return Set.of();
            }
            if (sample.requiresBridge()) {
                bridgeColumns.add(columnKey(sample.surfacePos()));
            }
        }
        return Set.copyOf(bridgeColumns);
    }

    static boolean isValidCandidatePath(List<BlockPos> candidate,
                                        List<BlockPos> baseline,
                                        Function<BlockPos, SurfaceSample> sampler,
                                        Set<Long> blockedColumns) {
        if (candidate.isEmpty() || baseline.isEmpty()) {
            return false;
        }
        if (!sameColumn(candidate.get(0), baseline.get(0))
                || !sameColumn(candidate.get(candidate.size() - 1), baseline.get(baseline.size() - 1))) {
            return false;
        }

        Set<Long> allowedBridgeColumns = collectBridgeColumns(baseline, sampler, blockedColumns);
        Set<Long> allowedAdjacentWaterColumns = collectAdjacentWaterColumns(baseline, sampler, blockedColumns);
        int bridgeColumns = 0;
        int contiguousBridgeColumns = 0;
        int longestBridgeRun = 0;
        int adjacentWaterColumns = 0;
        int lastY = Integer.MIN_VALUE;
        BlockPos previous = null;
        for (BlockPos pos : candidate) {
            SurfaceSample sample = safeExact(pos, sampler, blockedColumns, allowedBridgeColumns);
            if (sample == null || !sameColumn(sample.surfacePos(), pos)) {
                return false;
            }
            if (previous != null && !isAdjacent(previous, sample.surfacePos())) {
                return false;
            }
            if (lastY != Integer.MIN_VALUE && Math.abs(sample.surfacePos().getY() - lastY) > MAX_SAFE_STEP_HEIGHT) {
                return false;
            }
            if (sample.requiresBridge()) {
                bridgeColumns++;
                contiguousBridgeColumns++;
                longestBridgeRun = Math.max(longestBridgeRun, contiguousBridgeColumns);
            } else {
                contiguousBridgeColumns = 0;
            }
            if (sample.adjacentWaterColumns() > 0) {
                if (!allowedAdjacentWaterColumns.contains(columnKey(sample.surfacePos()))) {
                    return false;
                }
                adjacentWaterColumns++;
            }
            lastY = sample.surfacePos().getY();
            previous = sample.surfacePos();
        }
        return bridgeColumns <= allowedBridgeColumns.size()
                && bridgeColumns <= MAX_TOTAL_BRIDGE_COLUMNS
                && longestBridgeRun <= MAX_CONTIGUOUS_BRIDGE_COLUMNS
                && adjacentWaterColumns <= allowedAdjacentWaterColumns.size()
                && bridgeShareWithinLimit(candidate.size(), bridgeColumns);
    }

    private static Set<Long> collectAdjacentWaterColumns(List<BlockPos> path,
                                                         Function<BlockPos, SurfaceSample> sampler,
                                                         Set<Long> blockedColumns) {
        Set<Long> adjacentWaterColumns = new HashSet<>();
        for (BlockPos pos : path) {
            SurfaceSample sample = safeExact(pos, sampler, blockedColumns, Set.of(columnKey(pos)));
            if (sample == null) {
                return Set.of();
            }
            if (sample.adjacentWaterColumns() > 0) {
                adjacentWaterColumns.add(columnKey(sample.surfacePos()));
            }
        }
        return Set.copyOf(adjacentWaterColumns);
    }

    private static boolean bridgeShareWithinLimit(int pathLength, int totalBridgeColumns) {
        return totalBridgeColumns == 0
                || (pathLength > 0 && (totalBridgeColumns / (double) pathLength) <= MAX_BRIDGE_SHARE);
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

    private static boolean isAdjacent(BlockPos left, BlockPos right) {
        int dx = Math.abs(left.getX() - right.getX());
        int dz = Math.abs(left.getZ() - right.getZ());
        return Math.max(dx, dz) == 1;
    }

    private static boolean isCollinear(BlockPos previous, BlockPos current, BlockPos next) {
        int firstDx = Integer.compare(current.getX(), previous.getX());
        int firstDz = Integer.compare(current.getZ(), previous.getZ());
        int secondDx = Integer.compare(next.getX(), current.getX());
        int secondDz = Integer.compare(next.getZ(), current.getZ());
        return firstDx == secondDx && firstDz == secondDz;
    }

    private static double distance(BlockPos left, BlockPos right) {
        return distance(
                new CurvePoint(left.getX(), left.getZ()),
                new CurvePoint(right.getX(), right.getZ())
        );
    }

    private static double distance(CurvePoint left, CurvePoint right) {
        double dx = right.x() - left.x();
        double dz = right.z() - left.z();
        return Math.sqrt((dx * dx) + (dz * dz));
    }

    private static CurvePoint point(BlockPos pos) {
        return new CurvePoint(pos.getX(), pos.getZ());
    }

    private static CurvePoint pointAlong(BlockPos origin, BlockPos target, double distance) {
        double span = distance(origin, target);
        if (span <= 1.0E-6D) {
            return point(origin);
        }
        double t = distance / span;
        return new CurvePoint(
                lerp(origin.getX(), target.getX(), t),
                lerp(origin.getZ(), target.getZ(), t)
        );
    }

    private static void appendLinearSamples(List<CurvePoint> denseCurve, CurvePoint from, CurvePoint to) {
        int steps = Math.max(1, (int) Math.ceil(distance(from, to) * CURVE_DENSE_SAMPLES_PER_BLOCK));
        for (int step = 1; step <= steps; step++) {
            double t = step / (double) steps;
            CurvePoint sample = new CurvePoint(
                    lerp(from.x(), to.x(), t),
                    lerp(from.z(), to.z(), t)
            );
            if (!samePoint(denseCurve.get(denseCurve.size() - 1), sample)) {
                denseCurve.add(sample);
            }
        }
    }

    private static void appendQuadraticSamples(List<CurvePoint> denseCurve, CurvePoint start, CurvePoint control, CurvePoint end) {
        double approximateLength = distance(start, control) + distance(control, end);
        int steps = Math.max(2, (int) Math.ceil(approximateLength * CURVE_DENSE_SAMPLES_PER_BLOCK));
        for (int step = 1; step <= steps; step++) {
            double t = step / (double) steps;
            CurvePoint sample = new CurvePoint(
                    quadraticCoordinate(start.x(), control.x(), end.x(), t),
                    quadraticCoordinate(start.z(), control.z(), end.z(), t)
            );
            if (!samePoint(denseCurve.get(denseCurve.size() - 1), sample)) {
                denseCurve.add(sample);
            }
        }
    }

    private static double quadraticCoordinate(double start, double control, double end, double t) {
        double inverse = 1.0D - t;
        return (inverse * inverse * start) + (2.0D * inverse * t * control) + (t * t * end);
    }

    private static double lerp(double start, double end, double t) {
        return start + ((end - start) * t);
    }

    private static boolean samePoint(CurvePoint left, CurvePoint right) {
        return Math.abs(left.x() - right.x()) <= 1.0E-6D
                && Math.abs(left.z() - right.z()) <= 1.0E-6D;
    }

    private static long columnKey(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
    }

    public record SurfaceSample(BlockPos surfacePos,
                                boolean blocked,
                                boolean requiresBridge,
                                int adjacentWaterColumns) {
        public SurfaceSample {
            if (adjacentWaterColumns < 0) {
                throw new IllegalArgumentException("adjacentWaterColumns must be >= 0");
            }
        }
    }

    private record CurvePoint(double x, double z) {
    }
}
