package com.monpai.sailboatmod.client.renderer.blockentity;

import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

public class TownWarehouseBlockEntityRenderer implements BlockEntityRenderer<TownWarehouseBlockEntity> {
    private final Font font;

    public TownWarehouseBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public void render(TownWarehouseBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (blockEntity == null) {
            return;
        }
        String townName = blockEntity.getTownName();
        if (townName == null || townName.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5D, 1.35D, 0.5D);
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);
        String line = "[" + townName + "]" + Component.translatable("block.sailboatmod.town_warehouse").getString();
        float x = -this.font.width(line) / 2.0F;
        Matrix4f matrix = poseStack.last().pose();
        this.font.drawInBatch(line, x, 0, 0xD0C39A, false, matrix, bufferSource, Font.DisplayMode.SEE_THROUGH, 0x66000000, packedLight);
        poseStack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(TownWarehouseBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
