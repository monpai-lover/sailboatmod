package com.monpai.sailboatmod.client.renderer.blockentity;

import com.monpai.sailboatmod.block.entity.NationCoreBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;

public class NationCoreBlockEntityRenderer implements BlockEntityRenderer<NationCoreBlockEntity> {
    private final Font font;

    public NationCoreBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public void render(NationCoreBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (blockEntity == null || blockEntity.getNationId().isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null || minecraft.player == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, blockEntity.isActiveWar() ? 1.55D : 1.35D, 0.5D);
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        drawCenteredLine(poseStack, bufferSource, Component.literal(blockEntity.getNationName()), 0, blockEntity.getPrimaryColor(), packedLight);
        if (blockEntity.isActiveWar()) {
            Component status = Component.translatable("command.sailboatmod.nation.war.status." + blockEntity.getWarCaptureState());
            drawCenteredLine(poseStack, bufferSource, Component.translatable("screen.sailboatmod.nation.section.war").append(": ").append(status), 10, 0xE25A4F, packedLight);
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
    public boolean shouldRenderOffScreen(NationCoreBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
