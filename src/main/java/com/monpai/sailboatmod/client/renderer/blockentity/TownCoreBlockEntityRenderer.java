package com.monpai.sailboatmod.client.renderer.blockentity;

import com.monpai.sailboatmod.block.entity.TownCoreBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

public class TownCoreBlockEntityRenderer implements BlockEntityRenderer<TownCoreBlockEntity> {
    private static final int HEADER_COLOR = 0xFFF3E7C7;
    private static final int SUBTITLE_COLOR = 0xFFC7D5E0;
    private final Font font;

    public TownCoreBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public void render(TownCoreBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (blockEntity == null) {
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

        drawCenteredLine(poseStack, bufferSource, blockEntity.getDisplayName(), 0, HEADER_COLOR, packedLight);

        String townName = blockEntity.getTownName();
        if (!townName.isBlank() && !"-".equals(townName)) {
            drawCenteredLine(poseStack, bufferSource, Component.literal(townName), 10, blockEntity.getPrimaryColor(), packedLight);
        }

        String nationName = blockEntity.getNationName();
        if (!nationName.isBlank() && !"-".equals(nationName)) {
            drawCenteredLine(poseStack, bufferSource, Component.literal(nationName), 20, SUBTITLE_COLOR, packedLight);
        }
        poseStack.popPose();
    }

    private void drawCenteredLine(PoseStack poseStack, MultiBufferSource bufferSource, Component text, int y, int color, int packedLight) {
        String line = text == null ? "" : text.getString();
        if (line.isBlank()) {
            return;
        }
        float x = -this.font.width(line) / 2.0F;
        Matrix4f matrix = poseStack.last().pose();
        this.font.drawInBatch(line, x, y, color, false, matrix, bufferSource, Font.DisplayMode.SEE_THROUGH, 0x66000000, packedLight);
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
