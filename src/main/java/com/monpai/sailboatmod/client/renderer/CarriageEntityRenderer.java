package com.monpai.sailboatmod.client.renderer;

import com.monpai.sailboatmod.client.model.CarriageEntityModel;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Horse;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class CarriageEntityRenderer extends GeoEntityRenderer<CarriageEntity> {
    private static final int ARRIVAL_HOLOGRAM_COLOR = 0xF8E7A0;
    private static final int ARRIVAL_HOLOGRAM_BACKGROUND = 0x66000000;
    private static final float ARRIVAL_HOLOGRAM_SCALE = 0.025F;
    private static final float HORSE_TURN_SMOOTH_ALPHA = 0.18F; // 马转向角每帧逼近目标的比例(越大越跟手,越小越平滑)
    private static final float HORSE_TURN_GAIN = 1.0F;          // 马转向幅度增益(1=直接用马车转向角;游戏内觉得转太多/少改此值)

    @Nullable
    private Horse renderHorse;
    private float smoothedHorseTurn = 0.0F; // 马转向角平滑值(度),逼近 entity.getRenderTurnAngle() 防突变抖动

    public CarriageEntityRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new CarriageEntityModel());
        this.shadowRadius = 0.9F;
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
        // 马腿摆幅用「服务端同步的真实行驶速度」(getCurrentSpeedForHud,= DATA_CURRENT_SPEED entityData 同步,与 HUD 同源)。
        // 不用 getDeltaMovement(≈0,服务端权威)、也不用 xo/zo(lerp 步进偏小)。currentSpeed m/s(0~10.5),×0.15 让巡航腿摆≈0.5~1.0。
        double speed = Math.abs(entity.getCurrentSpeedForHud());
        float limbSwingAmount = Mth.clamp((float) (speed * 0.15D), 0.0F, 1.15F);

        // 2026-06 关键改造:改用 EntityRenderDispatcher.render 渲染这匹临时马,而非直接 horseModel.setupAnim。
        // 原因:Fresh Animations + EMF(entity_model_features) 通过 mixin 钩在 EntityRenderDispatcher.render HEAD 捕获
        // 当前渲染实体上下文,并从 entity.walkAnimation 读腿摆相位。手画马绕过 dispatcher → EMF 没捕获到它 → 一直播待机。
        // 走 dispatcher.render 后 EMF 才会把这匹马设为 current entity、读它的 walkAnimation,播跑动动画。
        // walkAnimation 同时驱动 vanilla HorseModel 和 FA(都读同一个),所以喂真实速度一处即可。
        horse.walkAnimation.update(limbSwingAmount, 1.0F);

        // 马朝向交给 yBodyRot(dispatcher 内部 setupRotations 做 180-yBodyRot,等价原 horseRootYawRotation)。
        // 转向偏转(smoothedHorseTurn)并入 yBodyRot:马整体跟转向方向偏(同 MrCrayfish 前轮),左/右打方向马朝对应方向转。
        float targetTurn = entity.getRenderTurnAngle();
        smoothedHorseTurn += (targetTurn - smoothedHorseTurn) * HORSE_TURN_SMOOTH_ALPHA;
        float bodyYaw = yaw - smoothedHorseTurn * HORSE_TURN_GAIN;
        horse.setYRot(bodyYaw);
        horse.yRotO = bodyYaw;
        horse.setYBodyRot(bodyYaw);
        horse.yBodyRotO = bodyYaw;
        horse.yHeadRot = bodyYaw;
        horse.yHeadRotO = bodyYaw;
        horse.setXRot(0.0F);
        horse.xRotO = 0.0F;

        // 马在车头前方的世界偏移(已按马朝向旋转好的世界轴向量,直接 translate,不再手动 mulPose 朝向——朝向交给 yBodyRot)。
        double bob = Mth.sin(animationTime * 0.34F) * 0.02F * limbSwingAmount;
        net.minecraft.world.phys.Vec3 worldOffset = CarriageVisualRig.renderedHorseWorldOffset(yaw, bob);
        float scale = CarriageVisualRig.horseModelScale();

        poseStack.pushPose();
        poseStack.translate(worldOffset.x, worldOffset.y, worldOffset.z);
        // 缩放正值:模型上下翻转交给 dispatcher 内部 LivingEntityRenderer 的 scale(-1,-1,1),这里别再带负号(否则马颠倒)。
        poseStack.scale(scale, scale, scale);

        // 关阴影/名牌:车自己有阴影,马只渲染模型本体。try/finally 还原,别污染 dispatcher 全局状态。
        net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher =
                Minecraft.getInstance().getEntityRenderDispatcher();
        // shouldRenderShadow 是 private 读不到;dispatcher 渲染每个实体时会自行控制此标志,渲染后还原 true 安全。
        dispatcher.setRenderShadow(false);
        try {
            // x/y/z=0:马位置已由上面 translate 摆好;rotationYaw=0:朝向已用 yBodyRot 设好,别再传 yaw(双重旋转)。
            dispatcher.render(horse, 0.0D, 0.0D, 0.0D, 0.0F, partialTick, poseStack, bufferSource, packedLight);
        } finally {
            dispatcher.setRenderShadow(true);
        }
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
            applyBrownVariant(renderHorse);
        }
        return renderHorse;
    }

    /**
     * 固定马毛色为棕色(原手画路径用 horse_brown.png)。dispatcher 渲染读马自身变体,默认随机/白。
     * Horse.setTypeVariant 是 private 且 reobf 后混淆名会变(反射不稳),改用 public 的 Entity.load(NBT):
     * Horse.readAdditionalSaveData 读 "Variant" int = base(BROWN=2) | (markings(NONE=0)<<8) = 2。
     * NBT 字段名 "Variant" 是存档格式常量,不随混淆变,稳定。
     */
    private static void applyBrownVariant(Horse horse) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        horse.saveWithoutId(tag); // 先存全量,只覆盖 Variant,避免缺字段读 NBT 时异常。
        tag.putInt("Variant", 2);  // BROWN base, NONE markings
        horse.load(tag);
    }
}
