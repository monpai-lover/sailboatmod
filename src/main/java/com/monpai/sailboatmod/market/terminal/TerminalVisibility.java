package com.monpai.sailboatmod.market.terminal;

import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData.Kind;
import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData.TerminalEntry;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 市场商品可见性/可购买性的<b>唯一判定实现</b>:某货源城镇的商品对某查看城镇是否可见(=有航路或陆路连接)。
 *
 * <p>纯读 {@link TerminalNetworkSavedData}(内存 SavedData),<b>不碰世界、不读区块、不实时寻路</b>——这是修复
 * "有航路也看不到"根因的核心(原因:旧逻辑实时读远端码头方块,远端区块卸载即误判)。</p>
 *
 * <p><b>MarketBlockEntity(客户端可见性)与 web(聚合购买)共用本类</b>,避免两份判定逻辑漂移。</p>
 *
 * <p><b>全双向</b>(用户决策:A↔B 任一有路线即互通):港口看 source/target 任一方路线终点指向对方;
 * 陆路看 source/target 任一方 reachableTownIds 含对方。</p>
 */
public final class TerminalVisibility {
    private TerminalVisibility() {
    }

    /** 货源城镇 sourceTownId 的商品对查看城镇 viewerTownId 是否可见:同城镇直接可见,否则需港路或陆路连通。 */
    public static boolean isVisible(TerminalNetworkSavedData net, String sourceTownId, String viewerTownId) {
        if (net == null || isBlank(sourceTownId) || isBlank(viewerTownId)) {
            return false;
        }
        if (sourceTownId.equals(viewerTownId)) {
            return true;
        }
        return hasPortRoute(net, sourceTownId, viewerTownId) || hasLandRoute(net, sourceTownId, viewerTownId);
    }

    /** 港路连通(双向):任一方港口的出航路线终点名 == 对方任一港口的 dockName。 */
    public static boolean hasPortRoute(TerminalNetworkSavedData net, String townA, String townB) {
        if (net == null || isBlank(townA) || isBlank(townB)) {
            return false;
        }
        List<TerminalEntry> portsA = net.forTown(townA, Kind.PORT);
        List<TerminalEntry> portsB = net.forTown(townB, Kind.PORT);
        return routeEndPointsInto(portsA, portsB) || routeEndPointsInto(portsB, portsA);
    }

    /** 陆路连通(双向):任一方驿站的 reachableTownIds 含对方城镇。 */
    public static boolean hasLandRoute(TerminalNetworkSavedData net, String townA, String townB) {
        if (net == null || isBlank(townA) || isBlank(townB)) {
            return false;
        }
        return anyStationReaches(net.forTown(townA, Kind.POST_STATION), townB)
                || anyStationReaches(net.forTown(townB, Kind.POST_STATION), townA);
    }

    /** from 方任一港口的某条路线终点名,匹配 to 方任一港口的 dockName(大小写不敏感,与 findRouteIndexByDestinationDock 同口径)。 */
    private static boolean routeEndPointsInto(List<TerminalEntry> fromPorts, List<TerminalEntry> toPorts) {
        if (fromPorts.isEmpty() || toPorts.isEmpty()) {
            return false;
        }
        for (TerminalEntry from : fromPorts) {
            for (String endDockName : from.routeEndDockNames()) {
                if (isBlank(endDockName)) {
                    continue;
                }
                for (TerminalEntry to : toPorts) {
                    if (equalsIgnoreCaseTrim(endDockName, to.dockName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean anyStationReaches(List<TerminalEntry> stations, String targetTownId) {
        for (TerminalEntry station : stations) {
            Set<String> reachable = station.reachableTownIds();
            if (reachable.contains(targetTownId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreCaseTrim(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
