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

    /**
     * Catmull-Rom 转贝塞尔 + de Casteljau 求值(移植 RoadWeaver SplineHelper)。相比 {@link #catmullRomSpline}:
     * 贝塞尔曲线<b>严格落在控制点凸包内,不过冲</b>(Catmull-Rom 会冲出控制点连线外侧、把转弯处航点甩进陆地)。
     * 取 p1→p2 段的曲线,p0/p3 给切线方向(/6.0 张力)。
     */
    public static Vec2d catmullRomBezier(double x0, double z0,
                                         double x1, double z1,
                                         double x2, double z2,
                                         double x3, double z3,
                                         double t) {
        double clampedT = Math.max(0.0D, Math.min(1.0D, t));
        // Catmull-Rom → 三次贝塞尔控制点(b0=p1, b3=p2, 中间两点用相邻点差/6 定切线)。
        Vec2d[] ctrl = {
                new Vec2d(x1, z1),
                new Vec2d(x1 + (x2 - x0) / 6.0D, z1 + (z2 - z0) / 6.0D),
                new Vec2d(x2 - (x3 - x1) / 6.0D, z2 - (z3 - z1) / 6.0D),
                new Vec2d(x2, z2)
        };
        return bezierDeCasteljau(ctrl, clampedT);
    }

    /** de Casteljau 递推求贝塞尔曲线 t 处的点(任意阶)。 */
    private static Vec2d bezierDeCasteljau(Vec2d[] ctrl, double t) {
        int n = ctrl.length;
        double[] xs = new double[n];
        double[] zs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = ctrl[i].x();
            zs[i] = ctrl[i].z();
        }
        for (int k = n - 1; k > 0; k--) {
            for (int i = 0; i < k; i++) {
                xs[i] = xs[i] + (xs[i + 1] - xs[i]) * t;
                zs[i] = zs[i] + (zs[i + 1] - zs[i]) * t;
            }
        }
        return new Vec2d(xs[0], zs[0]);
    }
}
