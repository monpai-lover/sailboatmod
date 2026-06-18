package com.monpai.sailboatmod.client.renderer.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 旗帜渲染共享逻辑(国旗/城镇旗共用)——<b>照搬 vanilla BannerRenderer 的几何与变换</b>,几何稳定正确。
 *
 * <p><b>2026-06 重做</b>:之前两版(依赖 ModelLayers.BANNER 的 base.png、以及自画顶点)都失败——
 * 旗帜只剩选择框 / 旗面尖刺 / 墙旗浮空。现在:
 * <ul>
 *   <li>自己用 {@link LayerDefinition} 手动 bake pole/bar/flag 三个 ModelPart(不依赖 vanilla layer 是否注册,
 *       避免 bakeLayer 抛异常令整个 BER 挂掉)。</li>
 *   <li>flag ModelPart 的 UV 铺满整张贴图([0,1]),所以自定义旗帜图案能完整显示(vanilla flag UV 只映射
 *       banner atlas 一角,直接用会只显示左上)。</li>
 *   <li>变换严格照 vanilla BannerRenderer:translate→旋转→scale(2/3,-2/3,-2/3);站立画旗杆、墙挂不画+贴墙偏移。</li>
 *   <li>飘动用 vanilla 方式:flag.xRot 随时间 cos 摆动。</li>
 * </ul>
 */
public final class FlagRenderHelper {
    private static final ResourceLocation POLE_TEXTURE = new ResourceLocation("minecraft", "textures/block/oak_planks.png");

    // 旗面像素尺寸(贴图坐标 64×64,flag cube 用 20×40,但我们让 UV 铺满 → 见 buildMesh)。
    private static final int TEX_W = 64;
    private static final int TEX_H = 64;

    // 旗面几何。关键:ModelPart.render 内部把 addBox 的「像素」坐标除以 16 进 model 空间;自画顶点<b>不会</b>
    // 自动 /16 → 必须用已 /16 的 model 空间值。以下照搬「老旗帜」(改造前能用版)的面积守恒几何 + 小振幅飘动。
    private static final float PX = 1.0F / 16.0F;
    private static final float BASE_FLAG_WIDTH = 20.0F * PX;
    private static final float BASE_FLAG_HEIGHT = 40.0F * PX;
    private static final float FLAG_AREA = BASE_FLAG_WIDTH * BASE_FLAG_HEIGHT;
    // 比例夹取范围照搬「老旗帜」(NationFlagBlockEntityRenderer 改造前):MAX_WIDTH=44。
    // 之前误把 MAX_WIDTH 收窄到 24,导致竖图/横图触发宽度上限误夹 → h=AREA/w 反算扭曲比例。
    private static final float MIN_FLAG_WIDTH = 14.0F * PX;
    private static final float MAX_FLAG_WIDTH = 44.0F * PX;
    private static final float MIN_FLAG_HEIGHT = 14.0F * PX;
    private static final float MAX_FLAG_HEIGHT = 40.0F * PX;
    private static final float FLAG_TOP = 0.0F;
    // 旗面在旗杆「前侧」(z 负向),旗杆在 z∈[-1px,1px];旗面在 z<-1px,小幅飘动不会穿到旗杆。单层(NoCull 正反可见)。
    private static final float FLAG_FRONT_Z = -1.5F * PX;
    private static final float FLAG_OFFSET_Y = -32.0F * PX;   // 挂到横杆下(model 空间)
    private static final int WAVE_SEGMENTS = 5;
    // 飘动时间取模周期:把 long gameTime 压回 float 精度安全区间(< 2^24),防老存档大 gameTime 精度丢失致动画冻结。
    private static final long WAVE_TIME_MODULO = 1_000_000L;
    // 小振幅前后飘动(老旗帜的感觉):0.025+0.012 model 空间 ≈ 0.6px,左右/前后轻晃,绝不穿旗杆。
    private static final float PRIMARY_WAVE_SPEED = 0.05F;
    private static final float SECONDARY_WAVE_SPEED = 0.13F;
    private static final float PRIMARY_AMPLITUDE = 0.025F;
    private static final float SECONDARY_AMPLITUDE = 0.012F;
    private static final float SEGMENT_PHASE_OFFSET = 0.6F;

    // 手动 bake 的旗杆/横杆 ModelPart(进程级共享,无状态)。
    private static ModelPart POLE;
    private static ModelPart BAR;

    private FlagRenderHelper() {
    }

    private static void ensureModel() {
        if (POLE != null) {
            return;
        }
        try {
            ModelPart root = buildMesh().bakeRoot();
            POLE = root.getChild("pole");
            BAR = root.getChild("bar");
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("FlagRender").error("[Flag] ensureModel bake 失败!", e);
        }
    }

