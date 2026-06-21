package com.monpai.sailboatmod.client.renderer;

import com.monpai.sailboatmod.client.model.CarriageEntityModel;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Horse;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class CarriageEntityRenderer extends GeoEntityRenderer<CarriageEntity> {
    private static final ResourceLocation HORSE_TEXTURE = new ResourceLocation("textures/entity/horse/horse_brown.png");
    private static final int ARRIVAL_HOLOGRAM_COLOR = 0xF8E7A0;
    private static final int ARRIVAL_HOLOGRAM_BACKGROUND = 0x66000000;
    private static final float ARRIVAL_HOLOGRAM_SCALE = 0.025F;

    private final HorseModel<Horse> horseModel;
    @Nullable
    private Horse renderHorse;

    public CarriageEntityRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new CarriageEntityModel());
        this.shadowRadius = 0.9F;
        this.horseModel = new HorseModel<>(renderManager.bakeLayer(ModelLayers.HORSE));
    }

    @Override
    public void preRender(PoseStack poseStack, CarriageEntity animatable, software.bernie.geckolib.cache.object.BakedGeoModel model,
                          net.minecraft.client.renderer.MultiBufferSource bufferSource,
                          com.mojang.blaze3d.vertex.VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        poseStack.translate(0.0F, CarriageVisualRig.carriageModelYOffset(), 0.0F);
        float modelScale = CarriageVisualRig.carriageModelScale();
        poseStack.scale(modelScale, modelScale, modelScale);
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    @Override
    protected void applyRotations(CarriageEntity entity, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick) {
        float yaw = entity.getViewYRot(partialTick);
        poseStack.mulPose(Axis.YP.rotationDegrees(CarriageVisualRig.localYawRotation(yaw)));
    }

    @Override
    public void render(CarriageEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        renderAttachedHorse(entity, partialTick, poseStack, bufferSource, packedLight);
        renderArrivalHologram(entity, poseStack, bufferSource);
    }

    private void renderArrivalHologram(CarriageEntity entity, PoseStack poseStack, MultiBufferSource bufferSource) {
        if (entity.getArrivalNoticeTicks() <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        String stationName = entity.getArrivalNoticeStationName();
        String elapsedText = entity.getArrivalNoticeElapsedText();
        String dateText = entity.getArrivalNoticeDateText();
        String[] lines = new String[] {
                Component.translatable("entity.sailboatmod.carriage.arrived").getString(),
                Component.translatable("entity.sailboatmod.carriage.arrival.station", stationName == null || stationName.isBlank() ? "-" : stationName).getString(),
                Component.translatable("entity.sailboatmod.carriage.arrival.elapsed", elapsedText == null || elapsedText.isBlank() ? "00:00" : elapsedText).getString(),
                Component.translatable("entity.sailboatmod.carriage.arrival.date", dateText == null || dateText.isBlank() ? "-" : dateText).getString()
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

    private void renderAttachedHorse(CarriageEntity entity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        Horse horse = getOrCreateRenderHorse();
        if (horse == null) {
            return;
        }

        float yaw = entity.getViewYRot(partialTick);
        float animationTime = entity.tickCount + partialTick;
        // 2026-06 马腿幅度改用「客户端真实每 tick 位移」算,不用 getDeltaMovement():自动驾驶马车服务端权威 + lerp 位置同步,
        // 客户端 deltaMovement≈0 → 旧算法马腿幅度≈0 → 马只待机不跑。xo/zo 是 vanilla 上一 tick 位置(super.tick 更新,
        // lerp 同步的位移也算进去),差值=真实位移,准确反映行驶速度。
        double dx = entity.getX() - entity.xo;
        double dz = entity.getZ() - entity.zo;
        double speed = Math.sqrt(dx * dx + dz * dz);
        float limbSwingAmount = Mth.clamp((float) (speed * 8.0D), 0.0F, 1.15F);
        float limbSwing = animationTime * (0.8F + limbSwingAmount * 2.2F);

        horse.setYRot(0.0F);
        horse.setYBodyRot(0.0F);
        horse.yBodyRotO = 0.0F;
        horse.yHeadRot = 0.0F;
        horse.yHeadRotO = 0.0F;
        horse.setXRot(0.0F);
        horse.xRotO = 0.0F;

        double bob = Mth.sin(animationTime * 0.34F) * 0.02F * limbSwingAmount;
        CarriageVisualRig.HorseAttachmentPose attachment = CarriageVisualRig.horseAttachmentPose(yaw, bob);

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(CarriageVisualRig.horseRootYawRotation(yaw)));
        poseStack.translate(attachment.localOffset().x, attachment.localOffset().y, attachment.localOffset().z);
        poseStack.scale(-attachment.scale(), -attachment.scale(), attachment.scale());

        horseModel.prepareMobModel(horse, limbSwing, limbSwingAmount, partialTick);
        horseModel.setupAnim(horse, limbSwing, limbSwingAmount, animationTime, 0.0F, 0.0F);
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(HORSE_TEXTURE));
        horseModel.renderToBuffer(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        poseStack.popPose();
    }

    @Nullable
    private Horse getOrCreateRenderHorse() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        if (renderHorse == null || renderHorse.level() != minecraft.level) {
            renderHorse = new Horse(EntityType.HORSE, minecraft.level);
        }
        return renderHorse;
    }
}
