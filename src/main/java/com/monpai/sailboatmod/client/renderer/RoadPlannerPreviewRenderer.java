package com.monpai.sailboatmod.client.renderer;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RoadPlannerPreviewRenderer {
    private RoadPlannerPreviewRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        RoadPlannerClientHooks.PreviewState preview = RoadPlannerClientHooks.previewState();
        if (player == null || preview == null || preview.path().size() < 2 || !isHoldingPlanner(player)) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        Vector3f normal = new Vector3f(0.0F, 1.0F, 0.0F);
        BlockPos renderOrigin = BlockPos.containing(event.getCamera().getPosition());
        float alpha = preview.awaitingConfirmation() ? 0.9F : 0.5F;
        for (int i = 1; i < preview.path().size(); i++) {
            BlockPos from = preview.path().get(i - 1).subtract(renderOrigin);
            BlockPos to = preview.path().get(i).subtract(renderOrigin);
            line(vertexConsumer, matrix, normal,
                    from.getX() + 0.5F, from.getY() + 1.08F, from.getZ() + 0.5F,
                    to.getX() + 0.5F, to.getY() + 1.08F, to.getZ() + 0.5F,
                    0.92F, 0.76F, 0.24F, alpha);
        }
        renderMarker(vertexConsumer, matrix, normal, preview.path().get(0).subtract(renderOrigin), 0.45F, 0.85F, 1.0F, 0.95F);
        renderMarker(vertexConsumer, matrix, normal, preview.path().get(preview.path().size() - 1).subtract(renderOrigin), 1.0F, 0.72F, 0.25F, 0.95F);
        bufferSource.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || !isHoldingPlanner(player)) {
            return;
        }
        RoadPlannerClientHooks.PreviewState preview = RoadPlannerClientHooks.previewState();
        List<RoadPlannerClientHooks.ProgressState> progressStates = RoadPlannerClientHooks.activeProgress();
        if (preview == null && progressStates.isEmpty()) {
            return;
        }
        GuiGraphics guiGraphics = event.getGuiGraphics();
        int x = 12;
        int y = 16;
        int width = 212;
        int height = preview != null ? 74 : 42;
        if (!progressStates.isEmpty()) {
            height += 22;
        }
        guiGraphics.fill(x, y, x + width, y + height, 0xC9121820);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xEE1D2731);
        guiGraphics.drawString(minecraft.font,
                net.minecraft.network.chat.Component.translatable("item.sailboatmod.road_planner"),
                x + 10, y + 8, 0xFFF1D9A0, false);
        int textY = y + 22;
        if (preview != null) {
            guiGraphics.drawString(minecraft.font,
                    net.minecraft.network.chat.Component.translatable("screen.sailboatmod.road_planner.overlay.route",
                            preview.sourceTownName(), preview.targetTownName()),
                    x + 10, textY, 0xFFE6EEF5, false);
            textY += 12;
            guiGraphics.drawString(minecraft.font,
                    net.minecraft.network.chat.Component.translatable("screen.sailboatmod.road_planner.overlay.length", preview.path().size()),
                    x + 10, textY, 0xFFB7C8D6, false);
            textY += 12;
            guiGraphics.drawString(minecraft.font,
                    net.minecraft.network.chat.Component.translatable(preview.awaitingConfirmation()
                            ? "screen.sailboatmod.road_planner.overlay.confirm"
                            : "screen.sailboatmod.road_planner.overlay.preview"),
                    x + 10, textY, preview.awaitingConfirmation() ? 0xFFF1C96C : 0xFF8FA9BA, false);
            textY += 18;
        }
        if (!progressStates.isEmpty()) {
            RoadPlannerClientHooks.ProgressState progress = progressStates.get(0);
            guiGraphics.drawString(minecraft.font,
                    net.minecraft.network.chat.Component.translatable("screen.sailboatmod.road_planner.overlay.progress",
                            progress.sourceTownName(), progress.targetTownName(), progress.progressPercent()),
                    x + 10, textY, 0xFFE6EEF5, false);
            textY += 12;
            guiGraphics.drawString(minecraft.font,
                    net.minecraft.network.chat.Component.translatable("screen.sailboatmod.road_planner.overlay.workers", progress.activeWorkers()),
                    x + 10, textY, 0xFFB7C8D6, false);
        }
    }

    private static boolean isHoldingPlanner(Player player) {
        return player.getMainHandItem().is(ModItems.ROAD_PLANNER_ITEM.get())
                || player.getOffhandItem().is(ModItems.ROAD_PLANNER_ITEM.get());
    }

    private static void renderMarker(VertexConsumer vc, Matrix4f matrix, Vector3f normal, BlockPos pos,
                                     float r, float g, float b, float a) {
        float x = pos.getX() + 0.5F;
        float y = pos.getY() + 1.0F;
        float z = pos.getZ() + 0.5F;
        line(vc, matrix, normal, x - 0.35F, y, z, x + 0.35F, y, z, r, g, b, a);
        line(vc, matrix, normal, x, y - 0.35F, z, x, y + 0.35F, z, r, g, b, a);
        line(vc, matrix, normal, x, y, z - 0.35F, x, y, z + 0.35F, r, g, b, a);
    }

    private static void line(VertexConsumer vc, Matrix4f matrix, Vector3f normal,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        vc.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z()).endVertex();
        vc.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z()).endVertex();
    }
}