    /** 旗杆/横杆几何照搬 vanilla(像素坐标)。旗面不用 ModelPart(UV 要铺满自定义贴图,自画)。 */
    private static LayerDefinition buildMesh() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("pole",
                CubeListBuilder.create().texOffs(44, 0).addBox(-1.0F, -30.0F, -1.0F, 2.0F, 42.0F, 2.0F),
                PartPose.ZERO);
        root.addOrReplaceChild("bar",
                CubeListBuilder.create().texOffs(0, 42).addBox(-10.0F, -32.0F, -1.0F, 20.0F, 2.0F, 2.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, TEX_W, TEX_H);
    }

    /** 渲染一面旗(站立或墙挂)。 */
    public static void render(PoseStack poseStack, MultiBufferSource bufferSource,
                              int light, int packedOverlay, boolean isWall, Direction facing,
                              int flagWidth, int flagHeight, ResourceLocation texture,
                              BlockPos blockPos, long gameTime, float partialTick) {
        ensureModel();
        poseStack.pushPose();
        if (isWall) {
            // vanilla wall banner:translate(0.5,-0.16667,0.5) → 绕Y旋到 facing → translate(0,-0.3125,-0.4375)。
            poseStack.translate(0.5F, -0.16666667F, 0.5F);
            poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            poseStack.translate(0.0F, -0.3125F, -0.4375F);
            POLE.visible = false;
        } else {
            // vanilla standing banner:translate(0.5,0.5,0.5) → 绕Y旋到 facing。
            poseStack.translate(0.5F, 0.5F, 0.5F);
            poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            POLE.visible = true;
        }
        poseStack.scale(0.6666667F, -0.6666667F, -0.6666667F);

        // 先按贴图比例算出旗面宽高(面积守恒,照搬老旗帜),横杆随旗面宽度缩放(老旗帜的 barScaleX)。
        float[] wh = computeFlagSize(flagWidth, flagHeight);
        float w = wh[0];
        float h = wh[1];
        float barScaleX = w / BASE_FLAG_WIDTH;

        VertexConsumer poleBuf = bufferSource.getBuffer(RenderType.entitySolid(POLE_TEXTURE));
        if (POLE.visible) {
            POLE.render(poseStack, poleBuf, light, packedOverlay);
        }
        poseStack.pushPose();
        poseStack.scale(barScaleX, 1.0F, 1.0F);
        BAR.render(poseStack, poleBuf, light, packedOverlay);
        poseStack.popPose();

        // 旗面:自画铺满 UV 的飘动面(用自定义贴图),挂在横杆下。
        poseStack.pushPose();
        poseStack.translate(0.0F, FLAG_OFFSET_Y, 0.0F);
        drawWavingFlag(poseStack, bufferSource, light, packedOverlay, texture, blockPos, gameTime, partialTick, w, h);
        poseStack.popPose();

        poseStack.popPose();
    }

    /** 面积守恒比例(照搬老旗帜 FlagGeometry.fromImageSize):width=sqrt(AREA*aspect)、height=AREA/width,各自夹 MIN/MAX。 */
    private static float[] computeFlagSize(int imgW, int imgH) {
        float safeW = Math.max(1.0F, imgW);
        float safeH = Math.max(1.0F, imgH);
        float aspect = safeW / safeH;
        float w = (float) Math.sqrt(FLAG_AREA * aspect);
        float h = FLAG_AREA / w;
        if (w < MIN_FLAG_WIDTH) { w = MIN_FLAG_WIDTH; h = FLAG_AREA / w; }
        else if (w > MAX_FLAG_WIDTH) { w = MAX_FLAG_WIDTH; h = FLAG_AREA / w; }
        if (h < MIN_FLAG_HEIGHT) { h = MIN_FLAG_HEIGHT; w = FLAG_AREA / h; }
        else if (h > MAX_FLAG_HEIGHT) { h = MAX_FLAG_HEIGHT; w = FLAG_AREA / h; }
        return new float[]{w, h};
    }

