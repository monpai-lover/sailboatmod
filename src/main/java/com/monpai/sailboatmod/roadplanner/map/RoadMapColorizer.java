package com.monpai.sailboatmod.roadplanner.map;

/**
 * 把采样列转成地图像素颜色。取色已在采样器用 {@link MapBlockColors} 完成（{@code baseArgb} 即最终地形色），
 * 本类只负责 Pl3xMap 式的明暗遮罩叠加（{@link MapReliefShader}）：陆地按相对高差压暗/提亮，
 * 水按深度额外压暗。不再用 RGB×系数缩放，避免发灰。
 */
public class RoadMapColorizer {
    public int color(RoadMapColumnSample sample) {
        if (RoadMapServerColumnSampler.isUnavailableSample(sample)) {
            return 0x00000000;
        }
        if (sample.water()) {
            return MapReliefShader.shadeByDelta(sample.baseArgb(), 0, waterDarkness(sample.waterDepth()));
        }
        int delta = sample.surfaceY() - sample.reliefBaseY();
        return MapReliefShader.shadeByDelta(sample.baseArgb(), delta, 0);
    }

    /** 水深越深，额外叠加的黑色遮罩 alpha 越大（深水更暗）。 */
    private int waterDarkness(int waterDepth) {
        if (waterDepth > 6) {
            return 0x60;
        }
        if (waterDepth > 3) {
            return 0x40;
        }
        return 0x18;
    }
}
