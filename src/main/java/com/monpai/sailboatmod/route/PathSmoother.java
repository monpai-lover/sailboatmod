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

    /** 水路平滑:Y 统一取传入海平面(水面航行)。spacing 建议 2.0。用默认切线张力。 */
    public static List<BlockPos> smooth2D(List<BlockPos> polyline, int fixedY, double spacing) {
        return smooth2D(polyline, fixedY, spacing, TANGENT_TIGHTNESS);
    }

    /**
     * 水路平滑(可调切线张力)。{@code tightness} 越大曲线越贴原折线、过冲越小(WaterPathSmoother 的「过冲段局部重收紧」
     * 用更大 tightness 重算撞陆的路径)。其余同 {@link #smooth2D(List, int, double)}。
     */
    public static List<BlockPos> smooth2D(List<BlockPos> polyline, int fixedY, double spacing, double tightness) {
        // 不做 Douglas-Peucker 直线化:它会把寻路绕陆/绕岛的拐点(垂距小但贴着陆地)拉直 → 穿陆(实测回归)。
        // zigzag 锯齿交给样条平滑本身(Catmull-Rom)柔化,不靠删点拉直(删点会穿陆,得不偿失)。
        List<Vec2d> centers = smoothCenters(polyline, spacing, null, tightness);
        if (centers.isEmpty()) {
            return polyline == null ? List.of() : List.copyOf(polyline);
        }
        List<BlockPos> out = new ArrayList<>(centers.size());
        for (Vec2d c : centers) {
            out.add(new BlockPos((int) Math.round(c.x()), fixedY, (int) Math.round(c.z())));
        }
        return out;
    }

    /**
     * 水路平滑 + origin 标记输出(给 debug 工具)。与 {@link #smooth2D} 同样的平滑结果,额外并列输出每个
     * 最终点的 origin:落在 simplify 控制点(真实拐点,取整坐标命中)上 = {@link WaypointMeta#ORIGIN_RAW},
     * 否则(样条加密 / 弧长重采样新生)= {@link WaypointMeta#ORIGIN_INTERP}。
     *
     * @return points 与 origins 等长;origins[i] 是 points[i] 的来源 byte。空折线 → 都为空。
     */
    public static SmoothWithOrigin smooth2DWithOrigin(List<BlockPos> polyline, int fixedY, double spacing) {
        List<Byte> origins = new ArrayList<>();
        List<Vec2d> centers = smoothCenters(polyline, spacing, origins);
        if (centers.isEmpty()) {
            List<BlockPos> pts = polyline == null ? List.of() : List.copyOf(polyline);
            // 退化路径无 origin 判定信息,全标 RAW(等长占位)。
            List<Byte> fallback = new ArrayList<>(pts.size());
            for (int i = 0; i < pts.size(); i++) {
                fallback.add(WaypointMeta.ORIGIN_RAW);
            }
            return new SmoothWithOrigin(pts, fallback);
        }
        List<BlockPos> out = new ArrayList<>(centers.size());
        for (Vec2d c : centers) {
            out.add(new BlockPos((int) Math.round(c.x()), fixedY, (int) Math.round(c.z())));
        }
        return new SmoothWithOrigin(out, origins);
    }

    /** {@link #smooth2DWithOrigin} 的并列输出:points[i] 的来源在 origins[i]。 */
    public record SmoothWithOrigin(List<BlockPos> points, List<Byte> origins) {
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
        return smoothCenters(polyline, spacing, null, TANGENT_TIGHTNESS);
    }

    /** {@link #smoothCenters(List, double, List, double)} 的默认张力重载。 */
    private static List<Vec2d> smoothCenters(List<BlockPos> polyline, double spacing, List<Byte> originsOut) {
        return smoothCenters(polyline, spacing, originsOut, TANGENT_TIGHTNESS);
    }

    /**
     * 折线 → 平滑 + 弧长重采样后的 2D 中心点列表(无 Y)。航点数超上限时等距抽稀。
     *
     * @param originsOut 非 null 时,与返回列表等长并列输出每点 origin(命中 simplify 控制点取整坐标=RAW,否则 INTERP)。
     * @param tightness  切线张力(越大越贴折线、过冲越小)。
     */
    private static List<Vec2d> smoothCenters(List<BlockPos> polyline, double spacing, List<Byte> originsOut,
                                             double tightness) {
        if (originsOut != null) {
            originsOut.clear();
        }
        if (polyline == null || polyline.size() < 2) {
            return List.of();
        }
        List<Vec2d> controls = simplify(polyline);
        if (controls.size() < 2) {
            return List.of();
        }
        if (controls.size() == 2) {
            // 仅两点:直接按 spacing 在直线上插值出等距航点(避免样条对两点退化)。
            // 首尾即控制点(RAW),中间插值点 INTERP。
            List<Vec2d> straight = resampleStraight(controls.get(0), controls.get(1), Math.max(0.5D, spacing));
            if (originsOut != null) {
                for (int i = 0; i < straight.size(); i++) {
                    originsOut.add(i == 0 || i == straight.size() - 1 ? WaypointMeta.ORIGIN_RAW : WaypointMeta.ORIGIN_INTERP);
                }
            }
            return straight;
        }
        // 2026-06 样条换贝塞尔:旧 Catmull-Rom 在转弯处过冲(曲线冲出控制点连线外侧 → 把航点甩进陆地)。改用
        // 贝塞尔(Catmull-Rom→Bezier+de Casteljau,移植 RoadWeaver SplineHelper):曲线<b>严格落在控制点凸包内、
        // 不过冲</b>,转弯仍圆滑。残留极少数贴岸过冲由调用方(WaterAutoRouteService)平滑后逐段 3×3 陆地校验兜底,
        // 判陆的段退回安全直连。
        List<Vec2d> spline = generateSplinePoints(controls, tightness);
        List<Vec2d> centers;
        if (originsOut == null) {
            centers = extractCenters(spline, Math.max(0.5D, spacing));
            return thin(centers, MAX_WAYPOINTS);
        }
        // 收集 origin:先标控制点取整坐标集合,extractCenters 输出每点查命中。
        java.util.Set<Long> controlKeys = new java.util.HashSet<>();
        for (Vec2d c : controls) {
            controlKeys.add(packKey((int) Math.round(c.x()), (int) Math.round(c.z())));
        }
        List<Byte> rawOrigins = new ArrayList<>();
        centers = extractCenters(spline, Math.max(0.5D, spacing), controlKeys, rawOrigins);
        // thin 抽稀时同步抽 origin(保持 index 对齐)。
        return thinWithOrigin(centers, rawOrigins, MAX_WAYPOINTS, originsOut);
    }

    private static long packKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * Douglas-Peucker 容差直线化(2D,XZ):递归保留离首尾连线最远的点(若超容差),其余删。把 A* step 网格的
     * 「右一格上一格」阶梯锯齿压成直线(阶梯各点离首尾连线都 &lt; 容差 → 全删),真转弯(远离连线)保留。
     */
    private static List<BlockPos> douglasPeucker(List<BlockPos> path, double tolerance) {
        if (path == null || path.size() < 3) {
            return path == null ? List.of() : List.copyOf(path);
        }
        boolean[] keep = new boolean[path.size()];
        keep[0] = true;
        keep[path.size() - 1] = true;
        dpRecurse(path, 0, path.size() - 1, tolerance, keep);
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            if (keep[i]) {
                out.add(path.get(i));
            }
        }
        return out;
    }

    private static void dpRecurse(List<BlockPos> path, int lo, int hi, double tol, boolean[] keep) {
        if (hi - lo < 2) {
            return;
        }
        BlockPos a = path.get(lo), b = path.get(hi);
        double maxDist = -1.0D;
        int maxIdx = -1;
        for (int i = lo + 1; i < hi; i++) {
            double d = perpDist(path.get(i), a, b);
            if (d > maxDist) {
                maxDist = d;
                maxIdx = i;
            }
        }
        if (maxDist > tol && maxIdx > 0) {
            keep[maxIdx] = true;
            dpRecurse(path, lo, maxIdx, tol, keep);
            dpRecurse(path, maxIdx, hi, tol, keep);
        }
    }

    /** 点 p 到线段 a-b 的垂距(2D,XZ)。a==b 退化为点距。 */
    private static double perpDist(BlockPos p, BlockPos a, BlockPos b) {
        double abx = b.getX() - a.getX(), abz = b.getZ() - a.getZ();
        double lenSq = abx * abx + abz * abz;
        if (lenSq < 1.0E-9D) {
            double dx = p.getX() - a.getX(), dz = p.getZ() - a.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
        double cross = Math.abs((p.getX() - a.getX()) * abz - (p.getZ() - a.getZ()) * abx);
        return cross / Math.sqrt(lenSq);
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

    /**
     * 幻影端点扩展 + 逐段<b>贝塞尔</b>采样(Catmull-Rom→Bezier de Casteljau,移植 RoadWeaver,不过冲)。
     * steps≈段长×{@link #SPLINE_SAMPLES_PER_BLOCK}。曲线落在控制点凸包内,转弯圆滑但不外扩甩岸。
     */
    private static List<Vec2d> generateSplinePoints(List<Vec2d> controls, double tightness) {
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
                // 2026-06 收紧:用更小的切线张力(贝塞尔控制点靠 p1/p2 更近),曲线更贴原折线、转弯外凸更小,
                // 减少甩向陆地的过冲(剩余穿陆由 WaterRouteNbtVerifier 法线侧移兜底)。
                spline.add(tightBezier(p0, p1, p2, p3, t, tightness));
            }
        }
        spline.add(controls.get(n - 1)); // 补末端点
        return spline;
    }

    /** 切线张力分母:越大切线越短、曲线越贴控制点连线(过冲越小)。标准 Catmull-Rom 用 6.0;此处收紧到 12.0。 */
    private static final double TANGENT_TIGHTNESS = 12.0D;

    /**
     * Catmull-Rom→三次贝塞尔 + de Casteljau,切线用 {@link #TANGENT_TIGHTNESS} 收紧(比 WeaverSplineHelper 的
     * /6.0 更贴折线)。曲线仍落在控制点凸包内不过冲,且转弯外凸更小 → 更少甩向陆地。
     */
    private static Vec2d tightBezier(Vec2d p0, Vec2d p1, Vec2d p2, Vec2d p3, double t, double tightness) {
        double ct = Math.max(0.0D, Math.min(1.0D, t));
        double tt = tightness <= 0.0D ? TANGENT_TIGHTNESS : tightness;
        double b1x = p1.x() + (p2.x() - p0.x()) / tt;
        double b1z = p1.z() + (p2.z() - p0.z()) / tt;
        double b2x = p2.x() - (p3.x() - p1.x()) / tt;
        double b2z = p2.z() - (p3.z() - p1.z()) / tt;
        // de Casteljau on [p1, b1, b2, p2]
        double[] xs = {p1.x(), b1x, b2x, p2.x()};
        double[] zs = {p1.z(), b1z, b2z, p2.z()};
        for (int k = 3; k > 0; k--) {
            for (int i = 0; i < k; i++) {
                xs[i] = xs[i] + (xs[i + 1] - xs[i]) * ct;
                zs[i] = zs[i] + (zs[i + 1] - zs[i]) * ct;
            }
        }
        return new Vec2d(xs[0], zs[0]);
    }

    /** 沿 from→to 反方向延伸一个等长幻影端点(给首尾段补第 4 控制点)。 */
    private static Vec2d extrapolate(Vec2d from, Vec2d toward) {
        return new Vec2d(2.0D * from.x() - toward.x(), 2.0D * from.z() - toward.z());
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

    /**
     * 同 {@link #extractCenters(List, double)},额外并列输出每个 center 的 origin:取整坐标命中
     * {@code controlKeys}(simplify 控制点)→ RAW,否则 INTERP。
     */
    private static List<Vec2d> extractCenters(List<Vec2d> spline, double spacing, java.util.Set<Long> controlKeys, List<Byte> originsOut) {
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
                    originsOut.add(originForKey(controlKeys, cx, cz));
                    nextCenterDist = currentDist + spacing;
                } else {
                    Vec2d last = centers.get(centers.size() - 1);
                    if ((int) last.x() != cx || (int) last.z() != cz) {
                        centers.add(new Vec2d(cx, cz));
                        originsOut.add(originForKey(controlKeys, cx, cz));
                        nextCenterDist = currentDist + spacing;
                    }
                }
            }
        }
        return centers;
    }

    private static byte originForKey(java.util.Set<Long> controlKeys, int x, int z) {
        return controlKeys.contains(packKey(x, z)) ? WaypointMeta.ORIGIN_RAW : WaypointMeta.ORIGIN_INTERP;
    }

    /** 超上限时等距抽稀,同步抽 origin 保持 index 对齐(始终保留首尾)。 */
    private static List<Vec2d> thinWithOrigin(List<Vec2d> centers, List<Byte> origins, int maxCount, List<Byte> originsOut) {
        if (centers.size() <= maxCount) {
            originsOut.addAll(origins);
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
            originsOut.add(origins.get(idx));
        }
        return out;
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
