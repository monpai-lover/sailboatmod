package com.monpai.sailboatmod.client.renderer.blockentity;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;

public class DockBlockEntityRenderer implements BlockEntityRenderer<DockBlockEntity> {
    private final Font font;
    public DockBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) { this.font = ctx.getFont(); }

    @Override
    public void render(DockBlockEntity be, float pt, PoseStack ps, MultiBufferSource buf, int light, int overlay) {
        if (be == null) return;
        String name = be.getDockName();
        if (name == null || name.isBlank()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        ps.pushPose();
        ps.translate(0.5D, 1.35D, 0.5D);
        ps.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        ps.scale(-0.025F, -0.025F, 0.025F);
        String line = "[" + name + "]" + facilityLabel();
        float x = -this.font.width(line) / 2.0F;
        Matrix4f m = ps.last().pose();
        this.font.drawInBatch(line, x, 0, facilityColor(), false, m, buf, Font.DisplayMode.SEE_THROUGH, 0x66000000, light);
        ps.popPose();
    }

    protected String facilityLabel() {
        return Component.translatable("block.sailboatmod.dock").getString();
    }

    protected int facilityColor() {
        return 0x88CCAA;
    }

    @Override public boolean shouldRenderOffScreen(DockBlockEntity be) { return true; }
    @Override public int getViewDistance() { return 128; }
}
