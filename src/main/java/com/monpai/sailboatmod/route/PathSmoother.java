package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.roadplanner.weaver.pathfinding.WeaverSplineHelper;
import com.monpai.sailboatmod.roadplanner.weaver.pathfinding.WeaverSplineHelper.Vec2d;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 路径平滑公共工具——把 A* 出来的折线(8格一跳、锯齿、共线点被删到只剩拐点)平滑成自然圆滑曲线,
 * 再按固定弧长重采样出等距、密集的航点。水路与陆路共用(移植 RoadWeaver PathPostProcessor 的样条+重采样)。
 *
 * <p>纯 2D + CPU,零世界访问,可在任意线程跑。流程:
 * <ol>
 *   <li>simplify(叉积阈值,去共线点,留真实拐点);</li>
 *   <li>幻影端点扩展 + 逐段 Catmull-Rom 采样(steps≈段长×4,曲线足够光滑);</li>
 *   <li>沿样条弧长每跨 spacing 取一点,去重 → 等距密集航点;</li>
 *   <li>超 {@link #MAX_WAYPOINTS} 等距抽稀,防航线航点数超上限。</li>
 * </ol>
 *
 * <p><b>顺序约束</b>:样条会把航点推离原折线(过冲),可能推到陆地;调用方必须在平滑之后再做真实区块校验
 * (水路 {@code RealWaterVerifier}),平滑不保证全程可航。
 */
public final class PathSmoother {
    private static final int MAX_WAYPOINTS = 256; // 对齐 SailboatEntity.MAX_AUTOPILOT_WAYPOINTS
    private static final double SIMPLIFY_CROSS_THRESHOLD = 16.0D; // 叉积阈值,小于此视作共线删点
    private static final double SPLINE_SAMPLES_PER_BLOCK = 4.0D;  // 每格段长采样点数(同 RoadWeaver)

    private PathSmoother() {
    }

    /** 水路平滑:Y 统一取传入海平面(水面航行)。spacing 建议 2.0。 */
    public static List<BlockPos> smooth2D(List<BlockPos> polyline, int fixedY, double spacing) {
        List<Vec2d> centers = smoothCenters(polyline, spacing);
        if (centers.isEmpty()) {
            return polyline == null ? List.of() : List.copyOf(polyline);
        }
        List<BlockPos> out = new ArrayList<>(centers.size());
        for (Vec2d c : centers) {
            out.add(new BlockPos((int) Math.round(c.x()), fixedY, (int) Math.round(c.z())));
        }
        return out;
    }

    /** 陆路平滑:Y 沿原折线线性插值(地形跟随)。spacing 建议 1.0。 */
    public static List<BlockPos> smooth2DInterpolatedY(List<BlockPos> polyline, double spacing) {
        List<Vec2d> centers = smoothCenters(polyline, spacing);
        if (centers.isEmpty()) {
            return polyline == null ? List.of() : List.copyOf(polyline);
        }
        List<BlockPos> out = new ArrayList<>(centers.size());
        for (Vec2d c : centers) {
            int x = (int) Math.round(c.x());
            int z = (int) Math.round(c.z());
            int y = interpolateY(x, z, polyline);
            out.add(new BlockPos(x, y, z));
        }
        return out;
    }

    /** 折线 → 平滑 + 弧长重采样后的 2D 中心点列表(无 Y)。航点数超上限时等距抽稀。 */
    private static List<Vec2d> smoothCenters(List<BlockPos> polyline, double spacing) {
        if (polyline == null || polyline.size() < 2) {
            return List.of();
        }
        List<Vec2d> controls = simplify(polyline);
        if (controls.size() < 2) {
            return List.of();
        }
        if (controls.size() == 2) {
            // 仅两点:直接按 spacing 在直线上插值出等距航点(避免样条对两点退化)。
            return resampleStraight(controls.get(0), controls.get(1), Math.max(0.5D, spacing));
        }
        List<Vec2d> spline = generateSplinePoints(controls);
        List<Vec2d> centers = extractCenters(spline, Math.max(0.5D, spacing));
        return thin(centers, MAX_WAYPOINTS);
    }

    /** 叉积阈值简化:删共线点,保留拐点。比"方向 sign 变化"更稳(能容忍微小抖动)。 */
    private static List<Vec2d> simplify(List<BlockPos> input) {
        List<Vec2d> pts = new ArrayList<>(input.size());
        for (BlockPos p : input) {
            Vec2d v = new Vec2d(p.getX(), p.getZ());
            if (pts.isEmpty() || pts.get(pts.size() - 1).distSqr(v) > 1.0E-6D) {
                pts.add(v);
            }
        }
        if (pts.size() <= 2) {
            return pts;
        }
        List<Vec2d> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            Vec2d prev = out.get(out.size() - 1);
            Vec2d cur = pts.get(i);
            Vec2d next = pts.get(i + 1);
            double dx1 = cur.x() - prev.x();
            double dz1 = cur.z() - prev.z();
            double dx2 = next.x() - cur.x();
            double dz2 = next.z() - cur.z();
            double cross = Math.abs(dx1 * dz2 - dz1 * dx2);
            if (cross > SIMPLIFY_CROSS_THRESHOLD) {
                out.add(cur);
            }
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    /** 幻影端点扩展 + 逐段 Catmull-Rom 采样(p0..p3,steps≈段长×4)。 */
    private static List<Vec2d> generateSplinePoints(List<Vec2d> controls) {
        int n = controls.size();
        // 幻影端点:首尾各镜像延伸一个点,保证首尾段也有 4 个控制点。
        List<Vec2d> ext = new ArrayList<>(n + 2);
        ext.add(extrapolate(controls.get(0), controls.get(1)));
        ext.addAll(controls);
        ext.add(extrapolate(controls.get(n - 1), controls.get(n - 2)));

        List<Vec2d> spline = new ArrayList<>();
        for (int i = 1; i < ext.size() - 2; i++) {
            Vec2d p0 = ext.get(i - 1);
            Vec2d p1 = ext.get(i);
            Vec2d p2 = ext.get(i + 1);
            Vec2d p3 = ext.get(i + 2);
            double dist = Math.sqrt(p1.distSqr(p2));
            int steps = Math.max(1, (int) Math.ceil(dist * SPLINE_SAMPLES_PER_BLOCK));
            for (int s = 0; s < steps; s++) {
                double t = s / (double) steps;
                spline.add(WeaverSplineHelper.catmullRomSpline(
                        p0.x(), p0.z(), p1.x(), p1.z(), p2.x(), p2.z(), p3.x(), p3.z(), t));
            }
        }
        spline.add(controls.get(n - 1)); // 补末端点
        return spline;
    }

    /** 沿样条累计弧长,每跨 spacing 取一点,去重(x/z 取整相同跳过)。 */
    private static List<Vec2d> extractCenters(List<Vec2d> spline, double spacing) {
        List<Vec2d> centers = new ArrayList<>();
        double currentDist = 0.0D;
        double nextCenterDist = 0.0D;
        for (int i = 0; i < spline.size(); i++) {
            Vec2d p = spline.get(i);
            if (i > 0) {
                currentDist += Math.sqrt(p.distSqr(spline.get(i - 1)));
            }
            if (currentDist >= nextCenterDist || i == spline.size() - 1) {
                int cx = (int) Math.round(p.x());
                int cz = (int) Math.round(p.z());
                if (centers.isEmpty()) {
                    centers.add(new Vec2d(cx, cz));
                    nextCenterDist = currentDist + spacing;
                } else {
                    Vec2d last = centers.get(centers.size() - 1);
                    if ((int) last.x() != cx || (int) last.z() != cz) {
                        centers.add(new Vec2d(cx, cz));
                        nextCenterDist = currentDist + spacing;
                    }
                }
            }
        }
        return centers;
    }

    /** 两点直线等距重采样。 */
    private static List<Vec2d> resampleStraight(Vec2d a, Vec2d b, double spacing) {
        double dist = Math.sqrt(a.distSqr(b));
        int steps = Math.max(1, (int) Math.ceil(dist / spacing));
        List<Vec2d> out = new ArrayList<>(steps + 1);
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            out.add(new Vec2d(
                    Math.round(a.x() + (b.x() - a.x()) * t),
                    Math.round(a.z() + (b.z() - a.z()) * t)));
        }
        return out;
    }

    /** 超上限时等距抽稀(始终保留首尾)。 */
    private static List<Vec2d> thin(List<Vec2d> centers, int maxCount) {
        if (centers.size() <= maxCount) {
            return centers;
        }
        List<Vec2d> out = new ArrayList<>(maxCount);
        double stride = (centers.size() - 1) / (double) (maxCount - 1);
        for (int i = 0; i < maxCount; i++) {
            int idx = (int) Math.round(i * stride);
            if (idx >= centers.size()) {
                idx = centers.size() - 1;
            }
            out.add(centers.get(idx));
        }
        return out;
    }

    /** 沿 from→to 反方向延伸一个等长幻影端点。 */
    private static Vec2d extrapolate(Vec2d from, Vec2d toward) {
        return new Vec2d(2.0D * from.x() - toward.x(), 2.0D * from.z() - toward.z());
    }

    /** 找 (x,z) 在原折线上最近段的投影,线性插值出 Y。 */
    private static int interpolateY(int x, int z, List<BlockPos> polyline) {
        double bestDistSq = Double.MAX_VALUE;
        double bestY = polyline.get(0).getY();
        for (int i = 0; i < polyline.size() - 1; i++) {
            BlockPos a = polyline.get(i);
            BlockPos b = polyline.get(i + 1);
            double abx = b.getX() - a.getX();
            double abz = b.getZ() - a.getZ();
            double lenSq = abx * abx + abz * abz;
            double t = lenSq < 1.0E-9D ? 0.0D
                    : ((x - a.getX()) * abx + (z - a.getZ()) * abz) / lenSq;
            t = Math.max(0.0D, Math.min(1.0D, t));
            double px = a.getX() + abx * t;
            double pz = a.getZ() + abz * t;
            double dSq = (x - px) * (x - px) + (z - pz) * (z - pz);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestY = a.getY() + (b.getY() - a.getY()) * t;
            }
        }
        return (int) Math.round(bestY);
    }
}
