package com.monpai.sailboatmod.market.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * debugroute 调试节点的<b>内存旁路缓存</b>:按 trace id 关联一条调试航线的逐航点真实方块快照。
 *
 * <p>为什么旁路:{@link com.monpai.sailboatmod.market.logistics.ShippingTraceRecord} 是持久化 record,
 * 不便挂临时 debug 字段;真实方块 id/是否水只是 {@code /marketweb debugroute} 命令当下的一次性采样,
 * 不进 NBT、重投即刷新。航点的出身标记(段/原始 vs 插值)走 {@code RouteDefinition.waypointMetas} 持久化,
 * 这里只补「命令时采到的真实方块」这一层瞬时信息,两者在命令里合成成 {@link Node}。
 *
 * <p>{@link com.monpai.sailboatmod.market.logistics.ShippingTraceService#toDto} 透传 DTO 时按 trace id
 * 取出本缓存,index 对齐地富化 {@code points[]}。命令 {@code debugroute clear} 同步清空。
 */
public final class DebugRouteNodeCache {

    /** 一个调试航点:坐标 + 命令时采到的真实方块 + 出身标记(来自 RouteDefinition.waypointMetas,缺省 -1)。 */
    public record Node(double x, double z, String blockId, boolean water, byte origin, byte segment) {
    }

    /** traceId -> 该 trace 逐航点的调试节点(与 trace.waypoints() 等长同序)。 */
    private static final Map<String, List<Node>> CACHE = new ConcurrentHashMap<>();

    private DebugRouteNodeCache() {
    }

    /** 覆盖写入一条调试航线的节点(命令重投即覆盖)。 */
    public static void put(String traceId, List<Node> nodes) {
        if (traceId == null || nodes == null) {
            return;
        }
        CACHE.put(traceId, List.copyOf(nodes));
    }

    /** 取一条调试航线的节点;无则 null(老航线/非 debug trace → DTO 降级,不富化)。 */
    public static List<Node> get(String traceId) {
        return traceId == null ? null : CACHE.get(traceId);
    }

    /** 移除一条。 */
    public static void remove(String traceId) {
        if (traceId != null) {
            CACHE.remove(traceId);
        }
    }

    /** 清空全部(debugroute clear 用)。 */
    public static void clear() {
        CACHE.clear();
    }
}
