package com.monpai.sailboatmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.ConstructionGhostClientHooks;
import com.monpai.sailboatmod.construction.RoadRuntimeProgressSelection;
import com.monpai.sailboatmod.construction.RoadRuntimeProgressSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ConstructionGhostPreviewRenderer {
    private ConstructionGhostPreviewRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || !ConstructionGhostClientHooks.isHoldingPreviewTool(player)) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer fillConsumer = bufferSource.getBuffer(RenderType.debugFilledBox());
        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());
        Vec3 cameraPos = event.getCamera().getPosition();

        for (ConstructionGhostClientHooks.BuildingPreview preview : ConstructionGhostClientHooks.buildingPreviews()) {
            renderPreview(poseStack, fillConsumer, lineConsumer, preview.blocks(), preview.targetPos(), cameraPos,
                    0.18F, 0.76F, 1.0F, 0.18F,
                    0.85F, 0.70F, 0.18F, 0.95F);
        }
        for (ConstructionGhostClientHooks.RoadPreview preview : ConstructionGhostClientHooks.roadPreviews()) {
            renderPreview(poseStack, fillConsumer, lineConsumer, preview.blocks(), preview.targetPos(), cameraPos,
                    0.30F, 0.90F, 0.88F, 0.16F,
                    0.94F, 0.76F, 0.20F, 0.95F);
        }
        bufferSource.endBatch(RenderType.debugFilledBox());
        bufferSource.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int x = 12;
        int y = 12;
        if (ConstructionGhostClientHooks.isHoldingPreviewTool(player)) {
            RoadRuntimeProgressSelection selected = RoadRuntimeProgressSelector.pickNearest(
                    player.blockPosition(),
                    roadProgressSelections(ConstructionGhostClientHooks.roadPreviews())
            );
            if (selected != null) {
                renderRoadProgressCard(minecraft, graphics, selected, x, y);
                y += 40;
            }
        }
        if (!ConstructionGhostClientHooks.isHoldingHammer(player)) {
            return;
        }
        ConstructionGhostClientHooks.HammerTarget target = ConstructionGhostClientHooks.pickTarget(player, 6.0D);
        if (target == null) {
            return;
        }

        graphics.fill(x, y, x + 182, y + 28, 0xC9131920);
        graphics.fill(x + 1, y + 1, x + 181, y + 27, 0xEE1F2830);
        graphics.drawString(minecraft.font, Component.translatable("overlay.sailboatmod.builder_hammer.ready"), x + 8, y + 7, 0xFFF0D28A, false);
        graphics.drawString(minecraft.font, Component.translatable(
                target.kind() == ConstructionGhostClientHooks.TargetKind.BUILDING
                        ? "overlay.sailboatmod.builder_hammer.building"
                        : "overlay.sailboatmod.builder_hammer.road"), x + 8, y + 17, 0xFFDCEEFF, false);
    }

    private static List<RoadRuntimeProgressSelection> roadProgressSelections(List<ConstructionGhostClientHooks.RoadPreview> previews) {
        if (previews == null || previews.isEmpty()) {
            return List.of();
        }
        return previews.stream()
                .filter(preview -> preview != null && preview.targetPos() != null)
                .map(preview -> new RoadRuntimeProgressSelection(
                        preview.jobId(),
                        preview.targetPos(),
                        preview.sourceTownName(),
                        preview.targetTownName(),
                        preview.progressPercent(),
                        preview.activeWorkers()))
                .toList();
    }

    private static void renderRoadProgressCard(Minecraft minecraft,
                                               GuiGraphics graphics,
                                               RoadRuntimeProgressSelection selection,
                                               int x,
                                               int y) {
        graphics.fill(x, y, x + 208, y + 34, 0xC9131920);
        graphics.fill(x + 1, y + 1, x + 207, y + 33, 0xEE1F2830);
        graphics.drawString(
                minecraft.font,
                Component.literal(selection.sourceTownName() + " -> " + selection.targetTownName() + " " + selection.progressPercent() + "%"),
                x + 8,
                y + 7,
                0xFFE8F6FF,
                false
        );
        graphics.drawString(
                minecraft.font,
                Component.translatable("screen.sailboatmod.road_planner.overlay.workers", selection.activeWorkers()),
                x + 8,
                y + 19,
                0xFF9ED5EA,
                false
        );
    }

    private static void renderPreview(PoseStack poseStack,
                                      VertexConsumer fillConsumer,
                                      VertexConsumer lineConsumer,
                                      java.util.List<ConstructionGhostClientHooks.GhostBlock> blocks,
                                      BlockPos targetPos,
                                      Vec3 cameraPos,
                                      float fillR,
                                      float fillG,
                                      float fillB,
                                      float fillA,
                                      float highlightR,
                                      float highlightG,
                                      float highlightB,
                                      float lineA) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        for (ConstructionGhostClientHooks.GhostBlock block : blocks) {
            if (block == null || block.pos() == null) {
                continue;
            }
            PreviewBox box = previewBox(block.pos(), cameraPos);
            float minX = (float) box.minX();
            float minY = (float) box.minY();
            float minZ = (float) box.minZ();
            float maxX = (float) box.maxX();
            float maxY = (float) box.maxY();
            float maxZ = (float) box.maxZ();
            boolean highlight = targetPos != null && targetPos.equals(block.pos());

            LevelRenderer.addChainedFilledBoxVertices(
                    poseStack,
                    fillConsumer,
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    highlight ? highlightR : fillR,
                    highlight ? highlightG : fillG,
                    highlight ? highlightB : fillB,
                    highlight ? 0.28F : fillA
            );
            LevelRenderer.renderLineBox(
                    poseStack,
                    lineConsumer,
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    highlight ? highlightR : fillR,
                    highlight ? highlightG : fillG,
                    highlight ? highlightB : fillB,
                    lineA
            );
        }
    }

    private static PreviewBox previewBox(BlockPos pos, Vec3 cameraPos) {
        double minX = pos.getX() - cameraPos.x;
        double minY = pos.getY() - cameraPos.y;
        double minZ = pos.getZ() - cameraPos.z;
        return new PreviewBox(minX, minY, minZ, minX + 1.0D, minY + 1.0D, minZ + 1.0D);
    }

    static PreviewBox previewBoxForTest(BlockPos pos, Vec3 cameraPos) {
        return previewBox(pos, cameraPos);
    }

    record PreviewBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }
}
