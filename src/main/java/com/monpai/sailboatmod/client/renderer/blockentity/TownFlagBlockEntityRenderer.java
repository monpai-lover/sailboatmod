package com.monpai.sailboatmod.client.renderer.blockentity;

import com.monpai.sailboatmod.block.TownFlagBlock;
import com.monpai.sailboatmod.block.WallTownFlagBlock;
import com.monpai.sailboatmod.block.entity.TownFlagBlockEntity;
import com.monpai.sailboatmod.client.texture.NationFlagTextureCache;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** 城镇旗渲染:薄壳,几何/动画全部委托 FlagRenderHelper(与国旗共用同一套,只是贴图来源不同)。 */
public class TownFlagBlockEntityRenderer implements BlockEntityRenderer<TownFlagBlockEntity> {
    private final ModelPart pole;
    private final ModelPart bar;

    public TownFlagBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart root = context.bakeLayer(ModelLayers.BANNER);
        this.pole = root.getChild("pole");
        this.bar = root.getChild("bar");
    }

    @Override
    public void render(TownFlagBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        boolean isWall = blockEntity.getBlockState().getBlock() instanceof WallTownFlagBlock;
        int light = blockEntity.getLevel() == null
                ? packedLight
                : LevelRenderer.getLightColor(blockEntity.getLevel(), blockEntity.getBlockPos().above());

        Direction facing;
        if (isWall) {
            facing = blockEntity.getBlockState().hasProperty(WallTownFlagBlock.FACING)
                    ? blockEntity.getBlockState().getValue(WallTownFlagBlock.FACING)
                    : Direction.NORTH;
        } else {
            facing = blockEntity.getBlockState().hasProperty(TownFlagBlock.FACING)
                    ? blockEntity.getBlockState().getValue(TownFlagBlock.FACING)
                    : Direction.NORTH;
        }

        ResourceLocation texture = NationFlagTextureCache.resolve(blockEntity.getFlagId(), blockEntity.getPrimaryColor(), blockEntity.getSecondaryColor(), blockEntity.isFlagMirrored());
        long gameTime = blockEntity.getLevel() == null ? 0L : blockEntity.getLevel().getGameTime();

        FlagRenderHelper.render(poseStack, bufferSource, pole, bar, light, packedOverlay, isWall, facing,
                blockEntity.getFlagWidth(), blockEntity.getFlagHeight(), texture,
                blockEntity.getBlockPos(), gameTime, partialTick);
    }
}
