package com.monpai.sailboatmod.route.repair;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把航行中的绕行修正写回保存的自动水路航线(船上目录 + 起讫码头)。码头未加载 → 暂存 {@link RouteRepairSavedData},
 * 码头 onLoad 时套用。按内容匹配,重复套用是 no-op。主线程调用。
 */
public final class RouteRepairService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RouteRepairService() {
    }

    /** 逐条套用;没有任何一条改变时返回传入的同一个 list。 */
    public static List<RouteDefinition> applyToRoutes(List<RouteDefinition> routes, RoutePatch patch) {
        if (routes == null || routes.isEmpty() || patch == null) {
            return routes;
        }
        List<RouteDefinition> out = new ArrayList<>(routes.size());
        boolean changed = false;
        for (RouteDefinition route : routes) {
            Optional<RouteDefinition> patched = patch.applyTo(route);
            changed |= patched.isPresent();
            out.add(patched.orElse(route));
        }
        return changed ? out : routes;
    }

    /** 写回到各码头:已加载直接改,未加载暂存。null 位置跳过。 */
    public static void submit(ServerLevel level, RoutePatch patch, BlockPos... dockPositions) {
        if (level == null || patch == null || dockPositions == null) {
            return;
        }
        String dimension = level.dimension().location().toString();
        for (BlockPos pos : dockPositions) {
            if (pos == null) {
                continue;
            }
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DockBlockEntity dock) {
                applyToDock(dock, patch);
            } else {
                RouteRepairSavedData.get(level).enqueue(dimension, pos.immutable(), patch);
                LOGGER.info("[RouteRepair] 码头 {} 未加载,修正暂存", pos);
            }
        }
    }

    /** 码头加载时:套用并清掉它的暂存修正。 */
    public static void applyPending(ServerLevel level, BlockPos pos, DockBlockEntity dock) {
        List<RoutePatch> patches = RouteRepairSavedData.get(level).drain(level.dimension().location().toString(), pos);
        for (RoutePatch patch : patches) {
            applyToDock(dock, patch);
        }
    }

    private static void applyToDock(DockBlockEntity dock, RoutePatch patch) {
        List<RouteDefinition> routes = dock.getRoutesForMap();
        List<RouteDefinition> updated = applyToRoutes(routes, patch);
        if (updated != routes) {
            dock.setRoutes(updated, dock.getSelectedRouteIndex());
            LOGGER.info("[RouteRepair] 码头 {} 航线已写回绕行修正", dock.getBlockPos());
        }
    }
}
