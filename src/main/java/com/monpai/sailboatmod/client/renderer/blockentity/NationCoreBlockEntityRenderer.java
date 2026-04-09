package com.monpai.sailboatmod.client.renderer.blockentity;

import com.monpai.sailboatmod.block.entity.NationCoreBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;

import java.util.List;

public class NationCoreBlockEntityRenderer implements BlockEntityRenderer<NationCoreBlockEntity> {
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

        String title = blockEntity.getDisplayName() == null ? "" : blockEntity.getDisplayName().getString();
        String nationName = blockEntity.getNationName();
        String warStatus = null;
        boolean activeWar = blockEntity.isActiveWar();
        if (activeWar) {
            Component status = Component.translatable("command.sailboatmod.nation.war.status." + blockEntity.getWarCaptureState());
            warStatus = Component.translatable("screen.sailboatmod.nation.section.war").append(": ").append(status).getString();
        }

        List<CoreHologramLayout.HologramLine> lines = CoreHologramLayout.nationLines(
                title,
                nationName,
                blockEntity.getPrimaryColor(),
                activeWar,
                warStatus
        );
        CoreHologramRendererHelper.render(font, poseStack, bufferSource, packedLight, lines);
        poseStack.popPose();
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
