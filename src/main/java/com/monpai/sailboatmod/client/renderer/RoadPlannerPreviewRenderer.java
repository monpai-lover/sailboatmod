package com.monpai.sailboatmod.client.renderer;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
        if (player == null || preview == null || preview.ghostBlocks().isEmpty() || !isHoldingPlanner(player)) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer fillConsumer = bufferSource.getBuffer(RenderType.debugFilledBox());
        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());
        BlockPos cameraPos = BlockPos.containing(event.getCamera().getPosition());

        float fillAlpha = preview.awaitingConfirmation() ? 0.24F : 0.16F;
        for (RoadPlannerClientHooks.PreviewGhostBlock block : preview.ghostBlocks()) {
            if (block == null || block.pos() == null) {
                continue;
            }
            renderBlockBox(
                    poseStack,
                    fillConsumer,
                    lineConsumer,
                    block.pos(),
                    cameraPos,
                    0.30F,
                    0.90F,
                    0.88F,
                    fillAlpha,
                    0.94F,
                    0.76F,
                    0.20F,
                    0.95F
            );
        }
        renderHighlightBox(poseStack, lineConsumer, preview.startHighlightPos(), cameraPos, 0.18F, 0.76F, 1.0F, 0.95F, 0.02F);
        renderHighlightBox(poseStack, lineConsumer, preview.endHighlightPos(), cameraPos, 1.0F, 0.72F, 0.25F, 0.95F, 0.02F);
        renderHighlightBox(poseStack, lineConsumer, preview.focusPos(), cameraPos, 0.94F, 0.76F, 0.20F, 0.98F, 0.06F);

        bufferSource.endBatch(RenderType.debugFilledBox());
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
        guiGraphics.drawString(minecraft.font, Component.translatable("item.sailboatmod.road_planner"), x + 10, y + 8, 0xFFF1D9A0, false);
        int textY = y + 22;
        if (preview != null) {
            guiGraphics.drawString(
                    minecraft.font,
                    Component.translatable("screen.sailboatmod.road_planner.overlay.route", preview.sourceTownName(), preview.targetTownName()),
                    x + 10,
                    textY,
                    0xFFE6EEF5,
                    false
            );
            textY += 12;
            guiGraphics.drawString(
                    minecraft.font,
                    Component.translatable("screen.sailboatmod.road_planner.overlay.length", preview.ghostBlocks().size()),
                    x + 10,
                    textY,
                    0xFFB7C8D6,
                    false
            );
            textY += 12;
            guiGraphics.drawString(
                    minecraft.font,
                    Component.translatable(preview.awaitingConfirmation()
                            ? "screen.sailboatmod.road_planner.overlay.confirm"
                            : "screen.sailboatmod.road_planner.overlay.preview"),
                    x + 10,
                    textY,
                    preview.awaitingConfirmation() ? 0xFFF1C96C : 0xFF8FA9BA,
                    false
            );
            textY += 18;
        }
        if (!progressStates.isEmpty()) {
            RoadPlannerClientHooks.ProgressState progress = progressStates.get(0);
            guiGraphics.drawString(
                    minecraft.font,
                    Component.translatable("screen.sailboatmod.road_planner.overlay.progress",
                            progress.sourceTownName(), progress.targetTownName(), progress.progressPercent()),
                    x + 10,
                    textY,
                    0xFFE6EEF5,
                    false
            );
            textY += 12;
            guiGraphics.drawString(
                    minecraft.font,
                    Component.translatable("screen.sailboatmod.road_planner.overlay.workers", progress.activeWorkers()),
                    x + 10,
                    textY,
                    0xFFB7C8D6,
                    false
            );
        }
    }

    private static boolean isHoldingPlanner(Player player) {
        return player.getMainHandItem().is(ModItems.ROAD_PLANNER_ITEM.get())
                || player.getOffhandItem().is(ModItems.ROAD_PLANNER_ITEM.get());
    }

    private static void renderBlockBox(PoseStack poseStack,
                                       VertexConsumer fillConsumer,
                                       VertexConsumer lineConsumer,
                                       BlockPos pos,
                                       BlockPos cameraPos,
                                       float fillR,
                                       float fillG,
                                       float fillB,
                                       float fillA,
                                       float lineR,
                                       float lineG,
                                       float lineB,
                                       float lineA) {
        BlockPos relative = pos.subtract(cameraPos);
        float minX = relative.getX();
        float minY = relative.getY();
        float minZ = relative.getZ();
        float maxX = minX + 1.0F;
        float maxY = minY + 1.0F;
        float maxZ = minZ + 1.0F;
        LevelRenderer.addChainedFilledBoxVertices(poseStack, fillConsumer, minX, minY, minZ, maxX, maxY, maxZ, fillR, fillG, fillB, fillA);
        LevelRenderer.renderLineBox(poseStack, lineConsumer, minX, minY, minZ, maxX, maxY, maxZ, lineR, lineG, lineB, lineA);
    }

    private static void renderHighlightBox(PoseStack poseStack,
                                           VertexConsumer lineConsumer,
                                           BlockPos pos,
                                           BlockPos cameraPos,
                                           float lineR,
                                           float lineG,
                                           float lineB,
                                           float lineA,
                                           float inset) {
        if (pos == null) {
            return;
        }
        BlockPos relative = pos.subtract(cameraPos);
        float minX = relative.getX() - inset;
        float minY = relative.getY() - inset;
        float minZ = relative.getZ() - inset;
        float maxX = relative.getX() + 1.0F + inset;
        float maxY = relative.getY() + 1.0F + inset;
        float maxZ = relative.getZ() + 1.0F + inset;
        LevelRenderer.renderLineBox(poseStack, lineConsumer, minX, minY, minZ, maxX, maxY, maxZ, lineR, lineG, lineB, lineA);
    }
}
