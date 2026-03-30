package com.monpai.sailboatmod.client.renderer.blockentity;

import com.monpai.sailboatmod.block.entity.TownCoreBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public class TownCoreBlockEntityRenderer implements BlockEntityRenderer<TownCoreBlockEntity> {
    private final Font font;

    public TownCoreBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public void render(TownCoreBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (blockEntity == null || blockEntity.getTownId().isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null || minecraft.player == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, 1.35D, 0.5D);
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        String line = "[\u57ce\u9547] " + blockEntity.getTownName();
        if (!line.isBlank() && !"-".equals(line)) {
            float x = -this.font.width(line) / 2.0F;
            Matrix4f matrix = poseStack.last().pose();
            this.font.drawInBatch(line, x, 0, blockEntity.getPrimaryColor(), false, matrix, bufferSource, Font.DisplayMode.SEE_THROUGH, 0x66000000, packedLight);
        }
        poseStack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(TownCoreBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}