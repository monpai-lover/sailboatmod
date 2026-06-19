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

            // 1) 删非水航点:逐航点读底下真实方块,非水(3×3 占地有陆/中心非水)即删。保首尾(泊位,已信任可航)。
            List<BlockPos> kept = new ArrayList<>();
            for (int i = 0; i < cur.size(); i++) {
                BlockPos p = cur.get(i);
                boolean endpoint = (i == 0 || i == cur.size() - 1);
                if (endpoint || map.hullClear(p.getX(), p.getZ(), halfWidth)) {
                    kept.add(p);
                } else {
                    dropped++;
                    if (dropped <= 8) {
                        LOGGER.info("[WaterPath] 航点NBT校验:删非水航点 #{} {} 原因={}",
                                i, p, map.sampleHullDiagnostic(p.getX(), p.getZ(), halfWidth));
                    }
                }
            }
            if (kept.size() < 2) {
                LOGGER.warn("[WaterPath] 航点NBT校验:删后不足 2 点,放弃修复,返回原航线");
                return waypoints;
            }

            // 2) 删点后相邻好点直连若穿陆 → 局部 BFS 绕行重连。重建出迭代后的航线。
            List<BlockPos> rebuilt = new ArrayList<>();
            rebuilt.add(kept.get(0));
            for (int i = 1; i < kept.size(); i++) {
                BlockPos prev = rebuilt.get(rebuilt.size() - 1);
                BlockPos next = kept.get(i);
                if (map.segmentHullClear(prev, next, halfWidth)) {
                    rebuilt.add(next);
                    continue;
                }
                List<BlockPos> detour = map.localRerouteNbt(prev, next, halfWidth, REROUTE_MARGIN, REROUTE_MAX_CELLS);
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
            LOGGER.info("[WaterPath] 航点NBT校验 第{}轮:删非水点={} 局部绕行重连={} 绕不开保留={} 航点{}→{}",
                    iter + 1, dropped, rerouted, keptBad, waypoints.size(), cur.size());

            // 收敛:本轮无删除、无绕行(全程全水)→ 完成。
            if (dropped == 0 && rerouted == 0) {
                if (keptBad > 0) {
                    LOGGER.warn("[WaterPath] 航点NBT校验:仍有 {} 处绕不开(按需读不到/无水路),保留断点", keptBad);
                }
                return cur;
            }
        }
        LOGGER.warn("[WaterPath] 航点NBT校验:达迭代上限 {} 轮仍未完全收敛,返回当前最优", MAX_ITERATIONS);
        return cur;
    }
}
