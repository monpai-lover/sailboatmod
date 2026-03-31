package com.monpai.sailboatmod.client.renderer.resident;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class ResidentEntityRenderer extends HumanoidMobRenderer<ResidentEntity, HumanoidModel<ResidentEntity>> {
    private static final ResourceLocation DEFAULT_SKIN =
            new ResourceLocation(SailboatMod.MODID, "textures/entity/resident/default.png");

    public ResidentEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(ResidentEntity entity) {
        // TODO: resolve per-NPC skin from skinHash once skin cache is implemented
        return DEFAULT_SKIN;
    }
}
