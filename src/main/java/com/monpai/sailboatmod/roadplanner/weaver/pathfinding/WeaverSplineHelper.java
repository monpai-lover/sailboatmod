package com.monpai.sailboatmod.roadplanner.weaver.pathfinding;

public final class WeaverSplineHelper {
    private WeaverSplineHelper() {
    }

    public record Vec2d(double x, double z) {
        public double distSqr(Vec2d other) {
            double dx = x - other.x;
            double dz = z - other.z;
            return dx * dx + dz * dz;
        }
    }

    public static Vec2d catmullRomSpline(double x0, double z0,
                                         double x1, double z1,
                                         double x2, double z2,
                                         double x3, double z3,
                                         double t) {
        double clampedT = Math.max(0.0D, Math.min(1.0D, t));
        double t2 = clampedT * clampedT;
        double t3 = t2 * clampedT;
        double f0 = -0.5D * t3 + t2 - 0.5D * clampedT;
        double f1 = 1.5D * t3 - 2.5D * t2 + 1.0D;
        double f2 = -1.5D * t3 + 2.0D * t2 + 0.5D * clampedT;
        double f3 = 0.5D * t3 - 0.5D * t2;
        return new Vec2d(
                x0 * f0 + x1 * f1 + x2 * f2 + x3 * f3,
                z0 * f0 + z1 * f1 + z2 * f2 + z3 * f3);
    }
}
