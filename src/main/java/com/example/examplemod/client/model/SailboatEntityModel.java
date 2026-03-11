package com.example.examplemod.client.model;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.entity.SailboatEntity;
import net.minecraft.resources.ResourceLocation;
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
}

