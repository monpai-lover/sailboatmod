package com.monpai.sailboatmod.road.pathfinding.post;

import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.RoadSegmentPlacement;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.post.SplineHelper.CurveMode;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PathPostProcessor {
    private static final int SPLINE_SEGMENTS_PER_SPAN = 4;
    private static final double RELAXATION_WEIGHT = 0.3;
    private static final int RELAXATION_PASSES = 3;
    private static final double SHARP_TURN_THRESHOLD = 60.0;

    public record ProcessedPath(List<BlockPos> path, List<BridgeSpan> bridgeSpans,
                                List<RoadSegmentPlacement> placements) {
        public ProcessedPath(List<BlockPos> path, List<BridgeSpan> bridgeSpans) {
            this(path, bridgeSpans, List.of());
        }
    }

    public ProcessedPath process(List<BlockPos> rawPath, TerrainSamplingCache cache,
                                  int bridgeMinWaterDepth, int halfWidth) {
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
        List<RoadSegmentPlacement> placements = rasterizeSegments(rasterized, halfWidth, finalBridges);
        anchorEndpoints(placements, cache);
        return new ProcessedPath(rasterized, finalBridges, placements);
    }

    public ProcessedPath process(List<BlockPos> rawPath, TerrainSamplingCache cache, int bridgeMinWaterDepth) {
        return process(rawPath, cache, bridgeMinWaterDepth, 1);
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
                int surfaceY = cache.getWaterSurfaceY(path.get(start).getX(), path.get(start).getZ());
                int floorY = cache.getOceanFloor(path.get(start).getX(), path.get(start).getZ());
                spans.add(new BridgeSpan(start, i - 1, surfaceY, floorY));
                start = -1;
            }
        }
        if (start != -1) {
            BlockPos p = path.get(start);
            spans.add(new BridgeSpan(start, path.size() - 1,
                cache.getWaterSurfaceY(p.getX(), p.getZ()),
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

    private List<RoadSegmentPlacement> rasterizeSegments(List<BlockPos> path, int halfWidth,
                                                          List<BridgeSpan> bridges) {
        if (path.size() < 2) {
            if (path.isEmpty()) return List.of();
            return List.of(new RoadSegmentPlacement(path.get(0), 0, List.copyOf(path), isInBridge(0, bridges)));
        }
        double halfWidthSq = (halfWidth + 0.5) * (halfWidth + 0.5);
        Map<Long, int[]> bestHit = new HashMap<>(); // key -> [segIndex, y]

        for (int seg = 0; seg < path.size() - 1; seg++) {
            BlockPos a = path.get(seg);
            BlockPos b = path.get(seg + 1);
            int minX = Math.min(a.getX(), b.getX()) - halfWidth - 1;
            int maxX = Math.max(a.getX(), b.getX()) + halfWidth + 1;
            int minZ = Math.min(a.getZ(), b.getZ()) - halfWidth - 1;
            int maxZ = Math.max(a.getZ(), b.getZ()) + halfWidth + 1;

            double segDx = b.getX() - a.getX();
            double segDz = b.getZ() - a.getZ();
            double segLenSq = segDx * segDx + segDz * segDz;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double t;
                    if (segLenSq < 0.001) {
                        t = 0;
                    } else {
                        t = ((x - a.getX()) * segDx + (z - a.getZ()) * segDz) / segLenSq;
                        t = Math.max(0, Math.min(1, t));
                    }
                    double projX = a.getX() + t * segDx;
                    double projZ = a.getZ() + t * segDz;
                    double distSq = (x - projX) * (x - projX) + (z - projZ) * (z - projZ);
                    if (distSq > halfWidthSq) continue;

                    int y = (int) Math.round(a.getY() + t * (b.getY() - a.getY()));
                    long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
                    int[] prev = bestHit.get(key);
                    if (prev == null) {
                        bestHit.put(key, new int[]{seg, y, (int) (distSq * 1000)});
                    } else if ((int) (distSq * 1000) < prev[2]) {
                        prev[0] = seg;
                        prev[1] = y;
                        prev[2] = (int) (distSq * 1000);
                    }
                }
            }
        }

        Map<Integer, List<BlockPos>> segPositions = new HashMap<>();
        for (Map.Entry<Long, int[]> entry : bestHit.entrySet()) {
            long key = entry.getKey();
            int[] val = entry.getValue();
            int x = (int) (key >> 32);
            int z = (int) key;
            segPositions.computeIfAbsent(val[0], k -> new ArrayList<>()).add(new BlockPos(x, val[1], z));
        }

        List<RoadSegmentPlacement> placements = new ArrayList<>();
        for (int seg = 0; seg < path.size() - 1; seg++) {
            List<BlockPos> positions = segPositions.getOrDefault(seg, List.of());
            if (positions.isEmpty()) continue;
            placements.add(new RoadSegmentPlacement(
                    path.get(seg), seg, positions, isInBridge(seg, bridges)));
        }
        return placements;
    }

    private void anchorEndpoints(List<RoadSegmentPlacement> placements, TerrainSamplingCache cache) {
        if (placements.isEmpty() || cache == null) return;
        int anchorCount = Math.min(3, placements.size());
        for (int p = 0; p < anchorCount; p++) {
            anchorPlacement(placements, p, cache);
        }
        for (int p = placements.size() - anchorCount; p < placements.size(); p++) {
            if (p >= anchorCount) anchorPlacement(placements, p, cache);
        }
    }

    private void anchorPlacement(List<RoadSegmentPlacement> placements, int index,
                                  TerrainSamplingCache cache) {
        RoadSegmentPlacement pl = placements.get(index);
        if (pl.bridge()) return;
        List<BlockPos> fixed = new ArrayList<>(pl.positions().size());
        for (BlockPos pos : pl.positions()) {
            int groundY = cache.getHeight(pos.getX(), pos.getZ());
            if (Math.abs(pos.getY() - groundY) > 2) {
                fixed.add(new BlockPos(pos.getX(), groundY, pos.getZ()));
            } else {
                fixed.add(pos);
            }
        }
        placements.set(index, new RoadSegmentPlacement(pl.center(), pl.segmentIndex(), fixed, pl.bridge()));
    }
}