    /**
     * 自画旗面:UV 铺满 [0,1](自定义贴图完整显示),宽高<b>面积守恒</b>按贴图比例自适应(照搬老旗帜,不拉伸成细线),
     * 小振幅前后飘动(老旗帜的感觉)。
     *
     * <p><b>2026-06 单层:</b>只画一个 quad。{@link RenderType#entityCutoutNoCull} 不剔除背面,单面正反都可见
     * (背面看到的是镜像 UV,旗子背面本就是正面的镜像,符合直觉),无需再画第二个背面 quad。之前画正/背两层
     * 是为「有厚度消闪烁」,但单层 NoCull 无 z-fighting,两层纯属多余。
     *
     * <p><b>多人动画修复:</b>{@code gameTime} 是 long,老存档(运行很久)可达数千万 tick;{@code long→float}
     * 在超过 ~16,777,216(float 24 位尾数上限,约 9.7 天游戏时间)后无法表示相邻整数,相邻 tick 的 gameTime
     * 映射到同一 float、{@code +partialTick} 也被舍掉 → {@code sin} 输入冻结 → 旗帜不飘(单机新存档 gameTime
     * 小看不出,多人/老存档才暴露)。修复:先 {@code gameTime % WAVE_TIME_MODULO} 把数值压回 float 精度区间,
     * 再 {@code +partialTick} 转 float,飘动连续(模数周期边界每 ~13.9 小时一次、幅度极小肉眼不可见)。
     */
    private static void drawWavingFlag(PoseStack poseStack, MultiBufferSource bufferSource, int light, int packedOverlay,
                                       ResourceLocation texture, BlockPos blockPos, long gameTime, float partialTick,
                                       float w, float h) {
        VertexConsumer c = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
        // 压回 float 精度安全区间再转 float,否则老存档大 gameTime 精度丢失 → 动画冻结(多人失效根因)。
        float time = (gameTime % WAVE_TIME_MODULO) + partialTick;
        float seedPhase = ((blockPos.getX() * 7 + blockPos.getY() * 9 + blockPos.getZ() * 13) % 360) * Mth.DEG_TO_RAD;

        float left = -w * 0.5F;
        float right = w * 0.5F;
        float segH = (h - FLAG_TOP) / WAVE_SEGMENTS;

        for (int i = 0; i < WAVE_SEGMENTS; i++) {
            float topY = FLAG_TOP + segH * i;
            float botY = FLAG_TOP + segH * (i + 1);
            float vTop = (float) i / WAVE_SEGMENTS;
            float vBot = (float) (i + 1) / WAVE_SEGMENTS;
            float topWave = waveZ(time, seedPhase, (float) i / WAVE_SEGMENTS, i);
            float botWave = waveZ(time, seedPhase, (float) (i + 1) / WAVE_SEGMENTS, i + 1);

            // 单层旗面(NoCull → 正反都可见)
            quad(poseStack, c, light, packedOverlay,
                    left,  topY, FLAG_FRONT_Z + topWave, 0.0F, vTop,
                    right, topY, FLAG_FRONT_Z + topWave, 1.0F, vTop,
                    right, botY, FLAG_FRONT_Z + botWave, 1.0F, vBot,
                    left,  botY, FLAG_FRONT_Z + botWave, 0.0F, vBot,
                    0.0F, 0.0F, 1.0F);
        }
    }

    /** 小振幅前后飘动(老旗帜算法):双正弦叠加 × segFactor,旗尾摆幅略大、旗根几乎不动,振幅小不穿旗杆。 */
    private static float waveZ(float time, float seedPhase, float segFactor, int segIndex) {
        float primary = Mth.sin(time * PRIMARY_WAVE_SPEED + seedPhase + segIndex * SEGMENT_PHASE_OFFSET) * PRIMARY_AMPLITUDE;
        float secondary = Mth.sin(time * SECONDARY_WAVE_SPEED + seedPhase * 1.7F + segIndex * SEGMENT_PHASE_OFFSET * 0.8F) * SECONDARY_AMPLITUDE;
        return (primary + secondary) * segFactor;
    }

    private static void quad(PoseStack poseStack, VertexConsumer c, int light, int packedOverlay,
                             float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2,
                             float x3, float y3, float z3, float u3, float v3,
                             float x4, float y4, float z4, float u4, float v4,
                             float nx, float ny, float nz) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f m = pose.pose();
        Matrix3f n = pose.normal();
        vertex(c, m, n, x1, y1, z1, u1, v1, nx, ny, nz, light, packedOverlay);
        vertex(c, m, n, x2, y2, z2, u2, v2, nx, ny, nz, light, packedOverlay);
        vertex(c, m, n, x3, y3, z3, u3, v3, nx, ny, nz, light, packedOverlay);
        vertex(c, m, n, x4, y4, z4, u4, v4, nx, ny, nz, light, packedOverlay);
    }

    private static void vertex(VertexConsumer c, Matrix4f m, Matrix3f n,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz, int light, int packedOverlay) {
        c.vertex(m, x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(packedOverlay)
                .uv2(light)
                .normal(n, nx, ny, nz)
                .endVertex();
    }
}
