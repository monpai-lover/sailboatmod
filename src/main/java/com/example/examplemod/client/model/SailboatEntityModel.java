package com.example.examplemod.client.model;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.entity.SailboatEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.state.BoneSnapshot;
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
        float deployProgress = animatable.getSailDeployProgress((float)animationState.getPartialTick());
        // The authored keyframes represent stowing the sail; deployment reuses them in reverse.
        float stowProgress = 1.0F - deployProgress;

        applyTransform("bone12", 0.0F, 73.0F * stowProgress, 0.0F, 0.0F, 1.0F, 0.9F * stowProgress, 1.0F);
        applyTransform(
                "bone13",
                0.0F,
                0.0F,
                0.0F,
                (float)Math.toRadians(12.5F * stowProgress),
                1.0F,
                1.0F - (0.8F * stowProgress),
                1.0F
        );
        applyTransform("bone16", 0.0F, 66.0F * stowProgress, 0.0F, 0.0F, 1.0F, 1.0F - (0.8F * stowProgress), 1.0F);
    }

    private void applyTransform(String boneName, float posX, float posY, float posZ, float rotX, float scaleX, float scaleY, float scaleZ) {
        CoreGeoBone bone = getAnimationProcessor().getBone(boneName);
        if (bone != null) {
            BoneSnapshot initial = bone.getInitialSnapshot();
            bone.setPosX(initial.getOffsetX() + posX);
            bone.setPosY(initial.getOffsetY() + posY);
            bone.setPosZ(initial.getOffsetZ() + posZ);
            bone.setRotX(initial.getRotX() + rotX);
            bone.setScaleX(initial.getScaleX() * scaleX);
            bone.setScaleY(initial.getScaleY() * scaleY);
            bone.setScaleZ(initial.getScaleZ() * scaleZ);
        }
    }
}

