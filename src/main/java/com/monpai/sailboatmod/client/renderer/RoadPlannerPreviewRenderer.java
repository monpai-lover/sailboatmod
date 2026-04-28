package com.monpai.sailboatmod.client.renderer;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket;
import com.monpai.sailboatmod.registry.ModItems;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
        if (player == null || preview == null || (preview.ghostBlocks().isEmpty() && preview.pathNodes().isEmpty()) || !isHoldingPlanner(player)) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        Vec3 cameraPos = event.getCamera().getPosition();

        // Phase 1: Render translucent block models (before getting line consumer)
        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        // Wrap bufferSource to force all block renders through translucent render type
        MultiBufferSource translucentSource = renderType -> {
            // Redirect all render types to translucent so blocks get alpha blending
            return bufferSource.getBuffer(RenderType.translucent());
        };
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.4F);
        for (RoadPlannerClientHooks.PreviewGhostBlock block : preview.ghostBlocks()) {
            if (block == null || block.pos() == null || block.state() == null) {
                continue;
            }
            if (block.state().isAir()) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(
                    block.pos().getX() - cameraPos.x,
                    block.pos().getY() - cameraPos.y,
                    block.pos().getZ() - cameraPos.z
            );
            try {
                blockRenderer.renderSingleBlock(block.state(), poseStack, translucentSource,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            } catch (Exception ignored) {}
            poseStack.popPose();
        }
        // Flush translucent batch then restore state
        bufferSource.endBatch(RenderType.translucent());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();

        // Phase 2: Render line wireframes (get line consumer AFTER block rendering is done)
        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());

        for (RoadPlannerClientHooks.PreviewGhostBlock block : preview.ghostBlocks()) {
            if (block == null || block.pos() == null) {
                continue;
            }
            if (block.state() != null && block.state().isAir()) {
                continue;
            }
            renderBlockBox(
                    poseStack,
                    lineConsumer,
                    block.pos(),
                    cameraPos,
                    0.30F,
                    0.90F,
                    0.88F,
                    0.0F,
                    0.94F,
                    0.76F,
                    0.20F,
                    0.95F
            );
        }
        List<BlockPos> pathNodes = preview.pathNodes();
        List<RoadPlannerClientHooks.BridgeRange> bridgeRanges = preview.bridgeRanges();
        for (int i = 1; i < pathNodes.size(); i++) {
            BlockPos from = pathNodes.get(i - 1);
            BlockPos to = pathNodes.get(i);
            if (from == null || to == null) {
                continue;
            }
            boolean isBridge = isInBridgeRange(i - 1, bridgeRanges);
            float segR = isBridge ? 0.3F : 0.2F;
            float segG = isBridge ? 0.6F : 0.9F;
            float segB = isBridge ? 1.0F : 0.3F;
            renderPathSegmentBox(
                    poseStack,
                    lineConsumer,
                    from,
                    to,
                    cameraPos,
                    segR,
                    segG,
                    segB,
                    0.95F,
                    0.08D
            );
        }
        renderHighlightBox(poseStack, lineConsumer, preview.startHighlightPos(), cameraPos, 0.18F, 0.76F, 1.0F, 0.95F, 0.02F);
        renderHighlightBox(poseStack, lineConsumer, preview.endHighlightPos(), cameraPos, 1.0F, 0.72F, 0.25F, 0.95F, 0.02F);
        renderHighlightBox(poseStack, lineConsumer, preview.focusPos(), cameraPos, 0.94F, 0.76F, 0.20F, 0.98F, 0.06F);

        bufferSource.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        boolean holdingPlanner = isHoldingPlanner(player);
        RoadPlannerClientHooks.PlanningProgressState planning = RoadPlannerClientHooks.activePlanningProgress();
        if (!holdingPlanner && planning == null) {
            return;
        }

        RoadPlannerClientHooks.PreviewState preview = holdingPlanner ? RoadPlannerClientHooks.previewState() : null;
        List<RoadPlannerClientHooks.ProgressState> progressStates = holdingPlanner ? RoadPlannerClientHooks.activeProgress() : List.of();
        if (preview == null && progressStates.isEmpty() && planning == null) {
            return;
        }

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int x = 12;
        int y = 16;
        int width = 212;
        int height = preview != null ? 74 : 42;
        if (planning != null) {
            height += 30;
        }
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
                    Component.translatable("screen.sailboatmod.road_planner.overlay.length", preview.pathNodeCount()),
                    x + 10,
                    textY,
                    0xFFB7C8D6,
                    false
            );
            textY += 12;
            if (!preview.selectedOptionId().isBlank()) {
                String selectedLabel = preview.options().stream()
                        .filter(option -> option.optionId().equalsIgnoreCase(preview.selectedOptionId()))
                        .map(RoadPlannerClientHooks.PreviewOption::label)
                        .findFirst()
                        .orElse(preview.selectedOptionId());
                guiGraphics.drawString(
                        minecraft.font,
                        Component.translatable("screen.sailboatmod.road_planner.overlay.option", selectedLabel),
                        x + 10,
                        textY,
                        0xFFB7C8D6,
                        false
                );
                textY += 12;
            }
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
        if (planning != null) {
            guiGraphics.drawString(
                    minecraft.font,
                    planningHeadline(planning),
                    x + 10,
                    textY,
                    planningStatusColor(planning.status()),
                    false
            );
            textY += 12;
            drawProgressBar(guiGraphics, x + 10, textY, width - 20, 8, planning.displayPercent());
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

    private static boolean isInBridgeRange(int nodeIndex, List<RoadPlannerClientHooks.BridgeRange> bridgeRanges) {
        if (bridgeRanges == null || bridgeRanges.isEmpty()) {
            return false;
        }
        for (RoadPlannerClientHooks.BridgeRange range : bridgeRanges) {
            if (nodeIndex >= range.startIndex() && nodeIndex <= range.endIndex()) {
                return true;
            }
        }
        return false;
    }

    private static void renderBlockBox(PoseStack poseStack,
                                       VertexConsumer lineConsumer,
                                       BlockPos pos,
                                       Vec3 cameraPos,
                                       float fillR,
                                       float fillG,
                                       float fillB,
                                       float fillA,
                                       float lineR,
                                       float lineG,
                                       float lineB,
                                       float lineA) {
        PreviewBox box = previewBox(pos, cameraPos);
        LevelRenderer.renderLineBox(
                poseStack,
                lineConsumer,
                (float) box.minX(),
                (float) box.minY(),
                (float) box.minZ(),
                (float) box.maxX(),
                (float) box.maxY(),
                (float) box.maxZ(),
                lineR,
                lineG,
                lineB,
                lineA
        );
    }

    private static void renderHighlightBox(PoseStack poseStack,
                                           VertexConsumer lineConsumer,
                                           BlockPos pos,
                                           Vec3 cameraPos,
                                           float lineR,
                                           float lineG,
                                           float lineB,
                                           float lineA,
                                           float inset) {
        if (pos == null) {
            return;
        }
        PreviewBox box = highlightBox(pos, cameraPos, inset);
        LevelRenderer.renderLineBox(
                poseStack,
                lineConsumer,
                (float) box.minX(),
                (float) box.minY(),
                (float) box.minZ(),
                (float) box.maxX(),
                (float) box.maxY(),
                (float) box.maxZ(),
                lineR,
                lineG,
                lineB,
                lineA
        );
    }

    private static void renderPathSegmentBox(PoseStack poseStack,
                                             VertexConsumer lineConsumer,
                                             BlockPos from,
                                             BlockPos to,
                                             Vec3 cameraPos,
                                             float lineR,
                                             float lineG,
                                             float lineB,
                                             float lineA,
                                             double thickness) {
        PreviewBox box = pathSegmentBox(from, to, cameraPos, thickness);
        LevelRenderer.renderLineBox(
                poseStack,
                lineConsumer,
                (float) box.minX(),
                (float) box.minY(),
                (float) box.minZ(),
                (float) box.maxX(),
                (float) box.maxY(),
                (float) box.maxZ(),
                lineR,
                lineG,
                lineB,
                lineA
        );
    }

    private static PreviewBox previewBox(BlockPos pos, Vec3 cameraPos) {
        double minX = pos.getX() - cameraPos.x;
        double minY = pos.getY() - cameraPos.y;
        double minZ = pos.getZ() - cameraPos.z;
        return new PreviewBox(minX, minY, minZ, minX + 1.0D, minY + 1.0D, minZ + 1.0D);
    }

    private static PreviewBox highlightBox(BlockPos pos, Vec3 cameraPos, double inset) {
        PreviewBox base = previewBox(pos, cameraPos);
        return new PreviewBox(
                base.minX - inset,
                base.minY - inset,
                base.minZ - inset,
                base.maxX + inset,
                base.maxY + inset,
                base.maxZ + inset
        );
    }

    private static PreviewBox pathSegmentBox(BlockPos from, BlockPos to, Vec3 cameraPos, double thickness) {
        double fromX = from.getX() + 0.5D - cameraPos.x;
        double fromY = from.getY() + 0.5D - cameraPos.y;
        double fromZ = from.getZ() + 0.5D - cameraPos.z;
        double toX = to.getX() + 0.5D - cameraPos.x;
        double toY = to.getY() + 0.5D - cameraPos.y;
        double toZ = to.getZ() + 0.5D - cameraPos.z;
        return new PreviewBox(
                Math.min(fromX, toX) - thickness,
                Math.min(fromY, toY) - thickness,
                Math.min(fromZ, toZ) - thickness,
                Math.max(fromX, toX) + thickness,
                Math.max(fromY, toY) + thickness,
                Math.max(fromZ, toZ) + thickness
        );
    }

    static PreviewBox previewBoxForTest(BlockPos pos, Vec3 cameraPos) {
        return previewBox(pos, cameraPos);
    }

    static PreviewBox highlightBoxForTest(BlockPos pos, Vec3 cameraPos, double inset) {
        return highlightBox(pos, cameraPos, inset);
    }

    static PreviewBox pathSegmentBoxForTest(BlockPos from, BlockPos to, Vec3 cameraPos, double thickness) {
        return pathSegmentBox(from, to, cameraPos, thickness);
    }

    static boolean rendersFilledBoxesForTest() {
        return false;
    }

    static Component planningHeadlineForTest(RoadPlannerClientHooks.PlanningProgressState state) {
        return planningHeadline(state);
    }

    static int planningStatusColorForTest(SyncManualRoadPlanningProgressPacket.Status status) {
        return planningStatusColor(status);
    }

    private static Component planningHeadline(RoadPlannerClientHooks.PlanningProgressState state) {
        if (state == null) {
            return Component.empty();
        }
        return Component.literal("道路规划中: " + state.stageLabel() + " " + state.displayPercent() + "%");
    }

    private static int planningStatusColor(SyncManualRoadPlanningProgressPacket.Status status) {
        if (status == null) {
            return 0xFFE6EEF5;
        }
        return switch (status) {
            case SUCCESS -> 0xFF98D8AA;
            case FAILED, CANCELLED -> 0xFFF08A8A;
            case RUNNING -> 0xFFE6EEF5;
        };
    }

    private static void drawProgressBar(GuiGraphics guiGraphics, int x, int y, int width, int height, int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        int fillWidth = Math.max(0, Math.min(width, (int) Math.floor(width * (clamped / 100.0D))));
        guiGraphics.fill(x, y, x + width, y + height, 0xAA12181F);
        guiGraphics.fill(x, y, x + fillWidth, y + height, 0xFF6CB6FF);
        guiGraphics.fill(x, y, x + width, y + 1, 0x55FFFFFF);
    }

    record PreviewBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }
}
