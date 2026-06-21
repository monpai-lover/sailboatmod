package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>统一航点 NBT 校验后处理</b>(所有水路模式通用):读每个航点底下<b>真实 NBT 方块</b>判是否可航水体,
 * 把非水航点<b>删除</b>,对删除造成的断开段用 <b>NBT 局部 BFS 绕行</b>重连,<b>迭代</b>直到整条航线每个航点
 * + 每段连线都在可航水域(3×3 船宽全水)。
 *
 * <p><b>为什么用 NBT 不用噪声</b>:WorldPainter「原版生成器+populate」地图噪声地形 ≠ 真实写入方块
 * ([[worldpainter_noise_mismatch]]),只有读真实区块({@link RealBlockWaterMap} 的 NBT 后台直读)才准。
 * 旧 {@link RealWaterVerifier} 走 NoiseChunk 密度,在这种地图上校验必误判,故不复用,改本类纯 NBT。
 *
 * <p><b>判水口径</b>:{@link RealBlockWaterMap#hullClear}(3×3 船宽占地全水)。逐航点查;非水即删。
 * 删点后相邻好点直连若仍穿陆(3×3 段校验不过)→ {@link RealBlockWaterMap#localRerouteNbt} 在局部包围盒内
 * 沿真实水格 BFS 绕一条水路接上。绕不开则保留断点(不毁整条航线),记一笔。
 *
 * <p><b>迭代</b>:绕行可能引入新点,新点也要校验 → 最多 {@link #MAX_ITERATIONS} 轮,直到一轮内无任何删除/绕行
 * (收敛=全程全水)。后台线程安全(全是命中缓存的内存数组查;按需模式未命中触发后台 NBT 读,不 join 主线程)。
 */
public final class WaterRouteNbtVerifier {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_ITERATIONS = 6;       // 删点+重连迭代轮上限(防绕行反复引入新点不收敛)
    private static final int REROUTE_MARGIN = 64;      // 局部绕行包围盒外扩
    private static final int REROUTE_MAX_CELLS = 6000; // 局部绕行 BFS 探格上限
    // 连线穿陆删点造成的跨湖段需要更大绕行预算(湖挡住主航道,绕行可能远超 64 格):
    private static final int BRIDGE_MARGIN = 128;       // 跨湖绕行包围盒外扩(连线扫描删点专用)
    private static final int BRIDGE_MAX_CELLS = 20000;  // 跨湖绕行 BFS 探格上限

    private WaterRouteNbtVerifier() {
    }

    /**
     * 校验+修复一条航线,迭代到全水。
     * @param map        已 enableOnDemand 的真实方块水图
     * @param waypoints  任意模式产出的航点(平滑后)
     * @param halfWidth  船宽半径(1=船 3×3)
     * @return 全程可航水域的航点(可能比输入更密/更疏);无法修复返回原输入(不毁航线)。
     */
    public static List<BlockPos> verify(RealBlockWaterMap map, List<BlockPos> waypoints, int halfWidth) {
        if (map == null || waypoints == null || waypoints.size() < 2) {
            return waypoints;
        }
        List<BlockPos> cur = new ArrayList<>(waypoints);
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            int dropped = 0;   // 本轮删除的非水航点数
            int rerouted = 0;  // 本轮局部绕行重连的段数
            int keptBad = 0;   // 本轮绕不开保留的断点数

            // 1) 处理非水航点:先尝试「沿航向法线侧移」找 3×3 全水替换点(保住航线意图);侧移找不到才删。
            //    保首尾(泊位,已信任可航)。([[water_route_land_node_normal_offset]])
            int repaired = 0;
            List<BlockPos> kept = new ArrayList<>();
            for (int i = 0; i < cur.size(); i++) {
                BlockPos p = cur.get(i);
                boolean endpoint = (i == 0 || i == cur.size() - 1);
                boolean water = map.hullClear(p.getX(), p.getZ(), halfWidth);
                // 2026-06 离岸余量(修贴岸蹭行):不只「非水」要修,「是水但贴岸」(离岸环数 < MIN_OFFSHORE)也修——船 narrow-long,
                // 转弯尾部会甩向岸,平滑曲线贴着岸走实测会蹭陆。贴岸点同样走法线侧移往水心拉。([[water_route_land_node_normal_offset]])
                boolean shoreHug = water && !endpoint && offshoreClearance(map, p.getX(), p.getZ(), halfWidth) < MIN_OFFSHORE;
                if (endpoint || (water && !shoreHug)) {
                    kept.add(p);
                    continue;
                }
                // land node 或 贴岸点:沿 before→after 航向法线往水心侧移。
                BlockPos before = !kept.isEmpty() ? kept.get(kept.size() - 1) : (i > 0 ? cur.get(i - 1) : p);
                BlockPos after = i + 1 < cur.size() ? cur.get(i + 1) : p;
                // 陆点:minOffshore=0(任何 2×2 全水即可);贴岸点:要求比原点更离岸(原离岸+1),否则会把自己当候选原地不动。
                int minOff = shoreHug ? offshoreClearance(map, p.getX(), p.getZ(), halfWidth) + 1 : 0;
                BlockPos fixed = normalOffsetRepair(map, p, before, after, halfWidth, minOff);
                if (fixed != null) {
                    kept.add(fixed);
                    repaired++;
                    if (repaired <= 8) {
                        LOGGER.info("[WaterPath] 航点NBT校验:法线侧移修复 #{} {} -> {} ({})",
                                i, p, fixed, shoreHug ? "贴岸拉离" : "陆点修复");
                    }
                } else if (shoreHug) {
                    // 贴岸点侧移找不到更离岸的水 → 保留原点(它至少可航,只是贴岸),不删(删可航点太激进)。
                    kept.add(p);
                } else {
                    dropped++;
                    if (dropped <= 8) {
                        LOGGER.info("[WaterPath] 航点NBT校验:侧移失败,删非水航点 #{} {} 原因={}",
                                i, p, map.sampleHullDiagnostic(p.getX(), p.getZ(), halfWidth));
                    }
                }
            }
            if (kept.size() < 2) {
                LOGGER.warn("[WaterPath] 航点NBT校验:删后不足 2 点,放弃修复,返回原航线");
                return waypoints;
            }

            // 1.5) 连线穿陆扫描(2026-06):步① 只查单点是否水,漏掉「单点是水但与前后航点连线穿陆」的点——
            //      典型是内陆湖航点(湖也是 minecraft:water,hullClear=true,但与主航道不连通,连前后点的连线必穿陆)。
            //      逐三元组 (prev,cur,next) 用 segmentHullClear 查 cur 两条连线;穿陆则先沿法线侧移到「两条连线都通」的
            //      水格(normalOffsetRepairLinked),侧移不成走条件剔除护栏:仅当「删 cur 后 prev→next 能大预算绕通」才删,
            //      否则保留原 cur(虽在湖里,但点数不变、绝不把湖里点换成 prev→next 大跳断点)。([[water_midseg_pierce_land_fixes]])
            int linkRepaired = 0;  // 连线穿陆→法线侧移修好
            int linkDropped = 0;   // 连线穿陆→侧移不成但删后能绕通,删除
            int linkKept = 0;      // 连线穿陆→侧移/删都不行,保留原点(护栏)
            List<BlockPos> linked = new ArrayList<>();
            linked.add(kept.get(0));
            for (int i = 1; i < kept.size() - 1; i++) {
                BlockPos prev = linked.get(linked.size() - 1);
                BlockPos mid = kept.get(i);
                BlockPos next = kept.get(i + 1);
                boolean prevClear = map.segmentHullClear(prev, mid, halfWidth);
                boolean nextClear = map.segmentHullClear(mid, next, halfWidth);
                if (prevClear && nextClear) {
                    linked.add(mid); // 两条连线都通,保留
                    continue;
                }
                // 连线穿陆:先法线侧移到「到 prev、到 next 两条连线都通」的水格。
                BlockPos fixed = normalOffsetRepairLinked(map, mid, prev, next, halfWidth);
                if (fixed != null) {
                    linked.add(fixed);
                    linkRepaired++;
                    if (linkRepaired <= 8) {
                        LOGGER.info("[WaterPath] 航点NBT校验:连线穿陆侧移 #{} {} -> {}", i, mid, fixed);
                    }
                    continue;
                }
                // 侧移不成 → 条件剔除护栏:仅当删 mid 后 prev→next 能大预算局部绕通才删,否则保留原 mid(不制造大跳)。
                boolean canBridge = map.segmentHullClear(prev, next, halfWidth)
                        || !map.localRerouteNbt(prev, next, halfWidth, BRIDGE_MARGIN, BRIDGE_MAX_CELLS).isEmpty();
                if (canBridge) {
                    linkDropped++;
                    if (linkDropped <= 8) {
                        LOGGER.info("[WaterPath] 航点NBT校验:连线穿陆删点(删后可绕通) #{} {}", i, mid);
                    }
                    // 不加入 linked;prev→next 的实际绕行航点交步② 用大预算重连(见下)。
                } else {
                    linked.add(mid); // 护栏:删了反而留大跳 → 保留原点
                    linkKept++;
                    if (linkKept <= 4) {
                        LOGGER.info("[WaterPath] 航点NBT校验:连线穿陆但删后绕不通,保留原点 #{} {}", i, mid);
                    }
                }
            }
            linked.add(kept.get(kept.size() - 1));
            kept = linked;
            if (kept.size() < 2) {
                LOGGER.warn("[WaterPath] 航点NBT校验:连线扫描后不足 2 点,放弃修复,返回原航线");
                return waypoints;
            }

            // 2) 删点后相邻好点直连若穿陆 → 局部 BFS 绕行重连。重建出迭代后的航线。
            //    margin/maxCells:常规段用 REROUTE_*;连线扫描删点造成的跨湖段需要更大预算(BRIDGE_*),用段长判别。
            List<BlockPos> rebuilt = new ArrayList<>();
            rebuilt.add(kept.get(0));
            for (int i = 1; i < kept.size(); i++) {
                BlockPos prev = rebuilt.get(rebuilt.size() - 1);
                BlockPos next = kept.get(i);
                if (map.segmentHullClear(prev, next, halfWidth)) {
                    rebuilt.add(next);
                    continue;
                }
                // 长段(>REROUTE_MARGIN)八成是连线扫描删湖点造成的跨湖绕行 → 用大预算;短段用常规预算。
                boolean longSeg = prev.distSqr(next) > (long) REROUTE_MARGIN * REROUTE_MARGIN;
                int margin = longSeg ? BRIDGE_MARGIN : REROUTE_MARGIN;
                int maxCells = longSeg ? BRIDGE_MAX_CELLS : REROUTE_MAX_CELLS;
                List<BlockPos> detour = map.localRerouteNbt(prev, next, halfWidth, margin, maxCells);
                if (!detour.isEmpty()) {
                    for (int k = 1; k < detour.size(); k++) { // detour 含 prev,跳过首元素
                        rebuilt.add(detour.get(k));
                    }
                    rerouted++;
                } else {
                    // 绕不开(按需读不到/无水路)→ 保留断点(不毁航线),记一笔。
                    rebuilt.add(next);
                    keptBad++;
                }
            }

            cur = rebuilt;
            LOGGER.info("[WaterPath] 航点NBT校验 第{}轮:法线侧移修复={} 删非水点={} 连线侧移={} 连线删点={} 连线保留={} 局部绕行重连={} 绕不开保留={} 航点{}→{}",
                    iter + 1, repaired, dropped, linkRepaired, linkDropped, linkKept, rerouted, keptBad, waypoints.size(), cur.size());

            // 收敛:本轮无任何修复/删除/绕行(单点 + 连线 + 段重连全静默 = 全程全水且连线全通)→ 完成。
            if (repaired == 0 && dropped == 0 && linkRepaired == 0 && linkDropped == 0 && rerouted == 0) {
                if (keptBad > 0) {
                    LOGGER.warn("[WaterPath] 航点NBT校验:仍有 {} 处绕不开(按需读不到/无水路),保留断点", keptBad);
                }
                return cur;
            }
        }
        LOGGER.warn("[WaterPath] 航点NBT校验:达迭代上限 {} 轮仍未完全收敛,返回当前最优", MAX_ITERATIONS);
        return cur;
    }

    /** 法线侧移最大偏移量(格):land 点最远向法线两侧找这么多格的 2×2 全水替换点。 */
    private static final int NORMAL_OFFSET_MAX = 48;

    /** 离岸余量阈值(环数):航点离岸 < 此值视作「贴岸」,即便是水也要往水心侧移拉离。1 = 8 邻里有陆就算贴岸。 */
    private static final int MIN_OFFSHORE = 1;

    /**
     * <b>沿航向法线侧移修复 land/贴岸 点</b>([[water_route_land_node_normal_offset]]):取 before→after 航向的法线方向,
     * 在法线两侧逐格(1..{@link #NORMAL_OFFSET_MAX})偏移找候选格,2×2 全水(hullClear)<b>且离岸≥minOffshore</b>即合格;
     * 在合格候选里选「离原点最近、同距下离岸最远」者替换。找不到返回 null。
     *
     * <p>{@code minOffshore}:陆点修复传 0(任何 hullClear 都行);贴岸点修复传「原点离岸+1」(必须拉得更离岸,否则
     * 原地打转——贴岸点本身 hullClear 会被自己当候选)。
     *
     * <p>Why 法线侧移而非顺航向外推/删点:穿陆/贴岸通常是路径在该处侧向偏出贴岸,沿航向法线把它横向拉回航道中心,
     * 保持前进方向不变;顺航向推会改变航线形状,删点会丢失绕行意图。
     */
    private static BlockPos normalOffsetRepair(RealBlockWaterMap map, BlockPos land, BlockPos before, BlockPos after,
                                               int halfWidth, int minOffshore) {
        double hx = after.getX() - before.getX();
        double hz = after.getZ() - before.getZ();
        double len = Math.sqrt(hx * hx + hz * hz);
        // 法线方向(单位向量):航向 (hx,hz) 的法线是 (-hz,hx)。航向退化(before==after)时用 X 轴法线兜底。
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
        for (int d = 1; d <= NORMAL_OFFSET_MAX; d++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int cx = land.getX() + (int) Math.round(nx * d * sign);
                int cz = land.getZ() + (int) Math.round(nz * d * sign);
                if (!map.hullClear(cx, cz, halfWidth)) {
                    continue;
                }
                int offshore = offshoreClearance(map, cx, cz, halfWidth);
                if (offshore < minOffshore) {
                    continue; // 不够离岸(贴岸修复时排除「同样贴岸」的候选,陆点修复 minOffshore=0 不排除)
                }
                // nearest first; at equal distance prefer the one farther from shore (more open water).
                if (d < bestDist || (d == bestDist && offshore > bestOffshore)) {
                    best = new BlockPos(cx, land.getY(), cz);
                    bestDist = d;
                    bestOffshore = offshore;
                }
            }
            // already found a hull-clear cell at this offset on at least one side -> nearest tier resolved, stop.
            if (best != null && bestDist == d) {
                break;
            }
        }
        return best;
    }

    /**
     * <b>连线穿陆专用法线侧移</b>(2026-06):骨架同 {@link #normalOffsetRepair},但候选格验收从「单点 hullClear」
     * 升级为「候选格到 {@code prev}、到 {@code next} 两条 {@link RealBlockWaterMap#segmentHullClear} 都通」——
     * 解决内陆湖航点:它自己 hullClear=true(单点验收会原地通过),但与前后点连线穿陆。这里强制找一个「连线都不穿陆」
     * 的水格,找不到返回 null(交上层条件剔除护栏)。沿 prev→next 航向法线两侧逐格(1..{@link #NORMAL_OFFSET_MAX})找,
     * 取最近的合格点。
     */
    private static BlockPos normalOffsetRepairLinked(RealBlockWaterMap map, BlockPos cur, BlockPos prev, BlockPos next,
                                                     int halfWidth) {
        double hx = next.getX() - prev.getX();
        double hz = next.getZ() - prev.getZ();
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
        for (int d = 1; d <= NORMAL_OFFSET_MAX; d++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int cx = cur.getX() + (int) Math.round(nx * d * sign);
                int cz = cur.getZ() + (int) Math.round(nz * d * sign);
                if (!map.hullClear(cx, cz, halfWidth)) {
                    continue;
                }
                BlockPos cand = new BlockPos(cx, cur.getY(), cz);
                // 候选必须让「到 prev、到 next」两条连线都不穿陆,否则没解决问题(内陆湖里挪一格仍穿陆)。
                if (map.segmentHullClear(prev, cand, halfWidth) && map.segmentHullClear(cand, next, halfWidth)) {
                    return cand;
                }
            }
        }
        return null;
    }

    /** How many rings (1..8) around (x,z) stay fully hull-clear -> a cheap "distance from shore" score. */
    private static int offshoreClearance(RealBlockWaterMap map, int x, int z, int halfWidth) {
        for (int r = 1; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // current ring only
                    }
                    if (!map.hullClear(x + dx, z + dz, halfWidth)) {
                        return r - 1;
                    }
                }
            }
        }
        return 8;
    }
}
