package com.monpai.sailboatmod.road.pathfinding.post;

import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.post.SplineHelper.CurveMode;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class PathPostProcessor {
    private static final int SPLINE_SEGMENTS_PER_SPAN = 4;
    private static final double RELAXATION_WEIGHT = 0.3;
    private static final int RELAXATION_PASSES = 3;
    private static final double SHARP_TURN_THRESHOLD = 60.0;

    public record ProcessedPath(List<BlockPos> path, List<BridgeSpan> bridgeSpans) {}

    public ProcessedPath process(List<BlockPos> rawPath, TerrainSamplingCache cache, int bridgeMinWaterDepth) {
        List<BlockPos> simplified = simplify(rawPath);
        List<BridgeSpan> bridges = detectBridges(simplified, cache, bridgeMinWaterDepth);
        List<BlockPos> straightened = straightenBridges(simplified, bridges);
        List<BlockPos> relaxed = relax(straightened, bridges);

        HeightProfileSmoother smoother = new HeightProfileSmoother(1.0);
        int[] smoothedHeights = smoother.smooth(relaxed, bridges);
        List<BlockPos> heightAdjusted = applyHeights(relaxed, smoothedHeights);

        List<BlockPos> splined = autoSpline(heightAdjusted, SPLINE_SEGMENTS_PER_SPAN);
        List<BlockPos> rasterized = rasterize(splined);
        List<BridgeSpan> finalBridges = detectBridges(rasterized, cache, bridgeMinWaterDepth);
        return new ProcessedPath(rasterized, finalBridges);
    }

    private List<BlockPos> applyHeights(List<BlockPos> path, int[] heights) {
        List<BlockPos> result = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            int h = (i < heights.length) ? heights[i] : p.getY();
            result.add(new BlockPos(p.getX(), h, p.getZ()));
        }
        return result;
    }

    private List<BlockPos> autoSpline(List<BlockPos> path, int segmentsPerSpan) {
        if (path.size() < 4) return new ArrayList<>(path);
        boolean hasSharpTurn = false;
        for (int i = 0; i < path.size() - 3; i++) {
            double angle = SplineHelper.angleBetween(
                path.get(i), path.get(i + 1), path.get(i + 2), path.get(i + 3));
            if (angle > SHARP_TURN_THRESHOLD) {
                hasSharpTurn = true;
                break;
            }
        }
        CurveMode mode = hasSharpTurn ? CurveMode.BEZIER_CASTELJAU : CurveMode.CATMULL_ROM;
        return SplineHelper.interpolate(path, segmentsPerSpan, mode);
    }

    private List<BlockPos> simplify(List<BlockPos> path) {
        if (path.size() < 3) return new ArrayList<>(path);
        List<BlockPos> result = new ArrayList<>();
        result.add(path.get(0));
        for (int i = 1; i < path.size() - 1; i++) {
            BlockPos prev = path.get(i - 1);
            BlockPos curr = path.get(i);
            BlockPos next = path.get(i + 1);
            int dx1 = curr.getX() - prev.getX(), dz1 = curr.getZ() - prev.getZ();
            int dx2 = next.getX() - curr.getX(), dz2 = next.getZ() - curr.getZ();
            if (dx1 != dx2 || dz1 != dz2) {
                result.add(curr);
            }
        }
        result.add(path.get(path.size() - 1));
        return result;
    }

    private List<BridgeSpan> detectBridges(List<BlockPos> path, TerrainSamplingCache cache, int minDepth) {
        List<BridgeSpan> spans = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            boolean water = cache.isWater(p.getX(), p.getZ())
                    && cache.getWaterDepth(p.getX(), p.getZ()) >= minDepth;
            if (water && start == -1) {
                start = i;
            } else if (!water && start != -1) {
                int surfaceY = cache.getHeight(path.get(start).getX(), path.get(start).getZ());
                int floorY = cache.getOceanFloor(path.get(start).getX(), path.get(start).getZ());
                spans.add(new BridgeSpan(start, i - 1, surfaceY, floorY));
                start = -1;
            }
        }
        if (start != -1) {
            BlockPos p = path.get(start);
            spans.add(new BridgeSpan(start, path.size() - 1,
                cache.getHeight(p.getX(), p.getZ()),
                cache.getOceanFloor(p.getX(), p.getZ())));
        }
        return spans;
    }

    private List<BlockPos> straightenBridges(List<BlockPos> path, List<BridgeSpan> bridges) {
        List<BlockPos> result = new ArrayList<>(path);
        for (BridgeSpan span : bridges) {
            BlockPos entry = path.get(span.startIndex());
            BlockPos exit = path.get(span.endIndex());
            int len = span.length();
            if (len <= 1) continue;
            for (int i = 1; i < len; i++) {
                double t = (double) i / len;
                int x = (int) Math.round(entry.getX() + (exit.getX() - entry.getX()) * t);
                int z = (int) Math.round(entry.getZ() + (exit.getZ() - entry.getZ()) * t);
                int y = (int) Math.round(entry.getY() + (exit.getY() - entry.getY()) * t);
                result.set(span.startIndex() + i, new BlockPos(x, y, z));
            }
        }
        return result;
    }

    private boolean isInBridge(int index, List<BridgeSpan> bridges) {
        for (BridgeSpan span : bridges) {
            if (index >= span.startIndex() && index <= span.endIndex()) return true;
        }
        return false;
    }

    private List<BlockPos> relax(List<BlockPos> path, List<BridgeSpan> bridges) {
        List<BlockPos> result = new ArrayList<>(path);
        for (int pass = 0; pass < RELAXATION_PASSES; pass++) {
            List<BlockPos> next = new ArrayList<>(result);
            for (int i = 1; i < result.size() - 1; i++) {
                if (isInBridge(i, bridges)) continue;
                BlockPos prev = result.get(i - 1);
                BlockPos curr = result.get(i);
                BlockPos nxt = result.get(i + 1);
                int x = (int) Math.round(curr.getX() * (1 - RELAXATION_WEIGHT)
                    + (prev.getX() + nxt.getX()) / 2.0 * RELAXATION_WEIGHT);
                int z = (int) Math.round(curr.getZ() * (1 - RELAXATION_WEIGHT)
                    + (prev.getZ() + nxt.getZ()) / 2.0 * RELAXATION_WEIGHT);
                next.set(i, new BlockPos(x, curr.getY(), z));
            }
            result = next;
        }
        return result;
    }

    private List<BlockPos> rasterize(List<BlockPos> path) {
        if (path.size() < 2) return new ArrayList<>(path);
        List<BlockPos> result = new ArrayList<>();
        result.add(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            BlockPos from = path.get(i - 1);
            BlockPos to = path.get(i);
            List<BlockPos> line = bresenham(from, to);
            for (int j = 1; j < line.size(); j++) {
                result.add(line.get(j));
            }
        }
        return result;
    }

    private List<BlockPos> bresenham(BlockPos from, BlockPos to) {
        List<BlockPos> points = new ArrayList<>();
        int x0 = from.getX(), z0 = from.getZ();
        int x1 = to.getX(), z1 = to.getZ();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        double totalDist = Math.sqrt((double)(x1 - x0) * (x1 - x0) + (double)(z1 - z0) * (z1 - z0));
        while (true) {
            double dist = Math.sqrt((double)(x0 - from.getX()) * (x0 - from.getX())
                + (double)(z0 - from.getZ()) * (z0 - from.getZ()));
            double t = totalDist < 0.001 ? 0 : dist / totalDist;
            int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            points.add(new BlockPos(x0, y, z0));
            if (x0 == x1 && z0 == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x0 += sx; }
            if (e2 < dx) { err += dx; z0 += sz; }
        }
        return points;
    }
}