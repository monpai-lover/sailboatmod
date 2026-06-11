package com.monpai.sailboatmod.client.renderer;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.item.SailboatItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class SailboatItemRenderer extends GeoItemRenderer<SailboatItem> {
    public SailboatItemRenderer() {
        super(new Model());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        poseStack.pushPose();
        if (displayContext == ItemDisplayContext.GUI) {
            poseStack.translate(0.5F, 0.5F, 0.5F);
            poseStack.scale(0.22F, 0.22F, 0.22F);
            poseStack.translate(-0.5F, -0.5F, -0.5F);
        } else if (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
            poseStack.translate(0.5F, 0.5F, 0.5F);
            poseStack.scale(0.12F, 0.12F, 0.12F);
            poseStack.translate(-0.5F, -0.5F, -0.5F);
        }
        super.renderByItem(stack, displayContext, poseStack, bufferSource, packedLight, packedOverlay);
        poseStack.popPose();
    }

    private static final class Model extends GeoModel<SailboatItem> {
        @Override
        public ResourceLocation getModelResource(SailboatItem animatable) {
            return new ResourceLocation(SailboatMod.MODID, "geo/sailboat.geo.json");
        }

        @Override
        public ResourceLocation getTextureResource(SailboatItem animatable) {
            return new ResourceLocation(SailboatMod.MODID, "textures/entity/sailboat.png");
        }

        @Override
        public ResourceLocation getAnimationResource(SailboatItem animatable) {
            return new ResourceLocation(SailboatMod.MODID, "animations/sailboat.animation.json");
        }
    }
}
