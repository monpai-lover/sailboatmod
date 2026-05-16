package com.monpai.sailboatmod.roadplanner.postprocess;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadPathPostProcessor {
    private static final long SIMPLIFY_CROSS_THRESHOLD = 16L;

    private RoadPathPostProcessor() {
    }

    public static List<BlockPos> process(List<BlockPos> rawPath, boolean[] bridgeMask) {
        if (rawPath == null || rawPath.size() < 3) {
            return rawPath == null ? List.of() : List.copyOf(rawPath);
        }
        boolean[] safeMask = bridgeMask != null && bridgeMask.length == rawPath.size() ? bridgeMask : new boolean[rawPath.size()];
        List<BlockPos> simplified = simplify(rawPath, safeMask);
        boolean[] simplifiedMask = remapBridgeMask(rawPath, simplified, safeMask);
        List<BlockPos> straightened = straightenBridgeRuns(simplified, simplifiedMask);
        return relax(straightened, simplifiedMask);
    }

    public static List<BlockPos> processWithSpline(List<BlockPos> rawPath, boolean[] bridgeMask) {
        if (rawPath == null || rawPath.size() < 3) {
            return rawPath == null ? List.of() : List.copyOf(rawPath);
        }
        boolean[] safeMask = bridgeMask != null && bridgeMask.length == rawPath.size() ? bridgeMask : new boolean[rawPath.size()];
        List<BlockPos> simplified = simplify(rawPath, safeMask);
        boolean[] simplifiedMask = remapBridgeMask(rawPath, simplified, safeMask);
        List<BlockPos> straightened = straightenBridgeRuns(simplified, simplifiedMask);
        List<BlockPos> relaxed = relax(straightened, simplifiedMask);
        return splineAndExtract(relaxed, simplifiedMask);
    }

    static List<BlockPos> simplify(List<BlockPos> nodes, boolean[] bridgeMask) {
        if (nodes.size() < 3) return List.copyOf(nodes);
        List<BlockPos> result = new ArrayList<>();
        result.add(nodes.get(0));
        BlockPos prev = nodes.get(0);
        for (int i = 1; i < nodes.size() - 1; i++) {
            BlockPos curr = nodes.get(i);
            BlockPos next = nodes.get(i + 1);
            long dx1 = curr.getX() - prev.getX();
            long dz1 = curr.getZ() - prev.getZ();
            long dx2 = next.getX() - curr.getX();
            long dz2 = next.getZ() - curr.getZ();
            long cross = Math.abs(dx1 * dz2 - dz1 * dx2);
            if (cross > SIMPLIFY_CROSS_THRESHOLD || bridgeMask[i]) {
                result.add(curr);
                prev = curr;
            }
        }
        result.add(nodes.get(nodes.size() - 1));
        return result;
    }

    static boolean[] remapBridgeMask(List<BlockPos> original, List<BlockPos> simplified, boolean[] originalMask) {
        boolean[] result = new boolean[simplified.size()];
        for (int si = 0; si < simplified.size(); si++) {
            BlockPos sp = simplified.get(si);
            for (int oi = 0; oi < original.size(); oi++) {
                if (original.get(oi).equals(sp)) {
                    result[si] = originalMask[oi];
                    break;
                }
            }
        }
        return result;
    }

    static List<BlockPos> straightenBridgeRuns(List<BlockPos> nodes, boolean[] bridgeMask) {
        List<BlockPos> result = new ArrayList<>(nodes);
        int n = nodes.size();
        int i = 0;
        while (i < n) {
            if (!bridgeMask[i]) { i++; continue; }
            int runStart = i;
            while (i < n && bridgeMask[i]) i++;
            int runEnd = i - 1;
            int entryIdx = Math.max(0, runStart - 1);
            int exitIdx = Math.min(n - 1, runEnd + 1);
            if (exitIdx <= entryIdx) continue;
            BlockPos entry = nodes.get(entryIdx);
            BlockPos exit = nodes.get(exitIdx);
            for (int k = runStart; k <= runEnd; k++) {
                double t = (double)(k - entryIdx) / (exitIdx - entryIdx);
                int nx = (int) Math.round(entry.getX() + (exit.getX() - entry.getX()) * t);
                int nz = (int) Math.round(entry.getZ() + (exit.getZ() - entry.getZ()) * t);
                result.set(k, new BlockPos(nx, nodes.get(k).getY(), nz));
            }
        }
        return result;
    }

    static List<BlockPos> relax(List<BlockPos> nodes, boolean[] bridgeMask) {
        if (nodes.size() < 3) return List.copyOf(nodes);
        List<BlockPos> result = new ArrayList<>(nodes.size());
        result.add(nodes.get(0));
        for (int i = 1; i < nodes.size() - 1; i++) {
            if (bridgeMask[i] || bridgeMask[i - 1] || bridgeMask[i + 1]) {
                result.add(nodes.get(i));
            } else {
                BlockPos prev = nodes.get(i - 1);
                BlockPos curr = nodes.get(i);
                BlockPos next = nodes.get(i + 1);
                int nx = (prev.getX() + curr.getX() * 2 + next.getX()) / 4;
                int nz = (prev.getZ() + curr.getZ() * 2 + next.getZ()) / 4;
                result.add(new BlockPos(nx, curr.getY(), nz));
            }
        }
        result.add(nodes.get(nodes.size() - 1));
        return result;
    }

    static List<BlockPos> splineAndExtract(List<BlockPos> controlPoints, boolean[] bridgeMask) {
        if (controlPoints.size() < 2) return List.copyOf(controlPoints);
        if (controlPoints.size() == 2) return linearExtract(controlPoints);

        List<BlockPos> ext = new ArrayList<>(controlPoints.size() + 2);
        ext.add(controlPoints.get(0));
        ext.addAll(controlPoints);
        ext.add(controlPoints.get(controlPoints.size() - 1));

        boolean[] extMask = new boolean[ext.size()];
        extMask[0] = bridgeMask[0];
        System.arraycopy(bridgeMask, 0, extMask, 1, bridgeMask.length);
        extMask[ext.size() - 1] = bridgeMask[bridgeMask.length - 1];

        List<double[]> splinePoints = new ArrayList<>();
        for (int i = 0; i < controlPoints.size() - 1; i++) {
            BlockPos p1 = ext.get(i + 1);
            BlockPos p2 = ext.get(i + 2);
            double dist = Math.sqrt(sqDist2d(p1, p2));
            int steps = Math.max(1, (int) Math.ceil(dist * 4.0));
            boolean bridge = extMask[i + 1] || extMask[i + 2];
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                double sx, sz;
                if (bridge) {
                    sx = p1.getX() + (p2.getX() - p1.getX()) * t;
                    sz = p1.getZ() + (p2.getZ() - p1.getZ()) * t;
                } else {
                    BlockPos p0 = ext.get(i);
                    BlockPos p3 = ext.get(i + 3);
                    double crX = catmullRom(p0.getX(), p1.getX(), p2.getX(), p3.getX(), t);
                    double crZ = catmullRom(p0.getZ(), p1.getZ(), p2.getZ(), p3.getZ(), t);
                    double[] bzPt = bezierDeCasteljau(
                            p0.getX(), p0.getZ(),
                            p1.getX(), p1.getZ(),
                            p2.getX(), p2.getZ(),
                            p3.getX(), p3.getZ(), t);
                    sx = crX * 0.4 + bzPt[0] * 0.6;
                    sz = crZ * 0.4 + bzPt[1] * 0.6;
                }
                splinePoints.add(new double[]{sx, sz});
            }
        }
        BlockPos last = controlPoints.get(controlPoints.size() - 1);
        splinePoints.add(new double[]{last.getX(), last.getZ()});

        return extractCenters(splinePoints, controlPoints);
    }

    private static List<BlockPos> linearExtract(List<BlockPos> two) {
        BlockPos a = two.get(0);
        BlockPos b = two.get(1);
        double dist = Math.sqrt(sqDist2d(a, b));
        int count = Math.max(1, (int) Math.round(dist));
        List<BlockPos> result = new ArrayList<>(count + 1);
        for (int i = 0; i <= count; i++) {
            double t = (double) i / count;
            int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
            int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
            int y = (int) Math.round(a.getY() + (b.getY() - a.getY()) * t);
            if (result.isEmpty() || !result.get(result.size() - 1).equals(new BlockPos(x, y, z))) {
                result.add(new BlockPos(x, y, z));
            }
        }
        return result;
    }

    private static List<BlockPos> extractCenters(List<double[]> splinePoints, List<BlockPos> rawPath) {
        List<BlockPos> centers = new ArrayList<>();
        double accumulated = 0;
        double nextThreshold = 0;
        for (int i = 0; i < splinePoints.size(); i++) {
            if (i > 0) {
                double dx = splinePoints.get(i)[0] - splinePoints.get(i - 1)[0];
                double dz = splinePoints.get(i)[1] - splinePoints.get(i - 1)[1];
                accumulated += Math.sqrt(dx * dx + dz * dz);
            }
            if (accumulated >= nextThreshold || i == splinePoints.size() - 1) {
                int cx = (int) Math.round(splinePoints.get(i)[0]);
                int cz = (int) Math.round(splinePoints.get(i)[1]);
                int cy = interpolateY(cx, cz, rawPath);
                BlockPos pos = new BlockPos(cx, cy, cz);
                if (centers.isEmpty() || !centers.get(centers.size() - 1).equals(pos)) {
                    centers.add(pos);
                }
                nextThreshold = accumulated + 1.0;
            }
        }
        return centers;
    }

    private static int interpolateY(int cx, int cz, List<BlockPos> rawPath) {
        double bestDist = Double.MAX_VALUE;
        double bestT = 0;
        int bestSeg = 0;
        for (int i = 0; i < rawPath.size() - 1; i++) {
            BlockPos a = rawPath.get(i);
            BlockPos b = rawPath.get(i + 1);
            double segDx = b.getX() - a.getX();
            double segDz = b.getZ() - a.getZ();
            double segLen2 = segDx * segDx + segDz * segDz;
            double t;
            if (segLen2 < 1e-6) {
                t = 0;
            } else {
                t = ((cx - a.getX()) * segDx + (cz - a.getZ()) * segDz) / segLen2;
                t = Math.max(0, Math.min(1, t));
            }
            double projX = a.getX() + segDx * t;
            double projZ = a.getZ() + segDz * t;
            double dist = (cx - projX) * (cx - projX) + (cz - projZ) * (cz - projZ);
            if (dist < bestDist) {
                bestDist = dist;
                bestT = t;
                bestSeg = i;
            }
        }
        BlockPos a = rawPath.get(bestSeg);
        BlockPos b = rawPath.get(Math.min(bestSeg + 1, rawPath.size() - 1));
        return (int) Math.round(a.getY() + (b.getY() - a.getY()) * bestT);
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return (-0.5 * t3 + t2 - 0.5 * t) * p0
             + (1.5 * t3 - 2.5 * t2 + 1.0) * p1
             + (-1.5 * t3 + 2.0 * t2 + 0.5 * t) * p2
             + (0.5 * t3 - 0.5 * t2) * p3;
    }

    private static double sqDist2d(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        return dx * dx + dz * dz;
    }

    private static double[] bezierDeCasteljau(double x0, double z0, double x1, double z1,
                                              double x2, double z2, double x3, double z3, double t) {
        double bx0 = x1;
        double bz0 = z1;
        double bx1 = x1 + (x2 - x0) / 6.0;
        double bz1 = z1 + (z2 - z0) / 6.0;
        double bx2 = x2 - (x3 - x1) / 6.0;
        double bz2 = z2 - (z3 - z1) / 6.0;
        double bx3 = x2;
        double bz3 = z2;
        double[] cx = elevate(new double[]{bx0, bx1, bx2, bx3});
        double[] cz = elevate(new double[]{bz0, bz1, bz2, bz3});
        cx = elevate(cx);
        cz = elevate(cz);
        return new double[]{deCasteljau(cx, t), deCasteljau(cz, t)};
    }

    private static double[] elevate(double[] pts) {
        int n = pts.length;
        double[] result = new double[n + 1];
        result[0] = pts[0];
        result[n] = pts[n - 1];
        for (int i = 1; i < n; i++) {
            double w = (double) i / n;
            result[i] = w * pts[i - 1] + (1.0 - w) * pts[i];
        }
        return result;
    }

    private static double deCasteljau(double[] pts, double t) {
        int n = pts.length;
        double[] work = java.util.Arrays.copyOf(pts, n);
        for (int r = 1; r < n; r++) {
            for (int i = 0; i < n - r; i++) {
                work[i] = (1.0 - t) * work[i] + t * work[i + 1];
            }
        }
        return work[0];
    }
}
