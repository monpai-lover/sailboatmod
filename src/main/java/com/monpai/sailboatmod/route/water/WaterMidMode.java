package com.monpai.sailboatmod.route.water;

/**
 * 中段寻路「地形真相源」运行时开关——供 {@code /sailboat watermid noise|realchunk} 指令切换,方便实测对比。
 *
 * <ul>
 *   <li>{@link #NOISE}(默认):走廊精寻用 NoiseChunk 精判(零区块加载、精确到方块、看世界生成原始地形)。
 *       几秒出路、不卡服。能修穿陆,但看不到玩家挖的运河/填海。</li>
 *   <li>{@link #REALCHUNK}:沿走廊分批加载<b>真实区块</b>精寻(玩家挖的运河/填海算数)。慢(几十秒~几分钟、
 *       带进度条),但全程真实地形。</li>
 * </ul>
 *
 * <p>静态全局开关:航线创建低频(玩家手动点),无需 per-world;切了下次创建即生效,不用重启。
 */
public enum WaterMidMode {
    NOISE,
    REALCHUNK;

    private static volatile WaterMidMode current = NOISE; // 默认 NoiseChunk 纯精判

    public static WaterMidMode current() {
        return current;
    }

    public static void set(WaterMidMode mode) {
        current = mode == null ? NOISE : mode;
    }

    /** 解析指令参数(noise/realchunk,大小写不敏感);非法返回 null。 */
    public static WaterMidMode parse(String s) {
        if (s == null) {
            return null;
        }
        switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "noise":
                return NOISE;
            case "realchunk":
            case "real":
                return REALCHUNK;
            default:
                return null;
        }
    }

    public String label() {
        return this == NOISE ? "NoiseChunk 纯精判(零加载·快)" : "真实区块分批加载(运河算数·慢·带进度条)";
    }
}
