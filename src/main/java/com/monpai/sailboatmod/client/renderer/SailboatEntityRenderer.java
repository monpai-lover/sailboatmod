package com.monpai.sailboatmod.client.renderer;

import com.monpai.sailboatmod.client.model.SailboatEntityModel;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class SailboatEntityRenderer extends GeoEntityRenderer<SailboatEntity> {
    public SailboatEntityRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new SailboatEntityModel());
        this.shadowRadius = 0.9F;
    }

    @Override
    public void preRender(PoseStack poseStack, SailboatEntity animatable, software.bernie.geckolib.cache.object.BakedGeoModel model,
                          net.minecraft.client.renderer.MultiBufferSource bufferSource,
                          com.mojang.blaze3d.vertex.VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        poseStack.translate(0.0F, 0.35F, 0.0F);
        poseStack.scale(0.68F, 0.68F, 0.68F);
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    @Override
    protected void applyRotations(SailboatEntity entity, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick) {
        float yaw = entity.getViewYRot(partialTick);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
    }
}
