package com.monpai.sailboatmod.client.renderer.blockentity;

import com.monpai.sailboatmod.block.entity.NationCoreBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

public class NationCoreBlockEntityRenderer implements BlockEntityRenderer<NationCoreBlockEntity> {
    private static final int HEADER_COLOR = 0xFFF3E7C7;
    private final Font font;

    public NationCoreBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public void render(NationCoreBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (blockEntity == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null || minecraft.player == null) {
            return;
        }

        double distSq = blockEntity.getBlockPos().distToCenterSqr(
                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
        if (distSq > 16 * 16) return;

        poseStack.pushPose();
        poseStack.translate(0.5D, blockEntity.isActiveWar() ? 1.55D : 1.35D, 0.5D);
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        drawCenteredLine(poseStack, bufferSource, blockEntity.getDisplayName(), 0, HEADER_COLOR, packedLight);

        String nationName = blockEntity.getNationName();
        if (!nationName.isBlank() && !"-".equals(nationName)) {
            drawCenteredLine(poseStack, bufferSource, Component.literal(nationName), 10, blockEntity.getPrimaryColor(), packedLight);
        }

        if (blockEntity.isActiveWar()) {
            Component status = Component.translatable("command.sailboatmod.nation.war.status." + blockEntity.getWarCaptureState());
            drawCenteredLine(poseStack, bufferSource, Component.translatable("screen.sailboatmod.nation.section.war").append(": ").append(status), 20, 0xE25A4F, packedLight);
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
        int readableColor = ensureReadableColor(color);
        drawOutline(line, x, y, matrix, bufferSource, packedLight);
        this.font.drawInBatch(line, x, y, readableColor, false, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, packedLight);
    }

    private void drawOutline(String line, float x, int y, Matrix4f matrix, MultiBufferSource bufferSource, int packedLight) {
        this.font.drawInBatch(line, x - 1.0F, y, 0xCC101010, false, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, packedLight);
        this.font.drawInBatch(line, x + 1.0F, y, 0xCC101010, false, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, packedLight);
        this.font.drawInBatch(line, x, y - 1.0F, 0xCC101010, false, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, packedLight);
        this.font.drawInBatch(line, x, y + 1.0F, 0xCC101010, false, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, packedLight);
    }

    private static int ensureReadableColor(int color) {
        int rgb = color & 0x00FFFFFF;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        double luminance = (0.299D * r) + (0.587D * g) + (0.114D * b);
        if (luminance < 78.0D) {
            r = Math.min(255, r + 68);
            g = Math.min(255, g + 68);
            b = Math.min(255, b + 68);
        } else if (luminance > 214.0D) {
            r = Math.max(0, r - 32);
            g = Math.max(0, g - 32);
            b = Math.max(0, b - 32);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    public boolean shouldRenderOffScreen(NationCoreBlockEntity blockEntity) {
        return false;
    }

    @Override
    public int getViewDistance() {
        return 16;
    }
}
