package com.monpai.sailboatmod.route.repair;

import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.WaypointMeta;
import com.monpai.sailboatmod.route.water.WaterAutoRouteService;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 航行中修正的一段航线:{@code before} 是原航线上锚点到锚点(含)的原航点,{@code after} 是替换航点(首尾与 before 相同)。
 * 回写时按内容匹配 before,方向无关(反向航线用 {@link #reversed()})。
 */
public record RoutePatch(List<Vec3> before, List<Vec3> after) {
    /** 与自动驾驶航点上限一致;超过会在下次发车时被截断,宁可不写回。 */
    public static final int MAX_WAYPOINTS = 512;
    private static final int NONE = -1;
    private static final int AMBIGUOUS = -2;

    public RoutePatch {
        before = List.copyOf(before);
        after = List.copyOf(after);
    }

    public RoutePatch reversed() {
        List<Vec3> b = new ArrayList<>(before);
        List<Vec3> a = new ArrayList<>(after);
        Collections.reverse(b);
        Collections.reverse(a);
        return new RoutePatch(b, a);
    }

    /**
     * 套用到一条航线:只改自动生成的水路航线;before 必须在航点中<b>唯一</b>连续出现(正向找不到再找反向)。
     * 航线已被重规划/删改(找不到或出现多次)→ empty,绝不猜。重算长度;metas 与航点等长时替换段标 RAW。
     */
    public Optional<RouteDefinition> applyTo(RouteDefinition route) {
        if (route == null || before.size() < 2 || after.size() < 2 || !WaterAutoRouteService.isGeneratedWaterRoute(route)) {
            return Optional.empty();
        }
        List<Vec3> waypoints = route.waypoints();
        List<Vec3> replacement = after;
        int at = uniqueIndexOf(waypoints, before);
        if (at == NONE) {
            RoutePatch reverse = reversed();
            at = uniqueIndexOf(waypoints, reverse.before);
            replacement = reverse.after;
        }
        if (at < 0) {
            return Optional.empty();
        }
        int end = at + before.size();
        if (waypoints.size() - before.size() + replacement.size() > MAX_WAYPOINTS) {
            return Optional.empty();
        }
        List<Vec3> patched = new ArrayList<>(waypoints.size() - before.size() + replacement.size());
        patched.addAll(waypoints.subList(0, at));
        patched.addAll(replacement);
        patched.addAll(waypoints.subList(end, waypoints.size()));

        List<WaypointMeta> metas = List.of();
        if (route.waypointMetas().size() == waypoints.size()) {
            List<WaypointMeta> m = new ArrayList<>(patched.size());
            m.addAll(route.waypointMetas().subList(0, at));
            WaypointMeta raw = new WaypointMeta(WaypointMeta.SEGMENT_MID, WaypointMeta.ORIGIN_RAW);
            for (int i = 0; i < replacement.size(); i++) {
                m.add(raw);
            }
            m.addAll(route.waypointMetas().subList(end, waypoints.size()));
            metas = m;
        }
        double length = 0.0D;
        for (int i = 1; i < patched.size(); i++) {
            length += patched.get(i - 1).distanceTo(patched.get(i));
        }
        return Optional.of(new RouteDefinition(route.name(), patched, route.authorName(), route.authorUuid(),
                route.createdAtEpochMillis(), length, route.startDockName(), route.endDockName(), metas));
    }

    private static int uniqueIndexOf(List<Vec3> haystack, List<Vec3> needle) {
        int found = NONE;
        for (int i = 0; i + needle.size() <= haystack.size(); i++) {
            if (haystack.subList(i, i + needle.size()).equals(needle)) {
                if (found != NONE) {
                    return AMBIGUOUS;
                }
                found = i;
            }
        }
        return found;
    }
}
