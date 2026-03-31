package com.monpai.sailboatmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager.StructureType;
import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BankConstructorPreviewRenderer {
    private static final Map<String, List<StructureTemplate.StructureBlockInfo>> BLOCK_CACHE = new HashMap<>();
    private static String lastCachedType = "";

    private BankConstructorPreviewRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        ItemStack held = player.getMainHandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get()) ? player.getMainHandItem()
                : player.getOffhandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get()) ? player.getOffhandItem() : ItemStack.EMPTY;
        if (held.isEmpty()) return;

        BlockHitResult hit = player.level().clip(new ClipContext(
                player.getEyePosition(event.getPartialTick()),
                player.getEyePosition(event.getPartialTick()).add(player.getViewVector(event.getPartialTick()).scale(5.0D)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return;

        StructureType type = BankConstructorItem.getSelectedType(held);
        BlockPos origin = hit.getBlockPos().above();
        Vec3 cam = event.getCamera().getPosition();

        List<StructureTemplate.StructureBlockInfo> blocks = getCachedBlocks(type);
        if (blocks == null || blocks.isEmpty()) {
            // Fallback: wireframe
            renderWireframe(event, type, origin, cam);
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        poseStack.pushPose();
        poseStack.translate(origin.getX() - cam.x, origin.getY() - cam.y, origin.getZ() - cam.z);

        // Render each block as semi-transparent
        VertexConsumer vc = bufferSource.getBuffer(RenderType.translucent());
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockState state = info.state();
            if (state.isAir()) continue;
            poseStack.pushPose();
            poseStack.translate(info.pos().getX(), info.pos().getY(), info.pos().getZ());
            try {
                blockRenderer.renderSingleBlock(state, poseStack, bufferSource, 0xF000F0, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            } catch (Exception ignored) {}
            poseStack.popPose();
        }

        // Draw wireframe outline on top
        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        org.joml.Vector3f normal = new org.joml.Vector3f(0, 1, 0);
        float w = type.w(), h = type.h(), d = type.d();
        float r = 0.2F, g = 0.8F, b = 1.0F, a = 0.6F;
        line(lineVc, matrix, normal, 0, 0, 0, w, 0, 0, r, g, b, a);
        line(lineVc, matrix, normal, w, 0, 0, w, 0, d, r, g, b, a);
        line(lineVc, matrix, normal, w, 0, d, 0, 0, d, r, g, b, a);
        line(lineVc, matrix, normal, 0, 0, d, 0, 0, 0, r, g, b, a);
        line(lineVc, matrix, normal, 0, h, 0, w, h, 0, r, g, b, a);
        line(lineVc, matrix, normal, w, h, 0, w, h, d, r, g, b, a);
        line(lineVc, matrix, normal, w, h, d, 0, h, d, r, g, b, a);
        line(lineVc, matrix, normal, 0, h, d, 0, h, 0, r, g, b, a);
        line(lineVc, matrix, normal, 0, 0, 0, 0, h, 0, r, g, b, a);
        line(lineVc, matrix, normal, w, 0, 0, w, h, 0, r, g, b, a);
        line(lineVc, matrix, normal, w, 0, d, w, h, d, r, g, b, a);
        line(lineVc, matrix, normal, 0, 0, d, 0, h, d, r, g, b, a);

        bufferSource.endBatch(RenderType.lines());
        bufferSource.endBatch(RenderType.translucent());
        poseStack.popPose();
    }

    private static void renderWireframe(RenderLevelStageEvent event, StructureType type, BlockPos origin, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(origin.getX() - cam.x, origin.getY() - cam.y, origin.getZ() - cam.z);
        VertexConsumer vc = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        org.joml.Vector3f normal = new org.joml.Vector3f(0, 1, 0);
        float w = type.w(), h = type.h(), d = type.d();
        float r = 0.0F, g = 1.0F, b = 0.0F, a = 0.8F;
        line(vc, matrix, normal, 0, 0, 0, w, 0, 0, r, g, b, a);
        line(vc, matrix, normal, w, 0, 0, w, 0, d, r, g, b, a);
        line(vc, matrix, normal, w, 0, d, 0, 0, d, r, g, b, a);
        line(vc, matrix, normal, 0, 0, d, 0, 0, 0, r, g, b, a);
        line(vc, matrix, normal, 0, h, 0, w, h, 0, r, g, b, a);
        line(vc, matrix, normal, w, h, 0, w, h, d, r, g, b, a);
        line(vc, matrix, normal, w, h, d, 0, h, d, r, g, b, a);
        line(vc, matrix, normal, 0, h, d, 0, h, 0, r, g, b, a);
        line(vc, matrix, normal, 0, 0, 0, 0, h, 0, r, g, b, a);
        line(vc, matrix, normal, w, 0, 0, w, h, 0, r, g, b, a);
        line(vc, matrix, normal, w, 0, d, w, h, d, r, g, b, a);
        line(vc, matrix, normal, 0, 0, d, 0, h, d, r, g, b, a);
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static List<StructureTemplate.StructureBlockInfo> getCachedBlocks(StructureType type) {
        if (BLOCK_CACHE.containsKey(type.nbtName())) return BLOCK_CACHE.get(type.nbtName());
        try {
            String path = "/data/" + SailboatMod.MODID + "/structures/" + type.nbtName() + ".nbt";
            InputStream is = BankConstructorPreviewRenderer.class.getResourceAsStream(path);
            if (is == null) is = Thread.currentThread().getContextClassLoader().getResourceAsStream("data/" + SailboatMod.MODID + "/structures/" + type.nbtName() + ".nbt");
            if (is == null) { BLOCK_CACHE.put(type.nbtName(), List.of()); return List.of(); }
            CompoundTag tag = NbtIo.readCompressed(is);
            is.close();
            StructureTemplate template = new StructureTemplate();
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) { BLOCK_CACHE.put(type.nbtName(), List.of()); return List.of(); }
            template.load(mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
            StructurePlaceSettings settings = new StructurePlaceSettings();
            // Collect all non-air blocks from the template
            List<StructureTemplate.StructureBlockInfo> allBlocks = new java.util.ArrayList<>();
            net.minecraft.core.Vec3i size = template.getSize();
            // Use filterBlocks for each block we find
            // Simpler: iterate all positions and check template palette
            for (net.minecraft.world.level.block.Block candidate : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                List<StructureTemplate.StructureBlockInfo> found = template.filterBlocks(BlockPos.ZERO, settings, candidate, false);
                for (StructureTemplate.StructureBlockInfo info : found) {
                    if (!info.state().isAir() && !info.state().is(Blocks.STRUCTURE_VOID)) {
                        allBlocks.add(info);
                    }
                }
                if (allBlocks.size() > 5000) break; // Safety limit
            }
            BLOCK_CACHE.put(type.nbtName(), allBlocks);
            return allBlocks;
        } catch (Exception e) {
            BLOCK_CACHE.put(type.nbtName(), List.of());
            return List.of();
        }
    }

    private static void line(VertexConsumer vc, Matrix4f matrix, org.joml.Vector3f normal,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        vc.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z()).endVertex();
        vc.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z()).endVertex();
    }
}
