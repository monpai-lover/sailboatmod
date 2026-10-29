package com.monpai.sailboatmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;

/**
 * 浮字(全息名牌)面向镜头的变换,与原版 1.21.1 EntityRenderer#renderNameTag 相同:先套 cameraOrientation(),
 * 再缩放 (s, -s, s)。1.21 起 cameraOrientation() 与视图旋转互为共轭,旧版的 (-s, -s, s) 会把文字镜像到背面、
 * 被背面剔除而看不见(核心、码头、市场、银行等浮字全部消失)。所有浮字都应走这里。
 */
public final class HologramBillboard {
    private HologramBillboard() {
    }

    public static void faceCamera(PoseStack poseStack, Quaternionf cameraOrientation, float scale) {
        poseStack.mulPose(cameraOrientation);
        poseStack.scale(scale, -scale, scale);
    }
}
