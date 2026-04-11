package com.monpai.sailboatmod.client.model;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.entity.CarriageEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class CarriageEntityModel extends GeoModel<CarriageEntity> {
    @Override
    public ResourceLocation getModelResource(CarriageEntity animatable) {
        return new ResourceLocation(SailboatMod.MODID, "geo/carriage.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(CarriageEntity animatable) {
        return animatable.getWoodType().textureLocation();
    }

    @Override
    public ResourceLocation getAnimationResource(CarriageEntity animatable) {
        return new ResourceLocation(SailboatMod.MODID, "animations/carriage.animation.json");
    }
}
