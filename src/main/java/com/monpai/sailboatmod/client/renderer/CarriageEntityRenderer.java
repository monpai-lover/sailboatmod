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
    private static final float HORSE_TURN_SMOOTH_ALPHA = 0.18F; // 马转向角每帧逼近目标的比例(越大越跟手,越小越平滑)
    private static final float HORSE_TURN_GAIN = 1.0F;          // 马转向幅度增益(1=直接用马车转向角;游戏内觉得转太多/少改此值)

    private final HorseModel<Horse> horseModel;
    @Nullable
    private Horse renderHorse;
    private float smoothedHorseTurn = 0.0F; // 马转向角平滑值(度),逼近 entity.getRenderTurnAngle() 防突变抖动

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
        // 2026-06 马腿幅度用「服务端同步的真实行驶速度」(getCurrentSpeedForHud,= DATA_CURRENT_SPEED entityData 同步,
        // 与 HUD 同源)。不用 getDeltaMovement(≈0,服务端权威)、也不用 xo/zo(lerp 插值步进位移偏小,马腿仍不动)。
        // currentSpeed 单位 m/s(0~10.5),巡航几 m/s,×0.15 让巡航时腿摆幅度≈0.5~1.0。
        double speed = Math.abs(entity.getCurrentSpeedForHud());
        float limbSwingAmount = Mth.clamp((float) (speed * 0.15D), 0.0F, 1.15F);
        float limbSwing = animationTime * (0.8F + limbSwingAmount * 2.2F);
        // 2026-06 诊断马腿不动:打实际 speed/limbSwingAmount(每~1.5s 一次)。确认 speed 有没有值。
        if (entity.tickCount % 30 == 0) {
            org.slf4j.LoggerFactory.getLogger("CarriageHorseAnim").info(
                    "[马腿诊断] currentSpeedForHud={} deltaMove={} limbSwingAmount={} limbSwing={}",
                    String.format(java.util.Locale.ROOT, "%.3f", entity.getCurrentSpeedForHud()),
                    String.format(java.util.Locale.ROOT, "%.4f", entity.getDeltaMovement().horizontalDistance()),
                    String.format(java.util.Locale.ROOT, "%.3f", limbSwingAmount),
                    String.format(java.util.Locale.ROOT, "%.1f", limbSwing));
        }

        horse.setYRot(0.0F);
        horse.setYBodyRot(0.0F);
        horse.yBodyRotO = 0.0F;
        horse.yHeadRot = 0.0F;
        horse.yHeadRotO = 0.0F;
        horse.setXRot(0.0F);
        horse.xRotO = 0.0F;

        double bob = Mth.sin(animationTime * 0.34F) * 0.02F * limbSwingAmount;
        CarriageVisualRig.HorseAttachmentPose attachment = CarriageVisualRig.horseAttachmentPose(yaw, bob);

        // 马整体跟转向方向偏转(同 MrCrayfish 前轮转向):平滑逼近马车转向角,左/右打方向马朝对应方向转。
        float targetTurn = entity.getRenderTurnAngle();
        smoothedHorseTurn += (targetTurn - smoothedHorseTurn) * HORSE_TURN_SMOOTH_ALPHA;

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(CarriageVisualRig.horseRootYawRotation(yaw)));
        poseStack.translate(attachment.localOffset().x, attachment.localOffset().y, attachment.localOffset().z);
        // 在马自身坐标系绕 Y 轴加转向偏转(translate 到马位置后再转,马绕自身中心转向不漂移)。
        poseStack.mulPose(Axis.YP.rotationDegrees(smoothedHorseTurn * HORSE_TURN_GAIN));
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
