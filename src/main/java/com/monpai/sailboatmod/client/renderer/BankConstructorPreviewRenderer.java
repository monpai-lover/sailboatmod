package com.monpai.sailboatmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.nation.service.BlueprintService;
import com.monpai.sailboatmod.nation.service.ConstructionCostService;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager.StructureType;
import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
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

    private BankConstructorPreviewRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            currentPreview = null;
            return;
        }
        ItemStack held = player.getMainHandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get()) ? player.getMainHandItem()
                : player.getOffhandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get()) ? player.getOffhandItem() : ItemStack.EMPTY;
        if (held.isEmpty()) {
            currentPreview = null;
            return;
        }

        BlockHitResult hit = player.level().clip(new ClipContext(
                player.getEyePosition(event.getPartialTick()),
                player.getEyePosition(event.getPartialTick()).add(player.getViewVector(event.getPartialTick()).scale(5.0D)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            currentPreview = null;
            return;
        }

        StructureType type = BankConstructorItem.getSelectedType(held);
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
        if (summary == null || mc.player == null) {
            return;
        }

        int x = 12;
        int y = 18;
        int width = 198;
        int lineHeight = 11;
        int progressBarY = y + 22;
        int materialStartY = progressBarY + 16;
        int boxHeight = 14 + summary.lines().size() * lineHeight + 26 + summary.materialLines().size() * 10;
        var g = event.getGuiGraphics();
        g.fill(x - 6, y - 6, x + width, y + boxHeight, 0xB0151B22);
        g.fill(x - 5, y - 5, x + width - 1, y + boxHeight - 1, 0xA9232D37);
        g.fill(x - 6, y - 6, x + width, y - 5, 0xCC6BD4FF);

        PreviewLine title = summary.lines().get(0);
        g.drawString(mc.font, title.text(), x, y, title.color(), true);

        int progressBarWidth = width - 20;
        int filledWidth = Math.max(0, Math.min(progressBarWidth, (summary.completionPercent() * progressBarWidth) / 100));
        g.fill(x, progressBarY, x + progressBarWidth, progressBarY + 7, 0x66303A44);
        g.fill(x, progressBarY, x + filledWidth, progressBarY + 7, summary.blockedCount() > 0 ? 0xCCF0A020 : 0xCC56DDB4);
        g.drawString(mc.font, String.format(Locale.ROOT, "%d%% complete", summary.completionPercent()), x + progressBarWidth - 72, y, 0xFFDCEEFF, false);

        for (int i = 1; i < summary.lines().size(); i++) {
            PreviewLine line = summary.lines().get(i);
            g.drawString(mc.font, line.text(), x, progressBarY + 12 + (i - 1) * lineHeight, line.color(), true);
        }

        if (!summary.materialLines().isEmpty()) {
            g.drawString(mc.font, "Remaining materials", x, materialStartY, 0xFFF3D486, false);
            for (int i = 0; i < summary.materialLines().size(); i++) {
                PreviewLine line = summary.materialLines().get(i);
                g.drawString(mc.font, line.text(), x + 4, materialStartY + 11 + i * 10, line.color(), false);
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
        lines.add(new PreviewLine(String.format(Locale.ROOT, "Size: %dx%dx%d", placement.bounds().width(), placement.bounds().height(), placement.bounds().depth()), 0xFF9ED5EA));
        if (missing + blocked > 0) {
            lines.add(new PreviewLine(String.format(Locale.ROOT, "Layer: %d / %d", analysis.currentLayerIndex(), analysis.totalLayers()), 0xFFFFE08A));
        } else {
            lines.add(new PreviewLine("Layer: complete", 0xFF8FD7B4));
        }
        lines.add(new PreviewLine(String.format(Locale.ROOT, "Blocks: %d  Matched: %d", total, matched), 0xFFD9E3EA));
        lines.add(new PreviewLine(String.format(Locale.ROOT, "Missing: %d  Blocked: %d", missing, blocked), blocked > 0 ? 0xFFFF8A8A : 0xFF7DE0FF));
        lines.add(new PreviewLine(String.format(Locale.ROOT, "Budget(est.): %d  Left: %d", totalBudget, remainingBudget), 0xFFF3D486));
        if (analysis.nextTarget() != null) {
            lines.add(new PreviewLine("Next: " + analysis.nextTarget().state().getBlock().getName().getString(), 0xFFFFE08A));
        }
        lines.add(new PreviewLine("RMB auto-build | Sprint+RMB assist", 0xFFB7C0CB));
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
                new PreviewLine(String.format(Locale.ROOT, "Size: %dx%dx%d", width, type.h(), depth), 0xFF9ED5EA),
                new PreviewLine("Preview fallback: bounds only", 0xFFE6B86A)
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
            return List.of(new PreviewLine("No remaining materials", 0xFF8FD7B4));
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
}
