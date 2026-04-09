package com.monpai.sailboatmod.client.renderer.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

import java.util.List;

public final class CoreHologramRendererHelper {
    static final int LINE_HEIGHT = 12;
    static final int BACKGROUND = 0x66000000;

    private CoreHologramRendererHelper() {
    }

    static void render(Font font, PoseStack poseStack, MultiBufferSource buffer, int light, List<CoreHologramLayout.HologramLine> lines) {
        Matrix4f matrix = poseStack.last().pose();
        for (int i = 0; i < lines.size(); i++) {
            CoreHologramLayout.HologramLine line = lines.get(i);
            float x = -font.width(line.text()) / 2.0F;
            font.drawInBatch(line.text(), x, i * LINE_HEIGHT, line.color(), false, matrix, buffer, Font.DisplayMode.SEE_THROUGH, BACKGROUND, light);
        }
    }
}
