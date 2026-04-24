package com.monpai.sailboatmod.road.pathfinding.post;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

public final class SplineHelper {
    private SplineHelper() {}

    public enum CurveMode { CATMULL_ROM, BEZIER_CASTELJAU }

    public static List<BlockPos> interpolate(List<BlockPos> controlPoints, int segmentsPerSpan, CurveMode mode) {
        if (mode == CurveMode.BEZIER_CASTELJAU) {
            return bezierSpline(controlPoints, segmentsPerSpan);
        }
        return catmullRom(controlPoints, segmentsPerSpan);
    }

    public static List<BlockPos> catmullRom(List<BlockPos> controlPoints, int segmentsPerSpan) {
        if (controlPoints.size() < 4) return new ArrayList<>(controlPoints);
        List<BlockPos> result = new ArrayList<>();
        result.add(controlPoints.get(0));

        for (int i = 0; i < controlPoints.size() - 3; i++) {
            BlockPos p0 = controlPoints.get(i);
            BlockPos p1 = controlPoints.get(i + 1);
            BlockPos p2 = controlPoints.get(i + 2);
            BlockPos p3 = controlPoints.get(i + 3);

            for (int j = 1; j <= segmentsPerSpan; j++) {
                double t = (double) j / segmentsPerSpan;
                double t2 = t * t;
                double t3 = t2 * t;

                double x = 0.5 * ((2 * p1.getX()) + (-p0.getX() + p2.getX()) * t
                    + (2 * p0.getX() - 5 * p1.getX() + 4 * p2.getX() - p3.getX()) * t2
                    + (-p0.getX() + 3 * p1.getX() - 3 * p2.getX() + p3.getX()) * t3);
                double z = 0.5 * ((2 * p1.getZ()) + (-p0.getZ() + p2.getZ()) * t
                    + (2 * p0.getZ() - 5 * p1.getZ() + 4 * p2.getZ() - p3.getZ()) * t2
                    + (-p0.getZ() + 3 * p1.getZ() - 3 * p2.getZ() + p3.getZ()) * t3);
                double y = 0.5 * ((2 * p1.getY()) + (-p0.getY() + p2.getY()) * t
                    + (2 * p0.getY() - 5 * p1.getY() + 4 * p2.getY() - p3.getY()) * t2
                    + (-p0.getY() + 3 * p1.getY() - 3 * p2.getY() + p3.getY()) * t3);

                result.add(new BlockPos((int) Math.round(x), (int) Math.round(y), (int) Math.round(z)));
            }
        }
        return result;
    }

    public static List<BlockPos> bezierSpline(List<BlockPos> controlPoints, int segmentsPerSpan) {
        if (controlPoints.size() < 4) return new ArrayList<>(controlPoints);
        List<BlockPos> result = new ArrayList<>();
        result.add(controlPoints.get(0));

        for (int i = 0; i < controlPoints.size() - 3; i += 3) {
            int end = Math.min(i + 3, controlPoints.size() - 1);
            double[][] ctrl = new double[end - i + 1][3];
            for (int k = 0; k <= end - i; k++) {
                BlockPos p = controlPoints.get(i + k);
                ctrl[k] = new double[]{p.getX(), p.getY(), p.getZ()};
            }

            for (int j = 1; j <= segmentsPerSpan; j++) {
                double t = (double) j / segmentsPerSpan;
                double[] pt = bezierDeCasteljau(ctrl, t);
                result.add(new BlockPos((int) Math.round(pt[0]), (int) Math.round(pt[1]), (int) Math.round(pt[2])));
            }
        }
        return result;
    }

    public static double[] bezierDeCasteljau(double[][] ctrl, double t) {
        int n = ctrl.length;
        double[][] work = new double[n][3];
        for (int i = 0; i < n; i++) {
            System.arraycopy(ctrl[i], 0, work[i], 0, 3);
        }
        for (int k = n - 1; k > 0; k--) {
            for (int i = 0; i < k; i++) {
                for (int d = 0; d < 3; d++) {
                    work[i][d] = work[i][d] + (work[i + 1][d] - work[i][d]) * t;
                }
            }
        }
        return work[0];
    }

    public static double[][] elevateBezierDegree(double[][] ctrl) {
        int n = ctrl.length;
        int newN = n + 1;
        double[][] elevated = new double[newN][3];
        elevated[0] = ctrl[0].clone();
        elevated[newN - 1] = ctrl[n - 1].clone();
        for (int i = 1; i < newN - 1; i++) {
            double ratio = (double) i / newN;
            for (int d = 0; d < 3; d++) {
                elevated[i][d] = ratio * ctrl[i - 1][d] + (1 - ratio) * ctrl[i][d];
            }
        }
        return elevated;
    }

    public static double angleBetween(BlockPos a, BlockPos b, BlockPos c, BlockPos d) {
        double dx1 = b.getX() - a.getX(), dz1 = b.getZ() - a.getZ();
        double dx2 = d.getX() - c.getX(), dz2 = d.getZ() - c.getZ();
        double mag1 = Math.sqrt(dx1 * dx1 + dz1 * dz1);
        double mag2 = Math.sqrt(dx2 * dx2 + dz2 * dz2);
        if (mag1 < 1e-6 || mag2 < 1e-6) return 0;
        double dot = (dx1 * dx2 + dz1 * dz2) / (mag1 * mag2);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }
}