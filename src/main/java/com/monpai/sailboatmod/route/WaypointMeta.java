package com.monpai.sailboatmod.route;

/**
 * 航点出身标记——只给水路自动航线填,供 debug 工具(/marketweb debugroute)分清「陆地上的航点」
 * 到底是寻路真寻到陆地({@link #ORIGIN_RAW}),还是样条平滑过冲甩进陆地({@link #ORIGIN_INTERP})。
 *
 * <p>与 {@link RouteDefinition#waypoints()} 等长并行;其它航线(陆路/手动/路书)metas 为空,不受影响。
 * 持久化到 NBT(每点存 Seg/Origin 两个 byte),跟着航线走。真实方块信息不在此处——那是 debug
 * 命令时一次性 force 加载采样的快照,走 trace 内存 transient,不持久化。
 *
 * @param segment 段归属:{@link #SEGMENT_START}起始 / {@link #SEGMENT_MID}中段 / {@link #SEGMENT_END}尾段
 * @param origin  来源:{@link #ORIGIN_RAW}寻路原始节点 / {@link #ORIGIN_INTERP}样条插值点
 */
public record WaypointMeta(byte segment, byte origin) {
    public static final byte SEGMENT_START = 0;
    public static final byte SEGMENT_MID = 1;
    public static final byte SEGMENT_END = 2;

    public static final byte ORIGIN_RAW = 0;    // 寻路 simplify 后保留的真实拐点
    public static final byte ORIGIN_INTERP = 1; // Catmull-Rom 样条采样 / 弧长重采样新生的点

    public static WaypointMeta of(int segment, int origin) {
        return new WaypointMeta((byte) segment, (byte) origin);
    }
}
