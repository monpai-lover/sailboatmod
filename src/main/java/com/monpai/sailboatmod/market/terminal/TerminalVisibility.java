package com.monpai.sailboatmod.market.terminal;

import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData.Kind;
import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData.TerminalEntry;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 市场商品可见性/可购买性的<b>唯一判定实现</b>:某货源城镇的商品对某查看城镇是否可见(=路网图上连通)。
 *
 * <p>纯读 {@link TerminalNetworkSavedData}(内存 SavedData),<b>不碰世界、不读区块、不实时寻路</b>——这是修复
 * "有航路也看不到"根因的核心(原因:旧逻辑实时读远端码头方块,远端区块卸载即误判)。</p>
 *
 * <p><b>MarketBlockEntity(客户端可见性)与 web(聚合购买)共用本类</b>,避免两份判定逻辑漂移。</p>
 *
 * <p><b>图论多跳连通</b>(用户决策):把所有终端的水路边(港口出航航线终点)+陆路边(驿站 reachableTownIds)
 * 混在<b>同一张 town↔town 无向图</b>,A 到 B 即使无直达边,只要图上连通(经中间城镇中转)也算可达——省道路开支。
 * 水陆可在同一条中转链混用(A 海路到 B,B 陆路到 C 即 A 可达 C)。连通只看路网,不管有无空闲载具。
 * 图无向天然双向(A↔B 任一有路线即互通)。</p>
 */
public final class TerminalVisibility {
    private TerminalVisibility() {
    }

    /** 货源城镇 sourceTownId 的商品对查看城镇 viewerTownId 是否可见:同城镇直接可见,否则路网图上连通(多跳中转)。 */
    public static boolean isVisible(TerminalNetworkSavedData net, String sourceTownId, String viewerTownId) {
        if (net == null || isBlank(sourceTownId) || isBlank(viewerTownId)) {
            return false;
        }
        if (sourceTownId.equals(viewerTownId)) {
            return true;
        }
        return isConnectedInGraph(buildTownGraph(net), sourceTownId, viewerTownId);
    }

    /**
     * 批量重载:调用方(如 web 聚合视图逐 listing 判定)先 {@link #buildGraph} 建一次图,循环内复用,避免每次重建图。
     */
    public static boolean isVisible(Map<String, Set<String>> prebuiltGraph, String sourceTownId, String viewerTownId) {
        if (prebuiltGraph == null || isBlank(sourceTownId) || isBlank(viewerTownId)) {
            return false;
        }
        if (sourceTownId.equals(viewerTownId)) {
            return true;
        }
        return isConnectedInGraph(prebuiltGraph, sourceTownId, viewerTownId);
    }

    /** 供批量场景在循环外建一次图复用。 */
    public static Map<String, Set<String>> buildGraph(TerminalNetworkSavedData net) {
        return buildTownGraph(net);
    }

    /**
     * 从终端清单构建 town↔town 无向连通图。水路边(港口出航航线终点)+陆路边(驿站 reachableTownIds)混在同一张图。
     */
    static Map<String, Set<String>> buildTownGraph(TerminalNetworkSavedData net) {
        Map<String, Set<String>> adj = new HashMap<>();
        if (net == null) {
            return adj;
        }
        List<TerminalEntry> all = net.all();

        // dockName(归一化小写)-> 拥有该 dock 的 townId 集合。跨所有 entry 反查港路对端(防御同名 dock,语义本应唯一)。
        Map<String, Set<String>> dockNameToTowns = new HashMap<>();
        for (TerminalEntry e : all) {
            String town = e.townId();
            String dock = normalizeDock(e.dockName());
            if (isBlank(town) || dock.isEmpty()) {
                continue;
            }
            dockNameToTowns.computeIfAbsent(dock, k -> new HashSet<>()).add(town);
        }

        for (TerminalEntry e : all) {
            String fromTown = e.townId();
            if (isBlank(fromTown)) {
                continue;
            }
            // 水路边:本港口每条出航路线终点名 -> 反查对端 townId 连边。
            if (e.kind() == Kind.PORT) {
                for (String endName : e.routeEndDockNames()) {
                    String key = normalizeDock(endName);
                    if (key.isEmpty()) {
                        continue;
                    }
                    Set<String> targets = dockNameToTowns.get(key);
                    if (targets == null) {
                        continue;
                    }
                    for (String toTown : targets) {
                        addEdge(adj, fromTown, toTown);
                    }
                }
            }
            // 陆路边:本驿站 reachableTownIds 每个 townId 直接连边。
            if (e.kind() == Kind.POST_STATION) {
                for (String toTown : e.reachableTownIds()) {
                    addEdge(adj, fromTown, toTown);
                }
            }
        }
        return adj;
    }

    /** 无向加边,排自连与空白(同 town 多港口/自指 dock 不污染图)。 */
    private static void addEdge(Map<String, Set<String>> adj, String a, String b) {
        if (isBlank(a) || isBlank(b) || a.equals(b)) {
            return;
        }
        adj.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        adj.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }

    /** dock 名归一化:与旧 equalsIgnoreCaseTrim 同口径(trim + 小写 ROOT)。 */
    private static String normalizeDock(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** 图上 source 与 viewer 是否连通(同 town 已在 isVisible 短路)。 */
    static boolean isConnectedInGraph(Map<String, Set<String>> adj, String source, String viewer) {
        if (adj == null || isBlank(source) || isBlank(viewer)) {
            return false;
        }
        if (source.equals(viewer)) {
            return true;
        }
        if (!adj.containsKey(source) || !adj.containsKey(viewer)) {
            return false; // 任一为孤立 town(无边),不连通
        }
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        visited.add(source);
        queue.add(source);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String next : adj.getOrDefault(cur, Set.of())) {
                if (next.equals(viewer)) {
                    return true;
                }
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
