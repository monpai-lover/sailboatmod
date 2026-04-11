package com.monpai.sailboatmod.client.model;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.renderer.CarriageItemRenderer;
import com.monpai.sailboatmod.item.CarriageItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class CarriageItemModel extends GeoModel<CarriageItem> {
    @Override
    public ResourceLocation getModelResource(CarriageItem animatable) {
        return new ResourceLocation(SailboatMod.MODID, "geo/carriage.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(CarriageItem animatable) {
        return CarriageItem.getWoodType(CarriageItemRenderer.currentItemStack()).textureLocation();
    }

    @Override
    public ResourceLocation getAnimationResource(CarriageItem animatable) {
        return new ResourceLocation(SailboatMod.MODID, "animations/carriage.animation.json");
    }
}
