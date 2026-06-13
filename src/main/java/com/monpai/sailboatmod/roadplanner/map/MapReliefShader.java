package com.monpai.sailboatmod.roadplanner.map;

/**
 * Pl3xMap 式地形明暗遮罩：用一层「黑色 + 可变 alpha」的半透明遮罩叠加来表现高低差，
 * 而不是把 RGB 三通道乘系数（那样会让颜色发灰发脏）。
 *
 * <p>算法照搬 Pl3xMap {@code ModernHeightmap} + {@code Heightmap.getColor}：
 * 比较当前格与西、北两邻格高度，每高/低一格使遮罩 alpha ±{@link #STEP}，
 * clamp 到 [{@link #MIN}, {@link #MAX}]，产出纯黑 + 该 alpha 的遮罩；
 * 再用 alpha 合成（照搬 {@code Colors.blend}）叠到地形色上。
 */
public final class MapReliefShader {
    /** 遮罩 alpha 下限（最亮，无遮罩）。 */
    public static final int MIN = 0x00;
    /** 遮罩 alpha 上限（最暗，约 27% 黑，平地有层次但不过暗）。 */
    public static final int MAX = 0x44;
    /** 每格高度差的遮罩步进。 */
    public static final int STEP = 0x22;
    /** 平地基准遮罩 alpha（无明显高差时的中间调）。 */
    public static final int BASE = 0x22;

    private MapReliefShader() {
    }

    /**
     * 计算当前格的明暗遮罩（纯黑 + alpha）。
     *
     * @param originY 当前格地表高度
     * @param westY   西邻格地表高度
     * @param northY  北邻格地表高度
     * @return ARGB，RGB 恒为 0（黑），alpha 在 [MIN, MAX]
     */
    public static int reliefMask(int originY, int westY, int northY) {
        int alpha = BASE;
        alpha = step(originY, westY, alpha);
        alpha = step(originY, northY, alpha);
        return clamp(alpha) << 24;
    }

    /**
     * Alpha 合成：把遮罩 {@code mask}（src）叠到地形色 {@code base}（dst，视为不透明）上。
     * 照搬 Pl3xMap {@code Colors.blend} 的 over 运算；base 不透明时结果即「base 被 mask 按其 alpha 压暗」。
     *
     * @param mask 遮罩 ARGB（黑色 + alpha）
     * @param base 地形 ARGB（应为不透明）
     * @return 叠加后的不透明 ARGB
     */
    public static int blend(int mask, int base) {
        double a0 = ((mask >>> 24) & 0xFF) / 255.0D;
        double a1 = ((base >>> 24) & 0xFF) / 255.0D;
        double a = a0 + a1 * (1 - a0);
        if (a <= 0.0D) {
            return base;
        }
        int r = (int) Math.round((((mask >> 16) & 0xFF) * a0 + ((base >> 16) & 0xFF) * a1 * (1 - a0)) / a);
        int g = (int) Math.round((((mask >> 8) & 0xFF) * a0 + ((base >> 8) & 0xFF) * a1 * (1 - a0)) / a);
        int b = (int) Math.round(((mask & 0xFF) * a0 + (base & 0xFF) * a1 * (1 - a0)) / a);
        int outAlpha = (int) Math.round(a * 255.0D);
        return (clampByte(outAlpha) << 24) | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    /** 便捷方法：直接给地形色叠加由高差算出的明暗遮罩。 */
    public static int shade(int baseArgb, int originY, int westY, int northY) {
        return blend(reliefMask(originY, westY, northY), 0xFF000000 | (baseArgb & 0x00FFFFFF));
    }

    /**
     * 按「当前格相对邻格基准的高度差」直接算遮罩，用于只有相对高度可用的场景
     * （如采样器只提供 surfaceY - reliefBaseY）。delta&gt;0 更亮（高地），delta&lt;0 更暗（凹地）。
     *
     * @param delta surfaceY - 邻格基准高度
     * @return ARGB，RGB=0，alpha 在 [MIN, MAX]
     */
    public static int reliefMaskByDelta(int delta) {
        int alpha = BASE - clampSteps(delta) * STEP;
        return clamp(alpha) << 24;
    }

    /** 把高度差限制在 ±2 档（与 STEP×档数后仍落在 [MIN,MAX] 区间相称）。 */
    private static int clampSteps(int delta) {
        if (delta > 0) {
            return Math.min(2, delta);
        }
        if (delta < 0) {
            return Math.max(-2, delta);
        }
        return 0;
    }

    /**
     * 给地形色叠加「按相对高度差」的明暗遮罩，并可额外叠加一层固定压暗（用于水深）。
     *
     * @param baseArgb   地形色
     * @param delta      高度差（surfaceY - 邻格基准）
     * @param extraDark  额外压暗 alpha（0~255，水深越深越大；陆地传 0）
     */
    public static int shadeByDelta(int baseArgb, int delta, int extraDark) {
        int opaque = 0xFF000000 | (baseArgb & 0x00FFFFFF);
        int shaded = blend(reliefMaskByDelta(delta), opaque);
        if (extraDark <= 0) {
            return shaded;
        }
        return blend((clampByte(extraDark) << 24), shaded);
    }

    private static int step(int originY, int neighborY, int alpha) {
        if (originY > neighborY) {
            return alpha - STEP;
        }
        if (originY < neighborY) {
            return alpha + STEP;
        }
        return alpha;
    }

    private static int clamp(int alpha) {
        return Math.max(MIN, Math.min(MAX, alpha));
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
