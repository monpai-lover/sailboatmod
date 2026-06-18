package com.monpai.sailboatmod.client.renderer.blockentity;

import com.monpai.sailboatmod.block.NationFlagBlock;
import com.monpai.sailboatmod.block.WallNationFlagBlock;
import com.monpai.sailboatmod.block.entity.NationFlagBlockEntity;
import com.monpai.sailboatmod.client.texture.NationFlagTextureCache;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** 国旗渲染:薄壳,几何/动画全部委托 FlagRenderHelper(与城镇旗共用同一套,只是贴图来源不同)。 */
public class NationFlagBlockEntityRenderer implements BlockEntityRenderer<NationFlagBlockEntity> {
    public NationFlagBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        // 不再 bakeLayer(ModelLayers.BANNER):FlagRenderHelper 已全自画顶点,不用 vanilla banner ModelPart。
        // 真因修复:此前构造里 bakeLayer 一旦在客户端抛异常,整个 BER 实例化失败 → 旗帜方块完全不渲染
        // (只剩选择框 outline,无模型无贴图),而城镇核心等不依赖 bakeLayer 的 BER 正常。
    }

    @Override
    public void render(NationFlagBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        boolean isWall = blockEntity.getBlockState().getBlock() instanceof WallNationFlagBlock;
        int light = blockEntity.getLevel() == null
                ? packedLight
                : LevelRenderer.getLightColor(blockEntity.getLevel(), blockEntity.getBlockPos().above());

        Direction facing;
        if (isWall) {
            facing = blockEntity.getBlockState().hasProperty(WallNationFlagBlock.FACING)
                    ? blockEntity.getBlockState().getValue(WallNationFlagBlock.FACING)
                    : Direction.NORTH;
        } else {
            facing = blockEntity.getBlockState().hasProperty(NationFlagBlock.FACING)
                    ? blockEntity.getBlockState().getValue(NationFlagBlock.FACING)
                    : Direction.NORTH;
        }

        ResourceLocation texture = NationFlagTextureCache.resolve(blockEntity.getFlagId(), blockEntity.getPrimaryColor(), blockEntity.getSecondaryColor(), blockEntity.isFlagMirrored());
        long gameTime = blockEntity.getLevel() == null ? 0L : blockEntity.getLevel().getGameTime();

        FlagRenderHelper.render(poseStack, bufferSource, light, packedOverlay, isWall, facing,
                blockEntity.getFlagWidth(), blockEntity.getFlagHeight(), texture,
                blockEntity.getBlockPos(), gameTime, partialTick);
    }
}
