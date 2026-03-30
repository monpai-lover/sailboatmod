package com.monpai.sailboatmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BankConstructorPreviewRenderer {
    private BankConstructorPreviewRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        boolean holding = player.getMainHandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get())
                || player.getOffhandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get());
        if (!holding) return;

        BlockHitResult hit = player.level().clip(new ClipContext(
                player.getEyePosition(event.getPartialTick()),
                player.getEyePosition(event.getPartialTick()).add(player.getViewVector(event.getPartialTick()).scale(5.0D)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return;

        Vec3 cam = event.getCamera().getPosition();
        double ox = hit.getBlockPos().getX() + 0.0D - cam.x;
        double oy = hit.getBlockPos().getY() + 1.0D - cam.y;
        double oz = hit.getBlockPos().getZ() + 0.0D - cam.z;
        double w = BankConstructorItem.STRUCTURE_W;
        double h = BankConstructorItem.STRUCTURE_H;
        double d = BankConstructorItem.STRUCTURE_D;

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(ox, oy, oz);

        VertexConsumer vc = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        org.joml.Vector3f normal = new org.joml.Vector3f(0, 1, 0);
        float r = 0.0F, g = 1.0F, b = 0.0F, a = 0.8F;

        // Bottom edges
        line(vc, matrix, normal, 0, 0, 0, (float)w, 0, 0, r, g, b, a);
        line(vc, matrix, normal, (float)w, 0, 0, (float)w, 0, (float)d, r, g, b, a);
        line(vc, matrix, normal, (float)w, 0, (float)d, 0, 0, (float)d, r, g, b, a);
        line(vc, matrix, normal, 0, 0, (float)d, 0, 0, 0, r, g, b, a);
        // Top edges
        line(vc, matrix, normal, 0, (float)h, 0, (float)w, (float)h, 0, r, g, b, a);
        line(vc, matrix, normal, (float)w, (float)h, 0, (float)w, (float)h, (float)d, r, g, b, a);
        line(vc, matrix, normal, (float)w, (float)h, (float)d, 0, (float)h, (float)d, r, g, b, a);
        line(vc, matrix, normal, 0, (float)h, (float)d, 0, (float)h, 0, r, g, b, a);
        // Vertical edges
        line(vc, matrix, normal, 0, 0, 0, 0, (float)h, 0, r, g, b, a);
        line(vc, matrix, normal, (float)w, 0, 0, (float)w, (float)h, 0, r, g, b, a);
        line(vc, matrix, normal, (float)w, 0, (float)d, (float)w, (float)h, (float)d, r, g, b, a);
        line(vc, matrix, normal, 0, 0, (float)d, 0, (float)h, (float)d, r, g, b, a);

        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static void line(VertexConsumer vc, Matrix4f matrix, org.joml.Vector3f normal,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        vc.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z()).endVertex();
        vc.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z()).endVertex();
    }
}
