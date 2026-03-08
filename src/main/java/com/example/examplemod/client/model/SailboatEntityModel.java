package com.example.examplemod.client.model;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.entity.SailboatEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

public class SailboatEntityModel extends GeoModel<SailboatEntity> {
    @Override
    public ResourceLocation getModelResource(SailboatEntity animatable) {
        return new ResourceLocation(SailboatMod.MODID, "geo/sailboat.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(SailboatEntity animatable) {
        return new ResourceLocation(SailboatMod.MODID, "textures/entity/sailboat.png");
    }

    @Override
    public ResourceLocation getAnimationResource(SailboatEntity animatable) {
        return new ResourceLocation(SailboatMod.MODID, "animations/sailboat.animation.json");
    }

    @Override
    public void setCustomAnimations(SailboatEntity animatable, long instanceId, AnimationState<SailboatEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        float deploy = animatable.getSailDeployProgress((float) animationState.getPartialTick());
        float foldedRad = (float) Math.toRadians(-78.0D);
        float targetRotX = foldedRad * (1.0F - deploy);

        rotateIfPresent("bone12", targetRotX);
        rotateIfPresent("bone13", targetRotX * 0.6F);
        rotateIfPresent("bone16", targetRotX);
        rotateIfPresent("bone17", targetRotX * 0.6F);
    }

    private void rotateIfPresent(String boneName, float rotX) {
        CoreGeoBone bone = getAnimationProcessor().getBone(boneName);
        if (bone != null) {
            bone.setRotX(rotX);
        }
    }
}

