package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.PathSmoother;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>撞陆感知水路平滑器</b>(2026-06):实测「所有撞陆都是贝塞尔平滑过冲造成」——A* 折线本身走 2×2 判过的格不撞陆,
 * 是 {@link PathSmoother} 抹平拐角时凸包外扩把曲线甩进陆地;且船 autopilot 不可能精确循线,曲线必须离陆留 margin。
 *
 * <p>本类把「{@link PathSmoother} 纯几何平滑」与「{@link RealBlockWaterMap} 真实方块查陆」缝合,做<b>三层防线</b>
 * (作用于平滑前/中/后),保证输出曲线不切陆且离岸有余量。PathSmoother 保持纯几何零世界访问(陆路共用,不污染):
 * <ol>
 *   <li><b>前·折线离岸预留</b>:平滑前把 A* 折线的贴岸控制点沿法线往水心推 margin,让控制点本身离岸 → 贝塞尔凸出
 *       也还在水里(源头预留)。</li>
 *   <li><b>中·过冲段局部重收紧</b>:用默认切线张力平滑;若结果有段撞陆,<b>整条用更大张力重平滑</b>(曲线更贴折线、
 *       过冲更小),逐级加紧最多 {@link #MAX_TIGHTEN_ROUNDS} 轮,取第一条不撞陆的;全失败取最紧那条交后处理兜底。</li>
 *   <li><b>后·逐点抽陆回推</b>:平滑输出每点查 {@code hullClear} 2×2 + 离岸 margin,撞陆/贴岸点沿航向法线推回水心。</li>
 * </ol>
 *
 * <p>结果仍会过 {@link WaterRouteNbtVerifier#verify}(逐点+逐段 supercover 抓陆绕行)做最终兜底,但治本在这里——
 * verify 是「事后绕」,本类是「平滑时就不甩进陆」。
 */
public final class WaterPathSmoother {

    private WaterPathSmoother() {
    }

    /** 基础离岸 margin(环数):直线段曲线点离岸 < 此值视作「贴岸危险」要回推。船 narrow-long 留 1 环冗余。 */
    private static final int OFFSHORE_MARGIN = 1;
    /** 最大离岸 margin(环数):急转弯处放大到此(给船反应式循迹滞后纠偏留更大冲出缓冲带,见 [[water_route_real_root_autopilot]])。 */
    private static final int MAX_OFFSHORE_MARGIN = 3;
    /** 转角(度)→margin 放大的满量程:该点航向转角 ≥ 此值时 margin 到 MAX(线性插值,直线=base)。 */
    private static final double MARGIN_FULL_TURN_DEGREES = 60.0D;
    // 中层重收紧的张力阶梯(由松到紧):张力越小越圆滑、过冲越大。第一轮用 8(更圆滑,用户反馈 12 太收敛不够平滑),
    // 撞陆则自动升 12/24/48 逐级加紧——只有真撞陆的航段才被收紧,默认航段保持圆滑。放松起点不会换回撞陆(中层抓得住)。
    private static final double[] TIGHTEN_LADDER = {8.0D, 12.0D, 24.0D, 48.0D};
    private static final int MAX_TIGHTEN_ROUNDS = TIGHTEN_LADDER.length;
    /** 法线回推最大偏移(格)。 */
    private static final int PUSHBACK_MAX = 32;

    /**
     * 撞陆感知平滑。
     *
     * @param map       已 enableOnDemand 的真实方块水图
     * @param polyline  A* 折线(生成期/运行时均可)
     * @param fixedY    水面 Y
     * @param spacing   重采样间距(建议 2.0)
     * @param halfWidth 船宽(语义已统一 2×2)
     * @return 撞陆/贴岸已尽量消除的平滑航点(仍建议交 verify 最终兜底)。
     */
    public static List<BlockPos> smooth(RealBlockWaterMap map, List<BlockPos> polyline, int fixedY, double spacing,
                                        int halfWidth) {
        if (map == null) {
            return PathSmoother.smooth2D(polyline, fixedY, spacing); // 无 map 退回纯几何
        }
        if (polyline == null || polyline.size() < 2) {
            return polyline == null ? List.of() : List.copyOf(polyline);
        }

        // ---- 前:折线贴岸控制点往水心推 margin ----
        List<BlockPos> prepared = pushShoreHuggingControls(map, polyline, halfWidth);

        // ---- 中:默认张力平滑,撞陆则逐级加紧重平滑,取第一条不撞陆的 ----
        List<BlockPos> best = null;
        for (int round = 0; round < MAX_TIGHTEN_ROUNDS; round++) {
            List<BlockPos> sm = PathSmoother.smooth2D(prepared, fixedY, spacing, TIGHTEN_LADDER[round]);
            best = sm; // 保底:全失败用最紧那条
            if (sm.size() < 2 || !hasLandCrossing(map, sm, halfWidth)) {
                break; // 不撞陆,采用
            }
        }
        if (best == null || best.size() < 2) {
            return best == null ? List.of() : best;
        }

        // ---- 后:逐点抽陆回推(撞陆/贴岸点沿法线推回水心)----
        return pullBackOffshore(map, best, fixedY, halfWidth);
    }

    /** 前层:折线里 2×2 不过或贴岸的控制点,沿相邻顶点航向的法线往水心推到离岸≥margin。 */
    private static List<BlockPos> pushShoreHuggingControls(RealBlockWaterMap map, List<BlockPos> polyline, int halfWidth) {
        List<BlockPos> out = new ArrayList<>(polyline.size());
        for (int i = 0; i < polyline.size(); i++) {
            BlockPos p = polyline.get(i);
            boolean endpoint = (i == 0 || i == polyline.size() - 1);
            BlockPos before = out.isEmpty() ? p : out.get(out.size() - 1);
            BlockPos after = i + 1 < polyline.size() ? polyline.get(i + 1) : p;
            int margin = dynamicMargin(before, p, after); // 急转处 margin 大,让出更宽冲出缓冲带
            boolean water = map.hullClear(p.getX(), p.getZ(), halfWidth);
            int off = water ? offshore(map, p.getX(), p.getZ(), halfWidth) : -1;
            boolean shoreHug = water && off < margin;
            if (endpoint || (water && !shoreHug)) {
                out.add(p);
                continue;
            }
            int minOff = shoreHug ? Math.max(off + 1, margin) : 0;
            BlockPos pushed = normalPush(map, p, before, after, halfWidth, minOff);
            out.add(pushed != null ? pushed : p); // 推不动保留原点(交后续层兜底)
        }
        return out;
    }

    /** 后层:平滑曲线每点撞陆/贴岸则法线回推;推不动保留(交 verify)。 */
    private static List<BlockPos> pullBackOffshore(RealBlockWaterMap map, List<BlockPos> curve, int fixedY,
                                                   int halfWidth) {
        List<BlockPos> out = new ArrayList<>(curve.size());
        for (int i = 0; i < curve.size(); i++) {
            BlockPos p = curve.get(i);
            boolean endpoint = (i == 0 || i == curve.size() - 1);
            BlockPos before = !out.isEmpty() ? out.get(out.size() - 1) : p;
            BlockPos after = i + 1 < curve.size() ? curve.get(i + 1) : p;
            int margin = dynamicMargin(before, p, after);
            boolean water = map.hullClear(p.getX(), p.getZ(), halfWidth);
            int off = water ? offshore(map, p.getX(), p.getZ(), halfWidth) : -1;
            boolean shoreHug = water && off < margin;
            if (endpoint || (water && !shoreHug)) {
                out.add(p);
                continue;
            }
            int minOff = shoreHug ? Math.max(off + 1, margin) : 0;
            BlockPos pushed = normalPush(map, p, before, after, halfWidth, minOff);
            out.add(pushed != null ? new BlockPos(pushed.getX(), fixedY, pushed.getZ()) : p);
        }
        return out;
    }

    /** 任一相邻段 2×2 supercover 撞陆 → true。 */
    private static boolean hasLandCrossing(RealBlockWaterMap map, List<BlockPos> path, int halfWidth) {
        for (int i = 1; i < path.size(); i++) {
            if (!map.segmentHullClear(path.get(i - 1), path.get(i), halfWidth)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 沿 before→after 航向法线把点往两侧偏移找 2×2 全水且离岸≥minOffshore 的最近候选(同距取离岸最远)。找不到返回 null。
     * 与 {@link WaterRouteNbtVerifier} 的 normalOffsetRepair 同思路(此处独立一份,供平滑前/后两层用)。
     */
    private static BlockPos normalPush(RealBlockWaterMap map, BlockPos p, BlockPos before, BlockPos after,
                                       int halfWidth, int minOffshore) {
        double hx = after.getX() - before.getX();
        double hz = after.getZ() - before.getZ();
        double len = Math.sqrt(hx * hx + hz * hz);
        double nx;
        double nz;
        if (len < 1.0E-6D) {
            nx = 1.0D;
            nz = 0.0D;
        } else {
            nx = -hz / len;
            nz = hx / len;
        }
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        int bestOffshore = -1;
        for (int d = 1; d <= PUSHBACK_MAX; d++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int cx = p.getX() + (int) Math.round(nx * d * sign);
                int cz = p.getZ() + (int) Math.round(nz * d * sign);
                if (!map.hullClear(cx, cz, halfWidth)) {
                    continue;
                }
                int off = offshore(map, cx, cz, halfWidth);
                if (off < minOffshore) {
                    continue;
                }
                if (d < bestDist || (d == bestDist && off > bestOffshore)) {
                    best = new BlockPos(cx, p.getY(), cz);
                    bestDist = d;
                    bestOffshore = off;
                }
            }
            if (best != null && bestDist == d) {
                break;
            }
        }
        return best;
    }

    /** (x,z) 周围保持 2×2 全水的环数(1..8) = 廉价的离岸距离评分。 */
    private static int offshore(RealBlockWaterMap map, int x, int z, int halfWidth) {
        for (int r = 1; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    if (!map.hullClear(x + dx, z + dz, halfWidth)) {
                        return r - 1;
                    }
                }
            }
        }
        return 8;
    }

    /**
     * 该点的动态离岸 margin(环数):按 before→p→after 处的航向转角线性放大——直线(转角≈0)=base 1,转角 ≥
     * {@link #MARGIN_FULL_TURN_DEGREES} 时到 {@link #MAX_OFFSHORE_MARGIN}。急转弯处航线让出更大冲出缓冲带,
     * 给船反应式循迹的滞后纠偏(过弯外切)留水域,偏出去也不撞岸。
     */
    private static int dynamicMargin(BlockPos before, BlockPos p, BlockPos after) {
        double ax = p.getX() - before.getX();
        double az = p.getZ() - before.getZ();
        double bx = after.getX() - p.getX();
        double bz = after.getZ() - p.getZ();
        double la = Math.sqrt(ax * ax + az * az);
        double lb = Math.sqrt(bx * bx + bz * bz);
        if (la < 1.0E-6D || lb < 1.0E-6D) {
            return OFFSHORE_MARGIN;
        }
        double cos = (ax * bx + az * bz) / (la * lb);
        cos = Math.max(-1.0D, Math.min(1.0D, cos));
        double turnDeg = Math.toDegrees(Math.acos(cos)); // 0=直行,180=掉头
        double t = Math.min(1.0D, turnDeg / MARGIN_FULL_TURN_DEGREES);
        return OFFSHORE_MARGIN + (int) Math.round(t * (MAX_OFFSHORE_MARGIN - OFFSHORE_MARGIN));
    }
}
