package com.monpai.sailboatmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NationCoreRelocationPreviewRenderer {
    private NationCoreRelocationPreviewRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        ItemStack held = findRelocatingCore(player);
        if (held.isEmpty()) {
            return;
        }

        BlockHitResult hit = player.level().clip(new ClipContext(
                player.getEyePosition(event.getPartialTick()),
                player.getEyePosition(event.getPartialTick()).add(player.getViewVector(event.getPartialTick()).scale(6.0D)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos previewPos = hit.getBlockPos().above();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(previewPos.getX() - camera.x, previewPos.getY() - camera.y, previewPos.getZ() - camera.z);
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        Vector3f normal = new Vector3f(0, 1, 0);
        renderBox(consumer, matrix, normal, 0.15F, 0.9F, 1.0F, 0.95F);
        renderFloatingLabel(poseStack, bufferSource, minecraft, held, 0xFFEAF6FF);
        bufferSource.endBatch();
        poseStack.popPose();
    }

    private static ItemStack findRelocatingCore(LocalPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        if (isRelocatingCore(mainHand)) {
            return mainHand;
        }
        ItemStack offHand = player.getOffhandItem();
        return isRelocatingCore(offHand) ? offHand : ItemStack.EMPTY;
    }

    private static boolean isRelocatingCore(ItemStack stack) {
        return !stack.isEmpty()
                && ((stack.is(ModItems.NATION_CORE_ITEM.get()) && stack.hasTag() && stack.getTag().contains("RelocatingNationId"))
                || (stack.is(ModItems.TOWN_CORE_ITEM.get()) && stack.hasTag() && stack.getTag().contains("RelocatingTownId")));
    }

    private static void renderFloatingLabel(PoseStack poseStack,
                                            MultiBufferSource bufferSource,
                                            Minecraft minecraft,
                                            ItemStack stack,
                                            int color) {
        String label = labelText(stack);
        if (label.isBlank()) {
            return;
        }

        Font font = minecraft.font;
        poseStack.pushPose();
        poseStack.translate(0.5D, 1.45D, 0.5D);
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);
        float x = -font.width(label) / 2.0F;
        font.drawInBatch(label, x, 0, color, false, poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0x66000000, 0xF000F0);
        poseStack.popPose();
    }

    private static String labelText(ItemStack stack) {
        if (stack.is(ModItems.NATION_CORE_ITEM.get())) {
            String name = stack.hasTag() ? stack.getTag().getString("RelocatingNationName") : "";
            String coreName = Component.translatable("item.sailboatmod.nation_core").getString();
            return name.isBlank() ? coreName : coreName + ": " + name;
        }
        if (stack.is(ModItems.TOWN_CORE_ITEM.get())) {
            String name = stack.hasTag() ? stack.getTag().getString("RelocatingTownName") : "";
            String coreName = Component.translatable("item.sailboatmod.town_core").getString();
            return name.isBlank() ? coreName : coreName + ": " + name;
        }
        return "";
    }

    private static void renderBox(VertexConsumer consumer, Matrix4f matrix, Vector3f normal, float r, float g, float b, float a) {
        line(consumer, matrix, normal, 0, 0, 0, 1, 0, 0, r, g, b, a);
        line(consumer, matrix, normal, 1, 0, 0, 1, 0, 1, r, g, b, a);
        line(consumer, matrix, normal, 1, 0, 1, 0, 0, 1, r, g, b, a);
        line(consumer, matrix, normal, 0, 0, 1, 0, 0, 0, r, g, b, a);
        line(consumer, matrix, normal, 0, 1, 0, 1, 1, 0, r, g, b, a);
        line(consumer, matrix, normal, 1, 1, 0, 1, 1, 1, r, g, b, a);
        line(consumer, matrix, normal, 1, 1, 1, 0, 1, 1, r, g, b, a);
        line(consumer, matrix, normal, 0, 1, 1, 0, 1, 0, r, g, b, a);
        line(consumer, matrix, normal, 0, 0, 0, 0, 1, 0, r, g, b, a);
        line(consumer, matrix, normal, 1, 0, 0, 1, 1, 0, r, g, b, a);
        line(consumer, matrix, normal, 1, 0, 1, 1, 1, 1, r, g, b, a);
        line(consumer, matrix, normal, 0, 0, 1, 0, 1, 1, r, g, b, a);
    }

    private static void line(VertexConsumer consumer, Matrix4f matrix, Vector3f normal,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        consumer.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z()).endVertex();
        consumer.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z()).endVertex();
    }
}
