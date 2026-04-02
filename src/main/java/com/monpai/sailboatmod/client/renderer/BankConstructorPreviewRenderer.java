package com.monpai.sailboatmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.ConstructorClientHooks;
import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.nation.service.BlueprintService;
import com.monpai.sailboatmod.nation.service.ConstructionCostService;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager.StructureType;
import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BankConstructorPreviewRenderer {
    private static final Map<String, BlueprintService.BlueprintPlacement> PLACEMENT_CACHE = new HashMap<>();
    private static PreviewSummary currentPreview;
    private static ProjectionSummary currentProjection;

    private BankConstructorPreviewRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            currentPreview = null;
            currentProjection = null;
            return;
        }
        ItemStack held = player.getMainHandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get()) ? player.getMainHandItem()
                : player.getOffhandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get()) ? player.getOffhandItem() : ItemStack.EMPTY;
        if (held.isEmpty()) {
            currentPreview = null;
            currentProjection = null;
            return;
        }

        StructureType heldType = BankConstructorItem.getSelectedType(held);
        renderPendingProjection(event, mc, player, held, heldType);

        BlockHitResult hit = player.level().clip(new ClipContext(
                player.getEyePosition(event.getPartialTick()),
                player.getEyePosition(event.getPartialTick()).add(player.getViewVector(event.getPartialTick()).scale(5.0D)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            currentPreview = null;
            return;
        }

        StructureType type = heldType;
        int rotation = BankConstructorItem.getRotation(held);
        int offsetX = BankConstructorItem.getOffsetX(held);
        int offsetY = BankConstructorItem.getOffsetY(held);
        int offsetZ = BankConstructorItem.getOffsetZ(held);
        BlockPos origin = hit.getBlockPos().above().offset(offsetX, offsetY, offsetZ);
        Vec3 cam = event.getCamera().getPosition();

        BlueprintService.BlueprintPlacement placement = getCachedPlacement(mc, type, rotation);
        if (placement == null || placement.blocks().isEmpty()) {
            currentPreview = buildFallbackSummary(type, rotation);
            // Fallback: wireframe
            renderWireframe(event, type, rotation, origin, cam);
            return;
        }

        PreviewAnalysis analysis = analyzePreview(player, origin, placement);
        Map<PreviewStatus, List<StructureTemplate.StructureBlockInfo>> groupedBlocks = analysis.groupedBlocks();
        currentPreview = buildPreviewSummary(type, placement, analysis);

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        poseStack.pushPose();
        poseStack.translate(origin.getX() - cam.x, origin.getY() - cam.y, origin.getZ() - cam.z);

        renderPreviewGroup(poseStack, bufferSource, blockRenderer, groupedBlocks.get(PreviewStatus.MATCHED), analysis, 0.45F, 1.0F, 0.45F, 0.16F);
        renderPreviewGroup(poseStack, bufferSource, blockRenderer, groupedBlocks.get(PreviewStatus.MISSING), analysis, 0.20F, 0.85F, 1.0F, 0.48F);
        renderPreviewGroup(poseStack, bufferSource, blockRenderer, groupedBlocks.get(PreviewStatus.BLOCKED), analysis, 1.0F, 0.28F, 0.28F, 0.62F);

        // Draw wireframe outline on top
        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        org.joml.Vector3f normal = new org.joml.Vector3f(0, 1, 0);
        float footprintR = groupedBlocks.get(PreviewStatus.BLOCKED).isEmpty() ? 0.25F : 1.0F;
        float footprintG = groupedBlocks.get(PreviewStatus.BLOCKED).isEmpty() ? 0.85F : 0.35F;
        float footprintB = groupedBlocks.get(PreviewStatus.BLOCKED).isEmpty() ? 1.0F : 0.35F;
        renderFootprintGrid(lineVc, matrix, normal, placement.bounds(), footprintR, footprintG, footprintB, 0.45F);
        renderTapeCorners(lineVc, matrix, normal, placement.bounds(), footprintR, footprintG, footprintB, 0.75F);
        if (analysis.currentLayerY() >= 0) {
            renderLayerHighlight(lineVc, matrix, normal, placement.bounds(), analysis.currentLayerY(), 1.0F, 0.88F, 0.35F, 0.85F);
        }
        renderBounds(lineVc, matrix, normal, placement.bounds(), 0.2F, 0.8F, 1.0F, 0.6F);
        if (analysis.nextTarget() != null) {
            renderTargetMarker(lineVc, matrix, normal, analysis.nextTarget().pos(), 1.0F, 0.86F, 0.2F, 0.95F);
        }
        bufferSource.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        PreviewSummary summary = currentPreview;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        StructureType selectedType = selectedType(mc.player);
        ConstructorClientHooks.ConstructionProgress progress =
                ConstructorClientHooks.findNearest(mc.player.blockPosition(), selectedType);
        ProjectionSummary projection = currentProjection;
        if (summary == null && progress == null && projection == null) {
            return;
        }

        int x = 12;
        int y = 18;
        int leftWidth = 210;
        int rightWidth = 154;
        int columnGap = 10;
        int totalWidth = leftWidth + columnGap + rightWidth;
        int padding = 8;
        int lineHeight = 9;
        int rowSpacing = 2;
        var g = event.getGuiGraphics();
        int topSectionHeight = 0;
        if (summary != null) {
            int leftSectionHeight = padding
                    + measureWrappedPreviewLines(mc, List.of(summary.lines().get(0)), leftWidth - padding * 2, lineHeight, rowSpacing)
                    + 10
                    + 7
                    + 6
                    + measureWrappedPreviewLines(mc, summary.lines().subList(1, summary.lines().size()), leftWidth - padding * 2, lineHeight, rowSpacing)
                    + padding;
            int rightSectionHeight = padding
                    + measureWrappedPreviewLines(mc,
                    List.of(new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.materials").getString(), 0xFFF3D486)),
                    rightWidth - padding * 2,
                    lineHeight,
                    rowSpacing)
                    + 4
                    + measureWrappedPreviewLines(mc, summary.materialLines(), rightWidth - padding * 2, lineHeight, rowSpacing)
                    + padding;
            topSectionHeight = Math.max(leftSectionHeight, rightSectionHeight);
        }

        List<PreviewLine> projectionLines = projection == null
                ? List.of()
                : List.of(
                new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.projected").getString(), 0xFFFFD27A),
                new PreviewLine(Component.translatable(
                        "overlay.sailboatmod.constructor.projected_detail",
                        Component.translatable(projection.type().translationKey()),
                        projection.origin().getX(),
                        projection.origin().getY(),
                        projection.origin().getZ()).getString(), 0xFFDCEEFF),
                new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.projected_hint").getString(), 0xFF9ED5EA)
        );

        List<PreviewLine> progressLines = progress == null
                ? List.of()
                : List.of(
                new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.active").getString(), 0xFFF3D486),
                new PreviewLine(Component.translatable(
                        "overlay.sailboatmod.constructor.progress",
                        Component.translatable("item.sailboatmod.structure." + progress.structureId()),
                        progress.progressPercent()).getString(), 0xFFDCEEFF),
                new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.workers", progress.activeWorkers()).getString(), 0xFF9ED5EA)
        );

        int lowerSectionHeight = 0;
        if (!projectionLines.isEmpty()) {
            lowerSectionHeight += measureWrappedPreviewLines(mc, projectionLines, totalWidth - padding * 2, lineHeight, rowSpacing);
        }
        if (!progressLines.isEmpty()) {
            if (lowerSectionHeight > 0) {
                lowerSectionHeight += 6;
            }
            lowerSectionHeight += measureWrappedPreviewLines(mc, progressLines, totalWidth - padding * 2, lineHeight, rowSpacing);
        }

        int boxHeight = padding * 2 + topSectionHeight + (lowerSectionHeight > 0 ? lowerSectionHeight + (topSectionHeight > 0 ? 8 : 0) : 0);

        g.fill(x - 6, y - 6, x + totalWidth, y + boxHeight, 0xB0151B22);
        g.fill(x - 5, y - 5, x + totalWidth - 1, y + boxHeight - 1, 0xA9232D37);
        g.fill(x - 6, y - 6, x + totalWidth, y - 5, 0xCC6BD4FF);

        int currentY = y + padding;
        if (summary != null) {
            int leftX = x;
            int rightX = x + leftWidth + columnGap;
            int progressBarWidth = leftWidth - padding * 2;

            currentY = y + padding;
            currentY += drawWrappedPreviewLines(g, mc, List.of(summary.lines().get(0)), leftX + padding, currentY, leftWidth - padding * 2, lineHeight, rowSpacing, true);
            g.drawString(mc.font,
                    Component.translatable("overlay.sailboatmod.constructor.completion", summary.completionPercent()).getString(),
                    leftX + padding,
                    currentY,
                    0xFFDCEEFF,
                    false);
            currentY += 10;

            int filledWidth = Math.max(0, Math.min(progressBarWidth, (summary.completionPercent() * progressBarWidth) / 100));
            g.fill(leftX + padding, currentY, leftX + padding + progressBarWidth, currentY + 7, 0x66303A44);
            g.fill(leftX + padding, currentY, leftX + padding + filledWidth, currentY + 7,
                    summary.blockedCount() > 0 ? 0xCCF0A020 : 0xCC56DDB4);
            currentY += 13;
            currentY += drawWrappedPreviewLines(g, mc, summary.lines().subList(1, summary.lines().size()),
                    leftX + padding, currentY, leftWidth - padding * 2, lineHeight, rowSpacing, true);

            int materialY = y + padding;
            materialY += drawWrappedPreviewLines(g, mc,
                    List.of(new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.materials").getString(), 0xFFF3D486)),
                    rightX + padding, materialY, rightWidth - padding * 2, lineHeight, rowSpacing, false);
            materialY += 4;
            drawWrappedPreviewLines(g, mc, summary.materialLines(),
                    rightX + padding, materialY, rightWidth - padding * 2, lineHeight, rowSpacing, false);

            g.fill(x + leftWidth + (columnGap / 2), y + 2, x + leftWidth + (columnGap / 2) + 1, y + topSectionHeight, 0x507F98AA);
        }

        currentY = y + padding + topSectionHeight;
        if (lowerSectionHeight > 0) {
            if (topSectionHeight > 0) {
                currentY += 4;
                g.fill(x, currentY, x + totalWidth - 6, currentY + 1, 0x507F98AA);
                currentY += 4;
            }
            if (!projectionLines.isEmpty()) {
                currentY += drawWrappedPreviewLines(g, mc, projectionLines, x + padding, currentY,
                        totalWidth - padding * 2, lineHeight, rowSpacing, false);
            }
            if (!progressLines.isEmpty()) {
                if (!projectionLines.isEmpty()) {
                    currentY += 6;
                }
                drawWrappedPreviewLines(g, mc, progressLines, x + padding, currentY,
                        totalWidth - padding * 2, lineHeight, rowSpacing, false);
            }
        }
    }

    private static void renderWireframe(RenderLevelStageEvent event, StructureType type, int rotation, BlockPos origin, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(origin.getX() - cam.x, origin.getY() - cam.y, origin.getZ() - cam.z);
        VertexConsumer vc = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        org.joml.Vector3f normal = new org.joml.Vector3f(0, 1, 0);
        float w = type.w(), h = type.h(), d = type.d();
        if (rotation == 1 || rotation == 3) { float temp = w; w = d; d = temp; }
        renderBounds(vc, matrix, normal,
                new BlueprintService.PlacementBounds(BlockPos.ZERO, new BlockPos(Math.max(0, (int) w - 1), Math.max(0, (int) h - 1), Math.max(0, (int) d - 1))),
                0.0F, 1.0F, 0.0F, 0.8F);
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static BlueprintService.BlueprintPlacement getCachedPlacement(Minecraft mc, StructureType type, int rotation) {
        String cacheKey = type.nbtName() + "_r" + rotation;
        if (PLACEMENT_CACHE.containsKey(cacheKey)) {
            return PLACEMENT_CACHE.get(cacheKey);
        }
        if (mc.level == null) {
            return null;
        }

        BlueprintService.BlueprintPlacement placement = BlueprintService.preparePlacement(
                mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                type.nbtName(),
                BlockPos.ZERO,
                rotation);
        if (placement != null) {
            PLACEMENT_CACHE.put(cacheKey, placement);
        }
        return placement;
    }

    private static PreviewAnalysis analyzePreview(Player player, BlockPos origin, BlueprintService.BlueprintPlacement placement) {
        Map<PreviewStatus, List<StructureTemplate.StructureBlockInfo>> grouped = new EnumMap<>(PreviewStatus.class);
        for (PreviewStatus status : PreviewStatus.values()) {
            grouped.put(status, new ArrayList<>());
        }
        List<StructureTemplate.StructureBlockInfo> blocks = placement.blocks();
        StructureTemplate.StructureBlockInfo nextTarget = null;
        int currentLayerY = Integer.MAX_VALUE;

        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockPos worldPos = origin.offset(info.pos());
            BlockState currentState = player.level().getBlockState(worldPos);
            if (currentState.equals(info.state())) {
                grouped.get(PreviewStatus.MATCHED).add(info);
            } else if (currentState.isAir() || currentState.canBeReplaced() || currentState.liquid()) {
                grouped.get(PreviewStatus.MISSING).add(info);
                currentLayerY = Math.min(currentLayerY, info.pos().getY());
            } else {
                grouped.get(PreviewStatus.BLOCKED).add(info);
                currentLayerY = Math.min(currentLayerY, info.pos().getY());
            }
        }
        if (currentLayerY == Integer.MAX_VALUE) {
            currentLayerY = -1;
        } else {
            double bestDistance = Double.MAX_VALUE;
            for (StructureTemplate.StructureBlockInfo info : grouped.get(PreviewStatus.MISSING)) {
                if (info.pos().getY() != currentLayerY) {
                    continue;
                }
                BlockPos worldPos = origin.offset(info.pos());
                double distance = worldPos.distToCenterSqr(player.getX(), player.getEyeY(), player.getZ());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nextTarget = info;
                }
            }
        }

        int totalLayers = Math.max(placement.bounds().height(), 1);
        int currentLayerIndex = currentLayerY < 0
                ? totalLayers
                : (currentLayerY - placement.bounds().min().getY()) + 1;
        return new PreviewAnalysis(grouped, nextTarget, currentLayerY, currentLayerIndex, totalLayers);
    }

    private static void renderPreviewGroup(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                           BlockRenderDispatcher blockRenderer,
                                           List<StructureTemplate.StructureBlockInfo> blocks,
                                           PreviewAnalysis analysis,
                                           float r, float g, float b, float a) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }

        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            float alpha = applyLayerAlpha(a, info.pos().getY(), analysis);
            float brightness = applyLayerBrightness(info.pos().getY(), analysis);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(
                    clamp01(r * brightness),
                    clamp01(g * brightness),
                    clamp01(b * brightness),
                    clamp01(alpha));
            poseStack.pushPose();
            poseStack.translate(info.pos().getX(), info.pos().getY(), info.pos().getZ());
            try {
                blockRenderer.renderSingleBlock(info.state(), poseStack, bufferSource, 0xF000F0,
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            } catch (Exception ignored) {
            }
            poseStack.popPose();
        }
        bufferSource.endBatch();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    private static void renderBounds(VertexConsumer vc, Matrix4f matrix, org.joml.Vector3f normal,
                                     BlueprintService.PlacementBounds bounds,
                                     float r, float g, float b, float a) {
        float minX = bounds.min().getX();
        float minY = bounds.min().getY();
        float minZ = bounds.min().getZ();
        float maxX = bounds.max().getX() + 1;
        float maxY = bounds.max().getY() + 1;
        float maxZ = bounds.max().getZ() + 1;

        line(vc, matrix, normal, minX, minY, minZ, maxX, minY, minZ, r, g, b, a);
        line(vc, matrix, normal, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
        line(vc, matrix, normal, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
        line(vc, matrix, normal, minX, minY, maxZ, minX, minY, minZ, r, g, b, a);
        line(vc, matrix, normal, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a);
        line(vc, matrix, normal, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a);
        line(vc, matrix, normal, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
        line(vc, matrix, normal, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);
        line(vc, matrix, normal, minX, minY, minZ, minX, maxY, minZ, r, g, b, a);
        line(vc, matrix, normal, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a);
        line(vc, matrix, normal, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a);
        line(vc, matrix, normal, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a);
    }

    private static void renderTargetMarker(VertexConsumer vc, Matrix4f matrix, org.joml.Vector3f normal,
                                           BlockPos pos, float r, float g, float b, float a) {
        float minX = pos.getX();
        float minY = pos.getY();
        float minZ = pos.getZ();
        float maxX = minX + 1;
        float maxY = minY + 1;
        float maxZ = minZ + 1;

        renderBoxEdges(vc, matrix, normal, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
        float inset = 0.2F;
        renderBoxEdges(vc, matrix, normal,
                minX + inset, minY + inset, minZ + inset,
                maxX - inset, maxY - inset, maxZ - inset,
                r, g, b, a * 0.7F);
    }

    private static void renderLayerHighlight(VertexConsumer vc, Matrix4f matrix, org.joml.Vector3f normal,
                                             BlueprintService.PlacementBounds bounds, int layerY,
                                             float r, float g, float b, float a) {
        float minX = bounds.min().getX();
        float minZ = bounds.min().getZ();
        float maxX = bounds.max().getX() + 1;
        float maxZ = bounds.max().getZ() + 1;
        float minY = layerY;
        float maxY = layerY + 1;

        renderBoxEdges(vc, matrix, normal, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
        line(vc, matrix, normal, minX, minY + 0.02F, minZ, maxX, minY + 0.02F, minZ, r, g, b, a * 0.8F);
        line(vc, matrix, normal, maxX, minY + 0.02F, minZ, maxX, minY + 0.02F, maxZ, r, g, b, a * 0.8F);
        line(vc, matrix, normal, maxX, minY + 0.02F, maxZ, minX, minY + 0.02F, maxZ, r, g, b, a * 0.8F);
        line(vc, matrix, normal, minX, minY + 0.02F, maxZ, minX, minY + 0.02F, minZ, r, g, b, a * 0.8F);
    }

    private static void renderBoxEdges(VertexConsumer vc, Matrix4f matrix, org.joml.Vector3f normal,
                                       float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                                       float r, float g, float b, float a) {
        line(vc, matrix, normal, minX, minY, minZ, maxX, minY, minZ, r, g, b, a);
        line(vc, matrix, normal, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
        line(vc, matrix, normal, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
        line(vc, matrix, normal, minX, minY, maxZ, minX, minY, minZ, r, g, b, a);
        line(vc, matrix, normal, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a);
        line(vc, matrix, normal, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a);
        line(vc, matrix, normal, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
        line(vc, matrix, normal, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);
        line(vc, matrix, normal, minX, minY, minZ, minX, maxY, minZ, r, g, b, a);
        line(vc, matrix, normal, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a);
        line(vc, matrix, normal, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a);
        line(vc, matrix, normal, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a);
    }

    private static void renderFootprintGrid(VertexConsumer vc, Matrix4f matrix, org.joml.Vector3f normal,
                                            BlueprintService.PlacementBounds bounds,
                                            float r, float g, float b, float a) {
        float minX = bounds.min().getX();
        float minY = bounds.min().getY() + 0.02F;
        float minZ = bounds.min().getZ();
        float maxX = bounds.max().getX() + 1;
        float maxZ = bounds.max().getZ() + 1;

        for (int x = bounds.min().getX(); x <= bounds.max().getX() + 1; x++) {
            float alpha = ((x - bounds.min().getX()) % 4 == 0) ? a : a * 0.45F;
            line(vc, matrix, normal, x, minY, minZ, x, minY, maxZ, r, g, b, alpha);
        }
        for (int z = bounds.min().getZ(); z <= bounds.max().getZ() + 1; z++) {
            float alpha = ((z - bounds.min().getZ()) % 4 == 0) ? a : a * 0.45F;
            line(vc, matrix, normal, minX, minY, z, maxX, minY, z, r, g, b, alpha);
        }
    }

    private static void renderTapeCorners(VertexConsumer vc, Matrix4f matrix, org.joml.Vector3f normal,
                                          BlueprintService.PlacementBounds bounds,
                                          float r, float g, float b, float a) {
        float minX = bounds.min().getX() - 0.15F;
        float minY = bounds.min().getY();
        float minZ = bounds.min().getZ() - 0.15F;
        float maxX = bounds.max().getX() + 1.15F;
        float maxY = bounds.max().getY() + 1.25F;
        float maxZ = bounds.max().getZ() + 1.15F;
        float arm = 0.65F;

        renderCornerMarker(vc, matrix, normal, minX, minY, minZ, minX + arm, minZ + arm, maxY, r, g, b, a);
        renderCornerMarker(vc, matrix, normal, maxX, minY, minZ, maxX - arm, minZ + arm, maxY, r, g, b, a);
        renderCornerMarker(vc, matrix, normal, minX, minY, maxZ, minX + arm, maxZ - arm, maxY, r, g, b, a);
        renderCornerMarker(vc, matrix, normal, maxX, minY, maxZ, maxX - arm, maxZ - arm, maxY, r, g, b, a);
    }

    private static void renderCornerMarker(VertexConsumer vc, Matrix4f matrix, org.joml.Vector3f normal,
                                           float x, float minY, float z, float armX, float armZ, float maxY,
                                           float r, float g, float b, float a) {
        line(vc, matrix, normal, x, minY, z, armX, minY, z, r, g, b, a);
        line(vc, matrix, normal, x, minY, z, x, minY, armZ, r, g, b, a);
        line(vc, matrix, normal, x, minY, z, x, maxY, z, r, g, b, a);
    }

    private static PreviewSummary buildPreviewSummary(StructureType type, BlueprintService.BlueprintPlacement placement,
                                                      PreviewAnalysis analysis) {
        Map<PreviewStatus, List<StructureTemplate.StructureBlockInfo>> groupedBlocks = analysis.groupedBlocks();
        int matched = sizeOf(groupedBlocks.get(PreviewStatus.MATCHED));
        int missing = sizeOf(groupedBlocks.get(PreviewStatus.MISSING));
        int blocked = sizeOf(groupedBlocks.get(PreviewStatus.BLOCKED));
        int total = matched + missing + blocked;
        int completion = total <= 0 ? 0 : (matched * 100) / total;
        List<StructureTemplate.StructureBlockInfo> remainingBlocks = new ArrayList<>();
        remainingBlocks.addAll(groupedBlocks.getOrDefault(PreviewStatus.MISSING, List.of()));
        remainingBlocks.addAll(groupedBlocks.getOrDefault(PreviewStatus.BLOCKED, List.of()));
        long totalBudget = estimateBudget(placement.blocks());
        long remainingBudget = estimateBudget(remainingBlocks);

        List<PreviewLine> lines = new ArrayList<>();
        lines.add(new PreviewLine(Component.translatable(type.translationKey()).getString(), 0xFFEAF6FF));
        lines.add(new PreviewLine(Component.translatable(
                "overlay.sailboatmod.constructor.size",
                placement.bounds().width(),
                placement.bounds().height(),
                placement.bounds().depth()).getString(), 0xFF9ED5EA));
        if (missing + blocked > 0) {
            lines.add(new PreviewLine(Component.translatable(
                    "overlay.sailboatmod.constructor.layer",
                    analysis.currentLayerIndex(),
                    analysis.totalLayers()).getString(), 0xFFFFE08A));
        } else {
            lines.add(new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.layer_complete").getString(), 0xFF8FD7B4));
        }
        lines.add(new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.blocks", total, matched).getString(), 0xFFD9E3EA));
        lines.add(new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.missing_blocked", missing, blocked).getString(),
                blocked > 0 ? 0xFFFF8A8A : 0xFF7DE0FF));
        lines.add(new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.budget", totalBudget, remainingBudget).getString(), 0xFFF3D486));
        if (analysis.nextTarget() != null) {
            lines.add(new PreviewLine(Component.translatable(
                    "overlay.sailboatmod.constructor.next",
                    analysis.nextTarget().state().getBlock().getName()).getString(), 0xFFFFE08A));
        }
        lines.add(new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.hint").getString(), 0xFFB7C0CB));
        return new PreviewSummary(List.copyOf(lines), buildMaterialLines(remainingBlocks), completion, blocked);
    }

    private static PreviewSummary buildFallbackSummary(StructureType type, int rotation) {
        int width = type.w();
        int depth = type.d();
        if (rotation == 1 || rotation == 3) {
            int temp = width;
            width = depth;
            depth = temp;
        }
        List<PreviewLine> lines = List.of(
                new PreviewLine(Component.translatable(type.translationKey()).getString(), 0xFFEAF6FF),
                new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.size", width, type.h(), depth).getString(), 0xFF9ED5EA),
                new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.preview_fallback").getString(), 0xFFE6B86A)
        );
        return new PreviewSummary(lines, List.of(), 0, 0);
    }

    private static int sizeOf(List<StructureTemplate.StructureBlockInfo> blocks) {
        return blocks == null ? 0 : blocks.size();
    }

    private static long estimateBudget(List<StructureTemplate.StructureBlockInfo> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return 0L;
        }

        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            ItemStack stack = toCostStack(info.state());
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack key = null;
            for (ItemStack existing : counts.keySet()) {
                if (ItemStack.isSameItem(existing, stack)) {
                    key = existing;
                    break;
                }
            }
            if (key == null) {
                counts.put(stack, 1);
            } else {
                counts.put(key, counts.get(key) + 1);
            }
        }

        long total = 0L;
        for (Map.Entry<ItemStack, Integer> entry : counts.entrySet()) {
            total += (long) ConstructionCostService.estimateFallbackUnitPrice(entry.getKey()) * entry.getValue();
        }
        return total;
    }

    private static List<PreviewLine> buildMaterialLines(List<StructureTemplate.StructureBlockInfo> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of(new PreviewLine(Component.translatable("overlay.sailboatmod.constructor.materials_none").getString(), 0xFF8FD7B4));
        }

        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            ItemStack stack = toCostStack(info.state());
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack key = null;
            for (ItemStack existing : counts.keySet()) {
                if (ItemStack.isSameItem(existing, stack)) {
                    key = existing;
                    break;
                }
            }
            if (key == null) {
                counts.put(stack, 1);
            } else {
                counts.put(key, counts.get(key) + 1);
            }
        }

        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<ItemStack, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(entry -> entry.getKey().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER))
                .limit(4)
                .map(entry -> new PreviewLine(
                        entry.getKey().getHoverName().getString() + " x" + entry.getValue(),
                        0xFFDCEEFF))
                .toList();
    }

    private static int drawWrappedPreviewLines(GuiGraphics graphics,
                                               Minecraft mc,
                                               List<PreviewLine> lines,
                                               int x,
                                               int y,
                                               int width,
                                               int lineHeight,
                                               int rowSpacing,
                                               boolean shadow) {
        int currentY = y;
        for (PreviewLine line : lines) {
            List<FormattedCharSequence> rows = wrapPreviewLine(mc, line, width);
            for (FormattedCharSequence row : rows) {
                graphics.drawString(mc.font, row, x, currentY, line.color(), shadow);
                currentY += lineHeight + rowSpacing;
            }
        }
        return Math.max(0, currentY - y);
    }

    private static int measureWrappedPreviewLines(Minecraft mc,
                                                  List<PreviewLine> lines,
                                                  int width,
                                                  int lineHeight,
                                                  int rowSpacing) {
        int height = 0;
        for (PreviewLine line : lines) {
            height += wrapPreviewLine(mc, line, width).size() * (lineHeight + rowSpacing);
        }
        return height;
    }

    private static List<FormattedCharSequence> wrapPreviewLine(Minecraft mc, PreviewLine line, int width) {
        if (line == null || line.text() == null || line.text().isBlank()) {
            return List.of();
        }
        return mc.font.split(Component.literal(line.text()), Math.max(24, width));
    }

    private static ItemStack toCostStack(BlockState state) {
        var item = state.getBlock().asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static float applyLayerAlpha(float baseAlpha, int blockY, PreviewAnalysis analysis) {
        int currentLayerY = analysis.currentLayerY();
        if (currentLayerY < 0) {
            return baseAlpha;
        }
        if (blockY == currentLayerY) {
            return baseAlpha * 1.45F;
        }
        if (blockY < currentLayerY) {
            return baseAlpha * 0.72F;
        }
        if (blockY == currentLayerY + 1) {
            return baseAlpha * 0.62F;
        }
        return baseAlpha * 0.34F;
    }

    private static float applyLayerBrightness(int blockY, PreviewAnalysis analysis) {
        int currentLayerY = analysis.currentLayerY();
        if (currentLayerY < 0) {
            return 1.0F;
        }
        if (blockY == currentLayerY) {
            return 1.18F;
        }
        if (blockY < currentLayerY) {
            return 0.92F;
        }
        if (blockY == currentLayerY + 1) {
            return 0.82F;
        }
        return 0.64F;
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private enum PreviewStatus {
        MATCHED,
        MISSING,
        BLOCKED
    }

    private record PreviewSummary(List<PreviewLine> lines, List<PreviewLine> materialLines, int completionPercent, int blockedCount) {
    }

    private record ProjectionSummary(BlockPos origin, StructureType type) {
    }

    private record PreviewLine(String text, int color) {
    }

    private record PreviewAnalysis(Map<PreviewStatus, List<StructureTemplate.StructureBlockInfo>> groupedBlocks,
                                   StructureTemplate.StructureBlockInfo nextTarget,
                                   int currentLayerY,
                                   int currentLayerIndex,
                                   int totalLayers) {
    }

    private static void line(VertexConsumer vc, Matrix4f matrix, org.joml.Vector3f normal,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        vc.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z()).endVertex();
        vc.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z()).endVertex();
    }

    private static StructureType selectedType(Player player) {
        ItemStack held = player.getMainHandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get()) ? player.getMainHandItem()
                : player.getOffhandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get()) ? player.getOffhandItem() : ItemStack.EMPTY;
        return held.isEmpty() ? null : BankConstructorItem.getSelectedType(held);
    }

    private static void renderPendingProjection(RenderLevelStageEvent event,
                                                Minecraft mc,
                                                Player player,
                                                ItemStack held,
                                                StructureType heldType) {
        if (!BankConstructorItem.hasPendingProjection(held, player.level())) {
            currentProjection = null;
            return;
        }

        BlockPos projectionOrigin = BankConstructorItem.getPendingProjectionOrigin(held, player.level());
        StructureType projectionType = BankConstructorItem.getPendingProjectionType(held);
        int projectionRotation = BankConstructorItem.getPendingProjectionRotation(held);
        if (projectionOrigin == null || projectionType == null) {
            currentProjection = null;
            return;
        }

        BlueprintService.BlueprintPlacement placement = getCachedPlacement(mc, projectionType, projectionRotation);
        if (placement == null) {
            currentProjection = new ProjectionSummary(projectionOrigin, projectionType);
            return;
        }

        currentProjection = new ProjectionSummary(projectionOrigin, projectionType);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        poseStack.pushPose();
        poseStack.translate(projectionOrigin.getX() - cam.x, projectionOrigin.getY() - cam.y, projectionOrigin.getZ() - cam.z);

        PreviewAnalysis analysis = analyzePreview(player, projectionOrigin, placement);
        Map<PreviewStatus, List<StructureTemplate.StructureBlockInfo>> groupedBlocks = analysis.groupedBlocks();
        renderPreviewGroup(poseStack, bufferSource, blockRenderer, groupedBlocks.get(PreviewStatus.MISSING), analysis, 1.0F, 0.72F, 0.24F, 0.26F);
        renderPreviewGroup(poseStack, bufferSource, blockRenderer, groupedBlocks.get(PreviewStatus.BLOCKED), analysis, 1.0F, 0.35F, 0.28F, 0.38F);

        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        org.joml.Vector3f normal = new org.joml.Vector3f(0, 1, 0);
        renderBounds(lineVc, matrix, normal, placement.bounds(), 1.0F, 0.72F, 0.24F, 0.85F);
        renderFootprintGrid(lineVc, matrix, normal, placement.bounds(), 1.0F, 0.72F, 0.24F, 0.35F);
        bufferSource.endBatch(RenderType.lines());
        poseStack.popPose();
    }
}
