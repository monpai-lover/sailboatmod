package com.monpai.sailboatmod.route.water.lookahead;

import com.monpai.sailboatmod.route.repair.RoutePatch;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把绕行段拼进自动驾驶航线:插在 route[fromSegment] 与 route[toSegment+1] 之间,中间被障碍挡住的原航点移除。
 * 绕行首尾若正好落在这两个航点上则不重复插入。
 */
public final class RouteSplicer {
    public record Spliced(List<Vec3> route, int targetIndex, RoutePatch patch) {
    }

    private RouteSplicer() {
    }

    /** 船已驶向被移除的航点(越过了 route[fromSegment+1] 之前的位置):结果过时,丢弃后下一轮重扫。 */
    public static boolean isStale(RouteLookahead.Obstacle obstacle, int targetIndex) {
        return targetIndex > obstacle.fromSegment() + 1;
    }

    /** @return 过时({@link #isStale})或拼接后超过 maxWaypoints → empty。 */
    public static Optional<Spliced> splice(List<Vec3> route, RouteLookahead.Obstacle obstacle, List<BlockPos> detour,
                                           int targetIndex, int maxWaypoints) {
        int from = obstacle.fromSegment();
        int to = obstacle.toSegment() + 1;
        if (detour == null || detour.size() < 2 || isStale(obstacle, targetIndex) || from < 0 || to >= route.size()
                || from >= to) {
            return Optional.empty();
        }
        Vec3 start = route.get(from);
        Vec3 end = route.get(to);
        List<Vec3> inserted = new ArrayList<>(detour.size());
        for (int k = 0; k < detour.size(); k++) {
            BlockPos p = detour.get(k);
            if ((k == 0 && sameCell(p, start)) || (k == detour.size() - 1 && sameCell(p, end))) {
                continue;
            }
            inserted.add(new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D));
        }
        List<Vec3> spliced = new ArrayList<>(from + 1 + inserted.size() + route.size() - to);
        spliced.addAll(route.subList(0, from + 1));
        spliced.addAll(inserted);
        spliced.addAll(route.subList(to, route.size()));
        if (spliced.size() > maxWaypoints) {
            return Optional.empty();
        }
        List<Vec3> after = new ArrayList<>(inserted.size() + 2);
        after.add(start);
        after.addAll(inserted);
        after.add(end);
        RoutePatch patch = new RoutePatch(new ArrayList<>(route.subList(from, to + 1)), after);
        int newTarget = targetIndex <= from ? targetIndex : from + 1;
        return Optional.of(new Spliced(List.copyOf(spliced), newTarget, patch));
    }

    private static boolean sameCell(BlockPos p, Vec3 v) {
        return p.getX() == Mth.floor(v.x) && p.getZ() == Mth.floor(v.z);
    }
}
