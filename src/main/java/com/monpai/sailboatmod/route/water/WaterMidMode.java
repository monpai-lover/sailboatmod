package com.monpai.sailboatmod.route.water;

/**
 * 中段寻路模式——创建航线时 per-航线选择(UI 三选项卡),也保留 {@code /sailboat watermid} 指令切默认值。
 *
 * <ul>
 *   <li>{@link #NOISE}:噪声粗寻 + NoiseChunk 精判走廊。零加载、几秒、不卡服;但判障粗(漏小岛/窄道),
 *       且 WorldPainter populate 地图下噪声地形 ≠ 真实方块(会穿陆/切岛)。适合原版世界或快速预览。</li>
 *   <li>{@link #NBT}:全程读<b>真实方块</b>(NBT 后台直读),沿 A→B 宽走廊渐进放宽真实寻路。最准(真实海岸/
 *       小岛/运河算数),慢(加载真实区块、带进度条)。WorldPainter 地图首选。</li>
 *   <li>{@link #HYBRID}(推荐日常):噪声粗寻拿「绕开大陆的走向」当导向 → 沿噪声路<b>真实方块精寻</b>。
 *       兼顾走向(噪声给)与细节(真实判定绕开噪声偏差/穿陆)。比 NBT 快(走廊窄),比 NOISE 准。</li>
 * </ul>
 *
 * <p>静态全局开关只当「指令设的默认值」;实际每条航线用 packet 传入的模式(见 CreateAutoRoutePacket)。
 */
public enum WaterMidMode {
    NOISE,
    NBT,
    HYBRID;

    // 默认 NBT(真实方块单段 A*,WorldPainter/已存盘地图首选);NOISE/HYBRID 保留给原版/生成式地图。
    private static volatile WaterMidMode defaultMode = NBT;

    /** 指令设的全局默认(玩家没在 UI 选时用)。 */
    public static WaterMidMode current() {
        return defaultMode;
    }

    public static void set(WaterMidMode mode) {
        defaultMode = mode == null ? NBT : mode;
    }

    /** packet 传 byte ↔ 枚举。 */
    public static WaterMidMode fromId(int id) {
        WaterMidMode[] v = values();
        return id >= 0 && id < v.length ? v[id] : NBT;
    }

    public int id() {
        return ordinal();
    }

    /** 解析指令/UI 参数(大小写不敏感);非法返回 null。 */
    public static WaterMidMode parse(String s) {
        if (s == null) {
            return null;
        }
        switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "noise":
                return NOISE;
            case "nbt":
            case "realchunk":
            case "real":
                return NBT;
            case "hybrid":
            case "mix":
                return HYBRID;
            default:
                return null;
        }
    }

    public String label() {
        switch (this) {
            case NOISE:
                return "噪声(快·粗,漏小岛)";
            case NBT:
                return "真实(最准·慢,运河算数)";
            default:
                return "混合(噪声导向+真实精寻,推荐)";
        }
    }
}
