package com.monpai.sailboatmod.client.renderer;

import com.monpai.sailboatmod.client.model.SailboatEntityModel;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class SailboatEntityRenderer extends GeoEntityRenderer<SailboatEntity> {
    private static final int ARRIVAL_HOLOGRAM_COLOR = 0xF8E7A0;
    private static final int ARRIVAL_HOLOGRAM_BACKGROUND = 0x66000000;
    private static final float ARRIVAL_HOLOGRAM_SCALE = 0.025F;

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

    @Override
    public void render(SailboatEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        renderArrivalHologram(entity, poseStack, bufferSource);
    }

    private void renderArrivalHologram(SailboatEntity entity, PoseStack poseStack, MultiBufferSource bufferSource) {
        if (entity.getArrivalNoticeTicks() <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        String stationName = entity.getArrivalNoticeStationName();
        String elapsedText = entity.getArrivalNoticeElapsedText();
        String dateText = entity.getArrivalNoticeDateText();
        String[] lines = new String[] {
                Component.translatable("entity.sailboatmod.sailboat.arrived").getString(),
                Component.translatable("entity.sailboatmod.sailboat.arrival.station", stationName == null || stationName.isBlank() ? "-" : stationName).getString(),
                Component.translatable("entity.sailboatmod.sailboat.arrival.elapsed", elapsedText == null || elapsedText.isBlank() ? "00:00" : elapsedText).getString(),
                Component.translatable("entity.sailboatmod.sailboat.arrival.date", dateText == null || dateText.isBlank() ? "-" : dateText).getString()
        };
        poseStack.pushPose();
        poseStack.translate(0.0D, entity.getBbHeight() + 1.15D, 0.0D);
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-ARRIVAL_HOLOGRAM_SCALE, -ARRIVAL_HOLOGRAM_SCALE, ARRIVAL_HOLOGRAM_SCALE);
        float lineHeight = 10.0F;
        float startY = -((lines.length - 1) * lineHeight) / 2.0F;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            float x = -font.width(line) / 2.0F;
            font.drawInBatch(line, x, startY + i * lineHeight, ARRIVAL_HOLOGRAM_COLOR, false, poseStack.last().pose(), bufferSource,
                    Font.DisplayMode.SEE_THROUGH, ARRIVAL_HOLOGRAM_BACKGROUND, 0xF000F0);
        }
        poseStack.popPose();
    }
}
