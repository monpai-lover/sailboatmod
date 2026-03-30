package com.monpai.sailboatmod.client.model;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.entity.SailboatEntity;
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

