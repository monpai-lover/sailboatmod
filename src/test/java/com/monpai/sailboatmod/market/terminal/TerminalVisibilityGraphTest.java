package com.monpai.sailboatmod.market.terminal;

import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData.Kind;
import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData.TerminalEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TerminalVisibility} 图论连通判定的纯内存单元测试(不依赖 ServerLevel)。
 * 覆盖:同城镇、港路直连(双向)、港路二跳、陆路直连、陆路二跳、<b>水陆混合中转</b>、不连通/孤立、大小写、排自连、空图。
 */
class TerminalVisibilityGraphTest {
    private static final String DIM = "minecraft:overworld";
    private static long nextPos = 1L;

    private static TerminalEntry port(String townId, String dockName, List<String> routeEnds) {
        return new TerminalEntry(DIM, nextPos++, Kind.PORT, dockName, townId, "", routeEnds, Set.of());
    }

    private static TerminalEntry station(String townId, Set<String> reachableTowns) {
        return new TerminalEntry(DIM, nextPos++, Kind.POST_STATION, "", townId, "", List.of(), reachableTowns);
    }

    private static TerminalNetworkSavedData net(TerminalEntry... entries) {
        TerminalNetworkSavedData net = new TerminalNetworkSavedData();
        for (TerminalEntry e : entries) {
            net.putEntry(e);
        }
        return net;
    }

    @Test
    void sameTown_alwaysVisible() {
        TerminalNetworkSavedData net = net();
        assertTrue(TerminalVisibility.isVisible(net, "town-a", "town-a"));
    }

    @Test
    void portDirect_bidirectional() {
        // town-a 港口 Alpha 出航到 Beta;town-b 港口名为 Beta。
        TerminalNetworkSavedData net = net(
                port("town-a", "Alpha", List.of("Beta")),
                port("town-b", "Beta", List.of()));
        assertTrue(TerminalVisibility.isVisible(net, "town-a", "town-b"));
        assertTrue(TerminalVisibility.isVisible(net, "town-b", "town-a"));
    }

    @Test
    void portTwoHop_viaMiddle() {
        // a->b 水路、b->c 水路,a 与 c 无直达 → 经 b 中转可达。
        TerminalNetworkSavedData net = net(
                port("town-a", "Alpha", List.of("Beta")),
                port("town-b", "Beta", List.of("Gamma")),
                port("town-c", "Gamma", List.of()));
        assertTrue(TerminalVisibility.isVisible(net, "town-a", "town-c"));
        assertTrue(TerminalVisibility.isVisible(net, "town-c", "town-a"));
    }

    @Test
    void landDirect_bidirectional() {
        TerminalNetworkSavedData net = net(
                station("town-a", Set.of("town-b")));
        assertTrue(TerminalVisibility.isVisible(net, "town-a", "town-b"));
        assertTrue(TerminalVisibility.isVisible(net, "town-b", "town-a"));
    }

    @Test
    void landTwoHop_viaMiddle() {
        // a.reachable={b}、b.reachable={c} → a 经 b 陆路中转到 c。
        TerminalNetworkSavedData net = net(
                station("town-a", Set.of("town-b")),
                station("town-b", Set.of("town-c")));
        assertTrue(TerminalVisibility.isVisible(net, "town-a", "town-c"));
    }

    @Test
    void waterLandMixed_viaMiddle() {
        // 核心新需求:a->b 水路(港口),b->c 陆路(驿站 reachable),混在一张图 → a 可达 c。
        TerminalNetworkSavedData net = net(
                port("town-a", "Alpha", List.of("Beta")),
                port("town-b", "Beta", List.of()),
                station("town-b", Set.of("town-c")));
        assertTrue(TerminalVisibility.isVisible(net, "town-a", "town-c"));
        assertTrue(TerminalVisibility.isVisible(net, "town-c", "town-a"));
    }

    @Test
    void notConnected_isolatedTown() {
        TerminalNetworkSavedData net = net(
                port("town-a", "Alpha", List.of("Beta")),
                port("town-b", "Beta", List.of()));
        assertFalse(TerminalVisibility.isVisible(net, "town-a", "town-z"));
        // 孤立 town 与自己仍可见(同城镇短路)。
        assertTrue(TerminalVisibility.isVisible(net, "town-z", "town-z"));
    }

    @Test
    void caseInsensitiveDockMatch() {
        // 出航终点 "BETA" 应匹配港口名 "beta"。
        TerminalNetworkSavedData net = net(
                port("town-a", "Alpha", List.of("BETA")),
                port("town-b", "beta", List.of()));
        assertTrue(TerminalVisibility.isVisible(net, "town-a", "town-b"));
    }

    @Test
    void selfLoop_notFalsePositive() {
        // 仅 town-a 港口自指自己的 dock + 无其它边 → 不连通到无关 town-x。
        TerminalNetworkSavedData net = net(
                port("town-a", "Alpha", List.of("Alpha")));
        assertFalse(TerminalVisibility.isVisible(net, "town-a", "town-x"));
    }

    @Test
    void emptyGraph_notVisible() {
        TerminalNetworkSavedData net = net();
        assertFalse(TerminalVisibility.isVisible(net, "town-a", "town-b"));
    }
}
