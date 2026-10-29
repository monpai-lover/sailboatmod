package com.monpai.sailboatmod.route.water.lookahead;

import com.monpai.sailboatmod.route.water.RealBlockWaterMap;
import com.monpai.sailboatmod.route.water.RuntimeWaterAStar;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** <b>后台线程</b>:障碍前后锚点间的局部绕行。先 BFS(margin 64),不行再 A*(margin 256),最后贪婪拉直。 */
public final class LookaheadReroute {
    static final int BFS_MARGIN = 64;
    static final int BFS_MAX_CELLS = 20_000;
    static final int ASTAR_MARGIN = 256;
    static final int ASTAR_MAX_EXPANSIONS = 60_000;
    private static final int PULL_WINDOW = 128;

    private LookaheadReroute() {
    }

    /** @return a..b 的绕行航点(含首尾);找不到返回空。 */
    public static List<BlockPos> compute(RealBlockWaterMap map, BlockPos a, BlockPos b, int halfWidth) {
        List<BlockPos> path = map.localRerouteNbt(a, b, halfWidth, BFS_MARGIN, BFS_MAX_CELLS);
        if (path == null || path.size() < 2) {
            path = RuntimeWaterAStar.findPath(map, a, b, halfWidth, ASTAR_MARGIN, ASTAR_MAX_EXPANSIONS);
        }
        if (path == null || path.size() < 2) {
            return List.of();
        }
        return pull(map, path, halfWidth);
    }

    /** 贪婪拉直:从每个点出发,取窗口内最远的、连线仍 2×2 全水的点。 */
    static List<BlockPos> pull(RealBlockWaterMap map, List<BlockPos> path, int halfWidth) {
        List<BlockPos> out = new ArrayList<>();
        int last = path.size() - 1;
        int i = 0;
        out.add(path.get(0));
        while (i < last) {
            int j = Math.min(last, i + PULL_WINDOW);
            while (j > i + 1 && !map.segmentHullClear(path.get(i), path.get(j), halfWidth)) {
                j--;
            }
            out.add(path.get(j));
            i = j;
        }
        return out;
    }
}
